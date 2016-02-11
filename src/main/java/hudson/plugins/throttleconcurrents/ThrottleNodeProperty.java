package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link hudson.model.queue.QueueTaskDispatcher} is not called for flyweights, so we can't use it for throttling.
 * Instead, we provide a hidden property (this one) for each node, and use
 * {@link NodeProperty#canTake(Queue.BuildableItem)} for throttling flyweights.
 */
public class ThrottleNodeProperty extends NodeProperty<Node> {

    private static final long NO_ITEM = -1;

    /**
     * The ID of an item being scheduled right now. We schedule one flyweight task at a time.
     */
    private static final AtomicLong FLYWEIGHT_ITEM_IN_QUEUE = new AtomicLong(NO_ITEM);

    @DataBoundConstructor
    public ThrottleNodeProperty() {
    }

    @Extension
    public static class DescriptorImpl extends NodePropertyDescriptor {
        @Override
        public String getDisplayName() {
            // Display name is null to hide the property from users
            return null;
        }

        @Override
        public boolean isApplicableAsGlobal() {
            return true;
        }

        @Override
        public boolean isApplicable(Class<? extends Node> targetType) {
            return true;
        }
    }

    @Extension
    public static final class QueueListenerImpl extends QueueListener {
        @Override
        public void onLeft(Queue.LeftItem li) {
            // When an item leaves the queue, it means it either cancelled or started execution. Either way, we need to
            // release the lock
            releaseLock(li);
        }
    }

    private static boolean acquireLock(Queue.Item item) {
        final long key = item.getId();
        return !(key != FLYWEIGHT_ITEM_IN_QUEUE.get() && !FLYWEIGHT_ITEM_IN_QUEUE.compareAndSet(NO_ITEM, key));
    }

    private static void releaseLock(Queue.Item item) {
        FLYWEIGHT_ITEM_IN_QUEUE.compareAndSet(item.getId(), NO_ITEM);
    }

    private boolean containsThisProperty(Node node) {
        for (NodeProperty<?> p : node.getNodeProperties()) {
            if (p.equals(this)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@link Node} which holds this property instance, or {@code null}, if not found.
     *
     * @return {@link Node} which holds this property instance, or {@code null}, if not found.
     */
    private Node findThisNode() {
        // Is there a better way to get the node?
        Jenkins j = Jenkins.getActiveInstance();
        if (containsThisProperty(j)) {
            return j;
        }
        for (Node n : j.getNodes()) {
            if (containsThisProperty(n)) {
                return n;
            }
        }
        return null;
    }

    @Override
    public CauseOfBlockage canTake(Queue.BuildableItem item) {
        Queue.Task task = item.task;
        if (!(task instanceof Queue.FlyweightTask)) {
            // no point in checking heavyweight tasks here
            return null;
        }

        Node node = findThisNode();
        if (null == node) {
            return null;
        }

        ThrottleJobProperty tjp = Throttler.getThrottleJobProperty(task);

        if (!Throttler.shouldBeThrottled(task, tjp)) {
            return null;
        }

        if (!acquireLock(item)) {
            // Failed to get the lock -> the process of searching an executor for another flyweight job is not
            // finished. Need to wait.
            return new ScheduleConflict();
        }

        try {
            CauseOfBlockage ret = Throttler.throttleOnNode(node, task, tjp);
            if (null != ret) {
                releaseLock(item);
            }
            return ret;
        } catch (RuntimeException e) {
            releaseLock(item);
            throw e;
        }
    }

    private static final class ScheduleConflict extends CauseOfBlockage {
        @Override
        public String getShortDescription() {
            return "Scheduling conflict, waiting";
        }
    }
}
