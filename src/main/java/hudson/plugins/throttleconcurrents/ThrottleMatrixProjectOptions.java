/*
 * The MIT License
 *
 * Copyright 2014 Oleg Nenashev, Synopsys Inc.
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
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixConfiguration;
import hudson.model.Describable;
import hudson.model.Descriptor;
import javax.annotation.Nonnull;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Defines additional options for throttling of {@link MatrixBuild}s and
 * {@link MatrixConfiguration}s.
 * This class is intended to be used inside {@link ThrottleJobProperty}.
 * @author Oleg Nenashev (github:oleg-nenashev)
 * @since TODO: 1.9.0?
 */
public class ThrottleMatrixProjectOptions implements Describable<ThrottleMatrixProjectOptions> {
    
    private final boolean throttleMatrixBuilds;
    private final boolean throttleMatrixConfigurations;
    
    /**
     * A default configuration, which retains the behavior from
     * version 1.8. 
     */
    public static final ThrottleMatrixProjectOptions DEFAULT = 
            new ThrottleMatrixProjectOptions(true, false);
    
    @DataBoundConstructor
    public ThrottleMatrixProjectOptions(boolean throttleMatrixBuilds, boolean throttleMatrixConfigurations) {
        this.throttleMatrixBuilds = throttleMatrixBuilds;
        this.throttleMatrixConfigurations = throttleMatrixConfigurations;
    }

    public boolean isThrottleMatrixBuilds() {
        return throttleMatrixBuilds;
    }

    public boolean isThrottleMatrixConfigurations() {
        return throttleMatrixConfigurations;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
    
    @Override
    public Descriptor<ThrottleMatrixProjectOptions> getDescriptor() {
        return DESCRIPTOR;
    }
    
    public static class DescriptorImpl extends Descriptor<ThrottleMatrixProjectOptions> {

        @Override
        public String getDisplayName() {
            return Messages.ThrottleMatrixProjectOptions_DisplayName();
        }
        
        @Nonnull
        public ThrottleMatrixProjectOptions getDefaults() {
            return ThrottleMatrixProjectOptions.DEFAULT;
        }
    }
}
