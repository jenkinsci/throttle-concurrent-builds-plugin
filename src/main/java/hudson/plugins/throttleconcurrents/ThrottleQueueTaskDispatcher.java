package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.ParameterValue;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.Run;
import hudson.model.queue.WorkUnit;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.plugins.throttleconcurrents.pipeline.ThrottleStep;
import hudson.security.ACL;
import hudson.security.NotSerilizableSecurityContext;
import hudson.model.Action;
import hudson.model.ParametersAction;
import hudson.model.queue.SubTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearBlockHoppingScanner;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution.PlaceholderTask;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (Jenkins.getAuthentication() == ACL.SYSTEM) {
            return canTakeImpl(node, task);
        }
        
        // Throttle-concurrent-builds requires READ permissions for all projects.
        SecurityContext orig = SecurityContextHolder.getContext();
        NotSerilizableSecurityContext auth = new NotSerilizableSecurityContext();
        auth.setAuthentication(ACL.SYSTEM);
        SecurityContextHolder.setContext(auth);
        
        try {
            return canTakeImpl(node, task);
        } finally {
            SecurityContextHolder.setContext(orig);
        }
    }
    
    private CauseOfBlockage canTakeImpl(Node node, Task task) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        List<String> pipelineCategories = categoriesForPipeline(task);

        // Handle multi-configuration filters
        if (!shouldBeThrottled(task, tjp) && pipelineCategories.isEmpty()) {
            return null;
        }

        if (!pipelineCategories.isEmpty() || (tjp!=null && tjp.getThrottleEnabled())) {
            CauseOfBlockage cause = canRunImpl(task, tjp, pipelineCategories);
            if (cause != null) {
            	return cause;
            }
            if (tjp != null) {
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
                    return throttleCheckForCategoriesOnNode(node, jenkins, tjp.getCategories());
                }
            } else if (!pipelineCategories.isEmpty()) {
                return throttleCheckForCategoriesOnNode(node, jenkins, pipelineCategories);
            }
        }

        return null;
    }

    private CauseOfBlockage throttleCheckForCategoriesOnNode(Node node, Jenkins jenkins, List<String> categories) {
        // If the project is in one or more categories...
        if (!categories.isEmpty()) {
            for (String catNm : categories) {
                // Quick check that catNm itself is a real string.
                if (catNm != null && !catNm.equals("")) {
                    List<Task> categoryTasks = ThrottleJobProperty.getCategoryTasks(catNm);

                    ThrottleJobProperty.ThrottleCategory category =
                            ThrottleJobProperty.fetchDescriptor().getCategoryByName(catNm);

                    // Double check category itself isn't null
                    if (category != null) {
                        int runCount = 0;
                        // Max concurrent per node for category
                        int maxConcurrentPerNode = getMaxConcurrentPerNodeBasedOnMatchingLabels(
                                node, category, category.getMaxConcurrentPerNode().intValue());
                        if (maxConcurrentPerNode > 0) {
                            for (Task catTask : categoryTasks) {
                                if (jenkins.getQueue().isPending(catTask)) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                }
                                runCount += buildsOfProjectOnNode(node, catTask);
                            }
                            Map<String,List<FlowNode>> throttledPipelines = ThrottleJobProperty.getThrottledPipelineRunsForCategory(catNm);
                            for (Map.Entry<String,List<FlowNode>> entry : throttledPipelines.entrySet()) {
                                if (hasPendingPipelineForCategory(entry.getValue())) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                }
                                Run<?,?> r = Run.fromExternalizableId(entry.getKey());
                                if (r != null) {
                                    List<FlowNode> flowNodes = entry.getValue();
                                    if (r.isBuilding()) {
                                        runCount += pipelinesOnNode(node, r, flowNodes);
                                    }
                                }
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
        return null;
    }

    private boolean hasPendingPipelineForCategory(List<FlowNode> flowNodes) {
        for (Queue.BuildableItem pending : Jenkins.getActiveInstance().getQueue().getPendingItems()) {
            if (isTaskThrottledPipeline(pending.task, flowNodes)) {
                return true;
            }
        }

        return false;
    }

    // @Override on jenkins 4.127+ , but still compatible with 1.399
    public CauseOfBlockage canRun(Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        List<String> pipelineCategories = categoriesForPipeline(item.task);

        if (!pipelineCategories.isEmpty() || (tjp!=null && tjp.getThrottleEnabled())) {
            if (tjp != null && tjp.isLimitOneJobWithMatchingParams() && isAnotherBuildWithSameParametersRunningOnAnyNode(item)) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_OnlyOneWithMatchingParameters());
            }
            return canRun(item.task, tjp, pipelineCategories);
        }
        return null;
    }
    
    @Nonnull
    private ThrottleMatrixProjectOptions getMatrixOptions(Task task) {
        ThrottleJobProperty tjp = getThrottleJobProperty(task);

        if (tjp == null){
        	return ThrottleMatrixProjectOptions.DEFAULT;       
        }
        ThrottleMatrixProjectOptions matrixOptions = tjp.getMatrixOptions();
        return matrixOptions != null ? matrixOptions : ThrottleMatrixProjectOptions.DEFAULT;
    }
    
    private boolean shouldBeThrottled(@Nonnull Task task, @CheckForNull ThrottleJobProperty tjp) {
       if (tjp == null) {
    	   return false;
       }
       if (!tjp.getThrottleEnabled()) { 
    	   return false;
       }
       
       // Handle matrix options
       ThrottleMatrixProjectOptions matrixOptions = tjp.getMatrixOptions();
       if (matrixOptions == null) {
    	   matrixOptions = ThrottleMatrixProjectOptions.DEFAULT;
       }
       if (!matrixOptions.isThrottleMatrixConfigurations() && task instanceof MatrixConfiguration) {
            return false;
       } 
       if (!matrixOptions.isThrottleMatrixBuilds()&& task instanceof MatrixProject) {
            return false;
       }
       
       // Allow throttling by default
       return true;
    }

    public CauseOfBlockage canRun(Task task, ThrottleJobProperty tjp, List<String> pipelineCategories) {
        if (Jenkins.getAuthentication() == ACL.SYSTEM) {
            return canRunImpl(task, tjp, pipelineCategories);
        }
        
        // Throttle-concurrent-builds requires READ permissions for all projects.
        SecurityContext orig = SecurityContextHolder.getContext();
        NotSerilizableSecurityContext auth = new NotSerilizableSecurityContext();
        auth.setAuthentication(ACL.SYSTEM);
        SecurityContextHolder.setContext(auth);
        
        try {
            return canRunImpl(task, tjp, pipelineCategories);
        } finally {
            SecurityContextHolder.setContext(orig);
        }
    }
    
    private CauseOfBlockage canRunImpl(Task task, ThrottleJobProperty tjp, List<String> pipelineCategories) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        if (!shouldBeThrottled(task, tjp) && pipelineCategories.isEmpty()) {
            return null;
        }
        if (jenkins.getQueue().isPending(task)) {
            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
        }
        if (tjp != null) {
            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                    int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                    int totalRunCount = buildsOfProjectOnAllNodes(task);

                    if (totalRunCount >= maxConcurrentTotal) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                    }
                }
            } else if (tjp.getThrottleOption().equals("category")) {
                return throttleCheckForCategoriesAllNodes(jenkins, tjp.getCategories());
            }
        } else if (!pipelineCategories.isEmpty()) {
            return throttleCheckForCategoriesAllNodes(jenkins, pipelineCategories);
        }

        return null;
    }

    private CauseOfBlockage throttleCheckForCategoriesAllNodes(Jenkins jenkins, @Nonnull List<String> categories) {
        for (String catNm : categories) {
            // Quick check that catNm itself is a real string.
            if (catNm != null && !catNm.equals("")) {
                List<Task> categoryTasks = ThrottleJobProperty.getCategoryTasks(catNm);

                ThrottleJobProperty.ThrottleCategory category =
                        ThrottleJobProperty.fetchDescriptor().getCategoryByName(catNm);

                // Double check category itself isn't null
                if (category != null) {
                    if (category.getMaxConcurrentTotal().intValue() > 0) {
                        int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                        int totalRunCount = 0;

                        for (Task catTask : categoryTasks) {
                            if (jenkins.getQueue().isPending(catTask)) {
                                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                            }
                            totalRunCount += buildsOfProjectOnAllNodes(catTask);
                        }
                        Map<String,List<FlowNode>> throttledPipelines = ThrottleJobProperty.getThrottledPipelineRunsForCategory(catNm);
                        for (Map.Entry<String,List<FlowNode>> entry : throttledPipelines.entrySet()) {
                            if (hasPendingPipelineForCategory(entry.getValue())) {
                                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                            }
                            Run<?,?> r = Run.fromExternalizableId(entry.getKey());
                            if (r != null) {
                                List<FlowNode> flowNodes = entry.getValue();
                                if (r.isBuilding()) {
                                    totalRunCount += pipelinesOnAllNodes(r, flowNodes);
                                }
                            }
                        }

                        if (totalRunCount >= maxConcurrentTotal) {
                            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                        }
                    }

                }
            }
        }
        return null;
    }

    private boolean isAnotherBuildWithSameParametersRunningOnAnyNode(Queue.Item item) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        if (isAnotherBuildWithSameParametersRunningOnNode(jenkins, item)) {
            return true;
        }

        for (Node node : jenkins.getNodes()) {
            if (isAnotherBuildWithSameParametersRunningOnNode(node, item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnotherBuildWithSameParametersRunningOnNode(Node node, Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp == null) {
            // If the property has been ocasionally deleted by this call, 
            // it does not make sense to limit the throttling by parameter.
            return false;
        }
        Computer computer = node.toComputer();
        List<String> paramsToCompare = tjp.getParamsToCompare();
        List<ParameterValue> itemParams = getParametersFromQueueItem(item);

        if (paramsToCompare.size() > 0) {
            itemParams = doFilterParams(paramsToCompare, itemParams);
        }

        if (computer != null) {
            for (Executor exec : computer.getExecutors()) {
                // TODO: refactor into a nameEquals helper method
                final Queue.Executable currentExecutable = exec.getCurrentExecutable();
                final SubTask parentTask = currentExecutable != null ? currentExecutable.getParent() : null;
                if (currentExecutable != null &&
                        parentTask.getOwnerTask().getName().equals(item.task.getName())) {
                    List<ParameterValue> executingUnitParams = getParametersFromWorkUnit(exec.getCurrentWorkUnit());
                    executingUnitParams = doFilterParams(paramsToCompare, executingUnitParams);

                    if (executingUnitParams.containsAll(itemParams)) {
                        LOGGER.log(Level.FINE, "build (" + exec.getCurrentWorkUnit() +
                                ") with identical parameters (" +
                                executingUnitParams + ") is already running.");
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Filter job parameters to only include parameters used for throttling
     * @param params
     * @param OriginalParams
     * @return
     */
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
                    paramsList = ((ParametersAction)action).getParameters();
                }
            }
        }
        return paramsList;
    }

    public List<ParameterValue> getParametersFromQueueItem(Queue.Item item) {
        List<ParameterValue> paramsList;

        ParametersAction params = item.getAction(ParametersAction.class);
        if (params != null) {
            paramsList = params.getParameters();
        }
        else
        {
            paramsList  = new ArrayList<ParameterValue>();
        }
        return paramsList;
    }

    @Nonnull
    private List<String> categoriesForPipeline(Task task) {
        if (task instanceof PlaceholderTask) {
            PlaceholderTask placeholderTask = (PlaceholderTask)task;
            Run<?, ?> r = placeholderTask.run();
            if (r != null) {
                Map<String, List<String>> categoriesByFlowNode = ThrottleJobProperty.getCategoriesForRunByFlowNode(r);
                if (!categoriesByFlowNode.isEmpty()) {
                    try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
                        FlowNode firstThrottle = firstThrottleStartNode(placeholderTask.getNode());
                        if (firstThrottle != null) {
                            List<String> categories = categoriesByFlowNode.get(firstThrottle.getId());
                            if (categories != null) {
                                return categories;
                            }
                        }
                    } catch (IOException | InterruptedException e) {
                        LOGGER.log(Level.WARNING, "Error getting categories for pipeline {0}: {1}",
                                new Object[] {task.getDisplayName(), e});
                        return new ArrayList<>();
                    }
                }
            }
        }
        return new ArrayList<>();
    }

    @CheckForNull
    private ThrottleJobProperty getThrottleJobProperty(Task task) {
        if (task instanceof Job) {
            Job<?,?> p = (Job<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = ((MatrixConfiguration)task).getParent();
            }
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);
            return tjp;
        }
        return null;
    }

    private int pipelinesOnNode(@Nonnull Node node, @Nonnull Run<?,?> run, @Nonnull List<FlowNode> flowNodes) {
        int runCount = 0;
        LOGGER.log(Level.FINE, "Checking for pipelines of {0} on node {1}", new Object[] {run.getDisplayName(), node.getDisplayName()});

        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            // Don't count flyweight tasks that might not consume an actual executor, unlike with builds.
            for (Executor e : computer.getExecutors()) {
                runCount += pipelinesOnExecutor(run, e, flowNodes);
            }
        }

        return runCount;
    }

    private int pipelinesOnAllNodes(@Nonnull Run<?,?> run, @Nonnull List<FlowNode> flowNodes) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        int totalRunCount = pipelinesOnNode(jenkins, run, flowNodes);

        for (Node node : jenkins.getNodes()) {
            totalRunCount += pipelinesOnNode(node, run, flowNodes);
        }
        return totalRunCount;
    }

    private int buildsOfProjectOnNode(Node node, Task task) {
        if (!shouldBeThrottled(task, getThrottleJobProperty(task))) {
            return 0;
        }

        // Note that this counts flyweight executors in its calculation, which may be a problem if flyweight executors
        // are being leaked by other plugins.
        return buildsOfProjectOnNodeImpl(node, task);
    }

    private int buildsOfProjectOnAllNodes(Task task) {
        if (!shouldBeThrottled(task, getThrottleJobProperty(task))) {
            return 0;
        }

        // Note that we can't use WorkflowJob.class because it is not on this plugin's classpath.
        if (task.getClass().getName().equals("org.jenkinsci.plugins.workflow.job.WorkflowJob")) {
            return buildsOfPipelineJob(task);
        } else {
            return buildsOfProjectOnAllNodesImpl(task);
        }
    }

    private int buildsOfPipelineJob(Task task) {
        int runCount = 0;

        for (FlowExecution flowExecution : FlowExecutionList.get()) {
            try {
                final Queue.Executable executable = flowExecution.getOwner().getExecutable();
                if (executable != null && task.equals(executable.getParent())) {
                    runCount++;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error getting number of builds for pipeline {0}: {1}", new Object[] {task.getDisplayName(), e});
            }
        }

        return runCount;
    }

    private int buildsOfProjectOnNodeImpl(Node node, Task task) {
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

    private int buildsOfProjectOnAllNodesImpl(Task task) {
        final Jenkins jenkins = Jenkins.getActiveInstance();
        int totalRunCount = buildsOfProjectOnNode(jenkins, task);

        for (Node node : jenkins.getNodes()) {
            totalRunCount += buildsOfProjectOnNodeImpl(node, task);
        }
        return totalRunCount;
    }

    private int buildsOnExecutor(Task task, Executor exec) {
        int runCount = 0;
        final Queue.Executable currentExecutable = exec.getCurrentExecutable();
        if (currentExecutable != null && task.equals(currentExecutable.getParent())) {
            runCount++;
        }

        return runCount;
    }

    /**
     * Get the count of currently executing {@link PlaceholderTask}s on a given {@link Executor} for a given {@link Run}
     * and list of {@link FlowNode}s in that run that have been throttled.
     *
     * @param run The {@link Run} we care about.
     * @param exec The {@link Executor} we're checking on.
     * @param flowNodes The list of {@link FlowNode}s associated with that run that have been throttled with a particular
     *                  category.
     * @return 1 if there's something currently executing on that executor and it's of that run and one of the provided
     * flow nodes, 0 otherwise.
     */
    private int pipelinesOnExecutor(@Nonnull Run<?,?> run, @Nonnull Executor exec, @Nonnull List<FlowNode> flowNodes) {
        final Queue.Executable currentExecutable = exec.getCurrentExecutable();
        if (currentExecutable != null) {
            SubTask parent = currentExecutable.getParent();
            if (parent instanceof PlaceholderTask) {
                PlaceholderTask task = (PlaceholderTask)parent;
                if (run.equals(task.run())) {
                    if (isTaskThrottledPipeline(task, flowNodes)) {
                        return 1;
                    }
                }
            }
        }

        return 0;
    }

    private boolean isTaskThrottledPipeline(Task origTask, List<FlowNode> flowNodes) {
        if (origTask instanceof PlaceholderTask) {
            PlaceholderTask task = (PlaceholderTask)origTask;
            try {
                FlowNode firstThrottle = firstThrottleStartNode(task.getNode());
                return firstThrottle != null && flowNodes.contains(firstThrottle);
            } catch (IOException | InterruptedException e) {
                    // TODO: do something?
            }
        }

        return false;
    }

    /**
     * Given a {@link FlowNode}, find the {@link FlowNode} most directly enclosing this one that comes from a {@link ThrottleStep}.
     *
     * @param inner The inner {@link FlowNode}
     * @return The most immediate enclosing {@link FlowNode} of the inner one that is associated with {@link ThrottleStep}. May be null.
     */
    @CheckForNull
    private FlowNode firstThrottleStartNode(@CheckForNull FlowNode inner) {
        if (inner != null) {
            LinearBlockHoppingScanner scanner = new LinearBlockHoppingScanner();
            scanner.setup(inner);
            for (FlowNode enclosing : scanner) {
                if (enclosing != null &&
                        enclosing instanceof BlockStartNode &&
                        enclosing instanceof StepNode &&
                        // There are two BlockStartNodes (aka StepStartNodes) for ThrottleStep, so make sure we get the
                        // first one of those two, which will not have BodyInvocationAction.class on it.
                        enclosing.getAction(BodyInvocationAction.class) == null) {
                    // Check if this is a *different* throttling node.
                    StepDescriptor desc = ((StepNode) enclosing).getDescriptor();
                    if (desc != null && desc.getClass().equals(ThrottleStep.DescriptorImpl.class)) {
                        return enclosing;
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
