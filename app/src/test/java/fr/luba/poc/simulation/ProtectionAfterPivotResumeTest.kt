package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 2026-10-19 at 14:50:34, Fosse: during a 150° pivot at the end of a pass (heading 152° → 1°), the robot slid 24 cm
 * towards the border and published self-check 25 on a single report, without a contact code. The protection cleared
 * 3 s later, but the automatic resume stopped the mowing with "Back-off impossible: chassis outside the zone along the
 * whole trail within 100 cm behind the robot": after the pivot, the travelled trail is ahead of the robot, not behind
 * it, and the straight escape ahead (1.1.4) was never tried by [DriftRecovery]. The manual "Resume" from the same pose
 * (14:53:49) left with a 30 cm escape ahead. The automatic resume now does the same.
 */
class ProtectionAfterPivotResumeTest {
    private val pose = Point(-37.7043, -3.9563)
    private val heading = 1.2982
    /** Robot positions published from 14:49:50 to 14:50:38 (log d99ace00, 0002.jsonl). */
    private val trail = listOf(
        Point(-33.0872, -6.742), Point(-32.8387, -6.8799), Point(-32.5916, -7.0238), Point(-32.3415, -7.1837),
        Point(-32.0973, -7.3377), Point(-31.8536, -7.4882), Point(-31.6898, -7.5902), Point(-31.6801, -7.6284),
        Point(-31.6584, -7.6629), Point(-31.6225, -7.6844), Point(-31.5976, -7.7081), Point(-31.5889, -7.6701),
        Point(-31.6496, -7.464), Point(-31.6281, -7.4037), Point(-31.6322, -7.3791), Point(-31.8141, -7.2368),
        Point(-32.0545, -7.0895), Point(-32.2989, -6.9399), Point(-32.5452, -6.7925), Point(-32.7851, -6.649),
        Point(-33.04, -6.5045), Point(-33.2955, -6.3719), Point(-33.5428, -6.2352), Point(-33.7897, -6.0957),
        Point(-34.0463, -5.9482), Point(-34.3098, -5.802), Point(-34.5555, -5.6485), Point(-34.8019, -5.5049),
        Point(-35.0327, -5.3526), Point(-35.2605, -5.1968), Point(-35.5134, -5.0409), Point(-35.772, -4.9103),
        Point(-36.0389, -4.7687), Point(-36.2793, -4.6287), Point(-36.5328, -4.4786), Point(-36.8019, -4.3511),
        Point(-36.9573, -4.2899), Point(-37.0291, -4.3258), Point(-37.1157, -4.282), Point(-37.2819, -4.101),
        Point(-37.3421, -3.9796), Point(-37.4248, -3.9587), Point(-37.5504, -3.9668), Point(-37.6743, -3.9859),
        Point(-37.7169, -3.968), Point(-37.704, -3.9567), Point(-37.7029, -3.9571), Point(-37.7043, -3.9563))
    private val obstacles = listOf(
        LocalObstacle("a", ObstacleKind.OTHER, -41.166747820987545, -3.9570483435376067, 0.2, 0.3, createdAtMs = 0, updatedAtMs = 0),
        LocalObstacle("b", ObstacleKind.OTHER, -39.99320048953354, 2.6637080031206204, 0.2, 0.3, createdAtMs = 0, updatedAtMs = 0))

    @Test fun `resume after a protection cleared next to the border leaves straight ahead`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val physical = RoutePlanning.physical(map, obstacles)
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        assertFalse("chassis overlapping the border", DriftRecovery.bodyInside(physical, pose, heading))
        assertTrue("no back-off point on the trail", DriftRecovery.starts(physical, pose, heading, trail).isEmpty())

        val zone = map.shapes.single { it.name.trim() == "Fosse" }
        val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base)))
        val engine = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))
        val path = engine.edges
        val index = path.withIndex().filter { it.value.cutting }.minBy { Geometry.distance(pose, it.value.a, it.value.b) }.index

        val resume = DriftRecovery.plan(routes, physical, path, null, index, pose, heading, trail)
        assertNull("no back-off", resume.backOff)
        val first = resume.edges.first()
        assertTrue("the resume starts with the straight escape", first.escape)
        assertFalse(first.cutting)
        assertEquals(pose, first.a)
        assertEquals(heading, Math.toDegrees(kotlin.math.atan2(first.b.y - first.a.y, first.b.x - first.a.x)), 1e-6)
        assertEquals(-1, resume.origins.first())
    }

    @Test fun `escape towards the border is still refused`() {
        val (map, _) = ReferenceRoutes.referenceMap()
        val physical = RoutePlanning.physical(map, obstacles)
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), recordedBoundary = true)
        val path = listOf(SimEdge(Point(-36.0, -4.6), Point(-33.0, -6.4), true))
        // Facing the border (heading 181°) without a trail: no back-off, and the escape ahead would go further out.
        val error = assertThrows(IllegalArgumentException::class.java) {
            DriftRecovery.plan(routes, physical, path, null, 0, pose, 181.3, emptyList())
        }
        assertTrue(error.message!!, error.message!!.contains("Recul impossible"))
    }
}
