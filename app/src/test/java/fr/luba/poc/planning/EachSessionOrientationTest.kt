package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

/** "Change every session" ([OrientationMode.EACH_SESSION]) through the production entry point, synthetic zones. */
class EachSessionOrientationTest {
    private val shape = rectangle(0.0, 0.0, 14.0, 9.0)
    private val config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65)
    private fun eachSession(memory: Map<Long, ZoneOrientation>) =
        request(shape, config = config.copy(orientation = OrientationMode.EACH_SESSION)).copy(orientationMemory = memory)
    private fun remembered(preview: Preview, request: PlanningRequest) =
        ZoneProgress.update(emptyMap(), preview, request.map, Double.MAX_VALUE, 1L)

    @Test fun withoutMemoryOrWithAnotherOutlineItIsAutomatic() {
        val automatic = RoutePlanning.calculate(request(shape, config = config), CoverageMode.LINES).selected
        assertEquals(automatic.planHash, RoutePlanning.calculate(eachSession(emptyMap()), CoverageMode.LINES).selected.planHash)
        val stale = mapOf(1L to ZoneOrientation(listOf(automatic.preview!!.angle), 0, "retraced", 1L))
        assertEquals(automatic.planHash, RoutePlanning.calculate(eachSession(stale), CoverageMode.LINES).selected.planHash)
    }

    @Test fun nextSessionsNeverRepeatTheLastDirectionAndPhase() {
        for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD)) {
            val automatic = RoutePlanning.calculate(request(shape, config = config), mode).selected
            var memory = remembered(automatic.preview!!, request(shape))
            var last = automatic.preview!!.angle to 0
            repeat(4) { session ->
                val previous = memory.getValue(1L)
                val selected = RoutePlanning.calculate(eachSession(memory), mode).selected
                assertTrue("$mode #$session: ${selected.diagnostics}", selected.valid)
                assertEquals(0, selected.metrics!!.geometricViolations)
                val p = selected.preview!!
                val pair = p.angle to OrientationRule.phaseIndex(p.zonePhases.single())
                val separation = OrientationRule(previous.directions, 0.5).separation(p.angle, mode.orientationPeriod)
                assertTrue("$mode #$session: $pair after $previous", separation >= 45 || pair.second != previous.phaseIndex)
                assertNotEquals(last, pair)
                // Deterministic.
                assertEquals(selected.planHash, RoutePlanning.calculate(eachSession(memory), mode).selected.planHash)
                last = pair
                memory = remembered(p, request(shape))
            }
        }
    }
}
