package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests of 2026-10-14: with the obstacle saved 82 cm from the station approach point, the map mowing preview
 * failed for every zone ("Aucune entrée sûre accessible" with the robot charging, "Aucun ordre complet accessible"
 * otherwise): the pass planner received the full obstacle margin, whereas the navigation already adjusted it
 * ([DockClearance]). Same computation as the map: start, passes, preparation.
 */
class DockObstaclePreviewTest {
    private val shapes: List<MapShape> = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    private val map = RobotMap(shapes, true, "reference-station")
    private val base = MapBase(Point(-0.5336, 1.097), "Position et axe observés en recharge", 1.5, 153.04, 1.5)
    private val obstacles = listOf(LocalObstacle("devant", ObstacleKind.OTHER, 1.6227, 0.4706, 0.2, 0.3),
        LocalObstacle("loin", ObstacleKind.OTHER, -19.4727, -8.8001, 0.2, 0.3))
    private val config = PlanningConfig(spacingM = 0.25, marginM = 0.6, cutWidthM = Geometry.CUT_WIDTH_M, minimumCoverage = 0.95,
        speedMps = 0.3, borders = true, borderPasses = 1, noGoPasses = 1, bordersFirst = true, nearbyNoGo = true,
        boundary = BoundaryMeaning.RECORDED_TRACE, outerOffsetM = 0.25)

    @Test(timeout = 600_000) fun mowingPreviewWithAnObstacleInFrontOfTheStation() {
        val zones = shapes.filter { it.kind == ShapeKind.ZONE }
        val planning = DockClearance.planning(obstacles, base)
        assertEquals(0.371, planning.first().radiusM, 0.001)
        assertEquals(0.5, planning.last().radiusM, 1e-9)
        for (selection in listOf(zones.filter { it.name.trim() == "Aire de jeux" }, zones)) for (docked in listOf(true, false)) {
            val routes = RoutePlanning.mission(map, obstacles, emptyList(), base)
            val start = RoutePlanning.missionStart(routes, Point(-12.0, 6.0), docked, base.point)!!
            val planned = RoutePlanning.coverage(PlanningRequest(map, selection.map { it.id }, start, config = config,
                obstacles = planning, navigation = PlanningNavigation(emptyList(), base), end = base.point.takeIf { it != start }))
            println("${selection.size} zone(s), ${if (docked) "charging" else "robot in the garden"} : ${planned.report?.selected?.metrics?.coverage}")
            assertTrue(planned.report!!.selected.valid)
            assertFalse(planned.preview.localOnly)
            val engine = MowingSimulation(routes, base.point, planned.preview, map, SimConfig(speedMps = 0.3, instantCharging = true,
                baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
            engine.routes.requirePath(listOf(engine.edges.first().a) + engine.edges.map { it.b })
        }
    }
}
