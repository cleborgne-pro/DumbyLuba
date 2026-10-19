package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class MowingOrderTest {
    private val row = listOf(Point(0.0,0.0),Point(10.0,0.0))
    private val exterior = listOf(Point(0.0,-3.0),Point(10.0,-3.0),Point(10.0,3.0),Point(0.0,-3.0))
    private val hole = listOf(Point(4.0,1.0),Point(5.0,1.0),Point(5.0,2.0),Point(4.0,1.0))
    private val preview = Preview(listOf(row),30.0,0,0,borders=listOf(exterior,hole),outerLoops=1,noGoLoops=1,borderKinds=listOf(false,true))
    @Test fun groupedNoGoRunsBeforeExteriorAndCanStartOrEndTheJob() {
        val first = MowingOrder.apply(preview,true,false,null).cuttingPaths
        assertTrue(first[0].containsAll(hole)); assertTrue(first[1].containsAll(exterior))
        assertEquals(row,first.last())
        val last = MowingOrder.apply(preview,false,false,null).cuttingPaths
        assertEquals(row,last.first())
        assertTrue(last[1].containsAll(hole)); assertTrue(last[2].containsAll(exterior))
    }
    @Test fun nearbyNoGoInterruptsTheClosestRowAndResumesWithoutLosingCoverage() {
        val result = MowingOrder.apply(preview,false,true,null).cuttingPaths
        assertEquals(Point(4.0,0.0),result.first().last())
        assertEquals(hole,result[1])
        assertEquals(result.first().last(),result[2].first())
        assertEquals(row.last(),result[2].last())
        assertTrue(result.last().containsAll(exterior))
        assertEquals(10.0,result.take(3).filterIndexed { i,_ -> i != 1 }.sumOf { p -> p.zipWithNext().sumOf { (a,b) -> a.distance(b) } },1e-8)
    }
    @Test fun nearbyNoGoWaitsForClosestPassInsteadOfAnEarlierNearbyRow() {
        val early = listOf(Point(0.0,0.1),Point(10.0,0.1))
        val close = listOf(Point(10.0,0.8),Point(0.0,0.8))
        val result = MowingOrder.apply(preview.copy(runs=listOf(early,close)),false,true,null).cuttingPaths
        assertEquals(early,result.first())
        assertEquals(Point(4.0,0.8),result[1].last())
        assertEquals(result[1].last(),result[3].first())
        assertEquals(close.last(),result[3].last())
    }
    @Test fun nearbyNoGoWaitsForClosestSegmentWithinTheSameRun() {
        val run = listOf(Point(0.0,0.1),Point(10.0,0.1),Point(10.0,0.8),Point(0.0,0.8))
        val result = MowingOrder.apply(preview.copy(runs=listOf(run)),false,true,null).cuttingPaths
        assertTrue(result.first().contains(Point(10.0,0.1)))
        assertEquals(Point(4.0,0.8),result.first().last())
    }
    @Test fun noBordersLeavesTheWholeCoverageAndZeroPassesAreAccepted() {
        val zone = listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(0.0,10.0))
        val plan = Geometry.plan(zone,emptyList(),borderPasses=0,noGoPasses=0)
        assertTrue(plan.borders.isEmpty())
        assertEquals(plan.runs,MowingOrder.apply(plan,true,true,null).cuttingPaths)
    }
    @Test fun recordedLoopsKeepVerifiedEntriesWhenReordered() {
        val verified=preview.copy(borderAnchors=listOf(exterior.first(),hole.first()))
        for (first in listOf(false,true)) for (nearby in listOf(false,true)) {
            val result=MowingOrder.apply(verified,first,nearby,Point(9.0,0.0))
            val loops=result.cuttingPaths.filter { it.first()==it.last() }
            assertEquals(2,loops.size)
            assertTrue(loops.all { it.first() in verified.borderAnchors })
            assertEquals(verified.borderAnchors,result.borderAnchors)
        }
    }
}
