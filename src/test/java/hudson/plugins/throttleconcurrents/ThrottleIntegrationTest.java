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

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.EnvVars;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty.NodeLabeledPair;
import hudson.plugins.throttleconcurrents.testutils.ExecutorWaterMarkRetentionStrategy;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.slaves.SlaveComputer;

import java.util.Arrays;
import java.util.Collections;

import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.HudsonTestCase;
import org.jvnet.hudson.test.SleepBuilder;

/*
import com.cloudbees.hudson.plugins.folder.Folder;
*/

/**
 * Tests that {@link ThrottleJobProperty} actually works for builds.
 */
public class ThrottleIntegrationTest extends HudsonTestCase {
    private final long SLEEP_TIME = 100;
    private int executorNum = 2;
    private ExecutorWaterMarkRetentionStrategy<SlaveComputer> waterMark;
    private DumbSlave slave = null;
    
    /**
     * Overrides to modify the number of executor.
     */
    @Override
    public DumbSlave createSlave(String nodeName, String labels, EnvVars env) throws Exception {
        synchronized (jenkins) {
            DumbSlave slave = new DumbSlave(
                    nodeName,
                    "dummy",
                    createTmpDir().getPath(),
                    Integer.toString(executorNum),      // Overridden!
                    Mode.NORMAL,
                    labels==null?"":labels,
                    createComputerLauncher(env),
                    RetentionStrategy.NOOP,
                    Collections.<NodeProperty<?>>emptyList()
            );
            jenkins.addNode(slave);
            return slave;
        }
    }
    
    /**
     * sets up slave and waterMark.
     */
    private void setupSlave() throws Exception {
        slave = createOnlineSlave();
        waterMark = new ExecutorWaterMarkRetentionStrategy<SlaveComputer>(slave.getRetentionStrategy());
        slave.setRetentionStrategy(waterMark);
    }
    
    /**
     * setup security so that no one except SYSTEM has any permissions.
     * should be called after {@link #setupSlave()}
     */
    private void setupSecurity() {
        jenkins.setSecurityRealm(createDummySecurityRealm());
        GlobalMatrixAuthorizationStrategy auth = new GlobalMatrixAuthorizationStrategy();
        jenkins.setAuthorizationStrategy(auth);
    }
    
    public void testNoThrottling() throws Exception {
        setupSlave();
        setupSecurity();
        
        FreeStyleProject p1 = createFreeStyleProject();
        p1.setAssignedNode(slave);
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        FreeStyleProject p2 = createFreeStyleProject();
        p2.setAssignedNode(slave);
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);
        
        waitUntilNoActivity();
        
        // not throttled, and builds run concurrently.
        assertEquals(2, waterMark.getExecutorWaterMark());
    }
    
    public void testThrottlingWithCategoryPerNode() throws Exception {
        setupSlave();
        setupSecurity();
        final String category = "category";
        
        ThrottleJobProperty.DescriptorImpl descriptor
            = (ThrottleJobProperty.DescriptorImpl)jenkins.getDescriptor(ThrottleJobProperty.class);
        descriptor.setCategories(Arrays.asList(
                new ThrottleJobProperty.ThrottleCategory(
                        category,
                        1,      // maxConcurrentPerNode
                        null,   // maxConcurrentTotal
                        Collections.<NodeLabeledPair>emptyList()
                )
        ));
        
        FreeStyleProject p1 = createFreeStyleProject();
        p1.setAssignedNode(slave);
        p1.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Arrays.asList(category),      // categories
                true,   // throttleEnabled
                "category",     // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        FreeStyleProject p2 = createFreeStyleProject();
        p2.setAssignedNode(slave);
        p2.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Arrays.asList(category),      // categories
                true,   // throttleEnabled
                "category",     // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);
        
        waitUntilNoActivity();
        
        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }
    
    public void testThrottlingWithCategoryTotal() throws Exception {
        setupSlave();
        setupSecurity();
        final String category = "category";

        ThrottleJobProperty.DescriptorImpl descriptor =
                (ThrottleJobProperty.DescriptorImpl) jenkins.getDescriptor(ThrottleJobProperty.class);
        descriptor.setCategories(Arrays.asList(new ThrottleJobProperty.ThrottleCategory(
                category,
                null, // maxConcurrentPerNode
                1, // maxConcurrentTotal
                Collections.<NodeLabeledPair>emptyList())));

        FreeStyleProject p1 = createFreeStyleProject();
        p1.setAssignedNode(slave);
        p1.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Arrays.asList(category), // categories
                true, // throttleEnabled
                "category", // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        FreeStyleProject p2 = createFreeStyleProject();
        p2.setAssignedNode(slave);
        p2.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Arrays.asList(category), // categories
                true, // throttleEnabled
                "category", // throttleOption
                false,
                null,
                ThrottleMatrixProjectOptions.DEFAULT));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));

        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);

        waitUntilNoActivity();

        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }

    @Bug(25326)
    public void testThrottlingWithCategoryInFolder() throws Exception {
        setupSlave();
        setupSecurity();
        final String category = "category";
        
        ThrottleJobProperty.DescriptorImpl descriptor
            = (ThrottleJobProperty.DescriptorImpl)jenkins.getDescriptor(ThrottleJobProperty.class);
        descriptor.setCategories(Arrays.asList(
                new ThrottleJobProperty.ThrottleCategory(
                        category,
                        1,      // maxConcurrentPerNode
                        null,   // maxConcurrentTotal
                        Collections.<NodeLabeledPair>emptyList()
                )
        ));
        
        Folder f1 = jenkins.createProject(Folder.class, "folder1");
        FreeStyleProject p1 = f1.createProject(FreeStyleProject.class, "p");
        p1.setAssignedNode(slave);
        p1.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Arrays.asList(category),      // categories
                true,   // throttleEnabled
                "category",     // throttleOption
                false,  // limitOneJobWithMatchingParams
                null,   // paramsToUse for the previous flag
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p1.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        Folder f2 = jenkins.createProject(Folder.class, "folder2");
        FreeStyleProject p2 = f2.createProject(FreeStyleProject.class, "p");
        p2.setAssignedNode(slave);
        p2.addProperty(new ThrottleJobProperty(
                null, // maxConcurrentPerNode
                null, // maxConcurrentTotal
                Arrays.asList(category),      // categories
                true,   // throttleEnabled
                "category",     // throttleOption
                false,  // limitOneJobWithMatchingParams
                null,   // paramsToUse for the previous flag
                ThrottleMatrixProjectOptions.DEFAULT
        ));
        p2.getBuildersList().add(new SleepBuilder(SLEEP_TIME));
        
        p1.scheduleBuild2(0);
        p2.scheduleBuild2(0);
        
        waitUntilNoActivity();
        
        // throttled, and only one build runs at the same time.
        assertEquals(1, waterMark.getExecutorWaterMark());
    }
}
