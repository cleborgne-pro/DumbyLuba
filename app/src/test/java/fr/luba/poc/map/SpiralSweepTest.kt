package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Geometry as JtsGeometry

/** [SpiralSweep] on synthetic areas (the area where pass centres may lie), 25 cm, 40 cm cut, 0.35 m radius. */
class SpiralSweepTest {
    private val factory = GeometryFactory()
    private val spacing = 0.25
    private val cut = 0.4
    private val radius = TurnOptimizer.FOLLOW_RADIUS_M

    private fun polygon(points: List<Point>, vararg holes: List<Point>): Polygon {
        fun ring(p: List<Point>) = factory.createLinearRing((p + p.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        return factory.createPolygon(ring(points), holes.map(::ring).toTypedArray())
    }
    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1))
    private val rectangle = polygon(rect(0.0, 0.0, 10.0, 6.0))
    private val lShape = polygon(listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 4.0), Point(4.0, 4.0), Point(4.0, 9.0), Point(0.0, 9.0)))
    private val holed = polygon(rect(0.0, 0.0, 10.0, 8.0), rect(4.0, 3.0, 6.0, 5.0))
    private val uShape = polygon(listOf(Point(0.0, 0.0), Point(9.0, 0.0), Point(9.0, 8.0), Point(6.0, 8.0), Point(6.0, 2.5), Point(3.0, 2.5), Point(3.0, 8.0), Point(0.0, 8.0)))
    private val lobe = polygon(listOf(Point(0.0, 0.0), Point(6.0, 0.0), Point(6.0, 4.0), Point(3.3, 4.0), Point(3.3, 6.0), Point(2.8, 6.0), Point(2.8, 4.0), Point(0.0, 4.0)))
    private val round = factory.createPoint(Coordinate(0.0, 0.0)).buffer(3.0, 32) as Polygon

    /** Inside [area] (1 mm tolerance) and nowhere else: the surface check of the planner. */
    private fun inside(area: JtsGeometry): (List<Point>) -> Boolean {
        val prepared = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(area.buffer(1e-3))
        return { path -> path.size >= 2 && prepared.covers(factory.createLineString(path.map { Coordinate(it.x, it.y) }.toTypedArray())) }
    }
    private fun build(area: JtsGeometry, start: Point? = null) = SpiralSweep.build(area, spacing, cut, radius, start, inside(area))
    /** Chains and pocket passes of [area], as the planner drives them. */
    private fun paths(area: JtsGeometry, result: SpiralSweep.Result) =
        result.chains + result.pockets.flatMap { SpiralSweep.fill(it, spacing, inside(area)).flatten() }
    private fun swept(area: JtsGeometry, paths: List<List<Point>>): Double = factory.createMultiLineString(paths.map { p ->
        factory.createLineString(p.map { Coordinate(it.x, it.y) }.toTypedArray()) }.toTypedArray()).buffer(cut / 2, 8).intersection(area).area / area.area

    @Test fun everyShapeIsSweptByInsideChainsAndPockets() {
        for ((name, area) in listOf("rectangle" to rectangle, "L" to lShape, "centred hole" to holed, "U" to uShape, "thin lobe" to lobe, "round" to round)) {
            val result = requireNotNull(build(area)) { name }
            val check = inside(area)
            assertTrue("$name: chains", result.chains.isNotEmpty())
            result.chains.forEach { assertTrue("$name: chain inside", check(it)) }
            assertEquals("$name: no refused ring", 0, result.refusedRings)
            assertEquals("$name: slits", 0, result.slitFailures)
            val share = swept(area, paths(area, result))
            val chainsOnly = swept(area, result.chains)
            val drive = result.chains.map { TurnOptimizer.drive(it, 0.3) }
            println("SPIRAL $name: ${result.chains.size} chains, ${result.rings} rings, ${result.splits} splits, ${result.pockets.size} pockets " +
                "(%.2f m²), swept %.2f %% by the chains, %.2f %% with the pockets; pivots %.0f°, wear %.0f, %.0f s".format(java.util.Locale.ROOT,
                    result.pockets.sumOf { it.area }, chainsOnly * 100, share * 100, drive.sumOf { it.pivotDegrees }, drive.sumOf { it.wear }, drive.sumOf { it.durationS }))
            assertTrue("$name: swept %.4f".format(share), share >= 0.995)
        }
    }

    @Test fun holesAreSlitAndSplitsStartTheirOwnChain() {
        val hole = requireNotNull(build(holed))
        // The rings go round the hole and come back along both sides of the slit: one chain, never inside the hole.
        val inner = polygon(rect(4.0, 3.0, 6.0, 5.0))
        hole.chains.forEach { chain -> assertFalse(factory.createLineString(chain.map { Coordinate(it.x, it.y) }.toTypedArray()).crosses(inner)) }
        assertTrue(hole.chains.flatten().any { it.x in 3.5..6.5 && it.y in 2.5..5.5 })
        val u = requireNotNull(build(uShape))
        assertTrue("U: ${u.splits} splits", u.splits >= 1)
        assertTrue("U: one chain per arm after the split", u.chains.size >= 3)
        // The thin lobe (0.5 m, narrower than twice the radius) is left to the pockets, filled in zigzag.
        val l = requireNotNull(build(lobe))
        assertTrue(l.chains.flatten().none { it.y > 4.2 })
        val lobePasses = l.pockets.flatMap { SpiralSweep.fill(it, spacing, inside(lobe)).flatten() }.filter { p -> p.any { it.y > 4.5 } }
        assertTrue("lobe filled", lobePasses.isNotEmpty())
    }

    @Test fun convexCornersRollAndTheRingsAreOneSpacingApart() {
        val chain = requireNotNull(build(rectangle)).chains.single()
        // Rounded convex corners (radius 0.35 m, about 4° per vertex) and slants of 0.25 m over 1 m: never a pivot.
        assertEquals(0.0, TurnOptimizer.drive(chain, 0.3).pivotDegrees, 1e-9)
        val turns = chain.indices.drop(1).dropLast(1).map { i ->
            val a = chain[i - 1]; val b = chain[i]; val c = chain[i + 1]
            Math.toDegrees(kotlin.math.abs(kotlin.math.atan2((b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x), (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y))))
        }
        assertTrue("largest turn ${turns.max()}", turns.max() < 16.0)
        // Outer ring on the boundary of the area (5 mm inside), next ones one spacing further in.
        val minX = chain.minOf { it.x }
        assertEquals(SpiralSweep.EDGE_M, minX, 1e-3)
        val xs = chain.filter { it.y in 2.9..3.1 && it.x < 3.0 }.map { it.x }.sorted().fold(mutableListOf<Double>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > 0.05) acc.add(x); acc }
        xs.zipWithNext().forEach { (a, b) -> assertEquals(spacing, b - a, 0.01) }
        // L: the reflex corner keeps the offset arcs, of radius j × spacing (5 mm for the outer ring): tighter than the follow
        // radius for the first rings. Reported (the tracking stops on them: 30° within 15 cm), not counted as pivots by
        // TurnOptimizer.drive, which only sees the 4° vertices.
        val l = requireNotNull(build(lShape)).chains
        val pivots = l.sumOf { TurnOptimizer.drive(it, 0.3).pivotDegrees }
        val tight = l.sumOf { chain -> chain.indices.drop(1).dropLast(1).count { i -> chain[i].distance(chain[i - 1]) < 0.02 } }
        println("SPIRAL curvature: rectangle 0° pivoted, L %.0f° pivoted, $tight vertices on arcs tighter than 0.3 m".format(java.util.Locale.ROOT, pivots))
        assertTrue("L pivots $pivots", pivots <= 250.0)
    }

    @Test fun theSpiralStartsNearestToTheStartAndIsDeterministic() {
        // Outer end: `T` before the start of the outer ring, in the middle of the long edge nearest to the start (the short
        // edges stop being straight after a few rings); one chain down to the core.
        for ((start, low) in listOf(Point(5.0, -1.0) to true, Point(2.0, -3.0) to true, Point(5.0, 7.0) to false, Point(9.0, 6.5) to false)) {
            val chain = requireNotNull(build(rectangle, start)).chains.single()
            assertTrue("$start: ${chain.first()}", if (low) chain.first().y < 0.1 else chain.first().y > 5.9)
            assertTrue("$start: ${chain.first()}", chain.first().x in 3.0..7.0)
            assertEquals(chain, requireNotNull(build(rectangle, start)).chains.single())
        }
    }

    @Test fun nothingToSweepGivesNullAndJtsFailuresNeverEscape() {
        assertNull(build(factory.createPolygon()))
        // Narrower than twice the radius: no ring fits.
        assertNull(build(polygon(rect(0.0, 0.0, 10.0, 0.5))))
        // Self-crossing outline (invalid for JTS): null or a result, never an exception.
        val bowTie = factory.createPolygon(listOf(Point(0.0, 0.0), Point(4.0, 4.0), Point(4.0, 0.0), Point(0.0, 4.0), Point(0.0, 0.0))
            .map { Coordinate(it.x, it.y) }.toTypedArray())
        SpiralSweep.build(bowTie, spacing, cut, radius, null, { true })
        // A refused ring ends the chain on the previous one driven whole; the next ring starts a new chain.
        var calls = 0
        val refused = requireNotNull(SpiralSweep.build(rectangle, spacing, cut, radius, null, { calls++; calls != 3 }))
        assertEquals(1, refused.refusedRings)
        assertTrue(refused.chains.size >= 2)
    }
}
