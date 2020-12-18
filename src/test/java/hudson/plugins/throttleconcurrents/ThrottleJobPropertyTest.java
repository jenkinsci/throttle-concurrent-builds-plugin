package hudson.plugins.throttleconcurrents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.gargoylesoftware.htmlunit.WebClientUtil;
import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.google.common.collect.Iterables;

import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
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
import org.jvnet.hudson.test.WithoutJenkins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class ThrottleJobPropertyTest {

    private final Random random = new Random(System.currentTimeMillis());

    @Rule public JenkinsRule j = new JenkinsRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public TemporaryFolder firstAgentTmp = new TemporaryFolder();
    @Rule public TemporaryFolder secondAgentTmp = new TemporaryFolder();

    @Issue("JENKINS-19623")
    @Test
    public void testGetCategoryProjects() throws Exception {
        String alpha = "alpha", beta = "beta", gamma = "gamma"; // category names
        FreeStyleProject p1 = j.createFreeStyleProject("p1");
        FreeStyleProject p2 = j.createFreeStyleProject("p2");
        p2.addProperty(
                new ThrottleJobProperty(
                        1,
                        1,
                        Collections.singletonList(alpha),
                        false,
                        TestUtil.THROTTLE_OPTION_CATEGORY,
                        false,
                        "",
                        ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p3 = j.createFreeStyleProject("p3");
        p3.addProperty(
                new ThrottleJobProperty(
                        1,
                        1,
                        Arrays.asList(alpha, beta),
                        true,
                        TestUtil.THROTTLE_OPTION_CATEGORY,
                        false,
                        "",
                        ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p4 = j.createFreeStyleProject("p4");
        p4.addProperty(
                new ThrottleJobProperty(
                        1,
                        1,
                        Arrays.asList(beta, gamma),
                        true,
                        TestUtil.THROTTLE_OPTION_CATEGORY,
                        false,
                        "",
                        ThrottleMatrixProjectOptions.DEFAULT));
        // TODO when core dep ≥1.480.3, add cloudbees-folder as a test dependency so we can check
        // jobs inside folders
        assertProjects(alpha, p3);
        assertProjects(beta, p3, p4);
        assertProjects(gamma, p4);
        assertProjects("delta");
        p4.renameTo("p-4");
        assertProjects(gamma, p4);
        p4.delete();
        assertProjects(gamma);
        AbstractProject<?, ?> p3b = j.jenkins.<AbstractProject<?, ?>>copy(p3, "p3b");
        assertProjects(beta, p3, p3b);
        p3.removeProperty(ThrottleJobProperty.class);
        assertProjects(beta, p3b);
    }

    @Test
    @WithoutJenkins
    public void testToStringWithNulls() {
        ThrottleJobProperty tjp =
                new ThrottleJobProperty(
                        0, 0, null, false, null, false, "", ThrottleMatrixProjectOptions.DEFAULT);
        assertNotNull(tjp.toString());
    }

    @Test
    @WithoutJenkins
    public void testThrottleJobConstructorShouldStoreArguments() {
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();
        boolean expectedLimitOneJobWithMatchingParams = anyBoolean();
        String expectedParamsToUseForLimit = anyString();

        ThrottleJobProperty property =
                new ThrottleJobProperty(
                        expectedMaxConcurrentPerNode,
                        expectedMaxConcurrentTotal,
                        expectedCategories,
                        expectedThrottleEnabled,
                        expectedThrottleOption,
                        expectedLimitOneJobWithMatchingParams,
                        expectedParamsToUseForLimit,
                        ThrottleMatrixProjectOptions.DEFAULT);

        assertEquals(expectedMaxConcurrentPerNode, property.getMaxConcurrentPerNode());
        assertEquals(expectedMaxConcurrentTotal, property.getMaxConcurrentTotal());
        assertEquals(expectedCategories, property.getCategories());
        assertEquals(expectedThrottleEnabled, property.getThrottleEnabled());
        assertEquals(expectedThrottleOption, property.getThrottleOption());
    }

    @Test
    @WithoutJenkins
    public void testThrottleJobShouldCopyCategoriesToConcurrencySafeList() {
        final String category = anyString();

        ArrayList<String> unsafeList =
                new ArrayList<String>() {
                    {
                        add(category);
                    }
                };

        ThrottleJobProperty property =
                new ThrottleJobProperty(
                        anyInt(),
                        anyInt(),
                        unsafeList,
                        anyBoolean(),
                        "throttle_option",
                        anyBoolean(),
                        anyString(),
                        ThrottleMatrixProjectOptions.DEFAULT);

        List<String> storedCategories = property.getCategories();
        assertEquals(
                "contents of original and stored list should be the equal",
                unsafeList,
                storedCategories);
        assertNotSame(
                "expected unsafe list to be converted to a converted to some other"
                        + " concurrency-safe impl",
                unsafeList,
                storedCategories);
        assertTrue(storedCategories instanceof CopyOnWriteArrayList);
    }

    @Test
    @WithoutJenkins
    public void testThrottleJobConstructorHandlesNullCategories() {
        ThrottleJobProperty property =
                new ThrottleJobProperty(
                        anyInt(),
                        anyInt(),
                        null,
                        anyBoolean(),
                        "throttle_option",
                        anyBoolean(),
                        anyString(),
                        ThrottleMatrixProjectOptions.DEFAULT);

        assertEquals(Collections.emptyList(), property.getCategories());
    }

    @Test
    public void testDescriptorImplShouldAConcurrencySafeListForCategories() {
        ThrottleJobProperty.DescriptorImpl descriptor = new ThrottleJobProperty.DescriptorImpl();

        assertTrue(descriptor.getCategories() instanceof CopyOnWriteArrayList);

        final ThrottleJobProperty.ThrottleCategory category =
                new ThrottleJobProperty.ThrottleCategory(anyString(), anyInt(), anyInt(), null);

        List<ThrottleJobProperty.ThrottleCategory> unsafeList = Collections.singletonList(category);

        descriptor.setCategories(unsafeList);
        List<ThrottleJobProperty.ThrottleCategory> storedCategories = descriptor.getCategories();
        assertEquals(
                "contents of original and stored list should be the equal",
                unsafeList,
                storedCategories);
        assertNotSame(
                "expected unsafe list to be converted to a converted to some other"
                        + " concurrency-safe impl",
                unsafeList,
                storedCategories);
        assertTrue(storedCategories instanceof CopyOnWriteArrayList);
    }

    @Issue("JENKINS-54578")
    @Test
    public void clearConfiguredCategories() throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor =
                j.jenkins.getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);
        assertNotNull(descriptor);

        // Ensure there are no categories.
        assertTrue(descriptor.getCategories().isEmpty());

        // Create a category and save.
        ThrottleJobProperty.ThrottleCategory cat =
                new ThrottleJobProperty.ThrottleCategory(anyString(), anyInt(), anyInt(), null);
        descriptor.setCategories(Collections.singletonList(cat));
        assertFalse(descriptor.getCategories().isEmpty());
        descriptor.save();

        // Delete the category via the UI and save.
        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo("configure");
        WebClientUtil.waitForJSExec(page.getWebClient());
        HtmlForm config = page.getFormByName("config");
        List<HtmlButton> deleteButtons =
                config.getByXPath(
                        "//td[@class='setting-name' and text()='Multi-Project Throttle"
                            + " Categories']/../td[@class='setting-main']//button[text()='Delete']");
        assertEquals(1, deleteButtons.size());
        deleteButtons.get(0).click();
        WebClientUtil.waitForJSExec(page.getWebClient());
        j.submit(config);

        // Ensure the category was deleted.
        assertTrue(descriptor.getCategories().isEmpty());
    }

    private void assertProjects(String category, AbstractProject<?, ?>... projects) {
        j.jenkins.setAuthorizationStrategy(new RejectAllAuthorizationStrategy());
        try {
            assertEquals(
                    new HashSet<Queue.Task>(Arrays.asList(projects)),
                    new HashSet<>(ThrottleJobProperty.getCategoryTasks(category)));
        } finally {
            // do not check during e.g. rebuildDependencyGraph from delete
            j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED);
        }
    }

    private static class RejectAllAuthorizationStrategy extends AuthorizationStrategy {
        RejectAllAuthorizationStrategy() {}

        @Override
        public ACL getRootACL() {
            return new AuthorizationStrategy.Unsecured().getRootACL();
        }

        @Override
        public Collection<String> getGroups() {
            return Collections.emptySet();
        }

        @Override
        public ACL getACL(Job<?, ?> project) {
            fail("not even supposed to be looking at " + project);
            return super.getACL(project);
        }
    }

    private String anyString() {
        return "concurrency_" + anyInt();
    }

    private boolean anyBoolean() {
        return random.nextBoolean();
    }

    private int anyInt() {
        return random.nextInt(10000);
    }

    @Test
    public void twoTotal() throws Exception {
        TestUtil.setupAgentsAndCategories(j, firstAgentTmp, secondAgentTmp);
        WorkflowJob firstJob = j.createProject(WorkflowJob.class, "first-job");
        firstJob.setDefinition(getJobFlow("first", "first-agent"));
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
        secondJob.setDefinition(getJobFlow("second", "second-agent"));
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
        thirdJob.setDefinition(getJobFlow("third", "on-agent"));
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

        QueueTaskFuture<WorkflowRun> thirdJobFirstRunFuture = thirdJob.scheduleBuild2(0);
        j.jenkins.getQueue().maintain();
        assertFalse(j.jenkins.getQueue().isEmpty());
        Queue.Item queuedItem =
                Iterables.getOnlyElement(Arrays.asList(j.jenkins.getQueue().getItems()));
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

        WorkflowRun thirdJobFirstRun = thirdJobFirstRunFuture.waitForStart();
        SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
        assertTrue(j.jenkins.getQueue().isEmpty());
        assertEquals(2, n.toComputer().countBusy() + n2.toComputer().countBusy());
        TestUtil.hasPlaceholderTaskForRun(n, thirdJobFirstRun);

        SemaphoreStep.success("wait-second-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(secondJobFirstRun));

        SemaphoreStep.success("wait-third-job/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(thirdJobFirstRun));
    }

    static CpsFlowDefinition getJobFlow(String jobName, String label) {
        return new CpsFlowDefinition(getThrottleScript(jobName, label), true);
    }

    private static String getThrottleScript(String jobName, String label) {
        return "echo 'hi there'\n"
                + "node('"
                + label
                + "') {\n"
                + "  semaphore 'wait-"
                + jobName
                + "-job'\n"
                + "}\n";
    }
}
