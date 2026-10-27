package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.planning.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zamboni routes driven by the tracking ([RouteExecutionModel], loop of the actual mowing) on two reference zones, against
 * Lawn friendly on the same zones: largest deviation within 21 cm (the rolling U-turns of Lawn friendly), without a
 * blockage or a pivot in the middle of a pass. Printed: mission metrics of the plan and execution at 30 and 50 %.
 */
class ZamboniExecutionTest {
    @Test(timeout = 900000) fun `zamboni routes are followed without exceeding the deviation limits`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val failures = mutableListOf<String>()
        for (name in listOf("Potager", "Jardin d’Eden")) {
            val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == name }
            for ((label, mode, style) in listOf(Triple("lawn", CoverageMode.LINES, TurnStyle.LAWN), Triple("zamboni", CoverageMode.ZAMBONI, TurnStyle.LAWN))) {
                val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
                    config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE, turnStyle = style),
                    navigation = PlanningNavigation(emptyList(), base)), mode)
                assertTrue("$name $label: plan not ready", !plan.preview.localOnly)
                val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone, plan)
                val metrics = plan.report?.selected?.metrics
                for (speed in listOf(30, 50)) {
                    val run = RouteExecutionModel.drive(edges, RouteExecutionModel.Robot(), speed, approach)
                    println("ZAMBONI execution $name $label $speed %%: plan %.0f m, %.0f m transit, %d pivots, coverage %.1f %% | %s".format(
                        metrics?.distanceM ?: 0.0, metrics?.transitM ?: 0.0, metrics?.pivots ?: 0, 100 * (metrics?.coverage ?: 0.0), run))
                    // Zamboni: within the 21 cm of the rolling U-turns of Lawn friendly (measured 0.197 to 0.204 m).
                    val limit = if (mode == CoverageMode.ZAMBONI) 0.21 else 0.27
                    if (run.worst > limit || run.blocked > 0 || run.midPivots > 0) failures += "$name $label $speed %: $run"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
