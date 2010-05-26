package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Node;
import hudson.util.FormValidation;

import java.util.regex.Pattern;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.util.Map;
import java.util.HashMap;

public class ThrottleJobProperty extends JobProperty<AbstractProject<?,?>> {

    private Integer maxConcurrentPerNode;
    private Integer maxConcurrentTotal;
    
    @DataBoundConstructor
    public ThrottleJobProperty(Integer maxConcurrentPerNode,
                               Integer maxConcurrentTotal) {
        this.maxConcurrentPerNode = maxConcurrentPerNode;
        this.maxConcurrentTotal = maxConcurrentTotal;
    }


    
    public boolean getThrottleEnabled() {
        if (((maxConcurrentPerNode != null) && (maxConcurrentPerNode.intValue() != 0))
            || (maxConcurrentTotal != null) && (maxConcurrentTotal.intValue() != 0)) {
            return true;
        }
        return false;
    }
    
    public Integer getMaxConcurrentPerNode() {
        if (maxConcurrentPerNode == null)
            maxConcurrentPerNode = 0;
        
        return maxConcurrentPerNode;
    }

    public Integer getMaxConcurrentTotal() {
        if (maxConcurrentTotal == null)
            maxConcurrentTotal = 0;
        
        return maxConcurrentTotal;
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        
        public DescriptorImpl() {
            super(ThrottleJobProperty.class);
            load();
        }
        
        @Override
        public String getDisplayName() {
            return "Throttle Concurrent Builds";
        }
        
        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        public FormValidation doCheckMaxConcurrentPerNode(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckMaxConcurrentTotal(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

    }

}
