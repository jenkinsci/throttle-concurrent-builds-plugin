package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;

import java.util.logging.Logger;

/**
 * Created with IntelliJ IDEA.
 * User: psamoshkin
 * Date: 11/9/12
 * Time: 5:04 PM
 * To change this template use File | Settings | File Templates.
 */
@Extension
public class ProjectTaskDispatcher extends ThrottleQueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Queue.Task task, ThrottleJobProperty tjp) {
        if (tjp.getThrottleProjectAlone()) {
            if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                long interval= tjp.getInterval().intValue();
                int runCount = buildsOfProjectOnNode(node, task);

                long minElapsedTime = buildsOfProjectOnNodeMinimalElapsedTime(node, task);

                // This would mean that there are as many or more builds currently running than are allowed.
                if (runCount >= maxConcurrentPerNode) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                } else if(runCount>0 && interval > 0
                        && minElapsedTime != LONG_MAX && interval > minElapsedTime){
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_IntervalOnNode(runCount));
                }
            }
        }

        return null;
    }

    public CauseOfBlockage canRun(Queue.Task task, ThrottleJobProperty tjp) {

        if (tjp.getThrottleProjectAlone()) {
            if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                int totalRunCount = buildsOfProjectOnAllNodes(task);

                if (totalRunCount >= maxConcurrentTotal) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                }
            }
        }

        return null;
    }

    private static final Logger LOGGER = Logger.getLogger(ProjectTaskDispatcher.class.getName());
}
