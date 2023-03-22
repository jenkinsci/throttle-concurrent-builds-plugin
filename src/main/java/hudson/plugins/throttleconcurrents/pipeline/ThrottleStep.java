package hudson.plugins.throttleconcurrents.pipeline;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class ThrottleStep extends Step implements Serializable {
    private List<String> categories;

    @DataBoundConstructor
    public ThrottleStep(@NonNull List<String> categories) {
        this.categories = categories;
    }

    @NonNull
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

        @SuppressWarnings({"lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"})
        public FormValidation doCheckCategoryName(@QueryParameter String value) {
            return ThrottleJobProperty.fetchDescriptor().doCheckCategoryName(value);
        }

        public List<ThrottleJobProperty.ThrottleCategory> getCategories() {
            return ThrottleJobProperty.fetchDescriptor().getCategories();
        }

        @SuppressWarnings("lgtm[jenkins/csrf]")
        public ListBoxModel doFillCategoryItems(@AncestorInPath Item item) {
            return ThrottleJobProperty.fetchDescriptor().doFillCategoryItems(item);
        }
    }

}
