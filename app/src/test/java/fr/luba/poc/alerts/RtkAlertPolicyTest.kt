package fr.luba.poc.alerts

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.ui.components.PositionQuality
import fr.luba.poc.ui.components.positionQuality
import org.junit.Assert.*
import org.junit.Test

class RtkAlertPolicyTest {
    /** Telemetry recorded on the Fold on 2026-10-15 (1,972 reports, robot on the base): FIX with pos_level = 1. */
    private val realFix = Telemetry(rtkStatus = 4, positioningLevel = 1, robotState = 11, battery = 100)
    private val events = mutableListOf<Pair<AlertKind, Severity>>()

    /** One second of monitoring, like [fr.luba.poc.ble.RobotBle.observeAlerts], with an RTK report received every second. */
    private fun run(monitor: AlertMonitor, t: Telemetry, moving: Boolean, from: Long, to: Long) {
        var now = from
        while (now <= to) {
            monitor.tick(now, now, t.battery, true, RtkAlertPolicy.watched(true, moving, false),
                RtkAlertPolicy.freshFix(t, now, now)) { kind, severity -> events += kind to severity }
            now += 1000
        }
    }

    @Test fun realFixIsFixWhateverPosLevel() {
        assertTrue(realFix.rtkFix)
        assertTrue(RtkAlertPolicy.freshFix(realFix, 1_000, 6_000))
        assertFalse(RtkAlertPolicy.freshFix(realFix, 1_000, 6_001))
        assertFalse(RtkAlertPolicy.freshFix(realFix, null, 1_000))
        // pos_level = 0 without FIX status is not a FIX (former reading).
        assertFalse(Telemetry(rtkStatus = 5, positioningLevel = 0).rtkFix)
        assertFalse(Telemetry(rtkStatus = 1, positioningLevel = 0).rtkFix)
        assertFalse(Telemetry().rtkFix)
    }

    @Test fun robotAtBaseNeverRaisesRtkLoss() {
        val m = AlertMonitor(); m.arm()
        run(m, realFix, moving = false, from = 0, to = 120_000)
        run(m, realFix.copy(rtkStatus = 5), moving = false, from = 121_000, to = 240_000)
        assertTrue(events.none { it.first == AlertKind.RTK_LOST })
    }

    @Test fun movingWithRealFixRaisesNothing() {
        val m = AlertMonitor(); m.arm()
        run(m, realFix, moving = true, from = 0, to = 120_000)
        assertTrue(events.none { it.first == AlertKind.RTK_LOST })
    }

    @Test fun movingInFloatRaisesAfterTenSeconds() {
        val m = AlertMonitor(); m.arm()
        run(m, realFix.copy(rtkStatus = 5), moving = true, from = 0, to = 9_000)
        assertTrue(events.isEmpty())
        run(m, realFix.copy(rtkStatus = 5), moving = true, from = 10_000, to = 30_000)
        assertEquals(listOf(AlertKind.RTK_LOST to Severity.CRITICAL), events)
    }

    @Test fun linkFaultLeavesRtkToLinkAlerts() {
        assertFalse(RtkAlertPolicy.watched(ready = true, moving = true, linkFault = true))
        assertFalse(RtkAlertPolicy.watched(ready = false, moving = true, linkFault = false))
        assertTrue(RtkAlertPolicy.watched(ready = true, moving = true, linkFault = false))
    }

    @Test fun displayedQualityFollowsRtkStatus() {
        assertEquals(PositionQuality.FIX, positionQuality(realFix.rtkStatus))
        assertEquals(PositionQuality.FLOAT, positionQuality(5))
        assertEquals(PositionQuality.SINGLE, positionQuality(1))
        assertEquals(PositionQuality.NONE, positionQuality(0))
        assertEquals(PositionQuality.UNKNOWN, positionQuality(null))
    }
}
