package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractProject;
import hudson.model.ParameterValue;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.queue.WorkUnit;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.Action;
import hudson.model.ParametersAction;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
        Task task = item.task;
        if (task instanceof MatrixConfiguration) {
            return null;
        }

        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            CauseOfBlockage cause = canRun(task, tjp);
            if (cause != null) return cause;
            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task);

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
                            List<AbstractProject<?,?>> categoryProjects = ThrottleJobProperty.getCategoryProjects(catNm);

                            ThrottleJobProperty.ThrottleCategory category =
                                ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);

                            // Double check category itself isn't null
                            if (category != null) {
                                // Max concurrent per node for category
                                int maxConcurrentPerNode = getMaxConcurrentPerNodeBasedOnMatchingLabels(
                                    node, category, category.getMaxConcurrentPerNode().intValue());
                                if (maxConcurrentPerNode > 0) {
                                    int runCount = 0;
                                    for (AbstractProject<?,?> catProj : categoryProjects) {
                                        if (Hudson.getInstance().getQueue().isPending(catProj)) {
                                            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                        }
                                        runCount += buildsOfProjectOnNode(node, catProj);
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

    // @Override on jenkins 1.427+ , but still compatible with 1.399
    public CauseOfBlockage canRun(Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            if (tjp.getlimitOneJobWithMatchingParams() && isAnotherBuildWithSameParametersRunningOnAnyNode(item)) {
                LOGGER.info("A build with matching parameters is already running.");
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_OnlyOneWithMatchingParameters());
            }
            return canRun(item.task, tjp);
        }
        return null;
    }

    public CauseOfBlockage canRun(Task task, ThrottleJobProperty tjp) {
        if (task instanceof MatrixConfiguration) {
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
                        List<AbstractProject<?,?>> categoryProjects = ThrottleJobProperty.getCategoryProjects(catNm);

                        ThrottleJobProperty.ThrottleCategory category =
                            ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(catNm);

                        // Double check category itself isn't null
                        if (category != null) {
                            if (category.getMaxConcurrentTotal().intValue() > 0) {
                                int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                                int totalRunCount = 0;

                                for (AbstractProject<?,?> catProj : categoryProjects) {
                                    if (Hudson.getInstance().getQueue().isPending(catProj)) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                    }
                                    totalRunCount += buildsOfProjectOnAllNodes(catProj);
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

    private boolean isAnotherBuildWithSameParametersRunningOnAnyNode(Queue.Item item) {
        if (isAnotherBuildWithSameParametersRunningOnNode(Hudson.getInstance(), item)) {
            return true;
        }

        for (Node node : Hudson.getInstance().getNodes()) {
            if (isAnotherBuildWithSameParametersRunningOnNode(node, item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnotherBuildWithSameParametersRunningOnNode(Node node, Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        Computer computer = node.toComputer();
        List<String> paramsToCompare = new ArrayList<String>();
        List<ParameterValue> itemParams = getParametersFromQueueItem(item);

        if (tjp.getLimitOneJobByParams().length() > 0) {
            paramsToCompare = Arrays.asList(tjp.getLimitOneJobByParams().split(","));
            itemParams = doFilterParams(paramsToCompare, itemParams);
        }

        if (computer != null) {
            for (Executor exec : computer.getExecutors()) {
                if (item != null && item.task != null) {
                    // TODO: refactor into a nameEquals helper method
                    if (exec.getCurrentExecutable() != null &&
                        exec.getCurrentExecutable().getParent() != null &&
                        exec.getCurrentExecutable().getParent().getOwnerTask() != null &&
                        exec.getCurrentExecutable().getParent().getOwnerTask().getName().equals(item.task.getName())) {
                        List<ParameterValue> executingUnitParams = getParametersFromWorkUnit(exec.getCurrentWorkUnit());
                        executingUnitParams = doFilterParams(paramsToCompare, executingUnitParams);

                        if (executingUnitParams.containsAll(itemParams)) {
                            LOGGER.info("build (" + exec.getCurrentWorkUnit() + ") with identical parameters (" +
                                        executingUnitParams + ") is already running.");
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // takes a String array containing a list of params, a List of ParameterValue objects
    // and returns a new List<ParameterValue> with only the desired params in the list.
    private List<ParameterValue> doFilterParams(List<String> params, List<ParameterValue> OriginalParams) {
        if (params.isEmpty()) {
            return OriginalParams;
        }

        List<ParameterValue> newParams = new ArrayList<ParameterValue>();

        for (ParameterValue p : OriginalParams) {
            if (params.contains(p.getName())) {
                newParams.add(p);
            }
        }
        return newParams;
    }

    public List<ParameterValue> getParametersFromWorkUnit(WorkUnit unit) {
        List<ParameterValue> paramsList = new ArrayList<ParameterValue>();

        if (unit != null && unit.context != null && unit.context.actions != null) {
            List<Action> actions = unit.context.actions;
            for (Action action : actions) {
                if (action instanceof ParametersAction) {
                    ParametersAction params = (ParametersAction) action;
                    if (params != null) {
                        paramsList = params.getParameters();
                    }
                }
            }
        }
        return paramsList;
    }

    public List<ParameterValue> getParametersFromQueueItem(Queue.Item item) {
        List<ParameterValue> paramsList = new ArrayList<ParameterValue>();

        ParametersAction params = item.getAction(ParametersAction.class);
        if (params != null) {
            paramsList = params.getParameters();
        }
        return paramsList;
    }


    @CheckForNull
    private ThrottleJobProperty getThrottleJobProperty(Task task) {
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = (AbstractProject<?,?>)((MatrixConfiguration)task).getParent();
            }
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);
            return tjp;
        }
        return null;
    }

    private int buildsOfProjectOnNode(Node node, Task task) {
        int runCount = 0;
        LOGGER.log(Level.FINE, "Checking for builds of {0} on node {1}", new Object[] {task.getName(), node.getDisplayName()});

        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            for (Executor e : computer.getExecutors()) {
                runCount += buildsOnExecutor(task, e);
            }
            if (task instanceof MatrixProject) {
                for (Executor e : computer.getOneOffExecutors()) {
                    runCount += buildsOnExecutor(task, e);
                }
            }
        }

        return runCount;
    }

    private int buildsOfProjectOnAllNodes(Task task) {
        int totalRunCount = buildsOfProjectOnNode(Hudson.getInstance(), task);

        for (Node node : Hudson.getInstance().getNodes()) {
            totalRunCount += buildsOfProjectOnNode(node, task);
        }
        return totalRunCount;
    }

    private int buildsOnExecutor(Task task, Executor exec) {
        int runCount = 0;
        if (exec.getCurrentExecutable() != null
            && exec.getCurrentExecutable().getParent() == task) {
            runCount++;
        }

        return runCount;
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
