package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.PreviewJson
import org.junit.Assert.*
import org.junit.Test

/**
 * Direction of the Spiral mode ([SpiralDirection]) through the production entry point ([RoutePlanning.calculate]),
 * synthetic zones, physical boundary, 25 cm: from the centre, the same rings as from the edge, every chain driven from its
 * innermost ring out to its outer ring after the zigzag of its core, the whole mission validated; from the edge, the plan
 * of the default configuration.
 */
class SpiralDirectionTest {
    private val spacing = 0.25
    private val rectangle = rectangle(0.0, 0.0, 12.0, 8.0)
    private val outer = rectangle(0.0, 0.0, 14.0, 10.0)
    private val noGo = rectangle(5.0, 4.0, 3.0, 2.0)
    /** A core pocket lies within one cut (40 cm) and one spacing of the first point of its chain. */
    private val reach = 0.4 + spacing

    private fun config(direction: SpiralDirection? = null) = PlanningConfig(spacingM = spacing, minimumCoverage = 0.65).let { c ->
        if (direction == null) c else c.copy(spiralDirection = direction) }

    /** Selected plan and the spiral built for the zone ([SpiralSweep.observer]). */
    private fun plan(points: List<Point>, holes: List<List<Point>>, direction: SpiralDirection?): Pair<CandidateResult, SpiralSweep.Decision> {
        val decisions = java.util.Collections.synchronizedList(mutableListOf<SpiralSweep.Decision>())
        SpiralSweep.observer = { decisions += it }
        try {
            val selected = RoutePlanning.calculate(request(points, holes, config(direction)), CoverageMode.SPIRAL).selected
            return selected to decisions.single()
        } finally { SpiralSweep.observer = null }
    }

    /** Distance from [p] to the zone boundary or a no-go zone. */
    private fun clearance(p: Point, points: List<Point>, holes: List<List<Point>>) = (listOf(points) + holes).minOf { ring ->
        (ring + ring.first()).zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) } }

    private fun pathDistance(p: Point, path: List<Point>) = if (path.size < 2) path.minOf { it.distance(p) }
        else path.zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }

    @Test fun fromTheCentreEveryChainRunsOutwardsAfterItsCore() {
        for ((name, points, holes) in listOf(Triple("rectangle", rectangle, emptyList()), Triple("no-go zone", outer, listOf(noGo)))) {
            val (edge, edgeDecision) = plan(points, holes, SpiralDirection.FROM_EDGE)
            val (centre, centreDecision) = plan(points, holes, SpiralDirection.FROM_CENTRE)
            for ((label, x) in listOf("edge" to edge, "centre" to centre)) {
                assertTrue("$name $label: ${x.diagnostics}", x.valid)
                assertEquals("$name $label", 0, x.metrics!!.geometricViolations)
                assertEquals("$name $label: spiral kept", listOf(CoverageMode.SPIRAL), x.preview!!.zoneModes)
            }
            // The same rings: same chains built, same sweep within the tolerance of the spiral decision (0.2 point).
            val chains = edgeDecision.result!!.chains
            assertEquals(name, chains, centreDecision.result!!.chains)
            assertEquals(name, edgeDecision.spiralCovered!!, centreDecision.spiralCovered!!, 1e-6)
            val e = edge.metrics!!; val c = centre.metrics!!
            assertEquals("$name: covered %.3f against %.3f m²".format(c.coveredAreaM2, e.coveredAreaM2), e.coveredAreaM2, c.coveredAreaM2, 0.002 * e.targetAreaM2)
            val paths = centre.preview!!.cuttingPaths
            println("SPIRAL $name: ${chains.size} chains, coverage %.2f %% / %.2f %%, mission %.0f s / %.0f s, transit %.1f m / %.1f m".format(java.util.Locale.ROOT,
                e.coverage * 100, c.coverage * 100, e.durationSeconds, c.durationSeconds, e.transitM, c.transitM))
            for (chain in chains) {
                // Driven backwards, never forwards: from its innermost ring (at least one ring further in) out to its outer ring.
                val outward = chain.reversed()
                val index = paths.indexOf(outward)
                assertTrue("$name: chain driven outwards", index >= 0)
                assertFalse("$name: chain never driven inwards", paths.contains(chain))
                val first = clearance(outward.first(), points, holes); val last = clearance(outward.last(), points, holes)
                val nearest = outward.minOf { clearance(it, points, holes) }
                assertTrue("$name: starts inside (%.2f m) and ends at the edge (%.2f m)".format(first, last), first > last + spacing / 2)
                assertEquals("$name: ends on its outer ring", nearest, last, 0.03)
                // Its core (the passes near its first point) is mowed before it.
                val core = paths.indices.filter { i -> i != index && paths[i] !in chains.map { it.reversed() } &&
                    pathDistance(outward.first(), paths[i]) <= reach }
                assertTrue("$name: core before the chain ($core, chain at $index)", core.all { it < index })
                if (name == "rectangle") assertTrue("$name: core mowed in zigzag", core.isNotEmpty())
            }
            // Every part from its centre out: the chains inside a split (continuing a chain inwards) before that chain.
            val parents = centreDecision.result!!.parents
            if (name == "no-go zone") assertTrue("$name: split", parents.any { it != null })
            chains.indices.forEach { b -> parents[b]?.let { a ->
                assertTrue("$name: chain $b inside chain $a driven first", paths.indexOf(chains[b].reversed()) < paths.indexOf(chains[a].reversed())) } }
            // Resume: the checkpoint keeps the scheduled paths as they are, directions included.
            val saved = PreviewJson.decode(PreviewJson.encode(centre.preview!!))
            assertEquals(name, paths, saved.cuttingPaths)
        }
    }

    /**
     * The seven zones of the reference map (recorded boundaries, 25 cm, as [SpiralReferenceTest]): from the centre, every
     * plan validated, the same coverage as from the edge within 0.2 point; on a zone kept in spiral, every chain driven
     * outwards, or the whole zone driven from the edge when no order from the centre has safe links (warned). Printed per
     * zone: coverage, mission duration and transit from the edge / from the centre, computation time.
     */
    @Test(timeout = 1_800_000) fun referenceMapFromTheCentre() {
        val json = org.json.JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
        val map = RobotMap(shapes, true, "reference-station")
        val base = fr.luba.poc.simulation.MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
        val failures = mutableListOf<String>()
        for (zone in shapes.filter { it.kind == ShapeKind.ZONE }) {
            val name = zone.name.trim()
            fun run(direction: SpiralDirection): Triple<CandidateResult, SpiralSweep.Decision, Double> {
                val decisions = java.util.Collections.synchronizedList(mutableListOf<SpiralSweep.Decision>())
                SpiralSweep.observer = { decisions += it }
                val start = System.nanoTime()
                try {
                    val selected = RoutePlanning.calculate(PlanningRequest(map, listOf(zone.id), base.point,
                        config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE, spiralDirection = direction),
                        navigation = PlanningNavigation(emptyList(), base)), CoverageMode.SPIRAL).selected
                    // The first decision: the one of the requested direction (a zone driven from the edge is planned again).
                    return Triple(selected, decisions.first(), (System.nanoTime() - start) / 1e9)
                } finally { SpiralSweep.observer = null }
            }
            val (edge, _, te) = run(SpiralDirection.FROM_EDGE)
            val (centre, decision, tc) = run(SpiralDirection.FROM_CENTRE)
            assertTrue("$name: ${centre.diagnostics}", centre.valid)
            assertEquals(name, 0, centre.metrics!!.geometricViolations)
            assertEquals(name, edge.preview!!.zoneModes, centre.preview!!.zoneModes)
            val e = edge.metrics!!; val c = centre.metrics!!
            if (c.coveredAreaM2 < e.coveredAreaM2 - 0.002 * e.targetAreaM2) failures += "$name: coverage %.4f against %.4f".format(c.coverage, e.coverage)
            var outward = 0
            val p = centre.preview!!
            val fromEdge = p.zoneReorderedCells == listOf(-1)
            if (p.zoneModes.single() == CoverageMode.SPIRAL && !fromEdge) {
                for (chain in decision.result!!.chains) {
                    if (p.cuttingPaths.contains(chain.reversed())) outward++ else failures += "$name: a chain not driven outwards"
                }
                assertEquals(name, listOf(outward), p.zoneReorderedCells)
                assertEquals(name, emptyList<String>(), zoneModeWarnings(map, p))
            }
            if (fromEdge) {
                assertEquals(name, edge.planHash, centre.planHash)
                assertEquals(name, listOf("Spirale depuis le centre impossible sur $name : spirale depuis le bord utilisée"), zoneModeWarnings(map, p))
            }
            println(("SPIRAL CENTRE $name: ${p.zoneModes.single()}${if (fromEdge) " from the edge (no safe order from the centre)" else ""}, $outward of ${decision.result?.chains?.size ?: 0} chains outwards | " +
                "coverage %.2f %% / %.2f %% | mission %.0f s / %.0f s (%+.1f %%) | transit %.0f m / %.0f m | computation %.2f s / %.2f s").format(java.util.Locale.ROOT,
                e.coverage * 100, c.coverage * 100, e.durationSeconds, c.durationSeconds, (c.durationSeconds / e.durationSeconds - 1) * 100,
                e.transitM, c.transitM, te, tc))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun fromTheEdgeIsTheDefaultPlan() {
        for ((points, holes) in listOf(rectangle to emptyList(), outer to listOf(noGo))) {
            val (default, _) = plan(points, holes, null)
            val (edge, _) = plan(points, holes, SpiralDirection.FROM_EDGE)
            assertTrue(default.valid)
            assertEquals(default.planHash, edge.planHash)
            assertEquals(default.preview!!.cuttingPaths, edge.preview!!.cuttingPaths)
        }
        assertEquals(SpiralDirection.FROM_EDGE, PlanningConfig().spiralDirection)
    }

    @Test fun plannerMarksTheOutwardChainsAndTheirCores() {
        val zone = rectangle
        fun preview(direction: SpiralDirection) = CoveragePlanner.plan(zone, emptyList(), spacing, 0.6, CoverageMode.SPIRAL, true,
            basePoint = Point(1.0, 1.0), continuous = true, spiralDirection = direction)
        val edge = preview(SpiralDirection.FROM_EDGE)
        val centre = preview(SpiralDirection.FROM_CENTRE)
        assertTrue(edge.outwardRuns.isEmpty())
        assertTrue(centre.outwardRuns.isNotEmpty())
        // The same runs (chains reversed), the chains' cells shared with their cores.
        assertEquals(edge.runs.map { setOf(it, it.reversed()) }.toSet(), centre.runs.map { setOf(it, it.reversed()) }.toSet())
        for ((i, before) in centre.outwardRuns) {
            val chain = centre.runs[i]
            assertTrue(chain in edge.runs.map { it.reversed() })
            val cell = centre.runCellIds[i]
            val core = centre.runs.indices.filter { it != i && centre.runCellIds[it] == cell }
            assertTrue("core of the chain", core.isNotEmpty())
            assertEquals("one chain: its core, all of its cell, is mowed before it", core.toSet(), before)
            // Cut width unknown to this call: the spacing.
            assertTrue(core.any { k -> pathDistance(chain.first(), centre.runs[k]) <= 2 * spacing })
        }
    }
}
