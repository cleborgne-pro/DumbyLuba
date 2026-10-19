package fr.luba.poc.map

import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ObstaclePlannerTest {
    private val zone = listOf(Point(0.0, 0.0), Point(16.0, 0.0), Point(16.0, 11.0), Point(0.0, 11.0))

    private fun assertClear(paths: List<List<Point>>, disc: ObstacleDisc) {
        paths.forEach { run ->
            run.forEach { p -> assertTrue(hypot(p.x - disc.x, p.y - disc.y) >= disc.radiusM - 1e-6) }
            run.zipWithNext().forEach { (a, b) ->
                assertTrue(Geometry.distance(disc.center(), a, b) >= disc.radiusM - 1e-6)
            }
        }
    }

    @Test fun `reserve disc kept out of every path`() {
        val disc = ObstacleDisc(8.0, 5.5, 0.73)
        val result = Geometry.plan(zone, emptyList(), obstacles = listOf(disc))
        assertTrue(result.runs.isNotEmpty())
        assertClear(result.runs + result.borders + result.passes, disc)
    }

    @Test fun `luba turning envelope held even with a small planner margin`() {
        val obstacle = LocalObstacle("o1", ObstacleKind.TREE, 8.0, 5.5, radiusM = 0.30, marginM = 0.10)
        val disc = obstacle.keepOutDisc(RobotEnvelope.LUBA2)
        assertTrue(disc.radiusM > 0.7)
        val result = Geometry.plan(zone, emptyList(), margin = 0.3, obstacles = listOf(disc))
        assertClear(result.runs + result.borders + result.passes, disc)
    }

    @Test fun `obstacle near the boundary stays out of the borders`() {
        val disc = ObstacleDisc(1.2, 1.2, 0.8)
        val result = Geometry.plan(zone, emptyList(), obstacles = listOf(disc))
        assertClear(result.runs + result.borders + result.passes, disc)
    }

    @Test fun `obstacle and no-go zone coexist in the same computation`() {
        val hole = listOf(Point(6.0, 4.0), Point(9.0, 4.0), Point(9.0, 7.0), Point(6.0, 7.0))
        val disc = ObstacleDisc(13.0, 3.0, 0.6)
        val result = Geometry.plan(zone, listOf(hole), obstacles = listOf(disc))
        val paths = result.runs + result.borders + result.passes
        assertClear(paths, disc)
        assertTrue(paths.all { run -> run.zipWithNext().all { (a, b) -> Geometry.safe(a, b, zone, listOf(hole), 0.6) } })
    }

    @Test fun `obstacle cuts the passes without removing the coverage`() {
        val disc = ObstacleDisc(8.0, 5.5, 0.9)
        val without = Geometry.plan(zone, emptyList(), borders = false)
        val with = Geometry.plan(zone, emptyList(), borders = false, obstacles = listOf(disc))
        assertTrue(with.runs.isNotEmpty())
        assertTrue(with.passes.size > without.passes.size)
        assertTrue(with.meters > 0)
    }

    @Test fun `multi-zone passes the reserve discs on`() {
        val zones = listOf(
            MapShape(1, 0, listOf(Point(0.0, 0.0), Point(6.0, 0.0), Point(6.0, 5.0), Point(0.0, 5.0)), "Jardin"),
            MapShape(2, 0, listOf(Point(20.0, 0.0), Point(26.0, 0.0), Point(26.0, 5.0), Point(20.0, 5.0)), "Cour"),
        )
        val disc = ObstacleDisc(3.0, 2.5, 0.7)
        val result = MultiZonePlanner.plan(zones, emptyList(), 0.5, 0.6, CoverageMode.LINES, true, obstacles = listOf(disc))
        assertClear(result.runs + result.borders + result.passes, disc)
    }

    @Test fun `obstacle covering the whole zone is refused cleanly`() {
        assertThrows(IllegalArgumentException::class.java) {
            Geometry.plan(zone, emptyList(), obstacles = listOf(ObstacleDisc(8.0, 5.5, 20.0)))
        }
    }

    @Test fun `learned obstacle stuck to a no-go zone bypassed by the no-go border instead of blocking the computation`() {
        // Recorded boundary (real map): the no-go border passed 8 cm from the no-go zone, through the obstacle reserve,
        // and the whole computation failed with "Collision du châssis".
        val hole = listOf(Point(6.0, 4.0), Point(9.0, 4.0), Point(9.0, 7.0), Point(6.0, 7.0))
        val obstacle = LocalObstacle("o1", ObstacleKind.POLE, 9.25, 5.5, radiusM = 0.27, marginM = 0.2)
        val disc = obstacle.keepOutDisc(RobotEnvelope.LUBA2)
        val result = CoveragePlanner.plan(zone, listOf(hole), 0.25, 0.6, CoverageMode.LINES, true,
            obstacles = listOf(disc), recordedBoundary = true)
        assertClear(result.runs + result.borders + result.passes, disc)
        // The no-go loop is kept: it surrounds the no-go zone and the obstacle.
        val noGoLoops = result.borders.drop(result.outerLoops)
        assertTrue("No-go loop kept", noGoLoops.any { loop -> hole.all { Geometry.inside(it, loop) } && Geometry.inside(Point(obstacle.x, obstacle.y), loop) })
    }

    @Test fun `no-go zone too close to the zone boundary has no loop but the computation is kept`() {
        // Tight painted zone: the no-go zone is 5 cm from the edge, its loop would leave the zone.
        val small = listOf(Point(0.0, 0.0), Point(6.0, 0.0), Point(6.0, 5.0), Point(0.0, 5.0))
        val hole = listOf(Point(2.0, 2.0), Point(3.0, 2.0), Point(3.0, 4.95), Point(2.0, 4.95))
        val result = CoveragePlanner.plan(small, listOf(hole), 0.25, 0.6, CoverageMode.LINES, true, recordedBoundary = true)
        assertTrue(result.runs.isNotEmpty())
        assertEquals(0, result.borders.size - result.outerLoops)
        assertTrue(result.skippedBorderLoops >= 1)
        val noGo = org.locationtech.jts.geom.GeometryFactory().let { f ->
            f.createPolygon((hole + hole.first()).map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }.toTypedArray()) }
        assertTrue((result.runs + result.borders).all { run -> run.zipWithNext().all { (a, b) ->
            !noGo.intersects(noGo.factory.createLineString(arrayOf(org.locationtech.jts.geom.Coordinate(a.x, a.y), org.locationtech.jts.geom.Coordinate(b.x, b.y)))) } })
    }
}
