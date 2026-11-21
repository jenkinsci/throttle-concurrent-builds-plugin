package hudson.plugins.throttleconcurrents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Manages shared hardware throttling: groups agents by physical machine labels,
 * assigns weights to each executor, and enforces per-machine capacity limits.
 *
 * <p>Node labels like {@code THROTTLE_SHARED_HARDWARE=ServerA:2} declare that an agent
 * runs on {@code ServerA} and each of its executors costs {@code 2} weight points.
 * The machine's total capacity (e.g., {@code 20}) is configured globally or discovered
 * from node labels like {@code THROTTLE_SHARED_HARDWARE_MAX=ServerA:20}.</p>
 *
 * <p>External plugins can extend capacity resolution by implementing {@link MachineCapacityProvider}
 * and registering it as a Jenkins {@link hudson.Extension}. This allows cloud plugins or
 * other infrastructure plugins to provide dynamic capacity limits.</p>
 */
public final class SharedHardwareThrottleManager {

    private static final Logger LOGGER = Logger.getLogger(SharedHardwareThrottleManager.class.getName());

    /** Default label key for shared hardware declarations. */
    public static final String DEFAULT_LABEL_KEY = "THROTTLE_SHARED_HARDWARE";

    /** Default label key for shared hardware max capacity declarations. */
    public static final String DEFAULT_MAX_LABEL_KEY = "THROTTLE_SHARED_HARDWARE_MAX";

    /** The label key prefix used to find machine assignment labels on nodes. */
    @NonNull
    private String labelKey;

    /** The label key prefix used to find machine max capacity labels on nodes. */
    @NonNull
    private String maxLabelKey;

    /** Map from machine name to configured max weight capacity. */
    @NonNull
    private List<MachineCapacityPair> machineCapacities;

    public SharedHardwareThrottleManager() {
        this.labelKey = DEFAULT_LABEL_KEY;
        this.maxLabelKey = DEFAULT_MAX_LABEL_KEY;
        this.machineCapacities = new ArrayList<>();
    }

    @DataBoundConstructor
    public SharedHardwareThrottleManager(
            @NonNull String labelKey,
            @NonNull String maxLabelKey,
            @NonNull List<MachineCapacityPair> machineCapacities) {
        this.labelKey = labelKey != null && !labelKey.isEmpty() ? labelKey : DEFAULT_LABEL_KEY;
        this.maxLabelKey = maxLabelKey != null && !maxLabelKey.isEmpty() ? maxLabelKey : DEFAULT_MAX_LABEL_KEY;
        this.machineCapacities = machineCapacities != null ? new ArrayList<>(machineCapacities) : new ArrayList<>();
    }

    @NonNull
    public String getLabelKey() {
        return labelKey;
    }

    @DataBoundSetter
    public void setLabelKey(@NonNull String labelKey) {
        this.labelKey = labelKey != null && !labelKey.isEmpty() ? labelKey : DEFAULT_LABEL_KEY;
    }

    @NonNull
    public String getMaxLabelKey() {
        return maxLabelKey;
    }

    @DataBoundSetter
    public void setMaxLabelKey(@NonNull String maxLabelKey) {
        this.maxLabelKey = maxLabelKey != null && !maxLabelKey.isEmpty() ? maxLabelKey : DEFAULT_MAX_LABEL_KEY;
    }

    @NonNull
    public List<MachineCapacityPair> getMachineCapacities() {
        return machineCapacities;
    }

    @DataBoundSetter
    public void setMachineCapacities(@NonNull List<MachineCapacityPair> machineCapacities) {
        this.machineCapacities = machineCapacities != null ? new ArrayList<>(machineCapacities) : new ArrayList<>();
    }

    /**
     * Parse a node's labels to extract the machine name and weight.
     *
     * @param node the node to inspect
     * @return a parsed result, or null if no matching label found
     */
    @CheckForNull
    public MachineInfo parseNodeLabels(@NonNull Node node) {
        Set<LabelAtom> labels = node.getAssignedLabels();
        if (labels == null) {
            return null;
        }

        String machineName = null;
        int weight = 1;

        for (LabelAtom label : labels) {
            String labelName = label.getDisplayName();
            if (labelName.startsWith(labelKey + "=")) {
                String value = labelName.substring(labelKey.length() + 1);
                MachineInfo parsed = parseMachineValue(value);
                if (parsed != null) {
                    if (machineName == null) {
                        machineName = parsed.machineName;
                        weight = parsed.weight;
                    }
                }
            }
        }

        if (machineName == null) {
            return null;
        }

        return new MachineInfo(machineName, weight);
    }

    /**
     * Parse a label value like "ServerA:2" into machine name and weight.
     *
     * @param value the label value part after the key=
     * @return parsed info or null if invalid
     */
    @CheckForNull
    private MachineInfo parseMachineValue(@NonNull String value) {
        if (value.isEmpty()) {
            return null;
        }

        String machineName;
        int weight = 1;

        int colonIdx = value.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < value.length() - 1) {
            machineName = value.substring(0, colonIdx);
            String weightStr = value.substring(colonIdx + 1).trim();
            try {
                weight = Integer.parseInt(weightStr);
                if (weight < 1) {
                    LOGGER.log(
                            Level.FINE,
                            "Shared hardware weight {0} is less than 1, defaulting to 1 for machine {1}",
                            new Object[] {weightStr, machineName});
                    weight = 1;
                }
            } catch (NumberFormatException e) {
                LOGGER.log(
                        Level.FINE,
                        "Shared hardware weight \"{0}\" is not a valid number, defaulting to 1 for machine {1}",
                        new Object[] {weightStr, machineName});
                weight = 1;
            }
        } else {
            machineName = value;
        }

        if (machineName.isEmpty()) {
            return null;
        }

        return new MachineInfo(machineName, weight);
    }

    /**
     * Discover the max capacity for a machine from node labels.
     * Looks for labels like {@code THROTTLE_SHARED_HARDWARE_MAX=ServerA:20} on all nodes
     * that also declare their cost for the same machine.
     *
     * @param machineName the machine name to look up
     * @return the discovered max capacity, or -1 if not found
     */
    private int discoverMaxCapacityFromNodes(@NonNull String machineName) {
        int maxCapacity = -1;

        Jenkins jenkins = Jenkins.get();
        for (Node node : jenkins.getNodes()) {
            // First check if this node declares its own cost for the same machine
            MachineInfo nodeInfo = parseNodeLabels(node);
            if (nodeInfo == null || !nodeInfo.machineName.equals(machineName)) {
                continue;
            }

            // This node is on the same machine; now look for MAX labels
            Set<LabelAtom> labels = node.getAssignedLabels();
            if (labels == null) {
                continue;
            }

            for (LabelAtom label : labels) {
                String labelName = label.getDisplayName();
                if (labelName.startsWith(maxLabelKey + "=")) {
                    String value = labelName.substring(maxLabelKey.length() + 1);
                    MachineInfo parsed = parseMachineValue(value);
                    if (parsed != null && parsed.machineName.equals(machineName)) {
                        if (maxCapacity < 0 || parsed.weight < maxCapacity) {
                            maxCapacity = parsed.weight;
                            LOGGER.log(
                                    Level.FINE,
                                    "Discovered max capacity {0} for machine {1} from node {2}",
                                    new Object[] {parsed.weight, machineName, node.getDisplayName()});
                        }
                    }
                }
            }
        }

        return maxCapacity;
    }

    /**
     * Get the max weight capacity for a machine.
     * Resolution order:
     * 1. External {@link MachineCapacityProvider} extensions (highest priority, lowest value wins)
     * 2. Configured {@link #machineCapacities} in global config (takes precedence over label discovery)
     * 3. Discovered from node labels (MAX labels) -- used only if no configured capacity exists
     *
     * <p>When a configured capacity exists and differs from a label-discovered capacity,
     * the configured value is trusted and the discrepancy is logged.</p>
     *
     * @param machineName the machine name
     * @return the max capacity, or -1 if unknown (throttle should not apply)
     */
    public int getMaxCapacity(@NonNull String machineName) {
        int maxCapacity = -1;
        boolean hasConfiguredCapacity = false;

        // 1. Check external providers first (lowest value wins among providers)
        List<MachineCapacityProvider> providers = ExtensionList.lookup(MachineCapacityProvider.class);
        for (MachineCapacityProvider provider : providers) {
            int providerCapacity = provider.getMaxCapacity(machineName);
            if (providerCapacity >= 0) {
                if (maxCapacity < 0 || providerCapacity < maxCapacity) {
                    maxCapacity = providerCapacity;
                    LOGGER.log(Level.FINE, "External provider {0} returned capacity {1} for machine {2}", new Object[] {
                        provider.getClass().getName(), providerCapacity, machineName
                    });
                }
            }
        }

        // 2. Check configured machine capacities (takes precedence over label discovery)
        for (MachineCapacityPair pair : machineCapacities) {
            if (pair.getMachineName() != null && pair.getMachineName().equals(machineName)) {
                int configured = pair.getMaxWeight();
                hasConfiguredCapacity = true;
                if (maxCapacity < 0 || configured < maxCapacity) {
                    maxCapacity = configured;
                }
            }
        }

        // 3. Discover from node labels -- only if no configured capacity exists for this machine
        int discoveredCapacity = -1;
        if (!hasConfiguredCapacity) {
            discoveredCapacity = discoverMaxCapacityFromNodes(machineName);
            if (discoveredCapacity >= 0 && (maxCapacity < 0 || discoveredCapacity < maxCapacity)) {
                maxCapacity = discoveredCapacity;
            }
        } else {
            // Configured capacity exists -- still discover from labels to log discrepancies
            discoveredCapacity = discoverMaxCapacityFromNodes(machineName);
            if (discoveredCapacity >= 0 && discoveredCapacity != maxCapacity) {
                LOGGER.log(
                        Level.FINE,
                        "Machine {0}: configured capacity {1} differs from label-discovered capacity {2}; "
                                + "using configured value",
                        new Object[] {machineName, maxCapacity, discoveredCapacity});
            }
        }

        // Ensure at least 1 if we found a capacity
        if (maxCapacity > 0) {
            return maxCapacity;
        }
        return maxCapacity;
    }

    /**
     * Calculate the total weight currently used on a machine across all its nodes.
     *
     * @param machineName the machine name
     * @return the total weight of currently running executors on that machine
     */
    public int getCurrentWeightUsed(@NonNull String machineName) {
        Jenkins jenkins = Jenkins.get();
        int totalWeight = 0;

        for (Node node : jenkins.getNodes()) {
            MachineInfo info = parseNodeLabels(node);
            if (info == null || !info.machineName.equals(machineName)) {
                continue;
            }

            // Count running executors on this node
            int runningExecutors = 0;
            var computer = node.toComputer();
            if (computer != null) {
                for (var executor : computer.getAllExecutors()) {
                    if (executor.isBusy() && executor.getCurrentExecutable() != null) {
                        runningExecutors++;
                    }
                }
            }

            totalWeight += runningExecutors * info.weight;
        }

        return totalWeight;
    }

    /**
     * Check if a new work unit can be assigned to a node based on shared hardware limits.
     *
     * @param node the node being considered
     * @return a cause of blockage if the machine is at capacity, null otherwise
     */
    @CheckForNull
    public hudson.model.queue.CauseOfBlockage checkMachineCapacity(@NonNull Node node) {
        MachineInfo info = parseNodeLabels(node);
        if (info == null) {
            // No shared hardware label; throttle does not apply
            return null;
        }

        int maxCapacity = getMaxCapacity(info.machineName);
        if (maxCapacity < 0) {
            // Unknown machine capacity; do not throttle
            LOGGER.log(
                    Level.FINE,
                    "No max capacity configured for machine {0} on node {1}, skipping shared hardware throttle",
                    new Object[] {info.machineName, node.getDisplayName()});
            return null;
        }

        // Warn if capacity is insufficient for this node's weight -- node can never accept work
        if (maxCapacity < info.weight) {
            LOGGER.log(
                    Level.WARNING,
                    "Machine {0}: configured capacity {1} is less than node {2} weight {3}; "
                            + "this node can never accept work",
                    new Object[] {info.machineName, maxCapacity, node.getDisplayName(), info.weight});
        }

        // Warn if capacity is insufficient for any other node on the same machine
        warnInsufficientCapacityForNodes(info.machineName, maxCapacity);

        int currentWeight = getCurrentWeightUsed(info.machineName);
        int remaining = maxCapacity - currentWeight;

        if (remaining < info.weight) {
            LOGGER.log(Level.FINE, "Machine {0} at capacity: {1}/{2} weight used, need {3} more", new Object[] {
                info.machineName, currentWeight, maxCapacity, info.weight
            });
            return hudson.model.queue.CauseOfBlockage.fromMessage(
                    Messages._ThrottleQueueTaskDispatcher_SharedHardwareCapacity(
                            info.machineName, currentWeight, maxCapacity));
        }

        return null;
    }

    /**
     * Check all nodes on a machine and warn if any node's weight exceeds the machine's capacity.
     * Logs at WARNING level so administrators are aware that some nodes can never accept work.
     *
     * @param machineName the machine name
     * @param maxCapacity the resolved max capacity for the machine
     */
    private void warnInsufficientCapacityForNodes(@NonNull String machineName, int maxCapacity) {
        Jenkins jenkins = Jenkins.get();
        for (Node node : jenkins.getNodes()) {
            MachineInfo info = parseNodeLabels(node);
            if (info == null || !info.machineName.equals(machineName)) {
                continue;
            }
            if (info.weight > maxCapacity) {
                LOGGER.log(
                        Level.WARNING,
                        "Machine {0}: configured capacity {1} is less than node {2} weight {3}; "
                                + "this node can never accept work",
                        new Object[] {machineName, maxCapacity, node.getDisplayName(), info.weight});
            }
        }
    }

    /**
     * Holds the parsed machine name and weight from a node label.
     */
    public static final class MachineInfo {
        @NonNull
        final String machineName;

        final int weight;

        MachineInfo(@NonNull String machineName, int weight) {
            this.machineName = machineName;
            this.weight = weight;
        }

        @NonNull
        public String getMachineName() {
            return machineName;
        }

        public int getWeight() {
            return weight;
        }
    }

    /**
     * Pair of machine name and its max weight capacity, for global configuration.
     */
    public static final class MachineCapacityPair extends AbstractDescribableImpl<MachineCapacityPair> {
        private String machineName;
        private Integer maxWeight;

        @DataBoundConstructor
        public MachineCapacityPair(String machineName, Integer maxWeight) {
            this.machineName = machineName;
            this.maxWeight = maxWeight;
        }

        public String getMachineName() {
            return machineName;
        }

        public Integer getMaxWeight() {
            if (maxWeight == null) {
                return 1;
            }
            return maxWeight;
        }

        @hudson.Extension
        public static class DescriptorImpl extends Descriptor<MachineCapacityPair> {
            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }

    /**
     * Extension point for external plugins to provide machine capacity limits.
     *
     * <p>Plugins that manage cloud infrastructure or other dynamic resources can implement
     * this interface and register it as a {@link hudson.Extension} to automatically provide
     * capacity limits for machines they manage. For example, a cloud plugin could query
     * the cloud provider API to determine how many concurrent builds a physical host can handle.</p>
     *
     * <p>When multiple providers return a capacity for the same machine, the lowest value wins.</p>
     *
     * <p>Example implementation:</p>
     * <pre>{@code
     * @Extension
     * public class MyCloudCapacityProvider implements MachineCapacityProvider {
     *     @Override
     *     public int getMaxCapacity(String machineName) {
     *         // Query cloud API for machine capacity
     *         if ("ServerA".equals(machineName)) {
     *             return 20;
     *         }
     *         return -1; // Unknown machine
     *     }
     * }
     * }</pre>
     */
    public interface MachineCapacityProvider {
        /**
         * Get the max weight capacity for a machine.
         *
         * @param machineName the machine name
         * @return the max capacity, or -1 if this provider doesn't manage this machine
         */
        int getMaxCapacity(@NonNull String machineName);
    }
}
