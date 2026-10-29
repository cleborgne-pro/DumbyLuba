package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.protocol.CollisionLatch
import fr.luba.poc.protocol.OffMapLatch
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.operation.distance.DistanceOp
import org.locationtech.jts.operation.union.UnaryUnionOp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** What a contact during a route was, for the response ([EdgeContact.classify]). */
enum class EdgeContactKind(val code: String) {
    /** The robot reached the limit of its task area (drift, slide): back-off and resume, no obstacle. */
    LIMIT("limit"),
    /** A real object at the drawn border, on the planned line: obstacle path with a tighter estimate ([ObstacleBypass.CONTACT_EDGE_MARGIN_M]). */
    OBJECT_AT_EDGE("object_at_edge"),
    /** Anything else (mid-zone object, sensors active, no map): obstacle path unchanged. */
    OBJECT("object"),
}

/**
 * Result of [EdgeContact.classify]. [bumper]: front bumper point; [borderDistM] / [centerBorderDistM]: signed distance
 * of the bumper point / of the robot centre to the drawn border (+ inside), null without a border; [driftM]: how much
 * closer to the border the robot stood than its planned line (centre at the contact and recent trail);
 * [approachDegrees]: angle between the heading and the outward direction of the border; [sameSpot]: a previous limit
 * contact, or an obstacle discovered during the mission, lies within [EdgeContact.EDGE_SAME_SPOT_M]; [frontBorderDistM]: same as [borderDistM] for the
 * point of the front edge (corners included) nearest to the border.
 */
data class EdgeContactClass(
    val kind: EdgeContactKind, val cause: String, val bumper: Point, val borderDistM: Double?, val centerBorderDistM: Double?,
    val driftM: Double, val approachDegrees: Double?, val sameSpot: Boolean,
    /** Signed distance to the border of the front edge point nearest to it (corners included), + inside. */
    val frontBorderDistM: Double? = null,
)

/**
 * Contact at the border of the task area (request of 2026-10-28: "sometimes the robot drifts near the limit, bumps
 * into it, detects an obstacle and bypasses it; it should just back off and resume the route").
 *
 * Single decision point, shared by the Fold (local tracking and both gateways: the gateway hands back the contact or
 * the collision lock, the Fold decides), the obstacle simulation and the tests. Pure: no robot state, no time.
 *
 * - [EdgeContactKind.LIMIT]: **a)** collision lock (self-check 25) without a contact code, bumper and lift sensors
 *   known and clear (`sensorStatus and 7 == 0`), robot centre beyond the zones and passages or less than
 *   [OffMapLatch.INSIDE_TOLERANCE_M] inside them: the rule that already exempts the joystick and the moves
 *   ([OffMapLatch]), extended to routes; **or b)** coded bumper contact with the front edge (bumper centre or a front
 *   corner: at 45° the corner touches while the centre is still 18 cm back) between [LIMIT_BAND_M] inside and
 *   [BEYOND_MAX_M] beyond the drawn border, heading within [APPROACH_DEGREES] of the outward direction of
 *   the border (the front bumper hit it), and a drift of at least [DRIFT_M] towards the border: the centre at the
 *   contact, or a point of the last [DRIFT_WINDOW_M] of trail, closer to the border than the nearest point of the
 *   planned line by that much. A pivot slide shows up as such a drift. A second limit contact within
 *   [EDGE_SAME_SPOT_M] of a previous one of the same mission is an object at the edge (`same_spot`), and so is a limit
 *   contact within [EDGE_SAME_SPOT_M] of an obstacle discovered during the mission (`near_obstacle`: its resume link
 *   would have to go around that obstacle). The caller also drops the limit path when the resume link exceeds
 *   [MAX_LINK_M] ([longLink]).
 * - [EdgeContactKind.OBJECT_AT_EDGE]: coded contact whose front edge lies at most [OBJECT_BAND_M] inside the border
 *   (the project's 50 cm border rule for blade-OFF links, plus tracking) or beyond it, without being a limit contact
 *   (2026-10-28 09:52:08: wood pile, bumper point 0.41 m inside, deviation 4 cm: the planned line itself touches the
 *   object; 09:53:53: hedge met on a blade-OFF link kept 0.5 m from the border).
 * - [EdgeContactKind.OBJECT]: everything else, unchanged. A lock without a code with bumper or lift bits set, or
 *   unknown sensors, is never a limit contact.
 */
object EdgeContact {
    /** Front edge (bumper centre or corner) at most this far inside the border for a coded limit contact. */
    const val LIMIT_BAND_M = 0.15
    /** Front edge at most this far beyond the border for a coded limit contact. */
    const val BEYOND_MAX_M = 0.40
    /** Heading within this angle of the outward direction of the border: the front bumper hit it. */
    const val APPROACH_DEGREES = 75.0
    /** Drift towards the border, from the planned line, that makes a coded contact at the border a limit contact. */
    const val DRIFT_M = 0.08
    /** Recent trail checked for the drift (about 10 s at the route speeds). */
    const val DRIFT_WINDOW_M = 1.5
    /**
     * Front edge at most this far inside the border: object at the edge. The 50 cm border rule of the blade-OFF links
     * applies to the robot centre line: a contact on such a link, heading along the border, puts the bumper 0.50 m
     * inside, plus the tracking tolerance (2026-10-28 09:53:53, hedge met in transit).
     */
    const val OBJECT_BAND_M = 0.60
    /** A second limit contact this close to a previous one (bumper points) is an object at the edge. */
    const val EDGE_SAME_SPOT_M = 1.5
    /**
     * Longest blades-OFF resume link after a limit contact (plan 2.7: back on the line after at most 1 m of back-off
     * and 2 m of link). Longer: the limit path is dropped for the obstacle path ([longLink]).
     */
    const val MAX_LINK_M = 2.0
    /** Limit contacts per mission; beyond, the stop is kept. */
    const val MAX_PER_MISSION = 4
    /** Planned line searched around the interrupted edge (behind, ahead) for the drift. */
    private const val PLANNED_BEHIND_M = 3.0
    private const val PLANNED_AHEAD_M = 1.0

    private val factory = GeometryFactory()

    /**
     * Drawn border of the task area: zones and passages (passage corridors widened like
     * [PointPlanner.beyondMapped]); no-go zones are not part of it ([PointPlanner.atExclusion] handles them).
     */
    class Border(zones: List<List<Point>>, passages: List<List<Point>> = emptyList(), robot: RobotEnvelope = RobotEnvelope.LUBA2) {
        private val area = run {
            val parts = zones.filter { it.size >= 3 }.map { p ->
                factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).let { if (it.isValid) it else it.buffer(0.0) }
            } + passages.filter { it.size >= 2 }.map { line ->
                factory.createLineString(line.map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(robot.halfWidthM + 0.05 + 0.4)
            }
            require(parts.isNotEmpty()) { tr("No zone", "Aucune zone") }
            UnaryUnionOp.union(parts)
        }
        private val inside = PreparedGeometryFactory.prepare(area)
        private val outline = area.boundary

        /** Signed distance from [p] to the border: positive inside the task area. */
        fun signedDistance(p: Point): Double {
            val g = factory.createPoint(Coordinate(p.x, p.y))
            val d = outline.distance(g)
            return if (inside.covers(g)) d else -d
        }

        /** Nearest point of the border to [p]. */
        fun nearest(p: Point): Point {
            val c = DistanceOp.nearestPoints(outline, factory.createPoint(Coordinate(p.x, p.y)))[0]
            return Point(c.x, c.y)
        }
    }

    /** Border of [map] (zones and passages), or null when the map is incomplete, a demonstration, or has no zone. */
    fun border(map: RobotMap, robot: RobotEnvelope = RobotEnvelope.LUBA2): Border? {
        if (!map.complete || map.source.contains("DÉMONSTRATION", true)) return null
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }.map(MapShape::points)
        if (zones.isEmpty()) return null
        return runCatching { Border(zones, map.shapes.filter { it.kind == ShapeKind.PASSAGE && it.points.size >= 2 }.map(MapShape::points), robot) }.getOrNull()
    }

    /** Front bumper point of the robot at [position], heading [headingDegrees]. */
    fun bumper(position: Point, headingDegrees: Double, robot: RobotEnvelope = RobotEnvelope.LUBA2): Point {
        val r = Math.toRadians(headingDegrees)
        return Point(position.x + robot.frontM * cos(r), position.y + robot.frontM * sin(r))
    }

    /** Polyline of [path] from [PLANNED_BEHIND_M] before edge [index] to [PLANNED_AHEAD_M] after its start. */
    private fun plannedWindow(path: List<SimEdge>, index: Int): List<Point> {
        if (path.isEmpty()) return emptyList()
        val i = index.coerceIn(path.indices)
        var from = i; var behind = 0.0
        while (from > 0 && behind < PLANNED_BEHIND_M) { from--; behind += path[from].meters }
        var to = i; var ahead = path[i].meters
        while (to < path.lastIndex && ahead < PLANNED_AHEAD_M) { to++; ahead += path[to].meters }
        return listOf(path[from].a) + (from..to).map { path[it].b }
    }

    /** Last [DRIFT_WINDOW_M] of [trail] (its last continuous piece), most recent first. */
    private fun recent(trail: List<Point>): List<Point> {
        val out = mutableListOf<Point>()
        var meters = 0.0
        for (k in trail.indices.reversed()) {
            val p = trail[k]
            if (MissionTrail.isBreak(p)) break
            out.lastOrNull()?.let { meters += it.distance(p) }
            if (meters > DRIFT_WINDOW_M) break
            out += p
        }
        return out
    }

    /**
     * Drift towards the border: largest amount by which the robot centre ([position], and the last [DRIFT_WINDOW_M] of
     * [trail]) stood closer to the border than the nearest point of the planned line around edge [index] of [path].
     * 0 when the robot stayed on its line or inside it.
     */
    fun drift(border: Border, path: List<SimEdge>, index: Int, position: Point, trail: List<Point>): Double {
        val window = plannedWindow(path, index)
        if (window.size < 2) return 0.0
        fun offset(p: Point): Double {
            val nearest = window.zipWithNext().map { (a, b) -> closest(p, a, b) }.minBy { it.distance(p) }
            return border.signedDistance(nearest) - border.signedDistance(p)
        }
        return (listOf(position) + recent(trail)).maxOf(::offset).coerceAtLeast(0.0)
    }

    private fun closest(p: Point, a: Point, b: Point): Point {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-12) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
        return Point(a.x + t * dx, a.y + t * dy)
    }

    private fun angleBetween(a: Double, b: Double) = abs(((b - a) % 360 + 540) % 360 - 180)

    /**
     * Classifies a contact at [position] / [headingDegrees] (stopped pose). [coded]: bumper code received (-1014);
     * false: collision lock ([selfCheck] 25) without a code. [sensors]: `sensor_status` read after the stop.
     * [border]: [border] of the map; null: always [EdgeContactKind.OBJECT]. [path] / [index]: route followed and
     * interrupted edge; [trail]: robot trail; [previousLimits]: bumper points of the limit contacts already handled in
     * this mission; [discovered]: obstacles discovered during this mission (estimated after a contact).
     */
    fun classify(coded: Boolean, selfCheck: Long?, sensors: Int?, position: Point, headingDegrees: Double, border: Border?,
                 path: List<SimEdge>, index: Int, trail: List<Point>, previousLimits: List<Point> = emptyList(),
                 discovered: List<LocalObstacle> = emptyList(), robot: RobotEnvelope = RobotEnvelope.LUBA2): EdgeContactClass {
        val bumper = bumper(position, headingDegrees, robot)
        if (border == null || !position.x.isFinite() || !position.y.isFinite() || !headingDegrees.isFinite())
            return EdgeContactClass(EdgeContactKind.OBJECT, "no_border", bumper, null, null, 0.0, null, false)
        val bumperDist = border.signedDistance(bumper)
        val centerDist = border.signedDistance(position)
        val frontDist = run {
            val r = Math.toRadians(headingDegrees)
            val lx = -sin(r) * robot.halfWidthM; val ly = cos(r) * robot.halfWidthM
            minOf(bumperDist, border.signedDistance(Point(bumper.x + lx, bumper.y + ly)), border.signedDistance(Point(bumper.x - lx, bumper.y - ly)))
        }
        // Outward direction of the border at the bumper point (at the centre when the bumper lies right on the border).
        val approach = run {
            val near = border.nearest(bumper)
            val (dx, dy) = if (near.distance(bumper) > 1e-6) {
                if (bumperDist >= 0) (near.x - bumper.x) to (near.y - bumper.y) else (bumper.x - near.x) to (bumper.y - near.y)
            } else border.nearest(position).let { n ->
                if (centerDist >= 0) (n.x - position.x) to (n.y - position.y) else (position.x - n.x) to (position.y - n.y)
            }
            if (abs(dx) + abs(dy) < 1e-9) null else angleBetween(headingDegrees, Math.toDegrees(atan2(dy, dx)))
        }
        val drift = drift(border, path, index, position, trail)
        fun result(kind: EdgeContactKind, cause: String, sameSpot: Boolean = false) =
            EdgeContactClass(kind, cause, bumper, bumperDist, centerDist, drift, approach, sameSpot, frontDist)
        val cause = if (!coded) {
            // a) Lock without a code: same rule as the joystick and the moves ([OffMapLatch.ignored]).
            if (!OffMapLatch.ignored(selfCheck, sensors, centerDist <= OffMapLatch.INSIDE_TOLERANCE_M))
                return result(EdgeContactKind.OBJECT, if (selfCheck == CollisionLatch.VALUE && sensors != null && sensors and 7 == 0) "lock_inside" else "sensors")
            "lock_at_limit"
        } else {
            // b) Coded contact: front edge in the narrow band at the border, front bumper towards it, after a drift.
            val inBand = frontDist <= LIMIT_BAND_M && frontDist >= -BEYOND_MAX_M
            val facing = approach != null && approach <= APPROACH_DEGREES
            if (!(inBand && facing && drift >= DRIFT_M)) return if (frontDist <= OBJECT_BAND_M) result(EdgeContactKind.OBJECT_AT_EDGE, "object_at_edge")
                else result(EdgeContactKind.OBJECT, "mid_zone")
            "coded_with_drift"
        }
        // Second limit contact at the same place: not a transient drift any more.
        if (previousLimits.any { it.distance(bumper) < EDGE_SAME_SPOT_M }) return result(EdgeContactKind.OBJECT_AT_EDGE, "same_spot", sameSpot = true)
        // Next to an obstacle discovered during the mission (distance to its estimated disc): the resume link would go
        // around it (QA 2026-10-28, Enfer: 52 m blades OFF), and the object probably extends along the border.
        if (discovered.any { (Point(it.x, it.y).distance(bumper) - it.radiusM).coerceAtLeast(0.0) < EDGE_SAME_SPOT_M })
            return result(EdgeContactKind.OBJECT_AT_EDGE, "near_obstacle", sameSpot = true)
        return result(EdgeContactKind.LIMIT, cause)
    }

    /** Planning margin of the disc estimated after a contact of [kind] ([ObstacleBypass.contactObstacle]). */
    fun marginM(kind: EdgeContactKind): Double =
        if (kind == EdgeContactKind.OBJECT_AT_EDGE) ObstacleBypass.CONTACT_EDGE_MARGIN_M else ObstacleBypass.CONTACT_MARGIN_M

    /**
     * Reason to drop the limit path when the resume link of [DriftRecovery.plan] is longer than [maxM]
     * ([MAX_LINK_M]), or null: the contact then goes down the obstacle path, not counted as a limit contact.
     */
    fun longLink(rejoinMeters: Double, maxM: Double = MAX_LINK_M): String? = if (rejoinMeters <= maxM) null else
        tr("resume link of %.1f m blades OFF, more than %.1f m: handled as an obstacle", "raccord de reprise de %.1f m lames OFF, plus de %.1f m : traité comme un obstacle")
            .format(rejoinMeters, maxM)

    /** Stop reason when one more limit contact is not allowed ([count] already handled in the mission), or null. */
    fun refusal(count: Int): String? = if (count >= MAX_PER_MISSION)
        tr("$MAX_PER_MISSION border contacts already handled, stop kept", "$MAX_PER_MISSION contacts en bordure déjà traités, arrêt maintenu") else null
}
