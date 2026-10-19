package fr.luba.poc.diagnostics

import com.google.protobuf.UnknownFieldSet
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlPeptProto.MctlPept
import fr.luba.poc.proto.MctrlPeptProto.perception_universal_buff_t
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_status
import fr.luba.poc.proto.MctrlSysProto.report_info_data
import fr.luba.poc.proto.MctrlSysProto.vio_to_app_info_msg
import fr.luba.poc.proto.MctrlSysProto.vision_point_info_msg
import fr.luba.poc.proto.MctrlSysProto.vision_point_msg
import org.junit.Assert.*
import org.junit.Test

class SensorProbeTest {
    private fun report(sensor: Int) = LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS)
        .setSender(MsgDevice.DEV_MAINCTL).setRcver(MsgDevice.DEV_MOBILEAPP)
        .setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().setDev(rpt_dev_status.newBuilder().setSensorStatus(sensor)))).build()

    @Test fun ignoresMessagesWhenStopped() {
        val probe = SensorProbe(); probe.accept(report(1), 10)
        assertEquals(0L, probe.snapshot().messages)
    }

    @Test fun recordsStreamsAndDistinctSensorValues() {
        val probe = SensorProbe(); probe.start(0)
        probe.accept(report(0), 10); probe.accept(report(0), 20); probe.accept(report(5), 30)
        val s = probe.snapshot()
        assertEquals(3L, s.messages)
        assertEquals(listOf(0, 5), s.sensorValues.map { it.value })
        assertEquals(2L, s.sensorValues.first().count)
        assertEquals(1, s.streams.size)
        assertTrue(s.verdict.startsWith("Aucun ultrason"))
    }

    @Test fun flagsPerceptionMessages() {
        val probe = SensorProbe(); probe.start(0)
        probe.accept(LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_PEPT).setSender(MsgDevice.DEV_PERCEPTION)
            .setPept(MctlPept.newBuilder().setPerceptionUniversalBuff(perception_universal_buff_t.newBuilder().setPerceptionType(3))).build(), 10)
        val s = probe.snapshot()
        assertEquals(1L, s.perceptionMessages); assertEquals(1L, s.peptPayloads)
        assertTrue(s.verdict.startsWith("Messages de perception"))
    }

    @Test fun reportsUnknownFieldsWithPath() {
        val probe = SensorProbe(); probe.start(0)
        val unknown = UnknownFieldSet.newBuilder().addField(900, UnknownFieldSet.Field.newBuilder().addVarint(42).build()).build()
        val dev = rpt_dev_status.newBuilder().setSensorStatus(1).setUnknownFields(unknown)
        probe.accept(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder().setDev(dev))).build(), 10)
        val s = probe.snapshot()
        assertEquals(1, s.unknown.size)
        assertTrue(s.unknown.single().path.endsWith("dev"))
        assertTrue(s.unknown.single().lastFields.contains("#900=42"))
    }

    @Test fun decodesUltrasonicSlotsAndReportsChanges() {
        assertEquals(2, SensorSlot.ULT_LEFT_FRONT.raw(2 shl 15))
        assertEquals(0, SensorSlot.ULT_RIGHT.raw(2 shl 15))
        val probe = SensorProbe(); probe.start(0)
        assertTrue(probe.accept(report(0), 10).isEmpty())
        val changed = probe.accept(report((2 shl 15) or (1 shl 21) or (1 shl 9)), 20)
        assertEquals(listOf(SensorSlot.ULT_LEFT_FRONT to 2, SensorSlot.ULT_RIGHT to 1), changed)
        probe.accept(report(0), 30)
        val s = probe.snapshot()
        val leftFront = s.slots.single { it.slot == SensorSlot.ULT_LEFT_FRONT }
        assertEquals(2L, leftFront.changes); assertEquals(2, leftFront.max); assertEquals(0, leftFront.current)
        assertEquals(0L, s.slots.single { it.slot == SensorSlot.BUMPER }.changes)
        assertEquals(4L, s.ultrasonicChanges)
        assertTrue(s.verdict.startsWith("Ultrasons : changement"))
    }

    @Test fun countsVisionStreams() {
        val probe = SensorProbe(); probe.start(0)
        val data = report_info_data.newBuilder()
            .addVisionPointInfo(vision_point_info_msg.newBuilder().setLabel(3).setNum(1).addVisionPoint(vision_point_msg.newBuilder().setX(1.5f).setY(-0.5f)))
            .setVioToAppInfo(vio_to_app_info_msg.newBuilder().setVioState(2).setDetectFeatureNum(40))
        probe.accept(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(data)).build(), 10)
        val v = probe.snapshot().vision
        assertEquals(1L, v.reportPoints); assertEquals(1L, v.vio); assertEquals(0L, v.statistics)
        assertTrue(v.lastPoints.startsWith("label 3 ×1"))
        assertTrue(probe.snapshot().verdict.startsWith("Points d'obstacle vision"))
    }

    @Test fun bitsAreGroupedBySensorSlot() {
        assertEquals("000 000 001 010 000 000 000 000 000 111", ProbeSensorValue((1 shl 21) or (2 shl 18) or 7, 1, 0, 0).bits)
    }

    @Test fun resetClearsEverything() {
        val probe = SensorProbe(); probe.start(0); probe.accept(report(1), 10); probe.reset()
        assertEquals(0L, probe.snapshot().messages); assertEquals(0, probe.snapshot().sensorValues.size)
    }
}

class SensorProbeDebugAndTunnelTest {
    private fun sys(block: fr.luba.poc.proto.MctrlSysProto.MctlSys.Builder.() -> Unit) =
        LubaMsg.newBuilder().setSys(fr.luba.poc.proto.MctrlSysProto.MctlSys.newBuilder().apply(block)).build()

    @Test fun recordsFirmwareDebugReports() {
        val probe = SensorProbe(); probe.start(0)
        probe.accept(sys { setDebugCommonReport(fr.luba.poc.proto.MctrlSysProto.debug_common_report_t.newBuilder().setMName("ult").setKey("dist0").setValue("0.42")) }, 10)
        probe.accept(sys { setDebugCommonReport(fr.luba.poc.proto.MctrlSysProto.debug_common_report_t.newBuilder().setMName("ult").setKey("dist0").setValue("0.30")) }, 20)
        val s = probe.snapshot()
        assertEquals(1, s.debugEntries.size)
        assertEquals("0.30", s.debugEntries.single().value); assertEquals(2L, s.debugEntries.single().count)
        assertTrue(s.verdict.startsWith("Rapports de débogage"))
    }

    @Test fun reportsVaryingTunnelIndicesAndVisionState() {
        val probe = SensorProbe(); probe.start(0)
        val vision = (3L shl 24) or (2L shl 8)
        fun tard(v: Long) = sys { setSystemTardStateTunnel(fr.luba.poc.proto.MctrlSysProto.systemTardStateTunnel_msg.newBuilder()
            .addAllTardStateData(List(16) { if (it == 5) v else 7L } + vision)) }
        probe.accept(tard(100), 10); probe.accept(tard(250), 20)
        val s = probe.snapshot()
        val array = s.arrays.single()
        assertEquals(17, array.length); assertEquals(listOf("[5] 100..250"), array.varying)
        assertEquals(3, s.extras.visionState); assertEquals(2, s.extras.fuseStatus)
        assertTrue(s.verdict.startsWith("Tunnels bruts variables"))
    }

    @Test fun decodesPerceptionObstacles() {
        val probe = SensorProbe(); probe.start(0)
        val obstacles = fr.luba.poc.proto.MctrlPeptProto.perception_obstacles_visualization_t.newBuilder().setStatus(1).setNum(1).setScale(0.01f)
            .addObstacles(fr.luba.poc.proto.MctrlPeptProto.perception_obstacles_t.newBuilder().setLabel(4).setNum(2).addPointsX(10).addPointsY(-3))
        probe.accept(LubaMsg.newBuilder().setPept(MctlPept.newBuilder().setPerceptionObstaclesVisualization(obstacles)).build(), 10)
        val s = probe.snapshot()
        assertEquals(1L, s.extras.obstacleMessages); assertTrue(s.extras.lastObstacles.contains("(10, -3)"))
    }

    @Test fun resetClearsDebugSwitch() {
        val probe = SensorProbe(); probe.start(0); probe.debugEnabled = true
        assertTrue(probe.snapshot().debugEnabled)
        probe.start(10)
        assertFalse(probe.snapshot().debugEnabled)
    }
}

/** Test of 2026-10-13: "still no capture"; the capture must tell whether the robot moved and count all vision data. */
class SensorProbeActivityTest {
    private fun dev(state: Int) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder()
        .setDev(rpt_dev_status.newBuilder().setSysStatus(state)))).build()

    @Test fun idleCaptureSaysToRetryDuringATrip() {
        val probe = SensorProbe(); probe.start(0)
        repeat(3) { probe.accept(dev(11), 10L + it) }; probe.accept(dev(15), 20)
        val snapshot = probe.snapshot()
        assertEquals(mapOf(11 to 3L, 15 to 1L), snapshot.robotStates)
        assertTrue(snapshot.idleOnly)
        assertTrue(snapshot.verdict, snapshot.verdict.contains("pendant un trajet"))
        probe.accept(dev(20), 30)
        assertFalse(probe.snapshot().idleOnly)
    }

    @Test fun rapidTunnelVioAndStatisticsAreCounted() {
        val probe = SensorProbe(); probe.start(0)
        val tunnel = fr.luba.poc.proto.MctrlSysProto.systemRapidStateTunnel_msg.newBuilder()
            .setVioToAppInfo(fr.luba.poc.proto.MctrlSysProto.vio_to_app_info_msg.newBuilder().setVioState(2).setDetectFeatureNum(40))
        probe.accept(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setSystemRapidStateTunnel(tunnel)).build(), 5)
        assertEquals(1L, probe.snapshot().vision.vio)
        assertTrue(probe.snapshot().verdict, probe.snapshot().verdict.contains("VIO"))
    }

    @Test fun probeSubscriptionIsRestartedLikeTheMammotionApp() {
        val cfg = LubaMsg.parseFrom(fr.luba.poc.protocol.Commands().reports(true, vision = true)).sys.todevReportCfg
        assertEquals(fr.luba.poc.proto.MctrlSysProto.rpt_act.RPT_START, cfg.act)
        assertTrue(cfg.subList.containsAll(listOf(fr.luba.poc.proto.MctrlSysProto.rpt_info_type.RIT_MAINTAIN,
            fr.luba.poc.proto.MctrlSysProto.rpt_info_type.RIT_VISION_POINT)))
        assertEquals(fr.luba.poc.proto.MctrlSysProto.rpt_act.RPT_KEEP,
            LubaMsg.parseFrom(fr.luba.poc.protocol.Commands().reports(true)).sys.todevReportCfg.act)
    }
}
