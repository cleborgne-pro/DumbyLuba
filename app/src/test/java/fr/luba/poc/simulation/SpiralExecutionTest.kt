package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.planning.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spiral routes driven by the tracking ([RouteExecutionModel], loop of the actual mowing) on two reference zones, against
 * Zigzag on the same zones: largest deviation within 21 cm (the rolling U-turns), without a blockage or a pivot in the
 * middle of a straight edge. Printed: mission metrics of the plan and execution at 30 and 50 %.
 */
class SpiralExecutionTest {
    @Test(timeout = 900000) fun `spiral routes are followed without exceeding the deviation limits`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val failures = mutableListOf<String>()
        for (name in listOf("Potager", "Jardin d’Eden")) {
            val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == name }
            for ((label, mode) in listOf("zigzag" to CoverageMode.LINES, "spiral" to CoverageMode.SPIRAL)) {
                val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
                    config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
                    navigation = PlanningNavigation(emptyList(), base)), mode)
                assertTrue("$name $label: plan not ready", !plan.preview.localOnly)
                if (mode == CoverageMode.SPIRAL) assertTrue("$name: ${plan.preview.zoneModes}", plan.preview.zoneModes == listOf(CoverageMode.SPIRAL))
                val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone, plan)
                val metrics = plan.report?.selected?.metrics
                for (speed in listOf(30, 50)) {
                    val run = RouteExecutionModel.drive(edges, RouteExecutionModel.Robot(), speed, approach)
                    println("SPIRAL execution $name $label $speed %%: plan %.0f m, %.0f m transit, %d pivots, coverage %.1f %% | %s".format(
                        metrics?.distanceM ?: 0.0, metrics?.transitM ?: 0.0, metrics?.pivots ?: 0, 100 * (metrics?.coverage ?: 0.0), run))
                    val limit = if (mode == CoverageMode.SPIRAL) 0.21 else 0.27
                    if (run.worst > limit || run.blocked > 0 || run.midPivots > 0) failures += "$name $label $speed %: $run"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
