package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.EnvVars;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;

import jenkins.model.queue.CompositeCauseOfBlockage;

import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TestUtil {

    // TODO move this into ThrottleJobProperty and use consistently; same for "project".
    static final String THROTTLE_OPTION_CATEGORY = "category";
    static final String THROTTLE_OPTION_PROJECT = "project";

    static final ThrottleJobProperty.ThrottleCategory ONE_PER_NODE =
            new ThrottleJobProperty.ThrottleCategory("one_per_node", 1, 0, null);
    static final ThrottleJobProperty.ThrottleCategory TWO_TOTAL =
            new ThrottleJobProperty.ThrottleCategory("two_total", 0, 2, null);
    static final ThrottleJobProperty.ThrottleCategory OTHER_ONE_PER_NODE =
            new ThrottleJobProperty.ThrottleCategory("other_one_per_node", 1, 0, null);

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
            EnvVars env,
            int numExecutors,
            String label)
            throws Exception {
        DumbSlave agent = TestUtil.createAgent(j, temporaryFolder, env, numExecutors, label);

        if (agents != null) {
            agents.add(agent);
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

    static void setupCategories(ThrottleJobProperty.ThrottleCategory... categories) {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        descriptor.setCategories(Arrays.asList(categories));
    }

    static Set<String> getBlockageReasons(CauseOfBlockage cob) {
        if (cob instanceof CompositeCauseOfBlockage) {
            CompositeCauseOfBlockage ccob = (CompositeCauseOfBlockage) cob;
            return ccob.uniqueReasons.keySet();
        } else {
            return Collections.singleton(cob.getShortDescription());
        }
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
