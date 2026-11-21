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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SharedHardwareThrottleManager} covering label parsing,
 * weight validation, capacity resolution, and configuration classes.
 */
class SharedHardwareThrottleManagerTest {

    /* ------------------------------------------------------------------ */
    /*  Constructor and getter/setter tests                                */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldInitializeWithDefaults() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();

        assertEquals("THROTTLE_SHARED_HARDWARE", manager.getLabelKey());
        assertEquals("THROTTLE_SHARED_HARDWARE_MAX", manager.getMaxLabelKey());
        assertNotNull(manager.getMachineCapacities());
        assertEquals(0, manager.getMachineCapacities().size());
    }

    @Test
    void shouldInitializeWithCustomValues() {
        List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerA", 20));

        SharedHardwareThrottleManager manager =
                new SharedHardwareThrottleManager("CUSTOM_KEY", "CUSTOM_MAX", capacities);

        assertEquals("CUSTOM_KEY", manager.getLabelKey());
        assertEquals("CUSTOM_MAX", manager.getMaxLabelKey());
        assertEquals(1, manager.getMachineCapacities().size());
    }

    @Test
    void shouldFallbackToDefaultsWhenNullValuesPassed() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager(null, null, null);

        assertEquals("THROTTLE_SHARED_HARDWARE", manager.getLabelKey());
        assertEquals("THROTTLE_SHARED_HARDWARE_MAX", manager.getMaxLabelKey());
        assertNotNull(manager.getMachineCapacities());
    }

    @Test
    void shouldFallbackToDefaultsWhenEmptyValuesPassed() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager("", "", null);

        assertEquals("THROTTLE_SHARED_HARDWARE", manager.getLabelKey());
        assertEquals("THROTTLE_SHARED_HARDWARE_MAX", manager.getMaxLabelKey());
    }

    @Test
    void shouldUpdateLabelKeyViaSetter() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        manager.setLabelKey("MY_LABEL_KEY");
        assertEquals("MY_LABEL_KEY", manager.getLabelKey());
    }

    @Test
    void shouldFallbackToDefaultWhenSettingNullLabelKey() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        manager.setLabelKey(null);
        assertEquals("THROTTLE_SHARED_HARDWARE", manager.getLabelKey());
    }

    @Test
    void shouldUpdateMaxLabelKeyViaSetter() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        manager.setMaxLabelKey("MY_MAX_KEY");
        assertEquals("MY_MAX_KEY", manager.getMaxLabelKey());
    }

    @Test
    void shouldUpdateMachineCapacitiesViaSetter() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        List<SharedHardwareThrottleManager.MachineCapacityPair> capacities = new ArrayList<>();
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerA", 10));
        capacities.add(new SharedHardwareThrottleManager.MachineCapacityPair("ServerB", 15));
        manager.setMachineCapacities(capacities);

        assertEquals(2, manager.getMachineCapacities().size());
    }

    @Test
    void shouldHandleNullMachineCapacitiesViaSetter() {
        SharedHardwareThrottleManager manager = new SharedHardwareThrottleManager();
        manager.setMachineCapacities(null);
        assertNotNull(manager.getMachineCapacities());
        assertEquals(0, manager.getMachineCapacities().size());
    }

    /* ------------------------------------------------------------------ */
    /*  MachineInfo tests                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldCreateMachineInfoWithCorrectValues() {
        SharedHardwareThrottleManager.MachineInfo info = new SharedHardwareThrottleManager.MachineInfo("ServerA", 5);

        assertEquals("ServerA", info.getMachineName());
        assertEquals(5, info.getWeight());
    }

    /* ------------------------------------------------------------------ */
    /*  MachineCapacityPair tests                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldCreateMachineCapacityPairWithCorrectValues() {
        SharedHardwareThrottleManager.MachineCapacityPair pair =
                new SharedHardwareThrottleManager.MachineCapacityPair("ServerA", 20);

        assertEquals("ServerA", pair.getMachineName());
        assertEquals(20, pair.getMaxWeight());
    }

    @Test
    void shouldDefaultMaxWeightTo1WhenNull() {
        SharedHardwareThrottleManager.MachineCapacityPair pair =
                new SharedHardwareThrottleManager.MachineCapacityPair("ServerA", null);

        assertEquals("ServerA", pair.getMachineName());
        assertEquals(1, pair.getMaxWeight());
    }

    @Test
    void shouldReturnConfiguredMaxWeight() {
        SharedHardwareThrottleManager.MachineCapacityPair pair =
                new SharedHardwareThrottleManager.MachineCapacityPair("ServerB", 50);

        assertEquals("ServerB", pair.getMachineName());
        assertEquals(50, pair.getMaxWeight());
    }

    /* ------------------------------------------------------------------ */
    /*  Default constants tests                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldHaveCorrectDefaultLabelKey() {
        assertEquals("THROTTLE_SHARED_HARDWARE", SharedHardwareThrottleManager.DEFAULT_LABEL_KEY);
    }

    @Test
    void shouldHaveCorrectDefaultMaxLabelKey() {
        assertEquals("THROTTLE_SHARED_HARDWARE_MAX", SharedHardwareThrottleManager.DEFAULT_MAX_LABEL_KEY);
    }

    /* ------------------------------------------------------------------ */
    /*  MachineCapacityPair Descriptor tests                               */
    /* ------------------------------------------------------------------ */

    @Test
    void shouldHaveDescriptorWithEmptyDisplayName() {
        SharedHardwareThrottleManager.MachineCapacityPair.DescriptorImpl descriptor =
                new SharedHardwareThrottleManager.MachineCapacityPair.DescriptorImpl();
        assertEquals("", descriptor.getDisplayName());
    }
}
