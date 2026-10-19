package fr.luba.poc.diagnostics

import fr.luba.poc.protocol.Commands
import fr.luba.poc.protocol.TelemetryReducer
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlDriverProto.*
import org.junit.Assert.*
import org.junit.Test

class BladeTest {
    @Test fun `setting feedback stays distinct from the work report`() {
        val reducer = TelemetryReducer()
        val driver = LubaMsg.newBuilder().setDriver(MctlDriver.newBuilder().setBidireKnifeHeightReport(DrvKnifeHeight.newBuilder().setKnifeHeight(60))).build()
        reducer.accept(driver, 100, 100)
        val work = LubaMsg.newBuilder().setSys(fr.luba.poc.proto.MctrlSysProto.MctlSys.newBuilder().setToappReportData(
            fr.luba.poc.proto.MctrlSysProto.report_info_data.newBuilder().setWork(fr.luba.poc.proto.MctrlSysProto.rpt_work.newBuilder().setKnifeHeight(50)))).build()
        reducer.accept(work, 200, 200)
        assertEquals(60, reducer.value.driverHeightMm)
        assertEquals(100L, reducer.value.lastDriverHeightAt)
        assertEquals(50, reducer.value.workHeightMm)
        assertEquals(200L, reducer.value.lastWorkHeightAt)
    }

    @Test fun `height uses driver field 2 without starting the blades`() {
        val msg = LubaMsg.parseFrom(Commands().bladeHeight(45, 1))
        assertTrue(msg.driver.hasTodevKnifeHeightSet())
        assertEquals(45, msg.driver.todevKnifeHeightSet.knifeHeight)
        assertFalse(msg.driver.hasMowCtrlByHand())
        assertThrows(IllegalArgumentException::class.java) { Commands().bladeHeight(24) }
        assertThrows(IllegalArgumentException::class.java) { Commands().bladeHeight(71) }
    }
    @Test fun `Luba2 ON OFF identical to upstream parameters without travel speed`() {
        for (enabled in listOf(false, true)) {
            val msg = LubaMsg.parseFrom(Commands().manualBlades(enabled, 60, 1))
            assertTrue(msg.driver.hasMowCtrlByHand())
            val command = msg.driver.mowCtrlByHand
            assertEquals(if (enabled) 1 else 0, command.mainCtrl)
            assertEquals(if (enabled) 1 else 0, command.cutKnifeCtrl)
            assertEquals(60, command.cutKnifeHeight)
            assertEquals(1.2f, command.maxRunSpeed, 0.0f)
            assertFalse(msg.driver.hasTodevDevmotionCtrl())
        }
    }
    @Test fun `height feedback does not refresh the general watchdog`() {
        val msg = LubaMsg.newBuilder().setDriver(MctlDriver.newBuilder().setBidireKnifeHeightReport(DrvKnifeHeight.newBuilder().setKnifeHeight(50))).build()
        val reducer = TelemetryReducer()
        assertFalse(reducer.accept(msg, 100, 100))
        assertEquals(50, reducer.value.bladeHeightMm)
        assertEquals(100L, reducer.value.lastBladeHeightAt)
        assertNull(reducer.value.lastReportAt)
    }
    @Test fun `guard refuses active robot protection blades low battery and stale state`() {
        BladeSafety.requireReady("Luba-VS-test", 100, 72, 11, 0, 0, false, 60)
        val scenarios = listOf<() -> Unit>(
            { BladeSafety.requireReady("Luba", 100, 72, 11, 0, 0, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 2001, 72, 11, 0, 0, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 100, 30, 11, 0, 0, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 100, 72, 13, 0, 0, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 100, 72, 11, 1, 0, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 100, 72, 11, 512, 0, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 100, 72, 11, 0, 34, false, 60) },
            { BladeSafety.requireReady("Luba-VS", 100, 72, 11, 0, 0, true, 60) }
        )
        scenarios.forEach { action -> assertThrows(IllegalArgumentException::class.java) { action() } }
    }

    @Test fun `two-step start separates mode and discs without travel speed`() {
        val mode = LubaMsg.parseFrom(Commands().manualMow(1, 0, 60, 1))
        assertEquals(1, mode.driver.mowCtrlByHand.mainCtrl)
        assertEquals(0, mode.driver.mowCtrlByHand.cutKnifeCtrl)
        assertEquals(60, mode.driver.mowCtrlByHand.cutKnifeHeight)
        assertEquals(1.2f, mode.driver.mowCtrlByHand.maxRunSpeed, 0.0f)
        assertFalse(mode.driver.hasTodevDevmotionCtrl())
        val disc = LubaMsg.parseFrom(Commands().manualMow(1, 1, 60, 1))
        assertEquals(1, disc.driver.mowCtrlByHand.mainCtrl)
        assertEquals(1, disc.driver.mowCtrlByHand.cutKnifeCtrl)
        assertThrows(IllegalArgumentException::class.java) { Commands().manualMow(2, 1, 60) }
        assertThrows(IllegalArgumentException::class.java) { Commands().manualMow(1, -1, 60) }
        assertThrows(IllegalArgumentException::class.java) { Commands().manualMow(1, 1, 24) }
    }

    @Test fun `alternative SysKnifeControl command matches upstream with no other field`() {
        val on = LubaMsg.parseFrom(Commands().knifeControl(true, 1))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, on.msgtype)
        assertEquals(MsgDevice.DEV_MAINCTL, on.rcver)
        assertTrue(on.sys.hasTodevKnifeCtrl())
        assertEquals(1, on.sys.todevKnifeCtrl.knifeStatus)
        assertFalse(on.driver.hasMowCtrlByHand())
        assertEquals(0, LubaMsg.parseFrom(Commands().knifeControl(false, 2)).sys.todevKnifeCtrl.knifeStatus)
    }

    @Test fun `cutter mode query and decoded reply without refreshing the watchdog`() {
        val query = LubaMsg.parseFrom(Commands().cutterQuery(1))
        assertTrue(query.driver.hasCurrentCutterMode())
        val answer = LubaMsg.newBuilder().setDriver(MctlDriver.newBuilder()
            .setCurrentCutterMode(AppGetCutterWorkMode.newBuilder().setCurrentCutterMode(1).setCurrentCutterRpm(3200))).build()
        val reducer = TelemetryReducer()
        assertFalse(reducer.accept(answer, 100, 100))
        assertEquals(1, reducer.value.cutterMode)
        assertEquals(3200, reducer.value.cutterRpm)
        assertNull(reducer.value.lastReportAt)
    }

    @Test fun `knife_status reply decoded without refreshing the watchdog`() {
        val report = LubaMsg.newBuilder().setDriver(MctlDriver.newBuilder()
            .setToappKnifeStatus(DrvKnifeStatus.newBuilder().setKnifeStatus(1))).build()
        val reducer = TelemetryReducer()
        assertFalse(reducer.accept(report, 50, 50))
        assertEquals(1, reducer.value.knifeStatus)
        assertEquals(50L, reducer.value.lastKnifeStatusAt)
        assertNull(reducer.value.lastReportAt)
    }
    @Test fun leavingBladeScreenStopsOnlyItsOwnTestNeverRealMowing() {
        assertFalse(BladeSafety.shouldStopTestOnExit(true,false,""))
        assertFalse(BladeSafety.shouldStopTestOnExit(true,false,"old-test"))
        assertFalse(BladeSafety.shouldStopTestOnExit(false,false,""))
        assertTrue(BladeSafety.shouldStopTestOnExit(false,true,"test"))
        assertTrue(BladeSafety.shouldStopTestOnExit(false,false,"awaiting-off"))
    }

    private fun change(start: Int, end: Int, cur: Int, moving: Boolean) = LubaMsg.newBuilder().setDriver(MctlDriver.newBuilder()
        .setToappKnifeStatusChange(DrvKnifeChangeReport.newBuilder().setIsStart(if (moving) 1 else 0).setStartHeight(start).setEndHeight(end).setCurHeight(cur))).build()
    private fun work(mm: Int) = LubaMsg.newBuilder().setSys(fr.luba.poc.proto.MctrlSysProto.MctlSys.newBuilder().setToappReportData(
        fr.luba.poc.proto.MctrlSysProto.report_info_data.newBuilder().setWork(fr.luba.poc.proto.MctrlSysProto.rpt_work.newBuilder().setKnifeHeight(mm)))).build()

    @Test fun `setting outside a mission goes through manual mode with blades OFF and the height`() {
        // Logs of 2026-10-10: only mow_ctrl_by_hand(main_ctrl=1, cut_knife_height) moves the cutting deck.
        val mode = LubaMsg.parseFrom(Commands().manualMow(1, 0, 40, 1)).driver.mowCtrlByHand
        assertEquals(1, mode.mainCtrl); assertEquals(0, mode.cutKnifeCtrl); assertEquals(40, mode.cutKnifeHeight)
        val exit = LubaMsg.parseFrom(Commands().manualMow(0, 0, 40, 1)).driver.mowCtrlByHand
        assertEquals(0, exit.mainCtrl); assertEquals(0, exit.cutKnifeCtrl); assertEquals(40, exit.cutKnifeHeight)
    }

    @Test fun `announced target is not a reached height`() {
        val reducer = TelemetryReducer()
        reducer.accept(work(60), 100, 100)
        assertFalse(HeightSequence.reached(reducer.value, 40, 150))
        reducer.accept(change(60, 40, 60, true), 200, 200)
        assertFalse(HeightSequence.reached(reducer.value, 40, 150))
        reducer.accept(work(55), 300, 300)
        assertFalse(HeightSequence.reached(reducer.value, 40, 150))
        reducer.accept(change(60, 40, 40, false), 400, 400)
        assertTrue(HeightSequence.reached(reducer.value, 40, 150))
    }

    @Test fun `work report at the target ends a movement without an end message`() {
        val reducer = TelemetryReducer()
        reducer.accept(change(60, 40, 60, true), 200, 200)
        reducer.accept(work(40), 300, 300)
        assertEquals(false, reducer.value.heightMoving)
        assertTrue(HeightSequence.reached(reducer.value, 40, 150))
        // A new target is not blocked by the old movement flag.
        reducer.accept(work(60), 500, 500)
        assertTrue(HeightSequence.reached(reducer.value, 60, 450))
    }

    @Test fun `report older than the command confirms nothing`() {
        val reducer = TelemetryReducer()
        reducer.accept(work(40), 100, 100)
        assertFalse(HeightSequence.reached(reducer.value, 40, 100))
        reducer.accept(work(40), 101, 101)
        assertTrue(HeightSequence.reached(reducer.value, 40, 100))
    }

    @Test fun `reversal after leaving manual mode is detected`() {
        val reducer = TelemetryReducer()
        reducer.accept(work(40), 100, 100)
        assertNull(HeightSequence.departed(reducer.value, 40, 50))
        reducer.accept(change(40, 60, 40, true), 200, 200)
        assertEquals(60, HeightSequence.departed(reducer.value, 40, 150))
        val other = TelemetryReducer()
        other.accept(work(40), 100, 100)
        other.accept(work(45), 300, 300)
        assertEquals(45, HeightSequence.departed(other.value, 40, 150))
        assertNull(HeightSequence.departed(other.value, 40, 300))
    }
}
