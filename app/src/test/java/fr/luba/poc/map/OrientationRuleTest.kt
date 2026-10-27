package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class OrientationRuleTest {
    private fun rule(vararg avoid: Int, phase: Double = 0.2) = OrientationRule(avoid.toList(), phase)

    @Test fun distanceIsTakenModulo180() {
        assertEquals(1, OrientationRule.distance(0, 179))
        assertEquals(90, OrientationRule.distance(10, 100))
        assertEquals(20, OrientationRule.distance(170, 10))
        assertEquals(20, OrientationRule.distance(-10, 10))
        assertEquals(0, OrientationRule.distance(45, 225))
    }

    @Test fun separationAgainstOneOrTwoDirections() {
        val lines = rule(60)
        assertEquals(45, lines.separation(105))
        assertEquals(90, lines.separation(150))
        assertEquals(20, lines.separation(80))
        // Checkerboard candidate: both of its families count.
        assertEquals(30, lines.separation(0, period = 90))
        // Previous checkerboard 30 / 120: only the 45° diagonal is far enough from both families.
        val checkerboard = rule(30, 120)
        assertEquals(45, checkerboard.separation(75, period = 90))
        assertEquals(30, checkerboard.separation(60, period = 90))
        assertEquals(45, checkerboard.separation(165))
        assertEquals(15, checkerboard.separation(15))
    }

    @Test fun pickKeepsTheCheapestSeparatedCandidateWithinFivePercent() {
        val r = rule(60)
        val auto = OrientationRule.Candidate("auto", 60, 180, 100.0)
        assertEquals("far", r.pick(listOf(auto, OrientationRule.Candidate("far", 105, 180, 104.9)))?.value)
        assertNull(r.pick(listOf(auto, OrientationRule.Candidate("far", 105, 180, 105.1))))
        // Too close, however cheap.
        assertNull(r.pick(listOf(auto, OrientationRule.Candidate("near", 80, 180, 100.5))))
        // Cheapest separated one, not the first.
        assertEquals("b", r.pick(listOf(auto, OrientationRule.Candidate("a", 150, 180, 104.0),
            OrientationRule.Candidate("b", 0, 180, 102.0)))?.value)
        // The cheapest candidate itself far enough: kept, no extra cost.
        assertEquals("auto", rule(150).pick(listOf(auto, OrientationRule.Candidate("x", 0, 180, 101.0)))?.value)
        assertNull(r.pick(emptyList<OrientationRule.Candidate<String>>()))
    }

    @Test fun ruleIsDeterministic() {
        val r = rule(60)
        val candidates = listOf(OrientationRule.Candidate("auto", 60, 180, 100.0), OrientationRule.Candidate("first", 105, 180, 103.0),
            OrientationRule.Candidate("second", 15, 180, 103.0))
        repeat(5) { assertEquals("first", r.pick(candidates)?.value) }
    }

    @Test fun extraAnglesGiveASeparatedCandidate() {
        assertEquals(listOf(105, 15), OrientationRule.extraAngles(listOf(60)))
        assertEquals(listOf(35, 125), OrientationRule.extraAngles(listOf(170)))
        assertEquals(listOf(75, 165), OrientationRule.extraAngles(listOf(30, 120)))
        val r = rule(30, 120)
        OrientationRule.extraAngles(r.avoid).forEach { assertEquals(45, r.separation(it)) }
    }

    @Test fun phasesCycleAndAreRecognized() {
        assertEquals(listOf(0.5, 0.2, 0.8), OrientationRule.PHASES)
        assertEquals(1, OrientationRule.nextPhaseIndex(0))
        assertEquals(2, OrientationRule.nextPhaseIndex(1))
        assertEquals(0, OrientationRule.nextPhaseIndex(2))
        assertEquals(1, OrientationRule.nextPhaseIndex(3))
        assertEquals(1, OrientationRule.phaseIndex(0.2))
        assertEquals(2, OrientationRule.phaseIndex(0.8))
        assertEquals(0, OrientationRule.phaseIndex(0.33))
        assertEquals(listOf(30, 120), OrientationRule.directions(30, 90))
        assertEquals(listOf(30), OrientationRule.directions(210))
        assertThrows(IllegalArgumentException::class.java) { OrientationRule(emptyList(), 0.2) }
        assertThrows(IllegalArgumentException::class.java) { OrientationRule(listOf(200), 0.2) }
    }
}
