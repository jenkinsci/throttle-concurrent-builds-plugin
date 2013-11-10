package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.Util;

import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.util.ArrayList;
import java.util.List;

public class ThrottleJobProperty extends JobProperty<AbstractProject<?,?>> {
    // Moving category to categories, to support, well, multiple categories per job.
    @Deprecated transient String category;

    private Integer maxConcurrentPerNode;
    private Integer maxConcurrentTotal;
    private List<String> categories;
    private boolean throttleEnabled;
    private String throttleOption;
    private boolean throttleConfiguration;

    /**
     * Store a config version so we're able to migrate config on various
     * functionality upgrades.
     */
    private Long configVersion;

    @DataBoundConstructor
    public ThrottleJobProperty(Integer maxConcurrentPerNode,
                               Integer maxConcurrentTotal,
                               List<String> categories,
                               boolean throttleEnabled,
                               String throttleOption,
                               boolean throttleConfiguration) {
        this.maxConcurrentPerNode = maxConcurrentPerNode == null ? 0 : maxConcurrentPerNode;
        this.maxConcurrentTotal = maxConcurrentTotal == null ? 0 : maxConcurrentTotal;
        this.categories = categories;
        this.throttleEnabled = throttleEnabled;
        this.throttleOption = throttleOption;
        this.throttleConfiguration = throttleConfiguration;
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

        if (configVersion < 1 && throttleOption == null) {
            if (categories.isEmpty()) {
                throttleOption = "project";
            }
            else {
                throttleOption = "category";
                maxConcurrentPerNode = 0;
                maxConcurrentTotal = 0;
            }
        }
        configVersion = 1L;

        return this;
    }

    public boolean getThrottleEnabled() {
        return throttleEnabled;
    }

    public String getThrottleOption() {
        return throttleOption;
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

    public boolean getThrottleConfiguration() {
        return throttleConfiguration;
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

        @DataBoundConstructor
        public ThrottleCategory(String categoryName,
                                Integer maxConcurrentPerNode,
                                Integer maxConcurrentTotal) {
            this.maxConcurrentPerNode = maxConcurrentPerNode == null ? 0 : maxConcurrentPerNode;
            this.maxConcurrentTotal = maxConcurrentTotal == null ? 0 : maxConcurrentTotal;
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

        @Extension public static class DescriptorImpl extends Descriptor<ThrottleCategory> {
            @Override public String getDisplayName() {
                return "";
            }
        }

    }

}
