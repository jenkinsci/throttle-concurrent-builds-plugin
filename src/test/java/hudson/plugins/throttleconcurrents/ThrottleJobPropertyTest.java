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
import hudson.model.Executor;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.util.RunList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

public class ThrottleJobPropertyTest {

    private static final String THROTTLE_OPTION_CATEGORY = "category"; // TODO move this into ThrottleJobProperty and use consistently; same for "project"
    private static final String TWO_TOTAL = "two_total";

    private final Random random = new Random(System.currentTimeMillis());

    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public TemporaryFolder firstAgentTmp = new TemporaryFolder();
    @Rule public TemporaryFolder secondAgentTmp = new TemporaryFolder();

    public void setupAgentsAndCategories() throws Exception {
        DumbSlave firstAgent =
                new DumbSlave(
                        "first-agent",
                        "dummy agent",
                        firstAgentTmp.getRoot().getAbsolutePath(),
                        "4",
                        Node.Mode.NORMAL,
                        "on-agent",
                        story.j.createComputerLauncher(null),
                        RetentionStrategy.NOOP,
                        Collections.emptyList());

        DumbSlave secondAgent =
                new DumbSlave(
                        "second-agent",
                        "dummy agent",
                        secondAgentTmp.getRoot().getAbsolutePath(),
                        "4",
                        Node.Mode.NORMAL,
                        "on-agent",
                        story.j.createComputerLauncher(null),
                        RetentionStrategy.NOOP,
                        Collections.emptyList());

        story.j.jenkins.addNode(firstAgent);
        story.j.jenkins.addNode(secondAgent);

        ThrottleJobProperty.ThrottleCategory cat =
                new ThrottleJobProperty.ThrottleCategory(TWO_TOTAL, 0, 2, null);

        ThrottleJobProperty.DescriptorImpl descriptor =
                story.j.jenkins.getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);
        assertNotNull(descriptor);
        descriptor.setCategories(Collections.singletonList(cat));

        // The following is required for tests that restart Jenkins.
        descriptor.save();
    }

    @Issue("JENKINS-19623")
    @Test
    public void testGetCategoryProjects() {
        story.then(
                s -> {
                    String alpha = "alpha", beta = "beta", gamma = "gamma"; // category names
                    FreeStyleProject p1 = story.j.createFreeStyleProject("p1");
                    FreeStyleProject p2 = story.j.createFreeStyleProject("p2");
                    p2.addProperty(
                            new ThrottleJobProperty(
                                    1,
                                    1,
                                    Collections.singletonList(alpha),
                                    false,
                                    THROTTLE_OPTION_CATEGORY,
                                    false,
                                    "",
                                    ThrottleMatrixProjectOptions.DEFAULT));
                    FreeStyleProject p3 = story.j.createFreeStyleProject("p3");
                    p3.addProperty(
                            new ThrottleJobProperty(
                                    1,
                                    1,
                                    Arrays.asList(alpha, beta),
                                    true,
                                    THROTTLE_OPTION_CATEGORY,
                                    false,
                                    "",
                                    ThrottleMatrixProjectOptions.DEFAULT));
                    FreeStyleProject p4 = story.j.createFreeStyleProject("p4");
                    p4.addProperty(
                            new ThrottleJobProperty(
                                    1,
                                    1,
                                    Arrays.asList(beta, gamma),
                                    true,
                                    THROTTLE_OPTION_CATEGORY,
                                    false,
                                    "",
                                    ThrottleMatrixProjectOptions.DEFAULT));
                    // TODO when core dep ≥1.480.3, add cloudbees-folder as a test dependency so we
                    // can check jobs inside folders
                    assertProjects(alpha, p3);
                    assertProjects(beta, p3, p4);
                    assertProjects(gamma, p4);
                    assertProjects("delta");
                    p4.renameTo("p-4");
                    assertProjects(gamma, p4);
                    p4.delete();
                    assertProjects(gamma);
                    AbstractProject<?, ?> p3b =
                            story.j.jenkins.<AbstractProject<?, ?>>copy(p3, "p3b");
                    assertProjects(beta, p3, p3b);
                    p3.removeProperty(ThrottleJobProperty.class);
                    assertProjects(beta, p3b);
                });
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
    public void testThrottleJobConstructorShouldParseParamsToUseForLimit() {
        // We just set these values but do not test much, see above for that
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();
        boolean expectedLimitOneJobWithMatchingParams = true;

        // Mix the comma and whitespace separators as commas were documented
        // in original codebase but did not work so people must have used the
        // whitespaces de-facto.
        // Note that the data type of paramsToUseForLimit is a List (ordered)
        // so in the test we expect it as such. Production code seems to use
        // it as an unordered set as suffices for filtering, however.
        // (0*) Null and empty string inputs should result in an empty list
        // object; surrounding whitespace should be truncated so a non-empty
        // input made of just separators is effectively empty for us too.
        String assignedParamsToUseForLimit00 = null;
        List<String> expectedParamsToUseForLimit00 = new ArrayList<String>();
        String assignedParamsToUseForLimit0 = "";
        List<String> expectedParamsToUseForLimit0 = new ArrayList<String>();
        String assignedParamsToUseForLimit0a = " ";
        List<String> expectedParamsToUseForLimit0a = new ArrayList<String>();
        String assignedParamsToUseForLimit0b = " , ";
        List<String> expectedParamsToUseForLimit0b = new ArrayList<String>();
        String assignedParamsToUseForLimit0c = " ,,,  \n";
        List<String> expectedParamsToUseForLimit0c = new ArrayList<String>();
        // (1) One buildarg name listed in input becomes the only one string
        // in the list; surrounding whitespace should be truncated
        String assignedParamsToUseForLimit1 = "ONE_PARAM";
        List<String> expectedParamsToUseForLimit1 = Arrays.asList("ONE_PARAM");
        String assignedParamsToUseForLimit1a = " ONE_PARAM";
        List<String> expectedParamsToUseForLimit1a = Arrays.asList("ONE_PARAM");
        String assignedParamsToUseForLimit1b = " ONE_PARAM\n";
        List<String> expectedParamsToUseForLimit1b = Arrays.asList("ONE_PARAM");
        // (2) Two buildarg names listed in input become two strings in the list
        String assignedParamsToUseForLimit2 = "TWO,PARAMS";
        List<String> expectedParamsToUseForLimit2 = Arrays.asList("TWO", "PARAMS");
        // (3) Different separators handled the same
        String assignedParamsToUseForLimit3 = "THREE DIFFERENT,PARAMS";
        List<String> expectedParamsToUseForLimit3 = Arrays.asList("THREE", "DIFFERENT", "PARAMS");
        // (4) Several separating tokens together must go away as one - we want
        // no empties in the resulting list
        String assignedParamsToUseForLimit4 = "FOUR ,SOMEWHAT\t,DIFFERENT , PARAMS";
        List<String> expectedParamsToUseForLimit4 = Arrays.asList("FOUR", "SOMEWHAT", "DIFFERENT", "PARAMS");
        // (5) Java does not really have multilines, but still... note that
        // if any whitespace should be there in the carry-over of string
        // representation, it is the coder's responsibility to ensure some.
        String assignedParamsToUseForLimit5 = "Multi\nline" +
                "string,for	kicks\n" +
                "EOL";
        List<String> expectedParamsToUseForLimit5 = Arrays.asList("Multi", "linestring", "for", "kicks", "EOL");

        ThrottleJobProperty property00 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit00,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit00, property00.getParamsToCompare());

        ThrottleJobProperty property0 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0, property0.getParamsToCompare());

        ThrottleJobProperty property0a = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0a,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0a, property0a.getParamsToCompare());

        ThrottleJobProperty property0b = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0b,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0b, property0b.getParamsToCompare());

        ThrottleJobProperty property0c = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0c,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0c, property0c.getParamsToCompare());

        ThrottleJobProperty property1 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1, property1.getParamsToCompare());

        ThrottleJobProperty property1a = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1a,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1a, property1a.getParamsToCompare());

        ThrottleJobProperty property1b = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1b,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1b, property1b.getParamsToCompare());

        ThrottleJobProperty property2 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit2,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit2, property2.getParamsToCompare());

        ThrottleJobProperty property3 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit3,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit3, property3.getParamsToCompare());

        ThrottleJobProperty property4 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit4,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit4, property4.getParamsToCompare());

        ThrottleJobProperty property5 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit5,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit5, property5.getParamsToCompare());
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
                "expected unsafe list to be converted to a converted to some other concurrency-safe impl",
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
        story.then(
                s -> {
                    ThrottleJobProperty.DescriptorImpl descriptor =
                            new ThrottleJobProperty.DescriptorImpl();

                    assertTrue(descriptor.getCategories() instanceof CopyOnWriteArrayList);

                    final ThrottleJobProperty.ThrottleCategory category =
                            new ThrottleJobProperty.ThrottleCategory(
                                    anyString(), anyInt(), anyInt(), null);

                    List<ThrottleJobProperty.ThrottleCategory> unsafeList = Collections.singletonList(category);

                    descriptor.setCategories(unsafeList);
                    List<ThrottleJobProperty.ThrottleCategory> storedCategories =
                            descriptor.getCategories();
                    assertEquals(
                            "contents of original and stored list should be the equal",
                            unsafeList,
                            storedCategories);
                    assertNotSame(
                            "expected unsafe list to be converted to a converted to some other concurrency-safe impl",
                            unsafeList,
                            storedCategories);
                    assertTrue(storedCategories instanceof CopyOnWriteArrayList);
                });
    }

    @Issue("JENKINS-54578")
    @Test
    public void clearConfiguredCategories() {
        story.then(
                s -> {
                    ThrottleJobProperty.DescriptorImpl descriptor =
                            story.j.jenkins.getDescriptorByType(
                                    ThrottleJobProperty.DescriptorImpl.class);
                    assertNotNull(descriptor);

                    // Ensure there are no categories.
                    assertTrue(descriptor.getCategories().isEmpty());

                    // Create a category and save.
                    ThrottleJobProperty.ThrottleCategory cat =
                            new ThrottleJobProperty.ThrottleCategory(
                                    anyString(), anyInt(), anyInt(), null);
                    descriptor.setCategories(Collections.singletonList(cat));
                    assertFalse(descriptor.getCategories().isEmpty());
                    descriptor.save();

                    // Delete the category via the UI and save.
                    JenkinsRule.WebClient webClient = story.j.createWebClient();
                    HtmlPage page = webClient.goTo("configure");
                    WebClientUtil.waitForJSExec(page.getWebClient());
                    HtmlForm config = page.getFormByName("config");
                    List<HtmlButton> deleteButtons =
                            config.getByXPath(
                                    "//td[@class='setting-name' and text()='Multi-Project Throttle Categories']/../td[@class='setting-main']//button[text()='Delete']");
                    assertEquals(1, deleteButtons.size());
                    deleteButtons.get(0).click();
                    WebClientUtil.waitForJSExec(page.getWebClient());
                    story.j.submit(config);

                    // Ensure the category was deleted.
                    assertTrue(descriptor.getCategories().isEmpty());
                });
    }

    private void assertProjects(String category, AbstractProject<?,?>... projects) {
        story.j.jenkins.setAuthorizationStrategy(new RejectAllAuthorizationStrategy());
        try {
            assertEquals(new HashSet<Queue.Task>(Arrays.asList(projects)), new HashSet<>
                    (ThrottleJobProperty.getCategoryTasks(category)));
        } finally {
            story.j.jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED); // do not check during e.g. rebuildDependencyGraph from delete
        }
    }
    private static class RejectAllAuthorizationStrategy extends AuthorizationStrategy {
        RejectAllAuthorizationStrategy() {}
        @Override public ACL getRootACL() {
            return new AuthorizationStrategy.Unsecured().getRootACL();
        }
        @Override public Collection<String> getGroups() {
            return Collections.emptySet();
        }
        @Override public ACL getACL(Job<?,?> project) {
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
    public void twoTotal() {
        story.then(
                s -> {
                    setupAgentsAndCategories();
                    WorkflowJob firstJob =
                            story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                    firstJob.setDefinition(getJobFlow("first", "first-agent"));
                    firstJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                    WorkflowJob secondJob =
                            story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                    secondJob.setDefinition(getJobFlow("second", "second-agent"));
                    secondJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

                    WorkflowJob thirdJob =
                            story.j.jenkins.createProject(WorkflowJob.class, "third-job");
                    thirdJob.setDefinition(getJobFlow("third", "on-agent"));
                    thirdJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    QueueTaskFuture<WorkflowRun> thirdJobFirstRunFuture =
                            thirdJob.scheduleBuild2(0);
                    story.j.jenkins.getQueue().maintain();
                    assertFalse(story.j.jenkins.getQueue().isEmpty());
                    Queue.Item queuedItem =
                            Iterables.getOnlyElement(
                                    Arrays.asList(story.j.jenkins.getQueue().getItems()));
                    assertEquals(
                            Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(2).toString(),
                            queuedItem.getCauseOfBlockage().getShortDescription());
                    Node n = story.j.jenkins.getNode("first-agent");
                    assertNotNull(n);
                    assertEquals(1, n.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n, firstJobFirstRun);

                    Node n2 = story.j.jenkins.getNode("second-agent");
                    assertNotNull(n2);
                    assertEquals(1, n2.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n2, secondJobFirstRun);

                    SemaphoreStep.success("wait-first-job/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(firstJobFirstRun));

                    WorkflowRun thirdJobFirstRun = thirdJobFirstRunFuture.waitForStart();
                    SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
                    assertTrue(story.j.jenkins.getQueue().isEmpty());
                    assertEquals(1, n.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n, thirdJobFirstRun);

                    SemaphoreStep.success("wait-second-job/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(secondJobFirstRun));

                    SemaphoreStep.success("wait-third-job/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(thirdJobFirstRun));
                });
    }

    @Test
    public void twoTotalWithRestart() {
        story.then(
                s -> {
                    setupAgentsAndCategories();
                    WorkflowJob firstJob =
                            story.j.jenkins.createProject(WorkflowJob.class, "first-job");
                    firstJob.setDefinition(getJobFlow("first", "first-agent"));
                    firstJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    WorkflowRun firstJobFirstRun = firstJob.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-first-job/1", firstJobFirstRun);

                    WorkflowJob secondJob =
                            story.j.jenkins.createProject(WorkflowJob.class, "second-job");
                    secondJob.setDefinition(getJobFlow("second", "second-agent"));
                    secondJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    WorkflowRun secondJobFirstRun = secondJob.scheduleBuild2(0).waitForStart();
                    SemaphoreStep.waitForStart("wait-second-job/1", secondJobFirstRun);

                    WorkflowJob thirdJob =
                            story.j.jenkins.createProject(WorkflowJob.class, "third-job");
                    thirdJob.setDefinition(getJobFlow("third", "on-agent"));
                    thirdJob.addProperty(
                            new ThrottleJobProperty(
                                    null, // maxConcurrentPerNode
                                    null, // maxConcurrentTotal
                                    Collections.singletonList(TWO_TOTAL), // categories
                                    true, // throttleEnabled
                                    THROTTLE_OPTION_CATEGORY, // throttleOption
                                    false,
                                    null,
                                    ThrottleMatrixProjectOptions.DEFAULT));

                    thirdJob.scheduleBuild2(0);
                    story.j.jenkins.getQueue().maintain();
                    assertFalse(story.j.jenkins.getQueue().isEmpty());
                    Queue.Item queuedItem =
                            Iterables.getOnlyElement(
                                    Arrays.asList(story.j.jenkins.getQueue().getItems()));
                    assertEquals(
                            Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(2).toString(),
                            queuedItem.getCauseOfBlockage().getShortDescription());
                    Node n = story.j.jenkins.getNode("first-agent");
                    assertNotNull(n);
                    assertEquals(1, n.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n, firstJobFirstRun);

                    Node n2 = story.j.jenkins.getNode("second-agent");
                    assertNotNull(n2);
                    assertEquals(1, n2.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n2, secondJobFirstRun);
                });
        story.then(
                s -> {
                    RunList<WorkflowRun> firstJobBuilds =
                            story.j
                                    .jenkins
                                    .getItemByFullName("first-job", WorkflowJob.class)
                                    .getBuilds();
                    assertEquals(1, firstJobBuilds.size());
                    WorkflowRun firstJobFirstRun = firstJobBuilds.getLastBuild();
                    assertNotNull(firstJobFirstRun);

                    RunList<WorkflowRun> secondJobBuilds =
                            story.j
                                    .jenkins
                                    .getItemByFullName("second-job", WorkflowJob.class)
                                    .getBuilds();
                    assertEquals(1, secondJobBuilds.size());
                    WorkflowRun secondJobFirstRun = secondJobBuilds.getLastBuild();
                    assertNotNull(secondJobFirstRun);

                    story.j.jenkins.getQueue().maintain();
                    while (!story.j.jenkins.getQueue().getBuildableItems().isEmpty()) {
                        Thread.sleep(500);
                        story.j.jenkins.getQueue().maintain();
                    }

                    assertFalse(story.j.jenkins.getQueue().isEmpty());
                    Queue.Item queuedItem =
                            Iterables.getOnlyElement(
                                    Arrays.asList(story.j.jenkins.getQueue().getItems()));
                    assertEquals(
                            Messages._ThrottleQueueTaskDispatcher_MaxCapacityTotal(2).toString(),
                            queuedItem.getCauseOfBlockage().getShortDescription());

                    Node n = story.j.jenkins.getNode("first-agent");
                    assertNotNull(n);
                    assertEquals(1, n.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n, firstJobFirstRun);

                    Node n2 = story.j.jenkins.getNode("second-agent");
                    assertNotNull(n2);
                    assertEquals(1, n2.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n2, secondJobFirstRun);

                    SemaphoreStep.success("wait-first-job/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(firstJobFirstRun));

                    WorkflowRun thirdJobFirstRun =
                            (WorkflowRun) queuedItem.getFuture().waitForStart();
                    SemaphoreStep.waitForStart("wait-third-job/1", thirdJobFirstRun);
                    assertTrue(story.j.jenkins.getQueue().isEmpty());
                    assertEquals(1, n.toComputer().countBusy());
                    hasPlaceholderTaskForRun(n, thirdJobFirstRun);

                    SemaphoreStep.success("wait-second-job/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(secondJobFirstRun));

                    SemaphoreStep.success("wait-third-job/1", null);
                    story.j.assertBuildStatusSuccess(story.j.waitForCompletion(thirdJobFirstRun));
                });
    }

    private static CpsFlowDefinition getJobFlow(String jobName, String label) {
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

    private static void hasPlaceholderTaskForRun(Node n, WorkflowRun r) throws Exception {
        for (Executor exec : n.toComputer().getExecutors()) {
            if (exec.getCurrentExecutable() != null) {
                assertTrue(
                        exec.getCurrentExecutable().getParent()
                                instanceof ExecutorStepExecution.PlaceholderTask);
                ExecutorStepExecution.PlaceholderTask task =
                        (ExecutorStepExecution.PlaceholderTask)
                                exec.getCurrentExecutable().getParent();
                while (task.run() == null) {
                    // Wait for the step context to be ready.
                    Thread.sleep(500);
                }
                assertEquals(r, task.run());
            }
        }
    }
}
