package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.operation.union.UnaryUnionOp

/** Transits (blades stopped) at least 50 cm from the borders, except crossings or when there is no choice. */
class BorderClearanceTest {
    private val factory = GeometryFactory()
    private fun polygon(p: List<Point>) = factory.createPolygon((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun shapes(): List<MapShape> {
        val a = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().readText()).getJSONArray("shapes")
        return (0 until a.length()).map { i -> val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                val v = p.getJSONArray(k); Point(v.getDouble(0), v.getDouble(1)) }, s.getString("name")) }
    }

    /** Length of a route within [limit] of the drawn outline (zones and exclusions), excluding the
     * first and last metre (exit / arrival imposed by the ends). */
    private fun nearLength(path: List<Point>, boundary: org.locationtech.jts.geom.Geometry, limit: Double): Double {
        var near = 0.0; var travelled = 0.0
        val total = path.zipWithNext().sumOf { (a, b) -> a.distance(b) }
        path.zipWithNext().forEach { (a, b) ->
            val d = a.distance(b); val steps = maxOf(1, (d / 0.05).toInt())
            for (i in 0 until steps) {
                val t = (i + 0.5) / steps
                val p = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                val along = travelled + d * t
                if (along > 1.0 && along < total - 1.0 &&
                    boundary.distance(factory.createPoint(Coordinate(p.x, p.y))) < limit) near += d / steps
            }
            travelled += d
        }
        return near
    }

    private fun drawn(shapes: List<MapShape>): org.locationtech.jts.geom.Geometry {
        val zones = UnaryUnionOp.union(shapes.filter { it.kind == ShapeKind.ZONE }.map { polygon(it.points) })
        return shapes.filter { it.kind == ShapeKind.NO_GO }.fold(zones) { area, s -> area.difference(polygon(s.points)) }
    }

    @Test fun squareZoneTransitKeepsHalfMetreFromBorder() {
        // 10 m square zone with a central exclusion: the link bypasses the exclusion.
        val square = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0)), "Carré")
        val hole = MapShape(2, 1, listOf(Point(3.0, 3.0), Point(7.0, 3.0), Point(7.0, 7.0), Point(3.0, 7.0)), "Exclusion")
        val map = RobotMap(listOf(square, hole), true)
        val routes = TrajectoryPlanner(map, 0.3065, listOf(hole.points), recordedBoundary = true)
        val path = routes.between(Point(1.0, 5.0), Point(9.0, 5.0))
        routes.requirePath(path)
        val boundary = drawn(listOf(square, hole)).boundary
        assertEquals(0.0, nearLength(path, boundary, BORDER - 0.02), 1e-9)
    }

    @Test fun narrowGapIsStillCrossed() {
        // Two rooms connected by a 0.8 m bottleneck: no choice, the route goes through anyway.
        val left = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 2.1), Point(6.0, 2.1),
            Point(6.0, 0.0), Point(11.0, 0.0), Point(11.0, 5.0), Point(6.0, 5.0), Point(6.0, 2.9), Point(5.0, 2.9),
            Point(5.0, 5.0), Point(0.0, 5.0)), "Goulet")
        val map = RobotMap(listOf(left), true)
        val routes = TrajectoryPlanner(map, 0.3065, emptyList(), recordedBoundary = true)
        val path = routes.between(Point(2.0, 1.0), Point(9.0, 1.0))
        routes.requirePath(path)
        // Outside the bottleneck, the route keeps its 50 cm.
        val boundary = polygon(left.points).boundary
        val outside = path.flatMap { listOf(it) }.filter { it.x < 4.4 || it.x > 6.6 }
        outside.drop(1).dropLast(1).forEach { p ->
            assertTrue("$p too close to the border", boundary.distance(factory.createPoint(Coordinate(p.x, p.y))) >= BORDER - 0.05)
        }
    }

    @Test fun referenceMapTransitsStayAwayFromBorders() {
        val shapes = shapes(); val map = RobotMap(shapes, true)
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val base = Point(-0.5351, 1.0712)
        val preview = MultiZonePlanner.plan(shapes.filter { it.kind == ShapeKind.ZONE }, holes, 0.25, 0.6,
            CoverageMode.LINES, true, recordedBoundary = true, basePoint = base,
            obstacles = listOf(fr.luba.poc.obstacles.ObstacleDisc(base.x, base.y, TrajectoryPlanner.baseSafetyRadius(1.5))),
            bordersFirst = true, nearbyNoGo = true)
        val routes = TrajectoryPlanner.withDock(map, 0.3065, holes, emptyList(), base, 153.0312, 1.5, recordedBoundary = true)
        val started = System.nanoTime()
        val engine = MowingSimulation(routes, base, preview, map, SimConfig(baseEntryHeadingDegrees = 153.0312))
        val elapsed = (System.nanoTime() - started) / 1e6
        val boundary = drawn(shapes).boundary
        // Transits grouped into continuous routes with blades stopped.
        val transits = mutableListOf<MutableList<Point>>()
        engine.edges.forEach { edge ->
            if (edge.cutting) return@forEach
            val last = transits.lastOrNull()
            if (last != null && last.last().distance(edge.a) < 1e-9) last.add(edge.b) else transits.add(mutableListOf(edge.a, edge.b))
        }
        val total = transits.sumOf { t -> t.zipWithNext().sumOf { (a, b) -> a.distance(b) } }
        val near = transits.sumOf { nearLength(it, boundary, BORDER - 0.05) }
        println("BorderClearance transit=%.1f m near the border=%.1f m (%.1f %%) preparation=%.0f ms".format(total, near, near * 100 / total, elapsed))
        assertTrue("Transit too often hugging the borders: %.1f m out of %.1f m".format(near, total), near <= total * 0.15)
    }

    /** Measurement per zone (log): transit, share near the borders and simulated duration. */
    @Test fun perZoneTransitReport() {
        val shapes = shapes(); val map = RobotMap(shapes, true)
        val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val base = Point(-0.5351, 1.0712)
        val routes = TrajectoryPlanner.withDock(map, 0.3065, holes, emptyList(), base, 153.0312, 1.5, recordedBoundary = true)
        val boundary = drawn(shapes).boundary
        shapes.filter { it.kind == ShapeKind.ZONE }.forEach { zone ->
            val preview = MultiZonePlanner.plan(listOf(zone), holes, 0.25, 0.6, CoverageMode.LINES, true,
                recordedBoundary = true, basePoint = base,
                obstacles = listOf(fr.luba.poc.obstacles.ObstacleDisc(base.x, base.y, TrajectoryPlanner.baseSafetyRadius(1.5))),
                bordersFirst = true, nearbyNoGo = true)
            val engine = MowingSimulation(routes, base, preview, map, SimConfig(baseEntryHeadingDegrees = 153.0312, instantCharging = true))
            val transit = engine.edges.filter { !it.cutting }
            val total = transit.sumOf { it.meters }
            val near = transit.sumOf { nearLength(listOf(it.a, it.b), boundary, BORDER - 0.05) }
            var state = engine.start()
            while (state.phase != SimPhase.COMPLETE && state.phase != SimPhase.BLOCKED) state = engine.advance(state, 600.0, false)
            println("ZONE %-16s transit=%.1f m near=%.1f m duration=%.0f s".format(zone.name.trim(), total, near, state.elapsed))
        }
    }

    private companion object { const val BORDER = 0.5 }
}
