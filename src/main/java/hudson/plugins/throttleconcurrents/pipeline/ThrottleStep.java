package hudson.plugins.throttleconcurrents.pipeline;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

public class ThrottleStep extends Step implements Serializable {
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

    private static final long serialVersionUID = 1L;

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "throttle";
        }

        @Override
        public String getDisplayName() {
            return "Throttle execution of node blocks within this body";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

        public FormValidation doCheckCategoryName(@QueryParameter String value) {
            return ThrottleJobProperty.fetchDescriptor().doCheckCategoryName(value);
        }
    }

}
