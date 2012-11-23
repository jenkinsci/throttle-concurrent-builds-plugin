package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.Util;

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
    // trottleOption replaced with flags throttleProjectAlone and throttleCategory
    @Deprecated transient String throttleOption;
    
    private Integer maxConcurrentPerNode;
    private Integer maxConcurrentTotal;
    private Long interval;
    private List<String> categories;
    private boolean throttleEnabled;

    /**
     * Store a config version so we're able to migrate config on various
     * functionality upgrades.
     */
    private Long configVersion;
    private boolean throttleProjectAlone;
    private boolean throttleCategory;

    @DataBoundConstructor
    public ThrottleJobProperty(Integer maxConcurrentPerNode,
                               Integer maxConcurrentTotal,
                               Long interval,
                               List<String> categories,
                               boolean throttleEnabled,
                               boolean throttleProjectAlone,
                               boolean throttleCategory) {
        this.maxConcurrentPerNode = maxConcurrentPerNode == null ? 0 : maxConcurrentPerNode;
        this.maxConcurrentTotal = maxConcurrentTotal == null ? 0 : maxConcurrentTotal;
        this.interval = interval == null ? 0 : interval;
        this.categories = categories;
        this.throttleEnabled = throttleEnabled;
        this.throttleProjectAlone = throttleProjectAlone;
        this.throttleCategory = throttleCategory;
    }


    /**
     * Migrates deprecated/obsolete data
     */
    public Object readResolve() {
        if (configVersion == null) {
            configVersion = 0L;
        }
        if (categories == null) {
            categories = new ArrayList<String>();
        }
        if (category != null) {
            categories.add(category);
            category = null;
        }

        if (configVersion < 1) {
            if (categories.isEmpty()) {
                throttleProjectAlone = true;
            }
            else {
                throttleCategory = true;
                maxConcurrentPerNode = 0;
                maxConcurrentTotal = 0;
                interval = 0L;
            }
        }
        else if(configVersion == 1){
            if(throttleOption == "project"){
                throttleProjectAlone = true;
            }
            if(throttleOption == "category"){
                throttleCategory = true;
            }
        }

        configVersion = 2L;
        
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

    public Long getInterval() {
        if (interval == null)
            interval = 0L;

        return interval;
    }

    public boolean getThrottleProjectAlone() {
        return throttleProjectAlone;
    }

    public boolean getThrottleCategory() {
        return throttleCategory;
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

        public FormValidation doCheckCategoryName(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.error("Empty category names are not allowed.");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckMaxConcurrentPerNode(@QueryParameter String value) {
            return checkNullOrInt(value);
        }

        private FormValidation checkNullOrInt(String value) {
            // Allow nulls - we'll just translate those to 0s.
            if (Util.fixEmptyAndTrim(value) != null) {
                return FormValidation.validateNonNegativeInteger(value);
            }
            else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckMaxConcurrentTotal(@QueryParameter String value) {
            return checkNullOrInt(value);
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

    public static final class ThrottleCategory extends AbstractDescribableImpl<ThrottleCategory> {
        private Integer maxConcurrentPerNode;
        private Integer maxConcurrentTotal;
        private String categoryName;
        private Long interval;

        @Extension
        public static class DescriptorImpl extends Descriptor<ThrottleCategory> {
            public String getDisplayName() { return ""; }
        }

        @DataBoundConstructor
        public ThrottleCategory(String categoryName,
                                Integer maxConcurrentPerNode,
                                Integer maxConcurrentTotal,
                                Long interval) {
            this.maxConcurrentPerNode = maxConcurrentPerNode == null ? 0 : maxConcurrentPerNode;
            this.maxConcurrentTotal = maxConcurrentTotal == null ? 0 : maxConcurrentTotal;
            this.interval = interval == null ? 0 : interval;
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

        public long getInterval() {
            if (interval == null)
                interval = 0L;

            return interval;
        }

        public String getCategoryName() {
            return categoryName;
        }

        public List<AbstractProject<?, ?>> getProjects() {
            List<AbstractProject<?,?>> categoryProjects = new ArrayList<AbstractProject<?,?>>();
            for (AbstractProject<?,?> p : Hudson.getInstance().getAllItems(AbstractProject.class)) {
                ThrottleJobProperty t = p.getProperty(ThrottleJobProperty.class);

                if (t!=null && t.getThrottleEnabled() && t.getThrottleCategory()) {
                    if (t.getCategories()!=null && t.getCategories().contains(categoryName)) {
                        categoryProjects.add(p);
                    }
                }
            }
            return categoryProjects;
        }
    }

    private static Logger LOGGER =  Logger.getLogger(ThrottleJobProperty.class.getName());
}
