package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.atomic.AtomicLong;

public class ThrottleNodeProperty extends NodeProperty<Node> {

    private static final long NO_TASK = -1;
    private static final AtomicLong FLYWEIGHT_TASK_IN_QUEUE = new AtomicLong(NO_TASK);

    @DataBoundConstructor
    public ThrottleNodeProperty() {
    }

    @Extension
    public static class DescriptorImpl extends NodePropertyDescriptor {
        @Override
        public String getDisplayName() {
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

    private static boolean acquireLock(Queue.Item item) {
        final long key = item.getId();
        return !(key != FLYWEIGHT_TASK_IN_QUEUE.get() && !FLYWEIGHT_TASK_IN_QUEUE.compareAndSet(NO_TASK, key));
    }

    private static void releaseLock(Queue.Item item) {
        FLYWEIGHT_TASK_IN_QUEUE.compareAndSet(item.getId(), NO_TASK);
    }

    static void onTaskLeftQueue(Queue.Item item) {
        releaseLock(item);
    }

    private boolean containsThisProperty(Node node) {
        for (NodeProperty<?> p : node.getNodeProperties()) {
            if (p.equals(this)) {
                return true;
            }
        }
        return false;
    }

    private Node findThisNode() {
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
            return new CauseOfBlockage() {
                @Override
                public String getShortDescription() {
                    return "Scheduling conflict, waiting";
                }
            };
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
}
