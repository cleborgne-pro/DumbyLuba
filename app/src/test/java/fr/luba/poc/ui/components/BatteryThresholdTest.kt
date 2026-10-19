package fr.luba.poc.ui.components

import fr.luba.poc.simulation.RobotRouteUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Threshold shown next to the battery: low when not charging, high when charging; those of the running mowing before those of the task. */
class BatteryThresholdTest {
    @Test fun lowThresholdWhenNotCharging() {
        val t = batteryThreshold(charging = false, RobotRouteUi(), taskLow = 25, taskHigh = 85)
        assertEquals(BatteryThreshold(25, false), t)
        assertEquals("↓ 25 %", t.short)
        assertEquals("retour à 25 %", t.label)
    }

    @Test fun highThresholdWhenCharging() {
        val t = batteryThreshold(charging = true, RobotRouteUi(), taskLow = 25, taskHigh = 85)
        assertEquals(BatteryThreshold(85, true), t)
        assertEquals("↑ 85 %", t.short)
        assertEquals("reprise à 85 %", t.label)
    }

    @Test fun runningMissionThresholdsWin() {
        val route = RobotRouteUi(running = true, returnPercent = 30, resumePercent = 95)
        assertEquals(30, batteryThreshold(false, route, 20, 90).percent)
        assertEquals(95, batteryThreshold(true, route, 20, 90).percent)
    }

    @Test fun reachedOnlyAtOrBelowTheReturnThreshold() {
        val low = BatteryThreshold(20, false)
        assertTrue(low.reached(20)); assertTrue(low.reached(12))
        assertFalse(low.reached(21)); assertFalse(low.reached(null))
        assertFalse(BatteryThreshold(90, true).reached(40))
    }

    @Test fun finishingAndShortChargeThresholdsAreShown() {
        val route = RobotRouteUi(running = true, returnPercent = 20, resumePercent = 90, lowWatch = 10, chargeTarget = 45)
        assertEquals(10, batteryThreshold(false, route, 20, 90).percent)
        assertEquals(45, batteryThreshold(true, route, 20, 90).percent)
    }
}
