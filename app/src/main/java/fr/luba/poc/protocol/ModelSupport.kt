package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.DevNetProto.DevNet
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMulProto.SocMul
import fr.luba.poc.proto.MctrlDriverProto.MctlDriver
import fr.luba.poc.proto.MctrlNavProto.MctlNav
import fr.luba.poc.proto.MctrlSysProto.MctlSys

/**
 * Support level of the connected Mammotion mower, from its Bluetooth name.
 *
 * Only the models validated in the field may receive motion, blade, blade-height or navigation-task commands: today
 * the LUBA 2 AWD identified as `Luba-VS…`. Every other Mammotion mower (LUBA 1, LUBA 2 X series `Luba-VP`, LUBA 3,
 * LUBA mini, Yuka…) is connected in **read-only** mode: connection, telemetry, map reading, error and self-check
 * queries, camera and diagnostics export, with the stop commands still available. Name prefixes come from the
 * Mammotion app table (PyMammotion `DeviceType`); pool cleaners (Spino) and RTK stations are not mowers.
 */
object ModelSupport {
    /** Bluetooth name prefixes of the models validated on a real robot (motion and blades allowed). */
    val VALIDATED_PREFIXES = listOf("Luba-VS")

    /** Bluetooth name prefixes of Mammotion mowers listed by the scan. */
    val MOWER_PREFIXES = listOf("Luba", "Yuka", "Ezy-", "Kumar", "Maston")

    fun isMower(name: String?): Boolean = name?.trim()?.let { n -> MOWER_PREFIXES.any { n.startsWith(it, ignoreCase = true) } } == true

    /** True when [name] is a validated model: motion and blade commands may be sent. */
    fun motionValidated(name: String?): Boolean =
        name?.trim()?.let { n -> VALIDATED_PREFIXES.any { n.startsWith(it, ignoreCase = true) } } == true

    /** Address of the page explaining the supported models and how to report one. */
    const val SUPPORT_PAGE = "github.com/cleborgne-pro/DumbyLuba/blob/main/docs/features/supported-models.md"

    /** Note shown under the robot card for a known robot that is not validated, null for a validated or no robot. */
    fun readOnlyNote(name: String?, modelLabel: String): String? {
        if (name.isNullOrBlank() || motionValidated(name)) return null
        return tr("Read-only mode: $modelLabel is not validated yet. Telemetry, map, camera and diagnostics work; motion and blades are disabled. Help support this model: $SUPPORT_PAGE",
            "Mode lecture seule : $modelLabel n'est pas encore validé. Télémétrie, carte, caméra et diagnostics fonctionnent ; mouvements et lames sont désactivés. Aidez à prendre en charge ce modèle : $SUPPORT_PAGE")
    }

    /** Refusal shown when a motion or blade action is requested on a model that is not validated, null otherwise. */
    fun refusal(name: String?): String? = if (motionValidated(name)) null else
        tr("Read-only: this robot model is not validated yet, motion and blades are disabled (only the LUBA 2 AWD Luba-VS is validated)",
            "Lecture seule : ce modèle de robot n'est pas encore validé, mouvements et lames désactivés (seul le LUBA 2 AWD Luba-VS est validé)")
}

/**
 * Last barrier of the read-only mode, applied to every payload written to a robot that is not validated: an explicit
 * list of read and stop commands, everything else is refused (unknown or new commands included).
 *
 * Allowed: zero-speed motion (STOP), blades off (manual mowing 0/0, knife off), BLE sync, network information,
 * report subscription, read-only system query (`rw = 1`), self-check details, model query (`device_product_type_info`), cutter mode query, map and route reads
 * (hashes, data, acknowledgements), task pause / stop / cancel return (actions 2, 4, 12), camera video and encoder.
 * Map data: only the read requests (`subCmd` 1, action 8) and the frame acknowledgements (`subCmd` 2); recording,
 * erasing or deleting a map element (`subCmd` 0) is refused.
 */
object ReadOnlyPolicy {
    private val STOP_TASK_ACTIONS = setOf(2, 4, 12)

    fun allows(payload: ByteArray): Boolean {
        val message = try { LubaMsg.parseFrom(payload) } catch (_: Exception) { return false }
        return when (message.lubaSubMsgCase) {
            LubaMsg.LubaSubMsgCase.NET -> when (message.net.netSubTypeCase) {
                DevNet.NetSubTypeCase.TODEV_BLE_SYNC, DevNet.NetSubTypeCase.TODEV_NETWORKINFO_REQ -> true
                else -> false
            }
            LubaMsg.LubaSubMsgCase.DRIVER -> {
                val driver = message.driver
                when (driver.subDrvMsgCase) {
                    MctlDriver.SubDrvMsgCase.TODEV_DEVMOTION_CTRL ->
                        driver.todevDevmotionCtrl.setLinearSpeed == 0 && driver.todevDevmotionCtrl.setAngularSpeed == 0
                    MctlDriver.SubDrvMsgCase.MOW_CTRL_BY_HAND ->
                        driver.mowCtrlByHand.mainCtrl == 0 && driver.mowCtrlByHand.cutKnifeCtrl == 0
                    MctlDriver.SubDrvMsgCase.CURRENT_CUTTER_MODE -> true
                    else -> false
                }
            }
            LubaMsg.LubaSubMsgCase.SYS -> {
                val sys = message.sys
                when (sys.subSysMsgCase) {
                    MctlSys.SubSysMsgCase.TODEV_REPORT_CFG, MctlSys.SubSysMsgCase.TO_DEV_SELF_CHECK_INFO_REQ,
                    MctlSys.SubSysMsgCase.DEVICE_PRODUCT_TYPE_INFO -> true
                    MctlSys.SubSysMsgCase.BIDIRE_COMM_CMD -> sys.bidireCommCmd.rw == 1
                    MctlSys.SubSysMsgCase.TODEV_KNIFE_CTRL -> sys.todevKnifeCtrl.knifeStatus == 0
                    else -> false
                }
            }
            LubaMsg.LubaSubMsgCase.NAV -> {
                val nav = message.nav
                when (nav.subNavMsgCase) {
                    MctlNav.SubNavMsgCase.TODEV_GETHASH, MctlNav.SubNavMsgCase.APP_REQUEST_COVER_PATHS -> true
                    MctlNav.SubNavMsgCase.TODEV_GET_COMMONDATA ->
                        nav.todevGetCommondata.let { (it.subCmd == 1 && it.action == 8) || it.subCmd == 2 }
                    MctlNav.SubNavMsgCase.TODEV_TASKCTRL -> nav.todevTaskctrl.action in STOP_TASK_ACTIONS
                    else -> false
                }
            }
            LubaMsg.LubaSubMsgCase.MUL -> when (message.mul.subMulCase) {
                SocMul.SubMulCase.SET_VIDEO, SocMul.SubMulCase.REQ_ENCODE -> true
                else -> false
            }
            else -> false
        }
    }
}
