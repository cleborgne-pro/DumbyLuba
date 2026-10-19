package fr.luba.poc.diagnostics

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

class BladeBenchTest {
    @Test fun `preconditions listed in refusal order`() {
        val ok = BladeSafety.checks("Luba-VS-x", 100, 72, 11, 0, 0, false, 60)
        assertTrue(ok.all { it.ok })
        val failed = BladeSafety.checks("Luba-VS-x", 100, 72, 11, 512, 0, false, 60)
        assertEquals(listOf("Bumper libre, lames OFF"), failed.filterNot { it.ok }.map { it.label })
        val many = BladeSafety.checks(null, null, null, 13, null, 34, true, 80)
        assertEquals(8, many.count { !it.ok })
        val error = assertThrows(IllegalArgumentException::class.java) { BladeSafety.requireReady(null, null, null, 13, null, 34, true, 80) }
        assertEquals(many.first { !it.ok }.detail, error.message)
    }

    @Test fun `height sequences stay in range and return to the start`() {
        assertEquals(listOf(70, 60), HeightSequence.roundTrip(60))
        assertEquals(listOf(55, 65), HeightSequence.roundTrip(65))
        assertEquals(listOf(70, 25, 60), HeightSequence.extremes(60))
        assertEquals(listOf(25, 70, 30), HeightSequence.extremes(30))
        assertEquals(listOf(70, 25), HeightSequence.extremes(25))
        assertEquals(listOf(25, 70), HeightSequence.extremes(70))
        for (mm in 25..70) {
            listOf(HeightSequence.roundTrip(mm), HeightSequence.extremes(mm)).forEach { seq ->
                assertTrue(seq.all { it in 25..70 }); assertEquals(mm, seq.last())
                assertTrue(seq.zipWithNext().none { (a, b) -> a == b }); assertNotEquals(mm, seq.first())
            }
        }
        assertThrows(IllegalArgumentException::class.java) { HeightSequence.roundTrip(24) }
    }

    @Test fun `height timeout proportional to the travel`() {
        assertEquals(60_000L, HeightSequence.timeoutMs(null, 60))
        assertEquals(30_000L, HeightSequence.timeoutMs(60, 60))
        assertEquals(65_000L, HeightSequence.timeoutMs(25, 70))
    }

    private fun plan(labels: List<String>) = MapNavPlan(emptyList(), emptyList(), labels.indices.map { Point(it.toDouble(), 0.0) },
        emptyList(), emptyList(), labels)

    @Test fun `blade runs alternate cut stripes and stopped links`() {
        val p = plan(listOf("Départ", "Liaison", "Bande 1", "Bande 1", "Liaison", "Liaison", "Bande 2", "Bordure", "Contournement local"))
        assertEquals(listOf(false, true, true, false, false, true, true, false), (0 until 8).map(p::cutting))
        assertEquals(1, p.bladeRangeEnd(0))
        assertEquals(3, p.bladeRangeEnd(1))
        assertEquals(5, p.bladeRangeEnd(3))
        assertEquals(7, p.bladeRangeEnd(5))
        assertEquals(8, p.bladeRangeEnd(7))
        assertEquals(4.0, p.cuttingMeters, 1e-9)
        assertThrows(IllegalArgumentException::class.java) { p.bladeRangeEnd(8) }
    }

    @Test fun `real map course plans meters with blades running`() {
        val zone = fr.luba.poc.map.MapShape(1, 0, listOf(Point(0.0,0.0), Point(10.0,0.0), Point(10.0,10.0), Point(0.0,10.0)), "z")
        val map = fr.luba.poc.map.RobotMap(shapes = listOf(zone), complete = true)
        val plan = MapNavPlanner.plan(map, Point(5.0, 5.0), 4.0, emptyList())
        assertTrue(plan.cuttingMeters > 2.0)
        assertTrue(plan.cuttingMeters < plan.meters + 1e-9)
        var edge = 0
        while (edge < plan.points.size - 1) { val end = plan.bladeRangeEnd(edge); assertTrue(end > edge); edge = end }
    }
}
