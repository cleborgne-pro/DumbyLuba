package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 2026-10-19 at 14:02, Fosse: after a bumper contact, the back-off drifted (wheel slip, heading 90° → 53°) and the robot
 * stopped with its centre 15 cm from the recorded border, chassis overlapping it. "Resume" was then refused twice with
 * "Link unreachable: Fosse → Fosse. No safe local link": the straight escape ahead was rejected because the start pose
 * itself collides. The escape is now accepted when it only moves the chassis away from the edge.
 */
class StuckNearBorderResumeTest {
    private val stuck = Point(-40.2247, 1.4604)
    private val contact = Point(-40.0335, 2.1202)
    private val obstacles = listOf(
        LocalObstacle("a", ObstacleKind.OTHER, -41.166747820987545, -3.9570483435376067, 0.2, 0.3, createdAtMs = 0, updatedAtMs = 0),
        LocalObstacle("b", ObstacleKind.OTHER, -39.99320048953354, 2.6637080031206204, 0.2, 0.3, createdAtMs = 0, updatedAtMs = 0))

    @Test fun `resume from a pose overlapping the border starts with a straight escape`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val physical = RoutePlanning.physical(map, obstacles)
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        val heading = 53.3
        assertFalse("chassis overlapping the border", physical.footprintSafe(RobotPose(stuck, heading)))
        assertFalse("no pivot possible there", physical.supportsPivot(stuck))
        val escape = requireNotNull(RobotRoute.escapePoint(routes, physical, stuck, heading)) { "straight escape ahead expected" }
        assertTrue(routes.safe(escape, escape) && routes.supportsPivot(escape))
        assertEquals(heading, Math.toDegrees(kotlin.math.atan2(escape.y - stuck.y, escape.x - stuck.x)), 1e-6)
        // Towards the border (backwards): refused.
        val behind = Point(stuck.x - 0.5 * kotlin.math.cos(Math.toRadians(heading)), stuck.y - 0.5 * kotlin.math.sin(Math.toRadians(heading)))
        assertFalse(physical.leavesStraight(stuck, behind, heading))

        val zone = map.shapes.single { it.name.trim() == "Fosse" }
        val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base)))
        val engine = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))
        val edge = engine.edges.withIndex().filter { it.value.cutting }.minBy { Geometry.distance(contact, it.value.a, it.value.b) }.index
        val mission = RobotRoute.missionEdges(engine, stuck, heading, physical, MissionResume(edge, contact))
        val first = mission.edges.first()
        assertTrue("the resume starts with the escape", first.escape)
        assertEquals(stuck, first.a)
        assertFalse(first.cutting)
    }
}
