package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

/** Manual direction of the passes ([OrientationMode.MANUAL]) through the production entry point, synthetic zones. */
class ManualOrientationTest {
    private val lShape = listOf(Point(0.0,0.0),Point(16.0,0.0),Point(16.0,5.0),Point(5.0,5.0),Point(5.0,14.0),Point(0.0,14.0))

    private fun manual(angle: Int, minimumCoverage: Double = 0.65) =
        PlanningConfig(spacingM=0.35, minimumCoverage=minimumCoverage, orientation=OrientationMode.MANUAL, manualAngleDeg=angle)

    /** Directions (0..179) of the straight passes of a preview. */
    private fun directions(preview: Preview) = preview.passes.filter { it.size >= 2 && it.first().distance(it.last()) > 0.3 }
        .map { foldAngle(Math.round(heading(it.first(), it.last())).toInt()) }.toSet()

    private fun near(a: Int, b: Int) = OrientationRule.distance(a, b) <= 1

    @Test fun everyZoneTakesTheRequestedAngle() {
        for (shape in listOf(rectangle(0.0,0.0,14.0,9.0), lShape)) for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD))
            for (angle in listOf(0, 37, 90, 143)) {
                val result = RoutePlanning.calculate(request(shape, config=manual(angle)), mode)
                val selected = result.selected
                assertTrue("$mode $angle°: ${selected.diagnostics}", selected.valid)
                assertEquals(0, selected.metrics!!.geometricViolations)
                val preview = selected.preview!!
                val expected = foldAngle(angle, mode.orientationPeriod)
                assertEquals("$mode $angle°", listOf(expected), preview.zoneAngles)
                val families = if (mode == CoverageMode.CHECKERBOARD) listOf(expected, expected + 90) else listOf(expected)
                val used = directions(preview)
                assertTrue("$mode $angle°: passes at $used", used.isNotEmpty() && used.all { d -> families.any { near(d, it) } })
                assertEquals(listOf(0.5), preview.zonePhases)
                assertEquals(listOf(mode), preview.zoneModes)
                // Same request, same plan.
                assertEquals(selected.planHash, RoutePlanning.calculate(request(shape, config=manual(angle)), mode).selected.planHash)
            }
    }

    @Test fun anAngleThatMissesTheTargetIsFlaggedNeverReplaced() {
        // Unreachable coverage target: the automatic search would not help either; the manual angle is kept and the
        // plan is refused with the advice to use Automatic.
        val result = RoutePlanning.calculate(request(lShape, config=manual(37, minimumCoverage=0.999)), CoverageMode.LINES)
        assertFalse(result.selected.valid)
        assertEquals(PlanStatus.PARTIAL_COVERAGE, result.selected.status)
        assertEquals(listOf(37), result.selected.preview!!.zoneAngles)
        assertTrue(result.selected.diagnostics.toString(), result.selected.diagnostics.any { "37°" in it && "Automatique" in it })
        assertTrue(result.candidates.map { it.name }.toString(), result.candidates.none { it.name == "continuous-reference" })
        // The production facade publishes it as a local-only preview: Start stays disabled.
        assertTrue(RoutePlanning.coverage(request(lShape, config=manual(37, minimumCoverage=0.999)), CoverageMode.LINES).preview.localOnly)
    }

    @Test fun optimizedIgnoresTheManualAngle() {
        val automatic = RoutePlanning.calculate(request(lShape, config=PlanningConfig(spacingM=0.35, minimumCoverage=0.65)), CoverageMode.OPTIMIZED)
        val withManual = RoutePlanning.calculate(request(lShape, config=manual(37)), CoverageMode.OPTIMIZED)
        assertEquals(automatic.selected.planHash, withManual.selected.planHash)
        assertEquals(listOf(CoverageMode.OPTIMIZED), withManual.selected.preview!!.zoneModes)
    }

    @Test fun manualAngleOutsideTheRangeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { PlanningConfig(orientation=OrientationMode.MANUAL, manualAngleDeg=180) }
        assertThrows(IllegalArgumentException::class.java) { PlanningConfig(manualAngleDeg=-5) }
    }
}
