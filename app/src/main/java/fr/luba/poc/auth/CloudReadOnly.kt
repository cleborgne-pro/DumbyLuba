package fr.luba.poc.auth
import fr.luba.poc.proto.LubaMsgProto.*
import fr.luba.poc.i18n.tr
/**
 * Explicit barrier: the POC cloud can only send map reads/ACKs, bounded telemetry subscriptions and the
 * video command. The only navigation exceptions: cancelling the native return (action 12, a stop command,
 * always allowed) and the native return to base (action 5), accepted only on the user's explicit choice in
 * "Charging station" ([nativeReturn]). Native mowing, resume, task pause/cancel and leaving the base stay refused.
 */
object CloudReadOnly {
    const val NATIVE_RETURN_ACTION = 5
    const val CANCEL_RETURN_ACTION = 12
    fun requireAllowed(bytes:ByteArray, nativeReturn:Boolean=false) {
        val m=LubaMsg.parseFrom(bytes)
        val allowed=when(m.msgtype) {
            MsgCmdType.MSG_CMD_TYPE_ESP -> m.hasNet() && m.net.allFields.size==1 && m.net.todevBleSync==3
            MsgCmdType.MSG_CMD_TYPE_NAV -> m.hasNav() && m.nav.allFields.size==1 && when {
                m.nav.hasTodevGethash() -> m.nav.todevGethash.let { it.pver==1 && it.subCmd in listOf(0,2) }
                m.nav.hasTodevGetCommondata() -> m.nav.todevGetCommondata.let { it.pver==1 && it.action==8 && it.subCmd in listOf(1,2) }
                m.nav.hasTodevSvgMsg() -> m.nav.todevSvgMsg.let { it.pver==1 && it.subCmd==2 && !it.hasSvgMessage() }
                m.nav.hasTodevTaskctrl() -> m.rcver==MsgDevice.DEV_NAVIGATION && m.nav.todevTaskctrl.let { it.allFields.size<=3 && it.type==1 && it.result==0 &&
                    (it.action==CANCEL_RETURN_ACTION || (nativeReturn && it.action==NATIVE_RETURN_ACTION)) }
                else -> false
            }
            // Telemetry subscriptions: only the exact Commands.reports configurations
            // (with or without maintenance and RTK base), no other system command.
            MsgCmdType.MSG_CMD_TYPE_EMBED_SYS -> m.hasSys() && m.sys.allFields.size==1 && m.sys.hasTodevReportCfg() &&
                m.sys.todevReportCfg in allowedReports
            // Camera: only vi_switch (LEFT position) and the encoder restart, no movement.
            MsgCmdType.MSG_CMD_TYPE_MUL -> m.hasMul() && m.rcver == MsgDevice.SOC_MODULE_MULTIMEDIA && m.mul.allFields.size == 1 && when {
                m.mul.hasSetVideo() -> m.mul.setVideo.viSwitch in 0..1 && m.mul.setVideo.position == fr.luba.poc.proto.LubaMulProto.MUL_CAMERA_POSITION.LEFT
                m.mul.hasReqEncode() -> m.mul.reqEncode.encode
                else -> false
            }
            else -> false
        }
        require(allowed) { tr("Cloud command forbidden: map reading, telemetry, camera and native return to base only", "Commande cloud interdite : lecture carte, télémétrie, caméra et retour natif à la base uniquement") }
    }
    private val allowedReports by lazy {
        val c=fr.luba.poc.protocol.Commands()
        listOf(false,true).flatMap { keep -> listOf(false,true).map { account ->
            LubaMsg.parseFrom(c.reports(keep, account = account)).sys.todevReportCfg } }
    }
}
