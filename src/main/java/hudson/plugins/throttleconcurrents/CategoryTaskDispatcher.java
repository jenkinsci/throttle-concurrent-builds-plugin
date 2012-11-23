package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;

import java.util.List;
import java.util.logging.Logger;

/**
 * Created with IntelliJ IDEA.
 * User: psamoshkin
 * Date: 11/9/12
 * Time: 5:04 PM
 * To change this template use File | Settings | File Templates.
 */
@Extension
public class CategoryTaskDispatcher extends ThrottleQueueTaskDispatcher {
    @Override
    public CauseOfBlockage canTake(Node node, Queue.Task task, ThrottleJobProperty tjp) {
        if (tjp.getThrottleCategory()) {
            if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                for (String catNm : tjp.getCategories()) {
                    ThrottleJobProperty.ThrottleCategory category =
                            ((ThrottleJobProperty.DescriptorImpl) tjp.getDescriptor()).getCategoryByName(catNm);
                    if (category != null) {
                        synchronized (category) {
                            int maxConcurrentPerNode = category.getMaxConcurrentPerNode().intValue();
                            long startIntervalPerNode = category.getInterval();
                            if (maxConcurrentPerNode > 0 || startIntervalPerNode > 0) {
                                List<AbstractProject<?,?>> categoryProjects = category.getProjects();
                                int runCount = 0;
                                long minElapsedTime = 4294967296L;
                                long elapsedTime = minElapsedTime;

                                for (AbstractProject<?,?> catProj : categoryProjects) {
                                    if (Hudson.getInstance().getQueue().isPending(catProj)) {
                                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                                    }
                                    runCount += buildsOfProjectOnNode(node, catProj);
                                    elapsedTime = buildsOfProjectOnNodeMinimalElapsedTime(node, catProj);
                                    if(elapsedTime < minElapsedTime){
                                        minElapsedTime = elapsedTime;
                                    }
                                }
                                // This would mean that there are as many or more builds currently running than are allowed.
                                if (maxConcurrentPerNode > 0 && runCount >= maxConcurrentPerNode) {
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                                }
                                else if(runCount > 0 && startIntervalPerNode > 0
                                        && minElapsedTime != LONG_MAX && startIntervalPerNode > minElapsedTime){
                                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_IntervalOnNode(runCount));
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public CauseOfBlockage canRun(Queue.Task task, ThrottleJobProperty tjp) {
        if (tjp.getThrottleCategory()) {
            if (tjp.getCategories() != null && !tjp.getCategories().isEmpty()) {
                for (String catNm : tjp.getCategories()) {
                    ThrottleJobProperty.ThrottleCategory category;

                    category = ((ThrottleJobProperty.DescriptorImpl) tjp.getDescriptor()).getCategoryByName(catNm);

                    if (category != null) {
                        synchronized (category){
                            List<AbstractProject<?,?>> categoryProjects = category.getProjects();

                            int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                            if (maxConcurrentTotal > 0) {
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

    private static final Logger LOGGER = Logger.getLogger(CategoryTaskDispatcher.class.getName());
}
