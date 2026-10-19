package fr.luba.poc.simulation

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissionTrailTest {
    @get:Rule val folder = TemporaryFolder()

    /** Mowing in 20 m stripes 20 cm apart, one point every 7 cm (≈ 0.35 m/s, reported every 200 ms). */
    private fun mowing(bands: Int): List<Point> = (0 until bands).flatMap { band ->
        val y = band * 0.2
        val xs = (0..285).map { it * 0.07 }
        (if (band % 2 == 0) xs else xs.reversed()).map { Point(it, y) }
    }

    @Test fun ignoresTinyMovesAndInvalidPoints() {
        var trail = MissionTrail.append(emptyList(), Point(0.0, 0.0))
        trail = MissionTrail.append(trail, Point(0.02, 0.0))
        trail = MissionTrail.append(trail, Point(Double.NaN, 0.0))
        trail = MissionTrail.append(trail, Point(0.05, 0.0))
        assertEquals(listOf(Point(0.0, 0.0), Point(0.05, 0.0)), trail)
    }

    @Test fun keepsTheWholeMowingInsteadOfDroppingItsBeginning() {
        val points = mowing(200)   // ≈ 57,000 points, 4 km: well beyond the former limit of 12,000 points
        var trail = emptyList<Point>()
        points.forEach { trail = MissionTrail.append(trail, it) }
        assertTrue(trail.size <= MissionTrail.MAX_POINTS)
        assertEquals(points.first(), trail.first())
        assertEquals(points.last(), trail.last())
        // The last points stay raw for back-offs and escapes.
        assertEquals(points.takeLast(400), trail.takeLast(400))
        // Each measured point stays within the tolerance of the kept trail (on its stripe).
        val path = trail.zipWithNext()
        points.filterIndexed { i, _ -> i % 37 == 0 }.forEach { p ->
            assertTrue(path.minOf { (a, b) -> Geometry.distance(p, a, b) } <= MissionTrail.TOLERANCE_M + 1e-6)
        }
    }

    @Test fun mergeAppendsGatewayTrail() {
        val merged = MissionTrail.merge(listOf(Point(0.0, 0.0)), listOf(Point(0.01, 0.0), Point(1.0, 0.0), Point(2.0, 0.0)))
        assertEquals(listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(2.0, 0.0)), merged)
    }

    @Test fun simplifyKeepsCornersAndEnds() {
        val l = (0..100).map { Point(it * 0.1, 0.0) } + (1..100).map { Point(10.0, it * 0.1) }
        assertEquals(listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)), MissionTrail.simplify(l, 0.02))
    }

    @Test fun storeRoundTripsOnlyForTheSameMowing() {
        val store = MissionTrailStore(folder.root)
        val trail = listOf(Point(-8.25, 8.5), Point(-8.0, 8.75), Point(3.125, -2.5))
        store.save("AA:BB", 1000L, trail)
        assertEquals(trail, store.read("AA:BB", 1000L))
        assertTrue(store.read("AA:BB", 2000L).isEmpty())
        assertTrue(store.read("CC:DD", 1000L).isEmpty())
        store.delete("AA:BB")
        assertTrue(store.read("AA:BB", 1000L).isEmpty())
    }

    @Test fun corruptFileIsIgnored() {
        val store = MissionTrailStore(folder.root)
        store.save("AA:BB", 1000L, listOf(Point(1.0, 2.0)))
        folder.root.listFiles()!!.single { it.name.startsWith("mission-trail-") }.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.read("AA:BB", 1000L).isEmpty())
    }

    /** 2026-10-15: Fosse trail kept, mowing resumed from the base, straight line across the zones. */
    @Test fun jumpCutsTheTraceInsteadOfJoiningIt() {
        var trail = listOf(Point(-39.6, -0.9), Point(-39.4, -0.6))
        trail = MissionTrail.append(trail, Point(-0.5, 1.1))
        trail = MissionTrail.append(trail, Point(-0.3, 1.0))
        assertEquals(listOf(Point(-39.6, -0.9), Point(-39.4, -0.6), MissionTrail.BREAK, Point(-0.5, 1.1), Point(-0.3, 1.0)), trail)
        assertEquals(2, MissionTrail.pieces(trail).size)
        assertEquals(2, MissionTrail.links(trail).size)
        // Point in the middle of the jump: far from the trail actually travelled.
        assertTrue(MissionTrail.distance(Point(-20.0, 0.25), trail) > 10.0)
        assertEquals(0.0, MissionTrail.distance(Point(-0.4, 1.05), trail), 1e-9)
    }

    @Test fun normalStepsAndGatewayTrailStayJoined() {
        var trail = emptyList<Point>()
        (0..20).forEach { trail = MissionTrail.append(trail, Point(it * 0.6, 0.0)) }
        assertTrue(trail.none(MissionTrail::isBreak))
        val merged = MissionTrail.merge(trail, listOf(Point(30.0, 0.0), Point(30.1, 0.0)))
        assertEquals(1, merged.count(MissionTrail::isBreak))
    }

    @Test fun compactionKeepsBreaks() {
        val first = mowing(100)
        val second = mowing(100).map { Point(it.x + 100.0, it.y) }
        var trail = emptyList<Point>()
        (first + second).forEach { trail = MissionTrail.append(trail, it) }
        assertTrue(trail.size <= MissionTrail.MAX_POINTS)
        assertEquals(1, trail.count(MissionTrail::isBreak))
        val pieces = MissionTrail.pieces(trail)
        assertEquals(first.first(), pieces[0].first()); assertEquals(first.last(), pieces[0].last())
        assertEquals(second.first(), pieces[1].first()); assertEquals(second.last(), pieces[1].last())
    }

    @Test fun storeKeepsBreaks() {
        val store = MissionTrailStore(folder.root)
        val trail = listOf(Point(1.0, 2.0), MissionTrail.BREAK, Point(30.0, 2.0))
        store.save("AA:BB", 1000L, trail)
        val read = store.read("AA:BB", 1000L)
        assertEquals(3, read.size); assertTrue(MissionTrail.isBreak(read[1]))
    }
}
