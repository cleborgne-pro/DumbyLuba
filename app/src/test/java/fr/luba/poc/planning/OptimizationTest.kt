package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.RobotRoute
import org.junit.Assert.*
import org.junit.Test

class OptimizationTest {
    @Test fun exactPartitionPreservesHolesConcavitiesAndRingOrder() {
        val concave=polygon(listOf(Point(0.0,0.0),Point(9.0,0.0),Point(9.0,3.0),Point(4.0,3.0),Point(4.0,8.0),Point(0.0,8.0)))
            .difference(polygon(rectangle(1.0,2.0,1.0,2.0)))
        for (domain in listOf(concave,concave.reverse(),polygon(rectangle()).difference(polygon(rectangle(3.0,2.0,2.0,2.0))))) {
            val cells=BoustrophedonDecomposer().cells(domain)
            assertTrue(cells.size>1)
            assertEquals(0.0,union(cells).symDifference(domain).area,1e-8)
            assertEquals(domain.area,cells.sumOf { it.area },1e-8)
            cells.forEach { assertTrue(it.isValid) }
        }
        assertThrows(PlanningBudgetExceeded::class.java) { BoustrophedonDecomposer(2).cells(concave) }
    }
    @Test fun roundedContoursUseCriticalEventsInsteadOfEveryVertex() {
        val domain=polygon(rectangle()).buffer(0.2,64)
        assertTrue(domain.coordinates.map { it.y }.distinct().size>4)
        val cells=BoustrophedonDecomposer(4).cells(domain)
        assertEquals(1,cells.size)
        assertEquals(0.0,union(cells).symDifference(domain).area,1e-8)
    }
    @Test fun dynamicProgrammingMatchesSmallExhaustiveOracle() {
        val terrain=PlanningTerrain(request())
        val evaluator=MissionEvaluator(terrain,TransitionPlanner(terrain))
        val paths=listOf(listOf(Point(2.0,2.0),Point(7.0,2.0)),listOf(Point(2.0,3.0),Point(7.0,3.0)),listOf(Point(2.0,4.0),Point(7.0,4.0)))
        val exact=(0 until 8).minOf { mask -> evaluator.travelCost(paths.mapIndexed { i,p -> if(mask and (1 shl i)==0) p else p.reversed() }) }
        val result=TaskSequencer(terrain,evaluator) {}.optimizeDirections(paths)
        assertEquals(exact,result.second,1e-7)
        assertEquals(exact,evaluator.travelCost(result.first),1e-7)
    }
    @Test fun variantsCannotWinByLosingCoverageAndAreDeterministic() {
        val r=request(holes=listOf(rectangle(4.0,2.0,2.0,2.0)),config=PlanningConfig(spacingM=0.35,minimumCoverage=0.5,candidateBudget=4,localSearchBudget=6))
        for (strategy in listOf(PlannerStrategy.OPTIMIZED,PlannerStrategy.EXPERIMENTAL_BCD)) {
            val result=CoverageEngine.plan(r,strategy)
            val baseline=result.candidates.first()
            assertTrue(result.candidates.toString(),baseline.valid)
            assertTrue(result.selected.valid)
            assertTrue(result.selected.score!!<=baseline.score!!+1e-6)
            assertTrue(result.selected.metrics!!.coveredAreaM2+1e-6>=baseline.metrics!!.coveredAreaM2)
            assertEquals(0,result.selected.metrics.geometricViolations)
            assertEquals(result.selected.planHash,CoverageEngine.plan(r,strategy).selected.planHash)
            assertThrows(IllegalArgumentException::class.java) { RobotRoute.requireExecutable(result.selected.preview!!) }
        }
    }
    @Test fun localOnlyFlagSurvivesCombiningZones() {
        val p=Preview(listOf(listOf(Point(1.0,1.0),Point(2.0,1.0))),1.0,0,0,localOnly=true)
        assertTrue(MultiZonePlanner.combine(listOf(MapShape(1,0,rectangle())),listOf(p),CoverageMode.LINES).localOnly)
    }
    @Test fun obliqueSubdivisionDoesNotInventRepeatCoverage() {
        val target=polygon(rectangle(-2.0,-2.0,10.0,10.0))
        val whole=listOf(MissionLeg(listOf(Point(0.0,0.0),Point(4.0,3.0)),true))
        val split=listOf(MissionLeg(listOf(Point(0.0,0.0),Point(1.0,0.75),Point(2.0,1.5),Point(4.0,3.0)),true))
        val a=MissionEvaluator.coverage(whole,target,0.2)
        val b=MissionEvaluator.coverage(split,target,0.2)
        assertEquals(a.covered,b.covered,1e-7)
        assertEquals(a.extra,b.extra,1e-7)
    }
    @Test fun obstacleRadiusAndChangedMapAreAppliedOnce() {
        val r=request().copy(obstacles=listOf(PlanningObstacle(Point(4.0,4.0),0.2)))
        val terrain=PlanningTerrain(r)
        assertFalse(terrain.physical.covers(disc(Point(4.1,4.0),0.01)))
        assertTrue(terrain.physical.covers(disc(Point(4.3,4.0),0.01)))
        val points=rectangle().toMutableList()
        val input=request().copy(map=RobotMap(listOf(MapShape(1,0,points)),true))
        val frozen=input.snapshot()
        points[1]=Point(12.0,0.0)
        assertNotEquals(input.hash,frozen.hash)
        assertEquals(Point(10.0,0.0),frozen.map.shapes.first().points[1])
    }
    @Test fun indexedMetricsMatchSequentialOracle() {
        val target=polygon(rectangle())
        val mission=listOf(MissionLeg(listOf(Point(1.0,1.0),Point(8.0,4.0),Point(1.0,4.0),Point(8.0,1.0),Point(1.0,1.0)),true),
            MissionLeg(listOf(Point(1.0,1.0),Point(1.0,4.0)),false),
            MissionLeg(listOf(Point(1.0,4.0),Point(8.0,4.0),Point(1.0,4.0)),true))
        val reference=MissionEvaluator.coverage(mission,target,0.2,false)
        val indexed=MissionEvaluator.coverage(mission,target,0.2,true)
        assertEquals(reference.covered,indexed.covered,1e-7)
        assertEquals(reference.repeated,indexed.repeated,1e-7)
        assertEquals(reference.extra,indexed.extra,1e-7)
    }
    @Test fun coordinateTranslationDoesNotChangeMetrics() {
        val r=request(config=PlanningConfig(spacingM=0.35,minimumCoverage=0.6,candidateBudget=1,localSearchBudget=0))
        val shifted=r.copy(map=r.map.copy(shapes=r.map.shapes.map { shape -> shape.copy(points=shape.points.map { Point(it.x+10000,it.y-10000) }) }),start=Point(r.start.x+10000,r.start.y-10000))
        val a=CoverageEngine.plan(r).selected; val b=CoverageEngine.plan(shifted).selected
        assertTrue(a.valid); assertTrue(b.valid)
        assertEquals(a.metrics!!.coveredAreaM2,b.metrics!!.coveredAreaM2,1e-6)
        assertEquals(a.metrics.distanceM,b.metrics.distanceM,1e-5)
    }
}
