package fr.luba.poc.simulation

import fr.luba.poc.diagnostics.MapNavFollower
import fr.luba.poc.diagnostics.MotionProgressGuard
import fr.luba.poc.map.Point
import java.util.Random
import kotlin.math.*

/**
 * Modelled execution of a prepared route with the same loop as actual mowing
 * ([fr.luba.poc.ble.RobotBle] `followPath`): one range per blade state, STOP between two ranges,
 * commands at 5 Hz on the predicted pose ([DeadReckoning], delay compensated), advance limited while the
 * heading is not confirmed or a pivot is not measured, end of range confirmed by [RangeArrival],
 * deviation and lack of progress checked on the **published** position (1 Hz, RTK noise).
 *
 * Robot: command → effect delay, first-order inertia, friction in translation and rotation, actual
 * gains unknown to the tracking. Measurements of 2026-10-10: delay ≈ 0.6 s, ≈ 0.11 °/s and ≈ 1.08 mm/s per unit,
 * pivot stalling below ≈ 25 units, almost no forward motion below ≈ 30–60 units.
 *
 * Bench model: it does not replace physical validation (ground, slope, wheel slip not modelled).
 */
object RouteExecutionModel {
    data class Robot(val delayMs: Long = 600, val degPerUnit: Double = 0.11, val metersPerUnit: Double = 0.00108,
                     val tauS: Double = 0.25, val friction: Int = 30, val angularFriction: Int = 20,
                     val noiseM: Double = 0.01, val headingNoise: Double = 1.0, val headingError: Double = 0.0,
                     val seed: Long = 1,
                     /** Point published behind the rotation centre (LUBA 2: [DeadReckoning.PUBLISHED_POINT_BEHIND_M]). */
                     val publishedBehindM: Double = 0.0,
                     /** Slide during in-place pivots (slope); null: flat ground. */
                     val pivotSlide: ((Point) -> Point)? = null) {
        override fun toString() = "retard $delayMs ms, %.2f °/s·u, %.2f mm/s·u, frottement $friction/$angularFriction, cap %+.0f°, graine $seed"
            .format(degPerUnit, metersPerUnit * 1000, headingError)
    }

    data class Run(
        /** Total duration and route length. */
        val seconds: Double, val meters: Double,
        /** Maximum deviation of the published position from the route (actual mowing check) and 30 cm overruns. */
        val worst: Double, val violations: Int, val where: String,
        /** RMS lateral deviation of the true position on the long edges, excluding 0.6 m after a vertex. */
        val rmsStraight: Double,
        /** In-place pivots and pivots far from the vertices (correction in the middle of a stripe). */
        val pivots: Int, val midPivots: Int,
        /** Time spent pivoting, stopped by command, in advance limited for lack of a measured heading (after a pivot
         * or at the start) and in slow advance for another cause (< half the set speed: braking, curve). */
        val pivotSeconds: Double, val stoppedSeconds: Double, val unconfirmedSeconds: Double, val slowSeconds: Double,
        /** Blade ranges, range ends (true distance to the point) and approach retries. */
        val ranges: Int, val endErrors: List<Double>, val approaches: Int,
        /** Presumed blockages (progress guard): stop of actual mowing. */
        val blocked: Int,
        /** Calibration reached at the end of the mission (null if the rotation was not calibrated). */
        val calibration: DeadReckoning.Calibration? = null,
        /** Deviation of the robot's true centre from the route (bench ground truth): maximum and RMS, all positions. */
        val trueWorst: Double = 0.0, val trueRms: Double = 0.0, val trueWhere: String = "",
    ) {
        val meanSpeed get() = meters / seconds
        override fun toString() = ("%.0f s, %.3f m/s, max deviation %.3f m (%d > 30 cm), RMS %.3f m, %d pivots (%d in stripe), " +
            "pivot %.0f s, stop %.0f s, heading not measured %.0f s, slow %.0f s, %d ranges, max ends %.2f m, %d retries, %d blockages, worst %s")
            .format(seconds, meanSpeed, worst, violations, rmsStraight, pivots, midPivots, pivotSeconds, stoppedSeconds,
                unconfirmedSeconds, slowSeconds, ranges, endErrors.maxOrNull() ?: 0.0, approaches, blocked, where)
    }

    private fun lateral(p: Point, e: SimEdge): Double {
        val dx = e.b.x - e.a.x; val dy = e.b.y - e.a.y; val l = hypot(dx, dy)
        return if (l < 1e-9) 0.0 else ((p.x - e.a.x) * dy - (p.y - e.a.y) * dx) / l
    }

    /**
     * Follows [edges] from the first to the last vertex, robot placed at the start along the first edge axis
     * (possible heading error [Robot.headingError]). [approach]: base approach point (end of range without
     * [RangeArrival], 5 cm tolerance, like actual mowing).
     */
    fun drive(edges: List<SimEdge>, robot: Robot, speedPercent: Int, approach: Point? = null,
              commandDelayMs: Long = DeadReckoning.ROUTE_COMMAND_DELAY_MS, limitSeconds: Double = 6 * 3600.0,
              /** Step-by-step trace (debugging): called for the edges of [traceEdges]. */
              traceEdges: IntRange? = null, trace: (String) -> Unit = {},
              /** Calibration kept from a previous mission. */
              calibration: DeadReckoning.Calibration? = null,
              /** Published point offset compensated by the tracking (0: published point taken as the centre). */
              compensatedBehindM: Double = robot.publishedBehindM,
              /** Slide map known to the tracking (prediction and corner vertices), null: none. */
              slideField: SlideField? = null,
              /** Slides measured during the mission (learning of [SlideField]). */
              learn: (SlideSample) -> Unit = {}): Run {
        require(edges.isNotEmpty())
        val random = Random(robot.seed)
        // Dynamics shared with the obstacle simulation ([SimulatedRobot]).
        val body = SimulatedRobot(fr.luba.poc.map.RobotPose(edges.first().a, PathFollower.headingOf(edges.first()) + robot.headingError),
            SimulatedRobot.Params(robot.delayMs, robot.degPerUnit, robot.metersPerUnit, robot.tauS, robot.friction, robot.angularFriction, robot.pivotSlide))
        var x = body.x; var y = body.y; var heading = body.heading
        val reckoning = DeadReckoning(commandDelayMs = commandDelayMs, calibration = calibration, publishedBehindM = compensatedBehindM)
        val slideVelocity: ((Point) -> Point?)? = slideField?.let { f -> f::velocity }
        reckoning.slide = slideVelocity
        val meter = PivotSlideMeter()
        var ms = 0L; var fixAt = 0L
        fun publish(): Point {
            val r = Math.toRadians(heading)
            return Point(x - robot.publishedBehindM * cos(r) + random.nextGaussian() * robot.noiseM,
                y - robot.publishedBehindM * sin(r) + random.nextGaussian() * robot.noiseM)
        }
        var published = publish()
        // Position checked by the tracking: centre estimated from the published point.
        var measured = published
        var worst = 0.0; var violations = 0; var where = ""
        var pivots = 0; var midPivots = 0; var blocked = 0; var approaches = 0; var ranges = 0
        var pivotMs = 0L; var stoppedMs = 0L; var unconfirmedMs = 0L; var slowMs = 0L
        var limited = false
        val endErrors = mutableListOf<Double>()
        var squares = 0.0; var samples = 0
        var trueWorst = 0.0; var trueSquares = 0.0; var trueSamples = 0; var trueWhere = ""
        val nominal = PathFollower.linearFor(speedPercent)
        fun step(command: Pair<Int, Int>) {
            body.step(command.first, command.second)
            x = body.x; y = body.y; heading = body.heading
            ms += 200
            when {
                command.first == 0 && command.second != 0 -> pivotMs += 200
                command.first == 0 -> stoppedMs += 200
                limited -> unconfirmedMs += 200
                command.first < nominal / 2 -> slowMs += 200
            }
            if (ms % 1000 == 0L) {
                published = publish(); fixAt = ms
                reckoning.fix(published, heading + random.nextGaussian() * robot.headingNoise, ms)
                measured = reckoning.measured ?: published
                meter.update(measured, ms, reckoning.rotationSlide)?.let(learn)
            }
            check(ms < limitSeconds * 1000) { "tracking blocked at %.0f s".format(ms / 1000.0) }
        }
        reckoning.fix(published, heading, 0)
        measured = reckoning.measured ?: published
        var from = 0
        while (from < edges.size) {
            var to = from + 1
            while (to < edges.size && edges[to].bladesOn == edges[from].bladesOn) to++
            ranges++
            val towardBase = approach != null && edges[to - 1].b == approach
            // Start of range: STOP then blade command, like the robot.
            reckoning.sent(ms, 0, 0); step(0 to 0)
            val follower = PathFollower(edges, from, to, if (towardBase) 0.05 else RangeArrival.TARGET_M).also { it.slide = slideVelocity }
            val arrival = if (towardBase) null else RangeArrival(commandDelayMs)
            val guard = MotionProgressGuard()
            var turning = false
            while (true) {
                val deviation = follower.deviation(measured)
                if (deviation > MapNavFollower.MAX_DEVIATION_M) violations++
                if (deviation > worst) {
                    worst = deviation
                    val e = edges[follower.index]
                    where = "edge ${follower.index}/${edges.size} (%.2f m, blades %s) at %.2f m".format(e.meters, e.bladesOn,
                        PathFollower.progressOf(measured, e) * e.meters)
                }
                val current = edges[follower.index]
                follower.deviation(Point(x, y)).let { d ->
                    if (d > trueWorst) {
                        trueWorst = d
                        val e = edges[follower.index]
                        trueWhere = "edge %d (%.2f m, heading %.0f°, previous %.0f°) at %.2f m, robot heading %.0f°, t=%.0f s, heading confirmed %s".format(follower.index, e.meters,
                            PathFollower.headingOf(e), if (follower.index > 0) PathFollower.headingOf(edges[follower.index - 1]) else 0.0,
                            PathFollower.progressOf(Point(x, y), e) * e.meters, heading, ms / 1000.0, reckoning.headingConfirmed)
                    }
                    trueSquares += d * d; trueSamples++ }
                val along = PathFollower.progressOf(Point(x, y), current) * current.meters
                if (current.meters > 1.5 && along > 0.6 && along < current.meters - 0.3) {
                    val lat = lateral(Point(x, y), current); squares += lat * lat; samples++
                }
                val pose = reckoning.predict(ms)!!
                val limit = if (!reckoning.headingConfirmed) minOf(nominal, DeadReckoning.UNCONFIRMED_LINEAR)
                    else reckoning.postPivotLinear(nominal)
                val c = follower.command(pose.position, pose.headingDegrees, limit, PathFollower.angularLimit(speedPercent),
                    reckoning.degreesPerUnit, reckoning.metersPerUnit, RangeArrival.APPROACH_MIN_LINEAR, deviation, reckoning.rotationCalibrated)
                if (c.done) {
                    when (RangeArrival.settle(arrival, ms, fixAt, measured, follower, edges[to - 1].b)) {
                        null -> { guard.reset(); reckoning.sent(ms, 0, 0); step(0 to 0); continue }
                        false -> continue
                        true -> break
                    }
                }
                if (guard.blocked(measured, fixAt, ms, c.linear)) { blocked++; guard.reset() }
                val pivot = c.linear == 0 && c.angular != 0
                if (pivot && !turning) {
                    pivots++
                    val e = edges[follower.index]
                    val a = PathFollower.progressOf(pose.position, e) * e.meters
                    if (a > 0.4 && a < e.meters - 0.4) midPivots++
                }
                turning = pivot
                limited = limit < nominal
                if (traceEdges != null && follower.index in traceEdges) trace(("t=%7.1f idx=%d true=(%.2f;%.2f;%6.1f°) measured=(%.2f;%.2f) predicted=(%.2f;%.2f;%6.1f°) " +
                    "deviation=%.3f limit=%d cmd=(%d;%d) v=%.2f w=%5.1f conf=%s piv=%s dpu=%.3f off=%.1f").format(ms / 1000.0, follower.index, x, y, heading,
                    measured.x, measured.y, pose.position.x, pose.position.y, pose.headingDegrees, deviation, limit, c.linear, c.angular, body.speedMps, body.turnDegreesPerSecond,
                    reckoning.headingConfirmed, reckoning.pivotUnconfirmed(), reckoning.degreesPerUnit, reckoning.headingOffset))
                reckoning.sent(ms, c.linear, c.angular); step(c.linear to c.angular)
                limited = false
            }
            approaches += arrival?.approaches ?: 0
            endErrors += Point(x, y).distance(edges[to - 1].b)
            from = to
        }
        meter.flush()?.let(learn)
        return Run(ms / 1000.0, edges.sumOf { it.meters }, worst, violations, where,
            if (samples == 0) 0.0 else sqrt(squares / samples), pivots, midPivots,
            pivotMs / 1000.0, stoppedMs / 1000.0, unconfirmedMs / 1000.0, slowMs / 1000.0, ranges, endErrors, approaches, blocked,
            reckoning.calibration, trueWorst, if (trueSamples == 0) 0.0 else sqrt(trueSquares / trueSamples), trueWhere)
    }
}
