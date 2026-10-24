package fr.luba.poc.simulation

import org.junit.Assert.*
import org.junit.Test

/** Fleeting protection of 2026-10-15 (self-check 41 on a single report): wait then resume, never on a lock. */
class ProtectionPauseTest {
    @Test fun onlyTransientProtectionsOfARunningRobotArePausable() {
        assertTrue(ProtectionPause.pausable(ProtectionPause.reason(20, 41)))
        assertTrue(ProtectionPause.pausable("Protection / état robot changé (état 20, protection 41)"))
        assertTrue(ProtectionPause.pausable("Protection / robot state changed (state 20, protection 41)"))
        assertTrue(ProtectionPause.pausable(ProtectionPause.reason(11, 25)))
        // Lock after STOP, unknown or incompatible state, nominal or missing self-check: stop kept.
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(20, 11)))
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(13, 41)))
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(null, 41)))
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(20, 10)))
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(20, null)))
        assertFalse(ProtectionPause.pausable("Protection robot active"))
        assertFalse(ProtectionPause.pausable("Écart au trajet >30 cm (30 cm, arête 2)"))
        assertNull(ProtectionPause.refusal(ProtectionPause.MAX_PAUSES - 1))
        assertNotNull(ProtectionPause.refusal(ProtectionPause.MAX_PAUSES))
    }

    @Test fun resumesOnlyOnceTheProtectionHasStayedClearedLongEnough() {
        val wait = ProtectionPause.Wait(startedAt = 0)
        assertFalse(wait.update(0, 20, 41))
        assertFalse(wait.update(1_000, 11, 10))
        // Relapse: the stability counter restarts.
        assertFalse(wait.update(2_000, 11, 41))
        assertFalse(wait.update(3_000, 11, 10))
        assertFalse(wait.update(5_900, 11, 10))
        assertTrue(wait.update(6_000, 11, 10))
    }

    @Test fun persistentOrLockedProtectionKeepsTheStop() {
        val stuck = ProtectionPause.Wait(startedAt = 0)
        assertFalse(stuck.update(29_000, 11, 25))
        val timeout = runCatching { stuck.update(ProtectionPause.WAIT_MS, 11, 25) }.exceptionOrNull()
        assertTrue("$timeout", timeout is IllegalStateException && timeout.message!!.contains("toujours active"))
        val locked = runCatching { ProtectionPause.Wait(0).update(1_000, 11, 11) }.exceptionOrNull()
        assertTrue("$locked", locked is IllegalStateException)
    }

    /** Forbidden working hours of the Mammotion app (self-check 23): it lasts hours, no waiting pause. */
    @Test fun mammotionForbiddenTimeIsNotAPause() {
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(11, 23)))
        assertFalse(ProtectionPause.pausable(ProtectionPause.reason(20, 23)))
        val forbidden = runCatching { ProtectionPause.Wait(0).update(1_000, 11, 23) }.exceptionOrNull()
        assertTrue("$forbidden", forbidden is IllegalStateException && forbidden.message!!.contains("app Mammotion"))
    }

    /** Weak satellite signal (41) and the other positioning conditions: waited up to 5 min, not 30 s. */
    @Test fun positioningConditionIsWaitedLonger() {
        assertEquals(ProtectionPause.POSITIONING_WAIT_MS, ProtectionPause.waitMs(41))
        assertEquals(ProtectionPause.WAIT_MS, ProtectionPause.waitMs(25))
        val wait = ProtectionPause.Wait(startedAt = 0)
        assertFalse(wait.update(4 * 60_000, 20, 41))
        assertFalse(wait.update(4 * 60_000 + 500, 11, 10))
        assertTrue(wait.update(4 * 60_000 + 3_500, 11, 10))
        val lost = runCatching { ProtectionPause.Wait(0).update(ProtectionPause.POSITIONING_WAIT_MS, 11, 41) }.exceptionOrNull()
        assertTrue("$lost", lost is IllegalStateException && lost.message!!.contains("300 s"))
    }
}
