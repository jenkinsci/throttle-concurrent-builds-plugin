package hudson.plugins.throttleconcurrents.inheritance;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import org.jvnet.hudson.test.JenkinsRule;
import com.google.common.collect.ImmutableList;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.plugins.throttleconcurrents.ThrottleConcurrentTest;
import hudson.plugins.throttleconcurrents.testutils.inheritance.InheritanceProjectRule;
import hudson.plugins.throttleconcurrents.testutils.inheritance.InheritanceProjectsPair;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.plugins.project_inheritance.projects.InheritanceProject;
import hudson.plugins.project_inheritance.projects.InheritanceProject.IMode;


public class ThrottleConcurrentInheritanceProjectTest extends ThrottleConcurrentTest<ThrottleConcurrentInheritanceProjectTest.GivenStage>{
    private static class RunProject implements Callable<AbstractBuild<?, ?>> {
        private final Semaphore inQueue = new Semaphore(1);
        
        private final InheritanceProject project;
        protected final JenkinsRule j;

        private RunProject(JenkinsRule j, String categoryName) throws IOException {
            this.j = j;
            project = createProjectInCategory(categoryName);
        }

        protected InheritanceProject createProjectInCategory(String categoryName) throws IOException {
            InheritanceProjectsPair inheritanceProjectsPair = ((InheritanceProjectRule)j).createInheritanceProjectDerivedWithBase();
            inheritanceProjectsPair.getBase().addProperty(
                    new ThrottleJobProperty(0, 0, ImmutableList.of(categoryName), 
                            true, "category", false, null, null));
            inheritanceProjectsPair.getBase().getBuildersList(IMode.LOCAL_ONLY).add(new SemaphoreBuilder(inQueue));
            //InheritanceProject.clearBuffers(inheritanceProjectsPair.getDerived());
            //DescribableList<Builder, Descriptor<Builder>> list = inheritanceProjectsPair.getDerived().getBuildersList();
            return inheritanceProjectsPair.getDerived();
        }

        @Override
        public AbstractBuild<?, ?> call() throws Exception {
            System.out.println(String.format("Thread %s %d will attempt to acquire semaphore %s",Thread.currentThread().getName(), Thread.currentThread().getId() , project.toString()));
            inQueue.acquire();
            try{
                System.out.println(String.format("Thread %s %d acquired semaphore %s",Thread.currentThread().getName(), Thread.currentThread().getId() , project.toString() ));
                AbstractBuild<?, ?> build = j.buildAndAssertSuccess(project);
                System.out.println(String.format("Thread %s %d returned %s",Thread.currentThread().getName(), Thread.currentThread().getId(), build == null?"NULL":build.toString()));
                return build;
            }catch (Exception ex){
                ex.printStackTrace();
                throw ex;
            }
        }
    }

   
    public static class GivenStage extends hudson.plugins.throttleconcurrents.ThrottleConcurrentTest.GivenStage {
        public Callable<AbstractBuild<?, ?>> createRunProject(JenkinsRule j, String categoryName) throws IOException {
            return new RunProject(j, categoryName);
        }
    }
   public ThrottleConcurrentInheritanceProjectTest(){
       super();
       this.j =  new InheritanceProjectRule();
   }
}
