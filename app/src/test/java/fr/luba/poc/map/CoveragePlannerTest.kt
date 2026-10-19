package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class CoveragePlannerTest {
    private val zone=listOf(Point(0.0,0.0),Point(16.0,0.0),Point(16.0,11.0),Point(0.0,11.0))
    private val hole=listOf(Point(6.0,4.0),Point(9.0,4.0),Point(9.0,7.0),Point(6.0,7.0))
    @Test fun recordedBoundaryFollowsTheTaughtEdgeButTurnsInside() {
        val result = Geometry.plan(zone, listOf(hole), recordedBoundary = true)
        val outer = result.borders.take(result.outerLoops)
        assertTrue(outer.isNotEmpty())
        assertTrue(outer.flatten().all { Geometry.inside(it, zone) })
        // Recorded boundary = side of the chassis: loop at half a width plus the margin (8 cm) from the boundary, never less.
        val half = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM
        fun toZone(p: Point) = zone.indices.minOf { i -> Geometry.distance(p, zone[i], zone[(i+1)%zone.size]) }
        assertTrue(outer.flatten().any { it.x < half + 0.08 + 0.02 })
        assertTrue(outer.flatten().all { toZone(it) >= half + 0.08 - 1e-6 })
        assertTrue(outer.flatten().none { it.x < half + 0.1 && it.y < half + 0.1 })
        val physical = RecordedBoundary.physicalArea(zone, fr.luba.poc.obstacles.RobotEnvelope.LUBA2)
        val envelope = TrajectoryEnvelope(physical)
        outer.forEach { path ->
            val heading = Math.toDegrees(kotlin.math.atan2(path[1].y-path[0].y,path[1].x-path[0].x))
            assertNull(envelope.pathCollision(path, finalHeading = heading))
        }
        val routes = fr.luba.poc.simulation.TrajectoryPlanner(RobotMap(listOf(MapShape(1,0,zone),MapShape(2,1,hole)),true),
            0.3065, listOf(hole), recordedBoundary = true)
        result.borders.forEach { path -> routes.requirePath(path) }
        fr.luba.poc.simulation.MowingSimulation(routes, Point(1.0,1.0), result,
            RobotMap(listOf(MapShape(1,0,zone),MapShape(2,1,hole)),true), fr.luba.poc.simulation.SimConfig())
        val noGo = result.borders.drop(result.outerLoops)
        assertTrue(noGo.isNotEmpty())
        assertTrue(noGo.flatten().none { Geometry.inside(it,hole) })
        fun toHole(p: Point) = hole.indices.minOf { i -> Geometry.distance(p,hole[i],hole[(i+1)%hole.size]) }
        // Chassis outside the no-go zone: loop at half a width plus the outer loop margin (8 cm by default here).
        val loop = RecordedBoundary.noGoLoopOffsetM(fr.luba.poc.obstacles.RobotEnvelope.LUBA2, marginM = CoveragePlanner.RECORDED_OUTER_OFFSET_M)
        assertTrue(noGo.flatten().any { p -> toHole(p) < loop + 0.02 })
        assertTrue(noGo.flatten().all { p -> toHole(p) >= loop - 0.01 })
    }
    @Test fun recordedConcaveBoundaryKeepsCentersAndManeuversInKnownTerrain() {
        val concave = listOf(Point(0.0,0.0),Point(8.0,0.0),Point(8.0,3.0),Point(3.0,3.0),Point(3.0,8.0),Point(0.0,8.0))
        val result = Geometry.plan(concave, emptyList(), recordedBoundary = true)
        assertTrue(result.borders.isNotEmpty())
        val envelope = TrajectoryEnvelope(RecordedBoundary.physicalArea(concave, fr.luba.poc.obstacles.RobotEnvelope.LUBA2))
        result.borders.forEach { path ->
            assertTrue(path.all { Geometry.inside(it,concave) })
            val heading = Math.toDegrees(kotlin.math.atan2(path[1].y-path[0].y,path[1].x-path[0].x))
            assertNull(envelope.pathCollision(path, finalHeading = heading))
        }
    }
    @Test fun irregularRecordedNoGoReservesSpaceForChassisPivots() {
        val zone = listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0))
        val hole = listOf(Point(4.0,4.0),Point(6.0,4.0),Point(6.0,6.0),
            Point(5.8,5.5),Point(5.5,6.1),Point(4.0,6.0))
        val result = Geometry.plan(zone,listOf(hole),spacing=0.25,recordedBoundary=true)
        val robot = fr.luba.poc.obstacles.RobotEnvelope.LUBA2
        val envelope = TrajectoryEnvelope(RecordedBoundary.physicalArea(zone,robot)
            .difference(RecordedBoundary.forbiddenArea(hole,robot)))
        assertTrue(result.noGoLoops > 0)
        result.borders.forEach { path ->
            val heading = Math.toDegrees(kotlin.math.atan2(path[1].y-path[0].y,path[1].x-path[0].x))
            assertNull(envelope.pathCollision(path,finalHeading=heading))
            assertTrue(path.all { Geometry.inside(it,zone) && !Geometry.inside(it,hole) })
        }
    }
    @Test fun roundedBoundaryDoesNotRequireAFullTurnAtItsEntry() {
        val circle = (0 until 64).map { i ->
            val angle = i * Math.PI * 2 / 64
            Point(6.0*kotlin.math.cos(angle),6.0*kotlin.math.sin(angle))
        }
        val result = Geometry.plan(circle, emptyList(), recordedBoundary = true)
        assertTrue(result.borders.isNotEmpty())
        val envelope = TrajectoryEnvelope(RecordedBoundary.physicalArea(circle,fr.luba.poc.obstacles.RobotEnvelope.LUBA2))
        val path = result.borders.first()
        // Loop at half a width plus 8 cm from the boundary (side of the chassis): the entry stays valid without requiring a full pivot.
        val heading = Math.toDegrees(kotlin.math.atan2(path[1].y-path[0].y,path[1].x-path[0].x))
        assertNull(envelope.pathCollision(path,finalHeading=heading))
        assertTrue(path.all { Geometry.inside(it,circle) })
    }
    @Test fun selectedSurfaceUnionsZonesAndClipsExclusions() {
        fun square(id: Long, type: Int, x: Double, y: Double, size: Double) = MapShape(id, type,
            listOf(Point(x,y), Point(x+size,y), Point(x+size,y+size), Point(x,y+size)))
        val map = RobotMap(listOf(square(1,0,0.0,0.0,10.0), square(2,0,5.0,0.0,10.0),
            square(3,1,0.0,0.0,2.0), square(4,1,40.0,40.0,2.0), square(5,0,40.0,0.0,10.0)))
        assertEquals(146.0, CoveragePlanner.selectedArea(map, setOf(1,2)), 1e-6)
        assertEquals(96.0, CoveragePlanner.selectedArea(map, setOf(1)), 1e-6)
        assertEquals(0.0, CoveragePlanner.selectedArea(map, emptySet()), 1e-6)
    }
    @Test fun nativeRowSpacingKeepsTheSameBoundaryClearance() {
        val small = listOf(Point(0.0,0.0), Point(5.0,0.0), Point(5.0,4.0), Point(0.0,4.0))
        listOf(0.05, 0.25, 0.35).forEach { spacing ->
            val result = Geometry.plan(small, emptyList(), spacing = spacing, margin = 0.6)
            assertTrue(result.runs.isNotEmpty())
            assertTrue(result.runs.all { run -> run.zipWithNext().all { (a,b) -> Geometry.safe(a,b,small,emptyList(),0.6) } })
        }
        assertThrows(IllegalArgumentException::class.java) { Geometry.plan(small, emptyList(), spacing = 0.04) }
    }
    @Test fun bordersIncludeOuterAndNoGoAndEverySegmentRespectsMargin() {
        val result=Geometry.plan(zone,listOf(hole))
        assertEquals(1,result.outerLoops);assertEquals(1,result.noGoLoops)
        assertTrue(result.borders.all { it.first()==it.last() })
        assertTrue(result.runs.all { run -> run.zipWithNext().all { (a,b)->Geometry.safe(a,b,zone,listOf(hole),0.6) } })
    }
    @Test fun bordersAreKeptOutOfTheBluePassesAndStillCountedInMeters() {
        val result=Geometry.plan(zone,listOf(hole))
        fun length(runs:List<List<Point>>)=runs.sumOf { r -> r.zipWithNext().sumOf { (a,b)->a.distance(b) } }
        // The meters do include the perimeter loop...
        assertEquals(result.meters,length(result.runs)+length(result.borders),1e-6)
        // ...but no blue pass reproduces a closed border: the display stays distinct.
        assertTrue(result.borders.isNotEmpty())
        assertTrue(result.runs.none { it.first()==it.last() })
        assertTrue(result.runs.none { r -> result.borders.any { b -> b==r } })
    }
    @Test fun everyPassIsExposedIndividuallyAndStaysInsideTheSafeZone() {
        val result=Geometry.plan(zone,listOf(hole),mode=CoverageMode.CHECKERBOARD)
        // One pass per drawn segment: required to accumulate opacity at overlaps.
        assertTrue(result.passes.isNotEmpty())
        assertTrue(result.passes.all { it.size>=2 })
        assertTrue(result.passes.all { run -> run.zipWithNext().all { (a,b)->Geometry.safe(a,b,zone,listOf(hole),0.6) } })
        // The passes do not contain the borders: those stay drawn apart.
        assertTrue(result.passes.none { it.first()==it.last() })
    }
    @Test fun checkerboardHasTwoPerpendicularSweepsAndLongerCoverage() {
        val lines=Geometry.plan(zone,emptyList(),borders=false)
        val grid=Geometry.plan(zone,emptyList(),mode=CoverageMode.CHECKERBOARD,borders=false)
        assertTrue(grid.meters>lines.meters*1.7)
        val directions=grid.runs.flatMap { it.zipWithNext() }.filter { (a,b)->a.distance(b)>3 }
            .map { (a,b)-> ((Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))+360)%180).toInt() }.toSet()
        assertTrue(directions.any { a->directions.any { b->kotlin.math.abs(a-b) in 89..91 } })
        assertTrue(grid.borders.isEmpty())
    }
    @Test fun obstacleSplittingAreaNeverGetsUnsafeConnector() {
        val divider=listOf(Point(7.0,-1.0),Point(9.0,-1.0),Point(9.0,12.0),Point(7.0,12.0))
        val result=Geometry.plan(zone,listOf(divider),mode=CoverageMode.CHECKERBOARD)
        assertTrue(result.runs.size>=2)
        assertTrue(result.runs.all { run->run.zipWithNext().all { (a,b)->Geometry.safe(a,b,zone,listOf(divider),0.6) } })
    }
    @Test(expected=IllegalArgumentException::class) fun crossedContourIsRejected() {
        Geometry.plan(listOf(Point(0.0,0.0),Point(10.0,10.0),Point(0.0,10.0),Point(10.0,0.0)),emptyList())
    }
    @Test fun pinchAndRotateKeepCentroidAnchoredAndInverseSelectionCorrect() {
        val center=Point(200.0,200.0);val touch=Point(280.0,130.0)
        val old=MapCamera(1.5,27.0,Point(30.0,-20.0))
        val before=old.inverse(touch,center)
        val next=old.transform(touch,center,Point(0.0,0.0),2.0,83.0)
        assertTrue(before.distance(next.inverse(touch,center))<1e-9)
        val moved=next.transform(touch,center,Point(40.0,30.0),1.0,0.0)
        assertTrue(before.distance(moved.inverse(Point(touch.x+40,touch.y+30),center))<1e-9)
    }

    @Test fun borderAndNoGoPassCountsAreConfigurableAndStaySafe() {
        val triple=Geometry.plan(zone,listOf(hole),spacing=0.5,margin=0.6,borderPasses=3,noGoPasses=2)
        assertEquals(3,triple.outerLoops);assertEquals(2,triple.noGoLoops)
        assertTrue(triple.borders.all { it.first()==it.last() && Geometry.safe(it.first(),it.last(),zone,listOf(hole),0.6) })
        // Each extra ring must be further from the boundaries than the previous one.
        val outerRings=triple.borders.take(3)
        fun minGap(ring:List<Point>):Double {
            val limits=listOf(zone,hole);var m=Double.MAX_VALUE
            for(p in ring) for(poly in limits) for(i in poly.indices) m=minOf(m,Geometry.distance(p,poly[i],poly[(i+1)%poly.size]))
            return m
        }
        assertTrue(minGap(outerRings[0]) < minGap(outerRings[1]))
        assertTrue(minGap(outerRings[1]) < minGap(outerRings[2]))
    }
    @Test fun coverageReachesSafePocketBehindNoGoNearOuterBorder() {
        val field=listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0))
        val exclusion=listOf(Point(4.0,0.7),Point(7.0,0.7),Point(7.0,3.0),Point(4.0,3.0))
        val plan=Geometry.plan(field,listOf(exclusion),spacing=0.25,recordedBoundary=true)
        val behind=Point(5.5,0.35)
        // 70 cm between the boundary and the no-go zone: the chassis (51 cm) fits, center 34 to 39 cm from the boundary.
        val ring=fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM+CoveragePlanner.RECORDED_OUTER_OFFSET_M
        assertTrue("The outer loop must run along the periphery behind the no-go zone",
            plan.borders.take(plan.outerLoops).flatMap { it.zipWithNext() }
                .any { (a,b) -> Geometry.distance(Point(5.5,ring),a,b)<0.05 })
        assertTrue("Free pocket behind the no-go zone not covered",plan.cuttingPaths.flatMap { it.zipWithNext() }
            .any { (a,b)->Geometry.distance(behind,a,b)<Geometry.CUT_WIDTH_M/2 })
        val noGoEdges=plan.borders.drop(plan.outerLoops).flatMap { it.zipWithNext() }
        assertTrue(noGoEdges.isNotEmpty())
        // The side of the no-go zone along the boundary is mowed by the outer loop (no zigzags possible in 70 cm).
        val passes=(plan.passes+plan.borders.take(plan.outerLoops)).flatMap { it.zipWithNext() }
        noGoEdges.filter { (a,b)->a.distance(b)>1.0 }.forEach { (a,b) ->
            val midpoint=Point((a.x+b.x)/2,(a.y+b.y)/2)
            val gap=passes.minOf { (c,d)->Geometry.distance(midpoint,c,d) }
            assertTrue("Gap between no-go loop and zigzags: $gap m",gap < Geometry.CUT_WIDTH_M)
        }
    }
    @Test fun sweepFinishesOneSideOfNoGoBeforeCrossingToTheOther() {
        val field=listOf(Point(0.0,0.0),Point(20.0,0.0),Point(20.0,12.0),Point(0.0,12.0))
        val exclusion=listOf(Point(8.0,3.0),Point(12.0,3.0),Point(12.0,9.0),Point(8.0,9.0))
        val plan=Geometry.plan(field,listOf(exclusion),spacing=0.25,recordedBoundary=true)
        val angle=Math.toRadians(plan.angle.toDouble())
        fun rotated(p:Point)=Point(p.x*kotlin.math.cos(angle)+p.y*kotlin.math.sin(angle),
            -p.x*kotlin.math.sin(angle)+p.y*kotlin.math.cos(angle))
        val center=rotated(Point(10.0,6.0))
        val sides=plan.passes.mapNotNull { pass ->
            val a=rotated(pass.first());val b=rotated(pass.last())
            if(kotlin.math.abs(a.y-center.y)>0.8) null else if((a.x+b.x)/2<center.x) -1 else 1
        }
        assertEquals(setOf(-1,1),sides.toSet())
        assertEquals("The sides must not alternate on every row",1,sides.zipWithNext().count { (a,b)->a!=b })
    }
    @Test fun mainCoverageOverlapsTheLastRecordedBorderForEachPassCount() {
        val field = listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0))
        val exclusion = listOf(Point(4.0,4.0),Point(7.0,4.0),Point(7.0,7.0),Point(4.0,7.0))
        for (count in listOf(1,2,4)) {
            val plan = Geometry.plan(field,listOf(exclusion),spacing=0.25,borderPasses=count,noGoPasses=count,recordedBoundary=true)
            val borders = plan.borders.flatMap { it.zipWithNext() }
            plan.passes.forEach { pass ->
                for (endpoint in listOf(pass.first(),pass.last())) {
                    val gap = borders.minOf { (a,b) -> Geometry.distance(endpoint,a,b) }
                    assertTrue("Unmowed band between coverage and borders ($count loops, $gap m)",gap <= Geometry.CUT_WIDTH_M-0.01)
                }
            }
        }
    }
    @Test fun borderPassesIncreaseMetersAndAreRejectedWhenInvalid() {
        val one=Geometry.plan(zone,listOf(hole))
        val two=Geometry.plan(zone,listOf(hole),borderPasses=2)
        assertTrue(two.meters>one.meters)
        assertThrows(IllegalArgumentException::class.java){Geometry.plan(zone,listOf(hole),borderPasses=-1)}
    }

    @Test fun progressPublishesImprovingTracesAndEndsOnTheResult() {
        val seen=mutableListOf<Preview>()
        val result=Geometry.plan(zone,listOf(hole),onProgress={ seen.add(it) })
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.runs.isNotEmpty() })
        assertEquals(result,seen.last())
        val costs=seen.map { it.meters+it.runs.size*10 }
        assertTrue(costs.zipWithNext().all { (a,b)->b<=a })
    }
}
