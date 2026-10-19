package fr.luba.poc.camera

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.i18n.tr

/**
 * Video state read from the robot telemetry (read-only):
 * - `rpt_dev_status.fpv_info` (recent firmware): `fpv_flag` = 1 when the robot already publishes the video. At 0, the
 *   token request is not enough (measured on 2026-10-13: `fpv_flag` stays at 0 after a `vi_switch=0`, no image):
 *   `vi_switch=1` is sent, as when the field is missing (old firmware) or without a device report;
 * - `rpt_connect_status.used_net`: over 4G (MNET) the encoder stops without `refresh_fpv` every 3 s;
 * - `SocMul.set_video_ack`: answer of the multimedia module to `vi_switch`.
 */
data class CameraRobotState(
    /** null: no device report received; false: report received without `fpv_info` (old firmware). */
    val fpvInfo: Boolean? = null,
    val fpvFlag: Int? = null, val wifiAvailable: Int? = null, val mobileAvailable: Int? = null,
    val usedNet: Int? = null,
    val lastAck: Int? = null, val lastAckAt: Long? = null,
) {
    /**
     * Publishing to request with `vi_switch=1` after the token, unless the robot already reports the video as active
     * (`fpv_flag` = 1): old firmware without `fpv_info`, FPV inactive (`fpv_flag` 0, for example after a stop) or
     * unknown state (no device report, robot reached through the cloud only).
     */
    val needsVideoSwitch: Boolean get() = fpvFlag != 1
    /** Robot on 4G: periodic `refresh_fpv` needed. */
    val mobileStream: Boolean get() = usedNet == 2 || usedNet == 3
    val fpvLabel: String get() = when {
        fpvInfo == null -> tr("unknown (no device report)", "inconnu (aucun rapport appareil)")
        fpvInfo == false -> tr("missing (old firmware: vi_switch needed)", "absent (ancien firmware : vi_switch nécessaire)")
        else -> when (fpvFlag) { 0 -> tr("no FPV", "pas de FPV"); 1 -> tr("available", "disponible"); 2 -> tr("error", "erreur"); else -> tr("state $fpvFlag", "état $fpvFlag") } +
            " · Wi-Fi ${availability(wifiAvailable)} · 4G ${availability(mobileAvailable)}"
    }
    val netLabel: String get() = when (usedNet) { null -> tr("unknown", "inconnu"); 0 -> tr("none", "aucun"); 1 -> "Wi-Fi"; 2 -> "4G"; 3 -> "4G (TC)"; else -> "type $usedNet" }

    companion object {
        private fun availability(v: Int?) = when (v) { null -> "?"; 0 -> tr("no", "non"); else -> tr("yes", "oui") }
        fun ackLabel(code: Int?): String = when (code) {
            null -> "—"
            0 -> tr("accepted", "accepté")
            1 -> tr("activation failed", "activation impossible")
            2 -> tr("network unavailable", "réseau indisponible")
            3 -> tr("channel creation failed", "création du canal impossible")
            4 -> tr("invalid parameter", "paramètre invalide")
            else -> "code $code"
        }
    }
}

class CameraRobotObserver {
    var value = CameraRobotState()
        private set

    /** True if the video state changed. */
    fun accept(message: LubaMsg, now: Long): Boolean {
        val before = value
        if (message.hasSys() && message.sys.hasToappReportData()) {
            val report = message.sys.toappReportData
            if (report.hasDev()) {
                val dev = report.dev
                value = if (dev.hasFpvInfo()) value.copy(fpvInfo = true, fpvFlag = dev.fpvInfo.fpvFlag,
                    wifiAvailable = dev.fpvInfo.wifiAvailable, mobileAvailable = dev.fpvInfo.mobileNetAvailable)
                else value.copy(fpvInfo = value.fpvInfo ?: false)
            }
            if (report.hasConnect()) value = value.copy(usedNet = report.connect.usedNetValue)
        }
        if (message.hasMul() && message.mul.hasSetVideoAck()) value = value.copy(lastAck = message.mul.setVideoAck.errorCodeValue, lastAckAt = now)
        return value != before
    }

    fun reset() { value = CameraRobotState() }
}
