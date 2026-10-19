package fr.luba.poc.camera

import fr.luba.poc.auth.CloudReadOnly
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.LubaMulProto.MUL_CAMERA_POSITION
import fr.luba.poc.proto.LubaMulProto.MUL_VIDEO_ERROR_CODE
import fr.luba.poc.proto.LubaMulProto.MulSetVideoAck
import fr.luba.poc.proto.LubaMulProto.SocMul
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.fpv_to_app_info_t
import fr.luba.poc.proto.MctrlSysProto.net_used_type
import fr.luba.poc.proto.MctrlSysProto.report_info_data
import fr.luba.poc.proto.MctrlSysProto.rpt_connect_status
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_status
import fr.luba.poc.protocol.Commands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CameraTest {
    @Test fun `video command matches PyMammotion device_agora_join_channel_with_position`() {
        val m = LubaMsg.parseFrom(Commands(1234).video(true))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_MUL, m.msgtype)
        assertEquals(MsgDevice.DEV_MOBILEAPP, m.sender)
        assertEquals(MsgDevice.SOC_MODULE_MULTIMEDIA, m.rcver)
        assertEquals(1234, m.subtype); assertEquals(1, m.version)
        assertEquals(MUL_CAMERA_POSITION.LEFT, m.mul.setVideo.position)
        assertEquals(1, m.mul.setVideo.viSwitch)
        assertEquals(0, LubaMsg.parseFrom(Commands().video(false)).mul.setVideo.viSwitch)
        assertTrue(LubaMsg.parseFrom(Commands().refreshFpv()).mul.reqEncode.encode)
    }

    @Test fun `firmware debug switch`() {
        val m = LubaMsg.parseFrom(Commands().debugEnable(true))
        assertEquals(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, m.msgtype); assertEquals(MsgDevice.DEV_MAINCTL, m.rcver)
        assertEquals(1, m.sys.debugEnable.enbale)
        assertEquals(0, LubaMsg.parseFrom(Commands().debugEnable(false)).sys.debugEnable.enbale)
    }

    @Test fun `the cloud accepts video and always refuses movement`() {
        val c = Commands()
        CloudReadOnly.requireAllowed(c.video(true)); CloudReadOnly.requireAllowed(c.video(false)); CloudReadOnly.requireAllowed(c.refreshFpv())
        for (bytes in listOf(c.video(true, MUL_CAMERA_POSITION.ALL), c.debugEnable(true), c.movement(100, 0), c.stop()))
            try { CloudReadOnly.requireAllowed(bytes); fail("Non-video command accepted by the cloud") } catch (_: IllegalArgumentException) {}
    }

    @Test fun `parses the stream token response without exposing the token`() {
        val response = JSONObject("""{"code":0,"msg":"ok","data":{"appid":"app","openEncrypt":1,"channelName":"chan123456","areaCode":"AREA_CODE_EU",
            "token":"secret-token","uid":12345,"availableTime":1800,"key":"k","salt":"c2FsdA==","cameras":[{"cameraId":1,"token":"t1"},{"cameraId":2,"token":"t2"}]}}""")
        val c = CameraCredentials.parse(response)
        assertEquals("app", c.appId); assertEquals(12345L, c.uid); assertTrue(c.encrypted)
        assertEquals(listOf(1, 2), c.cameraIds); assertEquals(1800, c.availableSeconds); assertEquals("AREA_CODE_EU", c.areaCode)
        assertFalse(c.summary.contains("secret-token")); assertFalse(c.summary.contains("chan123456"))
        val page = JSONObject(c.toPageJson(2))
        assertEquals("secret-token", page.getString("token")); assertEquals(2, page.getInt("target")); assertEquals("c2FsdA==", page.getString("salt"))
    }

    @Test fun `empty or incomplete response refused`() {
        assertThrows(IllegalStateException::class.java) { CameraCredentials.parse(JSONObject("""{"code":0,"data":null}""")) }
        assertThrows(IllegalStateException::class.java) { CameraCredentials.parse(JSONObject("""{"code":0,"data":{"appid":"a","channelName":"","token":"t"}}""")) }
        val plain = CameraCredentials.parse(JSONObject("""{"code":0,"data":{"appid":"a","channelName":"c","token":"t","uid":5,"openEncrypt":0,"key":null}}"""))
        assertFalse(plain.encrypted); assertNull(plain.key); assertNull(plain.availableSeconds)
    }

    @Test fun `request body identical to PyMammotion`() {
        val body = CameraCredentials.requestBody("iot-1", bothFront = false)
        assertEquals("iot-1", body.getString("deviceId")); assertEquals(0, body.getInt("mode"))
        val states = body.getJSONArray("cameraStates")
        assertEquals(listOf(1, 0, 0), (0 until states.length()).map { states.getJSONObject(it).getInt("cameraState") })
        val both = CameraCredentials.requestBody("iot-1", bothFront = true).getJSONArray("cameraStates")
        assertEquals(1, both.getJSONObject(1).getInt("cameraState"))
    }

    private fun report(dev: rpt_dev_status? = null, net: net_used_type? = null) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(
        report_info_data.newBuilder().apply {
            dev?.let { setDev(it) }
            net?.let { setConnect(rpt_connect_status.newBuilder().setUsedNet(it)) }
        })).build()

    @Test fun `fpv and network state read from the telemetry`() {
        val o = CameraRobotObserver()
        // Without device report (robot reached through the cloud only): vi_switch=1 sent as a precaution.
        assertNull(o.value.fpvInfo); assertTrue(o.value.needsVideoSwitch)
        assertTrue(o.accept(report(rpt_dev_status.newBuilder().setBatteryVal(80).build()), 1))
        assertEquals(false, o.value.fpvInfo); assertTrue(o.value.needsVideoSwitch)
        o.accept(report(rpt_dev_status.newBuilder().setFpvInfo(fpv_to_app_info_t.newBuilder().setFpvFlag(1).setWifiAvailable(1)).build(), net_used_type.NET_USED_TYPE_MNET), 2)
        assertEquals(true, o.value.fpvInfo); assertFalse(o.value.needsVideoSwitch); assertTrue(o.value.mobileStream)
        assertTrue(o.value.fpvLabel.startsWith("disponible"))
        // A later report without fpv_info does not switch back to old firmware.
        o.accept(report(rpt_dev_status.newBuilder().setBatteryVal(79).build(), net_used_type.NET_USED_TYPE_WIFI), 3)
        assertEquals(true, o.value.fpvInfo); assertFalse(o.value.mobileStream)
        // Recent firmware but FPV inactive (fpv_flag 0, observed after a vi_switch=0): publishing must be requested again.
        o.accept(report(rpt_dev_status.newBuilder().setFpvInfo(fpv_to_app_info_t.newBuilder().setFpvFlag(0).setWifiAvailable(1)).build()), 5)
        assertEquals(true, o.value.fpvInfo); assertTrue(o.value.needsVideoSwitch); assertTrue(o.value.fpvLabel.startsWith("pas de FPV"))
        val ack = LubaMsg.newBuilder().setMul(SocMul.newBuilder().setSetVideoAck(MulSetVideoAck.newBuilder().setErrorCode(MUL_VIDEO_ERROR_CODE.NETWORK_NOT_AVAILABLE))).build()
        assertTrue(o.accept(ack, 4)); assertEquals(2, o.value.lastAck); assertEquals("réseau indisponible", CameraRobotState.ackLabel(o.value.lastAck))
    }

    @Test fun `right camera restarts a stream requested without it`() {
        assertTrue(CameraChoice.needsRestart(target = 2, playing = true, busy = false, sessionBothFront = false))
        assertFalse(CameraChoice.needsRestart(target = 2, playing = true, busy = false, sessionBothFront = true))
        assertFalse(CameraChoice.needsRestart(target = 2, playing = false, busy = false, sessionBothFront = false))
        assertFalse(CameraChoice.needsRestart(target = 2, playing = true, busy = true, sessionBothFront = false))
        assertFalse(CameraChoice.needsRestart(target = 1, playing = true, busy = false, sessionBothFront = false))
    }

    @Test fun `obstacle photo camera`() {
        val right = ObstaclePhotoUi.CAMERA_RIGHT; val front = ObstaclePhotoUi.CAMERA_FRONT
        // Chosen camera as soon as it has a frame.
        assertEquals(right, ObstaclePhotoUi.photoCamera(right, setOf(front, right), remainingMs = 20_000))
        // Right without frame: wait, then front camera as fallback in the last seconds.
        assertNull(ObstaclePhotoUi.photoCamera(right, setOf(front), remainingMs = 10_000))
        assertEquals(front, ObstaclePhotoUi.photoCamera(right, setOf(front), remainingMs = ObstaclePhotoUi.FALLBACK_MS))
        assertNull(ObstaclePhotoUi.photoCamera(right, emptySet(), remainingMs = 1_000))
        assertEquals(front, ObstaclePhotoUi.photoCamera(front, setOf(front), remainingMs = 20_000))
        // Saved setting: any unknown value falls back to the front camera; front by default.
        assertEquals(right, ObstaclePhotoUi.cameraOf(2)); assertEquals(front, ObstaclePhotoUi.cameraOf(7))
        assertEquals(front, ObstaclePhotoUi().camera); assertEquals(front, ObstaclePhotoRequest(1, 1, 1014, null, null).camera)
        assertEquals("caméra droite", ObstaclePhotoUi.cameraLabel(right))
        // Stream open without the right camera: restart needed for the photo.
        assertTrue(CameraChoice.needsRestart(right, playing = true, busy = false, sessionBothFront = false))
    }

    @Test fun tokenFailuresAreReadable() {
        assertTrue(CameraCredentials.failureReason(IllegalStateException("x"), 50504).contains("50504"))
        assertTrue(CameraCredentials.failureReason(IllegalStateException("x"), 460).contains("expirée"))
        assertTrue(CameraCredentials.failureReason(java.net.UnknownHostException("domestic.mammotion.com"), null).contains("Internet"))
        assertTrue(CameraCredentials.failureReason(java.net.SocketTimeoutException(), null).contains("délai"))
        assertEquals("2/2 · jeton vidéo : pas de réponse en 30 s",
            CameraCredentials.failureReason(Exception("2/2 · jeton vidéo : pas de réponse en 30 s"), null))
    }

    @Test fun `obstacle photo taken on a frame after the back-off`() {
        val readyAt = 100_000L
        // Agora delay of 800 ms (+ 500 ms margin): frame displayed 1.2 s after the stop still too early, 1.4 s kept.
        assertFalse(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 1_200, frameAt = readyAt + 1_200, delayMs = 800))
        assertTrue(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 1_400, frameAt = readyAt + 1_400, delayMs = 800))
        // Frozen stream: the last displayed frame predates the stop, never kept even long after.
        assertFalse(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 20_000, frameAt = readyAt - 3_000, delayMs = 800))
        // Unknown delay: 1.5 s; aberrant delay capped.
        assertFalse(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 1_400, frameAt = readyAt + 1_400, delayMs = null))
        assertTrue(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 1_500, frameAt = readyAt + 1_500, delayMs = null))
        assertEquals(ObstaclePhotoUi.MAX_DELAY_MS, ObstaclePhotoUi.streamDelay(60_000))
        // No frame displayed since tracking started: wait; tracking impossible: stream delay awaited after the stop.
        assertFalse(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 10_000, frameAt = 0, delayMs = 800))
        assertFalse(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 1_000, frameAt = null, delayMs = 2_000))
        assertTrue(ObstaclePhotoUi.sceneAfterStop(readyAt, readyAt + 2_500, frameAt = null, delayMs = 2_000))
    }
}
