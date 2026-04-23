package hudson.plugins.throttleconcurrents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Node;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterDefinition;
import hudson.model.queue.QueueTaskFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ThrottleJobPropertyPipelineTest {

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

        Jenkins.get().setLabelString("built-in");
        Jenkins.get().save();

        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, "on-agent");
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        // two jobs will be created on the slave node and on the built-in node
        Node[] nodes = {agent, Jenkins.get()};

        ArrayList<ArrayList<WorkflowRun>> jobsRuns = new ArrayList<ArrayList<WorkflowRun>>();

        int expectedQueueSize = 1;

        // create two jobs per node
        for (Node node : nodes) {
            ArrayList<WorkflowRun> jobRuns = new ArrayList<WorkflowRun>();

            // create first job
            WorkflowJob firstJob = j.createProject(WorkflowJob.class);
            firstJob.setDefinition(getJobFlow("first" + node.getLabelString(), node.getLabelString()));
            firstJob.addProperty(new ThrottleJobProperty(
                    null, // maxConcurrentPerNode
                    null, // maxConcurrentTotal
                    Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                    true, // throttleEnabled
                    TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                    false,
                    null,
                    ThrottleMatrixProjectOptions.DEFAULT));

            // start first job
            WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait-first" + node.getLabelString() + "-job/1", firstJobFirstRun);

            // create second job
            WorkflowJob secondJob = j.createProject(WorkflowJob.class);
            secondJob.setDefinition(getJobFlow("second" + node.getLabelString(), node.getLabelString()));
            secondJob.addProperty(new ThrottleJobProperty(
                    null, // maxConcurrentPerNode
                    null, // maxConcurrentTotal
                    Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                    true, // throttleEnabled
                    TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                    false,
                    null,
                    ThrottleMatrixProjectOptions.DEFAULT));

            // start second job
            WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
            j.waitForMessage("Still waiting to schedule task", secondJobFirstRun);
            j.jenkins.getQueue().maintain();

            // second job should be blocked as it is at most one running job per node
            assertFalse(j.jenkins.getQueue().isEmpty());
            List<Queue.Item> queuedItemList =
                    Arrays.stream(j.jenkins.getQueue().getItems()).toList();
            // queue size should be 1 after creating jobs on first node and 2 after
            // creating jobs on second node
            assertEquals(expectedQueueSize++, queuedItemList.size());

            // check jobs are blocked because another job is already running on associated node
            for (Queue.Item queuedItem : queuedItemList) {
                Set<String> blockageReasons = TestUtil.getBlockageReasons(queuedItem.getCauseOfBlockage());
                assertThat(
                        blockageReasons,
                        hasItem(Messages._ThrottleQueueTaskDispatcher_MaxCapacityOnNode(1)
                                .toString()));
            }

            // first job should be running
            assertEquals(1, node.toComputer().countBusy());
            TestUtil.hasPlaceholderTaskForRun(node, firstJobFirstRun);

            jobRuns.add(firstJobFirstRun);
            jobRuns.add(secondJobFirstRun);
            jobsRuns.add(jobRuns);
        }

        // terminate first job on each node, check second one can start afterwards
        for (int i = 0; i < nodes.length; ++i) {
            Node node = nodes[i];
            WorkflowRun firstJobFirstRun = jobsRuns.get(i).get(0);
            WorkflowRun secondJobFirstRun = jobsRuns.get(i).get(1);

            // terminate first job
            SemaphoreStep.success("wait-first" + node.getLabelString() + "-job/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));
            SemaphoreStep.waitForStart("wait-second" + node.getLabelString() + "-job/1", secondJobFirstRun);
            j.jenkins.getQueue().maintain();

            if (i == 0) {
                // after terminating first job on first node,
                // second job on second node should still be in the queue
                assertFalse(j.jenkins.getQueue().isEmpty());

            } else {
                // after terminating first job on seconde node,
                // second job on second node should no longer be in the queue
                assertTrue(j.jenkins.getQueue().isEmpty());
            }

            // second job should be running
            assertEquals(1, node.toComputer().countBusy());
            TestUtil.hasPlaceholderTaskForRun(node, secondJobFirstRun);

            // terminate second job
            SemaphoreStep.success("wait-second" + node.getLabelString() + "-job/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

            // no more jobs should be running on the node
            assertEquals(0, node.toComputer().countBusy());
        }
    }

    @Test
    void twoTotal() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.TWO_TOTAL);

        WorkflowJob firstJob = j.createProject(WorkflowJob.class);
        firstJob.setDefinition(getJobFlow("first", firstAgent.getNodeName()));
        firstJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));

        WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

        WorkflowJob secondJob = j.createProject(WorkflowJob.class);
        secondJob.setDefinition(getJobFlow("second", secondAgent.getNodeName()));
        secondJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));

        WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

        WorkflowJob thirdJob = j.createProject(WorkflowJob.class);
        thirdJob.setDefinition(getJobFlow("third", "on-agent"));
        thirdJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));

        QueueTaskFuture<WorkflowRun> thirdJobFirstRunFuture = thirdJob.scheduleBuild2(0);
        j.jenkins.getQueue().maintain();
        assertFalse(j.jenkins.getQueue().isEmpty());
        List<Queue.Item> queuedItemList =
                Arrays.stream(j.jenkins.getQueue().getItems()).toList();
        assertEquals(1, queuedItemList.size());
        Queue.Item queuedItem = queuedItemList.get(0);
        Set<String> blockageReasons = TestUtil.getBlockageReasons(queuedItem.getCauseOfBlockage());
        assertThat(
                blockageReasons,
                hasItem(Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(2)
                        .toString()));
        assertEquals(1, firstAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(firstAgent, firstJobFirstRun);

        assertEquals(1, secondAgent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(secondAgent, secondJobFirstRun);

        SemaphoreStep.success("wait-first-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        WorkflowRun thirdJobFirstRun = thirdJobFirstRunFuture.waitForStart();
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

    @Issue("JENKINS-37809")
    @Test
    void limitOneJobWithMatchingParams() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, null);

        WorkflowJob project = j.createProject(WorkflowJob.class);
        ParametersDefinitionProperty pdp = new ParametersDefinitionProperty(
                new StringParameterDefinition("FOO", "foo", ""), new StringParameterDefinition("BAR", "bar", ""));
        project.addProperty(pdp);
        project.setConcurrentBuild(true);
        project.setDefinition(getJobFlow(project.getName(), agent.getNodeName()));
        project.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.emptyList(),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_PROJECT, // throttleOption
                true,
                "FOO,BAR",
                ThrottleMatrixProjectOptions.DEFAULT));

        WorkflowRun firstRun = project.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait-" + project.getName() + "-job/1", firstRun);

        QueueTaskFuture<WorkflowRun> secondRunFuture = project.scheduleBuild2(0);
        j.jenkins.getQueue().maintain();
        assertFalse(j.jenkins.getQueue().isEmpty());
        List<Queue.Item> queuedItemList =
                Arrays.stream(j.jenkins.getQueue().getItems()).toList();
        assertEquals(1, queuedItemList.size());
        Queue.Item queuedItem = queuedItemList.get(0);
        Set<String> blockageReasons = TestUtil.getBlockageReasons(queuedItem.getCauseOfBlockage());
        assertThat(
                blockageReasons,
                hasItem(Messages._ThrottleQueueTaskDispatcher_OnlyOneWithMatchingParameters()
                        .toString()));
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, firstRun);

        SemaphoreStep.success("wait-" + project.getName() + "-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(firstRun));

        WorkflowRun secondRun = secondRunFuture.waitForStart();
        SemaphoreStep.waitForStart("wait-" + project.getName() + "-job/2", secondRun);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(agent, secondRun);
        SemaphoreStep.success("wait-" + project.getName() + "-job/2", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondRun));
    }

    static CpsFlowDefinition getJobFlow(String jobName, String label) throws Exception {
        return new CpsFlowDefinition(getThrottleScript(jobName, label), true);
    }

    private static String getThrottleScript(String jobName, String label) {
        return "echo 'hi there'\n" + "node('" + label + "') {\n" + "  semaphore 'wait-" + jobName + "-job'\n" + "}\n";
    }
}
