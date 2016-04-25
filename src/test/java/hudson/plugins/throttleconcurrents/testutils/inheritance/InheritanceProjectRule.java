package hudson.plugins.throttleconcurrents.testutils.inheritance;

import java.io.IOException;

import org.jvnet.hudson.test.JenkinsRule;

import hudson.plugins.project_inheritance.projects.InheritanceProject;
import hudson.plugins.project_inheritance.projects.references.SimpleProjectReference;

public class InheritanceProjectRule extends JenkinsRule {
    InheritanceProject createInheritanceProject() throws IOException{
        return createInheritanceProject(createUniqueProjectName());
    }
    InheritanceProject createInheritanceProject(String name) throws IOException{
        return jenkins.createProject(InheritanceProject.class, name);
    }
    /**
     * Returns BASE,DERIVED projects
     * @throws IOException 
     */
    public InheritanceProjectsPair createInheritanceProjectDerivedWithBase() throws IOException{
        String baseProjectName = createUniqueProjectName();
        InheritanceProject base = createInheritanceProject(baseProjectName);
        InheritanceProject derived = createInheritanceProject();
        derived.addParentReference(new SimpleProjectReference(baseProjectName));
        return new InheritanceProjectsPair(base, derived);
    }
}
