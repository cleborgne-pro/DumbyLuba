package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Mission checkpoint and smart resume with a spiral plan (one long run of rings joined by slanted links, arcs of 4° per
 * vertex at the corners): the preview is saved and rebuilt as is, and a mowing interrupted in the middle of a corner arc or
 * of a straight edge resumes there, the remainder unchanged, also when the smart resume reorders it after a recharge.
 */
class SpiralResumeTest {
    private val zone = listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 9.0), Point(0.0, 9.0))
    private val map = RobotMap(listOf(MapShape(1, 0, zone)), true)
    private val preview = CoveragePlanner.plan(zone, emptyList(), 0.25, 0.6, CoverageMode.SPIRAL, true, recordedBoundary = true,
        continuous = true, cutWidthM = 0.4)
    private fun engine(p: Preview = preview) = MowingSimulation(TrajectoryPlanner(map, 0.6, emptyList(), recordedBoundary = true), Point(2.0, 2.0), p, map, SimConfig())
    private val straight: (Point, Double?, Point, Double?) -> Double? = { a, _, b, _ -> a.distance(b) }

    private fun checkpoint(e: MowingSimulation, edge: Int, point: Point?) = MissionCheckpoint("AA:BB", MissionProgress.mapKey(map),
        MissionProgress.fingerprint(e.edges), e.edges.size, e.preview, true, 45, 20, 90, edge, point,
        MissionProgress.cuttingMetersBefore(e.edges, edge, point), MissionProgress.totalCuttingMeters(e.edges), 1000L)

    private fun heading(s: SimEdge) = Math.toDegrees(atan2(s.b.y - s.a.y, s.b.x - s.a.x))
    private fun turn(a: SimEdge, b: SimEdge) = abs(((heading(b) - heading(a) + 540) % 360) - 180)

    /** A cutting edge in the middle of a corner arc (2.4 cm steps of about 4°) and a long straight cutting edge. */
    private fun arcEdge(e: MowingSimulation) = (e.edges.size / 3 until e.edges.size - 1).first { i ->
        val s = e.edges[i]
        s.cutting && s.meters < 0.05 && e.edges[i - 1].cutting && e.edges[i + 1].cutting &&
            turn(e.edges[i - 1], s) in 3.0..5.0 && turn(s, e.edges[i + 1]) in 3.0..5.0
    }
    private fun straightEdge(e: MowingSimulation) = e.edges.indices.drop(e.edges.size / 3).first { e.edges[it].cutting && e.edges[it].meters > 3.0 }

    @Test fun spiralPreviewRoundTripsAndRebuildsTheSamePlan() {
        assertEquals(CoverageMode.SPIRAL, preview.mode)
        assertEquals(listOf(CoverageMode.SPIRAL), preview.zoneModes)
        assertEquals(0, preview.angle)
        val e = engine()
        val decoded = PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(e.preview).toString()))
        assertEquals(e.preview, decoded)
        assertEquals(MissionProgress.fingerprint(e.edges), MissionProgress.fingerprint(engine(decoded).edges))
    }

    @Test fun resumeInTheMiddleOfAnArcOrOfAStraightEdgeKeepsTheRemainder() {
        val e = engine()
        for ((label, k) in listOf("arc" to arcEdge(e), "edge" to straightEdge(e))) {
            val edge = e.edges[k]
            val point = Point((edge.a.x + edge.b.x) / 2, (edge.a.y + edge.b.y) / 2)
            val plan = RobotRoute.missionEdges(e, Point(3.0, 3.0), 0.0, resume = MissionResume(k, point))
            val expected = MissionProgress.remaining(e.edges, k, point)
            assertEquals(label, expected, plan.edges.subList(plan.mowingFrom, plan.mowingTo))
            plan.edges.zipWithNext().forEach { (a, b) -> assertEquals(label, a.b, b.a) }
            assertEquals(label, k, plan.engineEdge(plan.mowingFrom))
            // Backed off along the interrupted edge, never before its start: on a 2.4 cm arc edge, its start.
            val first = plan.edges[plan.mowingFrom].a
            if (label == "arc") assertEquals(edge.a, first) else assertEquals(point.distance(first), MissionProgress.OVERLAP_M, 1e-6)
            val cp = checkpoint(e, k, point)
            SmartResume.afterRecharge(e.edges, cp, e.baseApproach, 0.0, e.baseApproach, 180.0, straight)?.let { choice ->
                assertEquals(label, SmartResume.remainingCutting(e.edges, cp), SmartResume.remainingCutting(e.edges, choice.checkpoint), 1e-6)
                assertEquals(label, cp.doneMeters, choice.checkpoint.doneMeters, 1e-6)
            }
            println("SPIRAL resume $label: edge $k of ${e.edges.size}, %.1f m left of %.1f m".format(
                SmartResume.remainingCutting(e.edges, cp), MissionProgress.totalCuttingMeters(e.edges)))
        }
    }
}
