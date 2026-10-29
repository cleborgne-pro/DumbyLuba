package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class RouteManoeuvresTest {
    private fun edgesOf(kind: TurnKind, lateral: Double): List<SimEdge> {
        val aFrom = Point(0.0, 0.0); val aEnd = Point(3.0, 0.0); val bStart = Point(3.0, lateral); val bTo = Point(0.0, lateral)
        val plan = TurnOptimizer.candidates(aFrom, aEnd, bStart, bTo, TurnOptimizer.TurnContext(0.25, 0.3, drivable = { true })).single { it.kind == kind }
        assertTrue(plan.refusal, plan.possible)
        return (listOf(aFrom) + plan.points + listOf(bTo)).zipWithNext().filter { (a, b) -> a.distance(b) > 1e-9 }.map { (a, b) -> SimEdge(a, b, true) }
    }

    @Test fun `a rolling U and a rounded loop of the Turn Optimizer are found between their passes`() {
        val u = edgesOf(TurnKind.U_ARC, 0.75)
        val found = RouteManoeuvres.find(u).single()
        assertEquals(RouteManoeuvres.Kind.U_TURN, found.kind)
        assertEquals(180.0, found.degrees, 1.0)
        assertEquals(1, found.first); assertEquals(u.size - 2, found.last)
        assertEquals(1.15, found.lengthM, 0.05)
        // Planned at the tracking's curve speed: longer than the same length at the set speed.
        assertTrue(RouteManoeuvres.plannedSeconds(u, found, 0.3) > found.lengthM / 0.3 * 1.5)
        val loop = edgesOf(TurnKind.LOOP, 0.25)
        val l = RouteManoeuvres.find(loop).single()
        assertEquals(RouteManoeuvres.Kind.LOOP, l.kind)
        assertEquals(180.0, l.degrees, 1.0)
        assertTrue(RouteManoeuvres.describe(loop, listOf(l), 0.3).startsWith("LOOP 1-"))
        // A pivot corner is not a manoeuvre; a 3 m radius bend of 45° is a curve.
        val square = listOf(SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), true), SimEdge(Point(2.0, 0.0), Point(2.0, 2.0), true))
        assertTrue(RouteManoeuvres.find(square).isEmpty())
        val bend = (0..30).map { k -> val a = Math.toRadians(1.5 * k); Point(3 * kotlin.math.sin(a), 3 - 3 * kotlin.math.cos(a)) }
            .zipWithNext().map { (a, b) -> SimEdge(a, b, true) }
        assertEquals(RouteManoeuvres.Kind.CURVE, RouteManoeuvres.find(bend).single().kind)
    }

    @Test fun `stats of a manoeuvre are given when the robot leaves it`() {
        val u = edgesOf(TurnKind.U_ARC, 0.75)
        val m = RouteManoeuvres.find(u).single()
        val stats = ManoeuvreStats(u)
        assertNull(stats.update(0, 0, Point(2.5, 0.0)))
        // Entry, then one position a second along the arc, 3 cm outside it, then the next pass.
        var summary: ManoeuvreStats.Summary? = null
        for (k in 0..10) {
            val i = m.first + (m.last - m.first) * k / 10
            val p = u[i].a.let { a -> Point(a.x + (a.x - 3.0).coerceAtLeast(0.0) * 0.0, a.y) }
            summary = stats.update(1000L * (k + 1), i, Point(p.x + 0.03, p.y))
            assertNull(summary)
        }
        summary = stats.update(12_000, m.last + 1, Point(2.9, 0.75))
        requireNotNull(summary)
        assertEquals(m, summary.manoeuvre)
        assertEquals(11.0, summary.durationS, 1e-9)
        assertTrue("${summary.maxDeviationM}", summary.maxDeviationM in 0.0..0.2)
        assertTrue(summary.medianSpeedMps > 0.05 && summary.medianSpeedMps < 0.2)
        assertNull(stats.update(13_000, m.last + 2, Point(2.5, 0.75)))
    }
}
