package hudson.plugins.throttleconcurrents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.util.CopyOnWriteMap;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.htmlunit.WebClientUtil;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jvnet.hudson.test.recipes.LocalData;

@WithJenkins
class ThrottleJobPropertyTest {

    private static final Random random = new Random(System.currentTimeMillis());

    @Issue("JENKINS-19623")
    @Test
    void testGetCategoryProjects(JenkinsRule j) throws Exception {
        String alpha = "alpha", beta = "beta", gamma = "gamma"; // category names
        FreeStyleProject p1 = j.createFreeStyleProject("p1");
        FreeStyleProject p2 = j.createFreeStyleProject("p2");
        p2.addProperty(new ThrottleJobProperty(
                1,
                1,
                Collections.singletonList(alpha),
                false,
                TestUtil.THROTTLE_OPTION_CATEGORY,
                false,
                "",
                ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p3 = j.createFreeStyleProject("p3");
        p3.addProperty(new ThrottleJobProperty(
                1,
                1,
                Arrays.asList(alpha, beta),
                true,
                TestUtil.THROTTLE_OPTION_CATEGORY,
                false,
                "",
                ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p4 = j.createFreeStyleProject("p4");
        p4.addProperty(new ThrottleJobProperty(
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
        assertProjects(j, alpha, p3);
        assertProjects(j, beta, p3, p4);
        assertProjects(j, gamma, p4);
        assertProjects(j, "delta");
        p4.renameTo("p-4");
        assertProjects(j, gamma, p4);
        p4.delete();
        assertProjects(j, gamma);
        AbstractProject<?, ?> p3b = j.jenkins.<AbstractProject<?, ?>>copy(p3, "p3b");
        assertProjects(j, beta, p3, p3b);
        p3.removeProperty(ThrottleJobProperty.class);
        assertProjects(j, beta, p3b);
    }

    @Test
    @WithoutJenkins
    void testToStringWithNulls() {
        ThrottleJobProperty tjp =
                new ThrottleJobProperty(0, 0, null, false, null, false, "", ThrottleMatrixProjectOptions.DEFAULT);
        assertNotNull(tjp.toString());
    }

    @Test
    @WithoutJenkins
    void testThrottleJobConstructorShouldStoreArguments() {
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();
        boolean expectedLimitOneJobWithMatchingParams = anyBoolean();
        String expectedParamsToUseForLimit = anyString();

        ThrottleJobProperty property = new ThrottleJobProperty(
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

    @Issue("JENKINS-46858")
    @Test
    @WithoutJenkins
    void testThrottleJobConstructorShouldParseParamsToUseForLimit() {
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();
        boolean expectedLimitOneJobWithMatchingParams = true;

        // Mix the comma and whitespace separators as commas were documented in the original
        // codebase but did not work so people must have used whitespace de-facto.

        // Note that the data type of paramsToUseForLimit is a List (ordered) so in the test we
        // expect it as such. Production code seems to use it as an unordered set as suffices for
        // filtering, however.

        // (0*) Null and empty string inputs should result in an empty list object; surrounding
        // whitespace should be truncated so a non-empty input made of just separators is
        // effectively empty for us too.
        String assignedParamsToUseForLimit00 = null;
        List<String> expectedParamsToUseForLimit00 = new ArrayList<>();
        ThrottleJobProperty property00 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit00,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit00, property00.getParamsToCompare());

        String assignedParamsToUseForLimit0 = "";
        List<String> expectedParamsToUseForLimit0 = new ArrayList<>();
        ThrottleJobProperty property0 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0, property0.getParamsToCompare());

        String assignedParamsToUseForLimit0a = " ";
        List<String> expectedParamsToUseForLimit0a = new ArrayList<>();
        ThrottleJobProperty property0a = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0a,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0a, property0a.getParamsToCompare());

        String assignedParamsToUseForLimit0b = " , ";
        List<String> expectedParamsToUseForLimit0b = new ArrayList<>();
        ThrottleJobProperty property0b = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0b,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0b, property0b.getParamsToCompare());

        String assignedParamsToUseForLimit0c = " ,,,  \n";
        List<String> expectedParamsToUseForLimit0c = new ArrayList<>();
        ThrottleJobProperty property0c = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0c,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0c, property0c.getParamsToCompare());

        // (1) One buildarg name listed in the input becomes the only one string in the list;
        // surrounding whitespace should be truncated.
        String assignedParamsToUseForLimit1 = "ONE_PARAM";
        List<String> expectedParamsToUseForLimit1 = Collections.singletonList("ONE_PARAM");
        ThrottleJobProperty property1 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1, property1.getParamsToCompare());

        String assignedParamsToUseForLimit1a = " ONE_PARAM";
        List<String> expectedParamsToUseForLimit1a = Collections.singletonList("ONE_PARAM");
        ThrottleJobProperty property1a = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1a,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1a, property1a.getParamsToCompare());

        String assignedParamsToUseForLimit1b = " ONE_PARAM\n";
        List<String> expectedParamsToUseForLimit1b = Collections.singletonList("ONE_PARAM");
        ThrottleJobProperty property1b = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1b,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1b, property1b.getParamsToCompare());

        // (2) Two buildarg names listed in the input become two strings in the list.
        String assignedParamsToUseForLimit2 = "TWO,PARAMS";
        List<String> expectedParamsToUseForLimit2 = Arrays.asList("TWO", "PARAMS");
        ThrottleJobProperty property2 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit2,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit2, property2.getParamsToCompare());

        // (3) Different separators are handled the same.
        String assignedParamsToUseForLimit3 = "THREE DIFFERENT,PARAMS";
        List<String> expectedParamsToUseForLimit3 = Arrays.asList("THREE", "DIFFERENT", "PARAMS");
        ThrottleJobProperty property3 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit3,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit3, property3.getParamsToCompare());

        // (4) Several separating tokens together must go away as one: we want no empties in the
        // resulting list.
        String assignedParamsToUseForLimit4 = "FOUR ,SOMEWHAT\t,DIFFERENT , PARAMS";
        List<String> expectedParamsToUseForLimit4 = Arrays.asList("FOUR", "SOMEWHAT", "DIFFERENT", "PARAMS");
        ThrottleJobProperty property4 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit4,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit4, property4.getParamsToCompare());

        // (5) Java does not really have multilines, but still... note that if any whitespace should
        // be there in the carry-over of string representation, it is the coder's responsibility to
        // ensure some.
        String assignedParamsToUseForLimit5 = """
                Multi
                linestring,for	kicks
                EOL""";
        List<String> expectedParamsToUseForLimit5 = Arrays.asList("Multi", "linestring", "for", "kicks", "EOL");
        ThrottleJobProperty property5 = new ThrottleJobProperty(
                expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories,
                expectedThrottleEnabled,
                expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit5,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit5, property5.getParamsToCompare());
    }

    @Test
    @WithoutJenkins
    void testThrottleJobShouldCopyCategoriesToConcurrencySafeList() {
        final String category = anyString();

        List<String> unsafeList = new ArrayList<>();
        unsafeList.add(category);

        ThrottleJobProperty property = new ThrottleJobProperty(
                anyInt(),
                anyInt(),
                unsafeList,
                anyBoolean(),
                "throttle_option",
                anyBoolean(),
                anyString(),
                ThrottleMatrixProjectOptions.DEFAULT);

        List<String> storedCategories = property.getCategories();
        assertEquals(unsafeList, storedCategories, "contents of original and stored list should be the equal");
        assertNotSame(
                unsafeList,
                storedCategories,
                "expected unsafe list to be converted to a converted to some other" + " concurrency-safe impl");
        assertInstanceOf(CopyOnWriteArrayList.class, storedCategories);
    }

    @Test
    @WithoutJenkins
    void testThrottleJobConstructorHandlesNullCategories() {
        ThrottleJobProperty property = new ThrottleJobProperty(
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
    void testDescriptorImplShouldAConcurrencySafeListForCategories(JenkinsRule j) {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);

        assertInstanceOf(CopyOnWriteArrayList.class, descriptor.getCategories());

        final ThrottleJobProperty.ThrottleCategory category =
                new ThrottleJobProperty.ThrottleCategory(anyString(), anyInt(), anyInt(), null);

        List<ThrottleJobProperty.ThrottleCategory> unsafeList = Collections.singletonList(category);

        descriptor.setCategories(unsafeList);
        List<ThrottleJobProperty.ThrottleCategory> storedCategories = descriptor.getCategories();
        assertEquals(unsafeList, storedCategories, "contents of original and stored list should be the equal");
        assertNotSame(
                unsafeList,
                storedCategories,
                "expected unsafe list to be converted to a converted to some other" + " concurrency-safe impl");
        assertInstanceOf(CopyOnWriteArrayList.class, storedCategories);
    }

    /**
     * Ensures that data serialized prior to the fix for JENKINS-49006 is correctly converted to
     * copy-on-write data structures upon deserialization.
     */
    @Issue("JENKINS-49006")
    @LocalData
    @Test
    void throttledPipelinesByCategoryMigratesOldData(JenkinsRule j) {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);

        Map<String, List<String>> throttledPipelinesByCategory =
                descriptor.getThrottledPipelinesForCategory(TestUtil.TWO_TOTAL.getCategoryName());
        assertInstanceOf(CopyOnWriteMap.Tree.class, throttledPipelinesByCategory);
        assertEquals(3, throttledPipelinesByCategory.size());
        assertEquals(
                new HashSet<>(Arrays.asList("first-job#1", "second-job#1", "third-job#1")),
                throttledPipelinesByCategory.keySet());
        for (List<String> flowNodes : throttledPipelinesByCategory.values()) {
            assertInstanceOf(CopyOnWriteArrayList.class, flowNodes);
            assertEquals(1, flowNodes.size());
            assertEquals("3", flowNodes.get(0));
        }
    }

    @Issue("JENKINS-54578")
    @Test
    void clearConfiguredCategories(JenkinsRule j) throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
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
        List<HtmlButton> deleteButtons = config.getByXPath(
                "//div[text()='Multi-Project Throttle Categories']/../div//button[@title='Delete' or normalize-space(string(.)) = 'Delete']");
        assertEquals(1, deleteButtons.size());
        deleteButtons.get(0).click();
        WebClientUtil.waitForJSExec(page.getWebClient());
        j.submit(config);

        // Ensure the category was deleted.
        assertTrue(descriptor.getCategories().isEmpty());
    }

    @Test
    void sharedHardwareFallbackCapacityFactorIsPersisted(JenkinsRule j) throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);

        // Set fallback capacity factor to 7 with custom label keys
        SharedHardwareThrottleManager manager =
                new SharedHardwareThrottleManager("CUSTOM_MACHINE_LABEL", "CUSTOM_MAX_LABEL", new ArrayList<>(), 7);
        descriptor.setSharedHardwareThrottle(manager);
        descriptor.save();

        // Verify it was saved with all custom values
        SharedHardwareThrottleManager saved = descriptor.getSharedHardwareThrottle();
        assertEquals("CUSTOM_MACHINE_LABEL", saved.getLabelKey());
        assertEquals("CUSTOM_MAX_LABEL", saved.getMaxLabelKey());
        assertEquals(7, saved.getFallbackCapacityFactor());

        // Reload the configuration
        descriptor.load();

        // Verify all values were restored, including the fallback capacity factor
        SharedHardwareThrottleManager restored = descriptor.getSharedHardwareThrottle();
        assertEquals("CUSTOM_MACHINE_LABEL", restored.getLabelKey());
        assertEquals("CUSTOM_MAX_LABEL", restored.getMaxLabelKey());
        assertEquals(7, restored.getFallbackCapacityFactor());
    }

    @Test
    void sharedHardwareConfigurationIsPersistedViaUIFormSubmission(JenkinsRule j) throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);

        // Simulate UI form submission with shared hardware throttle configuration
        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo("configure");
        WebClientUtil.waitForJSExec(page.getWebClient());
        HtmlForm form = page.getFormByName("config");

        // Set shared hardware throttle values via form fields
        form.getInputByName("_.labelKey").setValue("LABEL_KEY_FROM_UI");
        form.getInputByName("_.maxLabelKey").setValue("MAX_LABEL_KEY_FROM_UI");
        form.getInputByName("_.fallbackCapacityFactor").setValue("42");

        String advancedButtonXPath = "//div[contains(@class,'advancedBody')]"
                + "[.//button[contains(@class,'repeatable-add') and normalize-space(.)='Add Machine']]"
                + "/preceding-sibling::div[contains(@class,'advancedLink')]"
                + "//button[contains(@class,'advanced-button')]";
        List<HtmlButton> advancedButtons = form.getByXPath(advancedButtonXPath);
        assertEquals(1, advancedButtons.size());
        advancedButtons.get(0).click();
        WebClientUtil.waitForJSExec(webClient);

        // Add two machine capacity entries
        String addButtonXPath = "//button[contains(@class,'repeatable-add') and normalize-space(.)='Add Machine']";
        for (int i = 0; i < 2; i++) {
            List<HtmlButton> addButtons = form.getByXPath(addButtonXPath);
            assertEquals(1, addButtons.size());
            addButtons.get(0).click();
            WebClientUtil.waitForJSExec(webClient);
        }

        // Fill the repeated fields; Jenkins names each input relative to its repeatable row.
        List<HtmlInput> machineNameInputs = form.getInputsByName("_.machineName");
        List<HtmlInput> maxWeightInputs = form.getInputsByName("_.maxWeight");
        assertEquals(2, machineNameInputs.size());
        assertEquals(2, maxWeightInputs.size());
        machineNameInputs.get(0).setValue("machine-01");
        maxWeightInputs.get(0).setValue("16");
        machineNameInputs.get(1).setValue("machine-B");
        maxWeightInputs.get(1).setValue("32");

        // Submit the form
        j.submit(form);

        // Verify the configuration was persisted
        descriptor.load();
        SharedHardwareThrottleManager restored = descriptor.getSharedHardwareThrottle();
        assertNotNull(restored);
        assertEquals("LABEL_KEY_FROM_UI", restored.getLabelKey());
        assertEquals("MAX_LABEL_KEY_FROM_UI", restored.getMaxLabelKey());
        assertEquals(42, restored.getFallbackCapacityFactor());
        assertEquals(2, restored.getMachineCapacities().size());

        SharedHardwareThrottleManager.MachineCapacityPair capacity =
                restored.getMachineCapacities().get(0);
        assertEquals("machine-01", capacity.getMachineName());
        assertEquals(16, capacity.getMaxWeight());

        capacity = restored.getMachineCapacities().get(1);
        assertEquals("machine-B", capacity.getMachineName());
        assertEquals(32, capacity.getMaxWeight());
    }

    @Test
    void sharedHardwareConfigurationIsShownInUIAfterSave(JenkinsRule j) throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        descriptor.setSharedHardwareThrottle(new SharedHardwareThrottleManager(
                "SAVED_LABEL",
                "SAVED_MAX_LABEL",
                Arrays.asList(new SharedHardwareThrottleManager.MachineCapacityPair("saved-machine", 12)),
                5));
        descriptor.save();

        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo("configure");
        WebClientUtil.waitForJSExec(webClient);
        HtmlForm form = page.getFormByName("config");

        assertEquals("SAVED_LABEL", form.getInputByName("_.labelKey").getValue());
        assertEquals("SAVED_MAX_LABEL", form.getInputByName("_.maxLabelKey").getValue());
        assertEquals("5", form.getInputByName("_.fallbackCapacityFactor").getValue());
        assertEquals(1, form.getInputsByName("_.machineName").size());
        assertEquals(
                "saved-machine", form.getInputsByName("_.machineName").get(0).getValue());
        assertEquals("12", form.getInputsByName("_.maxWeight").get(0).getValue());

        // Saving the unchanged form must keep the values
        j.submit(form);
        SharedHardwareThrottleManager after = descriptor.getSharedHardwareThrottle();
        assertEquals("SAVED_LABEL", after.getLabelKey());
        assertEquals("SAVED_MAX_LABEL", after.getMaxLabelKey());
        assertEquals(5, after.getFallbackCapacityFactor());
        assertEquals(1, after.getMachineCapacities().size());
        assertEquals("saved-machine", after.getMachineCapacities().get(0).getMachineName());
    }

    @Test
    @WithoutJenkins
    void sharedHardwareConfigurationValidation() {
        List<SharedHardwareThrottleManager.MachineCapacityPair> unique = Arrays.asList(
                new SharedHardwareThrottleManager.MachineCapacityPair("A", 1),
                new SharedHardwareThrottleManager.MachineCapacityPair("B", 2));
        List<SharedHardwareThrottleManager.MachineCapacityPair> duplicate = Arrays.asList(
                new SharedHardwareThrottleManager.MachineCapacityPair("A", 1),
                new SharedHardwareThrottleManager.MachineCapacityPair("B", 2),
                new SharedHardwareThrottleManager.MachineCapacityPair(" A ", 3));

        assertEquals(null, SharedHardwareThrottleManager.validateConfiguration("X", "Y", unique));
        assertEquals(null, SharedHardwareThrottleManager.validateConfiguration("", "", null));
        assertNotNull(SharedHardwareThrottleManager.validateConfiguration("X", "X", unique));
        // Empty values mean the (different) defaults, but an explicit default clashes with an empty counterpart
        assertNotNull(SharedHardwareThrottleManager.validateConfiguration(
                SharedHardwareThrottleManager.DEFAULT_MAX_LABEL_KEY, "", unique));
        assertNotNull(SharedHardwareThrottleManager.validateConfiguration("X", "Y", duplicate));
    }

    @Test
    void sharedHardwareLabelKeyFormValidation(JenkinsRule j) {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckLabelKey("A", "B").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckLabelKey("A", "A").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckMaxLabelKey("B", "A").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckMaxLabelKey("A", "A").kind);
    }

    @Test
    void sharedHardwareEqualLabelKeysAreRejectedOnSave(JenkinsRule j) throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        descriptor.setSharedHardwareThrottle(
                new SharedHardwareThrottleManager("OLD_LABEL", "OLD_MAX_LABEL", new ArrayList<>(), 3));
        descriptor.save();

        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo("configure");
        WebClientUtil.waitForJSExec(webClient);
        HtmlForm form = page.getFormByName("config");
        form.getInputByName("_.labelKey").setValue("SAME_LABEL");
        form.getInputByName("_.maxLabelKey").setValue("SAME_LABEL");
        // Jenkins re-displays the form on a rejected save, the stored configuration must stay as it was
        j.submit(form);

        assertEquals("OLD_LABEL", descriptor.getSharedHardwareThrottle().getLabelKey());
        assertEquals("OLD_MAX_LABEL", descriptor.getSharedHardwareThrottle().getMaxLabelKey());
    }

    @Test
    void sharedHardwareDuplicateMachineNamesAreRejectedOnSave(JenkinsRule j) throws Exception {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        descriptor.setSharedHardwareThrottle(
                new SharedHardwareThrottleManager("OLD_LABEL", "OLD_MAX_LABEL", new ArrayList<>(), 3));
        descriptor.save();

        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.goTo("configure");
        WebClientUtil.waitForJSExec(webClient);
        HtmlForm form = page.getFormByName("config");

        String advancedButtonXPath = "//div[contains(@class,'advancedBody')]"
                + "[.//button[contains(@class,'repeatable-add') and normalize-space(.)='Add Machine']]"
                + "/preceding-sibling::div[contains(@class,'advancedLink')]"
                + "//button[contains(@class,'advanced-button')]";
        List<HtmlButton> advancedButtons = form.getByXPath(advancedButtonXPath);
        assertEquals(1, advancedButtons.size());
        advancedButtons.get(0).click();
        WebClientUtil.waitForJSExec(webClient);

        String addButtonXPath = "//button[contains(@class,'repeatable-add') and normalize-space(.)='Add Machine']";
        for (int i = 0; i < 2; i++) {
            List<HtmlButton> addButtons = form.getByXPath(addButtonXPath);
            addButtons.get(0).click();
            WebClientUtil.waitForJSExec(webClient);
        }
        List<HtmlInput> machineNameInputs = form.getInputsByName("_.machineName");
        List<HtmlInput> maxWeightInputs = form.getInputsByName("_.maxWeight");
        assertEquals(2, machineNameInputs.size());
        machineNameInputs.get(0).setValue("dup");
        maxWeightInputs.get(0).setValue("4");
        machineNameInputs.get(1).setValue("dup");
        maxWeightInputs.get(1).setValue("8");

        j.submit(form);
        assertEquals("OLD_LABEL", descriptor.getSharedHardwareThrottle().getLabelKey());
        assertTrue(descriptor.getSharedHardwareThrottle().getMachineCapacities().isEmpty());
    }

    @Test
    void descriptorLogsSharedHardwareConfiguration(JenkinsRule j) {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(new java.text.MessageFormat(record.getMessage()).format(record.getParameters()));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger(ThrottleJobProperty.DescriptorImpl.class.getName());
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        handler.setLevel(Level.FINE);
        logger.addHandler(handler);
        try {
            descriptor.setSharedHardwareThrottle(new SharedHardwareThrottleManager(
                    "LOGGED_LABEL",
                    "LOGGED_MAX_LABEL",
                    Arrays.asList(new SharedHardwareThrottleManager.MachineCapacityPair("logged-machine", 9)),
                    2));
            descriptor.save();
            descriptor.load();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
        }

        assertEquals(2, messages.size(), messages::toString);
        for (String message : messages) {
            assertTrue(message.contains("LOGGED_LABEL"), message);
            assertTrue(message.contains("LOGGED_MAX_LABEL"), message);
            assertTrue(message.contains("logged-machine=9"), message);
        }
        assertTrue(messages.get(0).startsWith("save: "), messages.get(0));
        assertTrue(messages.get(1).startsWith("load: "), messages.get(1));
    }

    private static void assertProjects(JenkinsRule j, String category, AbstractProject<?, ?>... projects) {
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

        @NonNull
        @Override
        public ACL getRootACL() {
            return new AuthorizationStrategy.Unsecured().getRootACL();
        }

        @NonNull
        @Override
        public Collection<String> getGroups() {
            return Collections.emptySet();
        }

        @NonNull
        @Override
        public ACL getACL(@NonNull Job<?, ?> project) {
            fail("not even supposed to be looking at " + project);
            return super.getACL(project);
        }
    }

    private static String anyString() {
        return "concurrency_" + anyInt();
    }

    private static boolean anyBoolean() {
        return random.nextBoolean();
    }

    private static int anyInt() {
        return random.nextInt(10000);
    }
}
