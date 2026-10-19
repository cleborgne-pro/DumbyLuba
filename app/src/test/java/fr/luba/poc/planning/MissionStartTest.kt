package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Launch of a mowing or a survey: start as close as possible to the robot, end at the base. */
class MissionStartTest {
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
    /** Actual base (log of 2026-10-10). */
    private val base = MapBase(Point(-0.5365, 1.0944), "Journal", 1.05, 152.9, 1.5)
    private val routes = RoutePlanning.mission(map, emptyList(), emptyList(), base)
    private val playground = shapes.single { it.name.trim() == "Aire de jeux" }

    @Test fun freeRobotPositionIsTheStart() {
        val robot = Point(-15.0, 9.0)
        assertTrue(routes.safe(robot, robot) && routes.supportsPivot(robot))
        assertEquals(robot, RoutePlanning.missionStart(routes, robot, docked = false, base = base.point))
    }

    @Test fun dockedUnknownOrInsideTheBaseZoneStartsFromTheBase() {
        assertEquals(base.point, RoutePlanning.missionStart(routes, Point(-15.0, 9.0), docked = true, base = base.point))
        assertEquals(base.point, RoutePlanning.missionStart(routes, null, docked = false, base = base.point))
        assertEquals(base.point, RoutePlanning.missionStart(routes, Point(-0.3, 1.0), docked = false, base = base.point))
    }

    @Test fun robotStoppedInAReserveStartsFromTheNearestFreePoint() {
        // 4 cm from the elongated no-go zone of the Aire de jeux: no pivot possible in place.
        val noGo = shapes.single { it.id == 6315065273897031876L }.points
        val edge = noGo.maxBy { it.x }
        val robot = Point(edge.x + 0.04, edge.y)
        assertFalse(routes.safe(robot, robot) && routes.supportsPivot(robot))
        val start = RoutePlanning.missionStart(routes, robot, docked = false, base = base.point)!!
        assertNotEquals(robot, start)
        assertTrue(routes.safe(start, start) && routes.supportsPivot(start))
        assertTrue("Free point at ${start.distance(robot)} m", start.distance(robot) <= 1.0)
    }

    @Test(timeout = 600_000) fun mowingStartsNextToTheRobotAndEndsAtTheBase() {
        val robot = Point(-15.0, 9.0)
        for (bordersFirst in listOf(true, false)) {
            val config = PlanningConfig(spacingM = 0.25, marginM = 0.6, cutWidthM = Geometry.CUT_WIDTH_M, minimumCoverage = 0.95,
                speedMps = 0.3, borders = true, borderPasses = 1, noGoPasses = 1, bordersFirst = bordersFirst, nearbyNoGo = true,
                boundary = BoundaryMeaning.RECORDED_TRACE)
            fun plan(start: Point, end: Point?) = RoutePlanning.coverage(PlanningRequest(map, listOf(playground.id), start, config = config,
                navigation = PlanningNavigation(emptyList(), base), end = end))
            val fromRobot = plan(robot, base.point)
            val fromBase = plan(base.point, null)
            assertTrue(fromRobot.report!!.selected.valid)
            val entry = fromRobot.preview.cuttingPaths.first().first()
            val baseEntry = fromBase.preview.cuttingPaths.first().first()
            println("Borders first=$bordersFirst · entry ${"%.2f".format(entry.distance(robot))} m from the robot (from the base: ${"%.2f".format(baseEntry.distance(robot))} m)")
            // Borders first: point of the outer loop closest to the robot where it can pivot
            // (safe loop entry); otherwise a nearby pass end.
            val nearest = if (bordersFirst) fromRobot.preview.borders.take(fromRobot.preview.outerLoops).flatten()
                .filter { routes.supportsPivot(it) }.minOf { it.distance(robot) }
                else fromRobot.preview.cuttingPaths.flatMap { listOf(it.first(), it.last()) }.minOf { it.distance(robot) }
            assertTrue("Entry at ${entry.distance(robot)} m, nearest possible ${nearest} m", entry.distance(robot) <= nearest + 0.5)
            assertTrue(entry.distance(robot) < baseEntry.distance(robot) - 5.0)
            // Start from the robot, mission end evaluated at the base.
            assertEquals(robot, fromRobot.report.selected.mission.first().path.first())
            assertEquals(base.point, fromRobot.report.selected.mission.last().path.last())
        }
    }
}
