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

import java.util.ArrayList;
import java.util.List;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);

            if (tjp!=null && tjp.getThrottleEnabled()) {
                List<AbstractProject<?,?>> categoryProjects = getCategoryProjects(tjp);
                
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task);
                    
                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
                }
                else if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                    int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                    int totalRunCount = buildsOfProjectOnAllNodes(task);

                    if (totalRunCount >= maxConcurrentTotal) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                    }
                }
                // If the project is in a category...
                else if (!categoryProjects.isEmpty()) {
                    ThrottleJobProperty.ThrottleCategory category =
                        ((ThrottleJobProperty.DescriptorImpl)tjp.getDescriptor()).getCategoryByName(tjp.getCategory());

                    // Double check category itself isn't null
                    if (category != null) {
                        // Max concurrent per node for category
                        if (category.getMaxConcurrentPerNode().intValue() > 0) {
                            int maxConcurrentPerNode = category.getMaxConcurrentPerNode().intValue();
                            int runCount = 0;

                            for (AbstractProject<?,?> catProj : categoryProjects) {
                                runCount += buildsOfProjectOnNode(node, catProj);
                            }
                            // This would mean that there are as many or more builds currently running than are allowed.
                            if (runCount >= maxConcurrentPerNode) {
                                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                            }
                        }
                        else if (category.getMaxConcurrentTotal().intValue() > 0) {
                            int maxConcurrentTotal = category.getMaxConcurrentTotal().intValue();
                            int totalRunCount = 0;
                            
                            for (AbstractProject<?,?> catProj : categoryProjects) {
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

        return null;
    }

    
    private int buildsOfProjectOnNode(Node node, Task task) {
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

        return runCount;
    }

    private int buildsOfProjectOnAllNodes(Task task) {
        int totalRunCount = 0;
        
        for (Computer c : Hudson.getInstance().getComputers()) {
            for (Executor e : c.getExecutors()) {
                if (e.getCurrentExecutable() != null
                    && e.getCurrentExecutable().getParent() == task) {
                    totalRunCount++;
                }
            }
        }

        return totalRunCount;
    }
    
    private List<AbstractProject<?,?>> getCategoryProjects(ThrottleJobProperty tjp) {
        List<AbstractProject<?,?>> categoryProjects = new ArrayList<AbstractProject<?,?>>();

        if (tjp.getCategory()!=null && !tjp.getCategory().equals("")) {
            for (AbstractProject<?,?> p : Hudson.getInstance().getAllItems(AbstractProject.class)) {
                ThrottleJobProperty t = p.getProperty(ThrottleJobProperty.class);
                
                if (t!=null && t.getThrottleEnabled()) {
                    if (t.getCategory()!=null && t.getCategory().equals(tjp.getCategory())) {
                        categoryProjects.add(p);
                    }
                }
            }
        }

        return categoryProjects;
    }
}
                
                    