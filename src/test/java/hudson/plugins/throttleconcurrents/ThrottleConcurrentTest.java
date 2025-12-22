package hudson.plugins.throttleconcurrents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ThrottleConcurrentTest {

    private static final String CATEGORY = "cat";

    @Test
    void category_per_node_throttling(JenkinsRule j) throws Exception {
        // given
        int numNodes = 2;
        int numExecutorsPerNode = 10;

        int maxConcurrentPerNode = 3;
        int maxConcurrentTotal = 0;

        setCategories(maxConcurrentPerNode, maxConcurrentTotal);
        initExecutors(j, numExecutorsPerNode, numNodes);
        List<RunProject> projects = initProjects(j, maxConcurrentPerNode * numNodes + 5);

        // when
        List<Future<AbstractBuild<?, ?>>> builds = executeBuilds(projects);

        TreeMap<Long, Integer> buildingChanges = new TreeMap<>();
        TreeMap<Long, Map<String, Integer>> buildsPerNode = new TreeMap<>();
        getBuildResults(builds, buildingChanges, buildsPerNode);

        // then
        assertNumberOfBuilds(buildingChanges, numNodes * maxConcurrentPerNode);

        Map<String, Integer> concurrentBuilds = new HashMap<>();
        Map<String, Integer> maxConcurrentBuilds = new HashMap<>();
        for (Map.Entry<Long, Map<String, Integer>> changePerNodePerTime : buildsPerNode.entrySet()) {
            for (Map.Entry<String, Integer> changesPerNode :
                    changePerNodePerTime.getValue().entrySet()) {
                String nodeName = changesPerNode.getKey();
                int newValue =
                        Optional.ofNullable(concurrentBuilds.get(nodeName)).orElse(0) + changesPerNode.getValue();
                concurrentBuilds.put(nodeName, newValue);
                if (newValue
                        > Optional.ofNullable(maxConcurrentBuilds.get(nodeName)).orElse(0)) {
                    maxConcurrentBuilds.put(nodeName, newValue);
                }
            }
        }
        assertThat(new HashSet<>(maxConcurrentBuilds.values()), contains(maxConcurrentPerNode));
    }

    @Test
    void category_total_throttling(JenkinsRule j) throws Exception {
        // given
        int numNodes = 2;
        int numExecutorsPerNode = 10;

        int maxConcurrentPerNode = 0;
        int maxConcurrentTotal = 4;

        setCategories(maxConcurrentPerNode, maxConcurrentTotal);
        initExecutors(j, numExecutorsPerNode, numNodes);
        List<RunProject> projects = initProjects(j, maxConcurrentTotal + 3);

        // when
        List<Future<AbstractBuild<?, ?>>> builds = executeBuilds(projects);

        TreeMap<Long, Integer> buildingChanges = new TreeMap<>();
        TreeMap<Long, Map<String, Integer>> buildsPerNode = new TreeMap<>();
        getBuildResults(builds, buildingChanges, buildsPerNode);

        // then
        assertNumberOfBuilds(buildingChanges, maxConcurrentTotal);
    }

    private static void assertNumberOfBuilds(TreeMap<Long, Integer> buildingChanges, int maxConcurrentTotal) {
        int numberOfConcurrentBuilds = 0;
        int maxConcurrentBuilds = 0;
        for (Map.Entry<Long, Integer> startEndTime : buildingChanges.entrySet()) {
            numberOfConcurrentBuilds += startEndTime.getValue();
            if (numberOfConcurrentBuilds > maxConcurrentBuilds) {
                maxConcurrentBuilds = numberOfConcurrentBuilds;
            }
        }
        assertEquals(maxConcurrentTotal, maxConcurrentBuilds);
    }

    private static void getBuildResults(
            List<Future<AbstractBuild<?, ?>>> builds,
            TreeMap<Long, Integer> buildingChanges,
            TreeMap<Long, Map<String, Integer>> buildsPerNode)
            throws Exception {
        for (Future<AbstractBuild<?, ?>> buildFuture : builds) {
            AbstractBuild<?, ?> build = buildFuture.get();
            long startTimeInMillis = build.getStartTimeInMillis();
            buildingChanges.put(
                    startTimeInMillis,
                    Optional.ofNullable(buildingChanges.get(startTimeInMillis)).orElse(0) + 1);
            long endTimeInMillis = startTimeInMillis + build.getDuration();
            buildingChanges.put(
                    endTimeInMillis,
                    Optional.ofNullable(buildingChanges.get(endTimeInMillis)).orElse(0) - 1);

            String nodeName = build.getBuiltOnStr();
            Map<String, Integer> nodeChanges =
                    Optional.ofNullable(buildsPerNode.get(startTimeInMillis)).orElse(new HashMap<>());
            nodeChanges.put(
                    nodeName, Optional.ofNullable(nodeChanges.get(nodeName)).orElse(0) + 1);
            buildsPerNode.put(startTimeInMillis, nodeChanges);

            nodeChanges =
                    Optional.ofNullable(buildsPerNode.get(endTimeInMillis)).orElse(new HashMap<>());
            nodeChanges.put(
                    nodeName, Optional.ofNullable(nodeChanges.get(nodeName)).orElse(0) - 1);
            buildsPerNode.put(endTimeInMillis, nodeChanges);
        }
    }

    private static List<Future<AbstractBuild<?, ?>>> executeBuilds(List<RunProject> projects) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(projects.size() * 2);

        try {
            List<RunProject> projectsToBeBuilt = new ArrayList<>();
            for (RunProject project : projects) {
                projectsToBeBuilt.addAll(Collections.nCopies(3, project));
            }
            Collections.shuffle(projectsToBeBuilt);

            return executorService.invokeAll(projectsToBeBuilt);
        } finally {
            executorService.shutdown();
        }
    }

    private static void initExecutors(JenkinsRule j, int numExecutorsPerNode, int numNodes) throws Exception {
        j.getInstance().setNumExecutors(numExecutorsPerNode);

        for (int k = 0; k < numNodes - 1; k++) {
            final CountDownLatch latch = new CountDownLatch(1);
            ComputerListener waiter = new ComputerListener() {
                @Override
                public void onOnline(Computer c, TaskListener t) {
                    latch.countDown();
                    unregister();
                }
            };
            waiter.register();
            Jenkins jenkins = j.getInstance();
            synchronized (jenkins) {
                DumbSlave slave = new DumbSlave(
                        "slave" + jenkins.getNodes().size(),
                        "dummy",
                        j.createTmpDir().getPath(),
                        Integer.toString(numExecutorsPerNode),
                        Node.Mode.NORMAL,
                        "",
                        j.createComputerLauncher(null),
                        RetentionStrategy.NOOP,
                        Collections.emptyList());
                jenkins.addNode(slave);
            }
            latch.await();
        }
    }

    private static void setCategories(int maxConcurrentPerNode, int maxConcurrentTotal) {
        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();
        assertNotNull(descriptor);
        descriptor.setCategories(Collections.singletonList(
                new ThrottleJobProperty.ThrottleCategory(CATEGORY, maxConcurrentPerNode, maxConcurrentTotal, null)));
    }

    private static List<RunProject> initProjects(JenkinsRule j, int count) throws Exception {
        List<RunProject> projects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            projects.add(new RunProject(j, CATEGORY));
        }
        return projects;
    }

    private static class RunProject implements Callable<AbstractBuild<?, ?>> {
        private final Semaphore inQueue = new Semaphore(1);
        private final FreeStyleProject project;
        private final JenkinsRule j;

        private RunProject(JenkinsRule j, String categoryName) throws Exception {
            this.j = j;
            project = createProjectInCategory(categoryName);
            project.getBuildersList().add(new SemaphoreBuilder(inQueue));
        }

        private FreeStyleProject createProjectInCategory(String categoryName) throws Exception {
            FreeStyleProject freeStyleProject = j.createFreeStyleProject();
            freeStyleProject.addProperty(new ThrottleJobProperty(
                    0,
                    0,
                    Collections.singletonList(categoryName),
                    true,
                    TestUtil.THROTTLE_OPTION_CATEGORY,
                    false,
                    null,
                    null));
            return freeStyleProject;
        }

        @Override
        public AbstractBuild<?, ?> call() throws Exception {
            inQueue.acquire();
            return j.buildAndAssertSuccess(project);
        }
    }

    private static class SemaphoreBuilder extends Builder {
        private final transient Semaphore inBuild;

        SemaphoreBuilder(Semaphore inBuild) {
            this.inBuild = inBuild;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                throws InterruptedException {
            inBuild.release();
            Thread.sleep(100);
            return true;
        }
    }
}
