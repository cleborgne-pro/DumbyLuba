package fr.luba.poc.diagnostics

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.junit.Assert.*
import org.junit.Test

class CommandSilenceProbeTest {
    /** Drives 0.1 m per second, positions at 1 Hz, last command at 3.2 s. */
    private fun drivenProbe(): CommandSilenceProbe {
        val probe = CommandSilenceProbe()
        probe.startDrive(0)
        for (i in 0..3) probe.driveFix(i * 1000L, Point(i * 0.1, 0.0))
        assertTrue(probe.readyToSilence(3200))
        probe.startSilence(3200)
        return probe
    }

    @Test fun robotStoppingAloneIsDemonstratedWithUpperBound() {
        val probe = drivenProbe()
        assertNull(probe.silentFix(3000, Point(0.3, 0.0))) // before the cutoff: ignored
        assertNull(probe.silentFix(4000, Point(0.38, 0.0)))
        assertNull(probe.silentFix(5000, Point(0.39, 0.0)))
        val verdict = probe.silentFix(6000, Point(0.395, 0.0)) as SilenceVerdict.RobotStopped
        assertEquals(800, verdict.boundMs) // stationary since the 4.0 s position
        assertEquals(0.095, verdict.driftM, 1e-9)
        assertEquals(0.1, verdict.speedMps!!, 1e-9)
        assertEquals("Arrêt seul démontré", verdict.title)
        val result = MotorWatchdogResult.of("AA", 1L, "1.30", 30, verdict)
        assertTrue(result.demonstrated)
        assertEquals(result, MotorWatchdogResult.fromJson(result.toJson()))
    }

    @Test fun singleRepeatedPositionIsNotEnough() {
        val probe = drivenProbe()
        assertNull(probe.silentFix(4000, Point(0.4, 0.0)))
        assertNull(probe.silentFix(5000, Point(0.4, 0.0)))
        assertNull(probe.silentFix(6000, Point(0.5, 0.0)))
        assertEquals(CommandSilenceProbe.Phase.SILENT, probe.phase)
    }

    @Test fun robotStillMovingAfterTimeoutRequiresAppStop() {
        val probe = drivenProbe()
        for (i in 4..9) assertNull(probe.silentFix(i * 1000L, Point(i * 0.1, 0.0)))
        assertNull(probe.tick(9100))
        val verdict = probe.tick(9200) as SilenceVerdict.StillMoving
        assertEquals(6000, verdict.elapsedMs)
        val result = MotorWatchdogResult.of("AA", 1L, null, 30, verdict)
        assertFalse(result.demonstrated)
        assertEquals(MotorWatchdogResult.STILL_MOVING, result.outcome)
    }

    @Test fun excessiveDriftStopsImmediately() {
        val probe = drivenProbe()
        assertNull(probe.silentFix(4000, Point(0.8, 0.0)))
        val verdict = probe.silentFix(5000, Point(1.35, 0.0))
        assertTrue(verdict is SilenceVerdict.StillMoving)
        assertEquals(CommandSilenceProbe.Phase.DONE, probe.phase)
    }

    @Test fun missingPositionsAreInconclusive() {
        val probe = drivenProbe()
        assertTrue(probe.tick(9200) is SilenceVerdict.Inconclusive)
        assertFalse(MotorWatchdogResult.of("AA", 1L, null, 30, probe.verdict!!).demonstrated)
    }

    @Test fun slowStopIsNotDemonstrated() {
        val probe = drivenProbe()
        assertNull(probe.silentFix(4000, Point(0.4, 0.0)))
        assertNull(probe.silentFix(5000, Point(0.5, 0.0)))
        assertNull(probe.silentFix(6000, Point(0.55, 0.0)))
        assertNull(probe.silentFix(7000, Point(0.55, 0.0)))
        val verdict = probe.silentFix(8000, Point(0.55, 0.0)) as SilenceVerdict.RobotStopped
        assertEquals(2800, verdict.boundMs)
        assertFalse(MotorWatchdogResult.of("AA", 1L, null, 30, verdict).demonstrated)
    }

    @Test fun driveWithoutMotionFails() {
        val probe = CommandSilenceProbe()
        probe.startDrive(0)
        for (i in 0..5) probe.driveFix(i * 1000L, Point(0.0, 0.0))
        assertFalse(probe.readyToSilence(3000))
        assertThrows(IllegalStateException::class.java) { probe.readyToSilence(6000) }
    }

    @Test fun clearanceRequiresFreeStraightLineInsideZone() {
        val map = RobotMap(listOf(MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0))),
            MapShape(2, 1, listOf(Point(6.0, 4.0), Point(7.0, 4.0), Point(7.0, 6.0), Point(6.0, 6.0)))), true)
        assertNull(SilenceClearance.failure(map, emptyList(), Point(2.0, 2.0), 0.0, 0.3))
        assertNotNull(SilenceClearance.failure(map, emptyList(), Point(8.5, 2.0), 0.0, 0.3)) // edge too close
        assertNotNull(SilenceClearance.failure(map, emptyList(), Point(4.0, 5.0), 0.0, 0.3)) // no-go zone ahead
        assertNotNull(SilenceClearance.failure(map.copy(complete = false), emptyList(), Point(2.0, 2.0), 0.0, 0.3))
        assertNotNull(SilenceClearance.failure(map, emptyList(), Point(-1.0, 2.0), 0.0, 0.3))
    }

    @Test fun corruptedResultIsIgnored() {
        assertNull(MotorWatchdogResult.fromJson("{}"))
        assertNull(MotorWatchdogResult.fromJson("pas du json"))
    }
}
