package hudson.plugins.throttleconcurrents.pipeline;

import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;

public class ThrottleStep extends Step {
    private String category;

    @DataBoundConstructor
    public ThrottleStep(@Nonnull String category) {
        this.category = category;
    }

    @Nonnull
    public String getCategory() {
        return category;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new ThrottleStepExecution(this, context);
    }

}
