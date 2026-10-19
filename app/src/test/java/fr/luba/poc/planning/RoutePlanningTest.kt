package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.simulation.*
import org.junit.Assert.*
import org.junit.Test

class RoutePlanningTest {
    @Test fun fossePreservesCoverageWithLocalFallbackWithoutGlobalRepair() {
        // Default app margin (5 cm), applied since 0.40.1 to no-go loops as to the perimeter: at 8 cm,
        // the local fallback covers 0.12 m² less than the reference (heuristic, 0.05 %).
        val input=PlannerBenchmarkTest().referenceCases("Fosse").getValue("Fosse").let {
            it.copy(config=it.config.copy(outerOffsetM=fr.luba.poc.map.OuterOffsetSetting.DEFAULT_M)) }
        // Since 0.41.0 (pass ends half a cut from the loop, corners included), the orientation search of the
        // reference finds better than its first candidate (146°, 259.32 m² versus 259.13); the local fallback covers
        // 259.27 m². Tolerance of 0.1 % of the target on the mowed surface (heuristic gap between the two searches).
        val reference=CoverageEngine.plan(input,PlannerStrategy.OPTIMIZED)
        assertTrue(reference.selected.valid)
        val actual=RoutePlanning.calculate(input)
        assertTrue(actual.selected.valid)
        val metrics=actual.selected.metrics!!
        assertTrue(metrics.coverage>=input.config.minimumCoverage)
        val referenceMetrics=reference.selected.metrics!!
        assertTrue("Surface ${metrics.coveredAreaM2} versus ${referenceMetrics.coveredAreaM2}",
            metrics.coveredAreaM2+0.001*referenceMetrics.targetAreaM2>=referenceMetrics.coveredAreaM2)
        assertTrue("Surface ${metrics.coveredAreaM2} below the reference's first candidate",
            metrics.coveredAreaM2+1e-6>=reference.candidates.first().metrics!!.coveredAreaM2)
        // Outline = chassis side: the first candidate of the Fosse already covers the target, the fallback is no longer triggered.
        if (actual.candidates.first().valid) assertEquals(1,actual.candidates.size)
        else {
            assertEquals(2,actual.candidates.size)
            assertEquals("continuous-reference",actual.selected.name)
            assertEquals(PlanStatus.PARTIAL_COVERAGE,actual.candidates.first().status)
        }
        assertTrue(actual.phaseNanos.containsKey("coverage_reference"))
        assertTrue(actual.phaseNanos.containsKey("local_order"))
        assertFalse(actual.phaseNanos.containsKey("neighborhood_search"))
    }

    @Test fun localStationAccessIsSharedAndBelongsToTheRequestSnapshot() {
        val input=request()
        val base=MapBase(Point(-1.0,3.5),"Enregistrée",entryHeadingDegrees=180.0)
        val access=mutableListOf(base.point,Point(3.0,3.5))
        val original=input.copy(start=base.point,navigation=PlanningNavigation(listOf(access),base))
        val frozen=original.snapshot()
        val snapshotHash=frozen.hash
        access.clear()
        assertEquals(snapshotHash,frozen.hash)
        assertNotEquals(frozen.hash,original.hash)
        val terrain=PlanningTerrain(frozen)
        val routes=RoutePlanning.navigation(frozen.map,0.31,emptyList(),frozen.navigation!!.accesses,base)
        val end=Point(3.0,3.5)
        assertEquals(routes.between(base.point,end,0.0,0.0),terrain.routes.between(base.point,end,0.0,0.0))
        assertTrue(terrain.physical.covers(disc(base.point,0.1)))
        assertFalse(PlanningTerrain(input).physical.covers(disc(base.point,0.1)))
        assertThrows(IllegalArgumentException::class.java) {
            PlanningTerrain(frozen.copy(config=frozen.config.copy(allowHypotheses=false)))
        }
    }

    @Test fun singleProductionEnginePublishesOnlyModelValidatedPlans() {
        val input=request(config=PlanningConfig(spacingM=0.8,minimumCoverage=0.4,candidateBudget=2,localSearchBudget=2))
        val actual=RoutePlanning.coverage(input)
        assertEquals(RoutePlanning.calculate(input).selected.planHash,actual.report!!.selected.planHash)
        assertEquals(1,actual.report.candidates.size)
        assertEquals("Automatique",actual.preview.plannerLabel)
        assertTrue(actual.report.selected.valid)
        assertFalse(actual.preview.localOnly)
        RobotRoute.requireExecutable(actual.preview)
        val research=RoutePlanning.researchCoverage(input)
        assertTrue(research.preview.localOnly)
        assertThrows(IllegalArgumentException::class.java) { RobotRoute.requireExecutable(research.preview) }
    }

    @Test fun referenceEntryPreservesItsPreviousGeometryAndObstacleMargin() {
        val input=request().copy(obstacles=listOf(PlanningObstacle(Point(5.0,3.0),0.2)))
        val actual=RoutePlanning.researchCoverage(input,strategy=PlannerStrategy.BASELINE)
        val c=input.config
        val expected=MultiZonePlanner.plan(input.map.shapes.filter { it.kind==ShapeKind.ZONE },emptyList(),
            c.spacingM,c.marginM,CoverageMode.LINES,c.borders,c.borderPasses,c.noGoPasses,
            obstacles=listOf(fr.luba.poc.obstacles.ObstacleDisc(5.0,3.0,0.2+input.robot.turnClearanceM)),
            basePoint=input.start,bordersFirst=c.bordersFirst,nearbyNoGo=c.nearbyNoGo)
        assertEquals(expected.cuttingPaths,actual.preview.cuttingPaths)
        assertNull(actual.report)
        assertFalse(actual.preview.localOnly)
    }

    @Test fun coverageSurveyAndCheckerboardReplayExactlyTheSameLocalMissionOnTheRobot() {
        val input=request(rectangle(w=12.0,h=12.0),config=PlanningConfig(spacingM=0.8,minimumCoverage=0.4))
        val routes=TrajectoryPlanner(input.map,0.31,emptyList())
        for(mode in listOf(CoverageMode.LINES,CoverageMode.CHECKERBOARD,CoverageMode.TOUR,CoverageMode.SURVEY_GRID)) {
            val plan=RoutePlanning.coverage(input,mode).preview
            val engine=MowingSimulation(routes,Point(2.0,2.0),plan,input.map,SimConfig())
            val mission=RoutePlanning.assemble(plan.cuttingPaths,routes::between)!!
            // Continuous blades in the zone (zigzag, checkerboard); survey: blades always stopped.
            val blades=if(mode.isSurvey) mission.map { it.cutting } else BladeContinuity.legs(mission,input.map.shapes.filter { it.kind==ShapeKind.ZONE && it.id in plan.zoneIds }.map { it.points })
            val assembled=mission.flatMapIndexed { i,leg -> leg.path.zipWithNext().filter { (a,b) -> a.distance(b)>1e-8 }
                    .map { (a,b) -> SimEdge(a,b,leg.cutting,bladesOn=blades[i]) } }
            assertEquals(mode.toString(),assembled,engine.edges)
            if(mode.isSurvey) assertTrue(engine.edges.none { !it.cutting && it.bladesOn })
            else assertTrue(mode.toString(),engine.edges.zipWithNext().none { (a,b) -> a.bladesOn && !b.bladesOn })
            val real=RobotRoute.edges(engine,Point(3.0,3.0),0.0)
            val offset=real.indexOf(engine.edges.first())
            assertEquals(engine.edges,real.subList(offset,offset+engine.edges.size))
            assertEquals(!mode.isSurvey,RobotRoute.cuttingAllowed(plan,true))
        }
    }

    @Test fun advancedEvaluationAndSimulationHaveIdenticalInternalTransitions() {
        val input=request(config=PlanningConfig(spacingM=0.8,minimumCoverage=0.4,candidateBudget=2,localSearchBudget=2))
        val plan=RoutePlanning.coverage(input)
        assertTrue(plan.report!!.selected.valid)
        val terrain=PlanningTerrain(input)
        val engine=MowingSimulation(terrain.routes,input.start,plan.preview,input.map,SimConfig())
        // The evaluation additionally includes the departure/return at the planning reference point.
        // The player separately adds the station-specific approach and reversing.
        val mission=plan.report.selected.mission.drop(1).dropLast(1)
        val blades=BladeContinuity.legs(mission,input.map.shapes.filter { it.kind==ShapeKind.ZONE && it.id in plan.preview.zoneIds }.map { it.points })
        val internal=mission.flatMapIndexed { i,leg -> leg.path.zipWithNext().filter { (a,b) -> a.distance(b)>1e-8 }
                .map { (a,b) -> SimEdge(a,b,leg.cutting,bladesOn=blades[i]) } }
        assertEquals(internal,engine.edges)
        val real=RobotRoute.edges(engine,input.start,0.0)
        val offset=real.indexOf(engine.edges.first())
        assertEquals(engine.edges,real.subList(offset,offset+engine.edges.size))
    }

    @Test fun connectorReceivesActualHeadingsAndMissionBoundaries() {
        val calls=mutableListOf<List<Any?>>()
        val paths=listOf(listOf(Point(2.0,2.0),Point(2.0,2.0),Point(4.0,2.0)),
            listOf(Point(4.0,4.0),Point(4.0,6.0),Point(4.0,6.0)))
        val start=Point(1.0,1.0); val end=Point(1.0,6.0)
        val result=RoutePlanning.assemble(paths,{ a,b,incoming,outgoing ->
            calls.add(listOf(a,b,incoming,outgoing)); listOf(a,b)
        },start,30.0,end,180.0)!!
        assertEquals(listOf(start,paths[0].first(),30.0,0.0),calls[0])
        assertEquals(listOf(paths[0].last(),paths[1].first(),0.0,90.0),calls[1])
        assertEquals(listOf(paths[1].last(),end,90.0,180.0),calls[2])
        assertEquals(listOf(false,true,false,true,false),result.map { it.cutting })
    }

    @Test fun partialCoverageIsNeverPromotedToRobotExecution() {
        val plan=RoutePlanning.coverage(request(config=PlanningConfig(minimumCoverage=1.0)))
        assertFalse(plan.report!!.selected.valid)
        assertTrue(plan.preview.localOnly)
        assertThrows(IllegalArgumentException::class.java) { RobotRoute.requireExecutable(plan.preview) }
    }

    @Test fun absentConnectionAndDegeneratePassNeverBecomeAValidMission() {
        val paths=listOf(listOf(Point(2.0,2.0),Point(4.0,2.0)),listOf(Point(8.0,8.0),Point(9.0,8.0)))
        assertNull(RoutePlanning.assemble(paths,{ _,_,_,_ -> null }))
        assertNull(RoutePlanning.assemble(listOf(listOf(Point(2.0,2.0),Point(2.0,2.0))),{ a,b,_,_ -> listOf(a,b) }))
        assertThrows(InterruptedException::class.java) {
            RoutePlanning.coverage(request(),checkpoint={ throw InterruptedException("annulé") })
        }
    }

    @Test fun pointPreviewAndLiveEntryUseTheSameSafeRoute() {
        val input=request(rectangle(w=20.0,h=20.0),holes=listOf(rectangle(7.0,6.0,6.0,8.0)))
        val start=Point(3.0,7.0); val target=Point(17.0,7.0)
        val actual=RoutePlanning.point(input.map,start,target,emptyList())
        assertEquals(TrajectoryPlanner.pointPlan(input.map,start,target,emptyList()).points,actual.points)
        actual.routes!!.requirePath(actual.points)
        assertThrows(IllegalArgumentException::class.java) {
            RoutePlanning.point(input.map,start,Point(-0.2,7.0),emptyList())
        }
        // Destination inside an exclusion: refused.
        assertThrows(IllegalArgumentException::class.java) {
            RoutePlanning.point(input.map,start,Point(10.0,10.0),emptyList())
        }
    }
}
