package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class OptimizedModeTest {
    /** L: horizontal arm 30 × 6 m, vertical arm 6 × 20 m standing on its left. */
    private val lShape = listOf(Point(0.0,0.0),Point(30.0,0.0),Point(30.0,6.0),Point(6.0,6.0),Point(6.0,26.0),Point(0.0,26.0))

    private fun directions(passes: List<List<Point>>) = passes.map { pass ->
        val a = pass.first(); val b = pass.last()
        ((heading(a,b).roundToInt() % 180) + 180) % 180
    }.toSet()

    @Test fun lShapeMixesOrientationsAndSavesUTurns() {
        val simple = Geometry.plan(lShape, emptyList(), spacing = 0.25, mode = CoverageMode.LINES, borders = false)
        val optimized = Geometry.plan(lShape, emptyList(), spacing = 0.25, mode = CoverageMode.OPTIMIZED, borders = false)
        assertEquals(CoverageMode.OPTIMIZED, optimized.mode)
        assertEquals(1, directions(simple.passes).size)
        // Most of it in one direction, the remaining arm in the other.
        val used = directions(optimized.passes)
        assertTrue("Orientations: $used", used.size >= 2)
        assertTrue(used.any { abs(it - 90) <= 1 } && used.any { it <= 1 || it >= 179 })
        // Fewer passes hence fewer U-turns, for an equivalent mowed surface.
        assertTrue("${optimized.passes.size} passes versus ${simple.passes.size}", optimized.passes.size < simple.passes.size * 0.8)
        val target = polygon(lShape).buffer(-0.7)
        fun covered(p: Preview) = MissionEvaluator.coverage(p.runs.map { MissionLeg(it,true) }, target, 0.2).covered
        assertTrue(covered(optimized) >= covered(simple) * 0.995)
        // Each pass stays inside the zone, away from the limit.
        val inner = polygon(lShape).buffer(-0.6 + 1e-6)
        optimized.runs.forEach { run -> assertTrue(inner.covers(line(run))) }
    }

    @Test fun simpleShapesKeepTheSingleOrientation() {
        val rectangle = rectangle(0.0,0.0,20.0,8.0)
        val simple = Geometry.plan(rectangle, emptyList(), spacing = 0.25, mode = CoverageMode.LINES, borders = false)
        val optimized = Geometry.plan(rectangle, emptyList(), spacing = 0.25, mode = CoverageMode.OPTIMIZED, borders = false)
        assertEquals(simple.passes, optimized.passes)
        assertEquals(simple.runs.size, optimized.runs.size)
    }

    @Test fun productionPipelineValidatesTheMixedPlan() {
        val config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.8)
        val simple = RoutePlanning.calculate(request(lShape, config = config), CoverageMode.LINES)
        val optimized = RoutePlanning.calculate(request(lShape, config = config), CoverageMode.OPTIMIZED)
        assertTrue(optimized.candidates.toString(), optimized.selected.valid)
        val preview = optimized.selected.preview!!
        assertEquals(CoverageMode.OPTIMIZED, preview.mode)
        assertTrue(directions(preview.passes).size >= 2)
        val a = optimized.selected.metrics!!; val b = simple.selected.metrics!!
        assertEquals(0, a.geometricViolations)
        assertTrue("${a.uTurns} U-turns versus ${b.uTurns}", a.uTurns < b.uTurns)
        assertTrue("${a.durationSeconds} s versus ${b.durationSeconds} s", a.durationSeconds < b.durationSeconds)
        assertTrue(a.coveredAreaM2 >= b.coveredAreaM2 * 0.995)
        // Deterministic: same request, same plan.
        assertEquals(optimized.selected.planHash, RoutePlanning.calculate(request(lShape, config = config), CoverageMode.OPTIMIZED).selected.planHash)
    }

    private val referenceShapes: List<MapShape> by lazy {
        val json = org.json.JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    private fun referenceRequest(name: String, spacing: Double = 0.25): PlanningRequest {
        val base = fr.luba.poc.simulation.MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
        return PlanningRequest(RobotMap(referenceShapes, true, "reference-station"), listOf(referenceShapes.single { it.name.trim() == name }.id), base.point,
            config = PlanningConfig(spacingM = spacing, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base))
    }

    /** Fosse: the narrow "chimney" at the top left is mowed vertically, the rest lengthwise. */
    @Test fun fosseMowsItsNarrowLeftPartVerticallyAndTheRestLengthwise() {
        for (spacing in listOf(0.25, 0.3)) {
            val request = referenceRequest("Fosse", spacing)
            val simple = RoutePlanning.calculate(request, CoverageMode.LINES).selected
            val optimized = RoutePlanning.calculate(request, CoverageMode.OPTIMIZED).selected
            assertTrue(optimized.diagnostics.toString(), optimized.valid)
            val passes = optimized.preview!!.passes
            // Chimney: x ≈ -40.6 to -38.7 m, y ≈ 0 to 3.6 m (map frame). Its vertical passes may go down
            // to y ≈ -1.2 m into the wide part (0.40.1: no-go loops at the set margin).
            fun inChimney(p: List<Point>) = p.all { it.x in -41.5..-37.5 && it.y > -1.5 } &&
                (p.first().y + p.last().y) / 2 > 0.8
            val chimney = passes.filter(::inChimney)
            // 1.9 m chimney: border loop half a width from the outline on each side, room for at least 2 passes.
            assertTrue("e=$spacing: chimney passes ${chimney.size}", chimney.size >= 2)
            assertTrue("e=$spacing: ${directions(chimney)}", directions(chimney).all { it in 70..110 })
            val rest = passes.filterNot(::inChimney)
            // Rest of the zone: one dominant orientation (the useful length depends on the outline, chassis side).
            val restDirections = rest.map { pass -> directions(listOf(pass)).single() }
            val dominant = restDirections.groupingBy { it / 15 }.eachCount().maxBy { it.value }.key * 15
            val lengthwise = restDirections.count { kotlin.math.abs(it - dominant) <= 15 }
            assertTrue("e=$spacing: $lengthwise / ${rest.size} towards $dominant°", lengthwise >= rest.size * 0.9)
            // Strict gain at 25 cm; at 30 cm, the chimney (2 passes) leaves nothing to gain: never worse.
            if (spacing == 0.25) {
                assertTrue(optimized.metrics!!.uTurns < simple.metrics!!.uTurns)
                assertTrue(CoverageEngine.robotScore(optimized, request.config) < CoverageEngine.robotScore(simple, request.config))
            } else {
                assertTrue(optimized.metrics!!.uTurns <= simple.metrics!!.uTurns)
                assertTrue(CoverageEngine.robotScore(optimized, request.config) <= CoverageEngine.robotScore(simple, request.config) + 1e-6)
            }
        }
    }

    /** Recorded map: the chosen plan is never worse than the zigzag (pivots measured on the robot). */
    @Test fun recordedZonesAreNeverWorseThanTheZigzag() {
        for (name in listOf("Fosse", "Potager", "Figuier", "Piscine")) {
            val request = referenceRequest(name)
            val simple = RoutePlanning.calculate(request, CoverageMode.LINES).selected
            val optimized = RoutePlanning.calculate(request, CoverageMode.OPTIMIZED).selected
            assertTrue(name, optimized.valid)
            assertEquals(CoverageMode.OPTIMIZED, optimized.preview!!.mode)
            assertTrue(name, CoverageEngine.robotScore(optimized, request.config) <= CoverageEngine.robotScore(simple, request.config) + 1e-6)
            assertTrue(name, optimized.metrics!!.coveredAreaM2 >= simple.metrics!!.coveredAreaM2 - 0.002 * simple.metrics.targetAreaM2)
        }
    }

    @Test fun mixedPlanSurvivesCheckpointEncoding() {
        val optimized = Geometry.plan(lShape, emptyList(), spacing = 0.5, mode = CoverageMode.OPTIMIZED, borders = false)
        val decoded = fr.luba.poc.simulation.PreviewJson.decode(fr.luba.poc.simulation.PreviewJson.encode(optimized))
        assertEquals(CoverageMode.OPTIMIZED, decoded.mode)
        assertEquals(optimized.runs, decoded.runs)
    }
}
