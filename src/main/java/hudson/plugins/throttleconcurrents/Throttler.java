package hudson.plugins.throttleconcurrents;

import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds throttle logic.
 */
public final class Throttler {

    @CheckForNull
    public static ThrottleJobProperty getThrottleJobProperty(Queue.Task task) {
        if (task instanceof Job) {
            Job<?,?> p = (Job<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = ((MatrixConfiguration)task).getParent();
            }
            return p.getProperty(ThrottleJobProperty.class);
        }
        return null;
    }

    public static boolean shouldBeThrottled(@Nonnull Queue.Task task, @CheckForNull ThrottleJobProperty tjp) {
        if (tjp == null) return false;
        if (!tjp.getThrottleEnabled()) return false;

        // Handle matrix options
        ThrottleMatrixProjectOptions matrixOptions = tjp.getMatrixOptions();
        if (matrixOptions == null) matrixOptions = ThrottleMatrixProjectOptions.DEFAULT;
        if (!matrixOptions.isThrottleMatrixConfigurations() && task instanceof MatrixConfiguration) {
            return false;
        }
        if (!matrixOptions.isThrottleMatrixBuilds()&& task instanceof MatrixProject) {
            return false;
        }

        // Allow throttling by default
        return true;
    }

    public static CauseOfBlockage throttleOnAllNodes(Queue.Task task) {
        return throttleOnAllNodes(task, getThrottleJobProperty(task));
    }

    public static CauseOfBlockage throttleOnAllNodes(Queue.Task task, ThrottleJobProperty tjp) {
        if (!shouldBeThrottled(task, tjp)) {
            return null;
        }
        if (Hudson.getInstance().getQueue().isPending(task)) {
            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
        }
        if (tjp.getThrottleOption().equals("project")) {
            if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                int totalRunCount = buildsOfProjectOnAllNodes(task);

                if (totalRunCount >= maxConcurrentTotal) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                }
            }
        }
        // If the project is in one or more categories...
        else if (tjp.getThrottleOption().equals("category")) {
            if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                for (String catNm : tjp.getCategories()) {
                    // Quick check that catNm itself is a real string.
                    if (catNm != null && !catNm.equals("")) {
                        List<Queue.Task> categoryTasks = ThrottleJobProperty.getCategoryTasks(catNm);

                        ThrottleJobProperty.ThrottleCategory category =
                                ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);

                        // Double check category itself isn't null
                        if (category != null) {
                            if (category.getMaxConcurrentTotal().intValue() > 0) {
                                int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                                int totalRunCount = 0;

                                for (Queue.Task catTask : categoryTasks) {
                                    if (Hudson.getInstance().getQueue().isPending(catTask)) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                    }
                                    totalRunCount += buildsOfProjectOnAllNodes(catTask);
                                }

                                if (totalRunCount >= maxConcurrentTotal) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                                }
                            }

                        }
                    }
                }
            }
        }

        return null;
    }

    public static CauseOfBlockage throttleOnNode(Node node, Queue.Task task) {
        return throttleOnNode(node, task, getThrottleJobProperty(task));
    }

    public static CauseOfBlockage throttleOnNode(Node node, Queue.Task task, ThrottleJobProperty tjp) {
        // Handle multi-configuration filters
        if (!shouldBeThrottled(task, tjp)) {
            return null;
        }

        if (tjp!=null && tjp.getThrottleEnabled()) {
            CauseOfBlockage cause = throttleOnAllNodes(task, tjp);
            if (cause != null)
                return cause;

            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task);

                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
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
                                int maxConcurrentPerNode = getMaxConcurrentPerNodeBasedOnMatchingLabels(node, category,
                                        category.getMaxConcurrentPerNode().intValue());
                                if (maxConcurrentPerNode > 0) {
                                    int runCount = 0;
                                    for (Queue.Task catTask : categoryTasks) {
                                        if (Hudson.getInstance().getQueue().isPending(catTask)) {
                                            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                        }
                                        runCount += buildsOfProjectOnNode(node, catTask);
                                    }
                                    // This would mean that there are as many or more builds currently running than are allowed.
                                    if (runCount >= maxConcurrentPerNode) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
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

    /**
     * @param node to compare labels with.
     * @param category to compare labels with.
     * @param maxConcurrentPerNode to return if node labels mismatch.
     * @return maximum concurrent number of builds per node based on matching labels, as an int.
     * @author marco.miller@ericsson.com
     */
    private static int getMaxConcurrentPerNodeBasedOnMatchingLabels(
            Node node, ThrottleJobProperty.ThrottleCategory category, int maxConcurrentPerNode)
    {
        List<ThrottleJobProperty.NodeLabeledPair> nodeLabeledPairs = category.getNodeLabeledPairs();
        int maxConcurrentPerNodeLabeledIfMatch = maxConcurrentPerNode;
        boolean nodeLabelsMatch = false;
        Set<LabelAtom> nodeLabels = node.getAssignedLabels();

        for(ThrottleJobProperty.NodeLabeledPair nodeLabeledPair: nodeLabeledPairs) {
            String throttledNodeLabel = nodeLabeledPair.getThrottledNodeLabel();
            if(!nodeLabelsMatch && !throttledNodeLabel.isEmpty()) {
                for(LabelAtom aNodeLabel: nodeLabels) {
                    String nodeLabel = aNodeLabel.getDisplayName();
                    if(nodeLabel.equals(throttledNodeLabel)) {
                        maxConcurrentPerNodeLabeledIfMatch = nodeLabeledPair.getMaxConcurrentPerNodeLabeled().intValue();
                        LOGGER.log(Level.FINE, "node labels match; => maxConcurrentPerNode'' = {0}", maxConcurrentPerNodeLabeledIfMatch);
                        nodeLabelsMatch = true;
                        break;
                    }
                }
            }
        }
        if(!nodeLabelsMatch) {
            LOGGER.fine("node labels mismatch");
        }
        return maxConcurrentPerNodeLabeledIfMatch;
    }

    private static int buildsOfProjectOnAllNodes(Queue.Task task) {
        int totalRunCount = buildsOfProjectOnNode(Hudson.getInstance(), task);

        for (Node node : Hudson.getInstance().getNodes()) {
            totalRunCount += buildsOfProjectOnNode(node, task);
        }
        return totalRunCount;
    }

    private static int buildsOnExecutor(Queue.Task task, Executor exec) {
        int runCount = 0;
        if (exec.getCurrentExecutable() != null
                && task.equals(exec.getCurrentExecutable().getParent())) {
            runCount++;
        }

        return runCount;
    }

    private static int buildsOfProjectOnNode(Node node, Queue.Task task) {
        if (!shouldBeThrottled(task, getThrottleJobProperty(task))) {
            return 0;
        }

        int runCount = 0;
        LOGGER.log(Level.FINE, "Checking for builds of {0} on node {1}", new Object[] {task.getName(), node.getDisplayName()});

        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            // Count flyweight tasks that might not consume an actual executor.
            for (Executor e : computer.getOneOffExecutors()) {
                runCount += buildsOnExecutor(task, e);
            }

            for (Executor e : computer.getExecutors()) {
                runCount += buildsOnExecutor(task, e);
            }
        }

        return runCount;
    }

    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueTaskDispatcher.class.getName());

    private Throttler() {
    }
}
