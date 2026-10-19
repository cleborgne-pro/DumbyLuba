package fr.luba.poc.protocol

import fr.luba.poc.diagnostics.MapNavFollower
import fr.luba.poc.map.Point
import fr.luba.poc.manual.Joystick
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import org.junit.Assert.*
import org.junit.Test

class RobotCoordinatesTest {
    @Test fun heightProgressUsesCurrentHeightNotRequestedEndHeight() {
        val reducer = TelemetryReducer()
        fun change(started: Int, current: Int) = LubaMsg.newBuilder().setDriver(
            fr.luba.poc.proto.MctrlDriverProto.MctlDriver.newBuilder().setToappKnifeStatusChange(
                fr.luba.poc.proto.MctrlDriverProto.DrvKnifeChangeReport.newBuilder()
                    .setIsStart(started).setStartHeight(60).setEndHeight(40).setCurHeight(current))).build()
        assertFalse(reducer.accept(change(1,55),100,100))
        assertEquals(55,reducer.value.driverHeightMm)
        assertEquals(true,reducer.value.heightMoving)
        assertEquals(40,reducer.value.heightTargetMm)
        reducer.accept(change(0,40),200,200)
        assertEquals(40,reducer.value.driverHeightMm)
        assertEquals(false,reducer.value.heightMoving)
        assertEquals(200L,reducer.value.lastDriverHeightAt)
    }
    @Test fun compassAxesAndWrap() {
        assertEquals(90.0, RobotCoordinates.mapHeading(0.0), 1e-9)
        assertEquals(0.0, RobotCoordinates.mapHeading(90.0), 1e-9)
        assertEquals(270.0, RobotCoordinates.mapHeading(180.0), 1e-9)
        assertEquals(180.0, RobotCoordinates.mapHeading(270.0), 1e-9)
        assertEquals(107.0, RobotCoordinates.mapHeading(-17.0), 1e-9)
    }
    @Test fun observedNorthHeadingMustTurnBeforeFollowingEast() {
        val (linear, angular) = MapNavFollower.command(Point(0.9213,2.2697), RobotCoordinates.mapHeading(0.0), Point(1.4267,2.2697),100,150)
        assertEquals(0, linear)
        assertTrue(angular < 0)
        assertTrue(LubaMsg.parseFrom(Commands().movement(linear,angular)).driver.todevDevmotionCtrl.setAngularSpeed > 0)
    }
    @Test fun rightJoystickSendsPositiveFirmwareRotation() {
        for (command in listOf(Joystick.command(1f,0f,100), Joystick.dualCommand(0f,1f,100))) {
            assertEquals(450, LubaMsg.parseFrom(Commands().movement(command.first,command.second)).driver.todevDevmotionCtrl.setAngularSpeed)
        }
    }
    @Test fun bumperManualKeepaliveNeverStartsDiscsOrMotion() {
        val message = LubaMsg.parseFrom(Commands().manualMow(1,0,60))
        assertEquals(1,message.driver.mowCtrlByHand.mainCtrl)
        assertEquals(0,message.driver.mowCtrlByHand.cutKnifeCtrl)
        assertFalse(message.driver.hasTodevDevmotionCtrl())
    }
}
