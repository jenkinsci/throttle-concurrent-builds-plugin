package hudson.plugins.throttleconcurrents;

import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.HudsonTestCase;

public class ThrottleJobPropertyTest extends HudsonTestCase {

    private static final String THROTTLE_OPTION_CATEGORY = "category"; // TODO move this into ThrottleJobProperty and use consistently; same for "project"

    @Bug(19623)
    public void testGetCategoryProjects() throws Exception {
        String alpha = "alpha", beta = "beta", gamma = "gamma"; // category names
        FreeStyleProject p1 = createFreeStyleProject("p1");
        FreeStyleProject p2 = createFreeStyleProject("p2");
        p2.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(alpha), false, THROTTLE_OPTION_CATEGORY));
        FreeStyleProject p3 = createFreeStyleProject("p3");
        p3.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(alpha, beta), true, THROTTLE_OPTION_CATEGORY));
        FreeStyleProject p4 = createFreeStyleProject("p4");
        p4.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(beta, gamma), true, THROTTLE_OPTION_CATEGORY));
        // TODO when core dep ≥1.480.3, add cloudbees-folder as a test dependency so we can check jobs inside folders
        assertProjects(alpha, p3);
        assertProjects(beta, p3, p4);
        assertProjects(gamma, p4);
        assertProjects("delta");
        p4.renameTo("p-4");
        assertProjects(gamma, p4);
        p4.delete();
        assertProjects(gamma);
        AbstractProject<?,?> p3b = jenkins.<AbstractProject<?,?>>copy(p3, "p3b");
        assertProjects(beta, p3, p3b);
    }
    private void assertProjects(String category, AbstractProject<?,?>... projects) {
        jenkins.setAuthorizationStrategy(new RejectP1P2AuthorizationStrategy());
        try {
            assertEquals(new HashSet<AbstractProject<?,?>>(Arrays.asList(projects)), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects(category)));
        } finally {
            jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED); // do not check during e.g. rebuildDependencyGraph from delete
        }
    }
    private static class RejectP1P2AuthorizationStrategy extends AuthorizationStrategy {
        RejectP1P2AuthorizationStrategy() {}
        @Override public ACL getRootACL() {
            return new AuthorizationStrategy.Unsecured().getRootACL();
        }
        @Override public Collection<String> getGroups() {
            return Collections.emptySet();
        }
        @Override public ACL getACL(Job<?,?> project) {
            assertFalse("not even supposed to be looking at " + project, project.getFullName().matches("p1|p2"));
            return super.getACL(project);
        }
    }


}
