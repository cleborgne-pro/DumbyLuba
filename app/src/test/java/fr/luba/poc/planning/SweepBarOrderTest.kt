package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.MapBase
import org.junit.Assert.*
import org.junit.Test

/** Reference map, saved base, 0.25 m spacing: production cell order (sweep bar, checkerboard by orientation)
 * against the earlier measurements (0.45.2): coverage, duration and backtracks. */
class SweepBarOrderTest {
    private fun request(zoneName: String): PlanningRequest {
        val station=PlannerBenchmarkTest().stationCases().values.single()
        val zone=station.map.shapes.single { it.kind==ShapeKind.ZONE && it.name.trim()==zoneName }
        val base=MapBase(Point(0.14231226759294913,1.2859193243581544),"Placée manuellement",1.5,152.8624890921028,1.5)
        return PlanningRequest(station.map,listOf(zone.id),base.point,
            config=PlanningConfig(spacingM=0.25,minimumCoverage=0.95,boundary=BoundaryMeaning.RECORDED_TRACE),
            navigation=PlanningNavigation(emptyList(),base))
    }
    @Test fun figuierLeavesFarFewerCellsBehindWithoutCostingTime() {
        val selected=RoutePlanning.calculate(request("Figuier")).selected
        assertTrue(selected.diagnostics.toString(),selected.valid)
        val metrics=selected.metrics!!
        assertEquals(0,metrics.geometricViolations)
        assertTrue(metrics.coverage>=0.95)
        // 0.45.2: 44 cells started while leaving a cell behind, 2113 s.
        val order=SweepBarMetrics.of(selected.preview!!)
        assertTrue("${order.backtracks} backtracks",order.backtracks<=15)
        assertTrue("${metrics.durationSeconds} s",metrics.durationSeconds<=2113*(1+AutomaticPlanner.BAR_TOLERANCE))
    }
    @Test fun checkerboardMowsOneOrientationAfterTheOtherOnFosse() {
        val selected=RoutePlanning.calculate(request("Fosse"),CoverageMode.CHECKERBOARD).selected
        assertTrue(selected.diagnostics.toString(),selected.valid)
        assertEquals(0,selected.metrics!!.geometricViolations)
        assertTrue(selected.metrics.coverage>=0.95)
        // 0.45.2: 30 orientation changes between two cells.
        assertEquals(1,SweepBarMetrics.of(selected.preview!!).layerSwitches)
    }
}
