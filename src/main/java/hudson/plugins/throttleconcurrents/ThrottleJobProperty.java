package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import jenkins.model.Jenkins;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class ThrottleJobProperty extends JobProperty<AbstractProject<?,?>> {
    // Moving category to categories, to support, well, multiple categories per job.
    @Deprecated transient String category;

    private Integer maxConcurrentPerNode;
    private Integer maxConcurrentTotal;
    private List<String> categories;
    private boolean throttleEnabled;
    private String throttleOption;
    private boolean limitOneJobWithMatchingParams;
    private String paramsToUseForLimit;
    private transient boolean throttleConfiguration;
    private @CheckForNull ThrottleMatrixProjectOptions matrixOptions;

    private transient List<String> paramsToCompare;

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
                               boolean limitOneJobWithMatchingParams,
                               String paramsToUseForLimit,
                               @CheckForNull ThrottleMatrixProjectOptions matrixOptions
                               ) {
        this.maxConcurrentPerNode = maxConcurrentPerNode == null ? 0 : maxConcurrentPerNode;
        this.maxConcurrentTotal = maxConcurrentTotal == null ? 0 : maxConcurrentTotal;
        this.categories = categories;
        this.throttleEnabled = throttleEnabled;
        this.throttleOption = throttleOption;
        this.limitOneJobWithMatchingParams = limitOneJobWithMatchingParams;
        this.paramsToUseForLimit = paramsToUseForLimit;
        this.matrixOptions = matrixOptions;
        this.paramToCompare = new ArrayList<String>();
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

    @Override protected void setOwner(AbstractProject<?,?> owner) {
        super.setOwner(owner);
        if (throttleEnabled && categories != null) {
            Map<String,Map<ThrottleJobProperty,Void>> propertiesByCategory = ((DescriptorImpl) getDescriptor()).propertiesByCategory;
            synchronized (propertiesByCategory) {
                for (String c : categories) {
                    Map<ThrottleJobProperty,Void> properties = propertiesByCategory.get(c);
                    if (properties == null) {
                        properties = new WeakHashMap<ThrottleJobProperty,Void>();
                        propertiesByCategory.put(c, properties);
                    }
                    properties.put(this, null);
                }
            }
        }
    }

    public boolean getThrottleEnabled() {
        return throttleEnabled;
    }

    public boolean isLimitOneJobWithMatchingParams() {
        return limitOneJobWithMatchingParams;
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

    static List<AbstractProject<?,?>> getCategoryProjects(String category) {
        assert category != null && !category.equals("");
        List<AbstractProject<?,?>> categoryProjects = new ArrayList<AbstractProject<?, ?>>();
        Collection<ThrottleJobProperty> properties;
        Map<String,Map<ThrottleJobProperty,Void>> propertiesByCategory = Jenkins.getInstance().getDescriptorByType(DescriptorImpl.class).propertiesByCategory;
        synchronized (propertiesByCategory) {
            Map<ThrottleJobProperty,Void> _properties = propertiesByCategory.get(category);
            properties = _properties != null ? new ArrayList<ThrottleJobProperty>(_properties.keySet()) : Collections.<ThrottleJobProperty>emptySet();
        }
        for (ThrottleJobProperty t : properties) {
            if (t.getThrottleEnabled()) {
                if (t.getCategories() != null && t.getCategories().contains(category)) {
                    AbstractProject<?,?> p = t.owner;
                    if (/* not deleted */getItem(p.getParent(), p.getName()) == p && /* has not since been reconfigured */ p.getProperty(ThrottleJobProperty.class) == t) {
                        categoryProjects.add(p);
                    }
                }
            }
        }
        return categoryProjects;
    }

    public List<String> getParamsToCompare() {
        return paramsToCompare;
    }

    private static Item getItem(ItemGroup group, String name) {
        if (group instanceof Jenkins) {
            return ((Jenkins) group).getItemMap().get(name);
        } else {
            return group.getItem(name);
        }
    }

    @Extension
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        private List<ThrottleCategory> categories;

        /** Map from category names, to properties including that category. */
        private Map<String,Map<ThrottleJobProperty,Void>> propertiesByCategory = new HashMap<String,Map<ThrottleJobProperty,Void>>();

        public DescriptorImpl() {
            super(ThrottleJobProperty.class);
            load();
        }

        @Override
        public String getDisplayName() {
            return "Throttle Concurrent Builds";
        }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindJSON(this, formData);
            save();
            return true;
        }

        @Override
        public protected void load() {
            super.load();
            if (paramsToUseForLimit.length() > 0) {
                paramsToCompare = Arrays.asList(paramsToUseForLimit.split(","));
            }
        }

        @Override
        public protected void save() {
            super.save();
            if (paramsToUseForLimit.length() > 0) {
                paramsToCompare = Arrays.asList(paramsToUseForLimit.split(","));
            }
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
        private List<NodeLabeledPair> nodeLabeledPairs;

        @DataBoundConstructor
        public ThrottleCategory(String categoryName,
                                Integer maxConcurrentPerNode,
                                Integer maxConcurrentTotal,
                                List<NodeLabeledPair> nodeLabeledPairs) {
            this.maxConcurrentPerNode = maxConcurrentPerNode == null ? 0 : maxConcurrentPerNode;
            this.maxConcurrentTotal = maxConcurrentTotal == null ? 0 : maxConcurrentTotal;
            this.categoryName = categoryName;
            this.nodeLabeledPairs =
                 nodeLabeledPairs == null ? new ArrayList<NodeLabeledPair>() : nodeLabeledPairs;
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

        public List<NodeLabeledPair> getNodeLabeledPairs() {
            if (nodeLabeledPairs == null)
                nodeLabeledPairs = new ArrayList<NodeLabeledPair>();

            return nodeLabeledPairs;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<ThrottleCategory> {
            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }

    /**
     * @author marco.miller@ericsson.com
     */
    public static final class NodeLabeledPair extends AbstractDescribableImpl<NodeLabeledPair> {
        private String throttledNodeLabel;
        private Integer maxConcurrentPerNodeLabeled;

        @DataBoundConstructor
        public NodeLabeledPair(String throttledNodeLabel,
                               Integer maxConcurrentPerNodeLabeled) {
            this.throttledNodeLabel = throttledNodeLabel == null ? new String() : throttledNodeLabel;
            this.maxConcurrentPerNodeLabeled =
                 maxConcurrentPerNodeLabeled == null ? new Integer(0) : maxConcurrentPerNodeLabeled;
        }

        public String getThrottledNodeLabel() {
            if(throttledNodeLabel == null) {
                throttledNodeLabel = new String();
            }
            return throttledNodeLabel;
        }

        public Integer getMaxConcurrentPerNodeLabeled() {
            if(maxConcurrentPerNodeLabeled == null) {
                maxConcurrentPerNodeLabeled = new Integer(0);
            }
            return maxConcurrentPerNodeLabeled;
        }

        @Extension
        public static class DescriptorImpl extends Descriptor<NodeLabeledPair> {
            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }
}
