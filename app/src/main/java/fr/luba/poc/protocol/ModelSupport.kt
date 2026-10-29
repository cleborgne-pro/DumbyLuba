package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr
import fr.luba.poc.proto.DevNetProto.DevNet
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.LubaMulProto.SocMul
import fr.luba.poc.proto.MctrlDriverProto.MctlDriver
import fr.luba.poc.proto.MctrlNavProto.MctlNav
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.ui.RobotModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Support level of the connected Mammotion mower, from its Bluetooth name.
 *
 * Only the models validated in the field may receive motion, blade, blade-height or navigation-task commands: today
 * the LUBA 2 AWD identified as `Luba-VS…` and its X series `Luba-VP…` (same platform and firmware, unlocked after the
 * report of a 3000X owner, DumbyLuba#1). Every other Mammotion mower (LUBA 1, LUBA 3, LUBA mini, Yuka…) is connected
 * in **read-only** mode: connection, telemetry, map reading, error and self-check
 * queries, camera and diagnostics export, with the stop commands still available. Name prefixes come from the
 * Mammotion app table (PyMammotion `DeviceType`); pool cleaners (Spino) and RTK stations are not mowers.
 */
object ModelSupport {
    /** Bluetooth name prefixes of the models validated on a real robot (motion and blades allowed). */
    val VALIDATED_PREFIXES = listOf("Luba-VS", "Luba-VP")

    /** Bluetooth name prefixes of Mammotion mowers listed by the scan. */
    val MOWER_PREFIXES = listOf("Luba", "Yuka", "Ezy-", "Kumar", "Maston")

    fun isMower(name: String?): Boolean = name?.trim()?.let { n -> MOWER_PREFIXES.any { n.startsWith(it, ignoreCase = true) } } == true

    /**
     * Mammotion mower of the account: name prefix as on Bluetooth, or a mower product key of the Mammotion table
     * ([RobotModel.fromProductKey]). The account name of a LUBA 1 may not carry a model prefix (DumbyLuba#3). Being
     * listed grants nothing: motion stays bound to the name through [motionValidated].
     */
    fun isMower(name: String?, productKey: String?): Boolean = isMower(name) || RobotModel.fromProductKey(productKey) != null

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
        tr("Read-only: this robot model is not validated yet, motion and blades are disabled (only the LUBA 2 AWD, Luba-VS and X series Luba-VP, is validated)",
            "Lecture seule : ce modèle de robot n'est pas encore validé, mouvements et lames désactivés (seul le LUBA 2 AWD, Luba-VS et série X Luba-VP, est validé)")
}

/**
 * Trial of a model that is not validated ([unlocking a model](docs/features/supported-models.md#unlocking-a-model)):
 * armed by the owner for the connected robot only, it opens the three unlocking tests and nothing else:
 * the command cutoff test, the joystick at low speed ([MAX_LINEAR], [MAX_ANGULAR]) and the blade test. Routes,
 * mowing, moves to a point, return to base, gateway tracking, cutting height and map writing stay refused.
 * Kept in memory only: off at every app start, after [DURATION_MS], on disconnection, or for another robot.
 */
object ModelTrial {
    /** Duration of an armed trial. */
    const val DURATION_MS = 30 * 60_000L
    /** Joystick ceilings during a trial (≈ 0.3 m/s, ≈ 15 °/s), also the bound of [ReadOnlyPolicy.allowsTrial]. */
    const val MAX_LINEAR = 300
    const val MAX_ANGULAR = 150

    /**
     * Armed trial: exact Bluetooth name of the robot and validity (elapsed realtime, ms). [bumperRest]: bumper slot of
     * `sensor_status` (bits 0–2) read at rest when arming, 0 or [BUMPER_REST_ALERT] (see [sensors]).
     */
    data class Armed(val robotName: String, val sinceMs: Long, val untilMs: Long, val bumperRest: Int = 0) {
        fun remainingMs(now: Long) = (untilMs - now).coerceAtLeast(0)
    }

    /**
     * Bumper slot value accepted as the resting value of a trial model: "alert" (1). A LUBA 3 AWD reports it
     * permanently with a free bumper (DumbyLuba#2), which refused the three tests; on the LUBA 2 these bits only mean
     * an alert, never a contact (read through code 1014 and self-check 25, still enforced). The error/obstacle values
     * (2–7) are never accepted.
     */
    const val BUMPER_REST_ALERT = 1

    private val armed = MutableStateFlow<Armed?>(null)
    val state: StateFlow<Armed?> = armed.asStateFlow()

    /** A Mammotion mower recognized by its name and not validated: the trial can be armed for it. */
    fun eligible(name: String?): Boolean =
        !name.isNullOrBlank() && ModelSupport.isMower(name) && !ModelSupport.motionValidated(name)

    /** Arms the trial for [name]; [sensors]: `sensor_status` at rest, whose bumper slot gives [Armed.bumperRest]. */
    fun arm(name: String?, now: Long, sensors: Int? = null): Armed {
        require(eligible(name)) { tr("Trial reserved for a connected mower in read-only mode", "Essai réservé à une tondeuse connectée en lecture seule") }
        val rest = sensors?.let { it and 7 }?.takeIf { it == BUMPER_REST_ALERT } ?: 0
        return Armed(name!!.trim(), now, now + DURATION_MS, rest).also { armed.value = it }
    }

    /**
     * `sensor_status` seen by the guards of the three tests on [name]: unchanged on a validated model or without an
     * active trial; during a trial armed with a bumper at rest in alert ([Armed.bumperRest]), that same value is
     * cleared, so that only an error/obstacle value (2–7) of the bumper slot stops or refuses the tests.
     */
    fun sensors(name: String?, sensors: Int?, now: Long): Int? {
        val rest = armed.value?.takeIf { active(name, now) && !ModelSupport.motionValidated(name) }?.bumperRest ?: 0
        return if (sensors != null && rest != 0 && sensors and 7 == rest) sensors and 7.inv() else sensors
    }

    /** Ends the trial; returns the trial that was armed, if any. */
    fun end(): Armed? = armed.value.also { armed.value = null }

    /** Trial armed for [name] and not expired at [now]. */
    fun active(name: String?, now: Long): Boolean =
        armed.value?.let { name != null && it.robotName == name.trim() && now < it.untilMs } == true

    /** One of the three unlocking tests may run on [name]: validated model, or trial armed for this robot. */
    fun testAllowed(name: String?, now: Long): Boolean = ModelSupport.motionValidated(name) || active(name, now)

    /** Joystick setpoint bounded for a trial (unchanged on a validated model). */
    fun joystick(name: String?, linear: Int, angular: Int): Pair<Int, Int> =
        if (ModelSupport.motionValidated(name)) linear to angular
        else linear.coerceIn(-MAX_LINEAR, MAX_LINEAR) to angular.coerceIn(-MAX_ANGULAR, MAX_ANGULAR)

    /** Tests only. */
    internal fun resetForTests() { armed.value = null }
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
 * Navigation messages must be addressed to the navigation receiver of the connected model ([Commands.navReceiverFor]:
 * `DEV_MAINCTL` on a LUBA 1, `DEV_NAVIGATION` otherwise), or to one of these two when the model is not given.
 */
object ReadOnlyPolicy {
    private val STOP_TASK_ACTIONS = setOf(2, 4, 12)
    /** Receivers of the navigation messages across the Mammotion models ([Commands.navReceiverFor]). */
    private val NAV_RECEIVERS = setOf(MsgDevice.DEV_NAVIGATION, MsgDevice.DEV_MAINCTL)

    /** [navReceiver]: navigation receiver of the connected model, null when unknown (either receiver accepted). */
    fun allows(payload: ByteArray, navReceiver: MsgDevice? = null): Boolean {
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
                if (navReceiver?.let { message.rcver != it } ?: (message.rcver !in NAV_RECEIVERS)) return false
                when (nav.subNavMsgCase) {
                    MctlNav.SubNavMsgCase.TODEV_GETHASH, MctlNav.SubNavMsgCase.APP_REQUEST_COVER_PATHS -> true
                    MctlNav.SubNavMsgCase.TODEV_GET_COMMONDATA ->
                        nav.todevGetCommondata.let { (it.subCmd == 1 && it.action == 8) || it.subCmd == 2 }
                    // Drawing frame ack (map reading): echo only, no content.
                    MctlNav.SubNavMsgCase.TODEV_SVG_MSG -> nav.todevSvgMsg.subCmd == 2 && !nav.todevSvgMsg.hasSvgMessage()
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
    /**
     * Payloads of the three unlocking tests while a [ModelTrial] is armed, on top of [allows]: motion within the trial
     * ceilings (command cutoff test, joystick), manual mowing mode and discs (blade test, with its OFF), knife control
     * (alternative blade command). Everything else stays refused: navigation tasks, height setting, map writing.
     */
    fun allowsTrial(payload: ByteArray, navReceiver: MsgDevice? = null): Boolean {
        if (allows(payload, navReceiver)) return true
        val message = try { LubaMsg.parseFrom(payload) } catch (_: Exception) { return false }
        return when (message.lubaSubMsgCase) {
            LubaMsg.LubaSubMsgCase.DRIVER -> when (message.driver.subDrvMsgCase) {
                MctlDriver.SubDrvMsgCase.TODEV_DEVMOTION_CTRL -> message.driver.todevDevmotionCtrl.let {
                    abs(it.setLinearSpeed) <= ModelTrial.MAX_LINEAR && abs(it.setAngularSpeed) <= ModelTrial.MAX_ANGULAR }
                MctlDriver.SubDrvMsgCase.MOW_CTRL_BY_HAND -> true
                else -> false
            }
            LubaMsg.LubaSubMsgCase.SYS -> message.sys.subSysMsgCase == MctlSys.SubSysMsgCase.TODEV_KNIFE_CTRL
            else -> false
        }
    }
}
