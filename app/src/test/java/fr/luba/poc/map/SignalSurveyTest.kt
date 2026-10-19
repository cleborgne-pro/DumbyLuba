package fr.luba.poc.map

import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.protocol.TelemetryReducer
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.*
import fr.luba.poc.simulation.RobotRoute
import org.junit.Assert.*
import org.junit.Test

class SignalSurveyTest {
    private val contour = listOf(listOf(Point(0.0,0.0),Point(10.0,0.0)))
    private fun telemetry() = Telemetry(x=1.0,y=0.0,lastPositionAt=1000,wifiRssi=-75,bleRssi=-50,mobileRssi=-90,
        lastConnectAt=1000,lastRtkAt=1000,positioningLevel=0,gpsStars=24)

    @Test fun rejectsStalePositionsAndInteriorTransits() {
        assertNull(SignalCoverage.sample(telemetry(),3001,contour))
        assertNull(SignalCoverage.sample(telemetry().copy(y=2.0),1000,contour))
        assertNull(SignalCoverage.sample(telemetry().copy(x=Double.NaN),1000,contour))
    }
    @Test fun signalFreshnessIsIndependentAndMissingSignalsStayMissing() {
        val sample = requireNotNull(SignalCoverage.sample(telemetry().copy(lastPositionAt=3000,lastConnectAt=999),3000,contour))
        assertNull(sample.wifi); assertNull(sample.ble); assertNull(sample.mobile)
        assertEquals(100.0,sample.value(SurveySignal.RTK)!!,0.0)
        assertNull(SignalCoverage.sample(telemetry().copy(lastPositionAt=4000),4000,contour))
        val missing = requireNotNull(SignalCoverage.sample(telemetry().copy(wifiRssi=0,mobileRssi=0),1000,contour))
        assertNull(missing.wifi); assertNull(missing.mobile)
        assertTrue(SignalCoverage.cells(listOf(missing),SurveySignal.WIFI).isEmpty())
    }
    @Test fun measuredCellsAverageSignalsWithoutFillingTheInterior() {
        val one = requireNotNull(SignalCoverage.sample(telemetry(),1000,contour))
        val two = one.copy(point=Point(1.8,0.0),wifi=-55,rtkLevel=1)
        val cells = SignalCoverage.cells(listOf(one,two),SurveySignal.WIFI)
        assertEquals(1,cells.size); assertEquals(-65.0,cells.single().value,0.0)
        assertEquals(2,cells.single().count)
        assertEquals(50.0,SignalCoverage.cells(listOf(one,two),SurveySignal.RTK).single().value,0.0)
    }
    @Test fun parsesRobotSignalsAndClearsUnavailableRssi() {
        val reducer = TelemetryReducer()
        fun report(wifi: Int) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(
            report_info_data.newBuilder().setConnect(rpt_connect_status.newBuilder().setWifiRssi(wifi).setBleRssi(-60).setMnetRssi(-90))
                .setRtk(rpt_rtk.newBuilder().setPosLevel(0).setGpsStars(25)))).build()
        reducer.accept(report(-70),1000,1000)
        assertEquals(-70,reducer.value.wifiRssi); assertEquals(25,reducer.value.gpsStars)
        assertEquals(1000L,reducer.value.lastConnectAt)
        reducer.accept(report(0),2000,2000)
        assertNull(reducer.value.wifiRssi)
    }
    @Test fun tourCannotEnableBladesEvenWhenRequested() {
        val tour = Preview(emptyList(),0.0,0,0,mode=CoverageMode.TOUR)
        assertFalse(RobotRoute.cuttingAllowed(tour,true))
        assertFalse(RobotRoute.cuttingAllowed(tour,false))
        assertFalse(RobotRoute.cuttingAllowed(tour.copy(mode=CoverageMode.SURVEY_GRID),true))
        assertTrue(RobotRoute.cuttingAllowed(tour.copy(mode=CoverageMode.LINES),true))
    }
    @Test fun multiZoneTourContainsOnlySelectedOuterContours() {
        val zones = listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(0.0,10.0))),
            MapShape(2,0,listOf(Point(15.0,0.0),Point(25.0,0.0),Point(25.0,10.0),Point(15.0,10.0))))
        // Use the model's zone type, without depending on protocol identifiers.
        val selected = zones.filter { it.id == 2L }
        assertEquals(ShapeKind.ZONE,selected.single().kind)
        val plan = MultiZonePlanner.plan(selected,emptyList(),0.25,0.6,CoverageMode.TOUR,false,4,4)
        assertEquals(listOf(2L),plan.zoneIds)
        assertTrue(plan.runs.isEmpty()); assertTrue(plan.passes.isEmpty())
        assertEquals(1,plan.outerLoops); assertEquals(0,plan.noGoLoops)
        assertTrue(plan.cuttingPaths.all { path -> path.first() == path.last() && path.all { it.x > 15 } })
    }
    private fun zone(id: Long, x: Double, y: Double, width: Double, height: Double) = MapShape(id,0,
        listOf(Point(x,y),Point(x+width,y),Point(x+width,y+height),Point(x,y+height)))

    @Test fun adjacentZonesShareOneOuterTourWithoutTheCommonBorder() {
        val selected = listOf(zone(1,0.0,0.0,10.0,10.0),zone(2,10.0,0.0,10.0,10.0))
        val plan = MultiZonePlanner.plan(selected,emptyList(),0.25,0.6,CoverageMode.TOUR,true)
        assertEquals(listOf(1L,2L),plan.zoneIds)
        assertEquals(1,plan.outerLoops)
        assertEquals(1,plan.cuttingPaths.size)
        assertTrue(plan.cuttingPaths.single().first() == plan.cuttingPaths.single().last())
        assertTrue(plan.cuttingPaths.single().zipWithNext().none { (a,b) ->
            kotlin.math.abs(a.x-10.0)<0.01 && kotlin.math.abs(b.x-10.0)<0.01 && kotlin.math.abs(a.y-b.y)>1.0
        })
    }
    @Test fun gridMeasuresInsideMergedZonesWithBothSweepDirectionsAndExcludesNoGo() {
        val selected = listOf(zone(1,0.0,0.0,15.0,20.0),zone(2,15.0,0.0,15.0,20.0))
        val hole = zone(3,12.0,8.0,4.0,4.0).points
        val grid = MultiZonePlanner.plan(selected,listOf(hole),3.0,0.6,CoverageMode.SURVEY_GRID,false,4,4)
        val tour = MultiZonePlanner.plan(selected,listOf(hole),0.25,0.6,CoverageMode.TOUR,false)
        assertEquals(1,grid.outerLoops); assertEquals(0,grid.noGoLoops)
        assertTrue(grid.passes.isNotEmpty()); assertTrue(grid.meters>tour.meters)
        assertTrue(grid.passes.flatten().any { it.x in 2.0..28.0 && it.y in 2.0..18.0 })
        val directions = grid.passes.flatMap { it.zipWithNext() }.filter { (a,b) -> a.distance(b)>2.0 }
            .map { (a,b) -> kotlin.math.atan2(b.y-a.y,b.x-a.x) }
        assertTrue(directions.any { a -> directions.any { b -> kotlin.math.abs(kotlin.math.cos(a-b))<0.01 } })
        grid.cuttingPaths.forEach { path -> path.zipWithNext().forEach { (a,b) ->
            assertTrue(Geometry.safe(a,b,listOf(Point(0.0,0.0),Point(30.0,0.0),Point(30.0,20.0),Point(0.0,20.0)),listOf(hole),0.6))
        } }
        val point = grid.passes.first().let { path -> Point((path[0].x+path[1].x)/2,(path[0].y+path[1].y)/2) }
        assertNotNull(SignalCoverage.sample(telemetry().copy(x=point.x,y=point.y),1000,grid.runs+grid.borders))
    }

    /** Weak Wi-Fi in red, strong in green; a medium signal stays bright (yellow/orange), never a dark green. */
    @Test fun weakWifiIsRedAndStrongWifiIsGreen() {
        fun color(dbm: Double) = heatColor(SurveySignal.WIFI, SignalCell(Point(0.5,0.5),dbm,1))
        val weak = color(-85.0); val poor = color(-75.0); val fair = color(-60.0); val strong = color(-45.0)
        assertTrue("weak: red", weak.red > 0.8f && weak.green < 0.3f)
        assertTrue("poor: orange, more red than green", poor.red > 0.9f && poor.red > poor.green)
        assertTrue("fair: yellow", fair.red > 0.9f && fair.green > 0.8f)
        assertTrue("strong: green", strong.green > strong.red + 0.3f)
        assertTrue("the medium signal is not darker than the strong one", poor.red + poor.green >= strong.red + strong.green)
        assertTrue(SignalCoverage.strength(SurveySignal.WIFI, -80.0) == 0f && SignalCoverage.strength(SurveySignal.WIFI, -50.0) == 1f)
    }
}
