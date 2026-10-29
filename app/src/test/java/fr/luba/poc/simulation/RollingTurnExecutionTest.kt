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
                val windows = RouteManoeuvres.find(edges)
                listOf(30, 50).map { speed -> speed to RouteExecutionModel.drive(edges, RouteExecutionModel.Robot(), speed, approach, windows = windows) }.also { executed ->
                    executed.forEach { (speed, run) ->
                        println("ROLLING $name ${style.name} $speed %%: plan %.0f m, %.0f m transit, %d pivots, %.0f°, coverage %.1f %% | %s | %s".format(
                            metrics?.distanceM ?: 0.0, metrics?.transitM ?: 0.0, metrics?.pivots ?: 0, metrics?.turnDegrees ?: 0.0,
                            100 * (metrics?.coverage ?: 0.0), run, TurnBench.summary(listOf(run))))
                    }
                }
            }
            for ((style, executed) in runs) for ((speed, run) in executed)
                if (run.worst > 0.27 || run.blocked > 0 || run.midPivots > 0) failures += "$name $style $speed %: $run"
            // Rolling U-turns (turn tracking, step C of docs/TRACKING_TURNS_AND_BORDER_CONTACTS.md): no pivot inside the arc,
            // true deviation within 15 cm (p90 within 8 cm) and quicker than before the tangent reference and the
            // feed-forward (bench of 2026-10-28 at 30 %: 15.0 to 15.2 s per U, p90 17 cm, 3 to 4 pivots per U).
            for ((speed, run) in runs.getValue(TurnStyle.LAWN)) {
                val t = TurnBench.totals(listOf(run))
                if (t.uCount == 0 || t.uPivots > 0 || t.uMax > 0.15 || t.uP90 > 0.08 || (speed == 30 && t.uSeconds > 13.5))
                    failures += "$name LAWN $speed %: ${TurnBench.summary(listOf(run))}"
            }
            // Time spent pivoting in place (lawn wear): lower at every speed.
            for (k in runs.getValue(TurnStyle.FAST).indices) {
                val (speed, fast) = runs.getValue(TurnStyle.FAST)[k]
                val lawn = runs.getValue(TurnStyle.LAWN)[k].second
                if (lawn.pivotSeconds >= fast.pivotSeconds) failures += "$name $speed %%: pivoting %.0f s in lawn friendly mode, %.0f s in fast mode".format(lawn.pivotSeconds, fast.pivotSeconds)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
