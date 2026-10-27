package fr.luba.poc.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.union.UnaryUnionOp

/**
 * U-turns on the seven zones of the reference map, recorded boundaries: [TurnStyle.FAST] gives the same route as before
 * the Turn Optimizer (pivots in place), [TurnStyle.LAWN] replaces most of them with rolling U-turns over passes taken
 * one in two, without losing surface. Printed per zone: U-turns, degrees pivoted in place, wear index and duration
 * of the passes with the tracking rules ([TurnOptimizer.drive]).
 */
class TurnStyleReferenceTest {
    private val factory = GeometryFactory()
    private val shapes: List<MapShape> = run {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val shape = array.getJSONObject(i)
            val points = shape.getJSONArray("points")
            MapShape(shape.getString("id").toLong(), shape.getInt("type"),
                (0 until points.length()).map { p -> points.getJSONArray(p).let { Point(it.getDouble(0), it.getDouble(1)) } }, shape.getString("name"))
        }
    }
    private val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
    private val base = Point(-0.4975, 1.13)
    private val spacing = 0.25
    private val cut = 0.4

    private fun plan(zone: MapShape, style: TurnStyle) = CoveragePlanner.plan(zone.points, holes, spacing, 0.6, CoverageMode.LINES, true,
        recordedBoundary = true, basePoint = base, turnStyle = style, cutWidthM = cut)

    private fun mowed(preview: Preview, zone: MapShape): Double {
        val area = factory.createPolygon((zone.points + zone.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        val swaths = (preview.runs + preview.borders).filter { it.size >= 2 }
            .map { factory.createLineString(it.map { p -> Coordinate(p.x, p.y) }.toTypedArray()).buffer(cut / 2, 8) }
        return UnaryUnionOp.union(swaths).intersection(area).area
    }

    /** Rolling U-turns of a route: arcs of [TurnOptimizer.ARC_STEP_DEGREES] per vertex, about 45 vertices each. */
    private fun rolling(preview: Preview) = preview.runs.sumOf { run -> run.windowed(3).count { (a, b, c) ->
        val t = Math.toDegrees(kotlin.math.abs(((kotlin.math.atan2(c.y - b.y, c.x - b.x) - kotlin.math.atan2(b.y - a.y, b.x - a.x) + 3 * Math.PI) % (2 * Math.PI)) - Math.PI))
        t in 3.5..4.5 } } / 40

    @Test fun `fast keeps the pivots and lawn friendly rolls most U-turns without losing surface`() {
        var fastPivots = 0.0; var lawnPivots = 0.0; var fastWear = 0.0; var lawnWear = 0.0; var fastTime = 0.0; var lawnTime = 0.0
        var arcs = 0
        for (zone in shapes.filter { it.kind == ShapeKind.ZONE }) {
            val fast = plan(zone, TurnStyle.FAST)
            val lawn = plan(zone, TurnStyle.LAWN)
            val default = CoveragePlanner.plan(zone.points, holes, spacing, 0.6, CoverageMode.LINES, true, recordedBoundary = true, basePoint = base, cutWidthM = cut)
            assertEquals("${zone.name}: default style is fast", default.runs, fast.runs)
            assertEquals("${zone.name}: fast keeps the pivots between neighbouring passes", 0, rolling(fast))
            val f = fast.runs.map { TurnOptimizer.drive(it, 0.3) }
            val l = lawn.runs.map { TurnOptimizer.drive(it, 0.3) }
            val zoneArcs = rolling(lawn)
            arcs += zoneArcs
            val fastArea = mowed(fast, zone); val lawnArea = mowed(lawn, zone)
            println("TURNS ${zone.name.trim()}: runs ${fast.runs.size} → ${lawn.runs.size}, fast ${fast.connectors} U-turns, %.0f° pivoted, wear %.0f, %.0f s | lawn ${lawn.connectors} U-turns ($zoneArcs rolling), %.0f° pivoted, wear %.0f, %.0f s | mowed %.2f → %.2f m²"
                .format(f.sumOf { it.pivotDegrees }, f.sumOf { it.wear }, f.sumOf { it.durationS }, l.sumOf { it.pivotDegrees }, l.sumOf { it.wear }, l.sumOf { it.durationS }, fastArea, lawnArea))
            // The orientation may change (wear weighed): within 1 % of the surface mowed by the fast route.
            assertTrue("${zone.name}: surface lost %.2f → %.2f".format(fastArea, lawnArea), lawnArea >= fastArea * 0.99)
            fastPivots += f.sumOf { it.pivotDegrees }; lawnPivots += l.sumOf { it.pivotDegrees }
            fastWear += f.sumOf { it.wear }; lawnWear += l.sumOf { it.wear }
            fastTime += f.sumOf { it.durationS }; lawnTime += l.sumOf { it.durationS }
        }
        println("TURNS total: pivoted %.0f° → %.0f°, wear %.0f → %.0f, passes %.0f s → %.0f s, $arcs rolling U-turns".format(fastPivots, lawnPivots, fastWear, lawnWear, fastTime, lawnTime))
        assertTrue("rolling U-turns used: $arcs", arcs > 0)
        assertTrue("less pivoting in place: %.0f → %.0f".format(fastPivots, lawnPivots), lawnPivots < fastPivots * 0.7)
        assertTrue(lawnWear < fastWear)
    }
}
