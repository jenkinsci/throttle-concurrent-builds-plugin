package hudson.plugins.throttleconcurrents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Node;
import hudson.model.Queue;
import hudson.util.RunList;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class ThrottleJobPropertyPipelineRestartTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @RegisterExtension
    private static final BuildWatcherExtension buildWatcher = new BuildWatcherExtension();

    @TempDir
    private File firstAgentTmp;

    @TempDir
    private File secondAgentTmp;

    @Test
    void twoTotalWithRestart() throws Throwable {
        String[] jobNames = new String[2];
        String[] agentNames = new String[2];
        sessions.then(j -> {
            List<Node> agents = new ArrayList<>();
            Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
            Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
            agentNames[0] = firstAgent.getNodeName();
            agentNames[1] = secondAgent.getNodeName();
            TestUtil.setupCategories(TestUtil.TWO_TOTAL);

            // The following is required so that the categories remain after Jenkins
            // restarts.
            ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
            assertNotNull(descriptor);
            descriptor.save();

            WorkflowJob firstJob = j.createProject(WorkflowJob.class);
            jobNames[0] = firstJob.getName();
            firstJob.setDefinition(ThrottleJobPropertyPipelineTest.getJobFlow("first", firstAgent.getNodeName()));
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
            jobNames[1] = secondJob.getName();
            secondJob.setDefinition(ThrottleJobPropertyPipelineTest.getJobFlow("second", secondAgent.getNodeName()));
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
            thirdJob.setDefinition(ThrottleJobPropertyPipelineTest.getJobFlow("third", "on-agent"));
            thirdJob.addProperty(new ThrottleJobProperty(
                    null, // maxConcurrentPerNode
                    null, // maxConcurrentTotal
                    Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                    true, // throttleEnabled
                    TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                    false,
                    null,
                    ThrottleMatrixProjectOptions.DEFAULT));

            thirdJob.scheduleBuild2(0);
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
        });
        sessions.then(j -> {
            RunList<WorkflowRun> firstJobBuilds =
                    j.jenkins.getItemByFullName(jobNames[0], WorkflowJob.class).getBuilds();
            assertEquals(1, firstJobBuilds.size());
            WorkflowRun firstJobFirstRun = firstJobBuilds.getLastBuild();
            assertNotNull(firstJobFirstRun);

            RunList<WorkflowRun> secondJobBuilds =
                    j.jenkins.getItemByFullName(jobNames[1], WorkflowJob.class).getBuilds();
            assertEquals(1, secondJobBuilds.size());
            WorkflowRun secondJobFirstRun = secondJobBuilds.getLastBuild();
            assertNotNull(secondJobFirstRun);

            j.jenkins.getQueue().maintain();
            while (!j.jenkins.getQueue().getBuildableItems().isEmpty()) {
                Thread.sleep(500);
                j.jenkins.getQueue().maintain();
            }

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

            Node firstAgent = j.jenkins.getNode(agentNames[0]);
            assertNotNull(firstAgent);
            assertEquals(1, firstAgent.toComputer().countBusy());
            TestUtil.hasPlaceholderTaskForRun(firstAgent, firstJobFirstRun);

            Node secondAgent = j.jenkins.getNode(agentNames[1]);
            assertNotNull(secondAgent);
            assertEquals(1, secondAgent.toComputer().countBusy());
            TestUtil.hasPlaceholderTaskForRun(secondAgent, secondJobFirstRun);

            SemaphoreStep.success("wait-first-job/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

            WorkflowRun thirdJobFirstRun = (WorkflowRun) queuedItem.getFuture().waitForStart();
            SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
            j.jenkins.getQueue().maintain();
            assertTrue(j.jenkins.getQueue().isEmpty());
            assertEquals(
                    2,
                    firstAgent.toComputer().countBusy()
                            + secondAgent.toComputer().countBusy());
            TestUtil.hasPlaceholderTaskForRun(firstAgent, thirdJobFirstRun);

            SemaphoreStep.success("wait-second-job/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

            SemaphoreStep.success("wait-third-job/1", null);
            j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));

            TestUtil.tearDown(j, Arrays.asList(firstAgent, secondAgent));
        });
    }
}
