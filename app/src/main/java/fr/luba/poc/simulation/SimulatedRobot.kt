package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotPose
import kotlin.math.*

/**
 * Simulated robot receiving the same raw commands (forward, rotation) as the LUBA 2: command → effect delay,
 * first-order inertia, friction in translation and rotation, actual gains unknown to the tracking. Measurements of
 * 2026-10-10: delay ≈ 0.6 s, ≈ 0.11 °/s and ≈ 1.08 mm/s per unit, pivot stalling below ≈ 25 units, almost no
 * forward motion below ≈ 30–60 units.
 *
 * Used by the execution bench (tests) and by the obstacle simulation: the robot is driven there by the actual mowing
 * tracking ([PathFollower], [DeadReckoning]), not by ideal kinematics.
 * Flat ground by default; slope through a slide during in-place pivots ([Params.pivotSlide]) and, for the disturbance
 * bench, any ground effect while driving ([Params.ground]: wheel slip, cross-slope drift, wheel snagged by a branch).
 */
class SimulatedRobot(start: RobotPose, private val params: Params = Params()) {
    data class Params(val delayMs: Long = 600, val degPerUnit: Double = 0.11, val metersPerUnit: Double = 0.00108,
                      val tauS: Double = 0.25, val friction: Int = 30, val angularFriction: Int = 20,
                      /** Centre slide (m/s) during an in-place rotation at a point (slope); null: flat ground. */
                      val pivotSlide: ((Point) -> Point)? = null,
                      /** Ground effect at each integration step ([Ground]); null: firm flat ground. */
                      val ground: ((Motion) -> Ground)? = null)

    /** State seen by [Params.ground]: actual pose, speeds (m/s, °/s) and time since the start. */
    data class Motion(val pose: RobotPose, val speedMps: Double, val turnDegreesPerSecond: Double, val elapsedMs: Long)

    /**
     * Ground effect: [traction] scales the forward motion (1: grip, 0: wheels spinning on the spot), [drift] moves the
     * centre (m/s, map frame: cross-slope, sideways slip) and [yawDegreesPerSecond] turns the chassis (one wheel held
     * back by a branch). The tracking only sees the result through the published positions and heading.
     */
    data class Ground(val traction: Double = 1.0, val drift: Point = Point(0.0, 0.0), val yawDegreesPerSecond: Double = 0.0) {
        init { require(traction in 0.0..1.0 && drift.x.isFinite() && drift.y.isFinite() && yawDegreesPerSecond.isFinite()) }
    }

    companion object {
        /** Tracking command period (5 Hz) and integration step. */
        const val PERIOD_MS = 200L
        const val SUBSTEPS = 4
    }

    var x = start.position.x; private set
    var y = start.position.y; private set
    var heading = start.headingDegrees; private set
    private var v = 0.0
    private var w = 0.0
    private var elapsedMs = 0L
    private val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat((params.delayMs / PERIOD_MS).toInt()) { q.addLast(0 to 0) } }

    val pose get() = RobotPose(Point(x, y), heading)
    /** Current actual speeds (m/s, °/s). */
    val speedMps get() = v
    val turnDegreesPerSecond get() = w
    val moving get() = abs(v) > 1e-4 || abs(w) > 0.05

    /**
     * Applies the command [linear]/[angular] for one period ([PERIOD_MS]), in [SUBSTEPS] steps.
     * [accept] is called with each intermediate pose: `false` cancels it (contact), the robot stops dead
     * on the previous pose and the function returns `false`.
     */
    fun step(linear: Int, angular: Int, accept: (RobotPose) -> Boolean = { true }): Boolean {
        pending.addLast(linear to angular); val (l, a) = pending.removeFirst()
        val effectiveLinear = sign(l.toDouble()) * max(0, abs(l) - params.friction)
        val effectiveAngular = sign(a.toDouble()) * max(0, abs(a) - params.angularFriction)
        val dt = PERIOD_MS / 1000.0 / SUBSTEPS
        val k = 1 - exp(-dt / params.tauS)
        repeat(SUBSTEPS) {
            v += (effectiveLinear * params.metersPerUnit - v) * k; w += (effectiveAngular * params.degPerUnit - w) * k
            val ground = params.ground?.invoke(Motion(pose, v, w, elapsedMs)) ?: Ground()
            val h = heading + (w + ground.yawDegreesPerSecond) * dt
            // Slope: the robot turning in place slides (2026-10-15, Jardin d'Eden: 3.5 cm/s at the bottom of the zone).
            val slip = if (abs(w) > 5.0 && abs(v) < 0.03) params.pivotSlide?.invoke(Point(x, y)) else null
            val forward = v * ground.traction * dt
            val nx = x + forward * cos(Math.toRadians(h)) + ((slip?.x ?: 0.0) + ground.drift.x) * dt
            val ny = y + forward * sin(Math.toRadians(h)) + ((slip?.y ?: 0.0) + ground.drift.y) * dt
            elapsedMs += PERIOD_MS / SUBSTEPS
            if (!accept(RobotPose(Point(nx, ny), h))) { halt(); return false }
            heading = h; x = nx; y = ny
        }
        return true
    }

    /** Dead stop (contact): zero speeds, pending commands dropped (queue purged). */
    fun halt() {
        v = 0.0; w = 0.0
        for (i in pending.indices) pending[i] = 0 to 0
    }

    /** Forced move (simulated straight back-off at constant speed), heading unchanged. */
    fun place(position: Point) { x = position.x; y = position.y }
}
