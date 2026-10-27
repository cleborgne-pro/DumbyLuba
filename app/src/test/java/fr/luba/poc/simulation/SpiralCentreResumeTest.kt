package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spiral from the centre ([SpiralDirection.FROM_CENTRE]) through a mission checkpoint and a resume, on zones of the
 * reference map (recorded boundaries, 25 cm, real navigation, as prepared by the map screen), one zone with no safe order
 * from the centre (Aire de jeux, planned from the edge with a warning) mowed with a zone kept from the centre: the
 * checkpoint store keeps the preview and its directions, the rebuilt route matches the saved fingerprint, a resume in the
 * middle of an outward chain drives its remainder outwards, a smart resume after a recharge keeps every cutting metre,
 * and (fails on f83a5f8a) it never drives a chain before the passes of its core or the chains inside it.
 */
class SpiralCentreResumeTest {
    @get:Rule val folder = TemporaryFolder()

    private val reference = ReferenceRoutes.referenceMap()
    private val map = reference.first
    private val base = reference.second
    private val routes by lazy {
        RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
    }
    /** A core pass lies within one cut (40 cm) and one spacing of the first point of its chain. */
    private val reach = 0.4 + 0.25

    /** One task from the centre: its plan, the spirals built ([SpiralSweep.observer]), its prepared route and its rules. */
    private inner class Centre(val names: List<String>) {
        val label = names.joinToString("+")
        val plan: CoveragePlan
        val spirals: List<SpiralSweep.Result>
        init {
            val ids = names.map { n -> map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == n }.id }
            val decisions = java.util.Collections.synchronizedList(mutableListOf<SpiralSweep.Decision>())
            SpiralSweep.observer = { decisions += it }
            try {
                plan = RoutePlanning.coverage(PlanningRequest(map, ids, base.point,
                    config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE,
                        spiralDirection = SpiralDirection.FROM_CENTRE),
                    navigation = PlanningNavigation(emptyList(), base)), CoverageMode.SPIRAL)
            } finally { SpiralSweep.observer = null }
            spirals = decisions.mapNotNull { it.result }
        }
        val preview = plan.preview
        val paths = preview.cuttingPaths
        val outward = spirals.flatMap { it.chains }.map { it.reversed() }.toSet().let { r -> paths.indices.filter { paths[it] in r }.toSet() }
        val engine = this@SpiralCentreResumeTest.engine(preview)
        val edges = engine.edges
        /** First and last prepared edge of every path. */
        val firstEdge = paths.map { p -> edgeOf(edges, p[0], p[1]) }
        val lastEdge = paths.map { p -> edgeOf(edges, p[p.size - 2], p.last()) }
        /** Rules of the zones kept from the centre (a zone planned from the edge enters its chains at either end). */
        val rules: List<Pair<Int, Int>> = run {
            val zones = preview.cuttingPathZones!!
            val fromCentre = preview.zoneIds.filterIndexed { z, _ -> preview.zoneReorderedCells[z] >= 0 }.toSet()
            rules(paths, preview.borders, spirals).filter { (p, c) -> lastEdge[p] >= 0 && zones[c] in fromCentre && zones[p] == zones[c] }
        }
        /** Interruptions: shares of the cutting metres, and the last edge of every core (just before its chain). */
        val interruptions: List<Int> = run {
            val total = MissionProgress.totalCuttingMeters(edges)
            val cutting = edges.indices.filter { edges[it].cutting }
            val shares = listOf(0.1, 0.25, 0.4, 0.55, 0.7, 0.85).map { f -> var acc = 0.0; cutting.first { i -> acc += edges[i].meters; acc >= f * total } }
            (shares + rules.map { (p, _) -> lastEdge[p] }).distinct().sorted()
        }
        fun interrupted(edge: Int): MissionCheckpoint {
            val s = edges[edge]
            val at = Point((s.a.x + s.b.x) / 2, (s.a.y + s.b.y) / 2)
            return MissionCheckpoint("AA:BB", MissionProgress.mapKey(map), MissionProgress.fingerprint(edges), edges.size, preview, true,
                50, 20, 90, edge = edge, point = at, doneMeters = MissionProgress.cuttingMetersBefore(edges, edge, at),
                totalMeters = MissionProgress.totalCuttingMeters(edges), startedAt = 1L, base = base)
        }
        fun afterRecharge(cp: MissionCheckpoint): SmartResumeChoice? {
            val dockHeading = (engine.dockEntryHeading + 180.0) % 360.0
            return SmartResume.afterRecharge(edges, cp, engine.baseApproach, dockHeading, engine.baseApproach, dockHeading, SmartResume.linkMeters(routes))
        }
    }

    private val tasks = listOf(listOf("Potager"), listOf("Enfer"), listOf("Aire de jeux", "Potager"))

    private fun engine(preview: Preview) = MowingSimulation(routes, base.point, preview, map,
        SimConfig(baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true))

    private fun pathDistance(p: Point, path: List<Point>) = if (path.size < 2) path.minOf { it.distance(p) }
        else path.zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }

    /** Index of the prepared cutting edge going from [a] to [b], or -1. */
    private fun edgeOf(edges: List<SimEdge>, a: Point, b: Point) = edges.indices.firstOrNull { i ->
        edges[i].cutting && edges[i].a.distance(a) < 1e-6 && edges[i].b.distance(b) < 1e-6 } ?: -1

    /**
     * Order rules of a plan from the centre, as indices of [paths] (path to finish first, outward chain): the chains
     * inside a split before the chain around them ([SpiralSweep.Result.parents]), and every pocket pass (neither a chain
     * nor a perimeter or no-go loop) within [reach] of the first point of an outward chain scheduled after it, before the
     * nearest such chain (its core, [CoveragePlanner.plan]: the planner gives a whole pocket to one chain, so a pass may lie
     * within reach of several chain ends; the one the plan mows it before is taken).
     */
    private fun rules(paths: List<List<Point>>, borders: List<List<Point>>, spirals: List<SpiralSweep.Result>): List<Pair<Int, Int>> {
        val index = paths.withIndex().associate { (i, p) -> p to i }
        val inner = spirals.flatMap { r -> r.chains.indices.mapNotNull { b -> r.parents[b]?.let { a ->
            val ib = index[r.chains[b].reversed()]; val ia = index[r.chains[a].reversed()]
            if (ib != null && ia != null) ib to ia else null } } }
        val chains = spirals.flatMap { it.chains }.flatMap { listOf(it, it.reversed()) }.toSet()
        val loops = borders.flatMap { listOf(it, it.reversed()) }.toSet()
        val outward = spirals.flatMap { it.chains }.mapNotNull { index[it.reversed()] }
        val cores = paths.indices.filter { paths[it] !in chains && paths[it] !in loops }.mapNotNull { p ->
            outward.filter { it > p }.map { c -> c to pathDistance(paths[c].first(), paths[p]) }.filter { it.second <= reach }
                .minByOrNull { it.second }?.let { p to it.first }
        }
        return (inner + cores).distinct()
    }

    @Test(timeout = 1_800_000) fun checkpointAndResumeKeepTheChainsOutwards() {
        val report = StringBuilder()
        for (names in tasks) {
            val t = Centre(names)
            val label = t.label
            val result = t.plan.report!!.selected
            assertTrue("$label: ${result.diagnostics}", result.valid)
            assertEquals(label, 0, result.metrics!!.geometricViolations)
            assertFalse(label, t.preview.localOnly)
            assertTrue("$label: chains driven outwards", t.outward.isNotEmpty())
            // Aire de jeux has no safe order from the centre: planned from the edge and warned, Potager kept from the centre.
            val expected = names.map { if (it == "Aire de jeux") -1 else null }
            t.preview.zoneReorderedCells.zip(expected).forEach { (n, e) -> if (e == null) assertTrue("$label: $n", n > 0) else assertEquals(label, e, n) }
            assertEquals(label, if ("Aire de jeux" in names) listOf("Spirale depuis le centre impossible sur Aire de jeux : spirale depuis le bord utilisée") else emptyList<String>(),
                zoneModeWarnings(map, t.preview))
            for (c in t.outward) assertTrue("$label: chain $c found in the route", t.firstEdge[c] >= 0 && t.lastEdge[c] > t.firstEdge[c])
            assertTrue("$label: cores found", t.rules.isNotEmpty())
            // As planned and prepared: every core and inner chain before its chain.
            t.rules.forEach { (p, c) -> assertTrue("$label: path $p before chain $c", t.lastEdge[p] < t.firstEdge[c]) }

            // Checkpoint in the middle of the longest outward chain of a zone kept from the centre: saved, read back, rebuilt, resumed.
            val zones = t.preview.cuttingPathZones!!
            val chain = t.outward.filter { c -> t.preview.zoneReorderedCells[t.preview.zoneIds.indexOf(zones[c])] > 0 }.maxBy { t.paths[it].size }
            val k = (t.firstEdge[chain] + t.lastEdge[chain]) / 2
            val cp = t.interrupted(k)
            MissionCheckpointStore(folder.root).save(cp)
            val read = MissionCheckpointStore(folder.root).read("AA:BB")!!
            assertEquals(label, cp.preview, read.preview)
            assertEquals(label, t.paths, read.preview.cuttingPaths)
            assertEquals(label, t.preview.zoneReorderedCells, read.preview.zoneReorderedCells)
            val rebuilt = engine(read.preview)
            assertEquals("$label: same route rebuilt", read.planFingerprint, MissionProgress.fingerprint(rebuilt.edges))
            val resumed = RobotRoute.missionEdges(rebuilt, rebuilt.baseApproach, 0.0, resume = MissionResume(read.edge, read.point))
            val rest = resumed.edges.subList(resumed.mowingFrom, resumed.mowingTo)
            assertEquals(label, MissionProgress.remaining(rebuilt.edges, k, read.point), rest)
            resumed.edges.zipWithNext().forEach { (a, b) -> assertEquals(label, a.b, b.a) }
            // The rest of the chain still outwards: resumed on the interrupted edge, its outer end reached afterwards.
            assertEquals(label, k, resumed.engineEdge(resumed.mowingFrom))
            assertTrue("$label: chain finished at its outer end", rest.any { it.b.distance(t.paths[chain].last()) < 1e-6 })

            // Smart resume after a recharge: whatever order it picks, every cutting metre left, in its prepared direction.
            var reordered = 0
            for (edge in t.interruptions) {
                val interrupted = t.interrupted(edge)
                val choice = t.afterRecharge(interrupted) ?: continue
                reordered++
                assertEquals("$label edge $edge", SmartResume.remainingCutting(t.edges, interrupted), SmartResume.remainingCutting(t.edges, choice.checkpoint), 1e-6)
                val mission = RobotRoute.missionEdges(t.engine, t.engine.baseApproach, (t.engine.dockEntryHeading + 180.0) % 360.0, null, MissionResume.of(choice.checkpoint))
                (mission.mowingFrom until mission.mowingTo).filter { mission.edges[it].cutting }.forEach { i ->
                    val source = mission.engineEdge(i) ?: return@forEach
                    val s = t.edges[source]; val m = mission.edges[i]
                    // Same direction as the prepared edge (a portion of it at most).
                    assertTrue("$label edge $edge: edge $source driven backwards",
                        (m.b.x - m.a.x) * (s.b.x - s.a.x) + (m.b.y - m.a.y) * (s.b.y - s.a.y) >= -1e-9)
                }
            }
            report.append("SPIRAL CENTRE resume $label: chains outwards per zone ${t.preview.zoneReorderedCells}, ${t.outward.size} outward chains, " +
                "${t.rules.size} order rules, ${t.edges.size} edges, $reordered smart resumes reordered of ${t.interruptions.size}\n")
        }
        print(report)
    }

    /**
     * QA 2026-10-27, fails on f83a5f8a (Enfer: the smart resume after a recharge interrupted at 10 % of the cutting metres,
     * or at the end of a core, rotates the remainder to an entry past the cores and drives chains 20, 21, 22, 27, 40 and 42
     * before the passes of their core, moved to the end, to save about 7 m of transit). Fixed: the order constraints are
     * kept in the checkpoint ([Preview.outwardPaths]) and the smart resume refuses an order that breaks one.
     */
    @Test(timeout = 1_800_000) fun smartResumeAfterARechargeKeepsTheCoresBeforeTheirChains() {
        val failures = mutableListOf<String>()
        for (names in tasks) {
            val t = Centre(names)
            for (edge in t.interruptions) {
                val choice = t.afterRecharge(t.interrupted(edge)) ?: continue
                // Drive order of the remaining cutting edges.
                val rank = HashMap<Int, Int>()
                SmartResume.spans(choice.checkpoint).flatMap { SmartResume.spanEdges(t.edges, it, overlap = false) }
                    .forEachIndexed { r, (i, _) -> rank.putIfAbsent(i, r) }
                for ((p, c) in t.rules) {
                    val before = rank[t.lastEdge[p]] ?: continue   // already mowed
                    val chainStart = rank[t.firstEdge[c]] ?: continue   // chain already started
                    if (before > chainStart) failures += "${t.label}: interrupted on prepared edge $edge, the smart resume drives chain $c " +
                        "(edges ${t.firstEdge[c]}..${t.lastEdge[c]}) before path $p (last edge ${t.lastEdge[p]}), " +
                        "%.2f m from its centre end, saving %.1f m".format(pathDistance(t.paths[c].first(), t.paths[p]), choice.savedM)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
