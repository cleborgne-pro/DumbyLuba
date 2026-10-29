package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import kotlin.math.*

/**
 * Remaining route repaired around obstacles discovered during a mission.
 * [edges] starts at the robot pose (after the optional back-off) and ends at the same point as the original route;
 * [origins] gives for each edge the index of the original edge (-1: bypass).
 */
data class RouteRepair(
    val edges: List<SimEdge>, val origins: List<Int>,
    /** Planners for the rest of the mission (discovered exclusions included). */
    val routes: TrajectoryPlanner, val physical: TrajectoryPlanner?,
    /** Straight back-off to perform first (heading unchanged), or null. */
    val backOff: Point?,
    val bypasses: Int, val bypassMeters: Double, val skippedCuttingMeters: Double,
    /** Tight detours chosen around the obstacles (side, radius, clearance to the borders), in route order. */
    val detours: List<BypassDetour> = emptyList(),
)

/**
 * Tight detour around a discovered obstacle: [side] +1 on the left of the route (counter-clockwise around the
 * obstacle), -1 on the right, 0 straight (the pass grazes the obstacle without entering its reserve). [inner]: side
 * farthest from the nearest drawn border (inside of the zone).
 */
data class BypassDetour(
    val center: Point, val side: Int, val radiusM: Double, val meters: Double,
    val borderClearanceM: Double, val bladesOn: Boolean, val inner: Boolean,
)

/**
 * Bypass of obstacles discovered during a mission (bumper contact, obstacle added): same planning building block as
 * the preparation ([TrajectoryPlanner]), same checks of envelope, zone borders, no-go zones, accesses and base safety
 * zone.
 *
 * 1. Back-off: after a contact, pivoting is impossible (the corners sweep 0.43 m, the obstacle is less than 0.35 m from
 *    the bumper). The robot backs off in a straight line, heading unchanged, along the trail it has just travelled,
 *    envelope checked against the borders, no-go zones and obstacles at their physical radius.
 * 2. Repair: every portion of the remaining route within [clipRadiusM] of a discovered obstacle is removed (including
 *    later passes crossing it again); the kept pieces are connected by navigation links. Links internal to a mowing
 *    zone keep the blades running, like the prepared links ([BladeContinuity]); the first link after a contact is
 *    blades OFF.
 * 3. Check: complete route verified ([TrajectoryPlanner.requirePath]) and outside the base zone.
 */
object ObstacleBypass {
    /** Margin added to the tracking check `obstacle.exclusionRadiusM + turnClearanceM`. */
    const val SLACK_M = 0.05
    /** Pivot margin at the link point. */
    const val PIVOT_SLACK_M = 0.03
    /** Back-off after contact: bounds and step (m). */
    const val MIN_BACK_OFF_M = 0.3
    const val MAX_BACK_OFF_M = 1.0
    /** Maximum gap between the back-off point and the travelled trail. */
    const val TRAIL_TOLERANCE_M = 0.25
    /** Default physical radius of an unknown obstacle hit ("Other" kind) and margin. */
    const val CONTACT_RADIUS_M = 0.2
    const val CONTACT_MARGIN_M = 0.3
    /**
     * Margin of the estimated disc for an object at the drawn border ([EdgeContactKind.OBJECT_AT_EDGE]): a planning
     * estimate, not a robot protection (bumper, [fr.luba.poc.obstacles.FollowObstacleGuard] and physical radius
     * unchanged). Clip radius 1.18 → 1.08 m, keep-out 0.93 → 0.83 m: one pass fewer cut away on each side in a narrow
     * corridor (2026-10-28, 2 m corridor).
     */
    const val CONTACT_EDGE_MARGIN_M = 0.2
    /**
     * Detour budget of a repair ([detourRefusal]): links at most `max(DETOUR_FLOOR_M, DETOUR_RATIO × cutting skipped)`.
     * 2026-10-28: 79.2 m of links for 14.1 m of cutting skipped at a wood pile at the border of a 2 m corridor, then
     * 73.6 m around a hedge in transit.
     */
    const val DETOUR_FLOOR_M = 15.0
    const val DETOUR_RATIO = 3.0
    /** Bypasses allowed per mission (contacts or added obstacles). */
    const val MAX_BYPASSES = 8
    /** Successive contacts around the same object: beyond this, object too large (to be drawn as a no-go zone). */
    const val NEARBY_M = 2.5
    const val MAX_NEARBY_CONTACTS = 4
    private val factory = GeometryFactory()

    /**
     * Rule shared by actual mowing and simulation: stop reason if one more bypass is not allowed ([bypasses] already
     * made), or null. An object larger than the estimated disc is hit again further on: each contact adds a disc, up to
     * [MAX_NEARBY_CONTACTS] within [NEARBY_M].
     */
    fun contactFailure(discovered: List<LocalObstacle>, candidate: LocalObstacle, bypasses: Int): String? {
        if (bypasses >= MAX_BYPASSES) return tr("$MAX_BYPASSES bypasses already made: stop kept", "$MAX_BYPASSES contournements déjà effectués : arrêt maintenu")
        val center = Point(candidate.x, candidate.y)
        if (discovered.count { Point(it.x, it.y).distance(center) < NEARBY_M } >= MAX_NEARBY_CONTACTS)
            return tr("Contact #${MAX_NEARBY_CONTACTS + 1} around the same object: too large to be bypassed, stop kept (draw it as a no-go zone)",
                "${MAX_NEARBY_CONTACTS + 1}ᵉ contact autour du même objet : trop étendu pour être contourné, arrêt maintenu (le tracer en no-go)")
        return null
    }

    /** Robot centre → obstacle centre distance kept by the tracking (turning envelope included). */
    fun keepOutM(obstacle: LocalObstacle, robot: RobotEnvelope = RobotEnvelope.LUBA2) =
        obstacle.exclusionRadiusM + robot.turnClearanceM
    /** Navigation exclusion: with the chassis half-width, the centre stays beyond [keepOutM]. */
    fun holeRadiusM(obstacle: LocalObstacle, robot: RobotEnvelope = RobotEnvelope.LUBA2) =
        keepOutM(obstacle, robot) - robot.halfWidthM + SLACK_M
    /** Route clipping: a full pivot is still possible at the point where the bypass starts. */
    fun clipRadiusM(obstacle: LocalObstacle, robot: RobotEnvelope = RobotEnvelope.LUBA2) =
        holeRadiusM(obstacle, robot) + robot.turnClearanceM + PIVOT_SLACK_M

    fun circle(center: Point, radius: Double, sides: Int = 64): List<Point> {
        val r = radius / cos(PI / sides)
        return (0 until sides).map { i -> val a = i * 2 * PI / sides; Point(center.x + r * cos(a), center.y + r * sin(a)) }
    }

    /** Unknown obstacle estimated from the pose stopped at the contact (front bumper, fused heading). */
    fun contactObstacle(id: String, position: Point, headingDegrees: Double, atMs: Long,
                        robot: RobotEnvelope = RobotEnvelope.LUBA2, marginM: Double = CONTACT_MARGIN_M): LocalObstacle {
        val center = fr.luba.poc.obstacles.TeachObstacle.estimateCenter(
            fr.luba.poc.obstacles.TeachContact(position.x, position.y, headingDegrees, atMs), CONTACT_RADIUS_M, robot)
        return LocalObstacle(id, fr.luba.poc.obstacles.ObstacleKind.OTHER, center.x, center.y, CONTACT_RADIUS_M,
            marginM, createdAtMs = atMs, updatedAtMs = atMs, label = tr("Contact during the mission", "Contact pendant la mission"))
    }

    /** Longest links accepted for a repair skipping [skippedCuttingM] of cutting ([detourRefusal]). */
    fun detourBudgetM(skippedCuttingM: Double): Double = max(DETOUR_FLOOR_M, DETOUR_RATIO * skippedCuttingM)

    /**
     * Rule shared by actual mowing and simulation: reason why [repair] costs too much transit for what it saves
     * ([DETOUR_FLOOR_M], [DETOUR_RATIO]), or null. The caller then defers the segment to the end of the mowing when it
     * can ([SmartResume.deferBlocked]); otherwise (station route, no progress saved) the repair is kept, never worse
     * than before.
     */
    fun detourRefusal(repair: RouteRepair): String? {
        val budget = detourBudgetM(repair.skippedCuttingMeters)
        if (repair.bypassMeters <= budget) return null
        return tr("Bypass too long (%.1f m of links for %.1f m of cutting skipped, at most %.1f m)",
            "Contournement trop long (%.1f m de raccords pour %.1f m de coupe sautés, au plus %.1f m)")
            .format(repair.bypassMeters, repair.skippedCuttingMeters, budget)
    }

    /** Planner for the rest of the mission: navigation exclusions of the discovered obstacles. */
    fun missionRoutes(base: TrajectoryPlanner, discovered: List<LocalObstacle>): TrajectoryPlanner =
        base.withHoles(discovered.map { circle(Point(it.x, it.y), holeRadiusM(it, base.robot)) })
    /** Physical planner (escapes): discovered obstacles at their physical radius. */
    fun physicalRoutes(base: TrajectoryPlanner, discovered: List<LocalObstacle>): TrajectoryPlanner =
        base.withHoles(discovered.map { circle(Point(it.x, it.y), it.radiusM) })

    /**
     * Back-off after a contact without a straight trail behind the robot (contact just after a pivot: it came from
     * another direction; 2026-10-15, 17:33 and 17:34: "Recul impossible" then mowing stopped twice at the same place).
     * Straight back-off of [BLIND_BACK_OFF_M], chassis checked on the map (borders, exclusions, known obstacles), like
     * the release of the collision lock.
     */
    const val BLIND_BACK_OFF_M = 0.30

    /** Fictitious straight trail behind the robot, for the [BLIND_BACK_OFF_M] back-off. */
    fun straightBehind(position: Point, headingDegrees: Double): List<Point> {
        val rad = Math.toRadians(headingDegrees)
        return listOf(Point(position.x - 0.6 * cos(rad), position.y - 0.6 * sin(rad)), position)
    }

    /** Trail breaks ([MissionTrail.BREAK]) excluded: never back off along an untravelled jump. */
    private fun distanceToTrail(p: Point, trail: List<Point>): Double = MissionTrail.distance(p, trail)

    /**
     * Admissible back-off points, from the nearest to the farthest: straight line behind the robot, heading unchanged,
     * on the recent trail ([trail]), chassis clear ([physical]: borders, no-go zones, physical obstacles).
     * The first blocked step ends the list: never back off beyond an obstacle.
     */
    fun backOffCandidates(physical: TrajectoryPlanner, position: Point, headingDegrees: Double, trail: List<Point>,
                          maxM: Double = MAX_BACK_OFF_M): List<Point> {
        if (trail.isEmpty()) return emptyList()
        val rad = Math.toRadians(headingDegrees)
        fun at(d: Double) = Point(position.x - d * cos(rad), position.y - d * sin(rad))
        // The footprint at contact touches the obstacle: check from 5 cm of back-off (the robot moves away from it).
        val from = RobotPose(at(0.05), headingDegrees)
        val result = mutableListOf<Point>()
        var d = MIN_BACK_OFF_M
        while (d <= maxM + 1e-9) {
            val q = at(d)
            if (distanceToTrail(q, trail) > TRAIL_TOLERANCE_M) break
            if (physical.motionCollision(from, RobotPose(q, headingDegrees)) != null) break
            result += q
            d += 0.1
        }
        return result
    }

    /** Chosen back-off: the shortest that allows pivoting and travelling with the margins, otherwise the longest
     * allowing a physical pivot (the departure then includes an escape). */
    fun backOff(routes: TrajectoryPlanner, physical: TrajectoryPlanner, position: Point, headingDegrees: Double,
                trail: List<Point>, maxM: Double = MAX_BACK_OFF_M): Point? {
        val candidates = backOffCandidates(physical, position, headingDegrees, trail, maxM)
        return candidates.firstOrNull { routes.safe(it, it) && routes.supportsPivot(it) }
            ?: candidates.lastOrNull { physical.supportsPivot(it) }
    }

    private class Piece(val a: Point, val b: Point, val cutting: Boolean, val bladesOn: Boolean, val escape: Boolean, val origin: Int) {
        val meters get() = a.distance(b)
        val heading get() = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
        // Exact ends: a piece kept to the end finishes at the same point as the route (approach of the base).
        fun at(t: Double) = when (t) { 0.0 -> a; 1.0 -> b; else -> Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) }
        fun sub(u: Double, v: Double) = Piece(at(u), at(v), cutting, bladesOn, escape, origin)
    }

    /** Parameter interval of [a, b] within [r] of [c], or null. */
    private fun blocked(a: Point, b: Point, c: Point, r: Double): Pair<Double, Double>? {
        val dx = b.x - a.x; val dy = b.y - a.y; val fx = a.x - c.x; val fy = a.y - c.y
        val aa = dx * dx + dy * dy
        if (aa < 1e-12) return if (hypot(fx, fy) < r) 0.0 to 1.0 else null
        val bb = 2 * (fx * dx + fy * dy); val cc = fx * fx + fy * fy - r * r
        val disc = bb * bb - 4 * aa * cc
        if (disc <= 0) return null
        val s = sqrt(disc)
        val t0 = (-bb - s) / (2 * aa); val t1 = (-bb + s) / (2 * aa)
        if (t1 <= 0 || t0 >= 1) return null
        return max(0.0, t0) to min(1.0, t1)
    }

    /** Kept pieces, grouped into continuous runs separated by a removed portion (null: portion removed outright). */
    private fun runs(pieces: List<Piece?>, discs: List<Pair<Point, Double>>): List<List<Piece>> {
        val runs = mutableListOf<MutableList<Piece>>()
        var gap = true
        for (piece in pieces) {
            if (piece == null) { gap = true; continue }
            val cuts = discs.mapNotNull { (c, r) -> blocked(piece.a, piece.b, c, r) }.sortedBy { it.first }
            var cursor = 0.0
            fun keep(u: Double, v: Double) {
                if ((v - u) * piece.meters < 1e-6) return
                if (gap || runs.isEmpty()) runs += mutableListOf<Piece>()
                runs.last() += piece.sub(u, v); gap = false
            }
            for ((u, v) in cuts) {
                if (v <= cursor) continue
                keep(cursor, max(cursor, u))
                gap = true; cursor = v
            }
            keep(cursor, 1.0)
        }
        return runs.map { it.toList() }
    }

    /** Kept end of a move without mowing replaced by a direct link: arrival point and heading imposed. */
    private const val TRANSIT_END_M = 0.05

    /**
     * Move without mowing (link, access, return to base) crossed by an obstacle reserve: bypassing it then resuming the
     * route just behind the obstacle lengthened the transit for nothing. The whole sequence of non-cutting edges is
     * removed (null); the repair link then connects the end of the previous cutting (or the robot pose) directly to the
     * start of the next cutting, by the shortest path ([hugs], [TrajectoryPlanner.between]). At the end of the route,
     * only the last [TRANSIT_END_M] are kept: the arrival point (approach of the base) and its heading stay imposed.
     */
    private fun directTransits(pieces: List<Piece>, discs: List<Pair<Point, Double>>): List<Piece?> {
        val out = mutableListOf<Piece?>()
        var i = 0
        while (i < pieces.size) {
            if (pieces[i].cutting) { out += pieces[i]; i++; continue }
            var j = i
            while (j < pieces.size && !pieces[j].cutting) j++
            val crossed = (i until j).any { k -> discs.any { (c, r) -> blocked(pieces[k].a, pieces[k].b, c, r) != null } }
            when {
                !crossed -> out += pieces.subList(i, j)
                j == pieces.size -> {
                    val last = pieces[j - 1]
                    out += null
                    out += if (last.meters <= TRANSIT_END_M) last else last.sub(1 - TRANSIT_END_M / last.meters, 1.0)
                }
                else -> out += null
            }
            i = j
        }
        return out
    }

    private fun length(run: List<Piece>) = run.sumOf { it.meters }
    /** [run] without its first [offset] metres, or null if not enough is left. */
    private fun dropStart(run: List<Piece>, offset: Double): List<Piece>? {
        if (offset <= 0) return run
        var left = offset
        run.forEachIndexed { i, p ->
            if (left < p.meters - 0.02) return listOf(p.sub(left / p.meters, 1.0)) + run.drop(i + 1)
            left -= p.meters
        }
        return null
    }
    /** [run] without its last [offset] metres, or null if not enough is left. */
    private fun dropEnd(run: List<Piece>, offset: Double): List<Piece>? {
        if (offset <= 0) return run
        var left = offset
        for (i in run.indices.reversed()) {
            val p = run[i]
            if (left < p.meters - 0.02) return run.take(i) + p.sub(0.0, 1 - left / p.meters)
            left -= p.meters
        }
        return null
    }

    private fun edges(points: List<Point>, bladesOn: Boolean) = points.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-8 }
        .map { (a, b) -> SimEdge(a, b, false, bladesOn = bladesOn) }

    /** Link entirely inside a mowing zone: blades kept, like the prepared links. */
    private fun insideOneZone(path: List<Point>, zones: List<List<Point>>): Boolean {
        if (path.size < 2) return false
        val line = factory.createLineString(path.map { Coordinate(it.x, it.y) }.toTypedArray())
        return zones.filter { it.size >= 3 }.any { points ->
            runCatching {
                val polygon = factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
                    .let { if (it.isValid) it else it.buffer(0.0) }
                PreparedGeometryFactory.prepare(polygon.buffer(BladeContinuity.TOLERANCE_M, 8)).covers(line)
            }.getOrDefault(false)
        }
    }

    /** Radii tried for a tight detour, beyond [keepOutM] (distance kept by the tracking). */
    private val HUG_EXTRA_M = listOf(0.08, 0.15, 0.25, 0.4)
    /** Maximum angular step of the detour arc (degrees). */
    private const val HUG_STEP_DEG = 20.0
    /** Maximum arc: beyond it, the detour goes almost all the way round the obstacle (crossed tangents). */
    private const val HUG_MAX_SWEEP = 1.4 * PI
    /** Distance to the drawn borders below which a side is penalised (m). */
    const val BORDER_COMFORT_M = 1.5
    /** 1 m of missing distance to the drawn borders weighs like [BORDER_WEIGHT] m of detour: inner side. */
    const val BORDER_WEIGHT = 6.0
    /** Pivot: 1° weighs ≈ 1/150 m (advance 0.3 m/s, pivot 45°/s). */
    private const val TURN_WEIGHT = 1.0 / 150
    /** Sampling step of the clearance and difficulties along a detour (m). */
    private const val SAMPLE_M = 0.25

    /** Label of a detour's side for the messages (" on the left, inner side"), empty without detour. */
    fun sideLabel(detour: BypassDetour?): String = when {
        detour == null || detour.side == 0 -> ""
        else -> (if (detour.side > 0) tr(" on the left", " par la gauche") else tr(" on the right", " par la droite")) +
            (if (detour.inner && detour.borderClearanceM < BORDER_COMFORT_M + 1.0) tr(", inner side", ", côté intérieur") else "")
    }

    /** [tolerant]: footprint within the tracking tolerance of the physical outline ([TrajectoryPlanner.pathTolerant]). */
    private class Hug(val points: List<Point>, val detour: BypassDetour, val cost: Double, val tolerant: Boolean)

    private fun polylineLength(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> a.distance(b) }

    private fun samples(points: List<Point>): List<Point> {
        val out = mutableListOf(points.first())
        points.zipWithNext().forEach { (a, b) ->
            val n = max(1, ceil(a.distance(b) / SAMPLE_M).toInt())
            for (k in 1..n) out += Point(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n)
        }
        return out
    }

    private fun turnDegrees(points: List<Point>, fromHeading: Double, toHeading: Double): Double {
        fun diff(a: Double, b: Double) = abs(((b - a) % 360 + 540) % 360 - 180)
        val headings = points.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-6 }
            .map { (a, b) -> Math.toDegrees(atan2(b.y - a.y, b.x - a.x)) }
        if (headings.isEmpty()) return diff(fromHeading, toHeading)
        return diff(fromHeading, headings.first()) + headings.zipWithNext().sumOf { (a, b) -> diff(a, b) } +
            diff(headings.last(), toHeading)
    }

    /**
     * Tangent, arc around [c] at radius [r], tangent: from [from] to [to] passing on the left ([side] +1, obstacle on
     * the robot's right) or on the right (-1). Arc vertices at the circumscribed radius: the chords stay at least [r]
     * from the centre. Null if an end is too close or if the arc goes almost all the way round.
     */
    internal fun arcAround(from: Point, to: Point, c: Point, r: Double, side: Int): List<Point>? {
        val dFrom = from.distance(c); val dTo = to.distance(c)
        if (dFrom <= r + 0.01 || dTo <= r + 0.01) return null
        val rotation = -side.toDouble() // left of the route = clockwise around the obstacle
        val t1 = atan2(from.y - c.y, from.x - c.x) + rotation * acos(r / dFrom)
        val t2 = atan2(to.y - c.y, to.x - c.x) - rotation * acos(r / dTo)
        val sweep = (((t2 - t1) * rotation) % (2 * PI) + 2 * PI) % (2 * PI)
        if (sweep > HUG_MAX_SWEEP) return null
        val steps = max(1, ceil(Math.toDegrees(sweep) / HUG_STEP_DEG).toInt())
        val delta = sweep / steps
        val outer = r / cos(delta / 2)
        val arc = (0..steps).map { k ->
            val a = t1 + rotation * delta * k
            Point(c.x + outer * cos(a), c.y + outer * sin(a))
        }
        return listOf(from) + arc + to
    }

    private fun distanceToSegment(c: Point, a: Point, b: Point) = Geometry.distance(c, a, b)

    /**
     * Possible tight detours from [from] to [to] around the discovered obstacle closest to the segment, sorted by cost:
     * length, pivots, distance to the drawn borders of the prepared mission ([base]: the inner side is preferred near a
     * border) and known difficulties ([difficulty], per metre). Each detour is checked with the mission margins
     * ([routes]) and keeps the tracking reserve of all obstacles. A side tries increasing radii up to the first detour
     * that keeps the tracking tolerance of the physical outline; the tighter detours remain candidates, as a last
     * resort.
     */
    private fun hugs(base: TrajectoryPlanner, routes: TrajectoryPlanner, from: Point, fromHeading: Double,
                     to: Point, toHeading: Double, discovered: List<LocalObstacle>,
                     difficulty: ((Point) -> Double)?): List<Hug> {
        if (from.distance(to) < 0.02) return emptyList()
        val robot = base.robot
        val obstacle = discovered.minByOrNull { distanceToSegment(Point(it.x, it.y), from, to) } ?: return emptyList()
        val center = Point(obstacle.x, obstacle.y)
        if (distanceToSegment(center, from, to) > clipRadiusM(obstacle, robot) + 0.5) return emptyList()
        fun clearance(points: List<Point>) = samples(points).minOf { base.borderClearance(it) }
        fun keepsOut(points: List<Point>) = points.zipWithNext().all { (a, b) ->
            discovered.all { o -> Geometry.distance(Point(o.x, o.y), a, b) >= keepOutM(o, robot) + 0.01 }
        }
        val out = mutableListOf<Hug>()
        fun consider(points: List<Point>, side: Int, radius: Double, opposite: Double?): Boolean {
            if (!keepsOut(points) || !routes.pathSafe(points, fromHeading, toHeading)) return false
            val meters = polylineLength(points)
            val clear = clearance(points)
            val hardship = difficulty?.let { cost -> samples(points).zipWithNext().sumOf { (a, b) -> cost(b) * a.distance(b) } } ?: 0.0
            val cost = meters + TURN_WEIGHT * turnDegrees(points, fromHeading, toHeading) +
                BORDER_WEIGHT * max(0.0, BORDER_COMFORT_M - min(clear, BORDER_COMFORT_M)) + hardship
            // Tolerance towards the borders, exclusions and the reserve of the discovered obstacles: at the minimum
            // radius, the chassis passed 3 cm from the reserve checked by the tracking.
            val tolerant = routes.pathTolerant(points, fromHeading, toHeading)
            out += Hug(points, BypassDetour(center, side, radius, meters, clear, false,
                inner = opposite == null || clear >= opposite - 1e-6), cost, tolerant)
            return tolerant
        }
        consider(listOf(from, to), 0, distanceToSegment(center, from, to), null)
        // Each side: the smallest radius practicable with the tracking tolerance.
        for (side in listOf(+1, -1)) for (extra in HUG_EXTRA_M) {
            val radius = keepOutM(obstacle, robot) + extra
            val points = arcAround(from, to, center, radius, side) ?: continue
            val opposite = arcAround(from, to, center, radius, -side)?.let(::clearance)
            if (consider(points, side, radius, opposite)) break
        }
        return out.sortedBy { it.cost }
    }

    /**
     * Link [link] from [from] (heading [fromHeading]) to the entry of a pass (heading [toHeading]) that keeps the
     * tracking tolerance ([TrajectoryPlanner.pathTolerant]), pivots included. The optional escape (`escape` edges,
     * physical surface) is not concerned: checked from its end.
     */
    /**
     * [link] meets the pass it joins along its axis when the pass lies near the outline
     * ([TrajectoryPlanner.arrivalAligned]): strict searches prefer an entry further on to a nose-first arrival.
     */
    internal fun arrivesAligned(routes: TrajectoryPlanner, link: List<SimEdge>, toHeading: Double): Boolean {
        val last = link.lastOrNull { !it.escape } ?: return true
        return routes.arrivalAligned(last.a, last.b, toHeading)
    }

    internal fun followTolerant(routes: TrajectoryPlanner, link: List<SimEdge>, from: Point, fromHeading: Double, toHeading: Double): Boolean {
        val joined = link.filterNot { it.escape }
        val origin = joined.firstOrNull()?.a ?: link.lastOrNull()?.b ?: from
        val heading = link.lastOrNull { it.escape }?.let { Math.toDegrees(atan2(it.b.y - it.a.y, it.b.x - it.a.x)) } ?: fromHeading
        return routes.pathTolerant(listOf(origin) + joined.map { it.b }, heading, toHeading)
    }

    /**
     * Repairs [path] from edge [index] (current at the time of the stop) for the robot at [position] with heading
     * [headingDegrees]. [discovered]: obstacles discovered during the mission (all of them, not only the last).
     * [base]/[physicalBase]: planners of the prepared mission.
     * [contact]: prior back-off from [position] along [trail], then first link blades OFF.
     */
    fun repair(base: TrajectoryPlanner, physicalBase: TrajectoryPlanner?, path: List<SimEdge>, origins: List<Int>?,
               index: Int, position: Point, headingDegrees: Double, discovered: List<LocalObstacle>,
               zones: List<List<Point>>, contact: Boolean, trail: List<Point> = emptyList(),
               difficulty: ((Point) -> Double)? = null): RouteRepair {
        require(index in path.indices) { tr("Interrupted edge outside the route", "Arête interrompue hors trajet") }
        require(discovered.isNotEmpty()) { tr("No obstacle to bypass", "Aucun obstacle à contourner") }
        require(origins == null || origins.size == path.size)
        require(position.x.isFinite() && position.y.isFinite() && headingDegrees.isFinite())
        val robot = base.robot
        val routes = missionRoutes(base, discovered)
        val physical = physicalRoutes(physicalBase ?: base, discovered)
        val backOff = if (contact) requireNotNull(backOff(routes, physical, position, headingDegrees, trail)
            ?: backOff(routes, physical, position, headingDegrees, straightBehind(position, headingDegrees), BLIND_BACK_OFF_M)) {
            tr("Back-off impossible after the contact: trail missing or chassis blocked (border, exclusion or obstacle behind)",
                "Recul impossible après le contact : trace absente ou châssis bloqué (bord, exclusion ou obstacle derrière)")
        } else null
        val start = backOff ?: position
        // Current edge truncated at the robot's projection, then the rest of the route.
        val current = path[index]
        val dx = current.b.x - current.a.x; val dy = current.b.y - current.a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-12) 1.0 else (((start.x - current.a.x) * dx + (start.y - current.a.y) * dy) / l2).coerceIn(0.0, 1.0)
        val pieces = (index until path.size).map { i ->
            val e = path[i]
            // Kept pieces checked again with the margins: no inherited escape.
            Piece(e.a, e.b, e.cutting, e.bladesOn, false, origins?.get(i) ?: i)
        }.toMutableList()
        pieces[0] = pieces[0].sub(t, 1.0)
        val discs = discovered.map { Point(it.x, it.y) to clipRadiusM(it, robot) }
        val remainingCutting = pieces.filter { it.cutting }.sumOf { it.meters }
        val runs = runs(directTransits(pieces, discs), discs)
        val target = path.last().b
        require(runs.isNotEmpty() && runs.last().last().b.distance(target) < 1e-6) {
            tr("End of the route inside the reserve of a discovered obstacle: bypass impossible",
                "Fin du trajet dans la réserve d'un obstacle découvert : contournement impossible")
        }
        val out = mutableListOf<SimEdge>(); val outOrigins = mutableListOf<Int>()
        var bypasses = 0; var bypassMeters = 0.0
        var pending: List<Piece>? = null
        fun commit(run: List<Piece>) = run.forEach { p ->
            out += SimEdge(p.a, p.b, p.cutting, p.escape, p.bladesOn); outOrigins += p.origin
        }
        val entryOffsets = listOf(0.0, 0.3, 0.6, 1.0, 1.5, 2.5, 4.0)
        val exitTrims = listOf(0.0, 0.3, 0.6, 1.0)
        val detours = mutableListOf<BypassDetour>()
        // After a contact, the first link (bypass of the object hit) is blades OFF.
        var bladesOffLink = contact
        /**
         * Link from [from] to [to]: tight detour on the cheapest side that keeps the tracking tolerance, otherwise
         * [fallback] (mission navigation). Blades stopped, the 50 cm rule to the drawn borders applies first; a closer
         * detour (border or tolerance) is only chosen when there is no other link.
         */
        fun link(from: Point, fromHeading: Double, to: Point, toHeading: Double, bladesOn: Boolean,
                 fallback: () -> List<SimEdge>): List<SimEdge> {
            val candidates = hugs(base, routes, from, fromHeading, to, toHeading, discovered, difficulty)
            fun take(hug: Hug): List<SimEdge> {
                // Pass near the outline joined along its axis, not nose first ([TrajectoryPlanner.alignedArrival]).
                val points = routes.alignedArrival(hug.points, fromHeading, toHeading)
                val on = bladesOn && insideOneZone(points, zones)
                if (hug.detour.side != 0 || hug.points.size > 2) detours += hug.detour.copy(bladesOn = on)
                return edges(points, on)
            }
            val preferred = candidates.firstOrNull { it.tolerant && (bladesOn || it.detour.borderClearanceM >= BORDER_CLEARANCE_M) }
            preferred?.let { return take(it) }
            return try { fallback() } catch (e: IllegalArgumentException) {
                candidates.firstOrNull()?.let { take(it) } ?: throw e
            }
        }
        for ((k, run) in runs.withIndex()) {
            val last = k == runs.lastIndex
            // Remainder too short between two reserves: ignored rather than joined.
            if (!last && length(run) < 0.10) continue
            var linked = false
            val tails = if (pending == null) listOf<List<Piece>?>(null) else exitTrims.mapNotNull { trim -> dropEnd(pending!!, trim) }
            // First a link keeping the tracking tolerance up to and including the entry pivot ([followTolerant]): on
            // 2026-10-14, the robot pivoted right at the edge at the resume point and the tracking stopped. Failing that,
            // the first safe one.
            search@ for (strict in listOf(true, false)) for (tail in tails) for (offset in entryOffsets) {
                val head = dropStart(run, offset) ?: break
                val entry = head.first()
                val detoursBefore = detours.size
                val link: List<SimEdge> = try {
                    if (tail == null) {
                        if (start.distance(entry.a) < 0.02) emptyList()
                        // First link blades OFF (transit): optional escape included.
                        else link(start, headingDegrees, entry.a, entry.heading, false) {
                            RobotRoute.departure(routes, physical, start, headingDegrees, entry.a, entry.heading)
                        }
                    } else {
                        val exit = tail.last()
                        val bladesOn = exit.bladesOn && entry.bladesOn && !bladesOffLink
                        link(exit.b, exit.heading, entry.a, entry.heading, bladesOn) {
                            val points = routes.alignedArrival(routes.between(exit.b, entry.a, exit.heading, entry.heading), exit.heading, entry.heading)
                            routes.requirePath(points, exit.heading, entry.heading)
                            edges(points, bladesOn && insideOneZone(points, zones))
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    while (detours.size > detoursBefore) detours.removeAt(detours.lastIndex)
                    continue
                }
                if (strict && !(followTolerant(routes, link, tail?.last()?.b ?: start, tail?.last()?.heading ?: headingDegrees, entry.heading) &&
                        arrivesAligned(routes, link, entry.heading))) {
                    while (detours.size > detoursBefore) detours.removeAt(detours.lastIndex)
                    continue
                }
                tail?.let { commit(it) }
                if (link.isNotEmpty()) { bypasses++; bypassMeters += link.sumOf { it.meters }; bladesOffLink = false }
                link.forEach { out += it; outOrigins += -1 }
                pending = head; linked = true
                break@search
            }
            require(linked || !last) { tr("No safe link around the obstacle up to the end of the route", "Aucun raccord sûr autour de l'obstacle jusqu'à la fin du trajet") }
        }
        commit(requireNotNull(pending))
        require(out.isNotEmpty() && out.last().b.distance(target) < 1e-6) { tr("Repaired route incomplete", "Trajet réparé incomplet") }
        // Complete check of the rest, from the end of the optional escape (already verified).
        val joined = out.filterNot { it.escape }
        val fromHeading = out.lastOrNull { it.escape }?.let { Math.toDegrees(atan2(it.b.y - it.a.y, it.b.x - it.a.x)) } ?: headingDegrees
        if (joined.isNotEmpty()) routes.requirePath(listOf(joined.first().a) + joined.map { it.b }, fromHeading, null)
        RobotRoute.requireOutsideBaseZone(routes, out)
        val skipped = (remainingCutting - out.filter { it.cutting }.sumOf { it.meters }).coerceAtLeast(0.0)
        return RouteRepair(out, outOrigins, routes, physical, backOff, bypasses, bypassMeters, skipped, detours)
    }
}
