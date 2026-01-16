package hudson.plugins.throttleconcurrents.pipeline;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.throttleconcurrents.ThrottleJobProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

public class ThrottleStepExecution extends StepExecution {
    private final ThrottleStep step;

    public ThrottleStepExecution(@NonNull ThrottleStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @NonNull
    public List<String> getCategories() {
        return Collections.unmodifiableList(step.getCategories());
    }

    private List<String> validateCategories(ThrottleJobProperty.DescriptorImpl descriptor, TaskListener listener) {
        List<String> undefinedCategories = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        List<String> unique = new ArrayList<>();

        if (descriptor.getCategories().isEmpty()) {
            undefinedCategories.addAll(getCategories());
        } else {
            for (String c : getCategories()) {
                if (!unique.contains(c)) {
                    unique.add(c);
                } else {
                    duplicates.add(c);
                }
                if (descriptor.getCategoryByName(c) == null) {
                    undefinedCategories.add(c);
                }
            }
        }

        if (!duplicates.isEmpty()) {
            listener.getLogger()
                    .println("One or more duplicate categories (" + StringUtils.join(duplicates, ", ")
                            + ") specified. Duplicates will be ignored.");
        }

        if (!undefinedCategories.isEmpty()) {
            throw new IllegalArgumentException(
                    "One or more specified categories do not exist: " + StringUtils.join(undefinedCategories, ", "));
        }

        return unique;
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
            for (String category : validateCategories(descriptor, listener)) {
                descriptor.addThrottledPipelineForCategory(runId, flowNodeId, category, listener);
            }
        }

        getContext()
                .newBodyInvoker()
                .withCallback(new Callback(runId, flowNodeId, getCategories()))
                .start();
        return false;
    }

    @Override
    public void stop(Throwable cause) throws Exception {}

    private static final class Callback extends BodyExecutionCallback.TailCall {
        @CheckForNull
        private String runId;

        @CheckForNull
        private String flowNodeId;

        private List<String> categories = new ArrayList<>();

        private static final long serialVersionUID = 1;

        Callback(@CheckForNull String runId, @CheckForNull String flowNodeId, @NonNull List<String> categories) {
            this.runId = runId;
            this.flowNodeId = flowNodeId;
            this.categories.addAll(categories);
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            if (runId != null && flowNodeId != null) {
                for (String category : categories) {
                    ThrottleJobProperty.fetchDescriptor()
                            .removeThrottledPipelineForCategory(
                                    runId, flowNodeId, category, context.get(TaskListener.class));
                }
            }
        }
    }
}
