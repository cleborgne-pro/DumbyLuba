package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.RobotEnvelope
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.*

data class RobotPose(val position: Point, val headingDegrees: Double) {
    init { require(position.x.isFinite() && position.y.isFinite() && headingDegrees.isFinite()) { tr("Invalid robot pose", "Pose robot invalide") } }
}
enum class EnvelopeMotion { STRAIGHT, TURN, ARC }
data class EnvelopeCollision(val segment: Int, val motion: EnvelopeMotion, val pose: RobotPose) {
    val description: String get() = (if (motion == EnvelopeMotion.STRAIGHT) tr("Chassis collision while moving at segment ${segment + 1} (%.2f m, %.2f m)", "Collision du châssis en déplacement au segment ${segment + 1} (%.2f m, %.2f m)")
        else tr("Chassis collision during the turn at segment ${segment + 1} (%.2f m, %.2f m)", "Collision du châssis pendant le virage au segment ${segment + 1} (%.2f m, %.2f m)")).format(pose.position.x, pose.position.y)
}

/** Check in meters in the map plane. Unknown ground is forbidden.
 * Translation: exact convex hull of the two rectangles. Rotation: steps <= 5°, convex hull of the extreme poses, grown
 * by the maximum sagitta of the arcs swept by the corners.
 * This continuous upper bound ALSO covers the space between samples, not only the poses.
 */
class TrajectoryEnvelope(area: JtsGeometry, val robot: RobotEnvelope = RobotEnvelope.LUBA2) {
    private val factory = GeometryFactory()
    init { require(!area.isEmpty && area.isValid) { tr("Free physical area invalid or empty", "Surface physique libre invalide ou vide") } }
    // Exact distances to the edge of the free area, without a JTS allocation per check.
    private val index = AreaIndex(area, 0.0)
    // Immutable area and robot: reuse the exact checks repeated by the candidates.
    private fun <K, V> boundedCache() = object : LinkedHashMap<K, V>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > 4096
    }
    // Envelope shared between planners: synchronized cache access, exact computation outside the lock (a possible
    // double computation gives the same result).
    private inline fun <K : Any, V : Any> LinkedHashMap<K, V>.cached(key: K, compute: () -> V): V {
        synchronized(this) { this[key] }?.let { return it }
        val value = compute()
        synchronized(this) { this[key] = value }
        return value
    }
    private data class Straight(val from: Point, val to: Point, val heading: Double)
    private val straightSafe = boundedCache<Straight, Boolean>()
    private val pivotDiscSafe = boundedCache<Point, Boolean>()
    private data class Turn(val at: Point, val from: Double, val to: Double)
    // The pivot disc may be forbidden while a small turn is safe.
    // These precise angular checks are repeated on the same station polylines: remember their successes and
    // collisions too, without lightening them.
    private data class TurnResult(val collision: EnvelopeCollision?)
    private val turnResults = boundedCache<Turn, TurnResult>()
    private val maxAngleStep = 5.0
    private fun footprint(pose: RobotPose): List<Coordinate> {
        val a = Math.toRadians(pose.headingDegrees)
        return listOf(robot.frontM to robot.halfWidthM, robot.frontM to -robot.halfWidthM,
            -robot.rearM to -robot.halfWidthM, -robot.rearM to robot.halfWidthM).map { (x, y) ->
            Coordinate(pose.position.x + x*cos(a) - y*sin(a), pose.position.y + x*sin(a) + y*cos(a))
        }
    }
    fun footprintGeometry(pose: RobotPose): JtsGeometry {
        val corners = footprint(pose)
        return factory.createPolygon((corners + corners.first()).toTypedArray())
    }
    /**
     * Chassis inside the free area whatever its measured heading: disc of half the width around the center.
     * Position check (drift tolerated on return), independent of the heading, in addition to the oriented checks.
     */
    fun bodySafe(center: Point): Boolean = index.containsDisc(center.x, center.y, robot.halfWidthM)
    fun poseSafe(pose: RobotPose): Boolean {
        val corners = footprint(pose)
        return index.containsConvex(DoubleArray(4) { corners[it].x }, DoubleArray(4) { corners[it].y }, 4, 0.0)
    }
    /** Convex hull (monotone chain) of the eight corners, then strict distance to the edge.
     * In rotation, the margin is the sagitta bounded as by the old JTS polygonal buffer (radius (sagitta + 1e-8) /
     * cos(π/32)): the tested region contains the old one, the check stays at least as cautious. In translation, strict
     * inclusion.
     */
    private fun sweepSafe(from: RobotPose, to: RobotPose, angularChange: Double, radius: Double): Boolean {
        val points = (footprint(from) + footprint(to)).sortedWith(compareBy({ it.x }, { it.y }))
        val hull = ArrayList<Coordinate>(9)
        fun cross(o: Coordinate, a: Coordinate, b: Coordinate) = (a.x-o.x)*(b.y-o.y)-(a.y-o.y)*(b.x-o.x)
        for (p in points) {
            while (hull.size >= 2 && cross(hull[hull.size-2], hull[hull.size-1], p) <= 0) hull.removeAt(hull.size-1)
            hull.add(p)
        }
        val lower = hull.size + 1
        for (p in points.asReversed().drop(1)) {
            while (hull.size >= lower && cross(hull[hull.size-2], hull[hull.size-1], p) <= 0) hull.removeAt(hull.size-1)
            hull.add(p)
        }
        hull.removeAt(hull.size-1)
        val clearance = if (angularChange != 0.0) {
            val sagitta = radius * (1 - cos(Math.toRadians(abs(angularChange)) / 2))
            (sagitta + 1e-8) / cos(Math.PI / 32)
        } else 0.0
        return index.containsConvex(DoubleArray(hull.size) { hull[it].x }, DoubleArray(hull.size) { hull[it].y }, hull.size, clearance)
    }
    /**
     * Elementary tracking motion: pivot in place (same position) or translation at constant heading.
     * Shared by the pilot navigation and the gateway's on-board tracking (which only receives the area).
     */
    fun motionCollision(from: RobotPose, to: RobotPose): EnvelopeCollision? =
        if (from.position.distance(to.position) < 1e-9) turnCollision(from.position, from.headingDegrees, to.headingDegrees)
        else {
            require(abs(((to.headingDegrees - from.headingDegrees + 540) % 360) - 180) < 1e-7) { tr("Translation with heading change not modelled", "Translation avec changement de cap non modélisée") }
            straightCollision(from.position, to.position, from.headingDegrees)
        }
    fun straightCollision(from: Point, to: Point, heading: Double, segment: Int = 0): EnvelopeCollision? {
        val a = RobotPose(from, heading); val b = RobotPose(to, heading)
        val safe = straightSafe.cached(Straight(from, to, heading)) { sweepSafe(a, b, 0.0, 0.0) }
        return if (safe) null else EnvelopeCollision(segment, EnvelopeMotion.STRAIGHT, a)
    }
    fun turnCollision(at: Point, fromHeading: Double, toHeading: Double, segment: Int = 0): EnvelopeCollision? {
        val start = RobotPose(at, fromHeading)
        require(toHeading.isFinite()) { tr("Invalid final heading", "Cap final invalide") }
        val delta = ((toHeading - fromHeading) % 360 + 540) % 360 - 180
        if (abs(delta) > 1e-9 && robot.minimumTurnRadiusM > 0.0)
            throw IllegalArgumentException(tr("This model requires a rolling turn: pivoting in place is forbidden", "Ce modèle exige un virage roulant : pivot sur place interdit"))
        // Fast conservative test, especially useful for the many links in open areas.
        val discSafe = pivotDiscSafe.cached(at) {
            // Pivot disc (radius bounded like the old JTS buffer), strict distance to the edge.
            val r = robot.turnClearanceM / cos(Math.PI / 32)
            index.containsDisc(at.x, at.y, r)
        }
        if (discSafe) return null
        val checked=turnResults.cached(Turn(at,fromHeading,toHeading)) {
            TurnResult(angularCollision(start,delta,0.0,0,EnvelopeMotion.TURN))
        }
        // The index belongs to the calling path, not to the cached geometry.
        return checked.collision?.copy(segment=segment)
    }
    /** Rolling arc: radius in the telemetry frame, + sign = left. */
    fun arcCollision(start: RobotPose, signedAngle: Double, radius: Double, segment: Int = 0): EnvelopeCollision? {
        require(signedAngle.isFinite() && abs(signedAngle) <= 360 && radius.isFinite() && radius > 0 && radius >= robot.minimumTurnRadiusM) { tr("Invalid turn radius or angle", "Rayon ou angle de virage invalide") }
        return angularCollision(start, signedAngle, radius, segment, EnvelopeMotion.ARC)
    }
    private fun angularCollision(start: RobotPose, delta: Double, radius: Double, segment: Int, motion: EnvelopeMotion): EnvelopeCollision? {
        val steps = max(1, ceil(abs(delta) / maxAngleStep).toInt())
        val sign = if(delta >= 0) 1.0 else -1.0
        val initial = Math.toRadians(start.headingDegrees)
        val center = Point(start.position.x - sign*radius*sin(initial), start.position.y + sign*radius*cos(initial))
        fun pose(step: Int): RobotPose {
            val angle = delta*step/steps
            val r = Math.toRadians(angle)
            val dx = start.position.x-center.x; val dy = start.position.y-center.y
            return RobotPose(Point(center.x+dx*cos(r)-dy*sin(r), center.y+dx*sin(r)+dy*cos(r)), start.headingDegrees+angle)
        }
        for (step in 0 until steps) {
            val from = pose(step); val to = pose(step+1)
            if (!sweepSafe(from, to, delta/steps, radius+robot.turnClearanceM)) return EnvelopeCollision(segment, motion, from)
        }
        return null
    }
    /** Current kinematics: forward translation, stop and short pivot at each vertex.
     * The initial/final heading also allows checking links, U-turns and ring closures.
     */
    fun pathCollision(path: List<Point>, initialHeading: Double? = null, finalHeading: Double? = null): EnvelopeCollision? {
        require(path.isNotEmpty() && path.all { it.x.isFinite() && it.y.isFinite() }) { tr("Empty or invalid path", "Trajectoire vide ou invalide") }
        var heading = initialHeading
        path.zipWithNext().forEachIndexed { index, (a,b) ->
            if (a.distance(b) > 1e-9) {
                val next = Math.toDegrees(atan2(b.y-a.y,b.x-a.x))
                heading?.let { turnCollision(a,it,next,index)?.let { collision -> return collision } }
                straightCollision(a,b,next,index)?.let { return it }
                heading = next
            }
        }
        finalHeading?.let { end -> heading?.let { turnCollision(path.last(),it,end,path.size-1)?.let { collision -> return collision } } }
        if (path.zipWithNext().none { (a,b) -> a.distance(b) > 1e-9 }) {
            val pose = RobotPose(path.first(), finalHeading ?: initialHeading ?: 0.0)
            if (!poseSafe(pose)) return EnvelopeCollision(0,EnvelopeMotion.STRAIGHT,pose)
        }
        return null
    }
    fun requirePath(path: List<Point>, initialHeading: Double? = null, finalHeading: Double? = null) {
        val collision = pathCollision(path,initialHeading,finalHeading)
        require(collision == null) { collision!!.description }
    }
}
