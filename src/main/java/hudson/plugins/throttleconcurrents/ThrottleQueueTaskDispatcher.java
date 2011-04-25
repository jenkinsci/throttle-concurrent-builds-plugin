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
import hudson.model.Queue.Task;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (task instanceof AbstractProject) {
            AbstractProject<?,?> p = (AbstractProject<?,?>) task;
            if (task instanceof MatrixConfiguration) {
                p = (AbstractProject<?,?>)((MatrixConfiguration)task).getParent();
            }
            ThrottleJobProperty tjp = p.getProperty(ThrottleJobProperty.class);

            if (tjp!=null && tjp.getThrottleEnabled()) {
                if (Hudson.getInstance().getQueue().isPending(task)) {
                    return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
                }
                
                if (tjp.getMaxConcurrentPerNode().intValue() > 0) {
                    int maxConcurrentPerNode = tjp.getMaxConcurrentPerNode().intValue();
                    int runCount = buildsOfProjectOnNode(node, task);
                    
                    // This would mean that there are as many or more builds currently running than are allowed.
                    if (runCount >= maxConcurrentPerNode) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(runCount));
                    }
                }
                if (tjp.getMaxConcurrentTotal().intValue() > 0) {
                    int maxConcurrentTotal = tjp.getMaxConcurrentTotal().intValue();
                    int totalRunCount = buildsOfProjectOnAllNodes(task);

                    if (totalRunCount >= maxConcurrentTotal) {
                        return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(totalRunCount));
                    }
                }
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
                                if (category.getMaxConcurrentPerNode().intValue() > 0) {
                                    int maxConcurrentPerNode = category.getMaxConcurrentPerNode().intValue();
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
        }

        return null;
    }

    
    private int buildsOfProjectOnNode(Node node, Task task) {
        int runCount = 0;
        LOGGER.fine("Checking for builds of " + task.getName() + " on node " + node.getDisplayName());
        
        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        for (Executor e : node.toComputer().getExecutors()) {
            if (e.getCurrentExecutable()!=null
                && e.getCurrentExecutable().getParent() == task) {
                // This means we've got a build of this project already running on this node.
                LOGGER.fine("Found one");
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
    
    private List<AbstractProject<?,?>> getCategoryProjects(String category) {
        List<AbstractProject<?,?>> categoryProjects = new ArrayList<AbstractProject<?,?>>();

        if (category != null && !category.equals("")) {
            for (AbstractProject<?,?> p : Hudson.getInstance().getAllItems(AbstractProject.class)) {
                ThrottleJobProperty t = p.getProperty(ThrottleJobProperty.class);
                
                if (t!=null && t.getThrottleEnabled()) {
                    if (t.getCategories()!=null && t.getCategories().contains(category)) {
                        categoryProjects.add(p);
                        if (p instanceof MatrixProject) {
                            for (MatrixConfiguration mc : ((MatrixProject)p).getActiveConfigurations()) {
                                categoryProjects.add((AbstractProject<?,?>)mc);
                            }
                        }
                    }
                }
            }
        }
        
        return categoryProjects;
    }

    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueTaskDispatcher.class.getName());

}
                
                    