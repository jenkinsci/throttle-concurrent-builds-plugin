package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.Executables;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.SubTask;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        Snapshot snapshot = new Snapshot();
        ThrottleJobProperty tjp = getThrottleJobProperty(task, snapshot);
        
        // Handle multi-configuration filters
        if (!shouldBeThrottled(task, tjp)) {
            LOGGER.log(Level.FINE, "{0} should not be throttled", task);
            return null;
        }

        if (tjp!=null && tjp.getThrottleEnabled()) {
            CauseOfBlockage cause = canRun(task, tjp, snapshot);
            if (cause != null) return cause;

            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task, snapshot);

                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
                }
            }
            else if (tjp.getThrottleOption().equals("category")) {
                // If the project is in one or more categories...
                if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                    for (String catNm : tjp.getCategories()) {
                        // Quick check that catNm itself is a real string.
                        if (catNm != null && !catNm.equals("")) {
                            Map<String,CauseOfBlockage> categoryToBlockage = snapshot.nodeToCategoryToBlockage.get(node);
                            if (categoryToBlockage == null) {
                                categoryToBlockage = new HashMap<String,CauseOfBlockage>();
                                snapshot.nodeToCategoryToBlockage.put(node, categoryToBlockage);
                            }
                            if (categoryToBlockage.containsKey(catNm)) {
                                cause = categoryToBlockage.get(catNm);
                            } else {
                                cause = causeOfBlockageFor(node, catNm, snapshot);
                                categoryToBlockage.put(catNm, cause);
                            }
                            if (cause != null) {
                                return cause;
                            }
                        }
                    }
                }
            }
        }

        LOGGER.log(Level.FINE, "allowing {0} to take {1}", new Object[] {node.getNodeName(), task});
        return null;
    }
    private @CheckForNull CauseOfBlockage causeOfBlockageFor(Node node, String catNm, Snapshot snapshot) {
        LOGGER.log(Level.FINE, "causeOfBlockageFor {0} {1}", new Object[] {node.getNodeName(), catNm});
        if (snapshot.pendingItems == null) {
            snapshot.pendingItems = new HashSet<Queue.BuildableItem>(Jenkins.getInstance().getQueue().getPendingItems());
        }
        List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(catNm, snapshot);

        ThrottleJobProperty.ThrottleCategory category = Jenkins.getInstance().getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class).getCategoryByName(catNm);

        // Double check category itself isn't null
        if (category != null) {
            // Max concurrent per node for category
            int maxConcurrentPerNode = getMaxConcurrentPerNodeBasedOnMatchingLabels(
                node, category, category.getMaxConcurrentPerNode().intValue());
            if (maxConcurrentPerNode > 0) {
                int runCount = 0;
                for (AbstractProject<?,?> catProj : categoryProjects) {
                    if (snapshot.pendingItems.contains(catProj)) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                    }
                    runCount += buildsOfProjectOnNode(node, catProj, snapshot);
                }
                LOGGER.log(Level.FINE, "{0} builds of projects in category {1} compared to maximum {2}", new Object[] {runCount, catNm, maxConcurrentPerNode});
                // This would mean that there are as many or more builds currently running than are allowed.
                if (runCount >= maxConcurrentPerNode) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                }
            }
        }
        return null;
    }

    // TODO @Override on 1.427+
    public CauseOfBlockage canRun(Queue.Item item) {
        Snapshot snapshot = new Snapshot();
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task, snapshot);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            return canRun(item.task, tjp, snapshot);
        }
        LOGGER.log(Level.FINE, "no throttling on {0}", item.task);
        return null;
    }
    
    /**
     * Snapshot of configuration state of the system.
     * Valid within a single call to {@link #canTake(Node, Queue.Task)} or {@link #canRun(Queue.Item)}, or across calls for a few seconds.
     * Allows us to avoid repeating computations.
     */
    private static final class Snapshot {
        Snapshot() {}
        final Map<AbstractProject<?,?>,ThrottleJobProperty> projectToProperty = new IdentityHashMap<AbstractProject<?,?>,ThrottleJobProperty>(10000);
        final Map<Node,SubTask[]> nodeToSubtasks = new IdentityHashMap<Node,SubTask[]>();
        final Map<Node,Map<String,CauseOfBlockage>> nodeToCategoryToBlockage = new IdentityHashMap<Node,Map<String,CauseOfBlockage>>();
        final Map<String,List<AbstractProject<?,?>>> categoryProjects = new HashMap<String,List<AbstractProject<?,?>>>();
        Set<Queue.BuildableItem> pendingItems;
    }

    private boolean shouldBeThrottled(@Nonnull Task task, @CheckForNull ThrottleJobProperty tjp) {
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

    private CauseOfBlockage canRun(Task task, ThrottleJobProperty tjp, Snapshot snapshot) {
        if (snapshot.pendingItems == null) {
            snapshot.pendingItems = new HashSet<Queue.BuildableItem>(Jenkins.getInstance().getQueue().getPendingItems());
        }

        if (!shouldBeThrottled(task, tjp)) {
            LOGGER.log(Level.FINE, "{0} should not be throttled", task);
            return null;
        }
        if (snapshot.pendingItems.contains(task)) {
            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
        }
        if (tjp.getThrottleOption().equals("project")) {
            if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                int totalRunCount = buildsOfProjectOnAllNodes(task, snapshot);

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
                        List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(catNm, snapshot);

                        ThrottleJobProperty.ThrottleCategory category =
                            ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);

                        // Double check category itself isn't null
                        if (category != null) {
                            if (category.getMaxConcurrentTotal().intValue() > 0) {
                                int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                                int totalRunCount = 0;

                                for (AbstractProject<?,?> catProj : categoryProjects) {
                                    if (snapshot.pendingItems.contains(catProj)) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                    }
                                    totalRunCount += buildsOfProjectOnAllNodes(catProj, snapshot);
                                }

                                LOGGER.log(Level.FINE, "{0} builds of projects in category {1} among all nodes compared to maximum {2}", new Object[] {totalRunCount, catNm, maxConcurrentTotal});
                                if (totalRunCount >= maxConcurrentTotal) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                                }
                            }

                        }
                    }
                }
            }
        }

        LOGGER.log(Level.FINE, "allowing {0} to be run", task);
        return null;
    }

    private static List<AbstractProject<?,?>> getCategoryProjects(String catNm, Snapshot snapshot) {
        List<AbstractProject<?,?>> result = snapshot.categoryProjects.get(catNm);
        if (result == null) {
            LOGGER.fine("looking up projects in " + catNm);
            result = ThrottleJobProperty.getCategoryProjects(catNm);
            snapshot.categoryProjects.put(catNm, result);
        }
        return result;
    }

    @CheckForNull
    private ThrottleJobProperty getThrottleJobProperty(Task task, Snapshot snapshot) {
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = (AbstractProject<?,?>)((MatrixConfiguration)task).getParent();
            }
            if (snapshot.projectToProperty.containsKey(p)) {
                return snapshot.projectToProperty.get(p);
            }
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);
            snapshot.projectToProperty.put(p, tjp);
            return tjp;
        }
        return null;
    }

    private int buildsOfProjectOnNode(Node node, Task task, Snapshot snapshot) {
        if (!shouldBeThrottled(task, getThrottleJobProperty(task, snapshot))) {
            return 0;
        }

        int runCount = 0;

        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        SubTask[] subtasks = snapshot.nodeToSubtasks.get(node);
        if (subtasks == null) {
            subtasks = computeSubtasks(node);
            snapshot.nodeToSubtasks.put(node, subtasks);
        }
        for (SubTask subtask : subtasks) {
            if (subtask == task) {
                runCount++;
            }
        }
        LOGGER.log(Level.FINE, "{0} builds of {1} on node {2}", new Object[] {runCount, task, node.getNodeName()});
        return runCount;
    }
    private static SubTask[] computeSubtasks(Node node) {
        List<SubTask> subtasks = new ArrayList<SubTask>();
        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            // Count flyweight tasks that might not consume an actual executor.
            for (Executor e : computer.getOneOffExecutors()) {
                addSubtask(subtasks, e);
            }
            for (Executor e : computer.getExecutors()) {
                addSubtask(subtasks, e);
            }
        }
        int size = subtasks.size();
        LOGGER.log(Level.FINE, "{0} has {1} tasks running", new Object[] {node.getNodeName(), size});
        return subtasks.toArray(new SubTask[size]);
    }
    private static void addSubtask(List<SubTask> subtasks, Executor exec) {
        Queue.Executable currentExecutable = exec.getCurrentExecutable();
        if (currentExecutable != null) {
            subtasks.add(Executables.getParentOf(currentExecutable));
        }
    }

    private int buildsOfProjectOnAllNodes(Task task, Snapshot snapshot) {
        int totalRunCount = buildsOfProjectOnNode(Jenkins.getInstance(), task, snapshot);

        for (Node node : Jenkins.getInstance().getNodes()) {
            totalRunCount += buildsOfProjectOnNode(node, task, snapshot);
        }
        return totalRunCount;
    }

    /**
     * @param node to compare labels with.
     * @param category to compare labels with.
     * @param maxConcurrentPerNode to return if node labels mismatch.
     * @return maximum concurrent number of builds per node based on matching labels, as an int.
     * @author marco.miller@ericsson.com
     */
    private int getMaxConcurrentPerNodeBasedOnMatchingLabels(
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

    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueTaskDispatcher.class.getName());
}
