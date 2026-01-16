package hudson.plugins.throttleconcurrents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
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
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ThrottleStepTest {

    private JenkinsRule j;

    @TempDir
    private File firstAgentTmp;

    @TempDir
    private File secondAgentTmp;

    private List<Node> agents = new ArrayList<>();

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
    }

    /** Clean up agents. */
    @AfterEach
    void tearDown() throws Exception {
        TestUtil.tearDown(j, agents);
        agents = new ArrayList<>();
    }

    @Test
    void onePerNode() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE.getCategoryName(), agent.getNodeName()));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(getJobFlow("second", TestUtil.ONE_PER_NODE.getCategoryName(), agent.getNodeName()));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        j.jenkins.getQueue().maintain();
        assertFalse(j.jenkins.getQueue().isEmpty());
        List<Queue.Item> queuedItemList =
                Arrays.stream(j.jenkins.getQueue().getItems()).toList();
        assertEquals(1, queuedItemList.size());
        Queue.Item queuedItem = queuedItemList.get(0);
        Set<String> blockageReasons = TestUtil.getBlockageReasons(queuedItem.getCauseOfBlockage());
        assertThat(
                blockageReasons,
                hasItem(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(1)
                        .toString()));
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, firstJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }

    @Test
    void duplicateCategories() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "throttle(['"
                        + TestUtil.ONE_PER_NODE.getCategoryName()
                        + "', '"
                        + TestUtil.ONE_PER_NODE.getCategoryName()
                        + "']) { echo 'Hello' }",
                true));

        WorkflowRun b = job.scheduleBuild2(0).waitForStart();

        j.assertBuildStatusSuccess(j.waitForCompletion(b));

        j.assertLogContains(
                "One or more duplicate categories ("
                        + TestUtil.ONE_PER_NODE.getCategoryName()
                        + ") specified. Duplicates will be ignored.",
                b);
        j.assertLogContains("Hello", b);
    }

    @Test
    void undefinedCategories() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition("throttle(['undefined', 'also-undefined']) { echo 'Hello' }", true));

        WorkflowRun b = job.scheduleBuild2(0).waitForStart();

        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(b));
        j.assertLogContains("One or more specified categories do not exist: undefined, also-undefined", b);
        j.assertLogNotContains("Hello", b);
    }

    @Test
    void multipleCategories() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE, TestUtil.OTHER_ONE_PER_NODE);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE.getCategoryName(), firstAgent.getNodeName()));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(
                getJobFlow("second", TestUtil.OTHER_ONE_PER_NODE.getCategoryName(), secondAgent.getNodeName()));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

        WorkflowJob thirdJob = j.createProject(WorkflowJob.class);
        thirdJob.setDefinition(getJobFlow(
                "third",
                Arrays.asList(TestUtil.ONE_PER_NODE.getCategoryName(), TestUtil.OTHER_ONE_PER_NODE.getCategoryName()),
                "on-agent"));

        WorkflowRun thirdJobFirstRun = thirdJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", thirdJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());
        assertEquals(1, firstAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, firstJobFirstRun);

        assertEquals(1, secondAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(secondAgent, secondJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, firstAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, thirdJobFirstRun);

        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

        SemaphoreStep.success("wait-third-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
    }

    @Test
    void onePerNodeParallel() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(new CpsFlowDefinition(
                "parallel(\n"
                        + "  a: { "
                        + getThrottleScript("first-branch-a", TestUtil.ONE_PER_NODE.getCategoryName(), "on-agent")
                        + " },\n"
                        + "  b: { "
                        + getThrottleScript("first-branch-b", TestUtil.ONE_PER_NODE.getCategoryName(), "on-agent")
                        + " },\n"
                        + "  c: { "
                        + getThrottleScript("first-branch-c", TestUtil.ONE_PER_NODE.getCategoryName(), "on-agent")
                        + " }\n"
                        + ")\n",
                true));

        WorkflowRun run1 = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-branch-a-job/1", run1);
        SemaphoreStep.waitForStart("wait-first-branch-b-job/1", run1);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(new CpsFlowDefinition(
                "parallel(\n"
                        + "  a: { "
                        + getThrottleScript("second-branch-a", TestUtil.ONE_PER_NODE.getCategoryName(), "on-agent")
                        + " },\n"
                        + "  b: { "
                        + getThrottleScript("second-branch-b", TestUtil.ONE_PER_NODE.getCategoryName(), "on-agent")
                        + " },\n"
                        + "  c: { "
                        + getThrottleScript("second-branch-c", TestUtil.ONE_PER_NODE.getCategoryName(), "on-agent")
                        + " }\n"
                        + ")\n",
                true));

        WorkflowRun run2 = secondJob.scheduleBuild2(0).waitForStart();

        assertEquals(1, firstAgent.toComputer().countBusy());
        assertEquals(1, secondAgent.toComputer().countBusy());

        j.waitForMessage("Still waiting to schedule task", run1);
        j.waitForMessage("Still waiting to schedule task", run2);

        SemaphoreStep.success("wait-first-branch-a-job/1", null);
        SemaphoreStep.waitForStart("wait-first-branch-c-job/1", run1);
        assertEquals(1, firstAgent.toComputer().countBusy());
        assertEquals(1, secondAgent.toComputer().countBusy());
        SemaphoreStep.success("wait-first-branch-b-job/1", null);
        SemaphoreStep.waitForStart("wait-second-branch-a-job/1", run2);
        assertEquals(1, firstAgent.toComputer().countBusy());
        assertEquals(1, secondAgent.toComputer().countBusy());
        SemaphoreStep.success("wait-first-branch-c-job/1", null);
        SemaphoreStep.waitForStart("wait-second-branch-b-job/1", run2);
        assertEquals(1, firstAgent.toComputer().countBusy());
        assertEquals(1, secondAgent.toComputer().countBusy());
        SemaphoreStep.success("wait-second-branch-a-job/1", null);
        SemaphoreStep.waitForStart("wait-second-branch-c-job/1", run2);
        assertEquals(1, firstAgent.toComputer().countBusy());
        assertEquals(1, secondAgent.toComputer().countBusy());
        SemaphoreStep.success("wait-second-branch-b-job/1", null);
        SemaphoreStep.success("wait-second-branch-c-job/1", null);

        j.assertBuildStatusSuccess(j.waitForCompletion(run1));
        j.assertBuildStatusSuccess(j.waitForCompletion(run2));
    }

    @Test
    void twoTotal() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.TWO_TOTAL);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(getJobFlow("first", TestUtil.TWO_TOTAL.getCategoryName(), firstAgent.getNodeName()));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(getJobFlow("second", TestUtil.TWO_TOTAL.getCategoryName(), secondAgent.getNodeName()));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

        WorkflowJob thirdJob = j.createProject(WorkflowJob.class);
        thirdJob.setDefinition(getJobFlow("third", TestUtil.TWO_TOTAL.getCategoryName(), "on-agent"));

        WorkflowRun thirdJobFirstRun = thirdJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", thirdJobFirstRun);
        j.jenkins.getQueue().maintain();
        assertFalse(j.jenkins.getQueue().isEmpty());
        assertEquals(1, firstAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, firstJobFirstRun);

        assertEquals(1, secondAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(secondAgent, secondJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(
                2,
                firstAgent.toComputer().countBusy() + secondAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, thirdJobFirstRun);

        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

        SemaphoreStep.success("wait-third-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
    }

    @Test
    void interopWithFreestyle() throws Exception {
        final Semaphore semaphore = new Semaphore(1);

        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE.getCategoryName(), agent.getNodeName()));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        FreeStyleProject freeStyleProject = j.createFreeStyleProject("f");
        freeStyleProject.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        freeStyleProject.setAssignedLabel(Label.get(agent.getNodeName()));
        freeStyleProject.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
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
        assertInstanceOf(FreeStyleProject.class, i.task);

        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, firstJobFirstRun);
        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        FreeStyleBuild freeStyleBuild = futureBuild.waitForStart();
        assertEquals(1, agent.toComputer().countBusy());
        for (Executor e : agent.toComputer().getExecutors()) {
            if (e.isBusy()) {
                assertEquals(freeStyleBuild, e.getCurrentExecutable());
            }
        }

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(getJobFlow("second", TestUtil.ONE_PER_NODE.getCategoryName(), agent.getNodeName()));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());

        assertEquals(1, agent.toComputer().countBusy());
        for (Executor e : agent.toComputer().getExecutors()) {
            if (e.isBusy()) {
                assertEquals(freeStyleBuild, e.getCurrentExecutable());
            }
        }
        semaphore.release();

        j.assertBuildStatusSuccess(j.waitForCompletion(freeStyleBuild));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }

    @Test
    void inOptionsBlockOfDeclarativePipeline() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(
                getDeclarativeJobFlow("first", TestUtil.ONE_PER_NODE.getCategoryName(), agent.getNodeName()));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(
                getDeclarativeJobFlow("second", TestUtil.ONE_PER_NODE.getCategoryName(), agent.getNodeName()));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        j.jenkins.getQueue().maintain();
        assertFalse(j.jenkins.getQueue().isEmpty());

        List<Queue.Item> queuedItemList =
                Arrays.stream(j.jenkins.getQueue().getItems()).toList();
        assertEquals(1, queuedItemList.size());
        Queue.Item queuedItem = queuedItemList.get(0);
        Set<String> blockageReasons = TestUtil.getBlockageReasons(queuedItem.getCauseOfBlockage());
        assertThat(
                blockageReasons,
                hasItem(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(1)
                        .toString()));
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, firstJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }

    private static CpsFlowDefinition getJobFlow(String jobName, String category, String label) throws Exception {
        return getJobFlow(jobName, Collections.singletonList(category), label);
    }

    private static CpsFlowDefinition getJobFlow(String jobName, List<String> categories, String label)
            throws Exception {
        return new CpsFlowDefinition(getThrottleScript(jobName, categories, label), true);
    }

    private static String getThrottleScript(String jobName, String category, String label) {
        return getThrottleScript(jobName, Collections.singletonList(category), label);
    }

    private static String getThrottleScript(String jobName, List<String> categories, String label) {
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

    static CpsFlowDefinition getDeclarativeJobFlow(String jobName, String categories, String label) throws Exception {
        return new CpsFlowDefinition(
                getDeclarativeThrottleScript(jobName, Collections.singletonList(categories), label), true);
    }

    private static String getDeclarativeThrottleScript(String jobName, List<String> categories, String label) {
        List<String> quoted = new ArrayList<>();
        for (String c : categories) {
            quoted.add("'" + c + "'");
        }

        return "pipeline {\n"
                + "agent none\n"
                + "stages {"
                + "stage('throttle') {\n"
                + "agent { label '"
                + label
                + "'}\n"
                + "options { throttle(["
                + StringUtils.join(quoted, ", ")
                + "]) }\n"
                + "steps {\n"
                + "  semaphore 'wait-"
                + jobName
                + "-job'\n"
                + "}\n"
                + "}\n"
                + "}\n"
                + "}\n";
    }

    @Test
    void snippetizer() throws Exception {
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        SnippetizerTester st = new SnippetizerTester(j);
        st.assertRoundTrip(
                new ThrottleStep(Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName())),
                "throttle(['" + TestUtil.ONE_PER_NODE.getCategoryName() + "']) {\n    // some block\n}");
    }

    /**
     * A variant of {@link ThrottleStepTest#onePerNode} that also ensures that {@link
     * ThrottleJobProperty.DescriptorImpl#throttledPipelinesByCategory} contains copy-on-write data
     * structures.
     */
    @Issue("JENKINS-49006")
    @Test
    void throttledPipelinesByCategoryCopyOnWrite() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(getJobFlow("first", TestUtil.ONE_PER_NODE.getCategoryName(), firstAgent.getNodeName()));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(
                getJobFlow("second", TestUtil.ONE_PER_NODE.getCategoryName(), firstAgent.getNodeName()));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
        assertFalse(j.jenkins.getQueue().isEmpty());
        assertEquals(1, firstAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, firstJobFirstRun);

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        Map<String, List<String>> throttledPipelinesByCategory =
                descriptor.getThrottledPipelinesForCategory(TestUtil.ONE_PER_NODE.getCategoryName());
        assertInstanceOf(CopyOnWriteMap.Tree.class, throttledPipelinesByCategory);
        assertEquals(2, throttledPipelinesByCategory.size());
        for (List<String> flowNodes : throttledPipelinesByCategory.values()) {
            assertInstanceOf(CopyOnWriteArrayList.class, flowNodes);
            assertEquals(1, flowNodes.size());
        }

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, firstAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, secondJobFirstRun);
        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }
}
