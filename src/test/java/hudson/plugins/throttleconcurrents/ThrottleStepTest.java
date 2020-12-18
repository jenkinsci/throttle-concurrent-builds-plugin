package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import hudson.util.CopyOnWriteMap;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.recipes.LocalData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

public class ThrottleStepTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public TemporaryFolder firstAgentTmp = new TemporaryFolder();
    @Rule public TemporaryFolder secondAgentTmp = new TemporaryFolder();

    @Test
    public void onePerNode() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
        secondJob.setDefinition(getJobFlow("second", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());
        Node n = j.jenkins.getNode("first-agent");
        assertNotNull(n);
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, firstJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }

    @Test
    public void duplicateCategories() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);

        WorkflowJob job = j.createProject(WorkflowJob.class, "first-job");
        job.setDefinition(
                new CpsFlowDefinition(
                        "throttle(['"
                                + TestUtil.ONE_PER_NODE
                                + "', '"
                                + TestUtil.ONE_PER_NODE
                                + "']) { echo 'Hello' }",
                        true));

        WorkflowRun b = job.scheduleBuild2(0).waitForStart();

        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        j.assertLogContains(
                "One or more duplicate categories ("
                        + TestUtil.ONE_PER_NODE
                        + ") specified. Duplicates will be ignored.",
                b);
        j.assertLogContains("Hello", b);
    }

    @Test
    public void undefinedCategories() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "first-job");
        job.setDefinition(
                new CpsFlowDefinition(
                        "throttle(['undefined', 'also-undefined']) { echo 'Hello' }", true));

        WorkflowRun b = job.scheduleBuild2(0).waitForStart();

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains(
                "One or more specified categories do not exist: undefined, also-undefined", b);
        j.assertLogNotContains("Hello", b);
    }

    @Test
    public void multipleCategories() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
        secondJob.setDefinition(getJobFlow("second", TestUtil.OTHER_ONE_PER_NODE, "second-agent"));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

        WorkflowJob thirdJob = j.createProject(WorkflowJob.class, "third-job");
        thirdJob.setDefinition(
                getJobFlow(
                        "third",
                        Arrays.asList(TestUtil.ONE_PER_NODE, TestUtil.OTHER_ONE_PER_NODE),
                        "on-agent"));

        WorkflowRun thirdJobFirstRun = thirdJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", thirdJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());
        Node n = j.jenkins.getNode("first-agent");
        assertNotNull(n);
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, firstJobFirstRun);

        Node n2 = j.jenkins.getNode("second-agent");
        assertNotNull(n2);
        assertEquals(1, n2.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n2, secondJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, thirdJobFirstRun);

        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

        SemaphoreStep.success("wait-third-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
    }

    @Test
    public void onePerNodeParallel() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(
                new CpsFlowDefinition(
                        "parallel(\n"
                                + "  a: { "
                                + getThrottleScript(
                                        "first-branch-a", TestUtil.ONE_PER_NODE, "on-agent")
                                + " },\n"
                                + "  b: { "
                                + getThrottleScript(
                                        "first-branch-b", TestUtil.ONE_PER_NODE, "on-agent")
                                + " },\n"
                                + "  c: { "
                                + getThrottleScript(
                                        "first-branch-c", TestUtil.ONE_PER_NODE, "on-agent")
                                + " }\n"
                                + ")\n",
                        true));

        WorkflowRun run1 = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-branch-a-job/1", run1);
        SemaphoreStep.waitForStart("wait-first-branch-b-job/1", run1);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
        secondJob.setDefinition(
                new CpsFlowDefinition(
                        "parallel(\n"
                                + "  a: { "
                                + getThrottleScript(
                                        "second-branch-a", TestUtil.ONE_PER_NODE, "on-agent")
                                + " },\n"
                                + "  b: { "
                                + getThrottleScript(
                                        "second-branch-b", TestUtil.ONE_PER_NODE, "on-agent")
                                + " },\n"
                                + "  c: { "
                                + getThrottleScript(
                                        "second-branch-c", TestUtil.ONE_PER_NODE, "on-agent")
                                + " }\n"
                                + ")\n",
                        true));

        WorkflowRun run2 = secondJob.scheduleBuild2(0).waitForStart();

        Computer first = j.jenkins.getNode("first-agent").toComputer();
        Computer second = j.jenkins.getNode("second-agent").toComputer();
        assertEquals(1, first.countBusy());
        assertEquals(1, second.countBusy());

        j.waitForMessage("Still waiting to schedule task", run1);
        j.waitForMessage("Still waiting to schedule task", run2);

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

        j.assertBuildStatusSuccess(j.waitForCompletion(run1));
        j.assertBuildStatusSuccess(j.waitForCompletion(run2));
    }

    @Test
    public void twoTotal() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(getJobFlow("first", TestUtil.TWO_TOTAL, "first-agent"));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
        secondJob.setDefinition(getJobFlow("second", TestUtil.TWO_TOTAL, "second-agent"));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

        WorkflowJob thirdJob = j.createProject(WorkflowJob.class, "third-job");
        thirdJob.setDefinition(getJobFlow("third", TestUtil.TWO_TOTAL, "on-agent"));

        WorkflowRun thirdJobFirstRun = thirdJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", thirdJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());
        Node n = j.jenkins.getNode("first-agent");
        assertNotNull(n);
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, firstJobFirstRun);

        Node n2 = j.jenkins.getNode("second-agent");
        assertNotNull(n2);
        assertEquals(1, n2.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n2, secondJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
        SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(2, n.toComputer().countBusy() + n2.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, thirdJobFirstRun);

        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

        SemaphoreStep.success("wait-third-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
    }

    @Test
    public void interopWithFreestyle() throws Exception {
        final Semaphore semaphore = new Semaphore(1);

        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        FreeStyleProject freeStyleProject = j.createFreeStyleProject("f");
        freeStyleProject.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(TestUtil.ONE_PER_NODE), // categories
                        true, // throttleEnabled
                        "category", // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        freeStyleProject.setAssignedLabel(Label.get("first-agent"));
        freeStyleProject
                .getBuildersList()
                .add(
                        new TestBuilder() {
                            @Override
                            public boolean perform(
                                    AbstractBuild<?, ?> build,
                                    Launcher launcher,
                                    BuildListener listener)
                                    throws InterruptedException {
                                semaphore.acquire();
                                return true;
                            }
                        });

        semaphore.acquire();

        QueueTaskFuture<FreeStyleBuild> futureBuild = freeStyleProject.scheduleBuild2(0);
        assertFalse(j.jenkins.getQueue().isEmpty());
        assertEquals(1, j.jenkins.getQueue().getItems().length);
        Queue.Item i = j.jenkins.getQueue().getItems()[0];
        assertTrue(i.task instanceof FreeStyleProject);

        Node n = j.jenkins.getNode("first-agent");
        assertNotNull(n);
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, firstJobFirstRun);
        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        FreeStyleBuild freeStyleBuild = futureBuild.waitForStart();
        assertEquals(1, n.toComputer().countBusy());
        for (Executor e : n.toComputer().getExecutors()) {
            if (e.isBusy()) {
                assertEquals(freeStyleBuild, e.getCurrentExecutable());
            }
        }

        WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
        secondJob.setDefinition(getJobFlow("second", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());

        assertEquals(1, n.toComputer().countBusy());
        for (Executor e : n.toComputer().getExecutors()) {
            if (e.isBusy()) {
                assertEquals(freeStyleBuild, e.getCurrentExecutable());
            }
        }
        semaphore.release();

        j.assertBuildStatusSuccess(j.waitForCompletion(freeStyleBuild));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
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

        return "throttle(["
                + StringUtils.join(quoted, ", ")
                + "]) {\n"
                + "  echo 'hi there'\n"
                + "  node('"
                + label
                + "') {\n"
                + "    semaphore 'wait-"
                + jobName
                + "-job'\n"
                + "  }\n"
                + "}\n";
    }

    @Test
    public void snippetizer() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        SnippetizerTester st = new SnippetizerTester(j);
        st.assertRoundTrip(
                new ThrottleStep(Collections.singletonList(TestUtil.ONE_PER_NODE)),
                "throttle(['" + TestUtil.ONE_PER_NODE + "']) {\n    // some block\n}");
    }

    /**
     * A variant of {@link ThrottleStepTest#onePerNode} that also ensures that {@link
     * ThrottleJobProperty.DescriptorImpl#throttledPipelinesByCategory} contains copy-on-write data
     * structures.
     */
    @Issue("JENKINS-49006")
    @Test
    public void throttledPipelinesByCategoryCopyOnWrite() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
        secondJob.setDefinition(getJobFlow("second", TestUtil.ONE_PER_NODE, "first-agent"));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());
        Node n = j.jenkins.getNode("first-agent");
        assertNotNull(n);
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, firstJobFirstRun);

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        Map<String, List<String>> throttledPipelinesByCategory =
                descriptor.getThrottledPipelinesForCategory(TestUtil.ONE_PER_NODE);
        assertTrue(throttledPipelinesByCategory instanceof CopyOnWriteMap.Tree);
        assertEquals(2, throttledPipelinesByCategory.size());
        for (List<String> flowNodes : throttledPipelinesByCategory.values()) {
            assertTrue(flowNodes instanceof CopyOnWriteArrayList);
            assertEquals(1, flowNodes.size());
        }

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, n.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }

    /**
     * Ensures that data serialized prior to the fix for JENKINS-49006 is correctly converted to
     * copy-on-write data structures upon deserialization.
     */
    @Issue("JENKINS-49006")
    @LocalData
    @Test
    public void throttledPipelinesByCategoryMigratesOldData() {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();

        Map<String, List<String>> throttledPipelinesByCategory =
                descriptor.getThrottledPipelinesForCategory(TestUtil.TWO_TOTAL);
        assertTrue(throttledPipelinesByCategory instanceof CopyOnWriteMap.Tree);
        assertEquals(3, throttledPipelinesByCategory.size());
        assertEquals(
                new HashSet<>(Arrays.asList("first-job#1", "second-job#1", "third-job#1")),
                throttledPipelinesByCategory.keySet());
        for (List<String> flowNodes : throttledPipelinesByCategory.values()) {
            assertTrue(flowNodes instanceof CopyOnWriteArrayList);
            assertEquals(1, flowNodes.size());
            assertEquals("3", flowNodes.get(0));
        }
    }
}
