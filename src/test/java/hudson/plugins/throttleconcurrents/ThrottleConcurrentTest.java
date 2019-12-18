package hudson.plugins.throttleconcurrents;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.AfterStage;
import com.tngtech.jgiven.annotation.BeforeStage;
import com.tngtech.jgiven.annotation.ScenarioState;
import com.tngtech.jgiven.junit.ScenarioTest;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import hudson.tasks.Builder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import jenkins.model.Jenkins;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

@Ignore("Depends on a newer version of Guava than can be used with Pipeline")
public class ThrottleConcurrentTest extends ScenarioTest<ThrottleConcurrentTest.GivenStage, ThrottleConcurrentTest.WhenAction, ThrottleConcurrentTest.ThenSomeOutcome> {
    @Rule
    @ScenarioState
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void category_per_node_throttling() throws Exception {
        int nodeNumber = 2;
        int maxPerNode = 3;

        given()
                .$_nodes(nodeNumber).with().$_executors(10)
                .and()
                .a_category().with().maxConcurrentPerNode(maxPerNode)
                .and()
                .$_projects_having_this_category(maxPerNode * nodeNumber + 5);

        when()
                .each_project_is_built_$_times(3);

        then()
                .there_should_be_at_most_$_concurrent_builds(nodeNumber * maxPerNode)
                .and()
                .at_most_$_concurrent_builds_per_node(maxPerNode);

    }

    @Test
    public void category_total_throttling() throws Exception {
        int nodeNumber = 2;
        int maxTotal = 4;

        given()
                .$_nodes(nodeNumber).with().$_executors(10)
                .and()
                .a_category().with().maxConcurrentTotal(maxTotal)
                .and()
                .$_projects_having_this_category(maxTotal + 3);

        when()
                .each_project_is_built_$_times(3);

        then()
                .there_should_be_at_most_$_concurrent_builds(maxTotal);
    }

    public static class GivenStage extends Stage<GivenStage> {
        @ScenarioState
        private CategorySpec currentCategory;

        @ScenarioState
        public JenkinsRule j;

        @ScenarioState
        private List<RunProject> projects = new ArrayList<RunProject>();

        private int numNodes = 2;
        private int numExecutorsPerNode = 10;


        public GivenStage a_category() {
            currentCategory = new CategorySpec(j);
            return self();
        }

        public GivenStage maxConcurrentPerNode(int maxConcurrentPerNode) throws IOException {
            currentCategory.maxConcurrentPerNode(maxConcurrentPerNode);
            return self();
        }

        public GivenStage maxConcurrentTotal(int maxConcurrentTotal) throws IOException {
            currentCategory.maxConcurrentTotal = maxConcurrentTotal;
            return self();
        }

        public GivenStage $_projects_having_this_category(int num) throws Exception {
            configureNodes();
            for (int i = 0; i < num; i++) {
                projects.add(new RunProject(j, currentCategory.name));
            }
            return self();
        }

        public GivenStage $_nodes(int i) throws Exception {
            numNodes = i;
            return self();
        }

        public GivenStage $_executors(int i) {
            numExecutorsPerNode = i;
            return self();
        }

        public static class CategorySpec extends Stage<CategorySpec> {
            public String name = "cat";
            public int maxConcurrentPerNode;
            public int maxConcurrentTotal;
            private JenkinsRule j;

            public CategorySpec(JenkinsRule j) {
                this.j = j;
            }

            public CategorySpec maxConcurrentPerNode(int maxConcurrentPerNode) throws IOException {
                this.maxConcurrentPerNode = maxConcurrentPerNode;
                return this;
            }

            private void createCategory() {
                ThrottleJobProperty.DescriptorImpl descriptor = (ThrottleJobProperty.DescriptorImpl) j.getInstance().getDescriptor(ThrottleJobProperty.class);
                descriptor.setCategories(ImmutableList.of(new ThrottleJobProperty.ThrottleCategory(name, maxConcurrentPerNode, maxConcurrentTotal, null)));
            }
        }

        @AfterStage
        private void createCategory() {
            if (currentCategory != null) {
                currentCategory.createCategory();
            }
        }

        private void configureNodes() throws Exception {
            j.getInstance().setNumExecutors(numExecutorsPerNode);

            for (int k = 0; k < numNodes - 1; k++) {
                final CountDownLatch latch = new CountDownLatch(1);
                ComputerListener waiter = new ComputerListener() {
                    @Override
                    public void onOnline(Computer C, TaskListener t) {
                        latch.countDown();
                        unregister();
                    }
                };
                waiter.register();
                Jenkins jenkins = j.getInstance();
                synchronized (jenkins) {
                    DumbSlave slave = new DumbSlave("slave" + jenkins.getNodes().size(), "dummy",
                            j.createTmpDir().getPath(), Integer.toString(numExecutorsPerNode), Node.Mode.NORMAL, "", j.createComputerLauncher(null), RetentionStrategy.NOOP, Collections.emptyList());
                    jenkins.addNode(slave);
                }
                latch.await();
            }
        }
    }

    public static class WhenAction extends Stage<WhenAction> {
        @ScenarioState
        private List<RunProject> projects;

        ExecutorService executorService;
        private List<Future<AbstractBuild<?, ?>>> builds;
        @ScenarioState
        private TreeMap<Long, Integer> buildingChanges;
        @ScenarioState
        private TreeMap<Long, Map<String, Integer>> buildsPerNode;

        @BeforeStage
        private void init() {
            executorService = Executors.newFixedThreadPool(projects.size() * 2);
        }

        public WhenAction each_project_is_built_$_times(int i) throws InterruptedException {
            List<RunProject> projectsToBeBuilt = new ArrayList<RunProject>();
            for (RunProject project : projects) {
                projectsToBeBuilt.addAll(Collections.nCopies(i, project));
            }
            Collections.shuffle(projectsToBeBuilt);

            builds = executorService.invokeAll(projectsToBeBuilt);

            return self();
        }

        @AfterStage
        private void teardown() {
            executorService.shutdown();
        }

        @AfterStage
        private void calculateConcurrentBuilds() throws ExecutionException, InterruptedException {
            buildingChanges = new TreeMap<Long, Integer>();
            buildsPerNode = new TreeMap<Long, Map<String, Integer>>();
            for (Future<AbstractBuild<?, ?>> buildFuture : builds) {
                AbstractBuild<?, ?> build = buildFuture.get();
                long startTimeInMillis = build.getStartTimeInMillis();
                buildingChanges.put(startTimeInMillis, Optional.fromNullable(buildingChanges.get(startTimeInMillis)).or(0) + 1);
                long endTimeInMillis = startTimeInMillis + build.getDuration();
                buildingChanges.put(endTimeInMillis, Optional.fromNullable(buildingChanges.get(endTimeInMillis)).or(0) - 1);

                String nodeName = build.getBuiltOnStr();
                Map<String, Integer> nodeChanges = Optional.fromNullable(buildsPerNode.get(startTimeInMillis)).or(new HashMap<String, Integer>());
                nodeChanges.put(nodeName, Optional.fromNullable(nodeChanges.get(nodeName)).or(0) + 1);
                buildsPerNode.put(startTimeInMillis,
                        nodeChanges);

                nodeChanges = Optional.fromNullable(buildsPerNode.get(endTimeInMillis)).or(new HashMap<String, Integer>());
                nodeChanges.put(nodeName, Optional.fromNullable(nodeChanges.get(nodeName)).or(0) - 1);
                buildsPerNode.put(endTimeInMillis,
                        nodeChanges);

            }

        }
    }

    public static class ThenSomeOutcome extends Stage<ThenSomeOutcome> {
        @ScenarioState
        private TreeMap<Long, Integer> buildingChanges;
        @ScenarioState
        private TreeMap<Long, Map<String, Integer>> buildsPerNode;
        @ScenarioState
        private JenkinsRule j;

        public ThenSomeOutcome there_should_be_at_most_$_concurrent_builds(int i) {
            int numberOfConcurrentBuilds = 0;
            int maxConcurrentBuilds = 0;
            for (Map.Entry<Long, Integer> startEndTime : buildingChanges.entrySet()) {
                numberOfConcurrentBuilds += startEndTime.getValue();
                if (numberOfConcurrentBuilds > maxConcurrentBuilds) {
                    maxConcurrentBuilds = numberOfConcurrentBuilds;
                }
            }
            assertThat(maxConcurrentBuilds).isEqualTo(i);
            return self();
        }

        public ThenSomeOutcome at_most_$_concurrent_builds_per_node(int maxConcurrentPerNode) {
            Map<String, Integer> numberOfConcurrentBuilds = new HashMap<String, Integer>();
            Map<String, Integer> maxConcurrentBuilds = new HashMap<String, Integer>();
            for (Map.Entry<Long, Map<String, Integer>> changePerNodePerTime : buildsPerNode.entrySet()) {
                for (Map.Entry<String, Integer> changesPerNode : changePerNodePerTime.getValue().entrySet()) {
                    String nodeName = changesPerNode.getKey();
                    int newValue = Optional.fromNullable(numberOfConcurrentBuilds.get(nodeName)).or(0) +
                            changesPerNode.getValue();
                    numberOfConcurrentBuilds.put(nodeName, newValue);
                    if (newValue > Optional.fromNullable(maxConcurrentBuilds.get(nodeName)).or(0)) {
                        maxConcurrentBuilds.put(nodeName, newValue);
                    }
                }
            }
            assertThat(ImmutableSet.copyOf(maxConcurrentBuilds.values())).containsExactly(maxConcurrentPerNode);
            return self();
        }
    }

    private static class RunProject implements Callable<AbstractBuild<?, ?>> {
        private final Semaphore inQueue = new Semaphore(1);
        private final FreeStyleProject project;
        private final JenkinsRule j;

        private RunProject(JenkinsRule j, String categoryName) throws IOException {
            this.j = j;
            project = createProjectInCategory(categoryName);
            project.getBuildersList().add(new SemaphoreBuilder(inQueue));
        }

        private FreeStyleProject createProjectInCategory(String categoryName) throws IOException {
            FreeStyleProject freeStyleProject = j.createFreeStyleProject();
            freeStyleProject.addProperty(
                    new ThrottleJobProperty(0, 0, ImmutableList.of(categoryName), 
                            true, "category", false, null, null));
            return freeStyleProject;
        }

        @Override
        public AbstractBuild<?, ?> call() throws Exception {
            inQueue.acquire();
            return j.buildAndAssertSuccess(project);
        }

    }

    private static class SemaphoreBuilder extends Builder {
        private Semaphore inBuild;

        SemaphoreBuilder(Semaphore inBuild) {
            this.inBuild = inBuild;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            inBuild.release();
            Thread.sleep(100);
            return true;
        }
    }
}
