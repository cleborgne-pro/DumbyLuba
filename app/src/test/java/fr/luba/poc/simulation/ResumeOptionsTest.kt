package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.RoutePlanning
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Options for resuming an interrupted mowing: "Resume further on" and "Clear". */
class ResumeOptionsTest {
    @get:Rule val folder = TemporaryFolder()

    private val map = RobotMap(listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0)))),true)
    private fun engine() = MowingSimulation(TrajectoryPlanner(map,0.6,emptyList()),Point(2.0,2.0),
        Geometry.plan(map.shapes.first().points,emptyList(),borders=true),map,SimConfig())
    private fun checkpoint(e: MowingSimulation, edge: Int, point: Point?) = MissionCheckpoint("AA:BB", MissionProgress.mapKey(map),
        MissionProgress.fingerprint(e.edges), e.edges.size, e.preview, true, 45, 20, 90, edge, point,
        MissionProgress.cuttingMetersBefore(e.edges,edge,point), MissionProgress.totalCuttingMeters(e.edges), 1000L)

    /** Two 10 m stripes connected by a 1 m link with blades stopped. */
    private val edges = listOf(SimEdge(Point(0.0,0.0),Point(10.0,0.0),true), SimEdge(Point(10.0,0.0),Point(10.0,1.0),false),
        SimEdge(Point(10.0,1.0),Point(0.0,1.0),true))

    @Test fun skipWalksAlongCuttingEdgesOnlyAndLandsOnABand() {
        // 4 m on the first stripe: point at 8 m, without overlap.
        assertEquals(MissionResume(0, Point(8.0, 0.0), overlap = false), MissionProgress.skipAhead(edges, 0, Point(4.0, 0.1), 4.0))
        // 9 m: 6 m left on the first stripe, the link with blades stopped does not count, 3 m on the second.
        val far = requireNotNull(MissionProgress.skipAhead(edges, 0, Point(4.0, 0.0), 9.0))
        assertEquals(2, far.edge)
        assertEquals(7.0, far.point!!.x, 1e-9); assertEquals(1.0, far.point!!.y, 1e-9)
        assertFalse(far.overlap)
        // Exactly the end of the first stripe.
        assertEquals(Point(10.0, 0.0), MissionProgress.skipAhead(edges, 0, Point(4.0, 0.0), 6.0)!!.point)
        // 0 m: normal resume, with overlap; beyond the last stripe: nothing.
        assertEquals(MissionResume(0, Point(4.0, 0.0)), MissionProgress.skipAhead(edges, 0, Point(4.0, 0.0), 0.0))
        assertNull(MissionProgress.skipAhead(edges, 0, Point(4.0, 0.0), 16.5))
        // Stop on the link: the distance starts from the beginning of the next stripe.
        assertEquals(Point(8.0, 1.0), MissionProgress.skipAhead(edges, 1, Point(10.0, 0.5), 2.0)!!.point)
        assertEquals(16.0, MissionProgress.remainingCuttingMeters(edges, 0, Point(4.0, 0.0)), 1e-9)
    }

    @Test fun skippedResumeStartsExactlyAtTheChosenPointWithoutOverlap() {
        val rest = MissionProgress.remaining(edges, 0, Point(8.0, 0.0), overlap = false)
        assertEquals(Point(8.0, 0.0), rest.first().a)
        assertEquals(8.0 - MissionProgress.OVERLAP_M, MissionProgress.remaining(edges, 0, Point(8.0, 0.0)).first().a.x, 1e-9)
    }

    @Test fun skippedBandsAndTapSelection() {
        val from = MissionResume(0, Point(4.0, 0.0))
        val to = MissionResume(2, Point(7.0, 1.0))
        assertEquals(listOf(Point(4.0, 0.0) to Point(10.0, 0.0), Point(10.0, 1.0) to Point(7.0, 1.0)), MissionProgress.cuttingBetween(edges, from, to))
        assertTrue(MissionProgress.cuttingBetween(edges, to, from).isEmpty())
        // Tap the second stripe at x = 7: 9 m further.
        assertEquals(9.0, MissionProgress.skipToward(edges, 0, Point(4.0, 0.0), Point(7.0, 1.3))!!, 1e-9)
        // Already mowed part of the interrupted stripe: brought back to the stop point; closer to a remaining stripe: that one.
        assertEquals(0.0, MissionProgress.skipToward(edges, 0, Point(4.0, 0.0), Point(3.5, 0.0))!!, 1e-9)
        assertEquals(14.0, MissionProgress.skipToward(edges, 0, Point(4.0, 0.0), Point(2.0, 0.2))!!, 1e-9)
        assertNull(MissionProgress.skipToward(edges, 0, Point(4.0, 0.0), Point(5.0, 8.0)))
    }

    @Test fun skippedCheckpointMovesForwardAndCountsSkippedMeters() {
        val e = engine()
        val k = e.edges.indices.first { e.edges[it].cutting && e.edges[it].meters > 4.0 }
        val stop = Point((e.edges[k].a.x * 3 + e.edges[k].b.x) / 4, (e.edges[k].a.y * 3 + e.edges[k].b.y) / 4)
        val cp = checkpoint(e, k, stop)
        val view = requireNotNull(ResumeSkipView.of(e.edges, cp, 5.0))
        assertEquals(5.0, view.skipMeters, 1e-9)
        assertEquals(5.0, view.skipped.sumOf { (a, b) -> a.distance(b) }, 1e-6)
        assertEquals(view.target, view.resume.point)
        val moved = MissionProgress.skipped(cp, e.edges, view.resume)
        assertEquals(5.0, moved.skippedMeters, 1e-6)
        assertEquals(cp.doneMeters + 5.0, moved.doneMeters, 1e-6)
        assertSame(cp, MissionProgress.skipped(cp, e.edges, null))
        // The resumed route starts at the chosen point, without overlap.
        val plan = RobotRoute.missionEdges(e, Point(3.0, 3.0), 0.0, resume = MissionResume(moved.edge, moved.point, overlap = false))
        assertEquals(view.target.x, plan.edges[plan.mowingFrom].a.x, 1e-9)
        assertEquals(view.target.y, plan.edges[plan.mowingFrom].a.y, 1e-9)
        // Never before the stop point, never on a link.
        assertThrows(IllegalStateException::class.java) { MissionProgress.skipped(cp, e.edges, MissionResume(k, e.edges[k].a, false)) }
        val transit = e.edges.indices.firstOrNull { it > k && !e.edges[it].cutting }
        if (transit != null) assertThrows(IllegalStateException::class.java) {
            MissionProgress.skipped(cp, e.edges, MissionResume(transit, e.edges[transit].a, false)) }
        // Bounded slider: reserve at the end of the last stripe.
        val max = ResumeSkipView.maxMeters(e.edges, cp)
        assertEquals(max, ResumeSkipView.of(e.edges, cp, 1e6)!!.skipMeters, 1e-9)
        assertFalse(MissionProgress.mowingDone(e.edges, ResumeSkipView.of(e.edges, cp, 1e6)!!.resume.edge, ResumeSkipView.of(e.edges, cp, 1e6)!!.resume.point))
    }

    @Test fun skippedMetersAreStoredAndOldFilesReadAsZero() {
        val e = engine()
        val store = MissionCheckpointStore(folder.root)
        val cp = checkpoint(e, 2, e.edges[2].a).copy(skippedMeters = 3.5)
        store.save(cp)
        assertEquals(cp, store.read("AA:BB"))
        val file = folder.root.listFiles()!!.single { it.name.startsWith("mission-checkpoint-") }
        val json = org.json.JSONObject(file.readText()).apply { remove("skippedMeters") }
        file.writeText(json.toString())
        assertEquals(0.0, store.read("AA:BB")!!.skippedMeters, 0.0)
    }

    @Test fun backOffFollowsTheTrailThenThePlannedBandThenAStraightLine() {
        val here = Point(8.0, 0.05)
        val planned = ResumeBackOff.plannedBefore(edges, 2, Point(7.0, 1.0))
        assertEquals(listOf(Point(10.0, 0.0), Point(10.0, 1.0), Point(7.0, 1.0)), planned.takeLast(3))
        // Actual trail under the robot: it guides the back-off.
        assertEquals(ResumeBackOff.Guide.TRAIL, ResumeBackOff.guide(here, 0.0, listOf(Point(5.0, 0.0), Point(8.0, 0.0)), emptyList()).first)
        // Trail from elsewhere (app restarted): route mowed before the stop.
        val alongPlanned = ResumeBackOff.plannedBefore(edges, 0, Point(8.0, 0.0))
        assertEquals(ResumeBackOff.Guide.PLANNED, ResumeBackOff.guide(here, 0.0, listOf(Point(0.0, 9.0)), alongPlanned).first)
        // Far from both: straight line behind the robot.
        val (guide, line) = ResumeBackOff.guide(Point(5.0, 6.0), 90.0, emptyList(), alongPlanned)
        assertEquals(ResumeBackOff.Guide.STRAIGHT, guide)
        assertEquals(5.0, line.first().x, 1e-9); assertTrue(line.first().y < 6.0 - ResumeBackOff.MAX_M)
    }

    @Test fun backOffPrefersTheClosestSafePivotPointAndStopsBeforeANoGo() {
        val robot = RobotEnvelope.LUBA2
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 10.0), Point(0.0, 10.0)), "Carré")
        val noGo = MapShape(2, 1, listOf(Point(3.0, 4.0), Point(4.2, 4.0), Point(4.2, 6.0), Point(3.0, 6.0)), "Exclusion")
        val roomy = RobotMap(listOf(zone, noGo), true)
        val physical = RoutePlanning.navigation(roomy, robot.halfWidthM + 0.05, listOf(noGo.points), recordedBoundary = true)
        val routes = RoutePlanning.navigation(roomy, robot.halfWidthM + 0.05, listOf(noGo.points), recordedBoundary = true)
        // In the middle of the zone: the first step (30 cm) is enough, the robot does not back off more than needed.
        val open = Point(8.0, 5.0)
        val target = requireNotNull(ResumeBackOff.target(routes, physical, open, 0.0, listOf(Point(5.0, 5.0), open)))
        assertEquals(ObstacleBypass.MIN_BACK_OFF_M, open.distance(target), 1e-6)
        // Exclusion behind: never beyond it, nor more than MAX_M.
        val close = Point(4.2 + robot.rearM + 0.5, 5.0)
        val back = ResumeBackOff.target(routes, physical, close, 0.0, listOf(Point(1.0, 5.0), close))
        if (back != null) {
            assertTrue(close.distance(back) <= ResumeBackOff.MAX_M + 1e-9)
            assertNull(physical.motionCollision(RobotPose(Point(close.x - 0.05, 5.0), 0.0), RobotPose(back, 0.0)))
        }
    }
}
