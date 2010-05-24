package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Queue.Task;
import hudson.model.Queue.QueueDecisionHandler;
import java.util.List;
import java.io.IOException;
import hudson.model.AbstractProject;
import hudson.model.JobProperty;
import hudson.model.Label;

import java.util.logging.Logger;
import java.util.logging.Level;

@Extension
public class ThrottleQueueDecisionHandler extends QueueDecisionHandler {
    
    @Override
    public boolean shouldSchedule(Task p, List<Action> actions) {
        if (p instanceof AbstractProject) {
            AbstractProject project = (AbstractProject) p;

            // ...why the hell do I need to cast this? Guh.
            ThrottleJobProperty tjp = (ThrottleJobProperty)project.getProperty(ThrottleJobProperty.class);

            // If the job property exists and is enabled...
            if (tjp!=null && tjp.getThrottleEnabled()) {
                String baseLabel = tjp.getBaseLabel();
                String dynamicLabel = tjp.getDynamicLabelToUse();

                /*
                Label actualLabel = Hudson.getInstance().getLabel(dynamicLabel);
                LOGGER.log(Level.WARNING, "QDH: Pre-assigned label: " + actualLabel.getName());
                LOGGER.log(Level.WARNING, "QDH: Idle executors: " + actualLabel.getIdleExecutors());
                for (Node n : actualLabel.getNodes()) {
                    LOGGER.log(Level.WARNING, "QDH: Eligible node: " + n.getNodeName());
                }
                */
                
                try {
                    project.setAssignedLabel(actualLabel);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            /*
            Label assignedLabel = project.getAssignedLabel();
            if (assignedLabel != null) {
                LOGGER.log(Level.WARNING, "QDH: After set, label is: " + assignedLabel.getName() + " with " + assignedLabel.getIdleExecutors() + " idle executors");
                } */
        }
        return true;
    }

    
    private static final Logger LOGGER = Logger.getLogger(ThrottleQueueDecisionHandler.class.getName());

}
