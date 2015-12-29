package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.List;
import java.util.logging.Logger;

public class ThrottleNodeProperty extends NodeProperty<Node> {

    private static final Logger LOGGER = Logger.getLogger(ThrottleNodeProperty.class.getName());
    private static final Object SYNC = new Object();

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

        ThrottleJobProperty tjp = ThrottleQueueTaskDispatcher.getThrottleJobProperty(task);

        // Handle multi-configuration filters
        if (!ThrottleQueueTaskDispatcher.shouldBeThrottled(task, tjp)) {
            return null;
        }

        synchronized (SYNC) {
            if (tjp.getThrottleOption().equals("project")) {
                try {
                    if (tjp.getMaxConcurrentPerNode() > 0) {
                        int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode();
                        int runCount = ThrottleQueueTaskDispatcher.buildsOfProjectOnNode(node, task);
                        LOGGER.warning("NP runCount: " + runCount + ", max: " + maxConcurrentPerNode);

                        // This would mean that there are as many or more builds currently running than are allowed.
                        if (runCount >= maxConcurrentPerNode) {
                            return CauseOfBlockage
                                    .fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (tjp.getThrottleOption().equals("category")) {
                // If the project is in one or more categories...
                if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                    for (String catNm : tjp.getCategories()) {
                        // Quick check that catNm itself is a real string.
                        if (catNm != null && !catNm.equals("")) {
                            List<Queue.Task> categoryTasks = ThrottleJobProperty.getCategoryTasks(catNm);

                            ThrottleJobProperty.ThrottleCategory category =
                                    ((ThrottleJobProperty.DescriptorImpl) tjp.getDescriptor()).getCategoryByName(catNm);

                            // Double check category itself isn't null
                            if (category != null) {
                                // Max concurrent per node for category
                                int maxConcurrentPerNode = ThrottleQueueTaskDispatcher
                                        .getMaxConcurrentPerNodeBasedOnMatchingLabels(node, category,
                                                category.getMaxConcurrentPerNode());
                                if (maxConcurrentPerNode > 0) {
                                    int runCount = 0;
                                    for (Queue.Task catTask : categoryTasks) {
                                        if (Jenkins.getInstance().getQueue().isPending(catTask)) {
                                            return CauseOfBlockage
                                                    .fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                        }
                                        runCount += ThrottleQueueTaskDispatcher.buildsOfProjectOnNode(node, catTask);
                                    }
                                    // This would mean that there are as many or more builds currently running than are allowed.
                                    if (runCount >= maxConcurrentPerNode) {
                                        return CauseOfBlockage.fromMessage(
                                                Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }
}
