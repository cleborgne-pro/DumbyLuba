package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mowing pivots facing no-go zones: the set margin (robot side → outline) also applies during the pivot.
 * 2026-10-15 (Fosse, margin 25 cm): four bumper contacts around the no-go zone (−38.5; −4.6), all during a pivot.
 * The chassis corners sweep the pivot radius (43 cm): at the end of a pass (centre at 60.6 cm) they passed
 * 17.6 cm from the outline, at the entry of the no-go loop (pivot on the loop, centre at 50.6 cm) 7.6 cm.
 */
class NoGoPivotMarginTest {
    private val factory = GeometryFactory()
    private val robot = RobotEnvelope.LUBA2
    private fun shapes(): List<MapShape> {
        val a = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().readText()).getJSONArray("shapes")
        return (0 until a.length()).map { i -> val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                val v = p.getJSONArray(k); Point(v.getDouble(0), v.getDouble(1)) }, s.getString("name")) }
    }
    private fun heading(a: Point, b: Point) = Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
    private fun delta(a: Double, b: Double): Double { var d = (b - a) % 360.0; if (d > 180) d -= 360; if (d < -180) d += 360; return d }

    private class Mission(val edges: List<SimEdge>, val noGo: List<org.locationtech.jts.geom.Geometry>, val endpoints: List<Point>)

    private fun missionEdges(zoneName: String, marginM: Double): Mission {
        val shapes = shapes(); val map = RobotMap(shapes, true)
        val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
        val zone = shapes.single { it.name.trim() == zoneName }
        val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { s ->
            factory.createPolygon((s.points + s.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray()) as org.locationtech.jts.geom.Geometry }
        val routes = RoutePlanning.mission(map, emptyList(), emptyList(), base)
        val start = RoutePlanning.missionStart(routes, null, true, base.point) ?: base.point
        val config = PlanningConfig(spacingM = 0.25, marginM = 0.6, boundary = BoundaryMeaning.RECORDED_TRACE, outerOffsetM = marginM)
        val planned = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), start, config = config,
            navigation = PlanningNavigation(emptyList(), base), end = base.point.takeIf { it != start }))
        val engine = MowingSimulation(routes, base.point, planned.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
        assertTrue(engine.edges.all { routes.safe(it.a, it.b) })
        val endpoints = planned.preview.cuttingPaths.flatMap { listOf(it.first(), it.last()) }
        return Mission(engine.edges.filter { it.meters > 1e-6 }, noGo, endpoints)
    }

    /** Minimum distance from the chassis (rectangle) to the no-go zones during an in-place pivot from [from] to [from]+[turn]. */
    private fun sweepClearance(at: Point, from: Double, turn: Double, noGo: List<org.locationtech.jts.geom.Geometry>): Double {
        var min = Double.POSITIVE_INFINITY
        for (k in 0..40) {
            val h = Math.toRadians(from + turn * k / 40); val ux = cos(h); val uy = sin(h)
            val corners = listOf(robot.frontM to robot.halfWidthM, robot.frontM to -robot.halfWidthM,
                -(robot.lengthM - robot.frontM) to -robot.halfWidthM, -(robot.lengthM - robot.frontM) to robot.halfWidthM)
                .map { (a, b) -> Coordinate(at.x + a * ux - b * uy, at.y + a * uy + b * ux) }
            val rect = factory.createPolygon((corners + corners.first()).toTypedArray())
            noGo.forEach { g -> min = minOf(min, if (g.intersects(rect)) 0.0 else g.distance(rect)) }
        }
        return min
    }

    /**
     * Pivots (follower stop vertices) at the ends of passes and loops (pass ends, loop entries and exits) whose chassis
     * sweep enters the no-go margin. Sharp corners inside a loop (hollows of a no-go zone, tips of the outer loop against
     * a no-go zone close to the limit) are not covered.
     */
    private fun pivotsInsideMargin(mission: Mission, marginM: Double): List<String> {
        val edges = mission.edges; val noGo = mission.noGo
        val stops = PathFollower.stopVertices(edges, 0, edges.size)
        return (0 until edges.size - 1).filter { i -> stops[i] && (edges[i].cutting || edges[i + 1].cutting) &&
            mission.endpoints.any { it.distance(edges[i].b) < 1e-6 } }.mapNotNull { i ->
            // The pivot aims at the rest of the route: heading of the edge after the possible rounded turn (15 cm).
            var j = i + 1; var run = 0.0
            while (j < edges.size - 1 && run + edges[j].meters < PathFollower.ROUNDED_CORNER_M) { run += edges[j].meters; j++ }
            val from = heading(edges[i].a, edges[i].b)
            val turn = delta(from, heading(edges[j].a, edges[j].b))
            val clearance = sweepClearance(edges[i].b, from, turn, noGo)
            if (clearance < marginM - 0.01) "(%.2f ; %.2f) pivot %.0f°: chassis at %.1f cm".format(edges[i].b.x, edges[i].b.y, turn, clearance * 100) else null
        }
    }

    @Test fun fossePivotsKeepTheNoGoMarginAt25cm() {
        val inside = pivotsInsideMargin(missionEdges("Fosse", 0.25), 0.25)
        assertTrue("Pivots inside the 25 cm margin of the no-go zones: $inside", inside.isEmpty())
    }

    @Test fun fossePivotsKeepTheNoGoMarginAt15cm() {
        val inside = pivotsInsideMargin(missionEdges("Fosse", 0.15), 0.15)
        assertTrue("Pivots inside the 15 cm margin of the no-go zones: $inside", inside.isEmpty())
    }

    @Test fun noGoLoopLeadsJoinTheLoopTangentiallyFromAPivotOutsideTheMargin() {
        // 2 m square no-go zone, loop at half a width + 25 cm: entry and exit further out, without a sharp corner.
        val square = listOf(Point(0.0, 0.0), Point(2.0, 0.0), Point(2.0, 2.0), Point(0.0, 2.0))
        val distance = robot.halfWidthM + 0.25
        val polygon = factory.createPolygon((square + square.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        val ring = polygon.buffer(distance, 16) as org.locationtech.jts.geom.Polygon
        for (loop in listOf(ring.exteriorRing.coordinates.map { Point(it.x, it.y) }, ring.exteriorRing.coordinates.map { Point(it.x, it.y) }.reversed())) {
            val lead = requireNotNull(NoGoLoopLeads(listOf(square), robot, 0.25).lead(loop))
            val pivot = robot.turnClearanceM + 0.25
            assertTrue(polygon.distance(factory.createPoint(Coordinate(lead.leadIn.first().x, lead.leadIn.first().y))) >= pivot)
            assertTrue(polygon.distance(factory.createPoint(Coordinate(lead.leadOut.last().x, lead.leadOut.last().y))) >= pivot)
            // Never closer to the no-go zone than the loop, in both directions of travel.
            (lead.leadIn + lead.leadOut).forEach { p ->
                assertTrue(polygon.distance(factory.createPoint(Coordinate(p.x, p.y))) >= distance - 0.01)
            }
            val edges = lead.path.zipWithNext().filter { (a, b) -> a.distance(b) > 1e-9 }.map { (a, b) -> SimEdge(a, b, true) }
            val stops = PathFollower.stopVertices(edges, 0, edges.size)
            assertTrue("Sharp corner on the entry, the loop or the exit", stops.none { it })
        }
    }
}
