package fr.luba.poc.history

import org.junit.Assert.*
import org.junit.Test

class LiveEtaTest {
    private val now = 1_000_000L
    private fun record(totals: MowingTotals = MowingTotals(), done: Double = 0.0) = MowingRecord(
        id = "t", robotId = "r", startedAt = 0, cuttingMeters = 1000.0, plannedMeters = 1200.0, doneMeters = done,
        returnPercent = 20, resumePercent = 90, totals = totals,
        forecast = MowingForecast(totalSeconds = 4000.0, recharges = 0, travelSeconds = 4000.0, autonomyMinutes = 100.0,
            chargeMinutes = 60.0, timeFactor = 1.0, battery = 100.0))

    @Test fun atTheStartTheForecastSpeedIsUsed() {
        val eta = requireNotNull(LiveEta.of(record(), 100, 0.0, now))
        assertFalse(eta.measuredSpeed)
        assertEquals(0.25, eta.cutMetresPerSecond, 1e-9)
        assertEquals(4000.0, eta.remainingSeconds, 1e-6)
        assertEquals(0, eta.rechargesLeft)
        assertEquals(now + 4_000_000L, eta.endAtMs)
    }

    @Test fun slowerMeasuredProgressPushesTheEndAndAddsARecharge() {
        // 300 m cut in 30 min (incidents included): 0.17 m/s instead of 0.25; 30 % used, i.e. 100 min of autonomy.
        val eta = requireNotNull(LiveEta.of(record(MowingTotals(activeSeconds = 1800.0, batteryUsed = 30.0)), 70, 0.3, now))
        assertTrue(eta.measuredSpeed)
        assertEquals(300.0 / 1800.0, eta.cutMetresPerSecond, 1e-9)
        // 700 m left: 4200 s of driving, only 50 % above the return threshold (3000 s): one recharge.
        assertEquals(1, eta.rechargesLeft)
        assertTrue(eta.remainingSeconds > 4200.0)
        val early = requireNotNull(LiveEta.of(record(MowingTotals(activeSeconds = 60.0, batteryUsed = 1.0)), 70, 0.3, now))
        assertFalse("too early to trust the measurement", early.measuredSpeed)
    }

    @Test fun chargingOnTheBaseCountsTheChargeLeftFirst() {
        val docked = requireNotNull(LiveEta.of(record(), 40, 0.0, now, charging = true))
        val leaving = requireNotNull(LiveEta.of(record(), 90, 0.0, now))
        // 50 points to the resume threshold at 60 min per 100 %.
        assertEquals(50 * 36.0, docked.remainingSeconds - leaving.remainingSeconds, 1e-6)
        // Measured charge rate replaces the forecast one (40 points in 20 min: 50 min per 100 %).
        val fast = requireNotNull(LiveEta.of(record(MowingTotals(chargingSeconds = 1200.0, batteryCharged = 40.0)), 40, 0.0, now, charging = true))
        assertEquals(50 * 30.0, fast.remainingSeconds - leaving.remainingSeconds, 1e-6)
    }

    @Test fun nothingWithoutACuttingLength() {
        assertNull(LiveEta.of(record().copy(cuttingMeters = 0.0), 80, null, now))
    }

    @Test fun barTextGivesTheEndTimeTheRemainderAndTheRecharges() {
        val eta = LiveEta.Eta(3900.0, 3_900_000L, 1, true, 0.2)
        val text = fr.luba.poc.ui.MowingLiveStats.etaText(eta, java.time.ZoneOffset.UTC)
        assertTrue(text, text.contains("01:05") && text.contains("1 h 05") && text.contains("1 recharge"))
        val estimated = fr.luba.poc.ui.MowingLiveStats.etaText(eta.copy(rechargesLeft = 0, measuredSpeed = false), java.time.ZoneOffset.UTC)
        assertFalse(estimated, estimated.contains("recharge"))
    }
}
