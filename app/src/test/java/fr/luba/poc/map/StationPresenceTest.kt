package fr.luba.poc.map

import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationPresenceTest {
    @Test
    fun chargingContactOffersOnlyLeave() {
        listOf(1, 2).forEach { c ->
            val p = StationPresence.of(c)
            assertEquals(StationPresence.ON_BASE, p)
            assertTrue(p.canLeave)
            assertFalse(p.canRecharge)
        }
    }

    @Test
    fun noContactOffersOnlyRecharge() {
        val p = StationPresence.of(0)
        assertEquals(StationPresence.AWAY, p)
        assertFalse(p.canLeave)
        assertTrue(p.canRecharge)
    }

    @Test
    fun unknownStateKeepsBothActions() {
        val p = StationPresence.of(null as Int?)
        assertEquals(StationPresence.UNKNOWN, p)
        assertTrue(p.canLeave)
        assertTrue(p.canRecharge)
    }

    @Test
    fun staleOrMissingReportIsUnknown() {
        val now = 1_000_000L
        val docked = Telemetry(chargeState = 1, lastDeviceAt = now - 5_000)
        assertEquals(StationPresence.ON_BASE, StationPresence.of(docked, 15_000, now))
        assertEquals(StationPresence.UNKNOWN, StationPresence.of(docked.copy(lastDeviceAt = now - 20_000), 15_000, now))
        assertEquals(StationPresence.UNKNOWN, StationPresence.of(docked.copy(lastDeviceAt = null), 15_000, now))
        assertEquals(StationPresence.UNKNOWN, StationPresence.of(null, 15_000, now))
        assertEquals(StationPresence.AWAY, StationPresence.of(Telemetry(chargeState = 0, lastDeviceAt = now), 15_000, now))
        // Clock lead within the tolerance stays valid.
        assertEquals(StationPresence.ON_BASE, StationPresence.of(docked.copy(lastDeviceAt = now + 5_000), 15_000, now))
    }
}
