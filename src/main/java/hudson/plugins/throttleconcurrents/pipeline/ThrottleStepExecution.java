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

public class ThrottleStepExecution extends StepExecution {
    private final ThrottleStep step;

    private BodyExecution body;

    public ThrottleStepExecution(@Nonnull ThrottleStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    public String getCategory() {
        return step.getCategory();
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
            descriptor.addThrottledPipelineForCategory(runId, flowNodeId, getCategory(), listener);
        }

        body = getContext().newBodyInvoker()
                .withCallback(new Callback(runId, flowNodeId, getCategory()))
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
        private String category;


        private static final long serialVersionUID = 1;

        Callback(String runId, String flowNodeId, @Nonnull String category) {
            this.runId = runId;
            this.flowNodeId = flowNodeId;
            this.category = category;
        }

        @Override protected void finished(StepContext context) throws Exception {
            if (runId != null && flowNodeId != null) {
                ThrottleJobProperty.fetchDescriptor().removeThrottledPipelineForCategory(runId,
                        flowNodeId,
                        category,
                        context.get(TaskListener.class));
            }
        }
    }
}
