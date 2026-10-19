package fr.luba.poc.planning

import fr.luba.poc.map.CoveragePlanner
import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

/**
 * 2026-10-14: outer loop inset raised to 50 cm or more, "Surface résiduelle" on the Piscine (89 % at 50 cm, 74 % at
 * 1 m): the stripe deliberately left along the outline counted as unmowed. The target now follows the path of the
 * outer loop; the inside remains subject to the 95 % threshold.
 */
class OuterOffsetCoverageTest {
    private fun plan(zone: String, offset: Double, passes: Int = 1, obstacles: List<PlanningObstacle> = emptyList()): CandidateResult {
        val source = PlannerBenchmarkTest().referenceCases(zone).getValue(zone)
        return RoutePlanning.calculate(source.copy(config = source.config.copy(spacingM = 0.25, minimumCoverage = 0.95,
            borderPasses = passes, outerOffsetM = offset, candidateBudget = 8, localSearchBudget = 24), obstacles = obstacles)).selected
    }

    @Test(timeout = 600000) fun piscineAcceptsAnOuterOffsetUpToOneMetre() {
        // Obstacle saved and objects hit on 2026-10-14 in the Piscine (radius + margin 0.5 m).
        val obstacles = listOf(Point(-19.4727, -8.8001), Point(-0.3836, -8.7248), Point(-19.553, -13.466), Point(-19.6905, -13.1569))
            .map { PlanningObstacle(it, 0.5) }
        for (offset in listOf(0.5, CoveragePlanner.MAX_OUTER_OFFSET_M)) for (passes in listOf(1, 2)) {
            val result = plan("Piscine", offset, passes, obstacles)
            assertTrue("inset $offset m, $passes loop(s): ${result.diagnostics}", result.valid)
            assertTrue(result.metrics!!.coverage >= 0.95)
            assertEquals(0, result.metrics.geometricViolations)
        }
        // Historical inset: target and result unchanged (whole outline).
        val historic = plan("Piscine", CoveragePlanner.RECORDED_OUTER_OFFSET_M)
        val wide = plan("Piscine", CoveragePlanner.MAX_OUTER_OFFSET_M)
        assertTrue(historic.valid && historic.metrics!!.targetAreaM2 > wide.metrics!!.targetAreaM2 + 50)
    }

    @Test(timeout = 1800000) fun everyReferenceZoneAcceptsALargeOuterOffset() {
        // Narrow zones included (Figuier, Potager): corners and bottlenecks rounded by the loop out of the target.
        for (zone in PlannerBenchmarkTest().referenceCases().keys) for (offset in listOf(0.5, CoveragePlanner.MAX_OUTER_OFFSET_M)) {
            val result = plan(zone, offset)
            assertTrue("$zone, inset $offset m: ${result.diagnostics}", result.valid)
            assertEquals(0, result.metrics!!.geometricViolations)
        }
    }
}
