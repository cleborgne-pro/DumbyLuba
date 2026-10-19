package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class LocalMowingOrderTest {
    @Test fun aDuplicatedPassNeverHidesAMissingPassWhenComparingCoverageGeometry() {
        val a=row(0.0);val b=row(1.0)
        assertFalse(AutomaticPlanner.sameCoveragePaths(listOf(a,a),listOf(a,b)))
        assertTrue(AutomaticPlanner.sameCoveragePaths(listOf(a,b),listOf(b.reversed(),a.reversed())))
        assertTrue(AutomaticPlanner.sameCoveragePaths(listOf(a,a,b),listOf(a,b)))
    }
    @Test fun playgroundProductionFinishesEveryCellWithoutSplittingItsPasses() {
        val source=PlannerBenchmarkTest().referenceCases("Aire de jeux").getValue("Aire de jeux")
        val input=source.copy(config=source.config.copy(spacingM=0.25,minimumCoverage=0.95,
            candidateBudget=8,localSearchBudget=24))
        val result=RoutePlanning.calculate(input)
        assertTrue(result.selected.diagnostics.toString(),result.selected.valid)
        assertEquals(1,result.candidates.size)
        val preview=result.selected.preview!!
        val seen=mutableSetOf<Int>();val counts=mutableMapOf<Int,Int>()
        var active: Int?=null
        for(path in preview.cuttingPaths) {
            val index=preview.runs.indexOfFirst { it==path || it.reversed()==path }
            val cell=if(index<0) null else preview.runCellIds[index]
            if(cell!=null) {
                counts[index]=(counts[index] ?: 0)+1
                if(cell!=active) assertTrue("Return into cell $cell",seen.add(cell))
            } else assertTrue("Pass split or lost",preview.borders.any { loop ->
                // Whole no-go loop, extended if needed with its slanted entry and exit (further out, 1.2 m).
                line(path).buffer(1e-7).covers(line(loop)) && line(loop).buffer(0.5).covers(line(path)) })
            active=cell
        }
        assertEquals(preview.runs.indices.associateWith { 1 },counts)
        assertEquals(0,result.selected.metrics!!.geometricViolations)
        assertTrue(result.selected.metrics.coverage>=0.95)
    }
    @Test fun rotatedRectanglesKeepLongPassesAndAvoidTheShortAxisTurnRegression() {
        for(angle in listOf(7.0,37.0,89.0)) {
            val rad=Math.toRadians(angle)
            fun rotate(p: Point)=Point(p.x*kotlin.math.cos(rad)-p.y*kotlin.math.sin(rad),
                p.x*kotlin.math.sin(rad)+p.y*kotlin.math.cos(rad))
            val original=request(rectangle(w=14.0,h=5.0))
            val input=original.copy(map=original.map.copy(shapes=original.map.shapes.map {
                it.copy(points=it.points.map(::rotate)) }),start=rotate(original.start))
            val previous=CoverageEngine.planPreviousAutomatic(input) {}.selected
            val current=RoutePlanning.calculate(input).selected
            assertTrue(current.diagnostics.toString(),current.valid)
            assertTrue(current.metrics!!.durationSeconds<=previous.metrics!!.durationSeconds*1.02)
            assertTrue(current.metrics.uTurns<=previous.metrics.uTurns+1)
            assertTrue(current.metrics.coveredAreaM2>=previous.metrics.coveredAreaM2-1e-6)
        }
    }
    private fun row(y: Double,x: Double = 0.0) = listOf(Point(x,y),Point(x+10,y))
    private fun schedule(p: Preview,start: Point = Point(0.0,0.0),safe: (List<Point>) -> Boolean = { true },
        link: (List<Point>?,List<Point>) -> Double = { a,b -> (a?.last() ?: start).distance(b.first()) }) =
        LocalMowingOrder.schedule(p,start,false,true,link,safe)
    @Test fun completesTheCurrentCellBeforeVisitingAnotherOneAndPreservesEveryWholeRun() {
        val a=row(0.0);val b=row(1.0);val other=row(0.2,10.0)
        val p=Preview(listOf(a,other,b),30.0,0,0,runCellIds=listOf(1,2,1))
        val result=schedule(p).cuttingPaths
        assertEquals(a,result[0]);assertEquals(b.reversed(),result[1]);assertEquals(other,result[2])
        assertEquals(p.runs.map { it.toSet() }.toSet(),result.map { it.toSet() }.toSet())
        assertEquals(p.runs.size,result.size)
    }
    @Test fun aNearbyNoGoNeverSplitsOrInterruptsTheStartedCell() {
        val a=row(0.0);val b=row(1.0)
        val loop=listOf(Point(4.0,0.2),Point(5.0,0.2),Point(5.0,0.4),Point(4.0,0.2))
        val p=Preview(listOf(a,b),23.0,0,0,borders=listOf(loop),noGoLoops=1,
            borderKinds=listOf(true),runCellIds=listOf(1,1))
        val result=schedule(p).cuttingPaths
        assertEquals(a,result[0]);assertEquals(b.reversed(),result[1]);assertEquals(3,result.size)
        assertTrue(result[2].containsAll(loop));assertEquals(result[2].first(),result[2].last())
    }
    @Test fun checkerboardFinishesOneOrientationBeforeStartingTheOther() {
        // Horizontal passes (cells 0 and 1) and a vertical one (cell 90 × 100,000): the vertical one is the nearest.
        val h1=row(0.0);val h2=row(10.0)
        val v=listOf(Point(10.5,0.0),Point(10.5,10.0))
        val p=Preview(listOf(h1,v,h2),30.0,0,0,mode=CoverageMode.CHECKERBOARD,runCellIds=listOf(0,9000000,1))
        assertEquals(listOf(h1,h2.reversed(),v.reversed()),schedule(p).cuttingPaths)
        // Same geometry in simple zigzag: nearest link, the vertical one comes between the two horizontal ones.
        assertEquals(v,schedule(p.copy(mode=CoverageMode.LINES)).cuttingPaths[1])
    }
    @Test fun checkerboardStartsWithTheNearestOrientation() {
        // Vertical pass (2nd orientation) closest to the start: it begins, its direction is finished first.
        val h1=row(5.0);val h2=row(10.0)
        val v=listOf(Point(0.0,0.0),Point(0.0,3.0))
        val p=Preview(listOf(h1,h2,v),23.0,0,0,mode=CoverageMode.CHECKERBOARD,runCellIds=listOf(0,1,9000000))
        val order=schedule(p).cuttingPaths
        assertEquals(v,order.first())
        assertEquals(setOf(h1.toSet(),h2.toSet()),order.drop(1).map { it.toSet() }.toSet())
    }
    @Test fun aSweepBarMowsTheSideLeftBehindBeforeAdvancing() {
        // Cell A (y 0–1) and B (y 0–1, to the right) at the same level, C (y 2–3) just above A.
        val a=listOf(row(0.0),row(1.0));val c=listOf(row(2.0),row(3.0));val b=listOf(row(0.0,13.0),row(1.0,13.0))
        val p=Preview(a+c+b,60.0,0,0,runCellIds=listOf(1,1,2,2,3,3))
        fun cells(order: List<List<Point>>)=order.map { path -> p.runCellIds[p.runs.indexOfFirst { it==path || it.reversed()==path }] }
        val start=Point(0.0,0.0)
        val greedy=LocalMowingOrder.schedule(p,start,false,true,{ x,y -> (x?.last() ?: start).distance(y.first()) },{ true })
        assertEquals(listOf(1,1,2,2,3,3),cells(greedy.cuttingPaths))
        val bar=LocalMowingOrder.schedule(p,start,false,true,{ x,y -> (x?.last() ?: start).distance(y.first()) },{ true },
            sweepBar=LocalMowingOrder.SweepBar(90.0,1,-1.0))
        assertEquals(listOf(1,1,3,3,2,2),cells(bar.cuttingPaths))
        assertEquals(p.runs.map { it.toSet() }.toSet(),bar.cuttingPaths.map { it.toSet() }.toSet())
    }
    @Test fun aBarWithoutSafeLinkFallsBackToTheRemainingBlocks() {
        val a=listOf(row(0.0),row(1.0));val c=listOf(row(2.0),row(3.0));val b=listOf(row(0.0,13.0),row(1.0,13.0))
        val p=Preview(a+c+b,60.0,0,0,runCellIds=listOf(1,1,2,2,3,3))
        val start=Point(0.0,0.0)
        // Cell B unreachable from A: the bar does not block the mowing, C comes first.
        val result=LocalMowingOrder.schedule(p,start,false,true,{ x,y ->
            if(x!=null && x.last().x<11 && x.last().y<1.5 && y.first().x>11) Double.POSITIVE_INFINITY else (x?.last() ?: start).distance(y.first()) },{ true },
            sweepBar=LocalMowingOrder.SweepBar(90.0,1,-1.0))
        assertEquals(6,result.cuttingPaths.size)
        assertTrue(result.cuttingPaths[2].all { it.y>=2.0 })
    }
    @Test fun backtracksCountCellsStartedWhileAnotherOneWasLeftBehind() {
        val bottom=row(0.0);val middle=row(5.0);val top=row(10.0)
        fun scheduled(vararg order: List<Point>)=Preview(listOf(bottom,middle,top),30.0,0,0,runCellIds=listOf(1,2,3),
            scheduledPaths=order.toList())
        assertEquals(0,LocalMowingOrder.backtracks(scheduled(bottom,middle,top)))
        assertEquals(0,LocalMowingOrder.backtracks(scheduled(top,middle.reversed(),bottom)))
        assertEquals(1,LocalMowingOrder.backtracks(scheduled(bottom,top,middle)))
    }
    @Test fun startsAtAnEndpointWithoutPostponingHalfOfALongRun() {
        val p=Preview(listOf(row(0.0)),10.0,0,0)
        assertEquals(listOf(row(0.0)),schedule(p,Point(4.0,0.0)).cuttingPaths)
    }
    @Test fun geometricGenerationKeepsWholeRunsEvenWhenTheStartIsInTheirMiddle() {
        val zone=rectangle(w=20.0,h=10.0)
        fun generate(base: Point?) = CoveragePlanner.plan(zone,emptyList(),0.5,0.6,CoverageMode.LINES,true,
            basePoint=base,continuous=true)
        val original=generate(null)
        val path=original.runs.first();val a=path[0];val b=path[1]
        val middle=Point((a.x+b.x)/2,(a.y+b.y)/2)
        assertEquals(original.runs,generate(middle).runs)
        assertEquals(original.runCellIds,generate(middle).runCellIds)
    }
    @Test fun blockedNearestEndpointsDoNotCreateAShortcutAndFallbackSearchRemainsDeterministic() {
        val p=Preview((0..4).map { row(it.toDouble()) },50.0,0,0)
        val result=schedule(p,link={ a,b -> if(a==null && b.first().y<2) Double.POSITIVE_INFINITY
            else (a?.last() ?: Point(0.0,0.0)).distance(b.first()) })
        assertEquals(2.0,result.cuttingPaths.first().first().y,0.0)
        assertEquals(5,result.cuttingPaths.size)
        assertThrows(IllegalArgumentException::class.java) { schedule(p,safe={ false }) }
    }
    @Test fun cellMetadataSurvivesZoneCombinationWithoutCollisions() {
        val p=Preview(listOf(row(0.0),row(1.0)),20.0,0,0,runCellIds=listOf(5,5))
        val zones=listOf(MapShape(1,0,rectangle()),MapShape(2,0,rectangle(12.0,0.0)))
        assertEquals(listOf(0,0,1,1),MultiZonePlanner.combine(zones,listOf(p,p),CoverageMode.LINES).runCellIds)
    }
}
