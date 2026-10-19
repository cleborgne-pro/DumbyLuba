package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class SignalSimulationTest {
    private fun engine(mode: CoverageMode): MowingSimulation {
        val zone = MapShape(1,0,listOf(Point(0.0,0.0),Point(14.0,0.0),Point(14.0,14.0),Point(0.0,14.0)))
        val map = RobotMap(listOf(zone),true)
        val plan = MultiZonePlanner.plan(listOf(zone),emptyList(),3.0,0.6,mode,true)
        return MowingSimulation(TrajectoryPlanner(map,0.6,emptyList()),Point(4.0,4.0),plan,map,
            SimConfig(autonomyMinutes=600.0,baseEntryHeadingDegrees=0.0,instantCharging=true))
    }
    @Test fun heatmapGrowsDuringSurveyAndRemainsAfterCompletion() {
        val engine = engine(CoverageMode.SURVEY_GRID)
        var state = engine.start()
        assertTrue(state.signalSamples.isEmpty())
        var firstCount = 0
        repeat(100) {
            state = engine.advance(state,5.0)
            if (it==30) firstCount=state.signalSamples.size
        }
        assertTrue(firstCount>0)
        assertTrue(state.signalSamples.size>firstCount)
        assertTrue(SignalCoverage.cells(state.signalSamples,SurveySignal.WIFI).size>1)
        assertTrue(state.signalSamples.any { it.point.x in 2.0..12.0 && it.point.y in 2.0..12.0 })
        repeat(200) { state=engine.advance(state,30.0) }
        assertEquals(SimPhase.COMPLETE,state.phase)
        assertEquals(state.signalSamples,engine.advance(state,60.0).signalSamples)
        assertTrue(state.signalSamples.zipWithNext().all { (a,b) -> b.at-a.at >= 1000 })
    }
    @Test fun accessTransitNeverProducesSimulatedRadioMeasurements() {
        val engine=engine(CoverageMode.TOUR)
        val start=engine.start()
        assertEquals(SimPhase.LEAVING_DOCK,start.phase)
        val next=engine.advance(start,0.1)
        assertTrue(next.signalSamples.isEmpty())
        assertEquals(SimPhase.LEAVING_DOCK,next.phase)
        val first=engine.edges.indexOfFirst { it.cutting }
        val moving=start.copy(phase=SimPhase.MOWING,edge=first,position=engine.edges[first].a,elapsed=20.0)
        assertTrue(engine.advance(moving,1.0).signalSamples.isNotEmpty())
    }
    @Test fun simulatedValuesAreDeterministicAndEstimationIncludesTransitWithoutGeneratingData() {
        val point=Point(8.0,5.0);val base=Point(4.0,4.0)
        assertEquals(SignalCoverage.simulated(point,base,100.0),SignalCoverage.simulated(point,base,100.0))
        val engine=engine(CoverageMode.TOUR)
        val start=engine.start()
        val estimate=MowingEstimate.fromSimulation(engine)
        assertTrue(estimate.totalSeconds>engine.preview.meters/engine.config.speedMps)
        assertTrue(start.signalSamples.isEmpty())
        var state=start
        repeat(100) { state=engine.advance(state,30.0,simulateSignals=false) }
        assertEquals(SimPhase.COMPLETE,state.phase)
        assertTrue(state.signalSamples.isEmpty())
        assertEquals(estimate.totalSeconds,state.elapsed,1e-6)
    }
    @Test fun realTourUsesSurveyContourAndSafeTransitBackToBaseWithBladesOff() {
        val engine = engine(CoverageMode.TOUR)
        val edges = RobotRoute.actionEdges(engine,engine.baseApproach,0.0,RouteIntent.FOLLOW)
        assertFalse(RobotRoute.cuttingAllowed(engine.preview,true))
        assertTrue(edges.containsAll(engine.edges))
        assertEquals(engine.baseApproach,edges.last().b)
        assertTrue(edges.last().cutting == false)
        assertTrue(edges.zipWithNext().all { (a,b) -> a.b == b.a })
        engine.routes.requirePath(listOf(engine.baseApproach)+edges.map { it.b },0.0)
        val measured = edges.filter { it.cutting }
        assertTrue(measured.isNotEmpty())
        val contours = engine.preview.borders + engine.preview.runs
        for (edge in measured) {
            val telemetry = fr.luba.poc.protocol.Telemetry(x=edge.a.x,y=edge.a.y,
                lastPositionAt=1000,lastRtkAt=1000,positioningLevel=0)
            assertNotNull(SignalCoverage.sample(telemetry,1000,contours))
        }
    }
    @Test fun ordinaryMowingSimulationDoesNotPretendToMeasureSignals() {
        val engine=engine(CoverageMode.LINES)
        val first=engine.edges.indexOfFirst { it.cutting }
        val state=engine.start().copy(phase=SimPhase.MOWING,edge=first,position=engine.edges[first].a,elapsed=20.0)
        assertTrue(engine.advance(state,1.0).signalSamples.isEmpty())
    }
}
