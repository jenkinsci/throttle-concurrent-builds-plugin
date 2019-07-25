package hudson.plugins.throttleconcurrents;

import hudson.model.AbstractProject;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import org.jvnet.hudson.test.Bug;
import org.jvnet.hudson.test.HudsonTestCase;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ThrottleJobPropertyTest extends HudsonTestCase {

    private static final String THROTTLE_OPTION_CATEGORY = "category"; // TODO move this into ThrottleJobProperty and use consistently; same for "project"
    private final Random random = new Random(System.currentTimeMillis());

    @Bug(19623)
    public void testGetCategoryProjects() throws Exception {
        String alpha = "alpha", beta = "beta", gamma = "gamma"; // category names
        FreeStyleProject p1 = createFreeStyleProject("p1");
        FreeStyleProject p2 = createFreeStyleProject("p2");
        p2.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(alpha), false, THROTTLE_OPTION_CATEGORY, false, "", ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p3 = createFreeStyleProject("p3");
        p3.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(alpha, beta), true, THROTTLE_OPTION_CATEGORY, false, "", ThrottleMatrixProjectOptions.DEFAULT));
        FreeStyleProject p4 = createFreeStyleProject("p4");
        p4.addProperty(new ThrottleJobProperty(1, 1, Arrays.asList(beta, gamma), true, THROTTLE_OPTION_CATEGORY, false, "", ThrottleMatrixProjectOptions.DEFAULT));
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
        p3.removeProperty(ThrottleJobProperty.class);
        assertProjects(beta, p3b);
    }



    public void testToString_withNulls(){
        ThrottleJobProperty tjp = new ThrottleJobProperty(0,0, null, false, null, false, "", ThrottleMatrixProjectOptions.DEFAULT);
        assertNotNull(tjp.toString());
    }

    public void testThrottleJob_constructor_should_store_arguments() {
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();
        boolean expectedLimitOneJobWithMatchingParams = anyBoolean();
        String expectedParamsToUseForLimit = anyString();

        ThrottleJobProperty property = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams, expectedParamsToUseForLimit,
                ThrottleMatrixProjectOptions.DEFAULT);

        assertEquals(expectedMaxConcurrentPerNode, property.getMaxConcurrentPerNode());
        assertEquals(expectedMaxConcurrentTotal, property.getMaxConcurrentTotal());
        assertEquals(expectedCategories, property.getCategories());
        assertEquals(expectedThrottleEnabled, property.getThrottleEnabled());
        assertEquals(expectedThrottleOption, property.getThrottleOption());
    }

    public void testThrottleJob_constructor_should_parse_paramsToUseForLimit() {
        // We just set these values but do not test much, see above for that
        Integer expectedMaxConcurrentPerNode = anyInt();
        Integer expectedMaxConcurrentTotal = anyInt();
        List<String> expectedCategories = Collections.emptyList();
        boolean expectedThrottleEnabled = anyBoolean();
        String expectedThrottleOption = anyString();
        boolean expectedLimitOneJobWithMatchingParams = true;

        // Mix the comma and whitespace separators as commas were documented
        // in original codebase but did not work so people must have used the
        // whitespaces de-facto.
        // Note that the data type of paramsToUseForLimit is a List (ordered)
        // so in the test we expect it as such. Production code seems to use
        // it as an unordered set as suffices for filtering, however.
        // (0*) Null and empty string inputs should result in an empty list
        // object; surrounding whitespace should be truncated so a non-empty
        // input made of just separators is effectively empty for us too.
        String assignedParamsToUseForLimit00 = null;
        List<String> expectedParamsToUseForLimit00 = new ArrayList<String>();
        String assignedParamsToUseForLimit0 = "";
        List<String> expectedParamsToUseForLimit0 = new ArrayList<String>();
        String assignedParamsToUseForLimit0a = " ";
        List<String> expectedParamsToUseForLimit0a = new ArrayList<String>();
        String assignedParamsToUseForLimit0b = " , ";
        List<String> expectedParamsToUseForLimit0b = new ArrayList<String>();
        String assignedParamsToUseForLimit0c = " ,,,  \n";
        List<String> expectedParamsToUseForLimit0c = new ArrayList<String>();
        // (1) One buildarg name listed in input becomes the only one string
        // in the list; surrounding whitespace should be truncated
        String assignedParamsToUseForLimit1 = "ONE_PARAM";
        List<String> expectedParamsToUseForLimit1 = Arrays.asList("ONE_PARAM");
        String assignedParamsToUseForLimit1a = " ONE_PARAM";
        List<String> expectedParamsToUseForLimit1a = Arrays.asList("ONE_PARAM");
        String assignedParamsToUseForLimit1b = " ONE_PARAM\n";
        List<String> expectedParamsToUseForLimit1b = Arrays.asList("ONE_PARAM");
        // (2) Two buildarg names listed in input become two strings in the list
        String assignedParamsToUseForLimit2 = "TWO,PARAMS";
        List<String> expectedParamsToUseForLimit2 = Arrays.asList("TWO", "PARAMS");
        // (3) Different separators handled the same
        String assignedParamsToUseForLimit3 = "THREE DIFFERENT,PARAMS";
        List<String> expectedParamsToUseForLimit3 = Arrays.asList("THREE", "DIFFERENT", "PARAMS");
        // (4) Several separating tokens together must go away as one - we want
        // no empties in the resulting list
        String assignedParamsToUseForLimit4 = "FOUR ,SOMEWHAT\t,DIFFERENT , PARAMS";
        List<String> expectedParamsToUseForLimit4 = Arrays.asList("FOUR", "SOMEWHAT", "DIFFERENT", "PARAMS");
        // (5) Java does not really have multilines, but still... note that
        // if any whitespace should be there in the carry-over of string
        // representation, it is the coder's responsibility to ensure some.
        String assignedParamsToUseForLimit5 = "Multi\nline" +
                "string,for	kicks\n" +
                "EOL";
        List<String> expectedParamsToUseForLimit5 = Arrays.asList("Multi", "linestring", "for", "kicks", "EOL");

        ThrottleJobProperty property00 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit00,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit00, property00.getParamsToCompare());

        ThrottleJobProperty property0 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0, property0.getParamsToCompare());

        ThrottleJobProperty property0a = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0a,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0a, property0a.getParamsToCompare());

        ThrottleJobProperty property0b = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0b,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0b, property0b.getParamsToCompare());

        ThrottleJobProperty property0c = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit0c,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit0c, property0c.getParamsToCompare());

        ThrottleJobProperty property1 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1, property1.getParamsToCompare());

        ThrottleJobProperty property1a = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1a,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1a, property1a.getParamsToCompare());

        ThrottleJobProperty property1b = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit1b,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit1b, property1b.getParamsToCompare());

        ThrottleJobProperty property2 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit2,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit2, property2.getParamsToCompare());

        ThrottleJobProperty property3 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit3,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit3, property3.getParamsToCompare());

        ThrottleJobProperty property4 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit4,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit4, property4.getParamsToCompare());

        ThrottleJobProperty property5 = new ThrottleJobProperty(expectedMaxConcurrentPerNode,
                expectedMaxConcurrentTotal,
                expectedCategories, expectedThrottleEnabled, expectedThrottleOption,
                expectedLimitOneJobWithMatchingParams,
                assignedParamsToUseForLimit5,
                ThrottleMatrixProjectOptions.DEFAULT);
        assertEquals(expectedParamsToUseForLimit5, property5.getParamsToCompare());
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
                anyBoolean(),
                anyString(),
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
                anyBoolean(),
                anyString(),
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
        jenkins.setAuthorizationStrategy(new RejectAllAuthorizationStrategy());
        try {
            assertEquals(new HashSet<Queue.Task>(Arrays.asList(projects)), new HashSet<Queue.Task>
                    (ThrottleJobProperty.getCategoryTasks(category)));
        } finally {
            jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED); // do not check during e.g. rebuildDependencyGraph from delete
        }
    }
    private static class RejectAllAuthorizationStrategy extends AuthorizationStrategy {
        RejectAllAuthorizationStrategy() {}
        @Override public ACL getRootACL() {
            return new AuthorizationStrategy.Unsecured().getRootACL();
        }
        @Override public Collection<String> getGroups() {
            return Collections.emptySet();
        }
        @Override public ACL getACL(Job<?,?> project) {
            fail("not even supposed to be looking at " + project);
            return super.getACL(project);
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
