/*
 * MIT License
 * Copyright (c) 2013, Ericsson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package hudson.plugins.throttleconcurrents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import hudson.model.FreeStyleProject;
import hudson.plugins.throttleconcurrents.testutils.HtmlUnitHelper;
import hudson.util.VersionNumber;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.htmlunit.ElementNotFoundException;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlOption;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.htmlunit.html.HtmlSelect;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * This class initiates the testing of {@link hudson.plugins.throttleconcurrents.ThrottleQueueTaskDispatcher}.<br>
 * -Test methods for {@link hudson.plugins.throttleconcurrents.ThrottleQueueTaskDispatcher#canTake(hudson.model.Node, hudson.model.Queue.Task)}.<br>
 * -Happens to test {@link hudson.plugins.throttleconcurrents.ThrottleQueueTaskDispatcher#getMaxConcurrentPerNodeBasedOnMatchingLabels(hudson.model.Node, hudson.plugins.throttleconcurrents.ThrottleJobProperty.ThrottleCategory, int)}.
 * @author marco.miller@ericsson.com
 */
@WithJenkins
class ThrottleQueueTaskDispatcherTest {
    private static final String buttonsXPath = "//button";
    private static final String configFormName = "config";
    private static final String configUrlSuffix = "configure";
    private static final String logUrlPrefix = "log/";
    private static final String match = "match";
    private static final String matchTrace = "node labels match";
    private static final String max = "max";
    private static final String maxTrace = "=> maxConcurrentPerNode' = ";
    private static final String mismatch = "mismatch";
    private static final String mismatchTrace = "node labels mismatch";
    private static final String parentXPath = "//div[contains(text(),'Throttl')]/..";
    private static final String saveButtonText = "Save";
    private static final String testCategoryName = "cat1";
    private static final String testCategoryLabel = testCategoryName + "label";
    //
    private static final boolean configureNodeLabel = true;
    private static final boolean configureNoNodeLabel = false;
    private static final boolean expectMatch = true;
    private static final boolean expectMismatch = false;
    //
    private static final int configureOneMaxLabelPair = 1;
    private static final int configureTwoMaxLabelPairs = 2;
    private static final int noCategoryWideMaxConcurrentPerNode = 0;
    private static final int someCategoryWideMaxConcurrentPerNode = 1;
    private static final int greaterCategoryWideMaxConcurrentPerNode = configureOneMaxLabelPair + 1;

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    @Test
    void testShouldConsiderTaskAsBlockableStillUponMatchingMaxLabelPair(JenkinsRule r)
            throws ExecutionException, InterruptedException, IOException {
        assertBasedOnMaxLabelPairMatchingOrNot(
                r, configureOneMaxLabelPair, noCategoryWideMaxConcurrentPerNode, expectMatch, configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    @Test
    void testShouldConsiderTaskAsBlockableStillUponMatchingMaxLabelPairs(JenkinsRule r)
            throws ExecutionException, InterruptedException, IOException {
        assertBasedOnMaxLabelPairMatchingOrNot(
                r, configureTwoMaxLabelPairs, noCategoryWideMaxConcurrentPerNode, expectMatch, configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    @Test
    void testShouldConsiderTaskAsBlockableStillUponMatchingLabelPairWithLowestMax(JenkinsRule r)
            throws ExecutionException, InterruptedException, IOException {
        assertBasedOnMaxLabelPairMatchingOrNot(
                r,
                configureOneMaxLabelPair, // => label-pair max of 1, still to match as *the* max;
                greaterCategoryWideMaxConcurrentPerNode, // greater than label-pair max but still
                expectMatch,
                configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    @Test
    void testShouldConsiderTaskAsBuildableStillUponMismatchingMaxLabelPairs(JenkinsRule r)
            throws ExecutionException, InterruptedException, IOException {
        assertBasedOnMaxLabelPairMatchingOrNot(
                r, configureTwoMaxLabelPairs, someCategoryWideMaxConcurrentPerNode, expectMismatch, configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    @Test
    void testShouldConsiderTaskAsBuildableStillUponNoNodeLabel(JenkinsRule r)
            throws ExecutionException, InterruptedException, IOException {
        assertBasedOnMaxLabelPairMatchingOrNot(
                r,
                configureOneMaxLabelPair,
                someCategoryWideMaxConcurrentPerNode,
                expectMismatch,
                configureNoNodeLabel);
    }

    /**
     * @param targetedPairNumber of throttling category maximum/label pairs.
     * @param maxConcurrentPerNode or category-wide maximum.
     * @param expectMatch of labels (or not).
     * @param configureNodeLabel or not.
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    private static void assertBasedOnMaxLabelPairMatchingOrNot(
            JenkinsRule r,
            int targetedPairNumber,
            int maxConcurrentPerNode,
            boolean expectMatch,
            boolean configureNodeLabel)
            throws ExecutionException, InterruptedException, IOException {
        if (configureNodeLabel) {
            String nodeLabelSuffix = expectMatch ? "" : "other";
            configureNewNodeWithLabel(r, testCategoryLabel + targetedPairNumber + nodeLabelSuffix);
        }
        configureGlobalThrottling(r, testCategoryLabel, targetedPairNumber, maxConcurrentPerNode);

        FreeStyleProject project = r.createFreeStyleProject();
        configureJobThrottling(r, project);
        String logger = configureLogger(r);
        project.scheduleBuild2(0).get();
        HtmlPage page = getLoggerPage(r, logger);
        if (expectMatch) {
            assertTrue(page.asNormalizedText().contains(matchTrace), expectedTracesMessage(match, true));
            assertTrue(
                    page.asNormalizedText().contains(maxTrace + targetedPairNumber), expectedTracesMessage(max, true));
        } else {
            assertTrue(page.asNormalizedText().contains(mismatchTrace), expectedTracesMessage(mismatch, true));
            assertFalse(page.asNormalizedText().contains(maxTrace), expectedTracesMessage(max, false));
        }
    }

    private static void configureGlobalThrottling(
            JenkinsRule r, String labelRoot, int numberOfPairs, int maxConcurrentPerNode)
            throws InterruptedException, IOException {
        URL url = new URL(r.getURL() + configUrlSuffix);
        HtmlPage page = r.createWebClient().getPage(url);
        HtmlForm form = page.getFormByName(configFormName);
        List<HtmlButton> buttons = HtmlUnitHelper.getButtonsByXPath(form, parentXPath + buttonsXPath);
        String buttonText = "Add Category";
        boolean buttonFound = false;

        for (HtmlButton button : buttons) {
            if (button.asNormalizedText().equals(buttonText)) {
                button.click();

                HtmlInput input = form.getInputByName("_.categoryName");
                input.setValue(testCategoryName);
                // _.maxConcurrentTotal ignored.
                input = form.getInputByName("_.maxConcurrentPerNode");
                input.setValue("" + maxConcurrentPerNode);

                buttons = HtmlUnitHelper.getButtonsByXPath(form, parentXPath + buttonsXPath);
                buttonText = "Add Maximum Per Labeled Node";
                for (HtmlButton deeperButton : buttons) {
                    if (deeperButton.asNormalizedText().equals(buttonText)) {
                        buttonFound = true;
                        for (int i = 0; i < numberOfPairs; i++) {
                            List<HtmlInput> inputs;
                            int clickThenWaitForMaxTries = 3;
                            do {
                                page = deeperButton.click();
                                TimeUnit.SECONDS.sleep(1);
                                form = page.getFormByName(configFormName);
                                inputs = form.getInputsByName("_.throttledNodeLabel");
                                clickThenWaitForMaxTries--;
                            } while (inputs.isEmpty() && clickThenWaitForMaxTries > 0);

                            assertFalse(
                                    inputs.isEmpty(),
                                    buttonText + " button clicked; no resulting field found on " + url);
                            inputs.get(i).setValue(labelRoot + (i + 1));

                            inputs = form.getInputsByName("_.maxConcurrentPerNodeLabeled");
                            inputs.get(i).setValue("" + (i + 1));
                        }
                    }
                }
                failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
                break;
            }
        }
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);

        buttons = HtmlUnitHelper.getButtonsByXPath(form, buttonsXPath);
        buttonText = saveButtonText;
        buttonFound = buttonFoundThusFormSubmitted(form, buttons, buttonText);
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
    }

    private static void configureJobThrottling(JenkinsRule r, FreeStyleProject project) throws IOException {
        URL url = new URL(r.getURL() + project.getUrl() + configUrlSuffix);
        HtmlPage page = r.createWebClient().getPage(url);
        HtmlForm form = page.getFormByName(configFormName);
        List<HtmlButton> buttons = HtmlUnitHelper.getButtonsByXPath(form, buttonsXPath);
        String buttonText = saveButtonText;
        boolean buttonFound = false;

        for (HtmlButton button : buttons) {
            if (button.getTextContent().trim().equals(buttonText)) {
                buttonFound = true;
                String checkboxName = "throttleEnabled";
                HtmlElement checkbox = page.getElementByName(checkboxName);
                assertNotNull(
                        checkbox, checkboxName + " checkbox not found on test job config page; plugin installed?");
                checkbox.click();

                List<HtmlRadioButtonInput> radios = form.getRadioButtonsByName("throttleOption");
                for (HtmlRadioButtonInput radio : radios) {
                    radio.setChecked(radio.getValue().equals("category"));
                }
                checkbox = page.getElementByName("categories");
                checkbox.click();

                button.click();
                break;
            }
        }
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
    }

    private static void configureNewNodeWithLabel(JenkinsRule r, String label) throws IOException {
        URL url = new URL(r.getURL() + "computer/new");
        HtmlPage page = r.createWebClient().getPage(url);
        HtmlForm form = page.getFormByName("createItem");

        HtmlInput input = form.getInputByName("name");
        input.setValue("test");

        List<HtmlRadioButtonInput> radios = form.getRadioButtonsByName("mode");
        for (HtmlRadioButtonInput radio : radios) {
            radio.setChecked(radio.getValue().equals("hudson.slaves.DumbSlave"));
        }
        page = submitForm(form);
        boolean buttonFound;

        List<HtmlForm> forms = page.getForms();

        for (HtmlForm aForm : forms) {
            if (aForm.getActionAttribute().equals("doCreateItem")) {
                form = aForm;
                break;
            }
        }
        input = form.getInputByName("_.numExecutors");
        input.setValue("1");

        input = form.getInputByName("_.remoteFS");
        input.setValue("/");

        input = form.getInputByName("_.labelString");
        input.setValue(label);

        List<HtmlButton> buttons = HtmlUnitHelper.getButtonsByXPath(form, buttonsXPath);
        buttonFound = buttonFoundThusFormSubmitted(form, buttons, saveButtonText);
        failWithMessageIfButtonNotFoundOnPage(buttonFound, saveButtonText, url);
    }

    private static HtmlPage submitForm(HtmlForm form) throws IOException {
        HtmlPage page;
        if (Jenkins.getVersion().isOlderThan(new VersionNumber("2.320"))) {
            List<HtmlButton> buttons = HtmlUnitHelper.getButtonsByXPath(form, buttonsXPath);
            if (buttons.isEmpty()) {
                fail("Failed to find button by xpath: " + buttonsXPath);
            }
            page = buttons.stream()
                    .filter(button -> button.getTextContent().equals("OK"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            String.format("Failed to find button by xpath: %s and text 'OK'", buttonsXPath)))
                    .click();

        } else if (Jenkins.getVersion().isOlderThan(new VersionNumber("2.376"))) {
            List<HtmlElement> elementsByAttribute = form.getElementsByAttribute("input", "type", "submit");
            if (elementsByAttribute.isEmpty()) {
                fail("Failed to find an input with type submit on the page");
            }
            page = elementsByAttribute.get(0).click();
        } else {
            HtmlButton button = form.getButtonByName("Submit");
            page = button.click();
        }
        return page;
    }

    private static String configureLogger(JenkinsRule r) throws IOException {
        String logger = ThrottleQueueTaskDispatcher.class.getName();
        r.jenkins.getLog().doNewLogRecorder(logger);
        URL url = new URL(r.getURL() + logUrlPrefix + logger + "/" + configUrlSuffix);
        HtmlPage page = r.createWebClient().getPage(url);
        HtmlForm form = page.getFormByName(configFormName);
        List<HtmlButton> buttons = HtmlUnitHelper.getButtonsByXPath(form, buttonsXPath);
        String buttonText = "Add";
        boolean buttonFound = false;

        for (HtmlButton button : buttons) {
            if (button.asNormalizedText().equals(buttonText)) {
                buttonFound = true;
                button.click();

                List<HtmlInput> inputs = form.getInputsByName("_.name");
                for (HtmlInput input : inputs) {
                    input.setValue(logger);
                }
                HtmlSelect select = form.getSelectByName("level");
                HtmlOption option;
                try {
                    option = select.getOptionByValue("fine");
                } catch (ElementNotFoundException e) {
                    // gets upper case since Jenkins 1.519
                    option = select.getOptionByValue("FINE");
                }
                select.setSelectedAttribute(option, true);
                break;
            }
        }
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);

        buttonText = saveButtonText;
        buttonFound = buttonFoundThusFormSubmitted(form, buttons, buttonText);
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
        return logger;
    }

    private static boolean buttonFoundThusFormSubmitted(HtmlForm form, List<HtmlButton> buttons, String buttonText)
            throws IOException {
        boolean buttonFound = false;
        for (HtmlButton button : buttons) {
            if (button.getTextContent().trim().equals(buttonText)) {
                buttonFound = true;
                button.click();
                break;
            }
        }
        return buttonFound;
    }

    private static String expectedTracesMessage(String traceKind, boolean assertingTrue) {
        StringBuilder messagePrefix = new StringBuilder("log shall");
        if (!assertingTrue) {
            messagePrefix.append(" not");
        }
        return messagePrefix + " contain '" + traceKind + "' traces";
    }

    private static void failWithMessageIfButtonNotFoundOnPage(boolean buttonFound, String buttonText, URL url) {
        assertTrue(buttonFound, buttonText + " button not found on " + url);
    }

    private static HtmlPage getLoggerPage(JenkinsRule r, String logger) throws IOException {
        URL url = new URL(r.getURL() + logUrlPrefix + logger);
        return r.createWebClient().getPage(url);
    }
}
