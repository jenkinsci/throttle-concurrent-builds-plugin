/*
 * The MIT License
 *
 * Copyright (c) 2016 IKEDA Yasuyuki
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.plugins.throttleconcurrents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterDefinition;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SequenceLock;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Tests that {@link ThrottleJobProperty} actually works for builds. */
@WithJenkins
class ThrottleJobPropertyFreestyleTest {

    private JenkinsRule j;

    @TempDir
    private File firstAgentTmp;

    @TempDir
    private File secondAgentTmp;

    private List<Node> agents = new ArrayList<>();

    /** setup security so that no one except SYSTEM has any permissions. */
    @BeforeEach
    void setupSecurity(JenkinsRule j) {
        this.j = j;
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy auth = new GlobalMatrixAuthorizationStrategy();
        j.jenkins.setAuthorizationStrategy(auth);
    }

    /** Clean up agents. */
    @AfterEach
    void tearDown() throws Exception {
        TestUtil.tearDown(j, agents);
        agents = new ArrayList<>();
    }

    @Test
    void testNoThrottling() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, null);

        FreeStyleProject p1 = j.createFreeStyleProject();
        SequenceLock seq1 = new SequenceLock();
        p1.setAssignedNode(agent);
        p1.getBuildersList().add(new SequenceLockBuilder(seq1));

        FreeStyleProject p2 = j.createFreeStyleProject();
        SequenceLock seq2 = new SequenceLock();
        p2.setAssignedNode(agent);
        p2.getBuildersList().add(new SequenceLockBuilder(seq2));

        FreeStyleBuild b1 = p1.scheduleBuild2(0).waitForStart();
        FreeStyleBuild b2 = p2.scheduleBuild2(0).waitForStart();

        seq1.phase(1);
        seq2.phase(1);

        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(2, agent.toComputer().countBusy());

        seq1.done();
        seq2.done();

        j.assertBuildStatusSuccess(j.waitForCompletion(b1));
        j.assertBuildStatusSuccess(j.waitForCompletion(b2));
    }

    @Test
    void onePerNode() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, null);
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        FreeStyleProject firstJob = j.createFreeStyleProject();
        firstJob.setAssignedNode(agent);
        firstJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        SequenceLock firstJobSeq = new SequenceLock();
        firstJob.getBuildersList().add(new SequenceLockBuilder(firstJobSeq));

        FreeStyleBuild firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        firstJobSeq.phase(1);

        FreeStyleProject secondJob = j.createFreeStyleProject();
        secondJob.setAssignedNode(agent);
        secondJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        SequenceLock secondJobSeq = new SequenceLock();
        secondJob.getBuildersList().add(new SequenceLockBuilder(secondJobSeq));

        QueueTaskFuture<FreeStyleBuild> secondJobFirstRunFuture = secondJob.scheduleBuild2(0);
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

        firstJobSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        FreeStyleBuild secondJobFirstRun = secondJobFirstRunFuture.waitForStart();
        secondJobSeq.phase(1);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        secondJobSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));
    }

    @Test
    void twoTotal() throws Exception {
        Node firstAgent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 4, "on-agent");
        Node secondAgent = TestUtil.setupAgent(j, secondAgentTmp, agents, null, 4, "on-agent");
        TestUtil.setupCategories(TestUtil.TWO_TOTAL);

        FreeStyleProject firstJob = j.createFreeStyleProject();
        firstJob.setAssignedNode(firstAgent);
        firstJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        SequenceLock firstJobSeq = new SequenceLock();
        firstJob.getBuildersList().add(new SequenceLockBuilder(firstJobSeq));

        FreeStyleBuild firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
        firstJobSeq.phase(1);

        FreeStyleProject secondJob = j.createFreeStyleProject();
        secondJob.setAssignedNode(secondAgent);
        secondJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        SequenceLock secondJobSeq = new SequenceLock();
        secondJob.getBuildersList().add(new SequenceLockBuilder(secondJobSeq));

        FreeStyleBuild secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
        secondJobSeq.phase(1);

        FreeStyleProject thirdJob = j.createFreeStyleProject();
        thirdJob.setAssignedLabel(Label.get("on-agent"));
        thirdJob.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.TWO_TOTAL.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        SequenceLock thirdJobSeq = new SequenceLock();
        thirdJob.getBuildersList().add(new SequenceLockBuilder(thirdJobSeq));

        QueueTaskFuture<FreeStyleBuild> thirdJobFirstRunFuture = thirdJob.scheduleBuild2(0);
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

        assertEquals(1, secondAgent.toComputer().countBusy());

        firstJobSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(firstJobFirstRun));

        FreeStyleBuild thirdJobFirstRun = thirdJobFirstRunFuture.waitForStart();
        thirdJobSeq.phase(1);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(
                2,
                firstAgent.toComputer().countBusy() + secondAgent.toComputer().countBusy());

        secondJobSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

        thirdJobSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
    }

    @Test
    void limitOneJobWithMatchingParams() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, null);

        FreeStyleProject project = j.createFreeStyleProject();
        project.setAssignedNode(agent);
        ParametersDefinitionProperty pdp = new ParametersDefinitionProperty(
                new StringParameterDefinition("FOO", "foo", ""), new StringParameterDefinition("BAR", "bar", ""));
        project.addProperty(pdp);
        project.setConcurrentBuild(true);
        project.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.emptyList(),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_PROJECT, // throttleOption
                true,
                "FOO,BAR",
                ThrottleMatrixProjectOptions.DEFAULT));
        SequenceLock firstRunSeq = new SequenceLock();
        SequenceLock secondRunSeq = new SequenceLock();
        project.getBuildersList().add(new SequenceLockBuilder(firstRunSeq, secondRunSeq));

        FreeStyleBuild firstRun = project.scheduleBuild2(0).waitForStart();
        firstRunSeq.phase(1);

        QueueTaskFuture<FreeStyleBuild> secondRunFuture = project.scheduleBuild2(0);
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

        firstRunSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(firstRun));

        FreeStyleBuild secondRun = secondRunFuture.waitForStart();
        secondRunSeq.phase(1);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        secondRunSeq.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(secondRun));
    }

    @Issue("JENKINS-25326")
    @Test
    void testThrottlingWithCategoryInFolder() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, null, 2, null);
        TestUtil.setupCategories(TestUtil.ONE_PER_NODE);

        Folder f1 = j.createProject(Folder.class, "folder1");
        FreeStyleProject p1 = f1.createProject(FreeStyleProject.class, "p");
        SequenceLock seq1 = new SequenceLock();
        p1.setAssignedNode(agent);
        p1.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false, // limitOneJobWithMatchingParams
                null, // paramsToUse for the previous flag
                ThrottleMatrixProjectOptions.DEFAULT));
        p1.getBuildersList().add(new SequenceLockBuilder(seq1));

        Folder f2 = j.createProject(Folder.class, "folder2");
        FreeStyleProject p2 = f2.createProject(FreeStyleProject.class, "p");
        SequenceLock seq2 = new SequenceLock();
        p2.setAssignedNode(agent);
        p2.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Collections.singletonList(TestUtil.ONE_PER_NODE.getCategoryName()),
                true, // throttleEnabled
                TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                false, // limitOneJobWithMatchingParams
                null, // paramsToUse for the previous flag
                ThrottleMatrixProjectOptions.DEFAULT));
        p2.getBuildersList().add(new SequenceLockBuilder(seq2));

        FreeStyleBuild b1 = p1.scheduleBuild2(0).waitForStart();
        seq1.phase(1);

        QueueTaskFuture<FreeStyleBuild> b2future = p2.scheduleBuild2(0);
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

        seq1.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(b1));

        FreeStyleBuild secondRun = b2future.waitForStart();
        seq2.phase(1);
        j.jenkins.getQueue().maintain();
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(1, agent.toComputer().countBusy());
        seq2.done();
        j.assertBuildStatusSuccess(j.waitForCompletion(secondRun));
    }

    private static class SequenceLockBuilder extends TestBuilder {

        private final List<SequenceLock> sequenceLocks;

        private SequenceLockBuilder(SequenceLock... sequenceLock) {
            this.sequenceLocks = Arrays.asList(sequenceLock);
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            for (int i = 0; i < sequenceLocks.size(); i++) {
                if ((build.number - 1) == i) {
                    sequenceLocks.get(i).phase(0);
                    sequenceLocks.get(i).phase(2);
                }
            }
            return true;
        }
    }
}
