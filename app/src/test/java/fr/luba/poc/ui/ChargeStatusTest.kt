package fr.luba.poc.ui

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.components.BLE_CHARGE_MAX_AGE_MS
import fr.luba.poc.ui.components.CLOUD_CHARGE_MAX_AGE_MS
import fr.luba.poc.ui.components.ChargeSource
import fr.luba.poc.ui.components.chargeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeStatusTest {
    private val now = 1_800_000_000_000L
    private fun t(battery: Int?, charge: Int?, ageMs: Long) = Telemetry(battery = battery, chargeState = charge, lastDeviceAt = now - ageMs)

    @Test fun cloudTelemetryAloneShowsCharge() {
        val s = requireNotNull(chargeStatus(t(64, 1, 20_000), null, now))
        assertEquals(64, s.percent); assertEquals(ChargeSource.CLOUD, s.source)
        assertEquals(ChargeSource.CLOUD, chargeStatus(t(64, 2, 0), null, now)?.source)
        assertNull(chargeStatus(t(64, 0, 0), null, now))
        assertNull(chargeStatus(Telemetry(battery = 64, chargeState = 1), null, now)) // never timestamped
    }

    @Test fun staleCloudReadingIsIgnored() {
        assertTrue(chargeStatus(t(64, 1, CLOUD_CHARGE_MAX_AGE_MS), null, now) != null)
        assertNull(chargeStatus(t(64, 1, CLOUD_CHARGE_MAX_AGE_MS + 1), null, now))
        assertNull(chargeStatus(t(64, 1, -60_000), null, now)) // timestamp in the future
    }

    @Test fun reportNewerThanSampledClockStaysCharging() {
        // Regression: the screen clock (sampled every 5 s) lagged behind BLE reports (1 per second);
        // the reading looked "in the future", so the charge disappeared and came back on the next tick.
        for (lead in listOf(200L, 900L, 4_900L, 10_000L)) {
            assertEquals("BLE, ahead by $lead ms", ChargeSource.BLE, chargeStatus(null, t(80, 2, -lead), now)?.source)
            assertTrue("account, ahead by $lead ms", chargeStatus(t(80, 2, -lead), null, now) != null)
        }
        assertNull(chargeStatus(t(80, 2, -10_001L), null, now))
    }

    @Test fun mostRecentSourceWins() {
        // Newer BLE: it wins, including to say the robot has left the base.
        assertEquals(ChargeSource.BLE, chargeStatus(t(60, 1, 30_000), t(61, 1, 1_000), now)?.source)
        assertNull(chargeStatus(t(60, 1, 30_000), t(61, 0, 1_000), now))
        // Silent BLE: the account takes over.
        assertEquals(ChargeSource.CLOUD, chargeStatus(t(60, 1, 30_000), t(61, 0, BLE_CHARGE_MAX_AGE_MS + 1), now)?.source)
        // Account newer than the last BLE report.
        assertEquals(66, chargeStatus(t(66, 1, 1_000), t(61, 0, 5_000), now)?.percent)
    }

    @Test fun fullAndUnknownPercent() {
        val full = requireNotNull(chargeStatus(t(100, 1, 0), null, now))
        assertTrue(full.full); assertEquals("Charge terminée", full.label)
        val unknown = requireNotNull(chargeStatus(t(null, 1, 0), null, now))
        assertNull(unknown.percent); assertFalse(unknown.full); assertEquals("En charge", unknown.label)
        assertEquals(100, chargeStatus(t(140, 1, 0), null, now)?.percent)
    }
}
