package fr.luba.poc.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebugPolicyTest {
    private fun decide(keep: Boolean = true, canWrite: Boolean = true, wifi: Boolean = true, enabled: Boolean = false,
                       now: Long = 100_000L, last: Long? = null) =
        WirelessDebugPolicy.shouldEnable(keep, canWrite, wifi, enabled, now, last)

    @Test fun turnsBackOnWhenOffWithWifiAndPermission() = assertTrue(decide())
    @Test fun leavesItAloneWhenAlreadyOn() = assertFalse(decide(enabled = true))
    @Test fun respectsTheUserChoice() = assertFalse(decide(keep = false))
    @Test fun noAttemptWithoutPermission() = assertFalse(decide(canWrite = false))
    @Test fun waitsForTheWifi() = assertFalse(decide(wifi = false))

    @Test fun limitsCloseAttempts() {
        assertFalse(decide(now = 105_000L, last = 100_000L))
        assertTrue(decide(now = 100_000L + WirelessDebugPolicy.MIN_INTERVAL_MS, last = 100_000L))
    }
}
