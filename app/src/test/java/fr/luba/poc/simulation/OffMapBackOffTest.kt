package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.protocol.CollisionLatch
import org.junit.Assert.*
import org.junit.Test

/**
 * 2026-10-20: robot driven beyond a zone by "Move to a point", then self-check 25. "Unlock" (30 cm back-off along the
 * trace) was always refused: the back-off was checked against the zone borders only, and the trace of the move was
 * ignored. The trace driven beyond the map now opens a corridor for the back-off, no-go zones and obstacles kept.
 */
class OffMapBackOffTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private fun map(vararg noGo: List<Point>) = RobotMap(listOf(MapShape(1, 0, rect(0.0, 0.0, 20.0, 20.0))) +
        noGo.mapIndexed { i, points -> MapShape((i + 2).toLong(), 1, points) }, true, "BLE")
    /** Robot driven east from inside the zone to 4 m beyond its border (x = 20). */
    private val here = Point(24.0, 10.0)
    private val trail = (0..60).map { Point(18.0 + it * 0.1, 10.0) }

    private fun back(planner: TrajectoryPlanner) =
        ObstacleBypass.backOffCandidates(planner, here, 0.0, trail, CollisionLatch.UNLOCK_BACK_OFF_M).lastOrNull()

    @Test fun unlockBackOffBeyondTheMapFollowsTheDrivenTrace() {
        val m = map()
        assertNull("borders only: refused off the map", back(RoutePlanning.physical(m, emptyList())))
        val target = back(RoutePlanning.backOffPhysical(m, emptyList(), here, trail))
        assertNotNull(target)
        assertEquals(CollisionLatch.UNLOCK_BACK_OFF_M, requireNotNull(target).distance(here), 0.02)
        assertTrue(target.x < here.x)
    }

    @Test fun noGoZoneAndObstacleBehindTheRobotStillRefuseTheBackOff() {
        // Recorded no-go outline (robot centre path) and saved obstacle 30 cm behind the rear of the chassis.
        val m = map(rect(22.0, 8.0, 1.75, 4.0))
        assertNull(back(RoutePlanning.backOffPhysical(m, emptyList(), here, trail)))
        val tree = LocalObstacle("tree", ObstacleKind.OTHER, 23.2, 10.0, 0.2, 0.3, createdAtMs = 0)
        assertNull(back(RoutePlanning.backOffPhysical(map(), listOf(tree), here, trail)))
    }

    @Test fun insideTheZonesTheBordersStayUnchanged() {
        // Robot inside the zone against its border, trace inside: no corridor, same surface as before.
        val inside = (0..30).map { Point(17.0 + it * 0.1, 10.0) }
        assertTrue(PointPlanner.trailCorridors(map(), Point(19.9, 10.0), inside).isEmpty())
        // Old trace beyond the map, robot back inside far from it: no corridor either.
        val out = (0..20).map { Point(20.0 + it * 0.1, 10.0) } + (0..80).map { Point(22.0 - it * 0.1, 10.0 - it * 0.05) }
        assertTrue(PointPlanner.trailCorridors(map(), out.last(), out).isEmpty())
        // Only the last metres of the trace count.
        val far = (0..40).map { Point(20.0 + it * 0.1, 2.0) } + (0..100).map { Point(24.0, 2.0 + it * 0.1) }
        val corridors = PointPlanner.trailCorridors(map(), far.last(), far)
        assertFalse(corridors.isEmpty())
        assertTrue(corridors.flatMap { it.points }.all { it.y > 8.0 })
    }

    @Test fun noTraceGivesNoCorridor() {
        assertTrue(PointPlanner.trailCorridors(map(), here, emptyList()).isEmpty())
        assertNull(back(RoutePlanning.backOffPhysical(map(), emptyList(), here, emptyList())))
    }
}
