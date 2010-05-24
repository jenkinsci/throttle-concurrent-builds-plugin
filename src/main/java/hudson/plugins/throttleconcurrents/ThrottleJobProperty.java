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

    private Boolean throttleEnabled;
    
    private Integer maxConcurrent;
    
    private String baseLabel;
    
    private String dynamicLabel;

    public Map<Node,Integer> nodeRunCnt = new HashMap<Node,Integer>(); 

    @DataBoundConstructor
    public ThrottleJobProperty(Integer maxConcurrent,
                               String baseLabel,
                               String dynamicLabel,
                               Boolean throttleEnabled) {
        this.throttleEnabled = throttleEnabled;
        this.maxConcurrent = maxConcurrent;
        this.baseLabel = baseLabel;
        this.dynamicLabel = dynamicLabel;
    }

    
    public boolean getThrottleEnabled() {
        return (throttleEnabled != null) ? throttleEnabled : false;
    }
    
    public void setThrottleEnabled(Boolean throttleEnabled) {
        this.throttleEnabled = throttleEnabled;
    }
    
    public String getBaseLabel() {
        return this.baseLabel;
    }
    
    public void setBaseLabel(String baseLabel) {
        this.baseLabel = baseLabel;
    }
    
    public String getDynamicLabel() {
        return this.dynamicLabel;
    }
    
    public void setDynamicLabel(String dynamicLabel) {
        this.dynamicLabel = dynamicLabel;
    }
    

    /**
     * Returns dynamicLabel if it's set and not empty, and the default permutation of the base label if
     * it isn't set or is empty.
     */
    public String getDynamicLabelToUse() {
        if (dynamicLabel==null || dynamicLabel.equals(""))
            return baseLabel + "___not_throttled";
        return dynamicLabel;
    }
    
    
    public Integer getMaxConcurrent() {
        if (maxConcurrent == null)
            maxConcurrent = 1;
        
        return maxConcurrent;
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

        public FormValidation doCheckMaxConcurrent(@QueryParameter String value) {
            return FormValidation.validatePositiveInteger(value);
        }


    }         
}
