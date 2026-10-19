package fr.luba.poc.map

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/**
 * Outer loop requested: the zigzag passes stop in the middle of the loop band, half a cut inside its trace, never closer
 * to the boundary (test of 2026-10-15, Jardin d'Eden: pass ends 7–11 cm from the loop, or even beyond, in the east and
 * south corners that the loop rounds).
 */
class ZigzagBehindLoopTest {
    private val factory = GeometryFactory()
    private fun shapes(): List<MapShape> {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        return (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
    }

    @Test(timeout = 600000) fun `pass ends half a cut inside the outer loop`() {
        val all = shapes()
        val holes = all.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val failures = mutableListOf<String>()
        for (name in listOf("Jardin d’Eden", "Aire de jeux", "Piscine")) {
            val zone = all.single { it.name == name }
            for (offset in listOf(0.05, 0.40)) {
                val preview = CoveragePlanner.plan(zone.points, holes, 0.25, 0.6, CoverageMode.LINES, true,
                    recordedBoundary = true, outerOffsetM = offset)
                val loops = preview.borders.take(preview.outerLoops)
                if (loops.isEmpty()) continue
                val polygons = loops.map { loop -> factory.createPolygon((if (loop.first() == loop.last()) loop else loop + loop.first())
                    .map { Coordinate(it.x, it.y) }.toTypedArray()) }
                val inside = org.locationtech.jts.operation.union.UnaryUnionOp.union(polygons)
                var worst = Double.POSITIVE_INFINITY
                var worstPoint: Point? = null
                preview.runs.flatten().forEach { p ->
                    val point = factory.createPoint(Coordinate(p.x, p.y))
                    // Point behind a no-go zone (notch of the loop): outside the loop polygon, checked by the safe area.
                    if (!inside.covers(point)) return@forEach
                    val distance = loops.minOf { loop -> (loop.zipWithNext()).minOf { (a, b) -> Geometry.distance(p, a, b) } }
                    if (distance < worst) { worst = distance; worstPoint = p }
                }
                if (worst < CoveragePlanner.ZIGZAG_LOOP_INSET_M - 0.01) failures += "$name, margin ${(offset * 100).toInt()} cm: pass end " +
                    "${"%.3f".format(worst)} m from the loop at $worstPoint"
                // No pass beyond the loop, on the boundary side: closer to the boundary than the nearest loop.
                val loopMin = loops.minOf { loop -> loop.minOf { q -> distanceToBoundary(q, zone.points) } }
                val runMin = preview.runs.flatten().minOf { distanceToBoundary(it, zone.points) }
                if (runMin < loopMin + CoveragePlanner.ZIGZAG_LOOP_INSET_M - 0.01) failures += "$name, margin ${(offset * 100).toInt()} cm: pass " +
                    "${"%.3f".format(runMin)} m from the boundary, loop at ${"%.3f".format(loopMin)} m"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun distanceToBoundary(p: Point, zone: List<Point>) = (zone + zone.first()).zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }
}
