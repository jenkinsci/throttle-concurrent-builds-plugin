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

import java.util.Arrays;
import java.util.Collections;

public class ThrottleJobPropertyRestartTest {

    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public TemporaryFolder firstAgentTmp = new TemporaryFolder();
    @Rule public TemporaryFolder secondAgentTmp = new TemporaryFolder();

    @Test
    public void twoTotalWithRestart() throws Throwable {
        sessions.then(
                j -> {
                    TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
                    WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
                    firstJob.setDefinition(
                            ThrottleJobPropertyPipelineTest.getJobFlow("first", "first-agent"));
                    firstJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TestUtil.TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                    WorkflowJob secondJob = j.createProject(WorkflowJob.class, "second-job");
                    secondJob.setDefinition(
                            ThrottleJobPropertyPipelineTest.getJobFlow("second", "second-agent"));
                    secondJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TestUtil.TWO_TOTAL), // categories
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
                                    Collections.singletonList(TestUtil.TWO_TOTAL), // categories
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
                    Node n = j.jenkins.getNode("first-agent");
                    assertNotNull(n);
                    assertEquals(1, n.toComputer().countBusy());
                    TestUtil.hasPlaceholderTaskForRun(n, firstJobFirstRun);

                    Node n2 = j.jenkins.getNode("second-agent");
                    assertNotNull(n2);
                    assertEquals(1, n2.toComputer().countBusy());
                    TestUtil.hasPlaceholderTaskForRun(n2, secondJobFirstRun);
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

                    WorkflowRun thirdJobFirstRun =
                            (WorkflowRun) queuedItem.getFuture().waitForStart();
                    SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
                    assertTrue(j.jenkins.getQueue().isEmpty());
                    assertEquals(2, n.toComputer().countBusy() + n2.toComputer().countBusy());
                    TestUtil.hasPlaceholderTaskForRun(n, thirdJobFirstRun);

                    SemaphoreStep.success("wait-second-job/1", null);
                    j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

                    SemaphoreStep.success("wait-third-job/1", null);
                    j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
                });
    }
}
