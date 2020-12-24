package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.EnvVars;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;

public class TestUtil {

    // TODO move this into ThrottleJobProperty and use consistently; same for "project".
    static final String THROTTLE_OPTION_CATEGORY = "category";
    static final String ONE_PER_NODE = "one_per_node";
    static final String OTHER_ONE_PER_NODE = "other_one_per_node";
    static final String TWO_TOTAL = "two_total";

    static DumbSlave createAgent(
            JenkinsRule j,
            String name,
            TemporaryFolder temporaryFolder,
            EnvVars env,
            int numExecutors,
            String label)
            throws Exception {
        DumbSlave agent =
                new DumbSlave(
                        name, temporaryFolder.getRoot().getPath(), j.createComputerLauncher(env));
        agent.setNumExecutors(numExecutors);
        agent.setMode(Node.Mode.NORMAL);
        agent.setLabelString(label == null ? "" : label);
        agent.setRetentionStrategy(RetentionStrategy.NOOP);
        agent.setNodeProperties(Collections.emptyList());

        j.jenkins.addNode(agent);
        j.waitOnline(agent);
        return agent;
    }

    static DumbSlave setupOneAgent(JenkinsRule j, TemporaryFolder firstAgentTmp) throws Exception {
        int sz = j.jenkins.getNodes().size();
        return createAgent(j, "agent" + sz, firstAgentTmp, null, 2, null);
    }

    static void setupTwoAgents(
            JenkinsRule j, TemporaryFolder firstAgentTmp, TemporaryFolder secondAgentTmp)
            throws Exception {
        createAgent(j, "first-agent", firstAgentTmp, null, 4, "on-agent");
        createAgent(j, "second-agent", secondAgentTmp, null, 4, "on-agent");
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
