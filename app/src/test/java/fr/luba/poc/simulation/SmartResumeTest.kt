package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Smart resume on synthetic plans: reordering after a recharge (shortest transit, nothing left unmowed), blockage
 * deferred then given up when met again, progress followed across the reordered portions and kept by the store.
 */
class SmartResumeTest {
    @get:Rule val folder = TemporaryFolder()

    private val straight: (Point, Double?, Point, Double?) -> Double? = { a, _, b, _ -> a.distance(b) }

    /** Three stripes of [length] m at x0, spaced 0.4 m, joined by short cutting turns, as a boustrophedon cell. */
    private fun cell(x0: Double, y0: Double, length: Double = 4.0, stripes: Int = 3): List<SimEdge> {
        val out = mutableListOf<SimEdge>()
        for (k in 0 until stripes) {
            val y = y0 + 0.4 * k
            val (a, b) = if (k % 2 == 0) Point(x0, y) to Point(x0 + length, y) else Point(x0 + length, y) to Point(x0, y)
            out += SimEdge(a, b, true)
            if (k < stripes - 1) out += SimEdge(b, Point(b.x, y + 0.4), true)
        }
        return out
    }

    private fun transit(from: Point, to: Point) = listOf(SimEdge(from, to, false))

    /**
     * Base approach at (0, 0). Plan: far cell A (x 20–24, y 0), cell Q near the base (x 1–5, y 4), far cell E
     * (x 20–24, y 10). The mowing is interrupted in the middle of A: going back there first, then Q, then E and
     * home costs much more transit than starting with Q, then E, then finishing A.
     */
    private val a = cell(20.0, 0.0)
    private val q = cell(1.0, 4.0)
    private val e = cell(20.0, 10.0)
    private val edges = a + transit(a.last().b, q.first().a) + q + transit(q.last().b, e.first().a) + e
    private val approach = Point(0.0, 0.0)

    private fun checkpoint(edge: Int, point: Point?) = MissionCheckpoint("AA:BB", "map", "plan", edges.size, Preview(emptyList(), 0.0, 0, 0),
        true, 50, 20, 90, edge = edge, point = point, doneMeters = MissionProgress.cuttingMetersBefore(edges, edge, point),
        totalMeters = MissionProgress.totalCuttingMeters(edges), startedAt = 1L)

    /** Cutting metres left on each prepared edge. */
    private fun perEdge(cp: MissionCheckpoint): Map<Int, Double> = SmartResume.spans(cp).flatMap { SmartResume.spanEdges(edges, it, overlap = false) }
        .filter { it.second.cutting }.groupBy({ it.first }, { it.second.meters }).mapValues { it.value.sum() }

    private fun assertSameRemainder(before: MissionCheckpoint, after: MissionCheckpoint) {
        val b = perEdge(before); val a = perEdge(after)
        assertEquals(b.keys, a.keys)
        b.forEach { (i, m) -> assertEquals("edge $i", m, a.getValue(i), 1e-6) }
        assertEquals(SmartResume.remainingCutting(edges, before), SmartResume.remainingCutting(edges, after), 1e-6)
        assertEquals(before.doneMeters, after.doneMeters, 1e-6)
    }

    @Test fun `after recharge the remainder starts with the block that minimises transit, nothing left unmowed`() {
        val interrupted = checkpoint(2, Point(22.0, 0.4))
        val choice = requireNotNull(SmartResume.afterRecharge(edges, interrupted, approach, 0.0, approach, 180.0, straight))
        // Default: base → interrupted point (≈ 22 m), A → Q, Q → E, E → base. Reordered: base → Q, Q → E, E → A, A → base.
        assertTrue("saved ${choice.savedM} m", choice.savedM >= 15.0)
        assertEquals(q.first().a, choice.entry)
        assertSameRemainder(interrupted, choice.checkpoint)
        // Interrupted cell finished last, from its interrupted point (with the usual overlap).
        val last = choice.checkpoint.deferred.last()
        assertEquals(PlanPos(2, Point(22.0, 0.4)), last.from)
        assertFalse(choice.checkpoint.linear)
    }

    @Test fun `spiral from the centre, no order drives a chain before its core still to mow`() {
        // Same plan, Q an outward chain whose core is A ([Preview.outwardPaths]): starting with Q would leave A to the end.
        fun path(cell: List<SimEdge>) = listOf(cell.first().a) + cell.map { it.b }
        val paths = listOf(path(a), path(q), path(e))
        val preview = Preview(paths, 0.0, 0, 0, scheduledPaths = paths, outwardPaths = mapOf(1 to setOf(0)))
        val interrupted = checkpoint(2, Point(22.0, 0.4)).copy(preview = preview)
        val (pathOf, rules) = requireNotNull(SmartResume.outwardRules(edges, preview))
        assertEquals(listOf(0 to 1), rules)
        assertEquals(List(a.size) { 0 } + -1 + List(q.size) { 1 } + -1 + List(e.size) { 2 }, pathOf.toList())
        val choice = SmartResume.afterRecharge(edges, interrupted, approach, 0.0, approach, 180.0, straight)
        if (choice != null) {
            assertNotEquals(q.first().a, choice.entry)
            assertTrue(SmartResume.brokenRules(edges, SmartResume.spans(choice.checkpoint), pathOf, rules).isEmpty())
        }
        // Core already mowed: the chain may come first again, as without constraints.
        val coreDone = checkpoint(a.size + 1, null).copy(preview = preview)
        assertEquals(SmartResume.afterRecharge(edges, checkpoint(a.size + 1, null), approach, 0.0, approach, 180.0, straight)?.entry,
            SmartResume.afterRecharge(edges, coreDone, approach, 0.0, approach, 180.0, straight)?.entry)
        // Constraints that do not match the prepared edges: no reordering.
        val other = interrupted.copy(preview = preview.copy(scheduledPaths = paths.reversed()))
        assertNull(SmartResume.outwardRules(edges, other.preview))
        assertNull(SmartResume.afterRecharge(edges, other, approach, 0.0, approach, 180.0, straight))
    }

    @Test fun `no reordering when the interrupted point is already on the way`() {
        // Interrupted in Q (near the base): going back there first is the shortest.
        val start = a.size + 1
        assertNull(SmartResume.afterRecharge(edges, checkpoint(start, Point(3.0, 4.0)), approach, 0.0, approach, 180.0, straight))
        // Successive height passes keep their order.
        val passes = edges.mapIndexed { i, s -> if (s.cutting) s.copy(heightMm = if (i < a.size) 70 else 50) else s }
        assertFalse(SmartResume.singleHeight(passes))
    }

    @Test fun `progress follows the reordered portions up to the end`() {
        val choice = requireNotNull(SmartResume.afterRecharge(edges, checkpoint(2, Point(22.0, 0.4)), approach, 0.0, approach, 180.0, straight))
        var cp = choice.checkpoint
        // Robot driving the portions in order (prepared edges of each portion).
        val route = SmartResume.spans(cp).flatMap { SmartResume.spanEdges(edges, it) }
        for ((i, s) in route) for (t in listOf(0.5, 0.999)) {
            val p = Point(s.a.x + (s.b.x - s.a.x) * t, s.a.y + (s.b.y - s.a.y) * t)
            SmartResume.advance(cp, edges, i, p, 2L)?.let { cp = it }
        }
        assertTrue("left ${SmartResume.remainingCutting(edges, cp)} m", SmartResume.remainingCutting(edges, cp) < 0.05)
        assertEquals(cp.totalMeters, cp.doneMeters, 0.05)
        // Never more than the plan, and the progress never goes back.
        assertTrue(cp.progress <= 1.0)
    }

    @Test fun `overlap before the resume point does not jump to another portion`() {
        val choice = requireNotNull(SmartResume.afterRecharge(edges, checkpoint(2, Point(22.0, 0.4)), approach, 0.0, approach, 180.0, straight))
        val entry = choice.checkpoint
        // Q is entered at its start; a position just before it (link) is not a progress.
        assertNull(SmartResume.advance(entry, edges, entry.edge, Point(1.0, 4.0), 2L))
        val moved = requireNotNull(SmartResume.advance(entry, edges, entry.edge, Point(2.0, 4.0), 2L))
        assertEquals(entry.deferred, moved.deferred)
        assertEquals(1.0, moved.doneMeters - entry.doneMeters, 1e-6)
    }

    @Test fun `blockage defers the segment to the end, blocked again there it is given up`() {
        val blocked = Point(21.0, 0.0)
        val cp = checkpoint(0, Point(20.9, 0.0))
        val deferral = requireNotNull(SmartResume.deferBlocked(edges, cp, blocked))
        assertTrue(deferral.target.distance(blocked) >= SmartResume.CLEARANCE_M - 1e-9)
        assertTrue(deferral.deferredM >= SmartResume.MIN_SKIP_M)
        assertEquals(0.0, deferral.abandonedM, 0.0)
        val next = deferral.checkpoint
        assertSameRemainder(cp, next)
        // Position memorised on the deferred portion, tried again last.
        assertEquals(blocked, next.deferred.last().blockedAt)
        assertEquals(PlanPos(0, Point(20.9, 0.0)), next.deferred.last().from)

        // The robot mows the rest then reaches the deferred portion: it becomes the portion in progress (retry).
        val retry = SmartResume.withSpans(edges, next, listOf(next.deferred.last()))
        assertEquals(blocked, retry.activeBlocked)
        val again = requireNotNull(SmartResume.deferBlocked(edges, retry, Point(21.05, 0.0)))
        assertEquals(0.0, again.deferredM, 0.0)
        assertTrue(again.abandonedM > 0.0)
        assertEquals(retry.skippedMeters + again.abandonedM, again.checkpoint.skippedMeters, 1e-9)
        assertTrue(SmartResume.mowingDone(edges, again.checkpoint) || again.checkpoint.deferred.none { it.blockedAt != null })
    }

    @Test fun `deferred portion impossible to route is given up near the blocked place only`() {
        val cp = checkpoint(0, Point(20.9, 0.0))
        val deferral = requireNotNull(SmartResume.deferBlocked(edges, cp, Point(21.0, 0.0)))
        val (far, none) = SmartResume.abandonNear(edges, deferral.checkpoint, Point(5.0, 5.0))
        assertEquals(0.0, none, 0.0); assertSame(deferral.checkpoint, far)
        val (kept, dropped) = SmartResume.abandonNear(edges, deferral.checkpoint, Point(21.0, 0.1))
        assertEquals(deferral.deferredM, dropped, 1e-6)
        assertTrue(kept.deferred.none { it.blockedAt != null })
        assertEquals(dropped, kept.skippedMeters, 1e-9)
    }

    @Test fun `no remainder away from the blockage keeps the stop`() {
        val tiny = listOf(SimEdge(Point(0.0, 0.0), Point(1.0, 0.0), true))
        val cp = MissionCheckpoint("AA:BB", "map", "plan", 1, Preview(emptyList(), 0.0, 0, 0), true, 50, 20, 90, totalMeters = 1.0, startedAt = 1L)
        assertNull(SmartResume.deferBlocked(tiny, cp, Point(0.5, 0.0)))
    }

    @Test fun `blockage messages from the Fold and the gateway in both languages`() {
        assertTrue(SmartResume.blockage("Presumed blockage: no translation for 3 s, bumper contact not confirmed"))
        assertTrue(SmartResume.blockage("Blocage présumé : aucune translation depuis 3 s, contact bumper non confirmé"))
        assertTrue(SmartResume.blockage("Absence de progression depuis 15 s (arête 4)"))
        assertTrue(SmartResume.blockage("No progress for 15 s (edge 4)"))
        assertFalse(SmartResume.blockage("Écart au trajet >30 cm"))
        assertFalse(SmartResume.blockage(null))
    }

    @Test fun `reordered progress survives the store, older saves stay linear`() {
        val choice = requireNotNull(SmartResume.afterRecharge(edges, checkpoint(2, Point(22.0, 0.4)), approach, 0.0, approach, 180.0, straight))
        val deferral = requireNotNull(SmartResume.deferBlocked(edges, choice.checkpoint, Point(2.0, 4.0)))
        val store = MissionCheckpointStore(folder.root)
        store.save(deferral.checkpoint)
        val read = requireNotNull(store.read("AA:BB"))
        assertEquals(deferral.checkpoint.activeEnd, read.activeEnd)
        assertEquals(deferral.checkpoint.deferred, read.deferred)
        assertEquals(deferral.checkpoint.activeBlocked, read.activeBlocked)
        assertEquals(deferral.checkpoint, read)
        store.save(checkpoint(2, Point(22.0, 0.4)))
        assertTrue(requireNotNull(store.read("AA:BB")).linear)
    }

    @Test fun `resume edges of a reordered remainder map back to the prepared edges`() {
        val choice = requireNotNull(SmartResume.afterRecharge(edges, checkpoint(2, Point(22.0, 0.4)), approach, 0.0, approach, 180.0, straight))
        val resume = MissionResume.of(choice.checkpoint)
        val spans = resume.spans(edges.size)
        assertEquals(1 + choice.checkpoint.deferred.size, spans.size)
        // Same cutting metres in the route pieces as in the remainder.
        val pieces = spans.flatMap { SmartResume.spanEdges(edges, it, overlap = false) }
        assertEquals(SmartResume.remainingCutting(edges, choice.checkpoint), pieces.filter { it.second.cutting }.sumOf { it.second.meters }, 1e-6)
        val mapping = MissionEdges(pieces.map { it.second }, 2, 2 + pieces.size, 0, pieces.map { it.first })
        assertNull(mapping.engineEdge(0))
        assertEquals(pieces.first().first, mapping.engineEdge(2))
        assertNull(mapping.engineEdge(2 + pieces.size))
        val withLink = MissionEdges(pieces.map { it.second }, 0, pieces.size, 0, listOf(-1) + pieces.drop(1).map { it.first })
        assertNull(withLink.engineEdge(0))
    }
}
