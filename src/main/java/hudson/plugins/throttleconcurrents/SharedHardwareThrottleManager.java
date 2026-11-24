package hudson.plugins.throttleconcurrents;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
public final class SharedHardwareThrottleManager extends AbstractDescribableImpl<SharedHardwareThrottleManager> {

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

    /**
     * Fallback capacity factor applied when no other capacity is known for a machine.
     * The effective capacity is computed as: heaviest agent weight on the machine × this factor.
     * A value of 0 means the fallback is disabled (default).
     * A value of 1 or greater enables the fallback.
     */
    private int fallbackCapacityFactor;

    /**
     * Cache for capacity lookup results to avoid redundant computation and log spam.
     * Maps machine name to the last known max capacity (-1 if unknown).
     * The cache is invalidated when configuration changes.
     * <p>Declared non-final because XStream deserialization (used by Jenkins config persistence)
     * skips final field initializers, leaving them null. These are lazily initialized on first use.</p>
     */
    private Map<String, Integer> capacityCache;

    /**
     * Tracks the last time we logged "no max capacity" for each machine, to rate-limit
     * the log messages. Jenkins calls canTake() hundreds of times per second.
     */
    private Map<String, Long> lastNoCapacityLogTime;

    /** Minimum interval (in milliseconds) between "no max capacity" log messages for the same machine. */
    private static final long NO_CAPACITY_LOG_INTERVAL_MS = 30_000;

    /**
     * TTL (in milliseconds) for the capacity cache. After this duration, the cached value
     * is considered stale and recomputed. This balances performance with responsiveness
     * to node label changes.
     */
    private static final long CAPACITY_CACHE_TTL_MS = 10_000;

    /** Tracks the last time each machine's capacity was cached. */
    private Map<String, Long> capacityCacheTime;

    /** Lazily initialize all cache maps. Safe to call multiple times. */
    private void ensureCacheInitialized() {
        if (capacityCache == null) {
            capacityCache = new ConcurrentHashMap<>();
        }
        if (lastNoCapacityLogTime == null) {
            lastNoCapacityLogTime = new ConcurrentHashMap<>();
        }
        if (capacityCacheTime == null) {
            capacityCacheTime = new ConcurrentHashMap<>();
        }
    }

    public SharedHardwareThrottleManager() {
        this.labelKey = DEFAULT_LABEL_KEY;
        this.maxLabelKey = DEFAULT_MAX_LABEL_KEY;
        this.machineCapacities = new ArrayList<>();
        this.fallbackCapacityFactor = 0;
    }

    @DataBoundConstructor
    public SharedHardwareThrottleManager(
            @NonNull String labelKey,
            @NonNull String maxLabelKey,
            @NonNull List<MachineCapacityPair> machineCapacities,
            int fallbackCapacityFactor) {
        this.labelKey = labelKey != null && !labelKey.isEmpty() ? labelKey : DEFAULT_LABEL_KEY;
        this.maxLabelKey = maxLabelKey != null && !maxLabelKey.isEmpty() ? maxLabelKey : DEFAULT_MAX_LABEL_KEY;
        this.machineCapacities = machineCapacities != null ? new ArrayList<>(machineCapacities) : new ArrayList<>();
        this.fallbackCapacityFactor = fallbackCapacityFactor >= 0 ? fallbackCapacityFactor : 0;
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
        // Invalidate cache when configuration changes
        ensureCacheInitialized();
        capacityCache.clear();
        capacityCacheTime.clear();
        lastNoCapacityLogTime.clear();
    }

    public int getFallbackCapacityFactor() {
        return fallbackCapacityFactor;
    }

    /**
     * Check the configuration values for consistency.
     * Empty label keys are treated as the defaults, and machine names are compared after trimming.
     *
     * @param labelKey the node label key for machine assignment
     * @param maxLabelKey the node label key for machine max capacity
     * @param machineCapacities the configured machine capacity pairs, may be null
     * @return a human-readable description of the first problem found, or null if the values are consistent
     */
    @CheckForNull
    public static String validateConfiguration(
            @CheckForNull String labelKey,
            @CheckForNull String maxLabelKey,
            @CheckForNull List<MachineCapacityPair> machineCapacities) {
        String labelProblem = validateLabelKeys(labelKey, maxLabelKey);
        if (labelProblem != null) {
            return labelProblem;
        }
        if (machineCapacities != null) {
            Set<String> seen = new HashSet<>();
            for (MachineCapacityPair pair : machineCapacities) {
                if (pair == null || pair.getMachineName() == null) {
                    continue;
                }
                String name = pair.getMachineName().trim();
                if (!name.isEmpty() && !seen.add(name)) {
                    return "Machine name \"" + name + "\" is listed more than once in the machine capacities; "
                            + "machine names must be unique.";
                }
            }
        }
        return null;
    }

    /**
     * Check that the machine assignment label key and the max capacity label key differ.
     *
     * @param labelKey the node label key for machine assignment, empty means the default
     * @param maxLabelKey the node label key for machine max capacity, empty means the default
     * @return a description of the problem, or null if the keys differ
     */
    @CheckForNull
    public static String validateLabelKeys(@CheckForNull String labelKey, @CheckForNull String maxLabelKey) {
        String assignment = labelKey == null || labelKey.trim().isEmpty() ? DEFAULT_LABEL_KEY : labelKey.trim();
        String max = maxLabelKey == null || maxLabelKey.trim().isEmpty() ? DEFAULT_MAX_LABEL_KEY : maxLabelKey.trim();
        if (assignment.equals(max)) {
            return "The node label key for machine assignment and the node label key for machine max capacity "
                    + "must differ (both are \"" + assignment + "\").";
        }
        return null;
    }

    @Override
    public String toString() {
        return "SharedHardwareThrottleManager{labelKey=" + labelKey + ", maxLabelKey=" + maxLabelKey
                + ", machineCapacities=" + machineCapacities + ", fallbackCapacityFactor=" + fallbackCapacityFactor
                + "}";
    }

    @DataBoundSetter
    public void setFallbackCapacityFactor(int fallbackCapacityFactor) {
        this.fallbackCapacityFactor = fallbackCapacityFactor;
        // Invalidate cache when configuration changes
        ensureCacheInitialized();
        capacityCache.clear();
        capacityCacheTime.clear();
        lastNoCapacityLogTime.clear();
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
     * <p>If multiple nodes declare different MAX values for the same machine, the lowest value
     * is honored and a WARNING is logged (rate-limited) to alert the administrator of the conflict.</p>
     *
     * @param machineName the machine name to look up
     * @return the discovered max capacity, or -1 if not found
     */
    private int discoverMaxCapacityFromNodes(@NonNull String machineName) {
        int maxCapacity = -1;
        // Track all discovered values to detect conflicts
        java.util.List<String> conflictDetails = new java.util.ArrayList<>();

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
                        conflictDetails.add(parsed.weight + " (from " + node.getDisplayName() + ")");
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

        // Log warning if multiple nodes declare different MAX values for the same machine
        if (conflictDetails.size() > 1) {
            // Check if all values are the same
            boolean allSame = conflictDetails.stream().distinct().count() == 1;
            if (!allSame) {
                // Rate-limit this warning to once per machine per 30 seconds
                ensureCacheInitialized();
                String conflictKey = "conflict:" + machineName;
                Long lastLogged = lastNoCapacityLogTime.get(conflictKey);
                if (lastLogged == null || (System.currentTimeMillis() - lastLogged) >= NO_CAPACITY_LOG_INTERVAL_MS) {
                    lastNoCapacityLogTime.put(conflictKey, System.currentTimeMillis());
                    LOGGER.log(
                            Level.WARNING,
                            "Machine {0}: conflicting max capacity values declared by nodes on the same machine: {1}. "
                                    + "Using the lowest value: {2}.",
                            new Object[] {machineName, conflictDetails, maxCapacity});
                }
            }
        }

        return maxCapacity;
    }

    /**
     * Find the heaviest agent weight on a machine.
     * Looks at all nodes that declare themselves on the given machine and finds the
     * maximum weight among them. This is used as a floor for label-discovered capacities
     * to ensure agents are never blocked by an unrealistically low auto-discovered capacity.
     *
     * @param machineName the machine name
     * @return the heaviest agent weight, or -1 if no agents found on that machine
     */
    private int getHeaviestAgentWeight(@NonNull String machineName) {
        int heaviest = -1;

        Jenkins jenkins = Jenkins.get();
        for (Node node : jenkins.getNodes()) {
            MachineInfo info = parseNodeLabels(node);
            if (info == null || !info.machineName.equals(machineName)) {
                continue;
            }
            if (info.weight > heaviest) {
                heaviest = info.weight;
            }
        }

        return heaviest;
    }

    /**
     * Get the max weight capacity for a machine.
     * Resolution order:
     * 1. External {@link MachineCapacityProvider} extensions (highest priority, lowest value wins)
     * 2. Configured {@link #machineCapacities} in global config (takes precedence over label discovery)
     * 3. Discovered from node labels (MAX labels) -- used only if no configured capacity exists
     * 4. Fallback capacity factor -- used only if no other capacity is found and factor is configured
     *
     * <p>When a configured capacity exists and differs from a label-discovered capacity,
     * the configured value is trusted and the discrepancy is logged.</p>
     *
     * <p>For label-discovered capacities (not configured), the effective minimum is raised to
     * at least the weight of the heaviest known agent on that machine, so agents are never
     * blocked by an unrealistically low auto-discovered capacity. Configured capacities are
     * enforced strictly without this floor adjustment.</p>
     *
     * <p>The fallback capacity factor (step 4) computes capacity as: heaviest agent weight × factor.
     * A factor of 0 means the fallback is disabled (default). A factor of 1 or greater enables it.</p>
     *
     * <p>The result is cached for a short TTL to avoid redundant node iteration on every call,
     * as this method is called hundreds of times per second by the Jenkins queue dispatcher.</p>
     *
     * @param machineName the machine name
     * @return the max capacity, or -1 if unknown (throttle should not apply)
     */
    public int getMaxCapacity(@NonNull String machineName) {
        ensureCacheInitialized();
        // Check cache first
        Long cachedTime = capacityCacheTime.get(machineName);
        if (cachedTime != null && (System.currentTimeMillis() - cachedTime) < CAPACITY_CACHE_TTL_MS) {
            Integer cached = capacityCache.get(machineName);
            if (cached != null) {
                return cached;
            }
        }

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
        if (machineCapacities != null) {
            for (MachineCapacityPair pair : machineCapacities) {
                if (pair.getMachineName() != null && pair.getMachineName().equals(machineName)) {
                    int configured = pair.getMaxWeight();
                    hasConfiguredCapacity = true;
                    if (maxCapacity < 0 || configured < maxCapacity) {
                        maxCapacity = configured;
                    }
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

        // For label-discovered capacities (no configured capacity), ensure the effective minimum
        // is at least the weight of the heaviest known agent on this machine.
        // Configured capacities are enforced strictly without this floor adjustment.
        if (!hasConfiguredCapacity && maxCapacity >= 0) {
            int heaviest = getHeaviestAgentWeight(machineName);
            if (heaviest > maxCapacity) {
                LOGGER.log(
                        Level.FINE,
                        "Machine {0}: label-discovered capacity {1} is less than heaviest agent weight {2}; "
                                + "raising to {2}",
                        new Object[] {machineName, maxCapacity, heaviest});
                maxCapacity = heaviest;
            }
        }

        // 4. Fallback capacity factor -- used only if no other capacity is found
        if (maxCapacity < 0 && fallbackCapacityFactor >= 1) {
            int heaviest = getHeaviestAgentWeight(machineName);
            if (heaviest >= 1) {
                maxCapacity = heaviest * fallbackCapacityFactor;
                LOGGER.log(
                        Level.FINE,
                        "Machine {0}: no capacity configured; applying fallback factor {1} to heaviest "
                                + "agent weight {2} = {3}",
                        new Object[] {machineName, fallbackCapacityFactor, heaviest, maxCapacity});
            }
        }

        // Ensure at least 1 if we found a capacity
        if (maxCapacity > 0) {
            capacityCache.put(machineName, maxCapacity);
            capacityCacheTime.put(machineName, System.currentTimeMillis());
            return maxCapacity;
        }
        // Cache the -1 result too, so we don't re-scan nodes on every call
        capacityCache.put(machineName, -1);
        capacityCacheTime.put(machineName, System.currentTimeMillis());
        return -1;
    }

    /**
     * Calculate the total weight currently used on a machine across all its nodes.
     *
     * @param machineName the machine name
     * @return the total weight of busy executors on that machine, including assigned work units
     */
    public int getCurrentWeightUsed(@NonNull String machineName) {
        Jenkins jenkins = Jenkins.get();
        int totalWeight = 0;

        for (Node node : jenkins.getNodes()) {
            MachineInfo info = parseNodeLabels(node);
            if (info == null || !info.machineName.equals(machineName)) {
                continue;
            }

            // Count busy executors, including work units assigned by the queue but not yet started.
            var computer = node.toComputer();
            if (computer != null) {
                totalWeight += countBusyExecutors(computer.getAllExecutors()) * info.weight;
            }
        }

        return totalWeight;
    }

    static int countBusyExecutors(Iterable<? extends Executor> executors) {
        int count = 0;
        for (Executor executor : executors) {
            if (executor.isBusy()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if a new work unit can be assigned to a node based on shared hardware limits.
     *
     * <p>This method is called frequently by the Jenkins queue dispatcher (hundreds of times
     * per second when there is work in the queue). To avoid log spam, the "no max capacity"
     * message is rate-limited to once per machine per 30 seconds, and capacity lookup results
     * are cached.</p>
     *
     * @param node the node being considered
     * @return a cause of blockage if the machine is at capacity, null otherwise
     */
    @CheckForNull
    public hudson.model.queue.CauseOfBlockage checkMachineCapacity(@NonNull Node node) {
        return checkMachineCapacity(node, null);
    }

    /**
     * Check if a new work unit can be assigned to a node based on shared hardware limits.
     *
     * @param node the node being considered
     * @param task the queued task being considered, if available
     * @return a cause of blockage if the machine is at capacity, null otherwise
     */
    @CheckForNull
    public hudson.model.queue.CauseOfBlockage checkMachineCapacity(@NonNull Node node, @CheckForNull Queue.Task task) {
        MachineInfo info = parseNodeLabels(node);
        if (info == null) {
            // No shared hardware label; throttle does not apply
            return null;
        }

        if (task != null && LOGGER.isLoggable(Level.FINEST)) {
            String requestedLabel = task.getAssignedLabel() == null
                    ? "<any agent>"
                    : task.getAssignedLabel().getExpression();
            String buildNameAndNumber = "unavailable";
            Queue.Executable ownerExecutable = task.getOwnerExecutable();
            if (ownerExecutable instanceof Run<?, ?> run) {
                buildNameAndNumber = run.getFullDisplayName();
            } else if (task instanceof Job<?, ?> job) {
                buildNameAndNumber = job.getFullDisplayName() + " #" + job.getNextBuildNumber();
            }
            LOGGER.log(
                    Level.FINEST,
                    "Shared hardware request for task {0}, build {1}, requested label expression {2}; "
                            + "candidate agent {3} (machine {4}, requested weight {5})",
                    new Object[] {
                        task.getFullDisplayName(),
                        buildNameAndNumber,
                        requestedLabel,
                        node.getDisplayName(),
                        info.machineName,
                        info.weight
                    });
        }

        int maxCapacity = getMaxCapacity(info.machineName);
        if (maxCapacity < 0) {
            // Unknown machine capacity; do not throttle
            // Rate-limit this log message to avoid spam when Jenkins calls canTake() hundreds of times/sec
            long now = System.currentTimeMillis();
            Long lastLogged = lastNoCapacityLogTime.get(info.machineName);
            if (lastLogged == null || (now - lastLogged) >= NO_CAPACITY_LOG_INTERVAL_MS) {
                lastNoCapacityLogTime.put(info.machineName, now);
                LOGGER.log(
                        Level.FINE,
                        "No max capacity configured for machine {0} on node {1}, skipping shared hardware throttle",
                        new Object[] {info.machineName, node.getDisplayName()});
            }
            return null;
        }

        int currentWeight = getCurrentWeightUsed(info.machineName);
        int remaining = maxCapacity - currentWeight;

        LOGGER.log(
                Level.FINE,
                "Machine {0} on agent {1}: capacity {2}, current weight {3}, remaining {4}, node weight {5}",
                new Object[] {
                    info.machineName, node.getDisplayName(), maxCapacity, currentWeight, remaining, info.weight
                });

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

        if (remaining < info.weight) {
            LOGGER.log(
                    Level.FINE,
                    "Machine {0} at capacity on agent {1}: {2}/{3} weight used, need {4} more -- BLOCKING",
                    new Object[] {info.machineName, node.getDisplayName(), currentWeight, maxCapacity, info.weight});
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

        @Override
        public String toString() {
            return machineName + "=" + getMaxWeight();
        }

        @hudson.Extension
        public static class DescriptorImpl extends Descriptor<MachineCapacityPair> {
            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }

    @hudson.Extension
    public static class DescriptorImpl extends Descriptor<SharedHardwareThrottleManager> {
        @Override
        public String getDisplayName() {
            return "Shared Hardware Throttle";
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
