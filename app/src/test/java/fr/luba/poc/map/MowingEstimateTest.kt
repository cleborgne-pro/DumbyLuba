package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class MowingEstimateTest {
    @Test fun configuredChargeLimitsDriveTheEstimate() {
        val result = MowingEstimate.fromDistance(600.0,0.3,60.0,30.0,100.0,30.0,80.0)
        assertEquals(0,result.recharges)
        assertEquals(2000.0,result.totalSeconds,1e-6)
        assertThrows(IllegalArgumentException::class.java) { MowingEstimate.fromDistance(600.0,0.3,60.0,30.0,100.0,80.0,80.0) }
    }
    @Test fun shortMissionNeedsNoCharge() {
        val result = MowingEstimate.fromDistance(180.0, 0.3, 60.0, 30.0, 100.0)
        assertEquals(0, result.recharges)
        assertEquals(600.0, result.totalSeconds, 1e-6)
    }
    @Test fun longerMissionIncludesRechargeTime() {
        val result = MowingEstimate.fromDistance(1800.0, 0.3, 60.0, 30.0, 100.0)
        // 6,000 s of route, 36 s per point: 80 % usable, full recharge 20 → 90 %, then only 20 → 40.8 % for the
        // remaining 600 s (25 % margin) instead of a second recharge to 90 %.
        assertEquals(2, result.recharges)
        assertEquals(6000.0 + (70.0 + 600.0 / 36 * 1.25) * 18, result.totalSeconds, 1e-6)
    }
    @Test fun lowBatteryIncludesInitialCharge() {
        val result = MowingEstimate.fromDistance(180.0, 0.3, 60.0, 30.0, 10.0)
        // Initial recharge up to 20 % + energy for the remainder (600 s, margin included), not up to 90 %.
        assertEquals(1, result.recharges)
        assertEquals(600.0 + (10.0 + 600.0 / 36 * 1.25) * 18, result.totalSeconds, 1e-6)
    }
    @Test fun smallRestIsFinishedBelowTheLowThreshold() {
        // 100 % then 80 % usable (2,880 s); 240 s left at the low threshold = 8.3 % margin included: arrival at 11.7 % ≥ 10 %.
        val result = MowingEstimate.fromDistance((2880.0 + 240.0) * 0.3, 0.3, 60.0, 30.0, 100.0)
        assertEquals(0, result.recharges)
        // 360 s left = 12.5 %: arrival at 7.5 %, short recharge (20 → 32.5 %).
        val short = MowingEstimate.fromDistance((2880.0 + 360.0) * 0.3, 0.3, 60.0, 30.0, 100.0)
        assertEquals(1, short.recharges)
        assertEquals(3240.0 + 12.5 * 18, short.totalSeconds, 1e-6)
    }
}
