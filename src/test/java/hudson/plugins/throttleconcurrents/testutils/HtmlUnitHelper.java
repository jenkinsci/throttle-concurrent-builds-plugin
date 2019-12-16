/*
 * The MIT License
 * 
 * Copyright (c) 2016 CloudBees, Inc.
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
package hudson.plugins.throttleconcurrents.testutils;

import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Stores helpers for HtmlUnit.
 * @author Oleg Nenashev
 * @since 1.9.0
 */
@Restricted(NoExternalUse.class)
public class HtmlUnitHelper {

    private HtmlUnitHelper() {
        // Instantination is prohibited
    }
    
    /**
     * Gets {@link HtmlButton}s by xpath.
     * This method provides the original behavior of {@link HtmlForm#getByXPath(java.lang.String)} before 2.0.
     * @param form HTML form
     * @param xpath Xpath for buttons search
     * @return List of discovered buttons
     */
    @Nonnull
    public static List<HtmlButton> getButtonsByXPath(@Nonnull HtmlForm form, @Nonnull String xpath) {
        List<?> buttons = form.getByXPath(xpath);
        List<HtmlButton> res = new ArrayList<>(buttons.size());
        for(Object buttonCandidate: buttons) {
            if (buttonCandidate instanceof HtmlButton) {
                res.add((HtmlButton)buttonCandidate);
            }
        }
        return res;
    }
}
