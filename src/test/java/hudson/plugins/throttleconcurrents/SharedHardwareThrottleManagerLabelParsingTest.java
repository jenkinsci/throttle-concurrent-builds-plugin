/*
 * MIT License
 * Copyright (c) 2013, Ericsson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package hudson.plugins.throttleconcurrents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Integration tests for {@link SharedHardwareThrottleManager#parseNodeLabels(hudson.model.Node)}
 * that require a running Jenkins instance.
 */
@WithJenkins
class SharedHardwareThrottleManagerLabelParsingTest {

    @Test
    void shouldParseMachineNameWithoutWeight(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info, "Should parse machine name without weight");
        assertEquals("ServerA", info.getMachineName());
        assertEquals(1, info.getWeight(), "Default weight should be 1");
    }

    @Test
    void shouldParseMachineNameWithValidWeight(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:3"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerA", info.getMachineName());
        assertEquals(3, info.getWeight());
    }

    @Test
    void shouldDefaultWeightTo1WhenNegative(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:-5"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerA", info.getMachineName());
        assertEquals(1, info.getWeight(), "Negative weight should default to 1");
    }

    @Test
    void shouldDefaultWeightTo1WhenZero(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:0"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerA", info.getMachineName());
        assertEquals(1, info.getWeight(), "Zero weight should default to 1");
    }

    @Test
    void shouldDefaultWeightTo1WhenNotANumber(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:abc"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerA", info.getMachineName());
        assertEquals(1, info.getWeight(), "Non-numeric weight should default to 1");
    }

    @Test
    void shouldHandleMachineNameWithColonButNoWeight(JenkinsRule r) throws Exception {
        // "ServerA:" -- colon at end, no weight after -- the colon is included in the machine name
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerA:", info.getMachineName(), "Trailing colon is part of the machine name");
        assertEquals(1, info.getWeight());
    }

    @Test
    void shouldHandleEmptyMachineNameBeforeColon(JenkinsRule r) throws Exception {
        // ":5" -- empty machine name before colon -- lastIndexOf(':') returns 0, which fails colonIdx > 0 check
        // so the entire ":5" is treated as the machine name
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=:5"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals(":5", info.getMachineName(), "Entire value treated as machine name when colon is at position 0");
        assertEquals(1, info.getWeight());
    }

    @Test
    void shouldHandleMachineNameWithSpacesInWeight(JenkinsRule r) throws Exception {
        // "ServerA: 3 " -- weight with surrounding spaces
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA: 3 "));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerA", info.getMachineName());
        assertEquals(3, info.getWeight(), "Weight with spaces should be trimmed");
    }

    @Test
    void shouldHandleMachineNameWithMultipleColons(JenkinsRule r) throws Exception {
        // "Server:A:3" -- multiple colons, last one separates weight
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=Server:A:3"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("Server:A", info.getMachineName());
        assertEquals(3, info.getWeight());
    }

    @Test
    void shouldReturnNullWhenNoMatchingLabel(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node = r.createOnlineSlave(new hudson.model.labels.LabelAtom("other-label"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNull(info, "No matching label should return null");
    }

    @Test
    void shouldReturnNullWhenNoLabels(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        hudson.slaves.DumbSlave node = r.createOnlineSlave();

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNull(info, "No labels should return null");
    }

    @Test
    void shouldUseCustomLabelKey(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        manager.setLabelKey("CUSTOM_HW");

        hudson.slaves.DumbSlave node = r.createOnlineSlave(new hudson.model.labels.LabelAtom("CUSTOM_HW=ServerB:4"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNotNull(info);
        assertEquals("ServerB", info.getMachineName());
        assertEquals(4, info.getWeight());
    }

    @Test
    void shouldIgnoreNonMatchingLabelKey(JenkinsRule r) throws Exception {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        manager.setLabelKey("CUSTOM_HW");

        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:2"));

        SharedHardwareThrottleManager.MachineInfo info = manager.parseNodeLabels(node);
        assertNull(info, "Non-matching label key should be ignored");
    }

    /* ------------------------------------------------------------------ */
    /*  Capacity resolution tests                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldUseConfiguredCapacityOverLabelDiscovered(JenkinsRule r) throws Exception {
        // Node declares both machine assignment and max capacity via labels
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerA:2"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerA:2 THROTTLE_SHARED_HARDWARE_MAX=ServerA:30");

        // Configured capacity is 20, label-discovered is 30 -- configured should win
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        java.util.List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new java.util.ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerA", 20));
        manager.setMachineCapacities(capacities);

        int maxCapacity = manager.getMaxCapacity("ServerA");
        assertEquals(20, maxCapacity, "Configured capacity should take precedence over label-discovered");
    }

    @Test
    void shouldUseLabelDiscoveredCapacityWhenNoConfiguration(JenkinsRule r) throws Exception {
        // Node declares both machine assignment and max capacity via labels
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerB:1"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerB:1 THROTTLE_SHARED_HARDWARE_MAX=ServerB:15");

        // No configured capacity -- label discovery should work
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();

        int maxCapacity = manager.getMaxCapacity("ServerB");
        assertEquals(15, maxCapacity, "Label-discovered capacity should be used when no configuration exists");
    }

    @Test
    void shouldReturnUnknownWhenNoCapacitySource(JenkinsRule r) throws Exception {
        // Node declares machine assignment but no max capacity
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerC:1"));

        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();

        int maxCapacity = manager.getMaxCapacity("ServerC");
        assertEquals(-1, maxCapacity, "Should return -1 when no capacity source is available");
    }

    @Test
    void shouldUseConfiguredCapacityEvenWhenLabelHasHigherValue(JenkinsRule r) throws Exception {
        // Node declares max capacity via labels as 50
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerD:3"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerD:3 THROTTLE_SHARED_HARDWARE_MAX=ServerD:50");

        // Configured capacity is 10 -- should still take precedence
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        java.util.List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new java.util.ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerD", 10));
        manager.setMachineCapacities(capacities);

        int maxCapacity = manager.getMaxCapacity("ServerD");
        assertEquals(10, maxCapacity, "Configured capacity should take precedence even when lower");
    }

    @Test
    void shouldUseLabelCapacityWhenConfiguredForDifferentMachine(JenkinsRule r) throws Exception {
        // Node declares machine assignment and max capacity for ServerE
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerE:1"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerE:1 THROTTLE_SHARED_HARDWARE_MAX=ServerE:25");

        // Configured capacity is for a different machine (ServerF)
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        java.util.List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new java.util.ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerF", 30));
        manager.setMachineCapacities(capacities);

        int maxCapacity = manager.getMaxCapacity("ServerE");
        assertEquals(
                25, maxCapacity, "Label-discovered capacity should be used when configured for a different machine");
    }

    @Test
    void shouldPickLowestMaxLabelWhenMultipleNodesDeclareIt(JenkinsRule r) throws Exception {
        // Two nodes on the same machine, declaring different max capacities
        hudson.slaves.DumbSlave node1 =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerF:1"));
        node1.setLabelString("THROTTLE_SHARED_HARDWARE=ServerF:1 THROTTLE_SHARED_HARDWARE_MAX=ServerF:20");

        hudson.slaves.DumbSlave node2 =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerF:2"));
        node2.setLabelString("THROTTLE_SHARED_HARDWARE=ServerF:2 THROTTLE_SHARED_HARDWARE_MAX=ServerF:10");

        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();

        int maxCapacity = manager.getMaxCapacity("ServerF");
        assertEquals(10, maxCapacity, "Should pick the lowest max capacity from label discovery");
    }

    @Test
    void shouldNotThrottleWhenConfiguredCapacityMatchesLabel(JenkinsRule r) throws Exception {
        // Node declares max capacity via labels as 20
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerG:1"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerG:1 THROTTLE_SHARED_HARDWARE_MAX=ServerG:20");

        // Configured capacity is also 20 -- no discrepancy
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        java.util.List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new java.util.ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerG", 20));
        manager.setMachineCapacities(capacities);

        int maxCapacity = manager.getMaxCapacity("ServerG");
        assertEquals(20, maxCapacity, "Configured capacity should be used when it matches label-discovered value");
    }

    /* ------------------------------------------------------------------ */
    /*  Insufficient capacity warning tests                                */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldBlockWhenCapacityLessThanNodeWeight(JenkinsRule r) throws Exception {
        // Node weight is 10, but capacity is only 1 -- node can never accept work
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerH:10"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerH:10 THROTTLE_SHARED_HARDWARE_MAX=ServerH:1");

        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();

        hudson.model.queue.CauseOfBlockage blockage = manager.checkMachineCapacity(node);
        assertNotNull(blockage, "Should block when capacity is less than node weight");
    }

    @Test
    void shouldBlockWhenConfiguredCapacityLessThanNodeWeight(JenkinsRule r) throws Exception {
        // Node weight is 100, configured capacity is 1
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerI:100"));

        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        java.util.List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new java.util.ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerI", 1));
        manager.setMachineCapacities(capacities);

        hudson.model.queue.CauseOfBlockage blockage = manager.checkMachineCapacity(node);
        assertNotNull(blockage, "Should block when configured capacity is less than node weight");
    }

    @Test
    void shouldAllowWhenCapacityEqualsNodeWeight(JenkinsRule r) throws Exception {
        // Node weight is 5, capacity is 5 -- should allow (at least 1 executor)
        hudson.slaves.DumbSlave node =
                r.createOnlineSlave(new hudson.model.labels.LabelAtom("THROTTLE_SHARED_HARDWARE=ServerJ:5"));
        node.setLabelString("THROTTLE_SHARED_HARDWARE=ServerJ:5 THROTTLE_SHARED_HARDWARE_MAX=ServerJ:5");

        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();

        hudson.model.queue.CauseOfBlockage blockage = manager.checkMachineCapacity(node);
        assertNull(blockage, "Should allow when capacity equals node weight and no executors are running");
    }
}
