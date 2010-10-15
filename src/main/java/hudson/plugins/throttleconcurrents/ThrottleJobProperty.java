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
    // Moving category to categories, to support, well, multiple categories per job.
    @Deprecated transient String category;
    
    private Integer maxConcurrentPerNode;
    private Integer maxConcurrentTotal;
    private List<String> categories;
    private boolean throttleEnabled;
    
    @DataBoundConstructor
    public ThrottleJobProperty(Integer maxConcurrentPerNode,
                               Integer maxConcurrentTotal,
                               List<String> categories,
                               boolean throttleEnabled) {
        this.maxConcurrentPerNode = maxConcurrentPerNode;
        this.maxConcurrentTotal = maxConcurrentTotal;
        this.categories = categories;
        this.throttleEnabled = throttleEnabled;
    }


    /**
     * Migrates deprecated/obsolete data
     */
    public Object readResolve() {
        if (category != null) {
            categories = new ArrayList<String>();

            categories.add(category);
        }

        return this;
    }
    
    public boolean getThrottleEnabled() {
        return throttleEnabled;
    }

    public List<String> getCategories() {
        return categories;
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
            req.bindJSON(this, formData);
            save();
            return true;
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

        public void setCategories(List<ThrottleCategory> categories) {
            this.categories = categories;
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
