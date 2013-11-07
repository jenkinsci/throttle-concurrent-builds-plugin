package hudson.plugins.throttleconcurrents;

import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import java.util.Arrays;
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
        assertEquals(Collections.singleton(p3), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects(alpha)));
        assertEquals(new HashSet<AbstractProject<?,?>>(Arrays.asList(p3, p4)), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects(beta)));
        assertEquals(Collections.singleton(p4), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects(gamma)));
        assertEquals(Collections.emptySet(), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects("delta")));
        p4.renameTo("p-4");
        assertEquals(Collections.singleton(p4), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects(gamma)));
        p4.delete();
        assertEquals(Collections.emptySet(), new HashSet<AbstractProject<?,?>>(ThrottleJobProperty.getCategoryProjects(gamma)));
    }


}
