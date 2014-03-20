/**
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

import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlOption;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlRadioButtonInput;
import com.gargoylesoftware.htmlunit.html.HtmlSelect;

import hudson.model.FreeStyleProject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.jvnet.hudson.test.HudsonTestCase;

/**
 * This class initiates the testing of {@link hudson.plugins.throttleconcurrents.ThrottleQueueTaskDispatcher}.<br>
 * -Test methods for {@link hudson.plugins.throttleconcurrents.ThrottleQueueTaskDispatcher#canTake(hudson.model.Node, hudson.model.Queue.Task)}.<br>
 * -Happens to test {@link hudson.plugins.throttleconcurrents.ThrottleQueueTaskDispatcher#getMaxConcurrentPerNodeBasedOnMatchingLabels(hudson.model.Node, hudson.plugins.throttleconcurrents.ThrottleJobProperty.ThrottleCategory, int)}.
 * @author marco.miller@ericsson.com
 */
public class ThrottleQueueTaskDispatcherTest extends HudsonTestCase
{
    private static final String buttonsXPath = "//button[@tabindex='0']";
    private static final String configFormName = "config";
    private static final String configUrlSuffix = "configure";
    private static final String logUrlPrefix = "log/";
    private static final String match = "match";
    private static final String matchTrace = "node labels match";
    private static final String max = "max";
    private static final String maxTrace = "=> maxConcurrentPerNode' = ";
    private static final String mismatch = "mismatch";
    private static final String mismatchTrace = "node labels mismatch";
    private static final String parentXPath = "//td[contains(text(),'Throttl')]/..";
    private static final String saveButtonText = "Save";
    private static final String testCategoryName = "cat1";
    private static final String testCategoryLabel = testCategoryName+"label";
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
    private static final int greaterCategoryWideMaxConcurrentPerNode = configureOneMaxLabelPair+1;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }
    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    public void testShouldConsiderTaskAsBlockableStillUponMatchingMaxLabelPair()
    throws ExecutionException, InterruptedException, IOException
    {
        assertBasedOnMaxLabelPairMatchingOrNot(
            configureOneMaxLabelPair,
            noCategoryWideMaxConcurrentPerNode,
            expectMatch,
            configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    public void testShouldConsiderTaskAsBlockableStillUponMatchingMaxLabelPairs()
    throws ExecutionException, InterruptedException, IOException
    {
        assertBasedOnMaxLabelPairMatchingOrNot(
            configureTwoMaxLabelPairs,
            noCategoryWideMaxConcurrentPerNode,
            expectMatch,
            configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    public void testShouldConsiderTaskAsBlockableStillUponMatchingLabelPairWithLowestMax()
    throws ExecutionException, InterruptedException, IOException
    {
        assertBasedOnMaxLabelPairMatchingOrNot(
            configureOneMaxLabelPair, //=> label-pair max of 1, still to match as *the* max;
            greaterCategoryWideMaxConcurrentPerNode, //greater than label-pair max but still
            expectMatch,
            configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    public void testShouldConsiderTaskAsBuildableStillUponMismatchingMaxLabelPairs()
    throws ExecutionException, InterruptedException, IOException
    {
        assertBasedOnMaxLabelPairMatchingOrNot(
            configureTwoMaxLabelPairs,
            someCategoryWideMaxConcurrentPerNode,
            expectMismatch,
            configureNodeLabel);
    }

    /**
     * @throws ExecutionException upon Jenkins project build scheduling issue.
     * @throws InterruptedException upon Jenkins global configuration issue.
     * @throws IOException upon many potential Jenkins IO issues during test.
     */
    public void testShouldConsiderTaskAsBuildableStillUponNoNodeLabel()
    throws ExecutionException, InterruptedException, IOException
    {
        assertBasedOnMaxLabelPairMatchingOrNot(
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
    private void assertBasedOnMaxLabelPairMatchingOrNot(
        int targetedPairNumber, int maxConcurrentPerNode, boolean expectMatch, boolean configureNodeLabel)
    throws ExecutionException, InterruptedException, IOException
    {
        if(configureNodeLabel)
        {
            String nodeLabelSuffix = expectMatch ? "" : "other";
            configureNewNodeWithLabel(testCategoryLabel +targetedPairNumber +nodeLabelSuffix);
        }
        configureGlobalThrottling(testCategoryLabel, targetedPairNumber, maxConcurrentPerNode);

        FreeStyleProject project = createFreeStyleProject();
        configureJobThrottling(project);
        String logger = configureLogger();
        project.scheduleBuild2(0).get();
        HtmlPage page = getLoggerPage(logger);
        if(expectMatch)
        {
            assertTrue(expectedTracesMessage(match, true), page.asText().contains(matchTrace));
            assertTrue(expectedTracesMessage(max, true), page.asText().contains(maxTrace+targetedPairNumber));
        }
        else {
            assertTrue(expectedTracesMessage(mismatch, true), page.asText().contains(mismatchTrace));
            assertFalse(expectedTracesMessage(max, false), page.asText().contains(maxTrace));
        }
    }

    private void configureGlobalThrottling(String labelRoot, int numberOfPairs, int maxConcurrentPerNode)
    throws InterruptedException, IOException, MalformedURLException
    {
        URL url = new URL(getURL()+configUrlSuffix);
        HtmlPage page = createWebClient().getPage(url);
        HtmlForm form = page.getFormByName(configFormName);
        List<HtmlButton> buttons = form.getByXPath(parentXPath+buttonsXPath);
        String buttonText = "Add Category";
        boolean buttonFound = false;

        for(HtmlButton button: buttons) {
            if(button.getTextContent().equals(buttonText))
            {
                buttonFound = true;
                button.click();

                HtmlInput input = form.getInputByName("_.categoryName");
                input.setValueAttribute(testCategoryName);
                //_.maxConcurrentTotal ignored.
                input = form.getInputByName("_.maxConcurrentPerNode");
                input.setValueAttribute(""+maxConcurrentPerNode);

                buttons = form.getByXPath(parentXPath+buttonsXPath);
                buttonText = "Add Maximum Per Labeled Node";
                buttonFound = false;
                for(HtmlButton deeperButton: buttons) {
                    if(deeperButton.getTextContent().equals(buttonText))
                    {
                        buttonFound = true;
                        for(int i=0; i<numberOfPairs; i++)
                        {
                            List<HtmlInput> inputs = null;
                            int clickThenWaitForMaxTries = 3;
                            do {
                                page = (HtmlPage)deeperButton.click();
                                TimeUnit.SECONDS.sleep(1);
                                form = page.getFormByName(configFormName);
                                inputs = form.getInputsByName("_.throttledNodeLabel");
                                clickThenWaitForMaxTries--;
                            } while(inputs.isEmpty() && clickThenWaitForMaxTries > 0);

                            assertFalse(buttonText+" button clicked; no resulting field found on "+url, inputs.isEmpty());
                            inputs.get(i).setValueAttribute(labelRoot+(i+1));

                            inputs = form.getInputsByName("_.maxConcurrentPerNodeLabeled");
                            inputs.get(i).setValueAttribute(""+(i+1));
                        }
                    }
                }
                failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
                break;
            }
        }
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);

        buttons = form.getByXPath(buttonsXPath);
        buttonText = saveButtonText;
        buttonFound = buttonFoundThusFormSubmitted(form, buttons, buttonText);
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
    }

    private void configureJobThrottling(FreeStyleProject project)
    throws IOException, MalformedURLException
    {
        URL url = new URL(getURL()+project.getUrl()+configUrlSuffix);
        HtmlPage page = createWebClient().getPage(url);
        HtmlForm form = page.getFormByName(configFormName);
        List<HtmlButton> buttons = form.getByXPath(buttonsXPath);
        String buttonText = saveButtonText;
        boolean buttonFound = false;

        for(HtmlButton button: buttons) {
            if(button.getTextContent().equals(buttonText))
            {
                buttonFound = true;
                String checkboxName = "throttleEnabled";
                HtmlElement checkbox = page.getElementByName(checkboxName);
                assertNotNull(checkboxName+" checkbox not found on test job config page; plugin installed?", checkbox);
                checkbox.click();

                List<HtmlRadioButtonInput> radios = form.getRadioButtonsByName("throttleOption");
                for(HtmlRadioButtonInput radio: radios) {
                    radio.setChecked(radio.getValueAttribute().equals("category"));
                }
                checkbox = page.getElementByName("categories");
                checkbox.click();

                form.submit(button);
                break;
            }
        }
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
    }

    private void configureNewNodeWithLabel(String label)
    throws IOException, MalformedURLException
    {
        URL url = new URL(getURL()+"computer/new");
        HtmlPage page = createWebClient().getPage(url);
        HtmlForm form = page.getFormByName("createItem");

        HtmlInput input = form.getInputByName("name");
        input.setValueAttribute("test");

        List<HtmlRadioButtonInput> radios = form.getRadioButtonsByName("mode");
        for(HtmlRadioButtonInput radio: radios) {
            radio.setChecked(radio.getValueAttribute().equals("hudson.slaves.DumbSlave"));
        }
        List<HtmlButton> buttons = form.getByXPath(buttonsXPath);
        String buttonText = "OK";
        boolean buttonFound = false;

        for(HtmlButton button: buttons) {
            if(button.getTextContent().equals(buttonText))
            {
                buttonFound = true;
                page = (HtmlPage)form.submit(button);
                List<HtmlForm> forms = page.getForms();

                for(HtmlForm aForm: forms) {
                    if(aForm.getActionAttribute().equals("doCreateItem"))
                    {
                        form = aForm;
                        break;
                    }
                }
                input = form.getInputByName("_.numExecutors");
                input.setValueAttribute("1");

                input = form.getInputByName("_.remoteFS");
                input.setValueAttribute("/");

                input = form.getInputByName("_.labelString");
                input.setValueAttribute(label);
                break;
            }
        }
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);

        buttons = form.getByXPath(buttonsXPath);
        buttonText = saveButtonText;
        buttonFound = buttonFoundThusFormSubmitted(form, buttons, buttonText);
        failWithMessageIfButtonNotFoundOnPage(buttonFound, buttonText, url);
    }

    private String configureLogger()
    throws IOException, MalformedURLException
    {
        String logger = ThrottleQueueTaskDispatcher.class.getName();
        jenkins.getLog().doNewLogRecorder(logger);
        URL url = new URL(getURL()+logUrlPrefix+logger+"/"+configUrlSuffix);
        HtmlPage page = createWebClient().getPage(url);
        HtmlForm form = page.getFormByName(configFormName);
        List<HtmlButton> buttons = form.getByXPath(buttonsXPath);
        String buttonText = "Add";
        boolean buttonFound = false;

        for(HtmlButton button: buttons) {
            if(button.getTextContent().equals(buttonText))
            {
                buttonFound = true;
                button.click();

                List<HtmlInput> inputs = form.getInputsByName("_.name");
                for(HtmlInput input: inputs) {
                    input.setValueAttribute(logger);
                }
                HtmlSelect select = form.getSelectByName("level");
                HtmlOption option = select.getOptionByValue("FINE");
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

    private boolean buttonFoundThusFormSubmitted(HtmlForm form, List<HtmlButton> buttons, String buttonText)
    throws IOException
    {
        boolean buttonFound = false;
        for(HtmlButton button: buttons) {
            if(button.getTextContent().equals(buttonText))
            {
                buttonFound = true;
                form.submit(button);
                break;
            }
        }
        return buttonFound;
    }

    private String expectedTracesMessage(String traceKind, boolean assertingTrue)
    {
        StringBuffer messagePrefix = new StringBuffer("log shall");
        if(!assertingTrue) {
            messagePrefix.append(" not");
        }
        return messagePrefix+" contain '"+traceKind+"' traces";
    }

    private void failWithMessageIfButtonNotFoundOnPage(boolean buttonFound, String buttonText, URL url)
    {
        assertTrue(buttonText+" button not found on "+url, buttonFound);
    }

    private HtmlPage getLoggerPage(String logger)
    throws IOException, MalformedURLException
    {
        URL url = new URL(getURL()+logUrlPrefix+logger);
        return createWebClient().getPage(url);
    }
}
