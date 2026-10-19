package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Robot icon during a simulation: one pose every 50 ms. The glide restarts from the displayed pose towards each new pose
 * ([rememberSmoothedPose]); its duration must follow the observed interval.
 */
class PoseSmoothingTest {
    @Test fun durationFollowsTheObservedIntervalUpToTheRealRobotPeriod() {
        assertEquals(POSE_SMOOTHING_MS, poseSmoothingMs(null))
        assertEquals(50L, poseSmoothingMs(50))
        assertEquals(POSE_SMOOTHING_MS, poseSmoothingMs(1000))
        assertEquals(POSE_SMOOTHING_MS, poseSmoothingMs(60_000))
        assertEquals(0L, poseSmoothingMs(-3))
    }

    /** Maximum gap of the displayed icon (16 ms frames) to the followed route: right-angle turn, 0.3 m per pose. */
    private fun worstDisplayGap(duration: (Long) -> Long): Double {
        val path = (0..40).map { Point(it * 0.3, 0.0) } + (1..40).map { Point(12.0, it * 0.3) }
        fun distanceToPath(p: Point) = path.zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }
        var from = path.first(); var to = path.first(); var startedAt = 0L; var length = 1L
        fun shown(now: Long): Point {
            val t = if (length <= 0) 1.0 else min(1.0, max(0.0, (now - startedAt).toDouble() / length))
            return Point(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
        }
        var worst = 0.0
        var now = 0L
        for (target in path.drop(1)) {
            from = shown(now); to = target; startedAt = now; length = duration(50)
            repeat(3) { now += 16; worst = max(worst, distanceToPath(shown(now))) }
            now += 2
        }
        return worst
    }

    @Test fun iconStaysOnTheLineThroughACorner() {
        val fixed = worstDisplayGap { POSE_SMOOTHING_MS }
        val adaptive = worstDisplayGap(::poseSmoothingMs)
        println("Icon: max gap %.2f m with a fixed 950 ms, %.3f m matched to the interval".format(fixed, adaptive))
        assertTrue(fixed > 1.0)
        assertTrue(adaptive < 0.05)
    }
}
