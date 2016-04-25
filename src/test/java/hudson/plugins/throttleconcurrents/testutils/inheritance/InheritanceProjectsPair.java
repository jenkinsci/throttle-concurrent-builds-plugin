package hudson.plugins.throttleconcurrents.testutils.inheritance;

import hudson.plugins.project_inheritance.projects.InheritanceProject;
/**
 * @author Jacek Tomaka
 */
public class InheritanceProjectsPair {

    private InheritanceProject base;
    private InheritanceProject derived;
    public InheritanceProject getBase() {
        return base;
    }
    public InheritanceProject getDerived() {
        return derived;
    }

    public InheritanceProjectsPair(InheritanceProject base, InheritanceProject derived){
        this.base = base;
        this.derived = derived;
    }
}
