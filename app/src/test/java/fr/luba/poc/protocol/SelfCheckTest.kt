package fr.luba.poc.protocol

import fr.luba.poc.diagnostics.NavSafety
import fr.luba.poc.obstacles.TeachSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckTest {
    @Test fun `only 0 and 10 allow movement`() {
        listOf(0L, 10L).forEach { assertTrue("$it", SelfCheck.nominal(it)) }
        listOf(null, 1L, 11L, 12L, 34L, 0xffffffffL).forEach { assertFalse("$it", SelfCheck.nominal(it)) }
    }

    @Test fun `explicit refusal reason`() {
        assertTrue(SelfCheck.refusal(11).startsWith("Robot verrouillé après STOP (self-check 11)"))
        assertEquals("Protection / self-check actif (34)", SelfCheck.refusal(34))
        assertEquals("Protection / self-check inconnu (télémétrie absente)", SelfCheck.refusal(null))
    }

    /** 2026-10-15: 10 → 23 at exactly 21:00:00, robot still on charge: forbidden time slot of the Mammotion app. */
    @Test fun `self-check 23, Mammotion app time slot, movement refused`() {
        assertTrue(SelfCheck.forbiddenTime(23))
        assertFalse(SelfCheck.forbiddenTime(11))
        assertFalse(SelfCheck.nominal(23))
        assertTrue(StopUnlock.blocked(23))
        assertTrue(SelfCheck.refusal(23).contains("plage horaire réglée dans l'app Mammotion"))
        assertEquals(SelfCheck.refusal(23), NavSafety.startFailure("Luba-VSDEMO01", 100, 100, 11, 0, 23, false, false, false, 60))
    }

    /** Real telemetry of 2026-10-11 after error 1008: sys_status 11 but self_check_status 11, robot not moving. */
    @Test fun `movement refused with self-check 11 despite the READY state`() {
        assertEquals(SelfCheck.refusal(11), NavSafety.startFailure("Luba-VSDEMO01", 100, 87, 11, 0, 11, false, false, false, 60))
        assertEquals(SelfCheck.refusal(11), TeachSafety.startFailure("Luba-VSDEMO01", 100, 87, 11, 0, 11, false, false, false, false))
        assertNull(NavSafety.startFailure("Luba-VSDEMO01", 100, 87, 11, 0, 10, false, false, false, 60))
    }
}
