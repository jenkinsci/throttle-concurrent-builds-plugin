package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.logging.Logger;
import java.util.logging.Level;

@Extension
public class ThrottleRunListener extends RunListener<AbstractBuild> {
    private Integer runCnt = 0;
    
    public ThrottleRunListener() {
        super(AbstractBuild.class);
        this.runCnt = 0;
    }

    public Integer getRunCnt() {
        return runCnt;
    }
    
    @Override
    public void onStarted(AbstractBuild r, TaskListener listener) {
        AbstractBuild<?,?> b = (AbstractBuild<?,?>)r;
        Node currentNode = b.getExecutor().getOwner().getNode();
        ThrottleJobProperty tjp = b.getProject().getProperty(ThrottleJobProperty.class);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            
            runCnt++;
            
            //            LOGGER.log(Level.WARNING, "Storing " + runCnt + " as runCnt for " + b.getProject() + " on " + currentNode.getNodeName());
            refreshLabels(b);
        }
    }

    @Override
    public void onFinalized(AbstractBuild r) {
        AbstractBuild<?,?> b = (AbstractBuild<?,?>)r;
        ThrottleJobProperty tjp = b.getProject().getProperty(ThrottleJobProperty.class);
        if (tjp!=null && tjp.getThrottleEnabled()) {
            
            if (runCnt > 0) {
                runCnt--;
            }
            
            //            LOGGER.log(Level.WARNING, "Storing " + runCnt + " as runCnt for " + b.getProject() + " on " + b.getBuiltOn().getNodeName());
            refreshLabels(b);
        }
    }

    private void refreshLabels(AbstractBuild<?,?> r) {
        
        ThrottleJobProperty tjp = r.getProject().getProperty(ThrottleJobProperty.class);

        if (tjp!=null && tjp.getThrottleEnabled()) {
            // Gotta reset the labels, annoyingly.
            // Ok, really annoyingly, this is the only way we can get trimLabels called easily.
            try {
                Hudson.getInstance().setNodes(Hudson.getInstance().getNodes());
            } catch (Exception e) {
                // Ignore it.
            }

            for (Node n : Hudson.getInstance().getNodes()) {
                n.getAssignedLabels();
            }
            Hudson.getInstance().getAssignedLabels();

            /*Label l = Hudson.getInstance().getLabel(tjp.getDynamicLabelToUse());
            LOGGER.log(Level.WARNING, "TRL: After assigning label: " + l.getName());
            LOGGER.log(Level.WARNING, "TRL: Idle executors: " + l.getIdleExecutors());
            for (Node n : l.getNodes()) {
                LOGGER.log(Level.WARNING, "TRL: Eligible node: " + n.getNodeName());
                }*/
        }
        
    }
    
    private static final Logger LOGGER = Logger.getLogger(ThrottleRunListener.class.getName());

}
