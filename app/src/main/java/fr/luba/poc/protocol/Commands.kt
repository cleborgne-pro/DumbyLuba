package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.LubaMsgProto.*
import fr.luba.poc.proto.DevNetProto.DevNet
import fr.luba.poc.proto.DevNetProto.DrvDebugDdsZmq
import fr.luba.poc.proto.DevNetProto.GetNetworkInfoReq
import fr.luba.poc.proto.MctrlDriverProto.*
import fr.luba.poc.proto.MctrlNavProto.*
import fr.luba.poc.proto.MctrlSysProto.*
import fr.luba.poc.proto.LubaMulProto.*

/** Supervised joystick commands, cutting tests and native firmware missions. */
class Commands(private val userAccount: Int = 0) {
    companion object {
        /** Ceiling of the Mammotion app rocker (PyMammotion `control.py` / `movement.py`:
         * linear_speed == ±1000, angular_speed == ±450). Measured units ≈ 1 mm/s and ≈ 0.1 °/s. */
        const val PROTOCOL_MAX_LINEAR = 1000
        const val PROTOCOL_MAX_ANGULAR = 450
        /** Automatic navigation ceilings: forward 500 (≈ 0.48 m/s measured on 2026-10-10),
         * rotation 450 (≈ 50 °/s). */
        const val MAX_LINEAR_SPEED = 500
        const val MAX_ANGULAR_SPEED = 450
        /** Joystick: 100 % = actual ceiling of the official rocker (≈ 1 m/s, ≈ 45 °/s). */
        const val JOYSTICK_MAX_LINEAR = PROTOCOL_MAX_LINEAR
        const val JOYSTICK_MAX_ANGULAR = PROTOCOL_MAX_ANGULAR
    }
    private var sequence = 0
    private fun envelope(type: MsgCmdType, receiver: MsgDevice, now: Long): LubaMsg.Builder {
        sequence = (sequence + 1) and 255
        return LubaMsg.newBuilder().setMsgtype(type).setSender(MsgDevice.DEV_MOBILEAPP)
            .setRcver(receiver).setMsgattr(MsgAttr.MSG_ATTR_REQ).setSeqs(sequence)
            .setVersion(1).setSubtype(userAccount).setTimestamp(now)
    }
    fun sync(now: Long = System.currentTimeMillis(), syncType: Int = 2): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_ESP, MsgDevice.DEV_COMM_ESP, now)
            .setNet(DevNet.newBuilder().setTodevBleSync(syncType)).build().toByteArray()

    fun stop(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, MsgDevice.DEV_MAINCTL, now)
            .setDriver(MctlDriver.newBuilder().setTodevDevmotionCtrl(
                DrvMotionCtrl.newBuilder().setSetLinearSpeed(0).setSetAngularSpeed(0)))
            .build().toByteArray()

    /** Joystick movement (upstream `send_movement`): raw int32 speeds, uncalibrated units.
     * Strict bounds; joystick and controllers use the same mathematical convention. */
    // Internal convention: positive = left (increasing mathematical angle). Firmware: positive = right.
    fun movement(linear: Int, angular: Int, now: Long = System.currentTimeMillis()): ByteArray {
        require(linear in -PROTOCOL_MAX_LINEAR..PROTOCOL_MAX_LINEAR) { tr("Linear speed out of bounds", "Vitesse linéaire hors bornes") }
        require(angular in -PROTOCOL_MAX_ANGULAR..PROTOCOL_MAX_ANGULAR) { tr("Angular speed out of bounds", "Vitesse angulaire hors bornes") }
        return envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, MsgDevice.DEV_MAINCTL, now)
            .setDriver(MctlDriver.newBuilder().setTodevDevmotionCtrl(
                DrvMotionCtrl.newBuilder().setSetLinearSpeed(linear).setSetAngularSpeed(-angular)))
            .build().toByteArray()
    }

    fun bladeHeight(mm: Int, now: Long = System.currentTimeMillis()): ByteArray {
        require(mm in CutHeightRange.ANY)
        return envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, MsgDevice.DEV_MAINCTL, now)
            .setDriver(MctlDriver.newBuilder().setTodevKnifeHeightSet(DrvKnifeHeight.newBuilder().setKnifeHeight(mm)))
            .build().toByteArray()
    }

    /** Exact parameters of mower_api.async_start_stop_blades for non-LUBA 1 models.
     * max_run_speed is an upstream limit, not a requested speed. No non-zero movement is sent.
     */
    fun manualBlades(enabled: Boolean, heightMm: Int, now: Long = System.currentTimeMillis()): ByteArray =
        manualMow(if (enabled) 1 else 0, if (enabled) 1 else 0, heightMm, now)

    /** Configurable manual mowing: `main_ctrl` (manual mowing mode) and `cut_knife_ctrl` (discs) are
     * separate to reproduce the official app's sequence: mode first, then discs. */
    fun manualMow(mainCtrl: Int, cutKnifeCtrl: Int, heightMm: Int, now: Long = System.currentTimeMillis()): ByteArray {
        require(mainCtrl in 0..1) { tr("main_ctrl outside 0..1", "main_ctrl hors 0..1") }
        require(cutKnifeCtrl in 0..1) { tr("cut_knife_ctrl outside 0..1", "cut_knife_ctrl hors 0..1") }
        require(heightMm in CutHeightRange.ANY) { CutHeightRange.ANY.refusal(heightMm)!! }
        return envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, MsgDevice.DEV_MAINCTL, now)
            .setDriver(MctlDriver.newBuilder().setMowCtrlByHand(DrvMowCtrlByHand.newBuilder()
                .setMainCtrl(mainCtrl).setCutKnifeCtrl(cutKnifeCtrl)
                .setCutKnifeHeight(heightMm).setMaxRunSpeed(1.2f)))
            .build().toByteArray()
    }

    /** "LUBA 1" cutting command (MctlSys.todev_knife_ctrl): the one the upstream joystick uses for
     * every model. Documented alternative path, never a silent workaround. */
    fun knifeControl(enabled: Boolean, now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, MsgDevice.DEV_MAINCTL, now)
            .setSys(MctlSys.newBuilder().setTodevKnifeCtrl(
                SysKnifeControl.newBuilder().setKnifeStatus(if (enabled) 1 else 0)))
            .build().toByteArray()

    /** Cutter mode query (upstream `get_cutter_mode`); reply expected in
     * `MctlDriver.current_cutter_mode` (current mode, RPM, query result). */
    fun cutterQuery(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_DRIVER, MsgDevice.DEV_MAINCTL, now)
            .setDriver(MctlDriver.newBuilder().setCurrentCutterMode(AppGetCutterWorkMode.getDefaultInstance()))
            .build().toByteArray()

    private fun nav(n: MctlNav): ByteArray = envelope(MsgCmdType.MSG_CMD_TYPE_NAV, MsgDevice.DEV_NAVIGATION, System.currentTimeMillis()).setNav(n).build().toByteArray()
    /** Native preparation, conservative manufacturer parameters from upstream. */
    fun prepareMowing(config: fr.luba.poc.mowing.MowConfig): ByteArray = nav(MctlNav.newBuilder()
        .setBidireReqconverPath(NavReqCoverPath.newBuilder().setPver(1).setSubCmd(0)
            .setJobMode(4).setEdgeMode(1).setKnifeHeight(config.heightMm).setSpeed(config.speedMps)
            .setUltraWave(2).setChannelWidth(config.spacingCm).setChannelMode(if (config.checkerboard) 1 else 0)
            .setTowardIncludedAngle(if (config.checkerboard) 90 else 0).setTaskSettingsMode(1)
            .addAllZoneHashs(config.zones)).build())
    fun taskControl(action: Int): ByteArray {
        require(action in setOf(1, 2, 3, 4, 5, 7)) { tr("Mowing action not allowed", "Action tonte non autorisée") }
        return nav(MctlNav.newBuilder().setTodevTaskctrl(NavTaskCtrl.newBuilder()
            .setType(1).setAction(action).setResult(0)).build())
    }
    /** Full return computed by the firmware, not a docking-only back-off command. */
    fun returnToDock(): ByteArray = taskControl(5)
    fun cancelReturnToDock(): ByteArray = nav(MctlNav.newBuilder().setTodevTaskctrl(
        NavTaskCtrl.newBuilder().setType(1).setAction(12).setResult(0)).build())
    fun leaveDock(): ByteArray = nav(MctlNav.newBuilder().setTodevOneTouchLeavePile(1).build())

    fun mowPathHashes() = nav(MctlNav.newBuilder().setTodevGethash(NavGetHashList.newBuilder().setPver(1).setSubCmd(3)).build())
    fun mowPaths(hashes: List<Long>, transaction: Long) = nav(MctlNav.newBuilder().setAppRequestCoverPaths(
        app_request_cover_paths_t.newBuilder().setPver(1).setSubCmd(0).addAllHashList(hashes).setTransactionId(transaction)).build())

    fun mapHashes() = nav(MctlNav.newBuilder().setTodevGethash(NavGetHashList.newBuilder().setPver(1).setSubCmd(0)).build())
    fun mapHashesAck(total: Int, frame: Int) = nav(MctlNav.newBuilder().setTodevGethash(NavGetHashList.newBuilder().setPver(1).setSubCmd(2).setTotalFrame(total).setCurrentFrame(frame)).build())
    fun mapData(hash: Long) = nav(MctlNav.newBuilder().setTodevGetCommondata(NavGetCommData.newBuilder().setPver(1).setAction(8).setSubCmd(1).setHash(hash)).build())
    fun mapDataAck(f: NavGetCommDataAck) = nav(MctlNav.newBuilder().setTodevGetCommondata(NavGetCommData.newBuilder().setPver(1).setSubCmd(2).setAction(f.action).setType(f.type).setHash(f.hash).setTotalFrame(f.totalFrame).setCurrentFrame(f.currentFrame)).build())

    // ---------------------------------------------------------------- map writing (sync with the Mammotion app)
    // APK 2.3.8 `MACommandHelper` (PyMammotion `navigation.py`): `todev_get_commondata` without `subCmd` (0), so that
    // [ReadOnlyPolicy] and the cloud filter, which only let reads (subCmd 1 / 2) through, refuse them.

    /** Starts recording a boundary by driving (APK `startDrawBorder` / `startDrawBarrier` / `startChannelLine`):
     * [type] 0 zone, 1 no-go zone, 2 passage. The robot records its own positions until [endMapRecording]. */
    fun startMapRecording(type: Int): ByteArray {
        require(type in MAP_RECORD_TYPES) { tr("Map element type not allowed", "Type d'élément de carte non autorisé") }
        return nav(MctlNav.newBuilder().setTodevGetCommondata(NavGetCommData.newBuilder().setPver(1).setAction(0).setType(type)).build())
    }

    /** Ends the recording started by [startMapRecording] (APK `endDrawBorder(type)`): the robot saves the element. */
    fun endMapRecording(type: Int): ByteArray {
        require(type in MAP_RECORD_TYPES) { tr("Map element type not allowed", "Type d'élément de carte non autorisé") }
        return nav(MctlNav.newBuilder().setTodevGetCommondata(NavGetCommData.newBuilder().setPver(1).setAction(1).setType(type)).build())
    }

    /** Explicit save of the mapping (APK `saveTask`, BLE only): `todev_save_task = 1`. */
    fun saveMapTask(): ByteArray = nav(MctlNav.newBuilder().setTodevSaveTask(1).build())

    /** Abandons the recording in progress without saving anything (APK `cancelCurrentRecord`). */
    fun cancelMapRecording(): ByteArray =
        nav(MctlNav.newBuilder().setTodevGetCommondata(NavGetCommData.newBuilder().setPver(1).setAction(7).setSubCmd(0)).build())

    /** Deletes one map element by its hash (APK `deleteMapElements(type, hash)`); never the whole map. */
    fun deleteMapElement(type: Int, hash: Long): ByteArray {
        require(type in MAP_RECORD_TYPES) { tr("Map element type not allowed", "Type d'élément de carte non autorisé") }
        require(hash != 0L) { tr("Map element hash missing", "Hash d'élément de carte absent") }
        return nav(MctlNav.newBuilder().setTodevGetCommondata(NavGetCommData.newBuilder().setPver(1).setAction(6).setType(type).setHash(hash)).build())
    }

    /** Upstream get_error_code query: no change to the protections. */
    fun errorQuery(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, MsgDevice.DEV_MAINCTL, now)
            .setSys(MctlSys.newBuilder().setBidireCommCmd(SysCommCmd.newBuilder().setId(5).setContext(2).setRw(1)))
            .build().toByteArray()

    /**
     * Self-check details (MctlSys field 70 `to_dev_self_check_info_req`, reply field 71: `scp`/`scr` pairs).
     * Read only, to decode an undocumented protection (self-check 25 of 2026-10-14).
     */
    fun selfCheckInfo(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, MsgDevice.DEV_MAINCTL, now)
            .setSys(MctlSys.newBuilder().setToDevSelfCheckInfoReq(self_check_info_req.newBuilder().setSelfCheckReqid(1)))
            .build().toByteArray()

    /**
     * Telemetry subscription to the `report_info` reports (read only). Maintenance (mileage, working time, battery
     * cycles, blade use: blade reminder) is always requested. [vision] adds the camera perception streams
     * RIT_VISION_POINT, RIT_VIO and RIT_VISION_STATISTIC (sensor probe only); `account` or [vision] add the RTK base.
     *
     * [periodMs]: 200 ms is requested during tracked movements, but the LUBA 2 (firmware 1.30.31.19) keeps publishing
     * at 1 Hz: on 2026-10-20, 20 s after a 200 ms request with no other request, the positions were 1.0 s apart
     * (median 1005-1010 ms, routes of 17:16 and 19:19). Kept in case another firmware honours it.
     */
    fun reports(keep: Boolean, now: Long = System.currentTimeMillis(), periodMs: Int = 1000, vision: Boolean = false, account: Boolean = false): ByteArray {
        require(periodMs in 200..1000)
        return envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, MsgDevice.DEV_MAINCTL, now)
            .setSys(MctlSys.newBuilder().setTodevReportCfg(report_info_cfg.newBuilder()
                // Sensor probe: RPT_START on every renewal, like the Mammotion app (it never uses RPT_KEEP);
                // a KEEP does not guarantee that the firmware takes up the list of vision streams again.
                .setAct(if (keep && !vision) rpt_act.RPT_KEEP else rpt_act.RPT_START)
                .setTimeout(10000).setPeriod(periodMs).setNoChangePeriod(periodMs).setCount(0)
                .addAllSub(listOf(rpt_info_type.RIT_CONNECT, rpt_info_type.RIT_DEV_STA,
                    rpt_info_type.RIT_RTK, rpt_info_type.RIT_DEV_LOCAL, rpt_info_type.RIT_FW_INFO, rpt_info_type.RIT_WORK, rpt_info_type.RIT_CUTTER_INFO,
                    rpt_info_type.RIT_MAINTAIN) +
                    (if (vision) listOf(rpt_info_type.RIT_VISION_POINT, rpt_info_type.RIT_VIO, rpt_info_type.RIT_VISION_STATISTIC) else emptyList()) +
                    // Full list of the Mammotion app during the probe (maintenance and RTK base included).
                    if (account || vision) listOf(rpt_info_type.RIT_BASESTATION_INFO) else emptyList())))
            .build().toByteArray()
    }

    /**
     * Camera (PyMammotion `device_agora_join_channel_with_position`): asks the multimedia module to
     * publish (1) or stop (0) the Agora video stream. No movement or blade command.
     * Position LEFT for every model other than the original Yuka.
     */
    fun video(on: Boolean, position: MUL_CAMERA_POSITION = MUL_CAMERA_POSITION.LEFT, now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_MUL, MsgDevice.SOC_MODULE_MULTIMEDIA, now)
            .setMul(SocMul.newBuilder().setSetVideo(MulSetVideo.newBuilder().setPosition(position).setViSwitch(if (on) 1 else 0)))
            .build().toByteArray()

    /** Video encoder restart (PyMammotion `refresh_fpv`), every 3 s when the robot streams over 4G. */
    fun refreshFpv(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_MUL, MsgDevice.SOC_MODULE_MULTIMEDIA, now)
            .setMul(SocMul.newBuilder().setReqEncode(MulSetEncode.newBuilder().setEncode(true)))
            .build().toByteArray()

    /**
     * Global firmware debug switch (APK `setDebugEnable`, `debug_enable_t`): sensor probe only, reset to 0 when
     * the capture stops. Any `debug_common_report` reports are read.
     */
    fun debugEnable(on: Boolean, now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, MsgDevice.DEV_MAINCTL, now)
            .setSys(MctlSys.newBuilder().setDebugEnable(debug_enable_t.newBuilder().setEnbale(if (on) 1 else 0)))
            .build().toByteArray()

    /**
     * Robot model (PyMammotion `get_device_product_model`): reply `device_product_type_info` with the internal model
     * code (`sub_product_type`), which tells the H versions (high cut) apart. Read-only query.
     */
    fun productModel(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_EMBED_SYS, MsgDevice.DEV_MAINCTL, now)
            .setSys(MctlSys.newBuilder().setDeviceProductTypeInfo(device_product_type_info_t.newBuilder().setResult(1)))
            .build().toByteArray()

    /** Robot network information (APK `getDeviceNetWorkInfo`): reply `toapp_networkinfo_rsp` (SSID, IP, mask). */
    fun networkInfo(now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_ESP, MsgDevice.DEV_COMM_ESP, now)
            .setNet(DevNet.newBuilder().setTodevNetworkinfoReq(GetNetworkInfoReq.newBuilder().setReqIds(1)))
            .build().toByteArray()

    /**
     * Robot DDS → ZMQ debug bridge (APK `setZMQEnable`): the DDS topic [PERCEPTION_TOPIC] is republished over ZMQ on
     * [PERCEPTION_ZMQ_URL] (robot port 5555, local Wi-Fi network). Sensor probe only, turned off when it stops.
     */
    fun perceptionZmq(on: Boolean, now: Long = System.currentTimeMillis()): ByteArray =
        envelope(MsgCmdType.MSG_CMD_TYPE_ESP, MsgDevice.DEV_COMM_ESP, now)
            .setNet(DevNet.newBuilder().setTodevSetDds2Zmq(DrvDebugDdsZmq.newBuilder().setIsEnable(on)
                .setRxTopicName(PERCEPTION_TOPIC).setTxZmqUrl(PERCEPTION_ZMQ_URL)))
            .build().toByteArray()
}

/** Map element types the app may record or delete: 0 zone, 1 no-go zone, 2 passage. */
val MAP_RECORD_TYPES = setOf(0, 1, 2)

/** Values sent by the Mammotion app (`setZMQEnable`). */
const val PERCEPTION_TOPIC = "perception_post_result"
const val PERCEPTION_ZMQ_URL = "tcp://0.0.0.0:5555"
const val PERCEPTION_ZMQ_PORT = 5555
