package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;


@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);

            if (tjp!=null && tjp.getThrottleEnabled()) {
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = 0;
                    
                    // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
                    // a build right after it was launched, for some reason.
                    for (Executor e : node.toComputer().getExecutors()) {
                        if (e.getCurrentExecutable()!=null
                            && e.getCurrentExecutable().getParent() == task) {
                            // This means we've got a build of this project already running on this node.
                            runCount++;
                        }
                    }
                    
                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
                }
                else if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                    int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                    int totalRunCount = 0;

                    for (Computer c : Hudson.getInstance().getComputers()) {
                        for (Executor e : c.getExecutors()) {
                            if (e.getCurrentExecutable() != null
                                && e.getCurrentExecutable().getParent() == task) {
                                totalRunCount++;
                            }
                        }
                    }

                    if (totalRunCount >= maxConcurrentTotal) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                    }
                }
            }
        }

        return null;
    }
}
                
                    