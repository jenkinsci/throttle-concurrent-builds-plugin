package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.BuildableItem;
import hudson.model.Queue.Task;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, BuildableItem item) {
    	Task task = item.task;
        if (task instanceof MatrixConfiguration) {
            return null;
        }

        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            CauseOfBlockage cause = canRun(item, tjp);
            if (cause != null) return cause;

            if (tjp.getThrottleOption().equals("project")) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, item.task, item, tjp.getMatchParamsArray());

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
                            List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(catNm);

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
                                        runCount += buildsOfProjectOnNode(node, catProj, item, tjp.getMatchParamsArray());
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

    // @Override on jenkins 4.127+ , but still compatible with 1.399
    public CauseOfBlockage canRun(Queue.Item item) {
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            return canRun(item, tjp);
        }
        return null;
    }

    public CauseOfBlockage canRun(Queue.Item item, ThrottleJobProperty tjp) {
    	Task task  = item.task;
        if (task instanceof MatrixConfiguration) {
            return null;
        }
        if (Hudson.getInstance().getQueue().isPending(task)) {
            return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
        }
        if (tjp.getThrottleOption().equals("project")) {
            if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                int totalRunCount = buildsOfProjectOnAllNodes(item.task, item, tjp.getMatchParamsArray());

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
                        List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(catNm);

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
                                    totalRunCount += buildsOfProjectOnAllNodes(catProj, item, tjp.getMatchParamsArray());
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


    private int buildsOnExecutor(Task taskToMatch, Queue.Item  queuedItem, Executor exec, ArrayList<String> matchParams) {
        int runCount = 0;
        if (exec.getCurrentExecutable() != null
            && exec.getCurrentExecutable().getParent() == taskToMatch) {
        	if(matchParams.isEmpty()){
        		runCount++;
        	} else {
        		/* We need to check if the params actually match */
        		exec.getCurrentExecutable().getParent();
        		if(! (exec.getCurrentExecutable() instanceof AbstractBuild<?,?>)){
        			LOGGER.warning("Something is wrong, the run is not actually a build !?");
        			return 0;
        		}
        		ParametersAction queuedAction = queuedItem.getAction(ParametersAction.class);

        		AbstractBuild<?,?> running  = (AbstractBuild<?,?>)exec.getCurrentExecutable();
        		ParametersAction runningAction = running.getAction(ParametersAction.class);

        		int incr = 1;
        		for(String param_name : matchParams){
        			ParameterValue runningVal = runningAction == null ? null : runningAction.getParameter(param_name);
        			ParameterValue newVal = queuedAction == null ? null : queuedAction.getParameter(param_name);
        			if(runningVal == null && newVal == null){
        				/* This is OK */
        			} else {
        				if(runningVal == null || !runningVal.equals(newVal))
        					incr = 0;
        			}
        		}
        		runCount += incr;
        	}
        }

        return runCount;
    }
    private int buildsOfProjectOnNode(Node node, Task taskToMatch, Queue.Item queuedItem, ArrayList<String> matchParams) {
        int runCount = 0;

        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            for (Executor e : computer.getExecutors()) {
                runCount += buildsOnExecutor(taskToMatch, queuedItem, e, matchParams);
            }
            if (taskToMatch instanceof MatrixProject) {
                for (Executor e : computer.getOneOffExecutors()) {
                    runCount += buildsOnExecutor(taskToMatch, queuedItem, e, matchParams);
                }
            }
        }

        return runCount;
    }


    private int buildsOfProjectOnAllNodes(Task taskToMatch, Queue.Item queuedItem, ArrayList<String> matchParams) {
        int totalRunCount = buildsOfProjectOnNode(Hudson.getInstance(), taskToMatch, queuedItem, matchParams);

        for (Node node : Hudson.getInstance().getNodes()) {
            totalRunCount += buildsOfProjectOnNode(node, taskToMatch, queuedItem, matchParams);
        }
        return totalRunCount;
    }



    private List<AbstractProject<?,?>> getCategoryProjects(String category) {
        List<AbstractProject<?,?>> categoryProjects = new ArrayList<AbstractProject<?,?>>();

        if (category != null && !category.equals("")) {
            for (AbstractProject<?,?> p : Hudson.getInstance().getAllItems(AbstractProject.class)) {
                ThrottleJobProperty t = p.getProperty(ThrottleJobProperty.class);

                if (t!=null && t.getThrottleEnabled()) {
                    if (t.getCategories()!=null && t.getCategories().contains(category)) {
                        categoryProjects.add(p);
                    }
                }
            }
        }

        return categoryProjects;
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
                        LOGGER.fine("node labels match");
                        LOGGER.fine("=> maxConcurrentPerNode' = "+maxConcurrentPerNodeLabeledIfMatch);
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
