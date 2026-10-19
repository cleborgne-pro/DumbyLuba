package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

class MountEstimatorTest {
    private fun angleGap(a: Double, b: Double) = abs(((a - b) % 360 + 540) % 360 - 180)

    /**
     * Back-and-forth survey on the fictional terrain: stop and pivot at the end of each pass, start in 0.6 s up to
     * [speed] m/s, braking at the end of the pass. The phone accelerometer (turned by [mountAngle]°) feels the slope and
     * the robot acceleration, smoothed over 1 s as on the gateway; readings at 1 Hz during the passes.
     */
    private fun survey(mountAngle: Double, speed: Double = 0.3, accelerations: Boolean = true, flat: Boolean = false, lag: Double = 0.0): List<SignalSample> {
        val history = ArrayDeque<Pair<Double, PhoneGravity>>()
        val out = mutableListOf<SignalSample>()
        val dt = 0.01; var t = 0.0
        var gx = 0.0; var gy = 0.0; var gz = PhoneMount.G; var started = false
        val passes = (0 until 7).map { i -> val y = 1.5 + i * 2.0; if (i % 2 == 0) Triple(1.0, 19.0, y) else Triple(19.0, 1.0, y) } +
            (0 until 5).map { i -> val x = 2.0 + i * 4.0; if (i % 2 == 0) Triple(1.0, 13.0, x) else Triple(13.0, 1.0, x) }
        passes.forEachIndexed { index, (from, to, fixed) ->
            val alongY = index >= 7
            val length = abs(to - from); val dir = if (to > from) 1.0 else -1.0
            val heading = if (alongY) (if (dir > 0) 90.0 else 270.0) else (if (dir > 0) 0.0 else 180.0)
            var s = 0.0; var v = 0.0; var nextSample = t + 1.0
            while (s < length) {
                val brake = (length - s) < v * v / (2 * 0.5) + 0.01
                val a = if (brake) -0.5 else if (v < speed) 0.5 else 0.0
                v = (v + a * dt).coerceIn(0.02, speed); s += v * dt; t += dt
                val pos = if (alongY) Point(fixed, from + dir * s) else Point(from + dir * s, fixed)
                val (gxT, gyT) = if (flat) 0.0 to 0.0 else ReliefModel.syntheticGradient(pos)
                val h = Math.toRadians(heading)
                val slopeF = gxT * cos(h) + gyT * sin(h); val slopeR = gxT * sin(h) - gyT * cos(h)
                val g = PhoneTilt.gravity(slopeF, slopeR, mountAngle)
                // Felt force: robot acceleration along its front, in the phone plane.
                val (f, _) = PhoneTilt.axes(mountAngle)
                val ax = g.x + (if (accelerations) a * f.first else 0.0); val ay = g.y + (if (accelerations) a * f.second else 0.0)
                val k = if (!started) 1.0 else 1 - exp(-dt / 1.0)
                gx += k * (ax - gx); gy += k * (ay - gy); gz += k * (g.z - gz); started = true
                history.addLast(t to PhoneGravity(gx, gy, gz))
                while (history.size > 1 && history[1].first <= t - lag) history.removeFirst()
                if (t >= nextSample) {
                    out += SignalSample(pos, (t * 1000).toLong(), -60, null, null, 0, 20, heading, history.first().second)
                    nextSample += 1.0
                }
            }
            // 4 s pivot in place towards the heading of the next pass, no reading recorded: the accelerometer keeps
            // measuring (smoothing and tilt delay carry across the pivot as on the gateway).
            val end = if (alongY) Point(fixed, to) else Point(to, fixed)
            val next = passes.getOrNull(index + 1)
            val nextHeading = when { next == null -> heading; index + 1 >= 7 -> if (next.second > next.first) 90.0 else 270.0
                else -> if (next.second > next.first) 0.0 else 180.0 }
            val turn = ((nextHeading - heading) % 360 + 540) % 360 - 180
            val (gxT, gyT) = if (flat) 0.0 to 0.0 else ReliefModel.syntheticGradient(end)
            var p = 0.0
            while (p < 4.0) {
                p += dt; t += dt
                val h = Math.toRadians(heading + turn * (p / 4.0).coerceAtMost(1.0))
                val g = PhoneTilt.gravity(gxT * cos(h) + gyT * sin(h), gxT * sin(h) - gyT * cos(h), mountAngle)
                val k = 1 - exp(-dt / 1.0)
                gx += k * (g.x - gx); gy += k * (g.y - gy); gz += k * (g.z - gz)
                history.addLast(t to PhoneGravity(gx, gy, gz))
                while (history.size > 1 && history[1].first <= t - lag) history.removeFirst()
            }
        }
        return out
    }

    @Test fun robotStartsAndStopsRevealAnyPhoneOrientationIncludingFrontAndBack() {
        listOf(0.0, 37.0, 90.0, 180.0, 245.0).forEach { truth ->
            val motion = requireNotNull(MountEstimator.motion(survey(truth))) { "angle $truth" }
            // Pivot on a slope: smoothing keeps a trace of it at the start, hence about ten degrees of error.
            assertTrue("true $truth, estimated ${motion.angle}", angleGap(motion.angle, truth) < 20)
            assertTrue(motion.events >= 6)
            val estimate = MountEstimator.estimate(survey(truth))!!
            assertEquals(MountSource.MOTION, estimate.source)
        }
    }

    /**
     * Tilt lagging behind the position (smoothing and sending by the gateway): the start thrust falls on the 2nd reading
     * or later. The orientation found must never be flipped (inverted terrain): right, or none.
     */
    @Test fun delayedTiltNeverFlipsTheOrientation() {
        listOf(0.0, 0.3, 0.6, 1.0, 1.5, 2.0).forEach { lag -> listOf(0.0, 90.0, 180.0, 245.0).forEach { truth ->
            val motion = MountEstimator.motion(survey(truth, lag = lag))
            if (lag <= 0.3) assertNotNull("lag $lag, true $truth", motion)
            motion?.let { assertTrue("lag $lag, true $truth, estimated ${it.angle}", angleGap(it.angle, truth) < 20) }
        } }
    }

    @Test fun automaticOrientationRebuildsTheSameTerrainAsTheRightManualChoice() {
        val samples = survey(90.0)
        val auto = requireNotNull(ReliefModel.result(samples, PhoneMount.AUTO))
        val manual = requireNotNull(ReliefModel.result(samples, PhoneMount.TOP_LEFT))
        assertEquals(MountSource.MOTION, auto.mount.source)
        assertEquals(manual.dropM, auto.dropM, 0.05)
        // Accelerations removed: the terrain stays close to the fictional one despite the starts.
        val truth = manual.heightCells.map { ReliefModel.syntheticHeight(it.point) }
        assertEquals(truth.max() - truth.min(), manual.dropM, 0.08)
        assertTrue(mountDescription(auto.mount).startsWith("Téléphone : haut"))
    }

    @Test fun withoutAccelerationsTheTerrainFixesTheAxisAndTheFrontIsAssumed() {
        val samples = survey(60.0, accelerations = false)
        assertNull(MountEstimator.motion(samples))
        val angle = requireNotNull(MountEstimator.terrain(samples))
        assertTrue("estimated $angle", angleGap(angle, 60.0) < 8)
        assertEquals(MountSource.TERRAIN, MountEstimator.estimate(samples)!!.source)
        // Phone actually turned towards the back: axis found, direction assumed towards the front (documented limit).
        val back = requireNotNull(MountEstimator.terrain(survey(240.0, accelerations = false)))
        assertTrue("estimated $back", angleGap(back, 60.0) < 8)
    }

    @Test fun flatGroundWithoutAccelerationsGivesNoOrientation() {
        val samples = survey(30.0, accelerations = false, flat = true)
        assertNull(MountEstimator.motion(samples))
        assertNull(MountEstimator.terrain(samples))
        assertEquals(MountSource.DEFAULT, MountEstimator.estimate(samples)!!.source)
        assertNull(MountEstimator.estimate(listOf(SignalSample(Point(0.0, 0.0), 0, -60, null, null, 0, 20))))
    }

    @Test fun descriptionsReadNaturally() {
        assertEquals("Téléphone : haut vers l'avant (choisie à la main)", mountDescription(MountEstimate(0.0, MountSource.MANUAL)))
        assertTrue(mountDescription(MountEstimate(35.2, MountSource.MOTION, 24)).contains("à 35° à gauche de l'avant (déduite des démarrages et arrêts du robot, 24 mesures)"))
        assertTrue(mountDescription(MountEstimate(-20.0, MountSource.TERRAIN)).contains("à 20° à droite de l'avant"))
    }
}
