package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavHeadingMemoryTest {
    private val memory = NavHeadingMemory(offset = 3.0, supportM = 0.8, atMs = 10_000, position = Point(5.0, 4.0), reportedHeading = 359.0)

    @Test fun `taken over while the robot stays where the previous drive stopped`() {
        // Coasting after the stop, published position up to 1 s late, heading noise across 0°.
        assertNull(memory.refusal(25_000, Point(5.12, 4.05), 2.0))
    }

    @Test fun `confirmed again when the robot moved, turned, or after a long wait`() {
        assertNotNull(memory.refusal(10_000 + NavHeadingMemory.MAX_AGE_MS + 1, Point(5.0, 4.0), 359.0))
        assertNotNull(memory.refusal(12_000, Point(5.4, 4.0), 359.0))
        assertNotNull(memory.refusal(12_000, Point(5.0, 4.0), 10.0))
        assertNotNull(memory.refusal(12_000, null, 359.0))
        assertNotNull(memory.refusal(12_000, Point(5.0, 4.0), Double.NaN))
        // Gap confirmed at the end of a short drive over 15 cm: the next drive measures its own.
        assertNotNull(memory.copy(supportM = 0.2).refusal(12_000, Point(5.0, 4.0), 359.0))
    }
}
