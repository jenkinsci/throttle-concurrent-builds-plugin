package hudson.plugins.throttleconcurrents;

import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import hudson.security.AuthorizationStrategy;
import hudson.security.NotSerilizableSecurityContext;
import hudson.security.GlobalMatrixAuthorizationStrategy;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.HudsonTestCase;

/*
import com.cloudbees.hudson.plugins.folder.Folder;
*/

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import jenkins.model.Jenkins;

public class ThrottleJobPropertyTest extends HudsonTestCase {

    private static final String THROTTLE_OPTION_CATEGORY = "category"; // TODO move this into ThrottleJobProperty and use consistently; same for "project"
    private final Random random = new Random(System.currentTimeMillis());

    @Bug(19623)
    public void testGetCategoryProjects() throws Exception {
        String alpha = "alpha", beta = "beta", gamma = "gamma"; // category names
        FreeStyleProject p1 = createFreeStyleProject("p1");
        FreeStyleProject p2 = createFreeStyleProject("p2");
        p2.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(alpha), false, THROTTLE_OPTION_CATEGORY, ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p3 = createFreeStyleProject("p3");
        p3.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(alpha, beta), true, THROTTLE_OPTION_CATEGORY, ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p4 = createFreeStyleProject("p4");
        p4.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(beta, gamma), true, THROTTLE_OPTION_CATEGORY, ThrottleMatrixProjectOptions.DEFAULT));
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
        p3.removeProperty(ThrottleJobProperty.class);
        assertProjects(beta, p3b);
    }

    /*
    // Requires Jenkins >= 1.480.3 and cloudbees-folder-plugin
    @Bug(25326)
    public void testGetCategoryProjectsInFolder() throws Exception {
        Folder f = jenkins.createProject(Folder.class, "folder");
        FreeStyleProject p = f.createProject(FreeStyleProject.class, "p");
        p.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList("category"), true, THROTTLE_OPTION_CATEGORY, ThrottleMatrixProjectOptions.DEFAULT));
        assertProjects("category", p);
    }
    */



    public void testToString_withNulls(){
        ThrottleJobProperty tjp = new ThrottleJobProperty(0,0, null, false, null, ThrottleMatrixProjectOptions.DEFAULT);
        assertNotNull(tjp.toString());
    }

    public void testThrottleJob_constructor_should_store_arguments() {
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();

        ThrottleJobProperty property = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                ThrottleMatrixProjectOptions.DEFAULT);

        assertEquals(expectedMaxConcurrentPerNode, property.getMaxConcurrentPerNode());
        assertEquals(expectedMaxConcurrentTotal, property.getMaxConcurrentTotal());
        assertEquals(expectedCategories, property.getCategories());
        assertEquals(expectedThrottleEnabled, property.getThrottleEnabled());
        assertEquals(expectedThrottleOption, property.getThrottleOption());
    }

    public void testThrottleJob_should_copy_categories_to_concurrency_safe_list() {
        final String category = anyString();

        ArrayList<String> unsafeList = new ArrayList<String>() {{
            add(category);
        }};

        ThrottleJobProperty property = new ThrottleJobProperty(anyInt(),
                anyInt(),
                unsafeList,
                anyBoolean(),
                "throttle_option",
                ThrottleMatrixProjectOptions.DEFAULT);

        List<String> storedCategories = property.getCategories();
        assertEquals("contents of original and stored list should be the equal", unsafeList, storedCategories);
        assertTrue("expected unsafe list to be converted to a converted to some other concurrency-safe impl",
                unsafeList != storedCategories);
        assertTrue(storedCategories instanceof CopyOnWriteArrayList);
    }

    public void testThrottleJob_constructor_handles_null_categories(){
        ThrottleJobProperty property = new ThrottleJobProperty(anyInt(),
                anyInt(),
                null,
                anyBoolean(),
                "throttle_option",
                ThrottleMatrixProjectOptions.DEFAULT);

        assertEquals(Collections.<String>emptyList(), property.getCategories());
    }

    public void testDescriptorImpl_should_a_concurrency_safe_list_for_categories(){
        ThrottleJobProperty.DescriptorImpl descriptor = new ThrottleJobProperty.DescriptorImpl();

        assertTrue(descriptor.getCategories() instanceof CopyOnWriteArrayList);

        final ThrottleJobProperty.ThrottleCategory category = new ThrottleJobProperty.ThrottleCategory(
                anyString(), anyInt(), anyInt(), null);

        ArrayList<ThrottleJobProperty.ThrottleCategory> unsafeList =
                new ArrayList<ThrottleJobProperty.ThrottleCategory>() {{
                    add(category);
                }};


        descriptor.setCategories(unsafeList);
        List<ThrottleJobProperty.ThrottleCategory> storedCategories = descriptor.getCategories();
        assertEquals("contents of original and stored list should be the equal", unsafeList, storedCategories);
        assertTrue("expected unsafe list to be converted to a converted to some other concurrency-safe impl",
                unsafeList != storedCategories);
        assertTrue(storedCategories instanceof CopyOnWriteArrayList);
    }


    private void assertProjects(String category, AbstractProject<?,?>... projects) {
        // Queue runs as ANONYMOUS.
        // Ensure throttle-concurrent-builds works even without any permissions for ANONYMOUS.
        jenkins.setAuthorizationStrategy(new GlobalMatrixAuthorizationStrategy());
        SecurityContext orig = SecurityContextHolder.getContext();
        NotSerilizableSecurityContext auth = new NotSerilizableSecurityContext();
        auth.setAuthentication(Jenkins.ANONYMOUS);
        SecurityContextHolder.setContext(auth);
        try {
            assertEquals(new HashSet<Queue.Task>(Arrays.asList(projects)), new HashSet<Queue.Task>
                    (ThrottleJobProperty.getCategoryTasks(category)));
        } finally {
            SecurityContextHolder.setContext(orig);
            jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED); // do not check during e.g. rebuildDependencyGraph from delete
        }
    }

    private String anyString() {
        return "concurrency_" + anyInt();
    }

    private boolean anyBoolean() {
        return random.nextBoolean();
    }

    private int anyInt() {
        return random.nextInt(10000);
    }

}
