package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.TrajectoryPlanner
import org.junit.Assert.*
import org.junit.Test

/** Transits kept away from the DANGER boundary portions ([TrajectoryPlanner.dangerClearance]). */
class DangerTransitTest {
    private val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
    private val kept = TrajectoryPlanner.dangerCenterDistance()

    /** 16 × 10 m zone, no-go 6..10 × 1.5..8: the short way between the two halves runs along the bottom edge. */
    private fun map() = RobotMap(listOf(
        MapShape(1, 0, rectangle(w = 16.0, h = 10.0), "Pelouse"),
        MapShape(2, 1, rectangle(6.0, 1.5, 4.0, 6.5), "Massif")), true, "danger")
    private val bottom = listOf(Point(0.0, 0.0), Point(16.0, 0.0))

    private fun samples(path: List<Point>): List<Point> = path.zipWithNext().flatMap { (a, b) ->
        val n = maxOf(1, (a.distance(b) / 0.05).toInt())
        (0..n).map { i -> Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n) }
    }

    @Test fun transitLeavesTheShortWayAlongADangerPortion() {
        val start = Point(3.0, 2.5); val end = Point(13.0, 2.5)
        val plain = RoutePlanning.navigation(map(), margin, listOf(map().shapes[1].points))
        val usual = plain.between(start, end)
        assertTrue("without DANGER portion, the short way under the bed: $usual", usual.minOf { it.y } < kept)
        val guarded = RoutePlanning.navigation(map(), margin, listOf(map().shapes[1].points), dangerLines = listOf(bottom))
        val path = guarded.between(start, end)
        guarded.requirePath(path)
        assertEquals(start, path.first()); assertEquals(end, path.last())
        val worst = samples(path).minOf { guarded.dangerClearance(it) }
        assertTrue("chassis ${TrajectoryPlanner.DANGER_TRANSIT_MARGIN_M} m from the DANGER portion: $worst, $path", worst >= -1e-6)
        assertTrue("goes round the bed by the top: $path", path.maxOf { it.y } > 8.0)
        // Same link with headings (mission links).
        val headed = guarded.between(start, end, 0.0, 0.0)
        assertTrue(samples(headed).minOf { guarded.dangerClearance(it) } >= -1e-6)
    }

    @Test fun endsInsideTheDangerBandOnlyLeaveItStraightAway() {
        val start = Point(3.0, 0.7); val end = Point(13.0, 0.7)
        val guarded = RoutePlanning.navigation(map(), margin, listOf(map().shapes[1].points), dangerLines = listOf(bottom))
        val path = guarded.between(start, end)
        guarded.requirePath(path)
        val inside = samples(path).filter { guarded.dangerClearance(it) < -1e-6 }
        assertTrue("only the exit and the arrival cross the band: $path",
            inside.all { minOf(it.distance(start), it.distance(end)) <= kept + 0.05 })
    }

    @Test fun withoutAnyAlternativeTheUsualTransitIsKept() {
        // DANGER on every side: no surface stays out of the band, the usual safe transit is unchanged.
        val zone = rectangle(w = 16.0, h = 2.0)
        val map = RobotMap(listOf(MapShape(1, 0, zone, "Couloir")), true, "couloir")
        val start = Point(1.0, 1.0); val end = Point(15.0, 1.0)
        val plain = RoutePlanning.navigation(map, margin, emptyList()).between(start, end)
        val guarded = RoutePlanning.navigation(map, margin, emptyList(), dangerLines = listOf(zone + zone.first()))
        assertEquals(plain, guarded.between(start, end))
    }

    @Test fun mowingLinksStayAwayFromTheDangerPortion() {
        val map = map()
        val config = PlanningConfig(spacingM = 0.5, minimumCoverage = 0.4)
        val request = PlanningRequest(map, listOf(1L), Point(1.5, 5.0), config = config, dangerLines = listOf(bottom))
        val result = RoutePlanning.calculate(request)
        assertTrue(result.selected.diagnostics.toString(), result.selected.valid)
        val routes = RoutePlanning.navigation(map, margin, listOf(map.shapes[1].points), dangerLines = listOf(bottom))
        // A link may only cross the band to leave or reach a pass that runs inside it.
        fun worst(mission: List<MissionLeg>): Double = mission.filter { !it.cutting && length(it.path) > 2 * kept + 0.5 }
            .flatMap { link -> val a = link.path.first(); val b = link.path.last()
                samples(link.path).filter { minOf(it.distance(a), it.distance(b)) > kept + 0.3 } }
            .minOfOrNull { routes.dangerClearance(it) } ?: Double.POSITIVE_INFINITY
        val usual = RoutePlanning.calculate(request.copy(dangerLines = emptyList()))
        assertTrue("without DANGER portion, some link runs along the bottom edge", worst(usual.selected.mission) < -0.05)
        assertTrue("links at ${worst(result.selected.mission)} from the DANGER band", worst(result.selected.mission) >= -0.05)
    }

    @Test fun dangerLinesComeFromTheAnnotatedPortions() {
        val map = map()
        val zone = map.shapes[0]
        val selection = requireNotNull(BorderTypes.select(zone, Point(2.0, 0.0), Point(12.0, 0.0)))
        val danger = BorderTypes.annotate(zone, selection, BorderType.DANGER, 1, 0)
        val wall = BorderTypes.annotate(zone, requireNotNull(BorderTypes.select(zone, Point(16.0, 2.0), Point(16.0, 8.0))), BorderType.WALL, 2, 0)
        val lines = BorderTypes.dangerLines(map, listOf(danger, wall))
        assertEquals(1, lines.size)
        assertEquals(10.0, BorderTypes.length(lines.single()), 1e-6)
        assertTrue(lines.single().all { kotlin.math.abs(it.y) < 1e-9 })
    }
}
