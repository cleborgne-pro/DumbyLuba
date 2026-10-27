package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lawn friendly U-turns driven by the tracking ([RouteExecutionModel], loop of the actual mowing): the routes prepared
 * by the planner of the map screen with [TurnStyle.LAWN] on reference zones stay within the deviation limits of
 * [RouteExecutionRegressionTest], without a blockage or a pivot in the middle of a pass, and spend less time pivoting
 * in place than the fast route of the same zone. Printed: fast then lawn friendly, mission metrics of the plan and execution.
 */
class RollingTurnExecutionTest {
    @Test(timeout = 900000) fun `rolling U-turns are followed without exceeding the deviation limits`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val failures = mutableListOf<String>()
        for (name in listOf("Potager", "Aire de jeux")) {
            val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == name }
            val runs = TurnStyle.entries.associateWith { style ->
                val plan = ReferenceRoutes.plan(map, base, zone, style)
                val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone, plan)
                val metrics = plan.report?.selected?.metrics
                listOf(30, 50).map { speed -> speed to RouteExecutionModel.drive(edges, RouteExecutionModel.Robot(), speed, approach) }.also { executed ->
                    executed.forEach { (speed, run) ->
                        println("ROLLING $name ${style.name} $speed %%: plan %.0f m, %.0f m transit, %d pivots, %.0f°, coverage %.1f %% | %s".format(
                            metrics?.distanceM ?: 0.0, metrics?.transitM ?: 0.0, metrics?.pivots ?: 0, metrics?.turnDegrees ?: 0.0,
                            100 * (metrics?.coverage ?: 0.0), run))
                    }
                }
            }
            for ((style, executed) in runs) for ((speed, run) in executed)
                if (run.worst > 0.27 || run.blocked > 0 || run.midPivots > 0) failures += "$name $style $speed %: $run"
            // Time spent pivoting in place (lawn wear): lower at every speed. The tracking still pivots a little on some
            // rolling U-turns (reference heading 25 cm ahead on a 0.35 m arc), so the number of pivots is not the measure.
            for (k in runs.getValue(TurnStyle.FAST).indices) {
                val (speed, fast) = runs.getValue(TurnStyle.FAST)[k]
                val lawn = runs.getValue(TurnStyle.LAWN)[k].second
                if (lawn.pivotSeconds >= fast.pivotSeconds) failures += "$name $speed %%: pivoting %.0f s in lawn friendly mode, %.0f s in fast mode".format(lawn.pivotSeconds, fast.pivotSeconds)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
