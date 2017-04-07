package hudson.plugins.throttleconcurrents;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.throttleconcurrents.pipeline.ThrottleStep;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ThrottleStepTest {
    private static final String ONE_PER_NODE = "one_per_node";
    private static final String OTHER_ONE_PER_NODE = "other_one_per_node";
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
        ThrottleJobProperty.ThrottleCategory thirdCat = new ThrottleJobProperty.ThrottleCategory(OTHER_ONE_PER_NODE, 1, 0, null);

        ThrottleJobProperty.DescriptorImpl descriptor = story.j.jenkins.getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);
        assertNotNull(descriptor);
        descriptor.setCategories(Arrays.asList(firstCat, secondCat, thirdCat));
    }

    @Test
    public void onePerNode() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                firstJob.setDefinition(getJobFlow("first", ONE_PER_NODE, "first-agent"));

                WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(getJobFlow("second", ONE_PER_NODE, "first-agent"));

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

    @Test
    public void multipleCategories() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                firstJob.setDefinition(getJobFlow("first", ONE_PER_NODE, "first-agent"));

                WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(getJobFlow("second", OTHER_ONE_PER_NODE, "second-agent"));

                WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

                WorkflowJob thirdJob = story.j.jenkins.createProject(WorkflowJob.class, "third-job");
                thirdJob.setDefinition(getJobFlow("third",
                        ONE_PER_NODE + ", " + OTHER_ONE_PER_NODE,
                        "on-agent"));

                WorkflowRun thirdJobFirstRun = thirdJob.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Still waiting to schedule task", thirdJobFirstRun);
                assertFalse(story.j.jenkins.getQueue().isEmpty());
                Node n = story.j.jenkins.getNode("first-agent");
                assertNotNull(n);
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, firstJobFirstRun);

                Node n2 = story.j.jenkins.getNode("second-agent");
                assertNotNull(n2);
                assertEquals(1, n2.toComputer().countBusy());
                hasPlaceholderTaskForRun(n2, secondJobFirstRun);

                SemaphoreStep.success("wait-first-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(firstJobFirstRun));

                SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
                assertTrue(story.j.jenkins.getQueue().isEmpty());
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, thirdJobFirstRun);

                SemaphoreStep.success("wait-second-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(secondJobFirstRun));

                SemaphoreStep.success("wait-third-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(thirdJobFirstRun));
            }
        });
    }

    @Test
    public void onePerNodeParallel() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                firstJob.setDefinition(new CpsFlowDefinition("parallel(\n" +
                        "  a: { " + getThrottleScript("first-branch-a", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  b: { " + getThrottleScript("first-branch-b", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  c: { " + getThrottleScript("first-branch-c", ONE_PER_NODE, "on-agent") + " }\n" +
                        ")\n", false));

                WorkflowRun run1 = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-branch-a-job/1", run1);
                SemaphoreStep.waitForStart("wait-first-branch-b-job/1", run1);

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(new CpsFlowDefinition("parallel(\n" +
                        "  a: { " + getThrottleScript("second-branch-a", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  b: { " + getThrottleScript("second-branch-b", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  c: { " + getThrottleScript("second-branch-c", ONE_PER_NODE, "on-agent") + " }\n" +
                        ")\n", false));

                WorkflowRun run2 = secondJob.scheduleBuild2(0).waitForStart();

                Computer first = story.j.jenkins.getNode("first-agent").toComputer();
                Computer second = story.j.jenkins.getNode("second-agent").toComputer();
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());

                story.j.waitForMessage("Still waiting to schedule task", run1);
                story.j.waitForMessage("Still waiting to schedule task", run2);

                SemaphoreStep.success("wait-first-branch-a-job/1", null);
                SemaphoreStep.waitForStart("wait-first-branch-c-job/1", run1);
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());
                SemaphoreStep.success("wait-first-branch-b-job/1", null);
                SemaphoreStep.waitForStart("wait-second-branch-a-job/1", run1);
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());
                SemaphoreStep.success("wait-first-branch-c-job/1", null);
                SemaphoreStep.waitForStart("wait-second-branch-b-job/1", run1);
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());
                SemaphoreStep.success("wait-second-branch-a-job/1", null);
                SemaphoreStep.waitForStart("wait-second-branch-c-job/1", run1);
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());
                SemaphoreStep.success("wait-second-branch-b-job/1", null);
                SemaphoreStep.success("wait-second-branch-c-job/1", null);

                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(run1));
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(run2));
            }
        });
    }

    @Test
    public void twoTotal() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                firstJob.setDefinition(getJobFlow("first", TWO_TOTAL, "first-agent"));

                WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(getJobFlow("second", TWO_TOTAL, "second-agent"));

                WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

                WorkflowJob thirdJob = story.j.jenkins.createProject(WorkflowJob.class, "third-job");
                thirdJob.setDefinition(getJobFlow("third", TWO_TOTAL, "on-agent"));

                WorkflowRun thirdJobFirstRun = thirdJob.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Still waiting to schedule task", thirdJobFirstRun);
                assertFalse(story.j.jenkins.getQueue().isEmpty());
                Node n = story.j.jenkins.getNode("first-agent");
                assertNotNull(n);
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, firstJobFirstRun);

                Node n2 = story.j.jenkins.getNode("second-agent");
                assertNotNull(n2);
                assertEquals(1, n2.toComputer().countBusy());
                hasPlaceholderTaskForRun(n2, secondJobFirstRun);

                SemaphoreStep.success("wait-first-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(firstJobFirstRun));
                SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
                assertTrue(story.j.jenkins.getQueue().isEmpty());
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, thirdJobFirstRun);

                SemaphoreStep.success("wait-second-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(secondJobFirstRun));

                SemaphoreStep.success("wait-third-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(thirdJobFirstRun));
            }
        });
    }

    @Test
    public void interopWithFreestyle() throws Exception {
        final Semaphore semaphore = new Semaphore(1);

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                firstJob.setDefinition(getJobFlow("first", ONE_PER_NODE, "first-agent"));

                WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                FreeStyleProject freeStyleProject = story.j.createFreeStyleProject("f");
                freeStyleProject.addProperty(new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Arrays.asList(ONE_PER_NODE),      // categories
                        true,   // throttleEnabled
                        "category",     // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT
                ));
                freeStyleProject.setAssignedLabel(Label.get("first-agent"));
                freeStyleProject.getBuildersList().add(new TestBuilder() {
                    @Override
                    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                        semaphore.acquire();
                        return true;
                    }
                });

                semaphore.acquire();

                QueueTaskFuture<FreeStyleBuild> futureBuild = freeStyleProject.scheduleBuild2(0);
                assertFalse(story.j.jenkins.getQueue().isEmpty());
                assertEquals(1, story.j.jenkins.getQueue().getItems().length);
                Queue.Item i = story.j.jenkins.getQueue().getItems()[0];
                assertTrue(i.task instanceof FreeStyleProject);

                Node n = story.j.jenkins.getNode("first-agent");
                assertNotNull(n);
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, firstJobFirstRun);
                SemaphoreStep.success("wait-first-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(firstJobFirstRun));

                FreeStyleBuild freeStyleBuild = futureBuild.waitForStart();
                assertEquals(1, n.toComputer().countBusy());
                for (Executor e : n.toComputer().getExecutors()) {
                    if (e.isBusy()) {
                        assertEquals(freeStyleBuild, e.getCurrentExecutable());
                    }
                }

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(getJobFlow("second", ONE_PER_NODE, "first-agent"));

                WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                story.j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
                assertFalse(story.j.jenkins.getQueue().isEmpty());

                assertEquals(1, n.toComputer().countBusy());
                for (Executor e : n.toComputer().getExecutors()) {
                    if (e.isBusy()) {
                        assertEquals(freeStyleBuild, e.getCurrentExecutable());
                    }
                }
                semaphore.release();

                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(freeStyleBuild));
                SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
                assertTrue(story.j.jenkins.getQueue().isEmpty());
                assertEquals(1, n.toComputer().countBusy());
                hasPlaceholderTaskForRun(n, secondJobFirstRun);
                SemaphoreStep.success("wait-second-job/1", null);
                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(secondJobFirstRun));
            }
        });
    }

    private CpsFlowDefinition getJobFlow(String jobName, String category, String label) {
        // This should be sandbox:true, but when I do that, I get org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException: Scripts not permitted to use method groovy.lang.GroovyObject invokeMethod java.lang.String java.lang.Object
        // And I cannot figure out why. So for now...
        return new CpsFlowDefinition(getThrottleScript(jobName, category, label), false);
    }

    private String getThrottleScript(String jobName, String category, String label) {
        return "throttle('" + category + "') {\n" +
                "  echo 'hi there'\n" +
                "  node('" + label + "') {\n" +
                "    semaphore 'wait-" + jobName + "-job'\n" +
                "  }\n" +
                "}\n";
    }

    @Test
    public void snippetizer() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                SnippetizerTester st = new SnippetizerTester(story.j);
                st.assertRoundTrip(new ThrottleStep(ONE_PER_NODE),
                        "throttle('" + ONE_PER_NODE + "') {\n    // some block\n}");
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
