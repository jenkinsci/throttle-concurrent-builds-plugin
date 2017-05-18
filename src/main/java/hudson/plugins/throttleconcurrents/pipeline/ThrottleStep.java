package hudson.plugins.throttleconcurrents.pipeline;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ThrottleStep extends Step implements Serializable {
    private List<String> categories;

    @DataBoundConstructor
    public ThrottleStep(@Nonnull List<String> categories) {
        this.categories = categories;
    }

    @Nonnull
    public List<String> getCategories() {
        return categories;
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
            return Messages.ThrottleStep_DisplayName();
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

        public List<ThrottleJobProperty.ThrottleCategory> getCategories() {
            return ThrottleJobProperty.fetchDescriptor().getCategories();
        }

        public ListBoxModel doFillCategoryItems() {
            return ThrottleJobProperty.fetchDescriptor().doFillCategoryItems();
        }
    }

}
