package fr.luba.poc.ui

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.RobotRouteUi
import org.junit.Assert.*
import org.junit.Test

class MowingLiveStatsTest {
    private val bladesOn = 1 shl 9

    @Test fun mowingShowsCuttingProgressBladesHeightBatteryAndDuration() {
        // 40 of 233 edges travelled, but 84 % of the cutting metres mowed (resume of 2026-10-15).
        val route = RobotRouteUi(running = true, started = true, heightMm = 40, index = 40, count = 233, cutProgress = 0.84)
        val stats = MowingLiveStats.of(Telemetry(sensorStatus = bladesOn, cutterRpm = 2978, bladeHeightMm = 40, battery = 87), route, 1500.0, 1300.0)
        assertEquals("Tondu 84 %", stats.progress)
        assertEquals(0.84f, stats.fraction!!, 1e-6f)
        assertEquals("Lames 2 978 tr/min", stats.blades)
        assertEquals(true, stats.bladesOn)
        assertEquals("Hauteur 40 mm", stats.height)
        assertEquals("Batterie 87 %", stats.battery)
        assertEquals("25 min / ≈ 22 min", stats.duration)
    }

    @Test fun bladesStoppedAndHeightBeingAdjusted() {
        val route = RobotRouteUi(running = true, heightMm = 40, index = 5, count = 10, cutProgress = 0.2)
        val stats = MowingLiveStats.of(Telemetry(sensorStatus = 0, cutterRpm = 0, bladeHeightMm = 55), route)
        assertEquals("Lames arrêtées", stats.blades)
        assertEquals(false, stats.bladesOn)
        assertEquals("Hauteur 55 → 40 mm", stats.height)
        assertNull(stats.battery)
        assertNull(stats.duration)
    }

    @Test fun routeWithoutCuttingFollowsEdges() {
        val stats = MowingLiveStats.of(Telemetry(), RobotRouteUi(running = true, index = 3, count = 12))
        assertEquals("Trajet 25 %", stats.progress)
        assertEquals("Lames ?", stats.blades)
        assertNull(stats.bladesOn)
        assertNull(stats.height)
        assertNull(MowingLiveStats.of(Telemetry(), RobotRouteUi()).fraction)
    }

    @Test fun rechargeCountShowsCurrentOverExpected() {
        val route = RobotRouteUi(running = true, rechargeCycles = 1, cutProgress = 0.5)
        assertEquals("Recharge 1/2", MowingLiveStats.of(Telemetry(), route, recharges = 1, expectedRecharges = 2).recharges)
        // Without history: recharges of the current task, total raised to the actual count.
        assertEquals("Recharge 1/1", MowingLiveStats.of(Telemetry(), route).recharges)
        assertEquals("Recharge 0/2", MowingLiveStats.rechargeText(0, 2))
        assertEquals("Recharge 3/3", MowingLiveStats.rechargeText(3, 2))
        assertNull(MowingLiveStats.rechargeText(0, 0))
        assertNull(MowingLiveStats.rechargeText(0, null))
    }
}
