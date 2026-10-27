package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.DeadReckoning
import fr.luba.poc.simulation.PathFollower
import kotlin.math.*

/** How the U-turns between two passes are chosen ([TurnOptimizer]). */
enum class TurnStyle(private val en: String, private val fr: String, private val enHint: String, private val frHint: String) {
    /** Shortest duration: pivots in place at the pass ends, as before. */
    FAST("Fast", "Rapides", "Pivots in place at the pass ends", "Pivots sur place en bout de passe"),
    /** Lawn spared: rolling U-turns wherever they fit, passes taken one in two so that the turn is wide enough. */
    LAWN("Lawn friendly", "Ménager le gazon", "Rolling U-turns where they fit, one pass in two (longer)",
        "Demi-tours en roulant là où ils passent, une passe sur deux (plus long)");
    val label: String get() = tr(en, fr)
    val hint: String get() = tr(enHint, frHint)
}

/** U-turn manoeuvres compared by [TurnOptimizer]. */
enum class TurnKind(private val en: String, private val fr: String) {
    /** Stop, pivot in place, straight link, pivot in place (two 90° pivots between neighbouring passes). */
    PIVOTS("Pivots in place", "Pivots sur place"),
    /** U driven without stopping between two passes at least two follow radii apart: arc of
     * [TurnOptimizer.FOLLOW_RADIUS_M], tangent straight line, arc (two quarter circles when the pass ends are level, a
     * half circle when the passes are exactly that far; staggered pass ends tilt the straight line). */
    U_ARC("Rolling U-turn", "Virage en U"),
    /** Rounded loop (Ω): away from the next pass, around, then into it, when the passes are too close for a U. */
    LOOP("Rounded loop", "Boucle arrondie"),
    /** Several moves with reversing (K-turn). */
    MULTI_POINT("Multi-point turn", "Manœuvre en plusieurs temps");
    val label: String get() = tr(en, fr)
}

/**
 * One U-turn from the end of pass A to the start of pass B. [points]: robot centre from the new end of A to the new
 * start of B (both included); [trimA] and [trimB]: length taken off the end of A and the start of B (their ground is
 * swept by the manoeuvre itself, checked). [durationS], [wear] and [tight] are compared on the same stretch: from
 * [TurnOptimizer.LEAD_M] before the end of A to as much after the start of B (the shortened pass parts are not driven
 * twice). [refusal]: why it is not possible.
 */
data class TurnPlan(val kind: TurnKind, val points: List<Point>, val trimA: Double = 0.0, val trimB: Double = 0.0,
                    val durationS: Double = Double.POSITIVE_INFINITY, val pivotDegrees: Double = 0.0, val wear: Double = 0.0,
                    val tight: Boolean = false, val refusal: String? = null) {
    val possible: Boolean get() = refusal == null
    fun score(style: TurnStyle): Double = when (style) {
        TurnStyle.FAST -> durationS + 0.02 * wear + if (tight) TIGHT_PENALTY else 0.0
        TurnStyle.LAWN -> 0.25 * durationS + 0.06 * wear + if (tight) TIGHT_PENALTY else 0.0
    }
    companion object {
        /** Footprint closer than [TurnOptimizer.RISK_MARGIN_M] to the boundary somewhere along the manoeuvre. */
        const val TIGHT_PENALTY = 8.0
    }
}

/**
 * Turn Optimizer: compares, for each U-turn between two passes, the pivot in place, the rolling U-turn, the rounded loop
 * and the multi-point turn, and keeps the possible one that minimises duration, lawn wear and risk for the chosen
 * [TurnStyle]. Possible: drivable by the current tracking ([PathFollower]: forward only, no stop on curves of at least
 * [FOLLOW_RADIUS_M]), checked by the caller ([TurnContext.drivable]: safe surface and chassis envelope), and every bit of
 * pass it shortens swept by the manoeuvre. Metric computation only: the duration follows the tracking rules (pivots at
 * 27 °/s measured with a 1.5 s stop, speed limited in curves), the wear is an index (degrees turned in place; a rolling
 * turn of radius R counts its degrees × h / (h + R), h half the chassis width), not a measurement.
 */
object TurnOptimizer {
    /**
     * Tightest arc planned for a rolling U-turn: above the ≈ 0.3 m the robot could not follow on the bench of 2026-10-10,
     * and under the tracking rule that turns 30° over 15 cm into a pivot ([PathFollower.ROUNDED_CORNER_M]) with
     * [ARC_STEP_DEGREES] per vertex (24.6° over 15 cm plus one vertex).
     */
    const val FOLLOW_RADIUS_M = 0.35
    /** Widest U tried (passes further apart are not neighbours any more). */
    const val MAX_U_LATERAL_M = 2.4
    /** Angle between two vertices of an arc: 2.4 cm at 0.35 m. */
    const val ARC_STEP_DEGREES = 4.0
    /** Pass shortened by at most this much at each end, by steps of [TRIM_STEP_M]. */
    const val MAX_TRIM_M = 0.6
    const val TRIM_STEP_M = 0.05
    /** Pass part kept at least after a trim. */
    const val MIN_PASS_M = 0.3
    /** Straight stretch before and after the U-turn included in the comparison. */
    const val LEAD_M = 0.7
    /** Shortened pass parts: kept this much inside the swath of the manoeuvre. */
    const val SWEPT_TOLERANCE_M = 0.01
    /** Footprint margin under which a manoeuvre is tight ([TurnPlan.tight]). */
    const val RISK_MARGIN_M = 0.05
    /** Pass directions accepted as opposite for a U (degrees from 180°). */
    const val OPPOSITE_DEGREES = 5.0
    /** Measured on the robot (docs/TRACKING_EXECUTION.md). */
    const val PIVOT_DEGREES_PER_SECOND = 27.0
    const val STOP_SECONDS = 1.5

    /** What the planner checks for a candidate: [drivable] the whole stretch, [roomy] the same with [RISK_MARGIN_M] more. */
    class TurnContext(val spacingM: Double, val speedMps: Double, val drivable: (List<Point>) -> Boolean,
                      val roomy: (List<Point>) -> Boolean = drivable, val robot: RobotEnvelope = RobotEnvelope.LUBA2,
                      /** Cut width: a shortened pass part is mowed if it lies within half of it of the manoeuvre. */
                      val cutWidthM: Double = spacingM)

    private data class Frame(val end: Point, val dir: Point, val left: Point, val side: Double, val lateral: Double, val along: Double)

    private fun unit(a: Point, b: Point): Point? {
        val d = a.distance(b); return if (d < 1e-6) null else Point((b.x - a.x) / d, (b.y - a.y) / d)
    }
    private fun at(p: Point, dir: Point, d: Double) = Point(p.x + dir.x * d, p.y + dir.y * d)
    private fun dot(a: Point, b: Point) = a.x * b.x + a.y * b.y

    /**
     * Candidates for the U-turn from pass A ([aFrom] → [aEnd]) to pass B ([bStart] → [bTo]), the possible ones first in
     * no particular order, then the refused ones with their reason.
     */
    fun candidates(aFrom: Point, aEnd: Point, bStart: Point, bTo: Point, context: TurnContext): List<TurnPlan> {
        val dirA = unit(aFrom, aEnd) ?: return emptyList()
        val dirB = unit(bStart, bTo) ?: return emptyList()
        val left = Point(-dirA.y, dirA.x)
        val offset = Point(bStart.x - aEnd.x, bStart.y - aEnd.y)
        val lateral = dot(left, offset)
        val frame = Frame(aEnd, dirA, left, if (lateral >= 0) 1.0 else -1.0, abs(lateral), dot(dirA, offset))
        val lengthA = aFrom.distance(aEnd); val lengthB = bStart.distance(bTo)
        val opposite = dot(dirA, dirB) <= -cos(Math.toRadians(OPPOSITE_DEGREES))
        val out = mutableListOf<TurnPlan>()
        val leads = min(LEAD_M, lengthA) to min(LEAD_M, lengthB)
        out += evaluate(TurnKind.PIVOTS, listOf(aEnd, bStart), 0.0, 0.0, aEnd, dirA, bStart, dirB, leads, context)
        out += if (!opposite) TurnPlan(TurnKind.U_ARC, emptyList(), refusal = tr("Passes not opposite", "Passes non opposées"))
            else if (frame.lateral / 2 < FOLLOW_RADIUS_M) TurnPlan(TurnKind.U_ARC, emptyList(), refusal =
                tr("Passes %.2f m apart: a U needs %.2f m to be driven without stopping", "Passes à %.2f m : un U demande %.2f m pour être suivi sans arrêt")
                    .format(frame.lateral, 2 * FOLLOW_RADIUS_M))
            else if (frame.lateral > MAX_U_LATERAL_M) TurnPlan(TurnKind.U_ARC, emptyList(), refusal = tr("Passes too far apart", "Passes trop éloignées"))
            else shapedBoth(TurnKind.U_ARC, frame, lengthA, lengthB, bStart, dirB, leads, context) { start, end -> sameSide(start, end, frame.side, FOLLOW_RADIUS_M) }
        out += if (!opposite) TurnPlan(TurnKind.LOOP, emptyList(), refusal = tr("Passes not opposite", "Passes non opposées"))
            else if (frame.lateral >= 2 * FOLLOW_RADIUS_M) TurnPlan(TurnKind.LOOP, emptyList(), refusal = tr("A U-turn is enough", "Un U suffit"))
            else shaped(TurnKind.LOOP, frame, lengthA, lengthB, dirB, leads, context) { start -> loop(start, frame.side, frame.lateral, FOLLOW_RADIUS_M) }
        out += TurnPlan(TurnKind.MULTI_POINT, emptyList(), refusal = tr("Needs reversing, which the tracking does not drive",
            "Demande de reculer, ce que le suivi ne sait pas faire"))
        return out.sortedBy { if (it.possible) 0 else 1 }
    }

    /** Best possible candidate for [style] (the pivots stay the reference when nothing else fits); null if none. */
    fun best(candidates: List<TurnPlan>, style: TurnStyle): TurnPlan? =
        candidates.filter { it.possible }.minByOrNull { it.score(style) }

    /** Pose of the robot: centre and heading (unit vector). */
    private data class Pose(val p: Point, val dir: Point)

    /** Points of an arc of [signedDegrees] (+ = left) and [radius] from [start], [start] excluded. */
    private fun arcPoints(start: Pose, signedDegrees: Double, radius: Double): Pair<List<Point>, Pose> {
        val sign = if (signedDegrees >= 0) 1.0 else -1.0
        val left = Point(-start.dir.y, start.dir.x)
        val center = at(start.p, left, sign * radius)
        val steps = max(1, ceil(abs(signedDegrees) / ARC_STEP_DEGREES).toInt())
        val heading0 = atan2(start.dir.y, start.dir.x)
        val points = (1..steps).map { k ->
            val a = Math.toRadians(signedDegrees * k / steps)
            val dx = start.p.x - center.x; val dy = start.p.y - center.y
            Point(center.x + dx * cos(a) - dy * sin(a), center.y + dx * sin(a) + dy * cos(a))
        }
        val h = heading0 + Math.toRadians(signedDegrees)
        return points to Pose(points.last(), Point(cos(h), sin(h)))
    }

    private fun arc(start: Pose, signedDegrees: Double, radius: Double): List<Point> = listOf(start.p) + arcPoints(start, signedDegrees, radius).first

    /**
     * Shortest forward path from [start] to [end] (opposite headings) turning only to the [side] on circles of [r]
     * (Dubins LSL / RSR): arc, tangent straight line, arc; null when the two circles overlap (passes closer than 2 [r]).
     */
    private fun sameSide(start: Pose, end: Pose, side: Double, r: Double): List<Point>? {
        fun leftOf(d: Point) = Point(-d.y, d.x)
        val c1 = at(start.p, leftOf(start.dir), side * r)
        val c2 = at(end.p, leftOf(end.dir), side * r)
        val gap = c1.distance(c2)
        if (gap < 1e-6) return arc(start, side * 180.0, r)
        val u = Point((c2.x - c1.x) / gap, (c2.y - c1.y) / gap)
        // Tangent points: the robot leaves circle 1 heading along u.
        val t1 = at(c1, leftOf(u), -side * r)
        fun turn(from: Point, to: Point): Double {
            val a = Math.toDegrees(atan2(to.y, to.x) - atan2(from.y, from.x))
            val ccw = ((a % 360) + 360) % 360
            return if (side > 0) ccw else -((360 - ccw) % 360)
        }
        val a1 = turn(start.dir, u); val a2 = turn(u, end.dir)
        if (abs(a1) + abs(a2) > 180.0 + 1e-6) return null
        val (first, p1) = arcPoints(start, a1, r)
        val firstPoints = if (abs(a1) < 1e-6) emptyList() else first
        val tangentStart = if (abs(a1) < 1e-6) start else p1
        check(tangentStart.p.distance(t1) < 1e-6) { "Dubins tangent mismatch" }
        val p2 = Pose(at(t1, u, gap), u)
        val (second, _) = arcPoints(p2, a2, r)
        return listOf(start.p) + firstPoints + listOf(p2.p) + (if (abs(a2) < 1e-6) emptyList() else second)
    }

    /**
     * Manoeuvre built by [shape] between a point of A and a point of B, both passes shortened by the same trim, for the
     * smallest trim that the planner accepts and whose shortened pass parts the manoeuvre sweeps.
     */
    private fun shapedBoth(kind: TurnKind, f: Frame, lengthA: Double, lengthB: Double, bStart: Point, dirB: Point, leads: Pair<Double, Double>,
                           context: TurnContext, shape: (Pose, Pose) -> List<Point>?): TurnPlan {
        var reason = tr("No room for it at the pass ends", "Pas la place en bout de passe")
        var trim = 0.0
        while (trim <= MAX_TRIM_M + 1e-9) {
            if (lengthA - trim < MIN_PASS_M || lengthB - trim < MIN_PASS_M) { reason = tr("Passes too short", "Passes trop courtes"); break }
            val points = shape(Pose(at(f.end, f.dir, -trim), f.dir), Pose(at(bStart, dirB, trim), dirB))
                ?.fold(mutableListOf<Point>()) { acc, p -> if (acc.isEmpty() || acc.last().distance(p) > 1e-6) acc.add(p); acc }
            if (points == null) { reason = tr("Passes too close for a U", "Passes trop proches pour un U"); break }
            if (swept(points, f.end, f.dir, trim, context.cutWidthM) && swept(points, bStart, Point(-dirB.x, -dirB.y), trim, context.cutWidthM)) {
                val plan = evaluate(kind, points, trim, trim, f.end, f.dir, bStart, dirB, leads, context)
                if (plan.possible) return plan
                reason = plan.refusal ?: reason
            } else reason = tr("Shortened passes not swept by the manoeuvre", "Passes raccourcies non balayées par la manœuvre")
            trim += TRIM_STEP_M
        }
        return TurnPlan(kind, emptyList(), refusal = reason)
    }

    /**
     * Rounded loop (Ω) of radius [r] from [start] to the opposite pass [lateral] metres to the [side]: away by α,
     * around by 180° + 2α, back by α. The three circles are tangent; only for [lateral] under 2 [r].
     */
    private fun loop(start: Pose, side: Double, lateral: Double, r: Double): List<Point> {
        val h = sqrt(max(0.0, 4 * r * r - (lateral / 2 + r).pow(2)))
        val alpha = Math.toDegrees(atan2(h, lateral / 2 + r))
        val (first, p1) = arcPoints(start, -side * alpha, r)
        val (second, p2) = arcPoints(p1, side * (180 + 2 * alpha), r)
        val (third, _) = arcPoints(p2, -side * alpha, r)
        return listOf(start.p) + first + second + third
    }

    /**
     * Manoeuvre built by [shape] from a point of A [trim] before its end (both passes shortened alike, plus their
     * stagger), for the smallest trim that the planner accepts and whose shortened pass parts the manoeuvre sweeps.
     */
    private fun shaped(kind: TurnKind, f: Frame, lengthA: Double, lengthB: Double, dirB: Point, leads: Pair<Double, Double>,
                       context: TurnContext, shape: (Pose) -> List<Point>): TurnPlan {
        var reason = tr("No room for it at the pass ends", "Pas la place en bout de passe")
        var trim = 0.0
        while (trim <= MAX_TRIM_M + 1e-9) {
            val s0 = min(0.0, f.along) - trim
            val trimA = -s0; val trimB = f.along - s0
            if (lengthA - trimA < MIN_PASS_M || lengthB - trimB < MIN_PASS_M) { reason = tr("Passes too short", "Passes trop courtes"); break }
            val points = shape(Pose(at(f.end, f.dir, s0), f.dir))
            val bStart = at(at(f.end, f.left, f.side * f.lateral), f.dir, f.along)
            val end = points.last()
            val landed = abs(dot(f.left, Point(end.x - f.end.x, end.y - f.end.y)) - f.side * f.lateral) < 0.01 &&
                abs(dot(f.dir, Point(end.x - f.end.x, end.y - f.end.y)) - s0) < 0.01
            check(landed) { "U-turn shape does not land on the next pass" }
            if (swept(points, f.end, f.dir, trimA, context.cutWidthM) && swept(points, bStart, Point(-dirB.x, -dirB.y), trimB, context.cutWidthM)) {
                val plan = evaluate(kind, points, trimA, trimB, f.end, f.dir, bStart, dirB, leads, context)
                if (plan.possible) return plan
                reason = plan.refusal ?: reason
            } else reason = tr("Shortened passes not swept by the manoeuvre", "Passes raccourcies non balayées par la manœuvre")
            trim += TRIM_STEP_M
        }
        return TurnPlan(kind, emptyList(), refusal = reason)
    }

    /** The [trim] metres of pass before [end] (direction [dir] towards [end]) lie within half a [cut] of [path]. */
    private fun swept(path: List<Point>, end: Point, dir: Point, trim: Double, cut: Double): Boolean {
        if (trim < 1e-9) return true
        val reach = cut / 2 - SWEPT_TOLERANCE_M
        val samples = max(1, ceil(trim / 0.02).toInt())
        return (0..samples).all { k ->
            val p = at(end, dir, -trim * k / samples)
            path.zipWithNext().any { (a, b) -> Geometry.distance(p, a, b) <= reach }
        }
    }

    /** Duration, pivots, wear and checks of [points] with [leads] of each pass around it ([LEAD_M] at most). */
    private fun evaluate(kind: TurnKind, points: List<Point>, trimA: Double, trimB: Double, aEnd: Point, dirA: Point,
                         bStart: Point, dirB: Point, leads: Pair<Double, Double>, context: TurnContext): TurnPlan {
        val stretch = listOf(at(aEnd, dirA, -leads.first)) + points + listOf(at(bStart, dirB, leads.second))
        val path = stretch.fold(mutableListOf<Point>()) { acc, p -> if (acc.isEmpty() || acc.last().distance(p) > 1e-6) acc.add(p); acc }
        if (!context.drivable(path)) return TurnPlan(kind, points, trimA, trimB, refusal = tr("Outside the safe surface or the chassis envelope",
            "Hors de la surface sûre ou de l'enveloppe du châssis"))
        val drive = drive(path, context.speedMps, context.robot)
        return TurnPlan(kind, points, trimA, trimB, drive.durationS, drive.pivotDegrees, drive.wear, tight = !context.roomy(path))
    }

    /** Time, degrees pivoted in place and wear index of driving [path] at [speedMps] with the tracking rules. */
    data class Drive(val durationS: Double, val pivotDegrees: Double, val wear: Double)

    /**
     * [path] driven by the tracking: pivot in place (stop included) at vertices turning more than
     * [PathFollower.CORNER_DEGREES], speed limited by the curvature elsewhere, as [PathFollower] does.
     */
    fun drive(path: List<Point>, speedMps: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): Drive {
        val speedPercent = (speedMps / (5 * DeadReckoning.DEFAULT_METERS_PER_UNIT)).roundToInt().coerceIn(10, 100)
        val turnRate = PathFollower.angularLimit(speedPercent) / 2.0 * DeadReckoning.DEFAULT_DEGREES_PER_UNIT * PathFollower.CURVE_STEER_SHARE
        val turns = path.indices.map { i ->
            if (i == 0 || i == path.lastIndex) 0.0 else {
                val h1 = atan2(path[i].y - path[i - 1].y, path[i].x - path[i - 1].x)
                val h2 = atan2(path[i + 1].y - path[i].y, path[i + 1].x - path[i].x)
                abs(Math.toDegrees(((h2 - h1 + 3 * PI) % (2 * PI)) - PI))
            }
        }
        var duration = 0.0; var pivots = 0.0; var wear = 0.0
        val half = robot.halfWidthM
        path.zipWithNext().forEachIndexed { i, (a, b) ->
            val length = a.distance(b)
            val bends = listOf(turns[i], turns[i + 1]).map { if (it > PathFollower.CORNER_DEGREES) 0.0 else it }
            val curvature = bends.average() / max(length, 1e-6)
            duration += length / min(speedMps, if (curvature > 1e-6) turnRate / curvature else Double.POSITIVE_INFINITY)
        }
        path.indices.forEach { i ->
            val t = turns[i]
            if (t > PathFollower.CORNER_DEGREES) { pivots += t; wear += t; duration += t / PIVOT_DEGREES_PER_SECOND + STOP_SECONDS }
            else if (t > 1e-6) {
                val radius = (path[i].distance(path[i - 1]) + path[i].distance(path[i + 1])) / 2 / Math.toRadians(t)
                wear += t * half / (half + radius)
            }
        }
        return Drive(duration, pivots, wear)
    }
}

/**
 * Order of the passes for [TurnStyle.LAWN]: in each cell, the passes are taken one in [step] (ascending, then the next
 * ones descending, and so on) so that two passes driven one after the other are [step] spacings apart, enough for a
 * U-turn of [radius] followed without stopping. The passes are all kept, only their order and direction change. A
 * cell whose passes are not a regular zigzag (several segments per row, uneven offsets) keeps its order.
 */
object TurnOrder {
    fun step(spacing: Double, radius: Double): Int = max(1, ceil(2 * radius / spacing - 1e-9).toInt())

    /** Passes of one cell taken one in [step]; the same list when that order does not apply. */
    fun <T> skip(group: List<T>, spacing: Double, radius: Double, path: (T) -> List<Point>, with: (T, List<Point>) -> T): List<T> {
        val m = step(spacing, radius)
        return if (m <= 1) group else reorder(group, spacing, m, path, with)
    }

    /**
     * Passes of a regular zigzag, all turned the way of the first one, or null: every path is one straight segment,
     * directions alternate, and consecutive rows are one [spacing] (± 30 %) apart, always on the same side. Staggered
     * ends are fine. Shared by the one-in-[step] order and [ZamboniOrder].
     */
    fun ladder(paths: List<List<Point>>, spacing: Double): List<List<Point>>? {
        if (paths.size < 2 || paths.any { it.size != 2 || it[0].distance(it[1]) < 1e-6 }) return null
        val d0 = paths[0].let { Point((it[1].x - it[0].x) / it[0].distance(it[1]), (it[1].y - it[0].y) / it[0].distance(it[1])) }
        val normal = Point(-d0.y, d0.x)
        // Regular zigzag: alternating directions, one spacing between consecutive rows, always the same way.
        val offsets = paths.map { it[0].x * normal.x + it[0].y * normal.y }
        val sign = if (offsets[1] >= offsets[0]) 1.0 else -1.0
        val regular = paths.indices.all { k ->
            val d = paths[k].let { Point(it[1].x - it[0].x, it[1].y - it[0].y) }
            val along = (d.x * d0.x + d.y * d0.y) / paths[k][0].distance(paths[k][1])
            (if (k % 2 == 0) along > 0.99 else along < -0.99) &&
                (k == 0 || abs(sign * (offsets[k] - offsets[k - 1]) - spacing) <= 0.3 * spacing)
        }
        return if (!regular) null else paths.mapIndexed { k, p -> if (k % 2 == 0) p else p.reversed() }
    }

    private fun <T> reorder(group: List<T>, spacing: Double, m: Int, path: (T) -> List<Point>, with: (T, List<Point>) -> T): List<T> {
        if (group.size <= 2) return group
        val base = ladder(group.map(path), spacing) ?: return group
        val order = (0 until m).flatMap { g -> (g until group.size step m).toList().let { if (g % 2 == 0) it else it.reversed() } }
        return order.mapIndexed { visit, k -> with(group[k], if (visit % 2 == 0) base[k] else base[k].reversed()) }
    }
}
