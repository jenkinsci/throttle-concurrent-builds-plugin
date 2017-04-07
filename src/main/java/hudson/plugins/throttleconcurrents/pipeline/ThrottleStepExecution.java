package hudson.plugins.throttleconcurrents.pipeline;

import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class ThrottleStepExecution extends StepExecution {
    private final ThrottleStep step;

    public ThrottleStepExecution(@Nonnull ThrottleStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Nonnull
    public List<String> getCategories() {
        return step.getCategoriesList();
    }

    @Override
    public boolean start() throws Exception {
        Run<?, ?> r = getContext().get(Run.class);
        TaskListener listener = getContext().get(TaskListener.class);
        FlowNode flowNode = getContext().get(FlowNode.class);

        ThrottleJobProperty.DescriptorImpl descriptor = ThrottleJobProperty.fetchDescriptor();

        String runId = null;
        String flowNodeId = null;

        if (r != null && flowNode != null) {
            runId = r.getExternalizableId();
            flowNodeId = flowNode.getId();
            for (String category : getCategories()) {
                descriptor.addThrottledPipelineForCategory(runId, flowNodeId, category, listener);
            }
        }

        getContext().newBodyInvoker()
                .withCallback(new Callback(runId, flowNodeId, getCategories()))
                .start();
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {

    }

    private static final class Callback extends BodyExecutionCallback.TailCall {
        @CheckForNull
        private String runId;
        @CheckForNull
        private String flowNodeId;
        private List<String> categories = new ArrayList<>();


        private static final long serialVersionUID = 1;

        Callback(@CheckForNull String runId, @CheckForNull String flowNodeId, @Nonnull List<String> categories) {
            this.runId = runId;
            this.flowNodeId = flowNodeId;
            this.categories.addAll(categories);
        }

        @Override protected void finished(StepContext context) throws Exception {
            if (runId != null && flowNodeId != null) {
                for (String category : categories) {
                    ThrottleJobProperty.fetchDescriptor().removeThrottledPipelineForCategory(runId,
                            flowNodeId,
                            category,
                            context.get(TaskListener.class));
                }
            }
        }
    }
}
