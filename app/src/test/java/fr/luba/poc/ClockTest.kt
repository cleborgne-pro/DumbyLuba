package fr.luba.poc

import org.junit.Assert.assertEquals
import org.junit.Test

/** The time seam: a test can freeze the wall and monotonic clocks, then restore them for the next test. */
class ClockTest {
    @Test fun wallAndMonotonicClocksCanBeFrozenAndRestored() {
        val wall = Clock.wall
        val monotonic = Clock.monotonic
        try {
            Clock.wall = { 42L }
            Clock.monotonic = { 7L }
            assertEquals(42L, Clock.now())
            assertEquals(7L, Clock.elapsed())
        } finally {
            Clock.wall = wall
            Clock.monotonic = monotonic
        }
    }
}
