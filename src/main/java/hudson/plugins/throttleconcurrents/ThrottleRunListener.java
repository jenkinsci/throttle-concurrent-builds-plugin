/*
 * The MIT License
 *
 * Copyright 2015 CloudBees Inc.
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

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

/**
 * Performs monitoring of {@link Run}s to fill {@link ThrottleCategoriesCountersCache}.
 * @author Oleg Nenashev
 * @since TODO
 */
@Extension
public class ThrottleRunListener extends RunListener<Run> {

    @Override
    public void onStarted(Run r, TaskListener listener) {
        if (r instanceof AbstractBuild) {
            ThrottleCategoriesCountersCache.getInstance().fireStarted((AbstractBuild)r);
        }
    }  
    
    @Override
    public void onCompleted(Run r, TaskListener listener) {
        if (r instanceof AbstractBuild) {
            ThrottleCategoriesCountersCache.getInstance().fireCompleted((AbstractBuild)r);
        }
    }      
}
