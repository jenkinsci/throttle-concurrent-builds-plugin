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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * This class initiates the testing of {@link hudson.plugins.throttleconcurrents.ThrottleJobProperty.ThrottleCategory}.<br>
 * -Hence this class also testing {@link hudson.plugins.throttleconcurrents.ThrottleJobProperty.NodeLabeledPair}.<br>
 * -Test methods for {@link hudson.plugins.throttleconcurrents.ThrottleJobProperty.ThrottleCategory#getNodeLabeledPairs()}.
 * @author marco.miller@ericsson.com
 */
class ThrottleCategoryTest {
    private static final String testCategoryName = "aCategory";

    @Test
    void shouldGetEmptyNodeLabeledPairsListUponInitialNull() {
        ThrottleJobProperty.ThrottleCategory category =
                new ThrottleJobProperty.ThrottleCategory(testCategoryName, 0, 0, null);
        assertTrue(category.getNodeLabeledPairs().isEmpty(), "nodeLabeledPairs shall be empty");
    }

    @Test
    void shouldGetNonEmptyNodeLabeledPairsListThatWasSet() {
        String expectedLabel = "aLabel";
        Integer expectedMax = 1;

        ThrottleJobProperty.ThrottleCategory category =
                new ThrottleJobProperty.ThrottleCategory(testCategoryName, 0, 0, null);
        List<ThrottleJobProperty.NodeLabeledPair> nodeLabeledPairs = category.getNodeLabeledPairs();
        nodeLabeledPairs.add(new ThrottleJobProperty.NodeLabeledPair(expectedLabel, expectedMax));

        String actualLabel = category.getNodeLabeledPairs().get(0).getThrottledNodeLabel();
        Integer actualMax = category.getNodeLabeledPairs().get(0).getMaxConcurrentPerNodeLabeled();

        assertEquals(
                expectedLabel,
                actualLabel,
                "throttledNodeLabel " + actualLabel + " does not match expected " + expectedLabel);
        assertEquals(
                expectedMax,
                actualMax,
                "maxConcurrentPerNodeLabeled " + actualMax + " does not match expected " + expectedMax);
    }
}
