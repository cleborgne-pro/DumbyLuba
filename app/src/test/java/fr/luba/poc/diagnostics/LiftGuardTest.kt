package fr.luba.poc.diagnostics

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

class LiftGuardTest {
    /** Constant command every 200 ms and positions at 1 Hz, from 0 to [untilMs]. */
    private fun drive(guard: LiftGuard, linear: Int, untilMs: Long, position: (Long) -> Point): String? {
        var reason: String? = null
        var t = 0L
        while (t <= untilMs) {
            guard.commanded(linear, t)
            if (t % 1000 == 0L) guard.position(position(t), t)?.let { if (reason == null) reason = it }
            t += 200
        }
        return reason
    }

    @Test fun normalDrivingNeverTriggers() {
        val guard = LiftGuard()
        // 250 units ≈ 0.24 m/s; positions 600 ms late like the firmware.
        assertNull(drive(guard, 250, 20_000) { t -> Point(maxOf(0L, t - 600) * 250 * 0.00095 / 1000, 0.0) })
    }

    @Test fun slowerThanNominalStillPasses() {
        val guard = LiftGuard()
        assertNull(drive(guard, 250, 20_000) { t -> Point(maxOf(0L, t - 600) * 250 * 0.00095 * 0.5 / 1000, 0.0) })
    }

    @Test fun liftedWheelsSpinningInPlaceTrigger() {
        val guard = LiftGuard()
        // Drives 5 s, then lifted: position frozen.
        val reason = drive(guard, 250, 12_000) { t -> Point(minOf(t, 5000L) * 250 * 0.00095 / 1000, 0.0) }
        assertNotNull(reason)
        assertTrue(reason!!.contains("soulevé"))
    }

    @Test fun slipIsFlaggedAgainstTheUsualProgressWithoutStopping() {
        val unit = 250 * 0.00095 / 1000
        // Robot slower than the nominal calibration (60 %): usual progress, never slipping.
        val slow = LiftGuard()
        var flagged = false
        var t = 0L
        while (t <= 20_000) {
            slow.commanded(250, t)
            if (t % 1000 == 0L) { assertNull(slow.position(Point(maxOf(0L, t - 600) * unit * 0.6, 0.0), t)); flagged = flagged || slow.slipping }
            t += 200
        }
        assertFalse(flagged)
        // Slipping: 40 % of the usual progress, above the stop threshold (25 %): flagged, no stop.
        val guard = LiftGuard()
        var x = 0.0; var stop: String? = null; flagged = false
        t = 0L
        while (t <= 30_000) {
            guard.commanded(250, t)
            if (t % 1000 == 0L) {
                guard.position(Point(x, 0.0), t)?.let { stop = it }
                if (t > 20_000) flagged = flagged || guard.slipping
            }
            x += 200 * unit * (if (t < 18_000) 1.0 else 0.4)
            t += 200
        }
        assertTrue("slip flagged", flagged)
        assertNull("slip without stop", stop)
    }

    /** 2026-10-22: gentle joystick (150 units) on a robot moving at a fifth of the nominal calibration: no stop. */
    @Test fun gentleDrivingSlowerThanCalibrationIsNotALift() {
        assertNull(drive(LiftGuard(), 150, 20_000) { t -> Point(maxOf(0L, t - 600) * 150 * 0.00095 * 0.2 / 1000, 0.0) })
        assertNull(drive(LiftGuard(), 120, 20_000) { Point(0.0, 0.0) })
    }

    @Test fun liftAtANormalJoystickSpeedStillStops() {
        assertNotNull(drive(LiftGuard(), 400, 12_000) { t -> Point(minOf(t, 5000L) * 400 * 0.00095 / 1000, 0.0) })
    }

    @Test fun slowApproachAndPivotsAreIgnored() {
        assertNull(drive(LiftGuard(), 20, 20_000) { Point(0.0, 0.0) })
        assertNull(drive(LiftGuard(), 0, 20_000) { Point(0.0, 0.0) })
    }

    @Test fun startOfMotionNeedsAFullWindow() {
        val guard = LiftGuard()
        // Long stop then start: no false positive during acceleration.
        assertNull(drive(guard, 0, 5000) { Point(0.0, 0.0) })
        var reason: String? = null
        for (t in 5200L..7000L step 200) {
            guard.commanded(250, t)
            if (t % 1000 == 0L) reason = reason ?: guard.position(Point(0.0, 0.0), t)
        }
        assertNull(reason)
    }

    @Test fun safetyBitsChangeOnlyMattersWhileMoving() {
        val guard = LiftGuard()
        assertNull(guard.sensors(0, 0))
        assertNull(guard.sensors(1 shl 4, 100)) // stopped: new reference
        guard.commanded(300, 200)
        assertNull(guard.sensors(1 shl 4, 300))
        assertNull(guard.sensors((1 shl 4) or (1 shl 9) or (2 shl 15), 400)) // blades and ultrasonic sensors ignored
        assertNotNull(guard.sensors(1 shl 3 or (1 shl 4), 500))
        assertFalse(guard.moving(5000))
    }

    @Test fun liftCodesAreRecognised() {
        assertTrue(LiftGuard.liftCode(2801)); assertTrue(LiftGuard.liftCode(-1030))
        assertFalse(LiftGuard.liftCode(1014))
        assertEquals("capteur de soulèvement déclenché", LiftGuard.liftLabel(2801))
    }
}
