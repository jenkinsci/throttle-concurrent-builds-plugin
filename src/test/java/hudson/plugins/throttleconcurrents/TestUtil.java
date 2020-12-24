package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.EnvVars;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.plugins.throttleconcurrents.testutils.ExecutorWaterMarkRetentionStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestUtil {

    // TODO move this into ThrottleJobProperty and use consistently; same for "project".
    static final String THROTTLE_OPTION_CATEGORY = "category";
    static final String ONE_PER_NODE = "one_per_node";
    static final String OTHER_ONE_PER_NODE = "other_one_per_node";
    static final String TWO_TOTAL = "two_total";

    private static DumbSlave createAgent(
            JenkinsRule j,
            TemporaryFolder temporaryFolder,
            EnvVars env,
            int numExecutors,
            String label)
            throws Exception {
        synchronized (j.jenkins) {
            int sz = j.jenkins.getNodes().size();
            DumbSlave agent =
                    new DumbSlave(
                            "agent" + sz,
                            temporaryFolder.getRoot().getPath(),
                            j.createComputerLauncher(env));
            agent.setNumExecutors(numExecutors);
            agent.setMode(Node.Mode.NORMAL);
            agent.setLabelString(label == null ? "" : label);
            agent.setRetentionStrategy(RetentionStrategy.NOOP);
            agent.setNodeProperties(Collections.emptyList());

            j.jenkins.addNode(agent);
            j.waitOnline(agent);
            return agent;
        }
    }

    static Node setupAgent(
            JenkinsRule j,
            TemporaryFolder temporaryFolder,
            List<Node> agents,
            List<ExecutorWaterMarkRetentionStrategy<SlaveComputer>> waterMarks,
            EnvVars env,
            int numExecutors,
            String label)
            throws Exception {
        DumbSlave agent = TestUtil.createAgent(j, temporaryFolder, env, numExecutors, label);

        if (agents != null) {
            agents.add(agent);
        }

        if (waterMarks != null) {
            ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark =
                    new ExecutorWaterMarkRetentionStrategy<SlaveComputer>(
                            agent.getRetentionStrategy());
            agent.setRetentionStrategy(waterMark);
            waterMarks.add(waterMark);
        }

        return agent;
    }

    static void tearDown(JenkinsRule j, List<Node> agents) throws Exception {
        for (Node agent : agents) {
            Computer computer = agent.toComputer();
            computer.disconnect(null);
            while (computer.isOnline()) {
                Thread.sleep(500L);
            }
            j.jenkins.removeNode(agent);
        }
    }

    static void setupCategories() {
        ThrottleJobProperty.ThrottleCategory firstCat =
                new ThrottleJobProperty.ThrottleCategory(ONE_PER_NODE, 1, 0, null);
        ThrottleJobProperty.ThrottleCategory secondCat =
                new ThrottleJobProperty.ThrottleCategory(TWO_TOTAL, 0, 2, null);
        ThrottleJobProperty.ThrottleCategory thirdCat =
                new ThrottleJobProperty.ThrottleCategory(OTHER_ONE_PER_NODE, 1, 0, null);

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        descriptor.setCategories(Arrays.asList(firstCat, secondCat, thirdCat));
    }

    static void hasPlaceholderTaskForRun(Node n, WorkflowRun r) throws Exception {
        for (Executor exec : n.toComputer().getExecutors()) {
            if (exec.getCurrentExecutable() != null) {
                assertTrue(
                        exec.getCurrentExecutable().getParent()
                                instanceof ExecutorStepExecution.PlaceholderTask);
                ExecutorStepExecution.PlaceholderTask task =
                        (ExecutorStepExecution.PlaceholderTask)
                                exec.getCurrentExecutable().getParent();
                while (task.run() == null) {
                    // Wait for the step context to be ready.
                    Thread.sleep(500);
                }
                assertEquals(r, task.run());
            }
        }
    }
}
