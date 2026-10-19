package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/**
 * Border loops (limit and no-go) with the chassis inside the zone, whatever the outer-loop margin.
 * 2026-10-15: Fosse at 20 cm from the borders, the loop of a no-go zone close to the limit passed 21 cm from it (chassis
 * 5 cm outside); the preparation refused the mission ("Trajet hors de la surface sûre"), at 30 cm the loop was dropped.
 */
class BorderMarginChassisTest {
    private val factory = GeometryFactory()
    private fun shapes(): List<MapShape> {
        val a = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().readText()).getJSONArray("shapes")
        return (0 until a.length()).map { i -> val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                val v = p.getJSONArray(k); Point(v.getDouble(0), v.getDouble(1)) }, s.getString("name")) }
    }

    @Test fun borderLoopsKeepTheChassisInsideEveryReferenceZone() {
        val shapes = shapes()
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val robot = RobotEnvelope.LUBA2
        shapes.filter { it.kind == ShapeKind.ZONE }.forEach { zone ->
            val chassis = factory.createPolygon((zone.points + zone.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
                .buffer(-(RecordedBoundary.centerInsetM(robot) - 1e-4), 16)
            for (cm in listOf(5, 15, 20, 25, 30, 40)) {
                val preview = Geometry.plan(zone.points, holes, 0.25, 0.6, CoverageMode.LINES, true, noGoPasses = 1,
                    recordedBoundary = true, outerOffsetM = cm / 100.0)
                preview.borders.forEach { loop ->
                    val line = factory.createLineString(loop.map { Coordinate(it.x, it.y) }.toTypedArray())
                    assertTrue("${zone.name.trim()} at $cm cm: border loop less than half a width from the limit",
                        chassis.covers(line))
                }
            }
        }
    }

    @Test fun fosseAtTwentyCentimetersPreparesTheFullMission() {
        val shapes = shapes(); val map = RobotMap(shapes, true)
        val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
        val zone = shapes.single { it.name.trim() == "Fosse" }
        val routes = RoutePlanning.mission(map, emptyList(), emptyList(), base)
        val start = RoutePlanning.missionStart(routes, null, true, base.point) ?: base.point
        val config = PlanningConfig(spacingM = 0.25, marginM = 0.6, boundary = BoundaryMeaning.RECORDED_TRACE, outerOffsetM = 0.20)
        val planned = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), start, config = config,
            navigation = PlanningNavigation(emptyList(), base), end = base.point.takeIf { it != start }))
        assertFalse(planned.preview.localOnly)
        val engine = MowingSimulation(routes, base.point, planned.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
        assertTrue(engine.edges.all { routes.safe(it.a, it.b) })
    }
}
