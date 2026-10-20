package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

internal fun rectangle(x: Double=0.0,y: Double=0.0,w: Double=10.0,h: Double=7.0) =
    listOf(Point(x,y),Point(x+w,y),Point(x+w,y+h),Point(x,y+h))
internal fun request(points: List<Point> = rectangle(),holes: List<List<Point>> = emptyList(),
    config: PlanningConfig = PlanningConfig(spacingM=0.35,minimumCoverage=0.65)) =
    PlanningRequest(RobotMap(listOf(MapShape(1,0,points))+holes.mapIndexed { i,p -> MapShape(i+2L,1,p) },true,"test"),
        listOf(1),Point(1.0,1.0),config=config)
class PlanningEngineTest {
    @Test fun visionZonesRefuseThePlanningInsteadOfBeingIgnored() {
        val base=request()
        val r=base.copy(map=base.map.copy(shapes=base.map.shapes+MapShape(9,25,rectangle(4.0,3.0,1.0,1.0))))
        val error=assertThrows(IllegalArgumentException::class.java) { PlanningTerrain(r) }
        assertTrue(error.message, error.message!!.contains("vision"))
    }
    @Test fun baselineIsPreservedAndMetricsIncludeReturn() {
        val r=request()
        val result=CoverageEngine.plan(r)
        val selected=result.selected
        assertTrue(selected.diagnostics.toString(),selected.valid)
        val old=MultiZonePlanner.plan(r.map.shapes,emptyList(),r.config.spacingM,r.config.marginM,CoverageMode.LINES,true,
            basePoint=r.start,bordersFirst=true,nearbyNoGo=true)
        assertEquals(old.cuttingPaths,selected.preview!!.cuttingPaths)
        assertTrue(selected.preview.localOnly)
        assertTrue(selected.metrics!!.distanceM >= old.meters)
        assertEquals(r.start,selected.mission.first().path.first())
        assertEquals(r.start,selected.mission.last().path.last())
        assertEquals(70.0,selected.metrics.coveredAreaM2+selected.metrics.uncoveredAreaM2,1e-6)
        assertEquals(0,selected.metrics.geometricViolations)
    }
    @Test fun subdivisionDoesNotCreateExtraVisits() {
        val target=polygon(rectangle(-2.0,-2.0,10.0,10.0))
        val a=Point(0.0,0.0); val b=Point(4.0,0.0)
        fun measure(p: List<Point>) = MissionEvaluator.coverage(listOf(MissionLeg(p,true)),target,0.2)
        val one=measure(listOf(a,b)); val split=measure(listOf(a,Point(1.0,0.0),Point(2.0,0.0),b))
        assertEquals(one.covered,split.covered,1e-8); assertEquals(0.0,split.extra,1e-8)
        val retrace=measure(listOf(a,b,a,b))
        assertTrue(retrace.extra>retrace.repeated)
        assertEquals(one.covered,retrace.covered,1e-8)
    }
    @Test fun lowCoverageNeverHasAScoreEvenWithZeroWeights() {
        val result=CoverageEngine.plan(request(config=PlanningConfig(minimumCoverage=1.0,weights=ScoreWeights(0.0,0.0,0.0,0.0,0.0))))
        assertEquals(PlanStatus.PARTIAL_COVERAGE,result.selected.status)
        assertNull(result.selected.score)
    }
    @Test fun malformedMicroAndUnsupportedKinematicsAreExplicitFailures() {
        listOf(listOf(Point(0.0,0.0),Point(2.0,2.0),Point(0.0,2.0),Point(2.0,0.0)),
            rectangle(w=1e-7,h=1e-7),listOf(Point(Double.NaN,0.0),Point(1.0,0.0),Point(1.0,1.0))).forEach {
            assertFalse(CoverageEngine.plan(request(it)).selected.valid)
        }
        val r=request()
        assertEquals(PlanStatus.INVALID_INPUT,CoverageEngine.plan(r.copy(robot=r.robot.copy(minimumTurnRadiusM=1.0))).selected.status)
        assertFalse(CoverageEngine.plan(request(rectangle(w=0.7,h=0.7))).selected.valid)
        assertFalse(CoverageEngine.plan(r.copy(config=r.config.copy(allowHypotheses=false))).selected.valid)
    }
    @Test fun unknownCorridorUsesTheNavigationConventionOnly() {
        val r=request()
        val map=r.map.copy(shapes=r.map.shapes+MapShape(9,2,listOf(Point(5.0,3.0),Point(15.0,3.0))))
        // Same corridor as the simulation and control navigation: axis ± (half-width + 0.45 m).
        val unknown=PlanningTerrain(r.copy(map=map))
        val half=r.robot.halfWidthM+0.05+0.4
        assertTrue(unknown.physical.covers(disc(Point(12.0,3.0),0.1)))
        assertTrue(unknown.physical.covers(disc(Point(12.0,3.0+half-0.15),0.1)))
        assertFalse(unknown.physical.covers(disc(Point(12.0,3.0+half+0.15),0.1)))
        assertTrue(unknown.assumptions.any { "largeur conventionnelle" in it })
        // A passage reduced to a point adds no space.
        val point=PlanningTerrain(r.copy(map=r.map.copy(shapes=r.map.shapes+MapShape(9,2,listOf(Point(12.0,3.0))))))
        assertFalse(point.physical.covers(disc(Point(12.0,3.0),0.1)))
        val known=PlanningTerrain(r.copy(map=map,corridorWidthsM=mapOf(9L to 1.0)))
        assertTrue(known.physical.covers(disc(Point(12.0,3.0),0.1)))
        assertFalse(known.physical.covers(disc(Point(12.0,3.0+0.5+0.15),0.1)))
    }
    @Test fun snapshotsAndPlansAreReproducible() {
        val r=request(); val a=CoverageEngine.plan(r); val b=CoverageEngine.plan(r)
        assertEquals(a.snapshotHash,b.snapshotHash); assertEquals(a.selected.planHash,b.selected.planHash)
        assertNotEquals(a.snapshotHash,CoverageEngine.plan(r.copy(config=r.config.copy(marginM=0.7))).snapshotHash)
    }
    @Test fun slopeDataRequiresVerifiedLimitAndDoesNotShrinkTarget() {
        val r=request().copy(slopes=listOf(SlopeRegion(rectangle(4.0,0.0,2.0,7.0),40.0)))
        assertFalse(CoverageEngine.plan(r).selected.valid)
        val terrain=PlanningTerrain(r.copy(config=r.config.copy(verifiedMaxSlopeDegrees=30.0)))
        assertEquals(70.0,terrain.target.area,1e-8)
        assertFalse(terrain.physical.covers(disc(Point(5.0,3.0),0.1)))
    }
}
