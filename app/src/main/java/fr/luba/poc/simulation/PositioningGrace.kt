package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.protocol.SelfCheck

/**
 * Ride-through of a short positioning loss during a route tracking (Fold, Android gateway, ESP32 gateway in gwcore).
 *
 * Under a raised play house of the Aire de jeux (2026-10-25), the robot published self-check 41 "weak satellite signal"
 * (RTK float, positioning level 0) for one second at 15:38:21 and 15:51:26, then for two minutes at 15:38:40: the
 * tracking stopped at once each time. The robot itself kept publishing moving positions and accepted the speed
 * commands (driven out with the joystick at 15:40:19, protection still raised): only the tracking gave up.
 *
 * A positioning condition ([SelfCheck.positioning]) with the robot still in manual control (state 20) is now ridden
 * through for at most [MAX_MS] and [MAX_M] travelled from where it appeared, with every other check unchanged
 * (fresh positions, deviation, corridor and obstacle guards, bumper, blades). Beyond either bound, or for any other
 * protection, the usual stop and pause ([ProtectionPause]). A nominal self-check clears the grace.
 */
class PositioningGrace(private val maxMs: Long = MAX_MS, private val maxM: Double = MAX_M) {
    private var since: Long? = null
    private var from: Point? = null
    /** The grace of the current loss is spent: the protection stops the tracking until the positioning comes back. */
    private var spent = false

    /** True while a loss is being ridden through. */
    val active: Boolean get() = since != null && !spent

    /**
     * Protection raised by [robotState]/[selfCheck] at [now] (ms) with the robot at [position]: true to keep tracking
     * (positioning condition within the bounds), false for the usual protection stop.
     */
    fun rides(now: Long, robotState: Int?, selfCheck: Long?, position: Point?): Boolean {
        if (SelfCheck.nominal(selfCheck)) { reset(); return false }
        if (spent || robotState != MANUAL_STATE || !SelfCheck.positioning(selfCheck) || position == null) return false
        val start = since ?: now.also { since = it; from = position }
        if (now - start > maxMs || position.distance(from ?: position) > maxM) { spent = true; return false }
        return true
    }

    /** Elapsed time (ms) and distance (m) of the current ride-through, for the log. */
    fun elapsedMs(now: Long): Long = since?.let { now - it } ?: 0L
    fun travelledM(position: Point?): Double = from?.let { f -> position?.distance(f) } ?: 0.0

    fun reset() { since = null; from = null; spent = false }

    companion object {
        /** Longest ride-through of one positioning loss. */
        const val MAX_MS = 20_000L
        /** Longest distance from where the loss appeared. */
        const val MAX_M = 5.0
        private const val MANUAL_STATE = 20
        const val EVENT = "route_positioning_grace"
    }
}
