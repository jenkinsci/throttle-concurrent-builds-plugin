package hudson.plugins.throttleconcurrents.pipeline;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Set;

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
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

        public FormValidation doCheckCategoryName(@QueryParameter String value) {
            return ThrottleJobProperty.fetchDescriptor().doCheckCategoryName(value);
        }
    }

}
