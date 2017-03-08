package hudson.plugins.throttleconcurrents.pipeline;

import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

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
        ThrottledStepInfo info = new ThrottledStepInfo(getCategory());

        ThrottledStepInfo parentInfo = getContext().get(ThrottledStepInfo.class);
        if (parentInfo != null) {
            // Make sure we record the node used for the parent throttle step if it exists.
            Computer computer = getContext().get(Computer.class);
            if (computer != null && computer.getNode() != null) {
                parentInfo.setNode(computer.getNode().getNodeName());
            }
            info.setParentInfo(parentInfo);
        }

        Run<?, ?> r = getContext().get(Run.class);
        TaskListener listener = getContext().get(TaskListener.class);

        ThrottleJobProperty.DescriptorImpl descriptor = Jenkins.getActiveInstance().getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);

        descriptor.addThrottledPipelineForCategory(r.getExternalizableId(), getCategory(), listener);

        body = getContext().newBodyInvoker()
                .withContext(info)
                .withCallback(BodyExecutionCallback.wrap(getContext()))
                .start();
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        if (body != null)
            body.cancel(cause);

        Run<?, ?> r = getContext().get(Run.class);
        TaskListener listener = getContext().get(TaskListener.class);

        ThrottleJobProperty.DescriptorImpl descriptor = Jenkins.getActiveInstance().getDescriptorByType(ThrottleJobProperty.DescriptorImpl.class);

        descriptor.removeThrottledPipelineForCategory(r.getExternalizableId(), getCategory(), listener);
    }
}
