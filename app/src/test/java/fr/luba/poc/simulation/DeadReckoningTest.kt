package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

class DeadReckoningTest {
    @Test fun `predicts the pivot between two positions at 1 Hz`() {
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 0.0, 0)
        r.sent(0, 0, 100)
        val pose = r.predict(1000)!!
        assertEquals(100 * DeadReckoning.DEFAULT_DEGREES_PER_UNIT, pose.headingDegrees, 1e-6)
        assertEquals(0.0, pose.position.distance(Point(0.0, 0.0)), 1e-9)
        // Bounded extrapolation: a missing position does not make the heading turn indefinitely.
        assertEquals(r.predict(1500)!!.headingDegrees, r.predict(9000)!!.headingDegrees, 1e-9)
    }

    @Test fun `calibrates on the measured positions and stays bounded`() {
        val r = DeadReckoning()
        var heading = 0.0
        r.fix(Point(0.0, 0.0), heading, 0)
        for (second in 1..10) {
            r.sent((second - 1) * 1000L, 0, 200)
            heading += 200 * 0.06 // actual robot twice as slow as the initial gain
            r.fix(Point(0.0, 0.0), heading % 360, second * 1000L)
        }
        assertEquals(0.06, r.degreesPerUnit, 0.005)
        val fast = DeadReckoning()
        fast.fix(Point(0.0, 0.0), 0.0, 0); fast.sent(0, 0, 100); fast.fix(Point(0.0, 0.0), 179.0, 1000)
        assertTrue(fast.degreesPerUnit <= DeadReckoning.DEGREES_RANGE.endInclusive)
    }

    @Test fun `predicted straight-line advance`() {
        val r = DeadReckoning()
        r.fix(Point(1.0, 2.0), 90.0, 0)
        r.sent(0, 200, 0)
        val pose = r.predict(1000)!!
        assertEquals(1.0, pose.position.x, 1e-6)
        assertEquals(2.0 + 200 * DeadReckoning.DEFAULT_METERS_PER_UNIT, pose.position.y, 1e-6)
    }

    @Test fun `realigns the published heading on the travelled direction then follows the robot realignment`() {
        // Survey of 2026-10-10: published heading 139–142° while the robot drove at ≈ 49°.
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 139.0, 0)
        assertFalse(r.headingConfirmed)
        r.sent(0, 500, 0)
        val moved = Point(0.35 * kotlin.math.cos(Math.toRadians(49.0)), 0.35 * kotlin.math.sin(Math.toRadians(49.0)))
        r.fix(moved, 141.0, 1000)
        assertTrue(r.headingConfirmed)
        assertEquals(49.0, r.heading(141.0), 3.0)
        // The robot realigns its heading by itself (77° jump without commanded rotation): gap dropped, to be confirmed again.
        r.sent(1000, 0, 0)
        r.fix(moved, 64.0, 2000)
        assertFalse(r.headingConfirmed)
        assertEquals(64.0, r.heading(64.0), 1e-9)
    }

    @Test fun `a heading gap taken over from the previous drive is not replaced by a short noisy line`() {
        // Previous drive: published heading 1° under the travelled direction. New drive: 16 cm of straight line whose ends
        // are 3 cm off across it (two RTK draws of 1.5 cm: 10.6° measured off), as on the bench before the outline.
        val r = DeadReckoning()
        r.inheritHeading(1.0, 0.8)
        assertTrue(r.headingConfirmed)
        assertEquals(0.8, r.headingSupportM, 1e-9)
        assertEquals(31.0, r.heading(30.0), 1e-9)
        r.fix(Point(0.0, 0.0), 30.0, 0)
        r.sent(0, 150, 0)
        val course = Math.toRadians(31.0)
        r.fix(Point(0.16 * kotlin.math.cos(course) - 0.03 * kotlin.math.sin(course), 0.16 * kotlin.math.sin(course) + 0.03 * kotlin.math.cos(course)), 30.0, 1000)
        // Weighted refinement only (≈ 2° of the 10° measured), instead of the whole gap of a first confirmation.
        assertEquals(31.0, r.heading(30.0), 3.0)
        val fresh = DeadReckoning()
        fresh.fix(Point(0.0, 0.0), 30.0, 0)
        fresh.sent(0, 150, 0)
        fresh.fix(Point(0.16 * kotlin.math.cos(course) - 0.03 * kotlin.math.sin(course), 0.16 * kotlin.math.sin(course) + 0.03 * kotlin.math.cos(course)), 30.0, 1000)
        assertTrue(fresh.headingConfirmed)
        assertTrue("first confirmation adopts the noisy gap: ${fresh.heading(30.0)}", kotlin.math.abs(fresh.heading(30.0) - 31.0) > 5.0)
    }

    @Test fun `after the first confirmation the gap is measured again over the whole straight line`() {
        // Published heading 0°, robot driving at 4°; the second position carries 3 cm of RTK noise across the line.
        val r = DeadReckoning()
        val course = Math.toRadians(4.0)
        fun on(m: Double, across: Double = 0.0) = Point(m * kotlin.math.cos(course) - across * kotlin.math.sin(course), m * kotlin.math.sin(course) + across * kotlin.math.cos(course))
        r.fix(on(0.0), 0.0, 0)
        r.sent(0, 150, 0)
        r.fix(on(0.16, 0.03), 0.0, 1000)
        assertTrue(r.headingConfirmed)
        assertEquals(14.6, r.headingOffset, 0.5) // 4° + 10.6° of noise, as the first confirmation always did
        for (second in 2..6) r.fix(on(0.16 * second), 0.0, second * 1000L)
        // 96 cm of line: the noise of one end weighs 2° no more, the gap is the travelled direction.
        assertEquals(4.0, r.headingOffset, 0.2)
        assertEquals(0.96, r.headingSupportM, 0.01)
        // A pivot ends that line; later lines only refine the gap with the usual weighted gain.
        r.sent(6000, 0, 200)
        r.fix(on(0.96), 20.0, 7000)
        assertEquals(4.0, r.headingOffset, 0.2)
    }

    @Test fun `a robot pushed sideways during that straight line is not taken for a heading gap`() {
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 0.0, 0)
        r.sent(0, 150, 0)
        r.fix(Point(0.16, 0.0), 0.0, 1000)
        r.fix(Point(0.32, 0.0), 0.0, 2000)
        assertEquals(0.0, r.headingOffset, 1e-6)
        // Pushed 40 cm to the side between two positions: the whole line would say 34°.
        r.fix(Point(0.48, 0.4), 0.0, 3000)
        assertEquals(0.0, r.headingOffset, 1e-6)
        assertTrue(r.headingConfirmed)
    }

    @Test fun `a heading gap is only taken over before the first position and a realignment still drops it`() {
        val r = DeadReckoning()
        r.inheritHeading(-8.0, 0.8)
        r.fix(Point(0.0, 0.0), 100.0, 0)
        assertThrows(IllegalStateException::class.java) { r.inheritHeading(0.0, 0.8) }
        r.sent(0, 0, 0)
        r.fix(Point(0.0, 0.0), 30.0, 1000) // 70° without any commanded rotation: robot realignment
        assertFalse(r.headingConfirmed)
        assertEquals(0.0, r.headingOffset, 1e-9)
    }

    @Test fun `a commanded pivot is not taken for a realignment`() {
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 0.0, 0)
        r.sent(0, 0, 263)
        r.fix(Point(0.0, 0.0), 28.0, 1000) // ≈ 0.107 °/s per unit, measured on 2026-10-10
        assertEquals(0.0, r.headingOffset, 1e-9)
    }

    /** Straight line at heading 0: one position per second, [course] = direction of the measured movement. */
    private fun straight(r: DeadReckoning, at: Long, from: Point, course: Double, meters: Double): Point {
        r.sent(at - 1000, 200, 0)
        val to = Point(from.x + meters * kotlin.math.cos(Math.toRadians(course)), from.y + meters * kotlin.math.sin(Math.toRadians(course)))
        r.fix(to, 0.0, at)
        return to
    }

    /**
     * Debug mowing of 2026-10-10: at 0.13 m/s, a single noisy position (1 cm) gave a travelled direction 20–25° off
     * the heading; the gap was adopted at once and the robot pivoted in the middle of a stripe.
     * The direction is measured over at least 15 cm of straight line: the noise of an isolated position cancels out.
     */
    @Test fun `a single noisy position does not skew the confirmed heading`() {
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 0.0, 0)
        var p = straight(r, 1000, Point(0.0, 0.0), 0.0, 0.2)
        assertTrue(r.headingConfirmed)
        p = straight(r, 2000, p, 0.0, 0.2)
        // Published position offset by 3 cm sideways (RTK noise), robot still on the axis.
        r.sent(2000, 200, 0)
        r.fix(Point(p.x + 0.13, p.y + 0.03), 0.0, 3000)
        p = Point(p.x + 0.13, p.y)
        p = straight(r, 4000, p, 0.0, 0.13)
        p = straight(r, 5000, p, 0.0, 0.13)
        assertEquals("isolated position ignored", 0.0, r.headingOffset, 2.0)
        // Small actual gap: partial correction, weighted by the distance travelled.
        p = straight(r, 6000, p, 6.0, 0.15)
        p = straight(r, 7000, p, 6.0, 0.15)
        assertTrue("gap %.2f°".format(r.headingOffset), r.headingOffset > 0.5 && r.headingOffset < 4.0)
        // Gap confirmed by two successive measurements (actual robot realignment): adopted.
        repeat(6) { k -> p = straight(r, 8000L + 1000L * k, p, -30.0, 0.1) }
        assertEquals(-30.0, r.heading(0.0) - 360, 4.0)
    }

    @Test fun `the acceleration at the start does not skew the speed calibration`() {
        // Actual delay longer than the compensation: the first second of command moved almost nothing.
        val r = DeadReckoning(commandDelayMs = 600)
        r.fix(Point(0.0, 0.0), 0.0, 0)
        r.sent(0, 500, 0)
        r.fix(Point(0.02, 0.0), 0.0, 1000)
        assertEquals(DeadReckoning.DEFAULT_METERS_PER_UNIT, r.metersPerUnit, 1e-12)
        // Steady state: calibration.
        r.fix(Point(0.02 + 0.54, 0.0), 0.0, 2000)
        r.fix(Point(0.02 + 1.08, 0.0), 0.0, 3000)
        assertTrue(r.metersPerUnit > DeadReckoning.DEFAULT_METERS_PER_UNIT)
    }

    /**
     * Execution bench of 2026-10-10 (Figuier, start): 9.5 cm travelled between two positions with 1 cm noise gave a
     * direction wrong by 32°; the heading was "confirmed" wrong by 37°, the robot pivoted wrongly.
     */
    @Test fun `the first heading confirmation requires 15 cm of straight line unless the gap is obvious`() {
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 0.0, 0)
        // First second: 9.5 cm, measured direction skewed by 30° by the noise.
        r.sent(0, 120, 0)
        r.fix(Point(0.095 * kotlin.math.cos(Math.toRadians(30.0)), 0.095 * kotlin.math.sin(Math.toRadians(30.0))), 0.0, 1000)
        assertFalse("a single short measurement does not confirm a 30° gap", r.headingConfirmed)
        // Next second: the cumulative chord (≈ 19 cm) gives the true direction, within a few degrees.
        r.sent(1000, 120, 0)
        r.fix(Point(0.19, 0.0), 0.0, 2000)
        assertTrue(r.headingConfirmed)
        assertEquals(0.0, ((r.headingOffset + 540) % 360) - 180, 16.0)
        assertTrue("adopted gap %.1f°".format(r.headingOffset), kotlin.math.abs(r.headingOffset) < 16.0)
        // Published heading wrong by 90° (survey of 2026-10-10): adopted from the first measurement, even a short one.
        val wrong = DeadReckoning()
        wrong.fix(Point(0.0, 0.0), 90.0, 0)
        wrong.sent(0, 120, 0)
        wrong.fix(Point(0.09, 0.0), 90.0, 1000)
        assertTrue(wrong.headingConfirmed)
        assertEquals(0.0, wrong.heading(90.0), 1.0)
    }

    /** Execution bench of 2026-10-10: at 5 cm/s, less than 8 cm between two positions, 20° heading error never realigned. */
    @Test fun `the confirmed heading also realigns at low speed on the cumulative straight line`() {
        val r = DeadReckoning()
        r.fix(Point(0.0, 0.0), 0.0, 0)
        var p = straight(r, 1000, Point(0.0, 0.0), 0.0, 0.2)
        assertTrue(r.headingConfirmed)
        // The robot actually drives 10° off its assumed heading, 5 cm per second.
        repeat(12) { k -> p = straight(r, 2000L + 1000L * k, p, 10.0, 0.05) }
        assertTrue("corrected gap %.1f°".format(r.headingOffset), r.headingOffset > 4.0)
    }

    /**
     * End of pivot: small commands while the robot is still turning (actual delay, inertia). These intervals skewed
     * the gain; only an interval of steady rotation or a complete rotation between two positions at rest calibrate
     * it.
     */
    @Test fun `the rotation calibration ignores the residual rotation at the end of a pivot`() {
        val r = DeadReckoning(commandDelayMs = 600)
        r.fix(Point(0.0, 0.0), 0.0, 0)
        r.fix(Point(0.0, 0.0), 0.0, 1000) // at rest
        // 2 s pivot at 300 units then end of pivot at 40 units; actual robot at 0.10 °/s·u, 0.8 s delay.
        for (k in 0 until 10) r.sent(1000L + 200 * k, 0, 300)
        r.sent(3000, 0, 40); r.sent(3200, 0, 40); r.sent(3400, 0, 0)
        val real = mapOf(2000L to 6.0, 3000L to 36.0, 4000L to 60.8, 5000L to 61.6, 6000L to 61.6)
        for ((at, heading) in real) {
            r.fix(Point(0.0, 0.0), heading, at)
            assertTrue("gain %.3f at $at ms".format(r.degreesPerUnit), r.degreesPerUnit <= DeadReckoning.DEFAULT_DEGREES_PER_UNIT + 1e-9)
        }
        // Complete rotation between two rests: 61.6° for 616 units·s, gain brought closer to 0.10.
        assertTrue(r.rotationCalibrated)
        assertTrue("gain %.3f".format(r.degreesPerUnit), r.degreesPerUnit in 0.105..0.118)
    }

    @Test fun `normal advance after a pivot only if the last pivots were well predicted`() {
        val r = DeadReckoning(commandDelayMs = 600)
        var heading = 0.0
        var t = 0L
        r.fix(Point(0.0, 0.0), heading, t)
        /** 1 s pivot at 250 units, commands at 5 Hz; actual robot at [gain] °/s·u, 0.6 s delay. */
        fun pivot(gain: Double) {
            for (k in 0 until 5) r.sent(t + 200 * k, 0, 250)
            r.sent(t + 1000, 0, 0)
            assertEquals("advance allowed during the pivot", if (r.pivotTrusted) 500 else DeadReckoning.POST_PIVOT_LINEAR, r.postPivotLinear(500))
            r.fix(Point(0.0, 0.0), heading + 0.4 * 250 * gain, t + 1000)
            heading += 250 * gain
            r.fix(Point(0.0, 0.0), heading, t + 2000)
            t += 2000
        }
        assertFalse(r.pivotTrusted)
        repeat(3) { pivot(0.12) }
        assertTrue(r.pivotTrusted)
        pivot(0.12)
        // Actual pivot 50 % wider than expected: 9° error at the position confirming it.
        pivot(0.18)
        assertFalse("a badly predicted pivot restores the slow advance", r.pivotTrusted)
        r.sent(t, 0, 250)
        assertEquals(DeadReckoning.POST_PIVOT_LINEAR, r.postPivotLinear(500))
    }

    @Test fun `the calibration of a previous mission is reused and bounded`() {
        assertNull(DeadReckoning().calibration)
        assertFalse(DeadReckoning().rotationCalibrated)
        val reused = DeadReckoning(calibration = DeadReckoning.Calibration(0.0011, 0.105))
        assertTrue(reused.rotationCalibrated)
        assertEquals(0.105, reused.degreesPerUnit, 1e-12)
        assertEquals(DeadReckoning.Calibration(0.0011, 0.105), reused.calibration)
        // Aberrant values (corrupted preferences): bounded like the online calibration.
        val bounded = DeadReckoning(calibration = DeadReckoning.Calibration(1.0, 9.0))
        assertEquals(DeadReckoning.METERS_RANGE.endInclusive, bounded.metersPerUnit, 1e-12)
        assertEquals(DeadReckoning.DEGREES_RANGE.endInclusive, bounded.degreesPerUnit, 1e-12)
    }
}
