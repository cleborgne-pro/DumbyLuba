package fr.luba.poc.maintenance

import fr.luba.poc.auth.Maintenance
import org.junit.Assert.*
import org.junit.Test

class BladeWearTest {
    /** Counters read from the LUBA 2 on 2026-10-20: 779 km, 1178 h, 426 cycles, 182 h of blades, no Mammotion threshold. */
    private val luba = Maintenance(778730, 4241952, 426, 655140, 0)

    @Test fun `no change noted and no robot threshold gives no reminder`() {
        val s = BladeWear.status(luba, null, 100)
        assertEquals(BladeLimitSource.NONE, s.source)
        assertNull(s.limitSeconds); assertNull(s.ratio); assertFalse(s.due)
        assertEquals(182.0, s.usedHours, 0.1)
    }

    @Test fun `robot threshold is used until a change is noted`() {
        val s = BladeWear.status(luba.copy(bladeWarnSeconds = 600_000), null, 100)
        assertEquals(BladeLimitSource.ROBOT, s.source); assertTrue(s.due)
        assertFalse(BladeWear.status(luba.copy(bladeWarnSeconds = 700_000), null, 100).due)
    }

    @Test fun `change noted counts from the robot counter at that time`() {
        val change = BladeChange(655140 - 99 * 3600, 1_000L)
        val before = BladeWear.status(luba, change, 100)
        assertEquals(BladeLimitSource.LOCAL, before.source)
        assertEquals(99 * 3600, before.usedSeconds); assertFalse(before.due); assertEquals(1_000L, before.changedAt)
        val after = BladeWear.status(luba.copy(bladeUsedSeconds = luba.bladeUsedSeconds + 3600), change, 100)
        assertTrue(after.due); assertEquals(1.0, requireNotNull(after.ratio), 1e-9)
        // The local interval wins over the Mammotion threshold.
        assertFalse(BladeWear.status(luba.copy(bladeWarnSeconds = 1), change, 100).due)
    }

    @Test fun `counter reset in the Mammotion app counts from the reset`() {
        val s = BladeWear.status(luba.copy(bladeUsedSeconds = 7200), BladeChange(655140, 1L), 100)
        assertEquals(7200, s.usedSeconds); assertFalse(s.due)
    }
}
