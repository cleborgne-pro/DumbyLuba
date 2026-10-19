package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class NearestStartTest {
    @Test fun loopStartsAtTheNearestSegmentInsteadOfItsFixedAnchorAndPreservesItsEntireFootprint() {
        val loop=rectangle(w=20.0,h=10.0).let { it+it.first() }
        val from=Point(18.0,-2.0)
        val p=Preview(emptyList(),60.0,0,0,borders=listOf(loop),outerLoops=1,borderKinds=listOf(false),borderAnchors=listOf(loop.first()))
        val result=LocalMowingOrder.schedule(p,from,true,true,
            { _,path -> from.distance(path.first()) },{ true },entryDistance={ from.distance(it.first()) })
        val path=result.cuttingPaths.single()
        assertEquals(Point(18.0,0.0),path.first());assertEquals(path.first(),path.last())
        assertTrue(path.containsAll(loop));assertEquals(length(loop),length(path),1e-8)
        val target=polygon(rectangle(-5.0,-5.0,30.0,20.0))
        val before=MissionEvaluator.coverage(listOf(MissionLeg(loop,true)),target,0.2)
        val after=MissionEvaluator.coverage(listOf(MissionLeg(path,true)),target,0.2)
        assertEquals(before.covered,after.covered,1e-8);assertEquals(before.extra,after.extra,1e-8)
    }
    @Test fun inaccessibleProjectionIsRejectedAndActualAccessDistanceWinsOverStraightLineDistance() {
        val loop=rectangle(w=20.0,h=10.0).let { it+it.first() };val from=Point(18.0,-2.0)
        val p=Preview(emptyList(),60.0,0,0,borders=listOf(loop),outerLoops=1,borderKinds=listOf(false))
        fun distance(path: List<Point>): Double = when(path.first()) {
            Point(18.0,0.0) -> Double.POSITIVE_INFINITY
            Point(20.0,0.0) -> 50.0
            Point(20.0,10.0) -> 13.0
            else -> 100.0
        }
        val result=LocalMowingOrder.schedule(p,from,true,true,{ _,path -> distance(path) },{ true },entryDistance=::distance)
        assertEquals(Point(20.0,10.0),result.cuttingPaths.first().first())
        assertThrows(IllegalArgumentException::class.java) {
            LocalMowingOrder.schedule(p,from,true,true,{ _,_->Double.POSITIVE_INFINITY },{ true },
                entryDistance={ Double.POSITIVE_INFINITY })
        }
    }
    @Test fun openPassesStayWholeAndUseTheNearestSafeEndpoint() {
        val a=listOf(Point(0.0,0.0),Point(20.0,0.0));val b=listOf(Point(17.0,1.0),Point(18.0,1.0))
        val from=Point(18.0,0.0);val p=Preview(listOf(a,b),21.0,0,0,runCellIds=listOf(1,2))
        val result=LocalMowingOrder.schedule(p,from,false,false,{ _,_->0.0 },{ true },
            entryDistance={ from.distance(it.first()) })
        assertEquals(b.reversed(),result.cuttingPaths.first());assertEquals(2,result.cuttingPaths.size)
        assertEquals(setOf(a.toSet(),b.toSet()),result.cuttingPaths.map { it.toSet() }.toSet())
    }
    @Test fun newLoopOriginsRequireManeuverSpaceAndKeepTheValidatedOriginalAnchorAsFallback() {
        val loop=rectangle(w=20.0,h=10.0).let { it+it.first() };val from=Point(18.0,-2.0)
        val p=Preview(emptyList(),60.0,0,0,borders=listOf(loop),outerLoops=1,borderKinds=listOf(false))
        val routed=mutableListOf<Point>()
        val result=LocalMowingOrder.schedule(p,from,true,true,{ _,_->0.0 },{ true },
            entryDistance={ routed.add(it.first());from.distance(it.first()) },loopSafe={ true },
            loopEntrySafe={ false })
        assertEquals(loop.first(),result.cuttingPaths.single().first())
        assertTrue(routed.all { it==loop.first() })
    }
    @Test fun recordedPlaygroundStartsNearItsRecordedBaseForMowingAndSignalContour() {
        val input=PlannerBenchmarkTest().stationCases().values.single()
        for(mode in listOf(CoverageMode.LINES,CoverageMode.TOUR)) {
            val result=RoutePlanning.calculate(input,mode)
            assertTrue(result.selected.diagnostics.toString(),result.selected.valid)
            val first=result.selected.mission.first { !it.cutting }.path
            assertTrue("Initial access ${length(first)} m",length(first)<5.0)
            assertEquals(0,result.selected.metrics!!.geometricViolations)
        }
    }
    @Test fun selectionOrderIsKeptEvenWhenAnotherZoneIsCloserToTheBase() {
        val map=RobotMap(listOf(MapShape(1,0,rectangle(10.0,0.0,10.0,10.0)),
            MapShape(2,0,rectangle(w=10.0,h=10.0))),true,"connected-zones")
        for(order in listOf(listOf(1L,2L),listOf(2L,1L))) {
            val input=PlanningRequest(map,order,Point(2.0,2.0),config=PlanningConfig(spacingM=0.5,minimumCoverage=0.4))
            val result=RoutePlanning.calculate(input)
            assertTrue(result.selected.diagnostics.toString(),result.selected.valid)
            val preview=result.selected.preview!!
            assertEquals(order,preview.zoneIds)
            // Actual cutting in the requested order: zone 2 at x < 10, zone 1 beyond.
            val visited=preview.cuttingPaths.map { path -> if(path.sumOf { it.x }/path.size<10.0) 2L else 1L }.distinct()
            assertEquals(order,visited)
            // The first chosen zone is reached through its entry closest to the base.
            if(order.first()==2L) assertTrue(length(result.selected.mission.first().path)<=1.5)
        }
    }
    @Test fun obstacleEntryPolicyMustNotLoseThePreviouslyValidPotager() {
        val input=PlannerBenchmarkTest().referenceCases("Potager").values.single()
        val result=RoutePlanning.calculate(input.copy(config=input.config.copy(spacingM=0.25,minimumCoverage=0.95)))
        assertTrue(result.selected.diagnostics.toString(),result.selected.valid)
        assertEquals(0,result.selected.metrics!!.geometricViolations)
        assertTrue(result.selected.metrics!!.coverage>=0.95)
    }
}
