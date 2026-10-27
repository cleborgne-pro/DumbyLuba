package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-check 41 under the raised play house of the Aire de jeux (2026-10-25). */
class PositioningGraceTest {
    @Test fun positioningLossRiddenThroughWithinTimeAndDistance() {
        val grace = PositioningGrace()
        assertTrue(grace.rides(0, 20, 41, Point(0.0, 0.0)))
        assertTrue(grace.rides(10_000, 20, 41, Point(2.0, 0.0)))
        assertTrue(grace.active)
        // Beyond 20 s: spent until the positioning comes back.
        assertFalse(grace.rides(PositioningGrace.MAX_MS + 1, 20, 41, Point(2.5, 0.0)))
        assertFalse(grace.rides(PositioningGrace.MAX_MS + 2000, 20, 41, Point(2.5, 0.0)))
        // Nominal again: a new loss gets its own grace.
        assertFalse(grace.rides(30_000, 20, 10, Point(3.0, 0.0)))
        assertTrue(grace.rides(31_000, 20, 41, Point(3.0, 0.0)))
        // Beyond 5 m from where it appeared.
        assertFalse(grace.rides(32_000, 20, 41, Point(3.0 + PositioningGrace.MAX_M + 0.1, 0.0)))
    }

    @Test fun otherProtectionsAndStatesAreNeverRiddenThrough() {
        val grace = PositioningGrace()
        assertFalse("collision lock", grace.rides(0, 20, 25, Point(0.0, 0.0)))
        assertFalse("locked after STOP", grace.rides(0, 20, 11, Point(0.0, 0.0)))
        assertFalse("robot no longer in manual control", grace.rides(0, 11, 41, Point(0.0, 0.0)))
        assertFalse("no position", grace.rides(0, 20, 41, null))
        assertTrue("weak RTK signal", grace.rides(0, 20, 99, Point(0.0, 0.0)))
    }
}
