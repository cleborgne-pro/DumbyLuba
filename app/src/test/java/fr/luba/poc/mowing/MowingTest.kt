package fr.luba.poc.mowing

import fr.luba.poc.protocol.*
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.*
import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class MowingTest {
    private val config = MowConfig(listOf(123L, 456L))
    private val t = Telemetry(battery = 80, robotState = 11, sensorStatus = 0, selfCheck = 0,
        chargeState = 0, x = 1.0, y = 2.0, lastPositionAt = 1000, lastDeviceAt = 1000)
    @Test fun preparationAcceptsFirmwareZeroPaddingButRejectsOtherMissions() {
        val config = MowConfig(listOf(1354990494679877631L))
        val response = fr.luba.poc.proto.MctrlNavProto.NavReqCoverPath.newBuilder()
            .setPver(1).setKnifeHeight(60).setPathHash(6515263766183269599L)
            .addAllZoneHashs(config.zones + List(99) { 0L }).build()
        assertTrue(MowPreparation.matches(response, config))
        assertFalse(MowPreparation.matches(response, MowConfig(listOf(123L))))
        assertFalse(MowPreparation.matches(response, config.copy(heightMm = 55)))
        assertFalse(MowPreparation.matches(response.toBuilder().setSubCmd(2).build(), config))
        assertFalse(MowPreparation.matches(response.toBuilder().addZoneHashs(123L).build(), config))
        assertFalse(MowPreparation.matches(response.toBuilder().clearZoneHashs().addZoneHashs(0L).build(), config))
    }
    @Test fun nativeCommandsNeverContainJoystickOrBladeDriver() {
        val c = Commands()
        val route = LubaMsg.parseFrom(c.prepareMowing(config))
        assertFalse(route.hasDriver())
        assertEquals(config.zones, route.nav.bidireReqconverPath.zoneHashsList)
        assertEquals(60, route.nav.bidireReqconverPath.knifeHeight)
        assertEquals(0.3f, route.nav.bidireReqconverPath.speed)
        listOf(1, 2, 3, 4, 5, 7).forEach { action ->
            val m = LubaMsg.parseFrom(c.taskControl(action))
            assertFalse(m.hasDriver()); assertEquals(action, m.nav.todevTaskctrl.action)
            assertEquals(1, m.nav.todevTaskctrl.type)
        }
        assertEquals(1, LubaMsg.parseFrom(c.leaveDock()).nav.todevOneTouchLeavePile)
    }
    @Test fun selectedParametersReachNativePreparation() {
        val config = MowConfig(listOf(123L), heightMm = 45, checkerboard = true, spacingCm = 30, speedMps = 0.5f)
        val route = LubaMsg.parseFrom(Commands().prepareMowing(config)).nav.bidireReqconverPath
        assertEquals(45, route.knifeHeight)
        assertEquals(30, route.channelWidth)
        assertEquals(0.5f, route.speed)
        assertEquals(1, route.channelMode)
        assertEquals(90, route.towardIncludedAngle)
        val simple = LubaMsg.parseFrom(Commands().prepareMowing(config.copy(checkerboard = false))).nav.bidireReqconverPath
        assertEquals(0, simple.channelMode)
        assertEquals(0, simple.towardIncludedAngle)
    }
    @Test fun invalidParametersAreRejectedBeforeSending() {
        listOf(4, 36).forEach { width ->
            assertThrows(IllegalArgumentException::class.java) { config.copy(spacingCm = width) }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.1f, 0.7f).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) { config.copy(speedMps = speed) }
        }
    }
    @Test fun chargingCommandsMatchDocumentedProtocolWithoutStartingMowing() {
        val c=Commands()
        val back=LubaMsg.parseFrom(c.returnToDock())
        assertEquals(5,back.nav.todevTaskctrl.action)
        assertEquals(1,back.nav.todevTaskctrl.type)
        assertEquals(0,back.nav.todevTaskctrl.result)
        assertFalse(back.hasDriver())
        val cancel=LubaMsg.parseFrom(c.cancelReturnToDock())
        assertEquals(12,cancel.nav.todevTaskctrl.action)
        assertEquals(1,cancel.nav.todevTaskctrl.type)
        val leave=LubaMsg.parseFrom(c.leaveDock())
        assertEquals(1,leave.nav.todevOneTouchLeavePile)
        assertFalse(leave.nav.hasTodevTaskctrl())
    }
    @Test fun staleStateAndProtectionsBlockCommands() {
        assertNull(MowSafety.failure("Luba-VS123", true, false, t, 1000))
        assertNotNull(MowSafety.failure("Luba-VS123", true, false, t, 3001))
        assertNotNull(MowSafety.failure("Luba-VS123", true, false, t.copy(selfCheck = null), 1000))
        assertNotNull(MowSafety.failure("Luba-VS123", true, true, t, 1000))
        assertNotNull(MowSafety.failure("Luba-VS123", true, false, t.copy(sensorStatus = 1), 1000))
        assertNotNull(MowSafety.startFailure(t.copy(battery = 30), 1000))
        assertNotNull(MowSafety.startFailure(t.copy(lastPositionAt = null), 1000))
        assertNotNull(MowSafety.startFailure(t.copy(sensorStatus = 512), 1000))
    }
    @Test fun zonesMustBelongToCompleteRobotMap() {
        val zone = MapShape(123, 0, listOf(Point(0.0,0.0), Point(5.0,0.0), Point(5.0,5.0)))
        assertNotNull(MowSafety.mapFailure(RobotMap(listOf(zone), true), config))
        assertNull(MowSafety.mapFailure(RobotMap(listOf(zone), true), MowConfig(listOf(123))))
        assertNotNull(MowSafety.mapFailure(RobotMap(listOf(zone), false), MowConfig(listOf(123))))
    }
    @Test fun pauseWaitSurvivesIntermediateWorkingReportsAndConfirmsOnlyPause() {
        val pending = MowUi(phase = MowPhase.PAUSING, config = config)
        val working = MowObservation.accept(pending, t.copy(robotState = 13, sensorStatus = 512), 0.5, 1000)
        assertEquals(MowPhase.PAUSING, working.phase)
        assertEquals(MowPhase.PAUSED, MowObservation.accept(working, t.copy(robotState = 19), null, 1000).phase)
        assertEquals(MowPhase.PAUSING, MowObservation.accept(pending, t.copy(robotState = 11), null, 1000).phase)
    }
    @Test fun taskRefusalRequiresMatchingOutstandingAction() {
        assertNotNull(MowTaskAck.rejection(2,2,1))
        assertNull(MowTaskAck.rejection(2,1,1))
        assertNull(MowTaskAck.rejection(null,2,1))
        assertNull(MowTaskAck.rejection(2,2,0))
    }
    @Test fun startingAtDockDoesNotValidateReturnOrCancelDepartureWait() {
        val ui = MowUi(phase = MowPhase.STARTING, config = config)
        val after = MowObservation.accept(ui, t.copy(robotState = 15, chargeState = 1), 0.0, 1000)
        assertEquals(MowPhase.STARTING, after.phase)
        assertFalse(after.dockedObserved)
    }
    @Test fun observationsRequireNewStateAndRealMovement() {
        var ui = MowUi(phase = MowPhase.STARTING, config = config)
        assertEquals(ui, MowObservation.accept(ui, t, 10.0, 1001))
        ui = MowObservation.accept(ui, t.copy(robotState = 13, sensorStatus = 512), 0.5, 1000)
        assertEquals(MowPhase.WORKING, ui.phase); assertTrue(ui.moved); assertTrue(ui.mowingObserved)
        ui = MowObservation.accept(ui, t.copy(robotState = 14), null, 1000)
        assertTrue(ui.returningObserved)
        ui = MowObservation.accept(ui, t.copy(robotState = 11, chargeState = 0), null, 1000)
        assertFalse(ui.dockedObserved)
        ui = MowObservation.accept(ui, t.copy(robotState = 15, chargeState = 1), 0.0, 1000)
        assertTrue(ui.dockedObserved)
        ui = MowObservation.accept(ui.copy(phase = MowPhase.LEAVING), t, 0.1, 1000)
        assertFalse(ui.leftDockObserved)
        ui = MowObservation.accept(ui, t, 0.3, 1000)
        assertTrue(ui.leftDockObserved); assertEquals(MowPhase.UNDOCKED, ui.phase)
    }
    @Test fun returnWaitDoesNotBecomePausedBeforeReturnIsRequested() {
        val ui = MowUi(phase = MowPhase.RETURN_REQUESTED)
        assertEquals(MowPhase.RETURN_REQUESTED, MowObservation.accept(ui, t.copy(robotState = 19), 0.0, 1000).phase)
        assertEquals(MowPhase.PREPARING, MowObservation.accept(ui.copy(phase = MowPhase.PREPARING), t.copy(robotState = 13), 5.0, 1000).phase)
    }
    @Test fun chargeAndWorkProgressAreDecodedWithoutFakingDeviceFreshness() {
        val r = TelemetryReducer()
        r.accept(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(
            report_info_data.newBuilder().setDev(rpt_dev_status.newBuilder().setBatteryVal(80).setChargeState(1))
                .setWork(rpt_work.newBuilder().setProgress(35)))).build(), 1000, 1000)
        assertEquals(1, r.value.chargeState); assertEquals(35, r.value.workProgress)
        r.accept(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(
            report_info_data.newBuilder().setWork(rpt_work.newBuilder().setProgress(40)))).build(), 2000, 2000)
        assertEquals(1000L, r.value.lastDeviceAt); assertEquals(40, r.value.workProgress)
    }
}
