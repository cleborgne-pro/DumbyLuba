package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Docking by the gateway, pilot absent at the end of the return to base (tests of 2026-10-14). */
class OrphanDockTest {
    private val approach = Point(0.8, 0.42)
    private val back = listOf(SimEdge(Point(-4.0, -6.0), Point(-1.0, -1.0), cutting = false), SimEdge(Point(-1.0, -1.0), approach, cutting = false))

    private fun spec(baseApproach: Point? = approach, dockExit: Boolean = false) = LegSpec(1, 1, 0, cutting = false, shown = true,
        watchBattery = false, departure = false, lastArrivalM = null, heightMm = 60, speedPercent = 30, returnPercent = 20,
        baseApproach = baseApproach, guardId = -1, physicalGuardId = -1, calibration = null, obstacles = emptyList(), sampling = false,
        dockExit = dockExit)

    @Test fun `return finished without pilot towards the approach point`() {
        assertTrue(OrphanDock.wanted(spec(), back, LegOutcome.DONE, stopConfirmed = true, pilotPresent = false))
        assertFalse("pilot present: it docks by itself", OrphanDock.wanted(spec(), back, LegOutcome.DONE, true, pilotPresent = true))
        assertFalse("stop not confirmed", OrphanDock.wanted(spec(), back, LegOutcome.DONE, false, false))
        assertFalse("tracking failed", OrphanDock.wanted(spec(), back, LegOutcome.FAILED, true, false))
        assertFalse("contact", OrphanDock.wanted(spec(), back, LegOutcome.CONTACT, true, false))
        assertFalse("without base", OrphanDock.wanted(spec(baseApproach = null), back, LegOutcome.DONE, true, false))
        assertFalse("leaving the station", OrphanDock.wanted(spec(dockExit = true), back, LegOutcome.DONE, true, false))
        assertFalse("route not ending in front of the base", OrphanDock.wanted(spec(), back.dropLast(1), LegOutcome.DONE, true, false))
    }

    private fun view(x: Double? = 0.85, y: Double? = 0.40, selfCheck: Long? = 10, sensors: Int? = 0, positionAt: Long = 9_500) =
        RobotView(Telemetry(robotState = 11, sensorStatus = sensors, selfCheck = selfCheck, x = x, y = y), devicePrevious = positionAt, positionPrevious = positionAt)

    @Test fun `robot stopped in front of the base, fresh position and nominal protection`() {
        assertNull(OrphanDock.refusal(view(), 10_000, approach))
        assertNotNull("too far", OrphanDock.refusal(view(x = 1.2), 10_000, approach))
        assertNotNull("stale position", OrphanDock.refusal(view(positionAt = 5_000), 10_000, approach))
        assertNotNull("never a position", OrphanDock.refusal(view(positionAt = 0), 10_000, approach))
        assertNotNull("missing position", OrphanDock.refusal(view(x = null), 10_000, approach))
        assertNotNull("protection 25", OrphanDock.refusal(view(selfCheck = 25), 10_000, approach))
        assertNotNull("bumper", OrphanDock.refusal(view(sensors = 1), 10_000, approach))
        assertNotNull("unknown sensors", OrphanDock.refusal(view(sensors = null), 10_000, approach))
    }
}
