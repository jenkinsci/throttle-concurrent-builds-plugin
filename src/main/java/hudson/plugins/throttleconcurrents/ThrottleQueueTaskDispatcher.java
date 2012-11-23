package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.*;
import hudson.model.Queue.Task;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;

import java.util.Set;
import java.util.logging.Logger;


public abstract class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {
    final long LONG_MAX = 4294967296L;

    public abstract CauseOfBlockage canTake(Node node, Task task, ThrottleJobProperty jpp);

    public abstract CauseOfBlockage canRun(Task task, ThrottleJobProperty tjp);

    @Override
    public CauseOfBlockage canTake(Node node, Task task) {
        if (task instanceof MatrixConfiguration) {
            return null;
        }
        ThrottleJobProperty tjp = getThrottleJobProperty(task);
        if (tjp != null && tjp.getThrottleEnabled()){
            if (Hudson.getInstance().getQueue().isPending(task)) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
            }
            CauseOfBlockage cause = canRun(task, tjp);
            if (cause != null) {
                return cause;
            }
            return canTake(node, task, tjp);
        }

        return null;
    }

    // @Override on jenkins 4.127+ , but still compatible with 1.399
    public CauseOfBlockage canRun(Queue.Item item) {
        if (item.task instanceof MatrixConfiguration) {
            return null;
        }
        ThrottleJobProperty tjp = getThrottleJobProperty(item.task);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            Set<Task> unblockedTasks = Hudson.getInstance().getQueue().getUnblockedTasks();
            if (Hudson.getInstance().getQueue().isPending(item.task)) {
                return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BuildPending());
            }
            return canRun(item.task, tjp);
        }
        return null;
    }

    protected ThrottleJobProperty getThrottleJobProperty(Task task) {
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

    protected int buildsOfProjectOnNode(Node node, Task task) {
        int runCount = 0;

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

    protected long buildsOfProjectOnNodeMinimalElapsedTime(Node node, Task task) {
        long minimalRunningTime = LONG_MAX;
        // I think this'll be more reliable than job.getBuilds(), which seemed to not always get
        // a build right after it was launched, for some reason.
        Computer computer = node.toComputer();
        if (computer != null) { //Not all nodes are certain to become computers, like nodes with 0 executors.
            for (Executor e : computer.getExecutors()) {
                long elapsedTime = minimalElapsedBuildTimeOnExecutor(task, e);
                if(elapsedTime < minimalRunningTime){
                    minimalRunningTime = elapsedTime;
                }
            }
            if (task instanceof MatrixProject) {
                for (Executor e : computer.getOneOffExecutors()) {
                    long elapsedTime = minimalElapsedBuildTimeOnExecutor(task, e);
                    if(elapsedTime < minimalRunningTime){
                        minimalRunningTime = elapsedTime;
                    }
                }
            }
        }

        return minimalRunningTime;
    }

    protected int buildsOfProjectOnAllNodes(Task task) {
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

    private Long minimalElapsedBuildTimeOnExecutor(Task task, Executor exec) {
        Long minimalRunningTime = LONG_MAX;
        if (exec.getCurrentExecutable() != null
            && exec.getCurrentExecutable().getParent() == task) {
            long elapsedTime = exec.getElapsedTime();
            if(elapsedTime < minimalRunningTime){
                minimalRunningTime = elapsedTime;
            }
        }

        return minimalRunningTime;
    }

    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueTaskDispatcher.class.getName());

}
