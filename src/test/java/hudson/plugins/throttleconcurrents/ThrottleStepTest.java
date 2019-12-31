package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import hudson.Functions;
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
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.throttleconcurrents.pipeline.ThrottleStep;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.util.CopyOnWriteMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import org.apache.commons.lang.StringUtils;
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
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.recipes.LocalData;

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
                RetentionStrategy.NOOP, Collections.emptyList());

        DumbSlave secondAgent = new DumbSlave("second-agent", "dummy agent", secondAgentTmp.getRoot().getAbsolutePath(),
                "4", Node.Mode.NORMAL, "on-agent", story.j.createComputerLauncher(null),
                RetentionStrategy.NOOP, Collections.emptyList());

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
    public void onePerNode() {
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
    public void duplicateCategories() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();

                WorkflowJob j = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                j.setDefinition(new CpsFlowDefinition("throttle(['" + ONE_PER_NODE + "', '" + ONE_PER_NODE +"']) { echo 'Hello' }", true));

                WorkflowRun b = j.scheduleBuild2(0).waitForStart();

                story.j.assertBuildStatusSuccess(story.j.waitForCompletion(b));

                story.j.assertLogContains("One or more duplicate categories (" + ONE_PER_NODE + ") specified. Duplicates will be ignored.", b);
                story.j.assertLogContains("Hello", b);
            }
        });
    }

    @Test
    public void undefinedCategories() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob j = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                j.setDefinition(new CpsFlowDefinition("throttle(['undefined', 'also-undefined']) { echo 'Hello' }", true));

                WorkflowRun b = j.scheduleBuild2(0).waitForStart();

                story.j.assertBuildStatus(Result.FAILURE, story.j.waitForCompletion(b));
                story.j.assertLogContains("One or more specified categories do not exist: undefined, also-undefined", b);
                story.j.assertLogNotContains("Hello", b);
            }
        });
    }

    @Test
    public void multipleCategories() {
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
                        Arrays.asList(ONE_PER_NODE, OTHER_ONE_PER_NODE),
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
    public void onePerNodeParallel() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                WorkflowJob firstJob = story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                firstJob.setDefinition(new CpsFlowDefinition("parallel(\n" +
                        "  a: { " + getThrottleScript("first-branch-a", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  b: { " + getThrottleScript("first-branch-b", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  c: { " + getThrottleScript("first-branch-c", ONE_PER_NODE, "on-agent") + " }\n" +
                        ")\n", true));

                WorkflowRun run1 = firstJob.scheduleBuild2(0).waitForStart();
                SemaphoreStep.waitForStart("wait-first-branch-a-job/1", run1);
                SemaphoreStep.waitForStart("wait-first-branch-b-job/1", run1);

                WorkflowJob secondJob = story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                secondJob.setDefinition(new CpsFlowDefinition("parallel(\n" +
                        "  a: { " + getThrottleScript("second-branch-a", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  b: { " + getThrottleScript("second-branch-b", ONE_PER_NODE, "on-agent") + " },\n" +
                        "  c: { " + getThrottleScript("second-branch-c", ONE_PER_NODE, "on-agent") + " }\n" +
                        ")\n", true));

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
                SemaphoreStep.waitForStart("wait-second-branch-a-job/1", run2);
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());
                SemaphoreStep.success("wait-first-branch-c-job/1", null);
                SemaphoreStep.waitForStart("wait-second-branch-b-job/1", run2);
                assertEquals(1, first.countBusy());
                assertEquals(1, second.countBusy());
                SemaphoreStep.success("wait-second-branch-a-job/1", null);
                SemaphoreStep.waitForStart("wait-second-branch-c-job/1", run2);
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
    public void twoTotal() {
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
    public void interopWithFreestyle() {
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
                        Collections.singletonList(ONE_PER_NODE),      // categories
                        true,   // throttleEnabled
                        "category",     // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT
                ));
                freeStyleProject.setAssignedLabel(Label.get("first-agent"));
                freeStyleProject.getBuildersList().add(new TestBuilder() {
                    @Override
                    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
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
        return getJobFlow(jobName, Collections.singletonList(category), label);
    }

    private CpsFlowDefinition getJobFlow(String jobName, List<String> categories, String label) {
        return new CpsFlowDefinition(getThrottleScript(jobName, categories, label), true);
    }

    private String getThrottleScript(String jobName, String category, String label) {
        return getThrottleScript(jobName, Collections.singletonList(category), label);
    }

    private String getThrottleScript(String jobName, List<String> categories, String label) {
        List<String> quoted = new ArrayList<>();
        for (String c : categories) {
            quoted.add("'" + c + "'");
        }

        return "throttle([" + StringUtils.join(quoted, ", ") + "]) {\n" +
                "  echo 'hi there'\n" +
                "  node('" + label + "') {\n" +
                "    semaphore 'wait-" + jobName + "-job'\n" +
                "  }\n" +
                "}\n";
    }

    @Test
    public void snippetizer() {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                setupAgentsAndCategories();
                SnippetizerTester st = new SnippetizerTester(story.j);
                st.assertRoundTrip(new ThrottleStep(Collections.singletonList(ONE_PER_NODE)),
                        "throttle(['" + ONE_PER_NODE + "']) {\n    // some block\n}");
            }
        });
    }

    /**
     * A variant of {@link ThrottleStepTest#onePerNode} that also ensures that {@link
     * ThrottleJobProperty.DescriptorImpl#throttledPipelinesByCategory} contains copy-on-write data
     * structures.
     */
    @Issue("JENKINS-49006")
    @Test
    public void throttledPipelinesByCategoryCopyOnWrite() {
        story.addStep(
                new Statement() {
                    @Override
                    public void evaluate() throws Throwable {
                        setupAgentsAndCategories();
                        WorkflowJob firstJob =
                                story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                        firstJob.setDefinition(getJobFlow("first", ONE_PER_NODE, "first-agent"));

                        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                        WorkflowJob secondJob =
                                story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                        secondJob.setDefinition(getJobFlow("second", ONE_PER_NODE, "first-agent"));

                        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                        story.j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
                        assertFalse(story.j.jenkins.getQueue().isEmpty());
                        Node n = story.j.jenkins.getNode("first-agent");
                        assertNotNull(n);
                        assertEquals(1, n.toComputer().countBusy());
                        hasPlaceholderTaskForRun(n, firstJobFirstRun);

                        ThrottleJobProperty.DescriptorImpl descriptor =
                                ThrottleJobProperty.fetchDescriptor();
                        Map<String, List<String>> throttledPipelinesByCategory =
                                descriptor.getThrottledPipelinesForCategory(ONE_PER_NODE);
                        assertTrue(throttledPipelinesByCategory instanceof CopyOnWriteMap.Tree);
                        assertEquals(2, throttledPipelinesByCategory.size());
                        for (List<String> flowNodes : throttledPipelinesByCategory.values()) {
                            assertTrue(flowNodes instanceof CopyOnWriteArrayList);
                            assertEquals(1, flowNodes.size());
                        }

                        SemaphoreStep.success("wait-first-job/1", null);
                        story.j.assertBuildStatusSuccess(
                                story.j.waitForCompletion(firstJobFirstRun));
                        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
                        assertTrue(story.j.jenkins.getQueue().isEmpty());
                        assertEquals(1, n.toComputer().countBusy());
                        hasPlaceholderTaskForRun(n, secondJobFirstRun);
                        SemaphoreStep.success("wait-second-job/1", null);
                        story.j.assertBuildStatusSuccess(
                                story.j.waitForCompletion(secondJobFirstRun));
                    }
                });
    }

    /**
     * Ensures that data serialized prior to the fix for JENKINS-49006 is correctly converted to
     * copy-on-write data structures upon deserialization.
     */
    @Issue("JENKINS-49006")
    @LocalData
    @Test
    public void throttledPipelinesByCategoryMigratesOldData() throws Exception {
        story.then(
                s -> {
                    ThrottleJobProperty.DescriptorImpl descriptor =
                            ThrottleJobProperty.fetchDescriptor();

                    Map<String, List<String>> throttledPipelinesByCategory =
                            descriptor.getThrottledPipelinesForCategory(TWO_TOTAL);
                    assertTrue(throttledPipelinesByCategory instanceof CopyOnWriteMap.Tree);
                    assertEquals(3, throttledPipelinesByCategory.size());
                    assertEquals(
                            new HashSet<>(
                                    Arrays.asList("first-job#1", "second-job#1", "third-job#1")),
                            throttledPipelinesByCategory.keySet());
                    for (List<String> flowNodes : throttledPipelinesByCategory.values()) {
                        assertTrue(flowNodes instanceof CopyOnWriteArrayList);
                        assertEquals(1, flowNodes.size());
                        assertEquals("3", flowNodes.get(0));
                    }
                    if (Functions.isWindows()) {
                        fail("fail to see log output to verify that Descriptor#save didn't log an error");
                    }
                });
    }

    private void hasPlaceholderTaskForRun(Node n, WorkflowRun r) {
        for (Executor exec : n.toComputer().getExecutors()) {
            if (exec.getCurrentExecutable() != null) {
                assertTrue(exec.getCurrentExecutable().getParent() instanceof ExecutorStepExecution.PlaceholderTask);
                ExecutorStepExecution.PlaceholderTask task = (ExecutorStepExecution.PlaceholderTask)exec.getCurrentExecutable().getParent();
                assertEquals(r, task.run());
            }
        }
    }
}
