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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.cloudbees.hudson.plugins.folder.Folder;

import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.plugins.throttleconcurrents.testutils.ExecutorWaterMarkRetentionStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.slaves.SlaveComputer;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tests that {@link ThrottleJobProperty} actually works for builds. */
public class ThrottleJobPropertyFreestyleTest {
    private static final long SLEEP_TIME = 100;

    @Rule public JenkinsRule j = new JenkinsRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public TemporaryFolder firstAgentTmp = new TemporaryFolder();

    private List<Node> agents = new ArrayList<>();
    private List<ExecutorWaterMarkRetentionStrategy<SlaveComputer>> waterMarks = new ArrayList<>();

    /** setup security so that no one except SYSTEM has any permissions. */
    @Before
    public void setupSecurity() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy auth = new GlobalMatrixAuthorizationStrategy();
        j.jenkins.setAuthorizationStrategy(auth);
    }

    /** Clean up agents. */
    @After
    public void tearDown() throws Exception {
        TestUtil.tearDown(j, agents);
        agents = new ArrayList<>();
        waterMarks = new ArrayList<>();
    }

    @Test
    public void testNoThrottling() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, waterMarks, null, 2, null);
        ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark = waterMarks.get(0);

        FreeStyleProject p1 = j.createFreeStyleProject();
        p1.setAssignedNode(agent);
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        FreeStyleProject p2 = j.createFreeStyleProject();
        p2.setAssignedNode(agent);
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);

        j.waitUntilNoActivity();

        // not throttled, and builds run concurrently.
        assertEquals(2, waterMark.getExecutorWaterMark());
    }

    @Test
    public void testThrottlingWithCategoryPerNode() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, waterMarks, null, 2, null);
        ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark = waterMarks.get(0);

        final String category = "category";

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        descriptor.setCategories(
                Collections.singletonList(
                        new ThrottleJobProperty.ThrottleCategory(
                                category,
                                1, // maxConcurrentPerNode
                                null, // maxConcurrentTotal
                                Collections.emptyList())));

        FreeStyleProject p1 = j.createFreeStyleProject();
        p1.setAssignedNode(agent);
        p1.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        FreeStyleProject p2 = j.createFreeStyleProject();
        p2.setAssignedNode(agent);
        p2.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);

        j.waitUntilNoActivity();

        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }

    @Test
    public void testThrottlingWithCategoryTotal() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, waterMarks, null, 2, null);
        ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark = waterMarks.get(0);

        final String category = "category";

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        descriptor.setCategories(
                Collections.singletonList(
                        new ThrottleJobProperty.ThrottleCategory(
                                category,
                                null, // maxConcurrentPerNode
                                1, // maxConcurrentTotal
                                Collections.emptyList())));

        FreeStyleProject p1 = j.createFreeStyleProject();
        p1.setAssignedNode(agent);
        p1.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        FreeStyleProject p2 = j.createFreeStyleProject();
        p2.setAssignedNode(agent);
        p2.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false,
                        null,
                        ThrottleMatrixProjectOptions.DEFAULT));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);

        j.waitUntilNoActivity();

        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }

    @Issue("JENKINS-25326")
    @Test
    public void testThrottlingWithCategoryInFolder() throws Exception {
        Node agent = TestUtil.setupAgent(j, firstAgentTmp, agents, waterMarks, null, 2, null);
        ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark = waterMarks.get(0);

        final String category = "category";

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        descriptor.setCategories(
                Collections.singletonList(
                        new ThrottleJobProperty.ThrottleCategory(
                                category,
                                1, // maxConcurrentPerNode
                                null, // maxConcurrentTotal
                                Collections.emptyList())));

        Folder f1 = j.createProject(Folder.class, "folder1");
        FreeStyleProject p1 = f1.createProject(FreeStyleProject.class, "p");
        p1.setAssignedNode(agent);
        p1.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false, // limitOneJobWithMatchingParams
                        null, // paramsToUse for the previous flag
                        ThrottleMatrixProjectOptions.DEFAULT));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        Folder f2 = j.createProject(Folder.class, "folder2");
        FreeStyleProject p2 = f2.createProject(FreeStyleProject.class, "p");
        p2.setAssignedNode(agent);
        p2.addProperty(
                new ThrottleJobProperty(
                        null, // maxConcurrentPerNode
                        null, // maxConcurrentTotal
                        Collections.singletonList(category), // categories
                        true, // throttleEnabled
                        TestUtil.THROTTLE_OPTION_CATEGORY, // throttleOption
                        false, // limitOneJobWithMatchingParams
                        null, // paramsToUse for the previous flag
                        ThrottleMatrixProjectOptions.DEFAULT));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);

        j.waitUntilNoActivity();

        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }
}
