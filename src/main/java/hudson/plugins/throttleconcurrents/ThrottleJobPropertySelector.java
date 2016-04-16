
package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.JobProperty;
import hudson.plugins.project_inheritance.projects.InheritanceProject;
import hudson.plugins.project_inheritance.projects.inheritance.InheritanceSelector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;



/**
 * 
 * @author Jacek Tomaka
 * @since TODO
 */

@Extension(optional = true)
public class ThrottleJobPropertySelector extends InheritanceSelector<JobProperty<?>> {
    class ParameterGroup {
        private List<String> parameters;
        public ParameterGroup(List<String> parameters){
            this.parameters = parameters;
        }
        @Override
        public boolean equals(Object other){
            if (!(other instanceof ParameterGroup)){
                return false;
            }
            ParameterGroup otherParameterGroup = (ParameterGroup)other;
            if (parameters == null) return otherParameterGroup.parameters == null;
            if (otherParameterGroup.parameters == null) return false;
            if (parameters.size()!=otherParameterGroup.parameters.size()) return false;
            for (int i=0;i<parameters.size();i++){
                if (!parameters.get(i).equals(otherParameterGroup.parameters.get(i))){
                    return false;
                }
            }
            return true;
            
        }
        @Override
        public int hashCode(){
            if (parameters == null) return 0;
            if (parameters.isEmpty()) return 1;
            return parameters.get(0).hashCode()^parameters.size();
        }
        public List<String> getParameters() {
           return parameters;
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
    private List<List<String>> getMergedParameterGroupsToCompare(List<List<String>> prior, List<List<String>> latter){
        if (prior == null || prior.isEmpty() ) return latter;
        if (latter == null || latter.isEmpty() ) return prior;
        HashSet<ParameterGroup> uniqueParameterGroup = new HashSet<ParameterGroup>(wrapParameterGroups(prior));
        for(ParameterGroup parameterGroup:wrapParameterGroups(latter)){
            if (!uniqueParameterGroup.contains(parameterGroup)){
                uniqueParameterGroup.add(parameterGroup);
            }
        }
        return unwrapParameterGroups(uniqueParameterGroup);
    }
    private List<ParameterGroup> wrapParameterGroups(Collection<List<String>> parameterGroups) {
        if (parameterGroups == null || parameterGroups.isEmpty()){
            return new ArrayList<ParameterGroup>(0);
        }
        List<ParameterGroup> wrappedParameterGroups = new ArrayList<ParameterGroup>(parameterGroups.size());
        for (List<String> parameterGroup:parameterGroups){
            wrappedParameterGroups.add(new ParameterGroup(parameterGroup));
        }
        return wrappedParameterGroups;
    }
    private List<List<String>> unwrapParameterGroups(Collection<ParameterGroup> parameterGroups){
        if (parameterGroups == null || parameterGroups.isEmpty()){
            return new ArrayList<List<String>>(0);
        }
        List<List<String>> unwrappedParameterGroups = new ArrayList<List<String>>(parameterGroups.size());
        for (ParameterGroup parameterGroup:parameterGroups){
            unwrappedParameterGroups.add(parameterGroup.getParameters());
        }
        return unwrappedParameterGroups;
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
        
        Integer mergedMaxConcurrentPerNodePerProject = getLowerOfMaximums(priorThrottleJobProperty.getMaxConcurrentPerNode(),
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

    @Override
    public JobProperty<?> handleSingleton(JobProperty<?> jobProperty, InheritanceProject caller) {
        if (jobProperty == null || caller == null)
            return jobProperty;
        if (caller.isAbstract)
            return jobProperty;

        if (!ThrottleJobProperty.class.isAssignableFrom(jobProperty.getClass()))
            return jobProperty;
       

        return new ThrottleJobProperty((ThrottleJobProperty)jobProperty, caller);
    }
}
