package hudson.plugins.throttleconcurrents;

import hudson.model.Executor;
import hudson.model.Node;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ThrottleStepTest {
    private static final String ONE_PER_NODE = "one_per_node";
    private static final String TWO_TOTAL = "two_total";

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public TemporaryFolder firstAgentTmp = new TemporaryFolder();
    @Rule
    public TemporaryFolder secondAgentTmp = new TemporaryFolder();

    public void setupAgentsAndCategories() throws Exception {
        DumbSlave firstAgent = new DumbSlave("first-agent", "dummy agent", firstAgentTmp.getRoot().getAbsolutePath(),
                "4", Node.Mode.NORMAL, "on-agent", story.j.createComputerLauncher(null),
                RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());

        DumbSlave secondAgent = new DumbSlave("second-agent", "dummy agent", secondAgentTmp.getRoot().getAbsolutePath(),
                "4", Node.Mode.NORMAL, "on-agent", story.j.createComputerLauncher(null),
                RetentionStrategy.NOOP, Collections.<NodeProperty<?>>emptyList());

        story.j.jenkins.addNode(firstAgent);
        story.j.jenkins.addNode(secondAgent);

        ThrottleJobProperty.ThrottleCategory firstCat = new ThrottleJobProperty.ThrottleCategory(ONE_PER_NODE, 1, 0, null);
        ThrottleJobProperty.ThrottleCategory secondCat = new ThrottleJobProperty.ThrottleCategory(TWO_TOTAL, 0, 2, null);

        ThrottleJobProperty.DescriptorImpl descriptor = story.j.jenkins.getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);
        assertNotNull(descriptor);
        descriptor.setCategories(Arrays.asList(firstCat, secondCat));
    }

    @Test
    public void onePerNode() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                // This should be sandbox:true, but when I do that, I get org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException: Scripts not permitted to use method groovy.lang.GroovyObject invokeMethod java.lang.String java.lang.Object
                // And I cannot figure out why. So for now...
                firstJob.setDefinition(new CpsFlowDefinition("throttle('" + ONE_PER_NODE + "') {\n" +
                        "  echo 'hi there'\n" +
                        "  node('first-agent') {\n" +
                        "    semaphore 'wait-first-job'\n" +
                        "  }\n" +
                        "}\n", false));

                WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(new CpsFlowDefinition("throttle('" + ONE_PER_NODE + "') {\n" +
                        "  node('first-agent') {\n" +
                        "    semaphore 'wait-second-job'\n" +
                        "  }\n" +
                        "}\n", false));

                WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
                assertFalse(story.j.jenkins.getQueue().isEmpty());
                Node n = story.j.jenkins.getNode("first-agent");
                assertNotNull(n);
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, firstJobFirstRun);

                SemaphoreStep.success("wait-first-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(firstJobFirstRun));
                SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
                assertTrue(story.j.jenkins.getQueue().isEmpty());
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, secondJobFirstRun);
                SemaphoreStep.success("wait-second-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(secondJobFirstRun));

            }
        });
    }

    private void hasPlaceholderTaskForRun(Node n, WorkflowRun r) throws Exception {
        for (Executor exec : n.toComputer().getExecutors()) {
            if (exec.getCurrentExecutable() != null) {
                assertTrue(exec.getCurrentExecutable().getParent() instanceof ExecutorStepExecution.PlaceholderTask);
                ExecutorStepExecution.PlaceholderTask task = (ExecutorStepExecution.PlaceholderTask)exec.getCurrentExecutable().getParent();
                assertEquals(r, task.run());
            }
        }
    }
}
