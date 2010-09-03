package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Node;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.util.regex.Pattern;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import java.util.logging.Logger;
import java.util.logging.Level;

public class ThrottleJobProperty extends JobProperty<AbstractProject<?,?>> {

    private Integer maxConcurrentPerNode;
    private Integer maxConcurrentTotal;
    private String category;
    private boolean throttleEnabled;
    
    @DataBoundConstructor
    public ThrottleJobProperty(Integer maxConcurrentPerNode,
                               Integer maxConcurrentTotal,
                               String category,
                               boolean throttleEnabled) {
        this.maxConcurrentPerNode = maxConcurrentPerNode;
        this.maxConcurrentTotal = maxConcurrentTotal;
        this.category = category;
        this.throttleEnabled = throttleEnabled;
    }


    
    public boolean getThrottleEnabled() {
        return throttleEnabled;
    }

    public String getCategory() {
        return category;
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
        private List<ThrottleCategory> categories;
        
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

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            //            req.bindParameters(this, "throttle.");
            //categories = req.bindParametersToList(ThrottleCategory.class, "throttle.categories.");
            req.bindJSON(this, formData);
            save();
            return true;
            //return super.configure(req, formData);
        }

        public FormValidation doCheckMaxConcurrentPerNode(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public FormValidation doCheckMaxConcurrentTotal(@QueryParameter String value) {
            return FormValidation.validateNonNegativeInteger(value);
        }

        public ThrottleCategory getCategoryByName(String categoryName) {
            ThrottleCategory category = null;
            
            for (ThrottleCategory tc : categories) {
                if (tc.getCategoryName().equals(categoryName)) {
                    category = tc;
                }
            }

            return category;
        }

        public List<ThrottleCategory> getCategories() {
            if (categories == null) {
                categories = new ArrayList<ThrottleCategory>();
            }

            return categories;
        }

        public ListBoxModel doFillCategoryItems() {
            ListBoxModel m = new ListBoxModel();

            m.add("(none)", "");
            
            for (ThrottleCategory tc : getCategories()) {
                m.add(tc.getCategoryName());
            }

            return m;
        }
        
    }

    public static final class ThrottleCategory {
        private Integer maxConcurrentPerNode;
        private Integer maxConcurrentTotal;
        private String categoryName;
        
        @DataBoundConstructor
        public ThrottleCategory(String categoryName,
                                Integer maxConcurrentPerNode,
                                Integer maxConcurrentTotal) {
            this.maxConcurrentPerNode = maxConcurrentPerNode;
            this.maxConcurrentTotal = maxConcurrentTotal;
            this.categoryName = categoryName;
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

        public String getCategoryName() {
            return categoryName;
        }
    }

    private static Logger LOGGER =  Logger.getLogger(ThrottleJobProperty.class.getName());
}
