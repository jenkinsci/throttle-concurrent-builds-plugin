/*
 * The MIT License
 *
 * Copyright (C) 2009 Robert Collins
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Label;
import hudson.model.LabelFinder;
import hudson.model.Run;
import hudson.model.listeners.RunListener;
import hudson.util.RunList;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Obtain dynamic labels for Nodes based on whether the concurrent builds throttler
 * is enabled for a job on that node, checking whether there's already too many
 * builds running on the node and including the dynamic label for the jobs that
 * aren't already maxed out.
 */
@Extension
public class ThrottleLabelFinder extends LabelFinder {

    @Override
    public Set<Label> findLabels(Node node) {
        Hudson h = Hudson.getInstance();
        Set<Label> result = new HashSet<Label>();
        Set<Label> staticLabels = Label.parse(node.getLabelString());
        
        for (AbstractProject<?,?> p: h.getAllItems(AbstractProject.class)) {
            ThrottleJobProperty tjp = (ThrottleJobProperty)p.getProperty(ThrottleJobProperty.class);
            
            if (tjp!=null && tjp.getThrottleEnabled()) {
                //tjp.initNodeRunCnt();
                String baseLabel = tjp.getBaseLabel();
                int maxConcurrent = tjp.getMaxConcurrent().intValue();
                
                if (staticLabels.contains(h.getLabel(baseLabel))) {
                    Integer runCount = 0;

                    for (RunListener l: RunListener.all()) {
                        if (l instanceof ThrottleRunListener) {
                            runCount = ((ThrottleRunListener)l).getRunCnt();
                            //                            LOGGER.log(Level.WARNING, "Ah-ha, we got the ThrottleRunListener and found " + runCount + " as the run count.");
                        }
                    }
                        
                    //LOGGER.log(Level.WARNING, "runCount is " + runCount + " for job/node " + p + "/" + node.getNodeName());
                    /*
                    //                    RunList buildsOnNode = new RunList(p.getBuilds()).node(node);
                    //LOGGER.log(Level.WARNING,"RunList size: " + buildsOnNode.size());
                    
                    for (AbstractBuild<?,?> r: p.getBuilds()) {
                        Node oldNode = r.getBuiltOn();
                        String oldNodeName;
                        if (oldNode==null) oldNodeName = "dead node";
                        else oldNodeName = oldNode.getNodeName();
                        
                        LOGGER.log(Level.WARNING, "Checking against " + r + ", built on " + oldNodeName);
                        if (r.getBuiltOn()==node) {
                            // If the build has yet to get to State.COMPLETED - i.e., it hasn't yet
                            // called fireFinalized - we count it. Otherwise, we don't count it, since it'll
                            // be vanishing from the node momentarily anyway.
                            if (r.isBuilding()) {
                                LOGGER.log(Level.WARNING, "...and it is building...");
                                runCount++;
                            }
                        }
                        } */
                    
                    // If there are less running builds of this job on this node than the max concurrent
                    // per-node allowed for this job, add the dynamic label for this job to the result. 
                    if (runCount < maxConcurrent) {
                        String dynamicLabel = tjp.getDynamicLabelToUse();

                        result.add(h.getLabel(dynamicLabel));
                        //  LOGGER.log(Level.WARNING, runCount + " is less than " + maxConcurrent + " so adding " + dynamicLabel + " to labels for " + node.getNodeName());
                    }
                }
            }
        }

        //        LOGGER.log(Level.WARNING, "TLF: # of dyn labels for " + node.getNodeName() + ": " + result.size());
        return result;
    }

    private static final Logger LOGGER = Logger.getLogger(ThrottleLabelFinder.class.getName());

}
