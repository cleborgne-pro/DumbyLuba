package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Start point chosen in the preview or saved per zone; otherwise, as close as possible to the robot. */
class ZoneStartTest {
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
    private val base = MapBase(Point(-0.5365, 1.0944), "Journal", 1.05, 152.9, 1.5)
    private val routes by lazy { RoutePlanning.mission(map, emptyList(), emptyList(), base) }
    private val playground = shapes.single { it.name.trim() == "Aire de jeux" }
    private val other = shapes.first { it.kind == ShapeKind.ZONE && it.id != playground.id }
    /** Free point of the Aire de jeux (used by the nearest-start test). */
    private val inside = Point(-15.0, 9.0)
    private val outside = Point(1000.0, 1000.0)

    @Test fun withoutChoiceNorSavedPointTheStartIsTheNearest() {
        assertEquals(StartRequest(null, StartSource.NEAREST), ZoneStart.requested(map, listOf(playground.id), null, emptyMap()))
        assertEquals(StartRequest(null, StartSource.NEAREST), ZoneStart.requested(map, emptyList(), null, emptyMap()))
    }

    @Test fun savedPointOfTheFirstZoneIsTheDefault() {
        val saved = mapOf(playground.id to inside)
        assertEquals(StartRequest(inside, StartSource.SAVED, playground.id), ZoneStart.requested(map, listOf(playground.id, other.id), null, saved))
        // Only the first mowed zone counts: its saved start is missing → nearest.
        assertEquals(StartSource.NEAREST, ZoneStart.requested(map, listOf(other.id, playground.id), null, saved).source)
    }

    @Test fun savedPointOutsideItsZoneIsIgnored() {
        assertEquals(StartSource.NEAREST, ZoneStart.requested(map, listOf(playground.id), null, mapOf(playground.id to outside)).source)
        val noGo = shapes.first { it.kind == ShapeKind.NO_GO && Geometry.inside(it.points.first(), playground.points) }
        val inNoGo = noGo.points.let { pts -> Point(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size) }
        if (Geometry.inside(inNoGo, noGo.points)) assertNull(ZoneStart.zoneAt(map, listOf(playground.id), inNoGo))
    }

    @Test fun choiceInThePreviewWinsOverTheSavedPoint() {
        val chosen = Point(inside.x + 2.0, inside.y)
        assertEquals(playground.id, ZoneStart.zoneAt(map, listOf(playground.id), chosen))
        val saved = mapOf(playground.id to inside)
        assertEquals(StartRequest(chosen, StartSource.CHOSEN, playground.id),
            ZoneStart.requested(map, listOf(playground.id), StartOverride.Chosen(chosen), saved))
        assertEquals(StartRequest(null, StartSource.NEAREST),
            ZoneStart.requested(map, listOf(playground.id), StartOverride.Nearest, saved))
        // Point tapped outside the chosen zones: refused, the default applies.
        assertEquals(StartSource.SAVED, ZoneStart.requested(map, listOf(playground.id), StartOverride.Chosen(outside), saved).source)
    }

    @Test fun storageFormatRoundTripsAndSkipsBrokenLines() {
        val saved = mapOf(playground.id to inside, -3L to Point(0.5, -1.25))
        assertEquals(saved, ZoneStart.decode(ZoneStart.encode(saved)))
        assertEquals(mapOf(4L to Point(1.0, 2.0)), ZoneStart.decode("4|1.0|2.0\nabc\n5|NaN|1\n6|1"))
        assertEquals(emptyMap<Long, Point>(), ZoneStart.decode(null))
    }

    @Test fun chosenPointInAReserveMovesToTheNearestFreePoint() {
        assertEquals(inside, RoutePlanning.nearestUsable(routes, inside))
        val noGo = shapes.single { it.id == 6315065273897031876L }.points
        val edge = noGo.maxBy { it.x }
        val tooClose = Point(edge.x + 0.04, edge.y)
        val free = RoutePlanning.nearestUsable(routes, tooClose)!!
        assertTrue(routes.safe(free, free) && routes.supportsPivot(free))
        assertTrue(free.distance(tooClose) <= 1.0)
        assertNull(RoutePlanning.nearestUsable(routes, outside))
    }

    @Test(timeout = 600_000) fun mowingBeginsNextToTheChosenPoint() {
        val config = PlanningConfig(spacingM = 0.25, marginM = 0.6, cutWidthM = Geometry.CUT_WIDTH_M, minimumCoverage = 0.95,
            speedMps = 0.3, borders = true, borderPasses = 1, noGoPasses = 1, bordersFirst = false, nearbyNoGo = true,
            boundary = BoundaryMeaning.RECORDED_TRACE)
        val chosen = requireNotNull(RoutePlanning.nearestUsable(routes, inside))
        val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(playground.id), chosen, config = config,
            navigation = PlanningNavigation(emptyList(), base), end = base.point))
        assertTrue(plan.report!!.selected.valid)
        val entry = plan.preview.cuttingPaths.first().first()
        val nearest = plan.preview.cuttingPaths.flatMap { listOf(it.first(), it.last()) }.minOf { it.distance(chosen) }
        assertTrue("Entry at ${entry.distance(chosen)} m, nearest possible $nearest m", entry.distance(chosen) <= nearest + 0.5)
        assertEquals(chosen, plan.report.selected.mission.first().path.first())
        assertEquals(base.point, plan.report.selected.mission.last().path.last())
    }
}
