package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Commands
import kotlin.math.*

/**
 * Continuous tracking of a sequence of prepared edges (same blade state), without STOP at the vertices.
 *
 * Curved borders are cut into hundreds of edges a few centimetres long: stopping, re-sending the blades and pivoting
 * at each one made actual mowing very slow, and an edge shorter than the arrival radius could be "reached" before the
 * blades were confirmed.
 * Here the robot follows the line: reference heading of the route ahead of it, corrected by the lateral offset
 * (convergence damped over [CONVERGE_M]), on the pose predicted at the moment the command takes effect. In curves the
 * reference is the tangent under the robot and the rotation of the curve is commanded directly (feed-forward,
 * [CURVE_TANGENT_DEGREES]). It only pivots in place at sharp corners (> [CORNER_DEGREES]), at the actual vertex, brakes
 * at constant deceleration before each stop and slows down in tight curves ([curveSpeed]) and just after them.
 * The route deviation, chassis envelope and other checks remain the caller's.
 */
class PathFollower(private val edges: List<SimEdge>, private val start: Int, private val end: Int, private val finalArrivalM: Double) {
    companion object {
        const val CORNER_DEGREES = 30.0
        const val ARRIVAL_M = 0.15
        const val PIVOT_ENTER_DEGREES = 25.0
        const val PIVOT_EXIT_DEGREES = 6.0
        /** Survey of 2026-10-10: below ≈ 25 units the LUBA 2 pivot stalls or turns at 1–3 °/s. */
        const val MIN_PIVOT = 35
        const val MIN_LINEAR = 20
        /** Target pivot speed per degree of error (s⁻¹), applied to the predicted heading. */
        const val PIVOT_GAIN = 1.5
        /** Heading correction while moving forward (s⁻¹). */
        const val STEER_GAIN = 1.5
        /** Convergence distance towards the line: target heading = reference − atan(offset / [CONVERGE_M]). */
        const val CONVERGE_M = 0.8
        /** Above 0.4 m/s, convergence over [CONVERGE_S] seconds of travel: constant damping. */
        const val CONVERGE_S = 2.0
        /** Reference heading: direction of the route over this distance ahead of the robot (curved borders). */
        const val PREVIEW_M = 0.25
        /** Pivot along the line only if the lateral offset requires more than this angle. */
        const val PIVOT_LATERAL_DEGREES = 45.0
        /** Target deceleration before a stop (m/s²) and stop margin before the vertex. */
        const val DECELERATION = 0.25
        const val STOP_MARGIN_M = 0.08
        const val RANGE_END_DECELERATION = 0.15
        /** Window for measuring the curvature ahead of the robot (speed in curves, [curveSpeed]). */
        const val CURVE_WINDOW_M = 0.5
        /**
         * Curve tracking (turn tracking plan, step C, docs/TRACKING_TURNS_AND_BORDER_CONTACTS.md): above this cumulative
         * turn over [CURVE_WINDOW_M] ahead, the reference heading is the tangent of the route at the foot of the
         * perpendicular instead of the [PREVIEW_M] chord (which led the tangent by ≈ 20° on a 0.35 m arc and settled the
         * robot inside the arc, then overshot at the exit), and the rotation the curve needs is commanded directly
         * (feed-forward, [CURVE_LOCAL_M]). Straight stretches keep the chord, unchanged.
         */
        const val CURVE_TANGENT_DEGREES = 3.0
        /**
         * Arc vertex: one of its edges shorter than this (rolling U-turns 2.4 cm, outer lap 4 cm, painted borders 7 cm,
         * transit links 14 cm). The chamfered corners of the map sync outlines (16.5 cm edges) keep the chord: driven as
         * curves they swung the chassis out of the recording corridor (MapSyncBenchTest); at 10 cm the transit links of
         * the bench lost the curve tracking (6 missions out of 175 beyond 30 cm at 100 % instead of 3).
         */
        const val CURVE_ARC_EDGE_M = 0.15
        /**
         * Share of the rotation limit given to the curve itself ([curveSpeed]): the speed in a curve is the one at which
         * the feed-forward rotation stays under this share. Before the feed-forward, 0.6 × half of the limit (30 %) was
         * kept for a proportional correction that had to carry the whole curve: 0.07 m/s on the rolling U-turns of
         * 2026-10-28. Bench sweep 0.4 / 0.5 / 0.6: see docs/TRACKING_EXECUTION.md.
         */
        const val CURVE_FF_SHARE = 0.6
        /**
         * Rotation (°/s) of a curve at most, whatever the speed setting: a delay off by 0.2 s turns the robot by this rate
         * × 0.2 s too early or too late at the entry and exit of a curve, a gain off by 20 % misses 20 % of it. Bench of
         * 2026-10-28 at 100 % (robot 0.8 s and 0.09 °/s·u against 0.6 s and 0.108 believed), first version of the curve
         * tracking: 32 °/s, 6 missions out of 175 beyond 30 cm and 28 cm at the exit of a painted curve of 2026-10-10;
         * 25 °/s, 6; 22 °/s, 1. The 63 % setting gives 23 °/s with the measured gain (0.11 °/s·u).
         */
        const val CURVE_MAX_RATE = 22.0
        /** Steering bound in a curve (share of the rotation limit): feed-forward plus the proportional correction. */
        const val CURVE_STEER_LIMIT = 0.8
        /** Feed-forward curvature: signed turn of the route within this distance on each side of the foot. */
        const val CURVE_LOCAL_M = 0.1
        /**
         * Feed-forward lead: rotation response of the LUBA 2 after the command delay (first-order, ≈ 0.25 s: surveys of
         * 2026-10-10, [DeadReckoning] predicts the delay but not this lag).
         */
        const val CURVE_LEAD_S = 0.25
        /** Length around a vertex over which its turn is spread in the tangent reference (short edges: half of each). */
        const val CURVE_BLEND_M = 0.1
        /**
         * In a curve (turn over [CURVE_TANGENT_DEGREES] ahead) and within [CURVE_PIVOT_LATERAL_M] of the line, in-place
         * pivot only beyond this heading error: the arc is followed, not cut into pivots (bench of 2026-10-28: 3 to 6
         * pivots per rolling U).
         */
        const val CURVE_PIVOT_DEGREES = 40.0
        const val CURVE_PIVOT_LATERAL_M = 0.15
        /** Exit of a curve: speed of the curve kept over this distance after its last curved vertex, then raised to the
         * set speed over [CURVE_EXIT_RAMP_M]. */
        const val CURVE_EXIT_HOLD_M = 0.3
        const val CURVE_EXIT_RAMP_M = 0.5
        /**
         * Rotation assumed in curves as long as the robot's rotation gain is not calibrated
         * ([DeadReckoning.rotationCalibrated]): execution bench of 2026-10-10, robot turning 25 % less than expected,
         * curve exit beyond 30 cm on the first borders at 100 %.
         */
        const val UNCALIBRATED_TURN_SHARE = 0.7
        /** Vertex of a sharp corner reached at this remainder along the edge (predicted pose). */
        const val CORNER_REACH_M = 0.04
        /**
         * Plateau at the minimum approach speed just before the vertex of a sharp corner: braking aims for this speed
         * [CORNER_PLATEAU_M] before [CORNER_REACH_M], instead of stopping [STOP_MARGIN_M] before the vertex followed by
         * a slow approach (≈ 1 s lost at each corner, twice per U-turn).
         * The plateau keeps the overshoot below the test thresholds when the actual delay reaches 1 s.
         */
        const val CORNER_PLATEAU_M = 0.02
        /** Anticipation time of the target passed to the caller (envelope and obstacle checks). */
        const val LOOKAHEAD_S = 1.2
        /** Maximum lag of the measured position behind the predicted pose, in route metres (≈ 1.6 s at 0.5 m/s). */
        const val LAG_M = 1.0
        /**
         * Governor on the measured deviation: beyond [GOVERNOR_START_M], forward speed reduced linearly down to half
         * the set speed at [GOVERNOR_FULL_M] (safety stop at 30 cm). Execution bench of 2026-10-10: at 100 %, robots
         * with misestimated delay or rotation gain, curve exits beyond 30 cm. Not slower: the heading must stay
         * measurable while driving.
         */
        const val GOVERNOR_START_M = 0.12
        const val GOVERNOR_FULL_M = 0.25
        /**
         * Pivot on a slope ([slide]): the vertex of a sharp corner is targeted earlier (or later) by the share of the
         * slide expected during the following pivot along the edge, by at most [MAX_SLIDE_SHIFT_M]. Pivot duration
         * estimated at maximum rotation, plus [PIVOT_SETTLE_S] (acceleration, slowed end of pivot).
         */
        const val MAX_SLIDE_SHIFT_M = 0.15
        const val PIVOT_SETTLE_S = 0.8

        /** Route forward speed: 100 % = 500 units (≈ 0.48 m/s measured), 30 % = 150. */
        fun linearFor(speedPercent: Int): Int = speedPercent.coerceIn(10, 100) * 5
        /** Maximum rotation: 210 units at 10 % up to 450 (protocol bound, ≈ 50 °/s) at 100 %. */
        fun angularLimit(speedPercent: Int): Int =
            (183 + speedPercent.coerceIn(10, 100) * 267 / 100).coerceAtMost(Commands.MAX_ANGULAR_SPEED)
        fun headingOf(edge: SimEdge) = Math.toDegrees(atan2(edge.b.y - edge.a.y, edge.b.x - edge.a.x))
        /** Progress of [p] along [e]: 0 at [SimEdge.a], 1 at [SimEdge.b]. */
        fun progressOf(p: Point, e: SimEdge): Double {
            val dx = e.b.x - e.a.x; val dy = e.b.y - e.a.y
            val l2 = dx * dx + dy * dy
            return if (l2 < 1e-12) 1.0 else ((p.x - e.a.x) * dx + (p.y - e.a.y) * dy) / l2
        }
        fun turnDegrees(a: SimEdge, b: SimEdge) = abs(((headingOf(b) - headingOf(a) + 540) % 360) - 180)

        /**
         * Speed (m/s) at which a curve of [curvature] (°/m) is driven with the rotation share [CURVE_FF_SHARE] of
         * [angular] (raw units, [degreesPerUnit]); [UNCALIBRATED_TURN_SHARE] of it while the rotation gain is not
         * calibrated. Shared by the tracking and the duration model of the Turn Optimizer
         * ([fr.luba.poc.map.TurnOptimizer.drive]), so that a manoeuvre is planned at the speed it is driven.
         */
        fun curveSpeed(curvature: Double, angular: Int, degreesPerUnit: Double, rotationCalibrated: Boolean = true): Double =
            if (curvature <= 1e-6) Double.POSITIVE_INFINITY
            else min(angular * degreesPerUnit * CURVE_FF_SHARE, CURVE_MAX_RATE) * (if (rotationCalibrated) 1.0 else UNCALIBRATED_TURN_SHARE) / curvature

        /**
         * Speed limit (m/s) [behind] metres after the last curved vertex of a curve driven at [hold]: [hold] over
         * [CURVE_EXIT_HOLD_M], then up to [setSpeed] over [CURVE_EXIT_RAMP_M]; no limit beyond. Shared by the tracking
         * and [fr.luba.poc.map.TurnOptimizer.drive].
         */
        fun exitLimit(behind: Double, hold: Double, setSpeed: Double): Double = when {
            hold >= setSpeed || behind >= CURVE_EXIT_HOLD_M + CURVE_EXIT_RAMP_M -> Double.POSITIVE_INFINITY
            behind <= CURVE_EXIT_HOLD_M -> hold
            else -> hold + (setSpeed - hold) * (behind - CURVE_EXIT_HOLD_M) / CURVE_EXIT_RAMP_M
        }

        /**
         * Tight rounded turn: successive vertices turning the same way whose sum exceeds [CORNER_DEGREES] over at most
         * this route length. Execution bench of 2026-10-10: rounded no-go corners (≈ 55° over 7 cm), impossible to
         * follow while driving even at minimum speed (turning radius ≥ 0.3 m); the robot crawled at 5 cm/s and
         * deviated up to 30 cm.
         */
        const val ROUNDED_CORNER_M = 0.15

        /**
         * Stop vertices of the range [start, end): sharp corner (> [CORNER_DEGREES]) or tight rounded turn
         * ([ROUNDED_CORNER_M]): as soon as the sum exceeds [CORNER_DEGREES], a stop at the vertex where it reaches half
         * of that sum; the pivot aims at the rest of the route and the robot cuts the rounding by 1 to 2 cm at most.
         * Index i: vertex at the end of edge i.
         */
        fun stopVertices(edges: List<SimEdge>, start: Int, end: Int): BooleanArray {
            val stops = BooleanArray(edges.size)
            fun signed(i: Int) = ((headingOf(edges[i + 1]) - headingOf(edges[i]) + 540) % 360) - 180
            var i = start
            while (i < end - 1) {
                val t = signed(i)
                if (abs(t) > CORNER_DEGREES) { stops[i] = true; i++; continue }
                if (abs(t) < 0.5) { i++; continue }
                var sum = t; var j = i; var length = 0.0
                while (abs(sum) <= CORNER_DEGREES && j + 1 < end - 1) {
                    length += edges[j + 1].meters
                    val u = signed(j + 1)
                    if (length > ROUNDED_CORNER_M || u * t <= 0 || abs(u) > CORNER_DEGREES) break
                    sum += u; j++
                }
                if (abs(sum) <= CORNER_DEGREES) { i++; continue }
                var k = i; var acc = signed(i)
                while (k < j && abs(acc) < abs(sum) / 2) { k++; acc += signed(k) }
                stops[k] = true
                i = j + 1
            }
            return stops
        }
    }

    data class Command(val linear: Int, val angular: Int, val done: Boolean, val target: Point, val stopDistanceM: Double)

    init {
        require(start in 0 until end && end <= edges.size) { tr("Invalid edge range", "Plage d'arêtes invalide") }
        require(finalArrivalM.isFinite() && finalArrivalM in 0.01..ARRIVAL_M)
    }

    /** Followed edge: used for display, the deviation check and the resume after recharge. */
    var index = start
        private set
    private var turning = false
    /** In-place pivot commanded by the last command ([PivotSlide]: slide tolerated during and after). */
    val pivoting: Boolean get() = turning

    /**
     * Robot standing still: faces the first edge in place (within [PIVOT_EXIT_DEGREES]) before moving forward, instead of
     * an arc whenever the misalignment stays under [PIVOT_ENTER_DEGREES]. For a straight line that ends on a safety limit
     * (join onto an outline of the map sync), where the arc swings the chassis off the line towards that limit.
     */
    fun alignFirst() { turning = true }

    /** Expected slide during a pivot at a point (m/s, [SlideField.velocity]); null: none. */
    var slide: ((Point) -> Point?)? = null

    /**
     * Expected slide (m) along edge [i] during the pivot at its vertex, sharp corner only (not at the end of the
     * range): positive, the robot will slide forward and stops that much earlier.
     */
    private fun slideAlong(i: Int, degreesPerUnit: Double, angular: Int): Double {
        val field = slide ?: return 0.0
        if (i + 1 >= end || !stops[i]) return 0.0
        val v = field(edges[i].b) ?: return 0.0
        val rate = max(angular * degreesPerUnit, 5.0)
        val seconds = (turnDegrees(edges[i], edges[i + 1]) / rate + PIVOT_SETTLE_S).coerceIn(0.5, 6.0)
        return (alongAxis(v, headingOf(edges[i])) * seconds).coerceIn(-MAX_SLIDE_SHIFT_M, MAX_SLIDE_SHIFT_M)
    }

    private val stops = stopVertices(edges, start, end)

    /** The vertex at the end of edge i requires a stop: end of range, sharp corner or tight rounded turn. */
    private fun corner(i: Int) = i + 1 >= end || stops[i]

    private fun progress(p: Point, e: SimEdge) = progressOf(p, e)

    /**
     * Route deviation of the **measured** position: current edge and edges already left over at most [LAG_M]. The
     * followed edge advances on the predicted pose (up to ≈ 1.6 s ahead of the position published at 1 Hz): survey
     * of 2026-10-10, the tracking had skipped two short edges and the deviation, measured only on the current and
     * previous edges, was wrongly 45 cm.
     */
    fun deviation(p: Point): Double {
        var best = Geometry.distance(p, edges[index].a, edges[index].b)
        var i = index - 1
        var behind = 0.0
        while (i >= start && behind < LAG_M) {
            best = min(best, Geometry.distance(p, edges[i].a, edges[i].b))
            behind += edges[i].meters; i--
        }
        return best
    }

    /**
     * Escape out of an obstacle reserve in progress for the **measured** position [measured]: followed escape edge, or
     * previous escape edge that the measured position has not passed yet. The followed edge advances on the predicted
     * pose, up to ≈ 1.6 s ahead of the published position: on a 30 cm escape, the measured position was still inside
     * the reserve while the tracking already checked the next edge with the full reserve.
     */
    fun escaping(measured: Point): Boolean =
        edges[index].escape || (index > start && edges[index - 1].escape && progress(measured, edges[index - 1]) < 1.0)

    /** Distance left to travel along edge [edge] from [p] (negative if passed). */
    fun remainingAlong(p: Point, edge: Int = index): Double = (1.0 - progress(p, edges[edge])) * edges[edge].meters

    /**
     * [position] and [heading]: predicted pose ([DeadReckoning]); [degreesPerUnit] and [metersPerUnit] convert the
     * target speeds into raw units.
     */
    fun command(position: Point, heading: Double, linear: Int, angular: Int,
                degreesPerUnit: Double = DeadReckoning.DEFAULT_DEGREES_PER_UNIT,
                metersPerUnit: Double = DeadReckoning.DEFAULT_METERS_PER_UNIT,
                /** Minimum forward speed when approaching a stop; [MIN_LINEAR] by default. */
                minLinear: Int = MIN_LINEAR,
                /** Deviation of the measured position from the route ([deviation]): speed reduced if it grows. */
                deviationM: Double = 0.0,
                /** Rotation gain calibrated on this robot; otherwise a more careful speed in curves. */
                rotationCalibrated: Boolean = true): Command {
        require(position.x.isFinite() && position.y.isFinite() && heading.isFinite())
        require(linear in 1..Commands.MAX_LINEAR_SPEED && angular in 1..Commands.MAX_ANGULAR_SPEED)
        require(degreesPerUnit > 0 && metersPerUnit > 0)
        val speed = linear * metersPerUnit
        val lookahead = max(0.4, speed * LOOKAHEAD_S)
        var overshoot = false
        while (true) {
            val edge = edges[index]
            val last = index == end - 1
            val passed = progress(position, edge) >= 1.0
            // Sharp corner: pivot at the vertex, measured along the edge. With a 15 cm radius, the
            // robot pivoted before the corner and the next stripe started offset by as much.
            // End of range: point reached along the edge axis (at most [finalArrivalM] sideways),
            // otherwise the point is aimed at directly. Stopping [finalArrivalM] before the point offset
            // the start of the next range by as much.
            val atEnd = remainingAlong(position) <= CORNER_REACH_M + slideAlong(index, degreesPerUnit, angular)
            val reached = if (last) position.distance(edge.b) <= min(finalArrivalM, CORNER_REACH_M) ||
                    (atEnd && (abs(lateral(position, edge)) <= finalArrivalM || position.distance(edge.b) <= finalArrivalM))
                else if (corner(index)) atEnd
                else passed || position.distance(edge.b) <= ARRIVAL_M
            if (!reached) { overshoot = last && atEnd; break }
            if (last) { turning = false; return Command(0, 0, true, edge.b, 0.0) }
            index++
        }
        val edge = edges[index]
        val t = progress(position, edge).coerceIn(0.0, 1.0)
        val foot = Point(edge.a.x + (edge.b.x - edge.a.x) * t, edge.a.y + (edge.b.y - edge.a.y) * t)
        // Walk along the route ahead of the foot of the perpendicular, up to the next sharp corner:
        // anticipated target (caller checks), reference heading and stop distance.
        val horizon = max(max(lookahead, PREVIEW_M), speed * speed / (2 * DECELERATION) + 0.3)
        var from = foot
        var i = index
        var target: Point? = null
        var preview: Point? = null
        var travelled = 0.0
        // Cumulative turn over [CURVE_WINDOW_M] ahead of the robot (curved borders, angles < [CORNER_DEGREES]).
        var curveDegrees = 0.0
        // Same, vertices of arcs only (an edge shorter than [CURVE_ARC_EDGE_M] on one side): curve tracking.
        var arcDegrees = 0.0
        while (true) {
            if (i > index && travelled < CURVE_WINDOW_M) {
                curveDegrees += turnDegrees(edges[i - 1], edges[i])
                if (arcVertex(i - 1)) arcDegrees += turnDegrees(edges[i - 1], edges[i])
            }
            val to = edges[i].b
            val length = from.distance(to)
            fun along(d: Double) = if (length < 1e-9) to else Point(from.x + (to.x - from.x) * d / length, from.y + (to.y - from.y) * d / length)
            if (target == null && travelled + length >= lookahead) target = along(lookahead - travelled)
            if (preview == null && travelled + length >= PREVIEW_M) preview = along(PREVIEW_M - travelled)
            travelled += length
            if (corner(i)) {
                // Beyond a corner: reference extended along the edge axis, the robot arrives at the
                // vertex aligned with the stripe instead of cutting towards the corner.
                if (preview == null) {
                    val e = edges[i]; val l = max(e.meters, 1e-9); val rest = PREVIEW_M - travelled
                    preview = Point(to.x + (e.b.x - e.a.x) / l * rest, to.y + (e.b.y - e.a.y) / l * rest)
                }
                if (target == null) target = to
                break
            }
            if (travelled >= horizon && target != null && preview != null) break
            i++; from = edges[i].a
        }
        val stop = if (corner(i)) travelled - slideAlong(i, degreesPerUnit, angular) else Double.POSITIVE_INFINITY
        val aim = requireNotNull(target)
        // In a curve: tangent at the nearest point of the route and signed curvature around it (feed-forward); otherwise
        // the chord ahead. The followed edge runs up to [ARRIVAL_M] ahead of the robot on short edges (its own heading
        // led the tangent by ≈ 25° on a 0.35 m arc): the vertices between the nearest edge and the followed one are still
        // ahead of the robot. Only arcs of short edges are curves ([CURVE_ARC_EDGE_M]): the bends of a polygonal path keep
        // the chord. Just after a stop vertex, and within [CURVE_WINDOW_M] before the stop of a tight rounded turn, the
        // chord and the pivot of the corner, as before.
        val near = nearestEdge(position)
        val skipped = (near until index).sumOf { if (arcVertex(it)) abs(vertexTurn(it)) else 0.0 }
        val nearStop = stopJustReached(position) ||
            (i + 1 < end && stops[i] && travelled <= CURVE_WINDOW_M && turnDegrees(edges[i], edges[i + 1]) <= CORNER_DEGREES)
        val curved = !nearStop && arcDegrees + skipped > CURVE_TANGENT_DEGREES
        val nearT = progress(position, edges[near]).coerceIn(0.0, 1.0)
        val reference = if (curved) tangentAt(near, nearT) else requireNotNull(preview).let { p ->
            if (p.distance(foot) < 1e-6) headingOf(edge) else Math.toDegrees(atan2(p.y - foot.y, p.x - foot.x)) }
        // Target heading: route reference corrected by the lateral offset, convergence damped over
        // [CONVERGE_M]. Aiming at a point 40 cm away made the tracking underdamped: ±10–20 cm zigzag
        // and pivots in the middle of a stripe (debug mowing of 2026-10-10, 14:55).
        val desired = if (overshoot) Math.toDegrees(atan2(edge.b.y - position.y, edge.b.x - position.x))
            else reference - Math.toDegrees(atan(lateral(position, if (curved) edges[near] else edge) / max(CONVERGE_M, speed * CONVERGE_S)))
        val error = wrap(desired - heading)
        val misaligned = abs(wrap(reference - heading))
        val lateralM = lateral(position, edges[near])
        val enter = if (curved && abs(lateralM) < CURVE_PIVOT_LATERAL_M) CURVE_PIVOT_DEGREES else PIVOT_ENTER_DEGREES
        if (turning) { if (abs(error) <= PIVOT_EXIT_DEGREES) turning = false }
        else if (misaligned > enter || abs(error) > PIVOT_LATERAL_DEGREES) turning = true
        if (turning) {
            val magnitude = (abs(error) * PIVOT_GAIN / degreesPerUnit).roundToInt().coerceIn(min(MIN_PIVOT, angular), angular)
            return Command(0, if (error > 0) magnitude else -magnitude, false, aim, min(stop, horizon))
        }
        // Braking at constant deceleration before the stop (sharp vertex or end of range), on the predicted
        // pose: the command delay is already compensated.
        // End of range (blades, destination): gentler deceleration, the overshoot stays small
        // even if the actual delay exceeds the compensated one.
        // Sharp corner: arrival at the vertex at the minimum approach speed ([CORNER_PLATEAU_M]).
        val braking = if (i == end - 1) sqrt(2 * RANGE_END_DECELERATION * max(0.0, stop - STOP_MARGIN_M))
            else (min(minLinear, linear) * metersPerUnit).let { approach ->
                sqrt(approach * approach + 2 * DECELERATION * max(0.0, stop - CORNER_REACH_M - CORNER_PLATEAU_M)) }
        // Tight curve: speed limited so that the rotation the curve needs stays within [CURVE_FF_SHARE] of the limit
        // ([curveSpeed]), and kept after the exit of a curve ([exitSpeed]).
        val curvature = curveDegrees / max(min(travelled, CURVE_WINDOW_M), 0.2)
        val curveLimit = curveSpeed(curvature, angular, degreesPerUnit, rotationCalibrated)
        val governed = if (deviationM <= GOVERNOR_START_M) Double.POSITIVE_INFINITY
            else linear * metersPerUnit * (0.5 + 0.5 * ((GOVERNOR_FULL_M - deviationM) / (GOVERNOR_FULL_M - GOVERNOR_START_M)).coerceIn(0.0, 1.0))
        val allowed = minOf(braking, governed, curveLimit, exitSpeed(near, nearT, linear * metersPerUnit, angular, degreesPerUnit, rotationCalibrated)) / metersPerUnit
        val forward = (min(linear.toDouble(), allowed) * cos(Math.toRadians(error)).coerceAtLeast(0.0))
            .roundToInt().coerceIn(min(minLinear, linear), linear)
        if (!curved) {
            val steer = (error * STEER_GAIN / degreesPerUnit).roundToInt().coerceIn(-angular / 2, angular / 2)
            return Command(forward, steer, false, aim, min(stop, horizon))
        }
        // Feed-forward: rotation of the curve at the commanded speed, plus the proportional correction.
        // Curvature taken [CURVE_LEAD_S] of travel ahead: the rotation of the robot follows its command with that lag.
        val feedForward = forward * metersPerUnit *
            localCurvature(near, nearT, (forward * metersPerUnit * CURVE_LEAD_S).coerceAtMost(CURVE_LOCAL_M))
        val bound = (CURVE_STEER_LIMIT * angular).roundToInt()
        val steer = ((error * STEER_GAIN + feedForward) / degreesPerUnit).roundToInt().coerceIn(-bound, bound)
        return Command(forward, steer, false, aim, min(stop, horizon))
    }

    /** Vertex at the end of edge [i] on an arc: one of its edges shorter than [CURVE_ARC_EDGE_M]. */
    private fun arcVertex(i: Int): Boolean = i + 1 < edges.size && min(edges[i].meters, edges[i + 1].meters) < CURVE_ARC_EDGE_M

    /** Signed turn (°, + to the left) at the vertex at the end of edge [i]; 0 at a stop vertex (pivot) or outside the range. */
    private fun vertexTurn(i: Int): Double =
        if (i < start || i + 1 >= end || stops[i]) 0.0 else wrap(headingOf(edges[i + 1]) - headingOf(edges[i]))

    /**
     * Edge of the route nearest to [position] among the followed edge and those left over the last [ARRIVAL_M], back to
     * the last stop vertex (pivot): on short edges the followed edge is reached up to [ARRIVAL_M] before its end.
     */
    private fun nearestEdge(position: Point): Int {
        var best = index
        var bestDistance = Geometry.distance(position, edges[index].a, edges[index].b)
        var k = index - 1
        var behind = 0.0
        while (k >= start && behind <= ARRIVAL_M + 0.05 && !stops[k]) {
            val d = Geometry.distance(position, edges[k].a, edges[k].b)
            if (d < bestDistance - 1e-9) { best = k; bestDistance = d }
            behind += edges[k].meters; k--
        }
        return best
    }

    /**
     * A stop vertex (pivot) lies within [ARRIVAL_M] of [position] among the vertices left over the last [ARRIVAL_M]:
     * the followed edge may already be several short edges beyond it.
     */
    private fun stopJustReached(position: Point): Boolean {
        var k = index - 1
        var behind = 0.0
        while (k >= start && behind <= ARRIVAL_M + 0.05) {
            if (stops[k]) return position.distance(edges[k].b) <= ARRIVAL_M
            behind += edges[k].meters; k--
        }
        return false
    }

    /**
     * Tangent of the route at [t] along edge [e]: its heading, the turn of each vertex spread over [CURVE_BLEND_M] (at
     * most half of the edge) on each side of it. On an arc cut into short edges this is the heading of the circle.
     */
    private fun tangentAt(e: Int, t: Double): Double {
        val length = edges[e].meters; val s = t * length
        val half = min(length / 2, CURVE_BLEND_M)
        if (half < 1e-9) return headingOf(edges[e])
        return headingOf(edges[e]) + when {
            s < half -> -vertexTurn(e - 1) / 2 * (1 - s / half)
            s > length - half -> vertexTurn(e) / 2 * (1 - (length - s) / half)
            else -> 0.0
        }
    }

    /**
     * Signed curvature (°/m) [shift] metres ahead of [t] along edge [e] ([shift] at most [CURVE_LOCAL_M]): turns of
     * the vertices within [CURVE_LOCAL_M] on each side of that point.
     */
    private fun localCurvature(e: Int, t: Double, shift: Double): Double {
        var sum = 0.0
        var ahead = (1 - t) * edges[e].meters
        var k = e
        while (ahead <= shift + CURVE_LOCAL_M && k + 1 < end) { sum += vertexTurn(k); k++; ahead += edges[k].meters }
        var behind = t * edges[e].meters
        k = e - 1
        while (behind <= CURVE_LOCAL_M - shift && k >= start) { sum += vertexTurn(k); behind += edges[k].meters; k-- }
        return sum / (2 * CURVE_LOCAL_M)
    }

    /**
     * Speed (m/s) after the exit of a curve, robot at [t] along edge [e]: the speed of that curve ([curveSpeed] of the
     * turns over [CURVE_WINDOW_M] behind its last curved vertex) for [CURVE_EXIT_HOLD_M], then up to [setSpeed] over
     * [CURVE_EXIT_RAMP_M].
     */
    private fun exitSpeed(e: Int, t: Double, setSpeed: Double, angular: Int, degreesPerUnit: Double, rotationCalibrated: Boolean): Double {
        var behind = t * edges[e].meters
        var k = e - 1
        while (k >= start && behind <= CURVE_EXIT_HOLD_M + CURVE_EXIT_RAMP_M) {
            if (abs(vertexTurn(k)) >= 0.5) {
                var sum = 0.0; var length = 0.0; var j = k
                while (j >= start && length < CURVE_WINDOW_M) { sum += abs(vertexTurn(j)); length += edges[j].meters; j-- }
                return exitLimit(behind, curveSpeed(sum / max(min(length, CURVE_WINDOW_M), 0.2), angular, degreesPerUnit, rotationCalibrated), setSpeed)
            }
            behind += edges[k].meters; k--
        }
        return Double.POSITIVE_INFINITY
    }

    private fun wrap(degrees: Double) = ((degrees + 540) % 360 + 360) % 360 - 180

    /** Signed offset of [p] from the line of [e]: positive to the left of the direction of travel. */
    private fun lateral(p: Point, e: SimEdge): Double {
        val dx = e.b.x - e.a.x; val dy = e.b.y - e.a.y; val l = hypot(dx, dy)
        return if (l < 1e-9) 0.0 else (dx * (p.y - e.a.y) - dy * (p.x - e.a.x)) / l
    }
}

/**
 * Arrival at the end of a range (blade state change, destination) confirmed by a position **measured after the
 * stop**. The tracking declares the arrival on the predicted pose; survey of 2026-10-10: the actual robot stopped 15
 * to 30 cm before the corner, the next range (blades running) started with a 0.8 s old position and the deviation
 * from the new stripe exceeded 30 cm straight away.
 *
 * [check]: `null` = stay stopped and wait for a position later than the stop;
 * `false` = robot stopped short, resume the approach; `true` = arrival accepted.
 */
class RangeArrival(private val commandDelayMs: Long, private val arrivalM: Double = ACCEPT_M) {
    companion object {
        /**
         * Arrival targeted by the tracking (predicted pose) at the end of a range: clearly below [ACCEPT_M].
         * Survey of 2026-10-10 (14:20): target and acceptance at 15 cm, the robot stopped at 15.5–16 cm, the resume
         * only lasted one 200 ms command (predicted pose immediately "arrived") and the robot no longer moved: stop
         * "blocage présumé, contact bumper non confirmé".
         */
        const val TARGET_M = 0.12
        /** Arrival accepted on the measured position (15 cm + RTK noise). */
        const val ACCEPT_M = 0.18
        /** Minimum forward speed when approaching: below ≈ 60 units the LUBA 2 hardly moves. */
        const val APPROACH_MIN_LINEAR = 80
        /** LUBA 2 deceleration after the stop takes effect. */
        const val SETTLE_MS = 300L
        const val MAX_APPROACHES = 3
        /** Tolerated remainder along the edge: beyond it, the stop is short and the approach resumes. */
        const val SHORT_M = 0.08

        /**
         * End-of-range decision of actual mowing: without [arrival] (approach of the base, docking follows), arrival
         * accepted; otherwise [check], whose `null` (stay stopped) must be kept. The BLE tracking wrote
         * `arrival?.check(...) ?: true`: the wait became an accepted arrival and the end of range was declared on the
         * predicted pose alone (execution bench of 2026-10-10).
         */
        fun settle(arrival: RangeArrival?, now: Long, fixAt: Long, measured: Point, follower: PathFollower, end: Point): Boolean? =
            if (arrival == null) true else arrival.check(now, fixAt, measured, follower, end)
    }
    private var stoppedAt = Long.MIN_VALUE
    var approaches = 0
        private set
    /** Last judged measurement: distance to the point and remainder along the edge. */
    var distanceM = Double.NaN
        private set
    var remainingM = Double.NaN
        private set

    fun check(now: Long, fixAt: Long, measured: Point, follower: PathFollower, end: Point): Boolean? {
        if (stoppedAt == Long.MIN_VALUE) stoppedAt = now
        if (fixAt < stoppedAt + commandDelayMs + SETTLE_MS) return null
        distanceM = measured.distance(end)
        remainingM = follower.remainingAlong(measured)
        // Passed, or offset only sideways (bounded by the deviation check): arrival granted.
        if (distanceM <= arrivalM || remainingM <= SHORT_M || approaches >= MAX_APPROACHES) return true
        approaches++
        stoppedAt = Long.MIN_VALUE
        return false
    }
}
