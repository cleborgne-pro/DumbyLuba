package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.RobotRoute
import org.junit.Assert.*
import org.junit.Test

class NeighborhoodSequencerTest {
    @Test fun shiftedAndSubdividedLoopEntryStillLocksTheBorderClass() {
        val terrain=PlanningTerrain(request())
        val ring=rectangle(1.0,1.0,7.0,4.0).let { it+it.first() }
        val shifted=listOf(Point(4.0,1.0))+ring.drop(1)+Point(4.0,1.0)
        val classifier=TaskClassifier(terrain,Preview(emptyList(),0.0,0,0,borders=listOf(ring)))
        assertTrue(classifier.group(shifted).second)
        assertTrue(classifier.group(shifted.reversed()).second)
        assertFalse(classifier.group(listOf(Point(1.0,2.0),Point(8.0,2.0))).second)
    }
    private fun permutations(items: List<List<Point>>): List<List<List<Point>>> =
        if(items.isEmpty()) listOf(emptyList()) else items.indices.flatMap { i ->
            permutations(items.filterIndexed { j,_ -> j!=i }).map { listOf(items[i])+it }
        }

    @Test fun subsetOracleMatchesEveryPermutationAndSenseWithDirectedBoundaryCosts() {
        for (rate in listOf(15.0,90.0)) {
            val r=request(config=PlanningConfig(spacingM=0.35,turnDegreesPerSecond=rate,stopSeconds=1.2))
                .copy(startHeading=75.0)
            val terrain=PlanningTerrain(r)
            val evaluator=MissionEvaluator(terrain,TransitionPlanner(terrain))
            val paths=listOf(listOf(Point(2.0,2.0),Point(8.0,2.0)),
                listOf(Point(2.0,4.0),Point(8.0,4.0)),listOf(Point(2.0,3.0),Point(7.0,3.0)))
            val oracle=permutations(paths).flatMap { order -> (0 until 8).map { mask ->
                order.mapIndexed { i,path -> if(mask and (1 shl i)==0) path else path.reversed() }
            } }.minOf { evaluator.travelCost(it) }
            val solver=NeighborhoodSequencer(terrain,evaluator) {}
            val result=solver.exact(paths)
            assertEquals(oracle,result.second,1e-7)
            assertEquals(oracle,evaluator.travelCost(result.first),1e-7)
            // A fixed outer link is part of the sub-problem, not an implicit base.
            val before=listOf(Point(1.0,1.0),Point(1.0,2.0))
            val after=listOf(Point(8.0,5.0),Point(7.0,5.0))
            val bounded=solver.exact(paths,before,after)
            val boundedOracle=permutations(paths).flatMap { order -> (0 until 8).map { mask ->
                order.mapIndexed { i,path -> if(mask and (1 shl i)==0) path else path.reversed() }
            } }.minOf { order -> order.sumOf { evaluator.pathCost(it) }+
                (listOf(before)+order+listOf(after)).zipWithNext().sumOf { (a,b) -> evaluator.linkCost(a,b) } }
            assertEquals(boundedOracle,bounded.second,1e-7)
        }
    }

    @Test fun impossibleTasksAndCancellationCannotProduceAFalseOptimum() {
        val terrain=PlanningTerrain(request())
        val evaluator=MissionEvaluator(terrain,TransitionPlanner(terrain))
        val bad=listOf(listOf(Point(0.0,0.0),Point(2.0,0.0)))
        assertFalse(NeighborhoodSequencer(terrain,evaluator) {}.exact(bad).second.isFinite())
        val paths=(0..4).map { y -> listOf(Point(2.0,1.0+y),Point(7.0,1.0+y)) }
        assertThrows(InterruptedException::class.java) {
            NeighborhoodSequencer(terrain,evaluator) { throw InterruptedException("annulé") }.exact(paths)
        }
    }

    @Test fun largeNeighborhoodPreservesEveryTaskAndZoneBoundary() {
        val r=request().let { it.copy(map=it.map.copy(shapes=listOf(MapShape(1,0,rectangle(w=5.0)),
            MapShape(8,0,rectangle(5.0,0.0,5.0,7.0)))),zoneIds=listOf(1,8)) }
        val terrain=PlanningTerrain(r)
        val evaluator=MissionEvaluator(terrain,TransitionPlanner(terrain))
        val paths=listOf(listOf(Point(1.0,2.0),Point(4.0,2.0)),listOf(Point(1.0,4.0),Point(4.0,4.0)),
            listOf(Point(1.0,3.0),Point(4.0,3.0)),listOf(Point(6.0,2.0),Point(9.0,2.0)),
            listOf(Point(6.0,4.0),Point(9.0,4.0)),listOf(Point(6.0,3.0),Point(9.0,3.0)))
        val preview=Preview(paths,paths.sumOf(::length),0,0,zoneIds=listOf(1,8))
        val result=NeighborhoodSequencer(terrain,evaluator) {}.optimize(preview)
        fun canonical(p: List<Point>) = minOf(p.toString(),p.reversed().toString())
        assertEquals(paths.map(::canonical).sorted(),result.cuttingPaths.map(::canonical).sorted())
        assertTrue(result.cuttingPaths.take(3).flatten().all { it.x<5 })
        assertTrue(result.cuttingPaths.drop(3).flatten().all { it.x>5 })
        assertTrue(evaluator.travelCost(result.cuttingPaths)<=evaluator.travelCost(paths)+1e-7)
        assertThrows(IllegalArgumentException::class.java) { RobotRoute.requireExecutable(result) }
    }

    @Test fun advancedRefinementsPreservePhaseTwoAcrossMotionProfiles() {
        for (rate in listOf(20.0,90.0)) {
            val r=request(holes=listOf(rectangle(4.0,2.0,2.0,2.0)),config=PlanningConfig(spacingM=0.35,
                minimumCoverage=0.5,candidateBudget=3,localSearchBudget=4,turnDegreesPerSecond=rate,stopSeconds=0.5))
            val reference=CoverageEngine.plan(r,PlannerStrategy.OPTIMIZED).selected
            for(strategy in listOf(PlannerStrategy.EXPERIMENTAL_JOINT,PlannerStrategy.EXPERIMENTAL_LNS,PlannerStrategy.ADVANCED)) {
                val result=CoverageEngine.plan(r,strategy)
                assertTrue(CoverageEngine.preservesReference(result.selected,reference))
                assertEquals(0,result.selected.metrics!!.geometricViolations)
                assertTrue(result.selected.preview!!.localOnly)
                assertEquals(result.selected.planHash,CoverageEngine.plan(r,strategy).selected.planHash)
            }
        }
    }

    @Test fun coverageUnionAgreesWithIndependentRasterAtTwoResolutions() {
        val target=polygon(rectangle(w=5.0,h=4.0)).difference(polygon(rectangle(2.0,1.0,0.2,2.0)))
        val paths=listOf(listOf(Point(0.5,0.5),Point(4.5,0.5),Point(4.5,3.5)),
            listOf(Point(0.5,1.0),Point(4.0,3.0)))
        val geometric=MissionEvaluator.coverage(paths.map { MissionLeg(it,true) },target,0.2).covered
        fun raster(step: Double): Double {
            var count=0
            for (x in 0 until (5/step).toInt()) for(y in 0 until (4/step).toInt()) {
                val point=Point((x+0.5)*step,(y+0.5)*step)
                val inHole=point.x in 2.0..2.2 && point.y in 1.0..3.0
                if(!inHole && paths.any { path -> path.zipWithNext().any { (a,b) -> Geometry.distance(point,a,b)<=0.2 } }) count++
            }
            return count*step*step
        }
        // The discretisation error is roughly bounded by the perimeter tube
        // of the three capsules and the exclusion: 40 * sqrt(2) * step + faceting.
        for(step in listOf(0.05,0.025)) assertEquals(geometric,raster(step),40*kotlin.math.sqrt(2.0)*step+0.002)
        assertEquals(geometric,raster(0.025),0.03)
    }
}
