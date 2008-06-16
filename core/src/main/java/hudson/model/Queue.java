package hudson.model;

import hudson.Util;
import hudson.model.Node.Mode;
import hudson.triggers.SafeTimerTask;
import hudson.triggers.Trigger;
import hudson.util.OneShotEvent;
import org.acegisecurity.AccessDeniedException;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.management.timer.Timer;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Build queue.
 * <p/>
 * <p/>
 * This class implements the core scheduling logic. {@link Task} represents the executable
 * task that are placed in the queue. While in the queue, it's wrapped into {@link Item}
 * so that we can keep track of additional data used for deciding what to exeucte when.
 * <p/>
 * <p/>
 * Items in queue goes through several stages, as depicted below:
 * <pre>
 * (enter) --> waitingList --+--> blockedProjects
 *                           |        ^
 *                           |        |
 *                           |        v
 *                           +--> buildables ---> (executed)
 * </pre>
 * <p/>
 * <p/>
 * In addition, at any stage, an item can be removed from the queue (for example, when the user
 * cancels a job in the queue.) See the corresponding field for their exact meanings.
 *
 * @author Kohsuke Kawaguchi
 */
public class Queue extends ResourceController {
    /**
     * Items that are waiting for its quiet period to pass.
     * <p/>
     * <p/>
     * This consists of {@link Item}s that cannot be run yet
     * because its time has not yet come.
     */
    private final Set<WaitingItem> waitingList = new TreeSet<WaitingItem>();

    /**
     * {@link Project}s that can be built immediately
     * but blocked because another build is in progress,
     * required {@link Resource}s are not available, or otherwise blocked
     * by {@link Task#isBuildBlocked()}.
     * <p/>
     * <p/>
     * Conceptually a set of {@link BlockedItem}, but we often need to look up
     * {@link BlockedItem} from {@link Task}, so organized as a map.
     */
    private final Map<Task, BlockedItem> blockedProjects = new HashMap<Task, BlockedItem>();

    /**
     * {@link Project}s that can be built immediately
     * that are waiting for available {@link Executor}.
     * <p/>
     * <p/>
     * Conceptually, this is a list of {@link BuildableItem} (FIFO list, not a set, so that
     * the item doesn't starve in the queue), but we often need to look up
     * {@link BuildableItem} from {@link Task}, so organized as a {@link LinkedHashMap}.
     */
    private final LinkedHashMap<Task, BuildableItem> buildables = new LinkedHashMap<Task, BuildableItem>();

    /**
     * Data structure created for each idle {@link Executor}.
     * This is an offer from the queue to an executor.
     * <p/>
     * <p/>
     * It eventually receives a {@link #item} to build.
     */
    private static class JobOffer {
        final Executor executor;

        /**
         * Used to wake up an executor, when it has an offered
         * {@link Project} to build.
         */
        final OneShotEvent event = new OneShotEvent();
        /**
         * The project that this {@link Executor} is going to build.
         * (Or null, in which case event is used to trigger a queue maintenance.)
         */
        BuildableItem item;

        public JobOffer(Executor executor) {
            this.executor = executor;
        }

        public void set(BuildableItem p) {
            assert this.item == null;
            this.item = p;
            event.signal();
        }

        public boolean isAvailable() {
            return item == null && !executor.getOwner().isOffline();
        }

        public Node getNode() {
            return executor.getOwner().getNode();
        }

        public boolean isNotExclusive() {
            return getNode().getMode() == Mode.NORMAL;
        }
    }

    /**
     * The executors that are currently parked while waiting for a job to run.
     */
    private final Map<Executor, JobOffer> parked = new HashMap<Executor, JobOffer>();

    public Queue() {
        // if all the executors are busy doing something, then the queue won't be maintained in
        // timely fashion, so use another thread to make sure it happens.
        new MaintainTask(this);
    }

    /**
     * Loads the queue contents that was {@link #save() saved}.
     */
    public synchronized void load() {
        // write out the contents of the queue
        try {
            File queueFile = getQueueFile();
            if (!queueFile.exists())
                return;

            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(queueFile)));
            String line;
            while ((line = in.readLine()) != null) {
                AbstractProject j = Hudson.getInstance().getItemByFullName(line, AbstractProject.class);
                if (j != null)
                    j.scheduleBuild();
            }
            in.close();
            // discard the queue file now that we are done
            queueFile.delete();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load the queue file " + getQueueFile(), e);
        }
    }

    /**
     * Persists the queue contents to the disk.
     */
    public synchronized void save() {
        // write out the contents of the queue
        try {
            PrintWriter w = new PrintWriter(new FileOutputStream(
                    getQueueFile()));
            for (Item i : getItems())
                w.println(i.task.getName());
            w.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to write out the queue file " + getQueueFile(), e);
        }
    }

    private File getQueueFile() {
        return new File(Hudson.getInstance().getRootDir(), "queue.txt");
    }

    /**
     * Schedule a new build for this project.
     *
     * @return true if the project is actually added to the queue.
     *         false if the queue contained it and therefore the add()
     *         was noop
     */
    public boolean add(AbstractProject p) {
        return add(p, p.getQuietPeriod());
    }

    /**
     * Schedules a new build with a custom quiet period.
     * <p/>
     * <p/>
     * Left for backward compatibility with &lt;1.114.
     *
     * @since 1.105
     */
    public synchronized boolean add(AbstractProject p, int quietPeriod) {
        return add((Task) p, quietPeriod);
    }

    /**
     * Schedules an execution of a task.
     *
     * @param quietPeriod Number of seconds that the task will be placed in queue.
     *                    Useful when the same task is likely scheduled for multiple
     *                    times.
     * @since 1.114
     */
    public synchronized boolean add(Task p, int quietPeriod) {
        Item item = getItem(p);
        Calendar due = new GregorianCalendar();
        due.add(Calendar.SECOND, quietPeriod);
        if (item != null) {
            if (!(item instanceof WaitingItem))
                // already in the blocked or buildable stage
                // no need to requeue
                return false;

            WaitingItem wi = (WaitingItem) item;
            if (wi.timestamp.before(due))
                return false; // no double queueing

            // allow the due date to be pulled in
            wi.timestamp = due;
        } else {
            LOGGER.fine(p.getName() + " added to queue");

            // put the item in the queue
            waitingList.add(new WaitingItem(due, p));

        }
        scheduleMaintenance();   // let an executor know that a new item is in the queue.
        return true;
    }

    /**
     * Cancels the item in the queue.
     *
     * @return true if the project was indeed in the queue and was removed.
     *         false if this was no-op.
     */
    public synchronized boolean cancel(AbstractProject<?, ?> p) {
        LOGGER.fine("Cancelling " + p.getName());
        for (Iterator itr = waitingList.iterator(); itr.hasNext();) {
            Item item = (Item) itr.next();
            if (item.task == p) {
                itr.remove();
                return true;
            }
        }
        // use bitwise-OR to make sure that both branches get evaluated all the time
        return blockedProjects.remove(p) != null | buildables.remove(p) != null;
    }

    public synchronized boolean isEmpty() {
        return waitingList.isEmpty() && blockedProjects.isEmpty() && buildables.isEmpty();
    }

    private synchronized WaitingItem peek() {
        return waitingList.iterator().next();
    }

    /**
     * Gets a snapshot of items in the queue.
     */
    public synchronized Item[] getItems() {
        Item[] r = new Item[waitingList.size() + blockedProjects.size() + buildables.size()];
        waitingList.toArray(r);
        int idx = waitingList.size();
        for (BlockedItem p : blockedProjects.values())
            r[idx++] = p;
        for (BuildableItem p : buildables.values())
            r[idx++] = p;
        return r;
    }

    public synchronized List<BuildableItem> getBuildableItems(Computer c) {
        List<BuildableItem> result = new ArrayList<BuildableItem>();
        for (BuildableItem p : buildables.values()) {
            Label l = p.task.getAssignedLabel();
            if (l != null) {
                // if a project has assigned label, it can be only built on it
                if (!l.contains(c.getNode()))
                    continue;
            }
            result.add(p);
        }
        return result;
    }

    /**
     * Gets the information about the queue item for the given project.
     *
     * @return null if the project is not in the queue.
     */
    public synchronized Item getItem(Task t) {
        BlockedItem bp = blockedProjects.get(t);
        if (bp != null)
            return bp;
        BuildableItem bi = buildables.get(t);
        if (bi != null)
            return bi;

        for (Item item : waitingList) {
            if (item.task == t)
                return item;
        }
        return null;
    }

    /**
     * Left for backward compatibility.
     *
     * @see #getItem(Task)
     */
    public synchronized Item getItem(AbstractProject p) {
        return getItem((Task) p);
    }

    /**
     * Returns true if this queue contains the said project.
     */
    public synchronized boolean contains(Task t) {
        if (blockedProjects.containsKey(t) || buildables.containsKey(t))
            return true;
        for (Item item : waitingList) {
            if (item.task == t)
                return true;
        }
        return false;
    }

    /**
     * Called by the executor to fetch something to build next.
     * <p/>
     * This method blocks until a next project becomes buildable.
     */
    public Task pop() throws InterruptedException {
        final Executor exec = Executor.currentExecutor();

        try {
            while (true) {
                final JobOffer offer = new JobOffer(exec);
                long sleep = -1;

                synchronized (this) {
                    // consider myself parked
                    assert !parked.containsKey(exec);
                    parked.put(exec, offer);

                    // reuse executor thread to do a queue maintenance.
                    // at the end of this we get all the buildable jobs
                    // in the buildables field.
                    maintain();

                    // allocate buildable jobs to executors
                    Iterator<BuildableItem> itr = buildables.values().iterator();
                    while (itr.hasNext()) {
                        BuildableItem p = itr.next();

                        // one last check to make sure this build is not blocked.
                        if (isBuildBlocked(p.task)) {
                            itr.remove();
                            blockedProjects.put(p.task, new BlockedItem(p));
                            continue;
                        }

                        JobOffer runner = choose(p.task);
                        if (runner == null)
                            // if we couldn't find the executor that fits,
                            // just leave it in the buildables list and
                            // check if we can execute other projects
                            continue;

                        // found a matching executor. use it.
                        runner.set(p);
                        itr.remove();
                    }

                    // we went over all the buildable projects and awaken
                    // all the executors that got work to do. now, go to sleep
                    // until this thread is awakened. If this executor assigned a job to
                    // itself above, the block method will return immediately.

                    if (!waitingList.isEmpty()) {
                        // wait until the first item in the queue is due
                        sleep = peek().timestamp.getTimeInMillis() - new GregorianCalendar().getTimeInMillis();
                        if (sleep < 100) sleep = 100;    // avoid wait(0)
                    }
                }

                // this needs to be done outside synchronized block,
                // so that executors can maintain a queue while others are sleeping
                if (sleep == -1)
                    offer.event.block();
                else
                    offer.event.block(sleep);

                synchronized (this) {
                    // retract the offer object
                    assert parked.get(exec) == offer;
                    parked.remove(exec);

                    // am I woken up because I have a project to build?
                    if (offer.item != null) {
                        LOGGER.fine("Pop returning " + offer.item + " for " + exec.getName());
                        // if so, just build it
                        return offer.item.task;
                    }
                    // otherwise run a queue maintenance
                }
            }
        } finally {
            synchronized (this) {
                // remove myself from the parked list
                JobOffer offer = parked.remove(exec);
                if (offer != null && offer.item != null) {
                    // we are already assigned a project,
                    // ask for someone else to build it.
                    // note that while this thread is waiting for CPU
                    // someone else can schedule this build again,
                    // so check the contains method first.
                    if (!contains(offer.item.task))
                        buildables.put(offer.item.task, offer.item);
                }

                // since this executor might have been chosen for
                // maintenance, schedule another one. Worst case
                // we'll just run a pointless maintenance, and that's
                // fine.
                scheduleMaintenance();
            }
        }
    }

    /**
     * Chooses the executor to carry out the build for the given project.
     *
     * @return null if no {@link Executor} can run it.
     */
    private JobOffer choose(Task p) {
        if (Hudson.getInstance().isQuietingDown()) {
            // if we are quieting down, don't run anything so that
            // all executors will be free.
            return null;
        }

        Label l = p.getAssignedLabel();
        if (l != null) {
            // if a project has assigned label, it can be only built on it
            for (JobOffer offer : parked.values()) {
                if (offer.isAvailable() && l.contains(offer.getNode()))
                    return offer;
            }
            return null;
        }

        // if we are a large deployment, then we will favor slaves
        boolean isLargeHudson = Hudson.getInstance().getSlaves().size() > 10;

        // otherwise let's see if the last node where this project was built is available
        // it has up-to-date workspace, so that's usually preferable.
        // (but we can't use an exclusive node)
        Node n = p.getLastBuiltOn();
        if (n != null && n.getMode() == Mode.NORMAL) {
            for (JobOffer offer : parked.values()) {
                if (offer.isAvailable() && offer.getNode() == n) {
                    if (isLargeHudson && offer.getNode() instanceof Slave)
                        // but if we are a large Hudson, then we really do want to keep the master free from builds
                        continue;
                    return offer;
                }
            }
        }

        // duration of a build on a slave tends not to have an impact on
        // the master/slave communication, so that means we should favor
        // running long jobs on slaves.
        // Similarly if we have many slaves, master should be made available
        // for HTTP requests and coordination as much as possible
        if (isLargeHudson || p.getEstimatedDuration() > 15 * 60 * 1000) {
            // consider a long job to be > 15 mins
            for (JobOffer offer : parked.values()) {
                if (offer.isAvailable() && offer.getNode() instanceof Slave && offer.isNotExclusive())
                    return offer;
            }
        }

        // lastly, just look for any idle executor
        for (JobOffer offer : parked.values()) {
            if (offer.isAvailable() && offer.isNotExclusive())
                return offer;
        }

        // nothing available
        return null;
    }

    /**
     * Checks the queue and runs anything that can be run.
     * <p/>
     * <p/>
     * When conditions are changed, this method should be invoked.
     * <p/>
     * This wakes up one {@link Executor} so that it will maintain a queue.
     */
    public synchronized void scheduleMaintenance() {
        // this code assumes that after this method is called
        // no more executors will be offered job except by
        // the pop() code.
        for (Entry<Executor, JobOffer> av : parked.entrySet()) {
            if (av.getValue().item == null) {
                av.getValue().event.signal();
                return;
            }
        }
    }

    /**
     * Checks if the given task is blocked.
     */
    private boolean isBuildBlocked(Task t) {
        return t.isBuildBlocked() || !canRun(t.getResourceList());
    }


    /**
     * Queue maintenance.
     * <p/>
     * Move projects between {@link #waitingList}, {@link #blockedProjects}, and {@link #buildables}
     * appropriately.
     */
    private synchronized void maintain() {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine("Queue maintenance started " + this);

        Iterator<BlockedItem> itr = blockedProjects.values().iterator();
        while (itr.hasNext()) {
            BlockedItem p = itr.next();
            if (!isBuildBlocked(p.task)) {
                // ready to be executed
                LOGGER.fine(p.task.getName() + " no longer blocked");
                itr.remove();
                buildables.put(p.task, new BuildableItem(p));
            }
        }

        while (!waitingList.isEmpty()) {
            WaitingItem top = peek();

            if (!top.timestamp.before(new GregorianCalendar()))
                return; // finished moving all ready items from queue

            Task p = top.task;
            if (!isBuildBlocked(p)) {
                // ready to be executed immediately
                waitingList.remove(top);
                LOGGER.fine(p.getName() + " ready to build");
                buildables.put(p, new BuildableItem(top));
            } else {
                // this can't be built now because another build is in progress
                // set this project aside.
                waitingList.remove(top);
                LOGGER.fine(p.getName() + " is blocked");
                blockedProjects.put(p, new BlockedItem(top));
            }
        }
    }

    /**
     * Task whose execution is controlled by the queue.
     * <p/>
     * {@link #equals(Object) Value equality} of {@link Task}s is used
     * to collapse two tasks into one. This is used to avoid infinite
     * queue backlog.
     */
    public interface Task extends ModelObject, ResourceActivity {
        /**
         * If this task needs to be run on a node with a particular label,
         * return that {@link Label}. Otherwise null, indicating
         * it can run on anywhere.
         */
        Label getAssignedLabel();

        /**
         * If the previous execution of this task run on a certain node
         * and this task prefers to run on the same node, return that.
         * Otherwise null.
         */
        Node getLastBuiltOn();

        /**
         * Returns true if the execution should be blocked
         * for temporary reasons.
         * <p/>
         * <p/>
         * This can be used to define mutual exclusion that goes beyond
         * {@link #getResourceList()}.
         */
        boolean isBuildBlocked();

        /**
         * When {@link #isBuildBlocked()} is true, this method returns
         * human readable description of why the build is blocked.
         * Used for HTML rendering.
         */
        String getWhyBlocked();

        /**
         * Unique name of this task.
         *
         * @see hudson.model.Item#getName()
         *      TODO: this doesn't make sense anymore. remove it.
         */
        String getName();

        /**
         * @see hudson.model.Item#getFullDisplayName()
         */
        String getFullDisplayName();

        /**
         * Estimate of how long will it take to execute this task.
         * Measured in milliseconds.
         *
         * @return -1 if it's impossible to estimate.
         */
        long getEstimatedDuration();

        /**
         * Creates {@link Executable}, which performs the actual execution of the task.
         */
        Executable createExecutable() throws IOException;

        /**
         * Checks the permission to see if the current user can abort this executable.
         * Returns normally from this method if it's OK.
         *
         * @throws AccessDeniedException if the permission is not granted.
         */
        void checkAbortPermission();

        /**
         * Works just like {@link #checkAbortPermission()} except it indicates the status by a return value,
         * instead of exception.
         */
        boolean hasAbortPermission();
    }

    public interface Executable extends Runnable {
        /**
         * Task from which this executable was created.
         * Never null.
         */
        Task getParent();

        /**
         * Called by {@link Executor} to perform the task
         */
        void run();
    }

    /**
     * Item in a queue.
     */
    @ExportedBean(defaultVisibility = 999)
    public abstract class Item {
        /**
         * Project to be built.
         */
        public final Task task;

        /**
         * Build is blocked because another build is in progress,
         * required {@link Resource}s are not available, or otherwise blocked
         * by {@link Task#isBuildBlocked()}.
         */
        @Exported
        public boolean isBlocked() {
            return this instanceof BlockedItem;
        }

        /**
         * Build is waiting the executor to become available.
         * This flag is only used in {@link Queue#getItems()} for
         * 'pseudo' items that are actually not really in the queue.
         */
        @Exported
        public boolean isBuildable() {
            return this instanceof BuildableItem;
        }

        protected Item(Task project) {
            this.task = project;
        }

        /**
         * Gets a human-readable status message describing why it's in the queu.
         */
        @Exported
        public abstract String getWhy();

        public boolean hasCancelPermission() {
            return task.hasAbortPermission();
        }
    }

    /**
     * {@link Item} in the {@link Queue#waitingList} stage.
     */
    public final class WaitingItem extends Item implements Comparable<WaitingItem> {
        /**
         * This item can be run after this time.
         */
        @Exported
        public Calendar timestamp;

        /**
         * Unique number of this {@link WaitingItem}.
         * Used to differentiate {@link WaitingItem}s with the same due date, to make it sortable.
         */
        public final int id;

        WaitingItem(Calendar timestamp, Task project) {
            super(project);
            this.timestamp = timestamp;
            synchronized (Queue.this) {
                this.id = iota++;
            }
        }

        public int compareTo(WaitingItem that) {
            int r = this.timestamp.getTime().compareTo(that.timestamp.getTime());
            if (r != 0) return r;

            return this.id - that.id;
        }

        @Override
        public String getWhy() {
            long diff = timestamp.getTimeInMillis() - System.currentTimeMillis();
            if (diff > 0)
                return Messages.Queue_InQuietPeriod(Util.getTimeSpanString(diff));
            else
                return Messages.Queue_Unknown();
        }
    }

    /**
     * Common part between {@link BlockedItem} and {@link BuildableItem}.
     */
    public abstract class NotWaitingItem extends Item {
        /**
         * When did this job exit the {@link Queue#waitingList} phase?
         */
        @Exported
        public final long buildableStartMilliseconds;

        protected NotWaitingItem(WaitingItem wi) {
            super(wi.task);
            buildableStartMilliseconds = System.currentTimeMillis();
        }

        protected NotWaitingItem(NotWaitingItem ni) {
            super(ni.task);
            buildableStartMilliseconds = ni.buildableStartMilliseconds;
        }
    }

    /**
     * {@link Item} in the {@link Queue#blockedProjects} stage.
     */
    public final class BlockedItem extends NotWaitingItem {
        public BlockedItem(WaitingItem wi) {
            super(wi);
        }

        public BlockedItem(NotWaitingItem ni) {
            super(ni);
        }

        @Override
        public String getWhy() {
            ResourceActivity r = getBlockingActivity(task);
            if (r != null) {
                if (r == task) // blocked by itself, meaning another build is in progress
                    return Messages.Queue_InProgress();
                return Messages.Queue_BlockedBy(r.getDisplayName());
            }
            return task.getWhyBlocked();
        }
    }

    /**
     * {@link Item} in the {@link Queue#buildables} stage.
     */
    public final class BuildableItem extends NotWaitingItem {
        public BuildableItem(WaitingItem wi) {
            super(wi);
        }

        public BuildableItem(NotWaitingItem ni) {
            super(ni);
        }

        @Override
        public String getWhy() {
            Label node = task.getAssignedLabel();
            Hudson hudson = Hudson.getInstance();
            if (hudson.getSlaves().isEmpty())
                node = null;    // no master/slave. pointless to talk about nodes

            String name = null;
            if (node != null) {
                name = node.getName();
                if (node.isOffline()) {
                    if (node.getNodes().size() > 1)
                        return "All nodes of label '" + name + "' is offline";
                    else
                        return name + " is offline";
                }
            }

            return "Waiting for next available executor" + (name == null ? "" : " on " + name);
        }
    }

    /**
     * Unique number generator
     */
    private int iota = 0;

    private static final Logger LOGGER = Logger.getLogger(Queue.class.getName());

    /**
     * Regularly invokes {@link Queue#maintain()} and clean itself up when
     * {@link Queue} gets GC-ed.
     */
    private static class MaintainTask extends SafeTimerTask {
        private final WeakReference<Queue> queue;

        MaintainTask(Queue queue) {
            this.queue = new WeakReference<Queue>(queue);

            long interval = 5 * Timer.ONE_SECOND;
            Trigger.timer.schedule(this, interval, interval);
        }

        protected void doRun() {
            Queue q = queue.get();
            if (q != null)
                q.maintain();
            else
                cancel();
        }
    }
}
