package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Iterables;

import hudson.model.Node;
import hudson.model.Queue;
import hudson.util.RunList;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsSessionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ThrottleJobPropertyPipelineRestartTest {

    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public TemporaryFolder firstAgentTmp = new TemporaryFolder();
    @Rule public TemporaryFolder secondAgentTmp = new TemporaryFolder();

    @Test
    public void twoTotalWithRestart() throws Throwable {
        String[] agentNames = new String[2];
        sessions.then(
                j -> {
                    List<Node> agents = new ArrayList<>();
                    Node firstAgent =
                            TestUtil.setupAgent(
                                    j, firstAgentTmp, agents, null, null, 4, "on-agent");
                    Node secondAgent =
                            TestUtil.setupAgent(
                                    j, secondAgentTmp, agents, null, null, 4, "on-agent");
                    agentNames[0] = firstAgent.getNodeName();
                    agentNames[1] = secondAgent.getNodeName();
                    TestUtil.setupCategories(TestUtil.TWO_TOTAL);

                    // The following is required so that the categories remain after Jenkins
                    // restarts.
                    ThrottleJobProperty.DescriptorImpl descriptor =
                            ThrottleJobProperty.fetchDescriptor();
                    assertNotNull(descriptor);
                    descriptor.save();

                    WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
                    firstJob.setDefinition(
                            ThrottleJobPropertyPipelineTest.getJobFlow(
                                    "first", firstAgent.getNodeName()));
                    firstJob.addProperty(
                            new ThrottleJobProperty(
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

                    WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
                    secondJob.setDefinition(
                            ThrottleJobPropertyPipelineTest.getJobFlow(
                                    "second", secondAgent.getNodeName()));
                    secondJob.addProperty(
                            new ThrottleJobProperty(
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

                    WorkflowJob thirdJob = j.createProject(WorkflowJob.class, "third-job");
                    thirdJob.setDefinition(
                            ThrottleJobPropertyPipelineTest.getJobFlow("third", "on-agent"));
                    thirdJob.addProperty(
                            new ThrottleJobProperty(
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
                    Queue.Item queuedItem =
                            Iterables.getOnlyElement(
                                    Arrays.asList(j.jenkins.getQueue().getItems()));
                    assertEquals(
                            Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(2).toString(),
                            queuedItem.getCauseOfBlockage().getShortDescription());
                    assertEquals(1, firstAgent.toComputer().countBusy());
                    TestUtil.hasPlaceholderTaskForRun(firstAgent, firstJobFirstRun);

                    assertEquals(1, secondAgent.toComputer().countBusy());
                    TestUtil.hasPlaceholderTaskForRun(secondAgent, secondJobFirstRun);
                });
        sessions.then(
                j -> {
                    RunList<WorkflowRun> firstJobBuilds =
                            j.jenkins.getItemByFullName("first-job", WorkflowJob.class).getBuilds();
                    assertEquals(1, firstJobBuilds.size());
                    WorkflowRun firstJobFirstRun = firstJobBuilds.getLastBuild();
                    assertNotNull(firstJobFirstRun);

                    RunList<WorkflowRun> secondJobBuilds =
                            j.jenkins
                                    .getItemByFullName("second-job", WorkflowJob.class)
                                    .getBuilds();
                    assertEquals(1, secondJobBuilds.size());
                    WorkflowRun secondJobFirstRun = secondJobBuilds.getLastBuild();
                    assertNotNull(secondJobFirstRun);

                    j.jenkins.getQueue().maintain();
                    while (!j.jenkins.getQueue().getBuildableItems().isEmpty()) {
                        Thread.sleep(500);
                        j.jenkins.getQueue().maintain();
                    }

                    assertFalse(j.jenkins.getQueue().isEmpty());
                    Queue.Item queuedItem =
                            Iterables.getOnlyElement(
                                    Arrays.asList(j.jenkins.getQueue().getItems()));
                    assertEquals(
                            Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(2).toString(),
                            queuedItem.getCauseOfBlockage().getShortDescription());

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

                    WorkflowRun thirdJobFirstRun =
                            (WorkflowRun) queuedItem.getFuture().waitForStart();
                    SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
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
                });
    }
}
