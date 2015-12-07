package hudson.plugins.throttleconcurrents;

import hudson.Extension;
import hudson.model.Node;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;

@Extension
public class ComputerChangeListener extends ComputerListener {

    private static void ensureNodeProperty(Node node) {
        DescribableList<NodeProperty<?>, NodePropertyDescriptor> props = node.getNodeProperties();
        for (NodeProperty<?> p : props) {
            if (p instanceof ThrottleNodeProperty) {
                return;
            }
        }
        props.add(new ThrottleNodeProperty());
    }

    @Override
    public void onConfigurationChange() {
        Jenkins j = Jenkins.getActiveInstance();
        ensureNodeProperty(j);
        for (Node n : j.getNodes()) {
            ensureNodeProperty(n);
        }
    }
}
