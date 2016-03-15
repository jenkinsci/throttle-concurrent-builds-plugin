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
package hudson.plugins.throttleconcurrents.util;

import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.model.AbstractProject;
import hudson.model.Queue;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.plugins.throttleconcurrents.ThrottleMatrixProjectOptions;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * A set of helper methods.
 * @author Oleg Nenashev
 * @since TODO
 */
public class ThrottleHelper {
    
    @CheckForNull
    public static ThrottleJobProperty getThrottleJobProperty(Queue.Task task) {
        if (task instanceof AbstractProject) {
            return getThrottleJobProperty((AbstractProject<?,?>) task);          
        }
        return null;
    }
    
    @CheckForNull
    public static ThrottleJobProperty getThrottleJobProperty(AbstractProject<?,?> prj) {
        if (prj instanceof MatrixConfiguration) {
            prj = (AbstractProject<?,?>)((MatrixConfiguration)prj).getParent();
        }
        ThrottleJobProperty tjp = prj.getProperty(ThrottleJobProperty.class);
        return tjp;
    }
    
    /**
     * Checks if the task should be throttled
     * @param <TTask> Type of task to be checked. Designed for {@link Queue.Task} and {@link AbstractProject}.
     * @param task Task to be checked
     * @param tjp Property of the task being evaluated
     * @return
     */
    public static <TTask> boolean shouldBeThrottled(@Nonnull TTask task, @CheckForNull ThrottleJobProperty tjp) {
       if (tjp == null) return false;
       if (!tjp.getThrottleEnabled()) return false;
       
       // Handle matrix options
       ThrottleMatrixProjectOptions matrixOptions = tjp.getMatrixOptions();
       if (matrixOptions == null) matrixOptions = ThrottleMatrixProjectOptions.DEFAULT;
       if (!matrixOptions.isThrottleMatrixConfigurations() && task instanceof MatrixConfiguration) {
            return false;
       } 
       if (!matrixOptions.isThrottleMatrixBuilds()&& task instanceof MatrixProject) {
            return false;
       }
       
       // Allow throttling by default
       return true;
    }
}
