package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Smart resume after a recharge on the reference map (several zones, real navigation, recorded boundary): from the
 * approach point in front of the base, the reordered remainder costs less transit than going back to the interrupted
 * point, the route is accepted by the safety checks ([RobotRoute.missionEdges]) and every remaining stripe is still
 * mowed exactly once.
 */
class SmartResumeReferenceTest {
    @Test(timeout = 600_000) fun `reordered resume after recharge saves transit on the reference map`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val names = listOf("Aire de jeux", "Potager", "Piscine", "Fosse")
        val zones = names.map { n -> map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == n } }
        val plan = RoutePlanning.coverage(PlanningRequest(map, zones.map { it.id }, base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base)))
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        val engine = MowingSimulation(routes, base.point, plan.preview, map,
            SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))
        val edges = engine.edges
        val dockHeading = (engine.dockEntryHeading + 180.0) % 360.0
        val total = MissionProgress.totalCuttingMeters(edges)
        val link = SmartResume.linkMeters(routes)
        val cuttingIndices = edges.indices.filter { edges[it].cutting }
        var gains = 0
        val report = StringBuilder()
        for (fraction in listOf(0.2, 0.35, 0.5, 0.65, 0.8)) {
            // Interrupted in the middle of the cutting edge reached at that share of the plan.
            var acc = 0.0
            val edge = cuttingIndices.first { i -> acc += edges[i].meters; acc >= fraction * total }
            val e = edges[edge]
            val point = Point((e.a.x + e.b.x) / 2, (e.a.y + e.b.y) / 2)
            val interrupted = MissionCheckpoint("AA:BB", "map", "plan", edges.size, plan.preview, true, 50, 20, 90, edge = edge, point = point,
                doneMeters = MissionProgress.cuttingMetersBefore(edges, edge, point), totalMeters = total, startedAt = 1L)
            val choice = SmartResume.afterRecharge(edges, interrupted, engine.baseApproach, dockHeading, engine.baseApproach, dockHeading, link)
            report.append("%.0f %%: ".format(fraction * 100)).append(choice?.let { "%.1f m → %.1f m".format(it.defaultTransitM, it.transitM) } ?: "unchanged").append('\n')
            if (choice == null) continue
            gains++
            assertTrue(choice.savedM >= SmartResume.MIN_GAIN_M)
            // Nothing left out: same cutting metres on every prepared edge.
            fun perEdge(cp: MissionCheckpoint) = SmartResume.spans(cp).flatMap { SmartResume.spanEdges(edges, it, overlap = false) }
                .filter { it.second.cutting }.groupBy({ it.first }, { it.second.meters }).mapValues { it.value.sum() }
            val before = perEdge(interrupted); val after = perEdge(choice.checkpoint)
            assertEquals(before.keys, after.keys)
            before.forEach { (i, m) -> assertEquals("edge $i", m, after.getValue(i), 1e-6) }
            // Route actually executable from the approach point (same checks as a mowing start).
            val mission = RobotRoute.missionEdges(engine, engine.baseApproach, dockHeading, null, MissionResume.of(choice.checkpoint))
            val routed = (mission.mowingFrom until mission.mowingTo).filter { mission.edges[it].cutting }.mapNotNull { i -> mission.engineEdge(i) }.toSet()
            assertEquals(before.keys, routed)
            val transit = mission.edges.filterNot { it.cutting }.sumOf { it.meters }
            val usual = RobotRoute.missionEdges(engine, engine.baseApproach, dockHeading, null, MissionResume.of(interrupted)).edges
                .filterNot { it.cutting }.sumOf { it.meters }
            report.append("   route transit %.1f m (usual resume %.1f m)\n".format(transit, usual))
            assertTrue("route transit $transit m, usual $usual m", transit < usual)
        }
        println(report)
        assertTrue(report.toString(), gains >= 1)
    }
}
