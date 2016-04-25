
package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.JobProperty;
import hudson.plugins.project_inheritance.projects.InheritanceProject;
import hudson.plugins.project_inheritance.projects.inheritance.InheritanceSelector;
import hudson.plugins.project_inheritance.util.TimedBuffer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 
 * @author Jacek Tomaka
 * @since TODO
 */

@Extension(optional = true)
public class ThrottleJobPropertySelector extends InheritanceSelector<JobProperty<?>> {
    /*
     * ThrottleJobPropertyCache is a hack to get access to project cache to keep reference to
     * newly created ThrottleJobProperty for as long as needed, i.e. whenever
     * project configuration changes, the cache gets invalidated. But otherwise
     * the reference to a property will be kept.
     */
    private static class ThrottleJobPropertyCache {
        private final static String cacheKey = "ThrottleJobProperty";

        private static TimedBuffer<InheritanceProject, String> getOnInheritChangeBuffer() {
            Field f;
            try {
                f = InheritanceProject.class.getDeclaredField("onInheritChangeBuffer");

                f.setAccessible(true);
                return (TimedBuffer<InheritanceProject, String>) f.get(null);
            } catch (NoSuchFieldException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (SecurityException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IllegalArgumentException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return null;
        }

        public static void put(ThrottleJobProperty property, InheritanceProject project) {
            getOnInheritChangeBuffer().set(project, cacheKey, property);
        }

        public static ThrottleJobProperty get(InheritanceProject project) {
            return (ThrottleJobProperty) getOnInheritChangeBuffer().get(project, cacheKey);
        }
    }

    private static final long serialVersionUID = 1L;

    @Override
    public boolean isApplicableFor(Class<?> clazz) {
        return JobProperty.class.isAssignableFrom(clazz);
    }

    @Override
    public InheritanceSelector.MODE getModeFor(Class<?> clazz) {
        if (ThrottleJobProperty.class.isAssignableFrom(clazz))
            return MODE.MERGE;
        return MODE.NOT_RESPONSIBLE;
    }

    @Override
    public String getObjectIdentifier(JobProperty<?> obj) {
        if (obj != null && ThrottleJobProperty.class.getName().equals(obj.getClass().getName())) {
            return ThrottleJobProperty.class.getName();
        }
        return null;
    }

    private ThrottleJobProperty getThrottleJobProperty(JobProperty<?> jobProperty) {
        if (jobProperty == null)
            return null;
        if (!ThrottleJobProperty.class.isAssignableFrom(jobProperty.getClass()))
            return null;
        return (ThrottleJobProperty) jobProperty;
    }

    private List<String> getMergedCategories(List<String> priorCategories, List<String> latterCategories) {
        if (priorCategories == null || priorCategories.isEmpty())
            return latterCategories;
        if (latterCategories == null || latterCategories.isEmpty())
            return priorCategories;
        HashSet<String> uniqueCategories = new HashSet<String>(priorCategories);
        for (String category : latterCategories) {
            if (!uniqueCategories.contains(category)) {
                uniqueCategories.add(category);
            }
        }
        return new ArrayList<String>(uniqueCategories);
    }

    private Integer getLowerOfMaximums(Integer max1, Integer max2) {
        if (max1 == null || max2 == null)
            return max2;
        return new Integer(Math.min(max1.intValue(), max2.intValue()));
    }

    String getMergedThrottleOption(String prior, String latter) {
        return "CATEGORY";// TODO: JTO think about it.
    }

    private List<List<String>> getMergedParameterGroupsToCompare(boolean mergedIsLimitOneJobWithMatchingParameters,
            List<List<String>> priorParameterGroupsToCompare, List<List<String>> latterParameterGroupsToCompare) {
        if (mergedIsLimitOneJobWithMatchingParameters) {
            return getMergedParameterGroupsToCompare(priorParameterGroupsToCompare, latterParameterGroupsToCompare);
        } else {
            return new ArrayList<List<String>>();
        }
    }

    private boolean getMergedLimitOneJobWithMatchingParameters(boolean limitOneJobWithMatchingParamsPrior,
            boolean limitOneJobWithMatchingParamsLatter) {
        return limitOneJobWithMatchingParamsLatter || limitOneJobWithMatchingParamsPrior;
    }

    private List<List<String>> getMergedParameterGroupsToCompare(List<List<String>> prior, List<List<String>> latter) {
        if (prior == null || prior.isEmpty())
            return latter;
        if (latter == null || latter.isEmpty())
            return prior;
        HashSet<List<String>> uniqueParameterGroup = new HashSet<List<String>>(prior);
        for (List<String> parameterGroup : latter) {
            if (!uniqueParameterGroup.contains(parameterGroup)) {
                uniqueParameterGroup.add(parameterGroup);
            }
        }
        return new ArrayList<List<String>>(uniqueParameterGroup);
    }

    @Override
    public ThrottleJobProperty merge(JobProperty<?> prior, JobProperty<?> latter, InheritanceProject caller) {
        ThrottleJobProperty priorThrottleJobProperty = getThrottleJobProperty(prior);
        ThrottleJobProperty latterThrottleJobProperty = getThrottleJobProperty(latter);
        if (priorThrottleJobProperty == null || !priorThrottleJobProperty.getThrottleEnabled()) {
            return latterThrottleJobProperty;
        }
        if (latterThrottleJobProperty == null || !latterThrottleJobProperty.getThrottleEnabled()) {
            return priorThrottleJobProperty;
        }
        boolean mergedThrottleEnabled = true;

        List<String> mergedCategories = getMergedCategories(priorThrottleJobProperty.getCategories(),
                latterThrottleJobProperty.getCategories());

        Integer mergedMaxConcurrentPerNodePerProject = getLowerOfMaximums(
                priorThrottleJobProperty.getMaxConcurrentPerNode(),
                latterThrottleJobProperty.getMaxConcurrentPerNode());

        Integer mergedMaxConcurrentTotalPerProject = getLowerOfMaximums(
                priorThrottleJobProperty.getMaxConcurrentTotal(), latterThrottleJobProperty.getMaxConcurrentTotal());

        String mergedThrottleOption = getMergedThrottleOption(priorThrottleJobProperty.getThrottleOption(),
                latterThrottleJobProperty.getThrottleOption());

        boolean mergedIsLimitOneJobWithMatchingParameters = getMergedLimitOneJobWithMatchingParameters(
                priorThrottleJobProperty.isLimitOneJobWithMatchingParams(),
                latterThrottleJobProperty.isLimitOneJobWithMatchingParams());

        String mergedParamsToUseForLimit = null;

        ThrottleMatrixProjectOptions mergedThrottleMatrixProjectOptions = null;

        List<List<String>> mergedParameterGroupsToCompare = getMergedParameterGroupsToCompare(
                mergedIsLimitOneJobWithMatchingParameters, priorThrottleJobProperty.getParameterGroupsToCompare(),
                latterThrottleJobProperty.getParameterGroupsToCompare());

        return new ThrottleJobProperty(mergedMaxConcurrentPerNodePerProject, mergedMaxConcurrentTotalPerProject,
                mergedCategories, mergedThrottleEnabled, mergedThrottleOption,
                mergedIsLimitOneJobWithMatchingParameters, mergedParamsToUseForLimit,
                mergedThrottleMatrixProjectOptions, mergedParameterGroupsToCompare);
    }

    private static boolean areEssentiallyTheSame(ThrottleJobProperty jobProperty1, ThrottleJobProperty jobProperty2) {
        if (jobProperty1 == null) {
            return jobProperty2 == null;
        }
        if (jobProperty2 == null)
            return false;

        if (jobProperty1.getThrottleEnabled() != jobProperty2.getThrottleEnabled())
            return false;

        if (!Objects.equals(jobProperty1.getCategories(), jobProperty2.getCategories()))
            return false;

        if (!Objects.equals(jobProperty1.getMaxConcurrentPerNode(), jobProperty2.getMaxConcurrentPerNode()))
            return false;

        if (!Objects.equals(jobProperty1.getMaxConcurrentTotal(), jobProperty2.getMaxConcurrentTotal()))
            return false;

        if (!Objects.equals(jobProperty1.getThrottleOption(), jobProperty2.getThrottleOption()))
            return false;

        if (jobProperty1.isLimitOneJobWithMatchingParams() != jobProperty2.isLimitOneJobWithMatchingParams())
            return false;

        if (!Objects.equals(jobProperty1.getParameterGroupsToCompare(), jobProperty2.getParameterGroupsToCompare()))
            return false;
        return true;
    }

    @Override
    public JobProperty<?> handleSingleton(JobProperty<?> jobProperty, InheritanceProject caller) {
        if (jobProperty == null || caller == null)
            return jobProperty;
        if (caller.isAbstract)
            return jobProperty;

        if (!ThrottleJobProperty.class.isAssignableFrom(jobProperty.getClass()))
            return jobProperty;
        ThrottleJobProperty cachedJobProperty = ThrottleJobPropertyCache.get(caller);
        if (cachedJobProperty == null || !areEssentiallyTheSame(cachedJobProperty, (ThrottleJobProperty) jobProperty)) {
            cachedJobProperty = new ThrottleJobProperty((ThrottleJobProperty) jobProperty, caller);
            ThrottleJobPropertyCache.put(cachedJobProperty, caller);
        } 
        return cachedJobProperty;

    }
}
