package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class MultiZonePlannerTest {
    private fun zone(id:Long,x:Double)=MapShape(id,0,listOf(Point(x,0.0),Point(x+6,0.0),Point(x+6,5.0),Point(x,5.0)),"Jardin $id")
    @Test fun combinesZonesWithoutConnectingAcrossUnmappedSpace() {
        val zones=listOf(zone(1,0.0),zone(2,20.0))
        val steps=mutableListOf<Int>()
        val result=MultiZonePlanner.plan(zones,emptyList(),0.5,0.6,CoverageMode.LINES,true,progress={ done,_,_->steps.add(done) })
        assertEquals(listOf(1L,2L),result.zoneIds)
        assertEquals(listOf(0,1,2),steps)
        assertEquals(2,result.outerLoops)
        assertTrue(result.runs.all { run -> zones.any { zone -> run.zipWithNext().all { (a,b)->Geometry.safe(a,b,zone.points,emptyList(),0.6) } } })
        assertTrue(result.passes.isNotEmpty())
        assertTrue(result.passes.all { run -> zones.any { zone -> run.zipWithNext().all { (a,b)->Geometry.safe(a,b,zone.points,emptyList(),0.6) } } })
        val sum=zones.sumOf { Geometry.plan(it.points,emptyList()).meters }
        assertEquals(sum,result.meters,1e-6)
    }
    @Test fun startsInFirstSelectedZoneEvenWhenBaseIsCloserToSecond() {
        val zones = listOf(zone(1,0.0),zone(2,20.0))
        val base = Point(26.0,2.5)
        val result = MultiZonePlanner.plan(zones,emptyList(),0.5,0.6,CoverageMode.LINES,true,basePoint=base)
        assertTrue(result.cuttingPaths.first().first().x < 6)
        val zoneOrder = result.cuttingPaths.map { if (it.first().x < 6) 1L else 2L }.distinct()
        assertEquals(listOf(1L,2L),zoneOrder)
        assertEquals(zones.sumOf { Geometry.plan(it.points,emptyList()).meters },result.meters,1e-6)
    }
    @Test fun selectionOrderKeepsEachZonesBordersAndRowsTogether() {
        val zones=listOf(zone(2,20.0),zone(1,0.0))
        for (first in listOf(true,false)) for (nearby in listOf(true,false)) {
            val result=MultiZonePlanner.plan(zones,emptyList(),0.5,0.6,CoverageMode.LINES,true,
                basePoint=Point(0.0,2.5),bordersFirst=first,nearbyNoGo=nearby)
            assertEquals(listOf(2L,1L),result.zoneIds)
            assertEquals(listOf(2L,1L),result.cuttingPaths.map { if(it.first().x>20) 2L else 1L }.distinct())
        }
    }
    @Test fun nextZoneUsesPreviousExitInsteadOfTheStation() {
        val zones=listOf(zone(1,0.0),zone(2,20.0))
        val base=Point(26.0,2.5)
        val first=Geometry.plan(zones[0].points,emptyList(),0.5,0.6,CoverageMode.LINES,true,basePoint=base)
        val entry=first.cuttingPaths.last().last()
        val next=Geometry.plan(zones[1].points,emptyList(),0.5,0.6,CoverageMode.LINES,true,basePoint=entry)
        val result=MultiZonePlanner.plan(zones,emptyList(),0.5,0.6,CoverageMode.LINES,true,basePoint=base)
        val secondStart=result.cuttingPaths.first { it.first().x>20 }.first()
        assertEquals(next.cuttingPaths.first().first(),secondStart)
        assertTrue(secondStart.x<23.0)
    }
    @Test(expected=IllegalArgumentException::class) fun emptySelectionRejected() {
        MultiZonePlanner.plan(emptyList(),emptyList(),0.5,0.6,CoverageMode.LINES,true)
    }
    @Test fun failingZoneNamesTheProblemInsteadOfReturningPartialSuccess() {
        try {
            MultiZonePlanner.plan(listOf(zone(1,0.0),MapShape(2,0,listOf(Point(0.0,0.0),Point(0.1,0.0),Point(0.0,0.1)),"Petit jardin")),emptyList(),0.5,0.6,CoverageMode.LINES,true)
            fail("A partly impossible selection must be refused")
        } catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("Petit jardin")) }
    }

    @Test fun livePreviewsGrowZoneByZoneAndEndOnTheFullResult() {
        val zones=listOf(zone(1,0.0),zone(2,20.0))
        val live=mutableListOf<Preview>()
        val result=MultiZonePlanner.plan(zones,emptyList(),0.5,0.6,CoverageMode.LINES,true,onLive={ live.add(it) })
        assertTrue(live.isNotEmpty())
        assertTrue(live.all { it.zoneIds.isNotEmpty() && it.zoneIds==listOf(1L,2L).take(it.zoneIds.size) })
        assertEquals(listOf(1L,2L),result.zoneIds)
        assertEquals(result,live.last())
    }
}
