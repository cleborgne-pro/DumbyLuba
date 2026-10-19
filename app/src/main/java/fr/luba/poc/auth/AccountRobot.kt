package fr.luba.poc.auth

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import org.json.JSONArray
import org.json.JSONObject
import fr.luba.poc.i18n.tr

/**
 * Robot details read through the Mammotion account (read-only). Sources: `report_info` protobuf reports
 * received over MQTT (including maintenance and RTK base) and two HTTP reads (mowing history, firmware
 * check). Encodings taken from PyMammotion 0.10.10 and Mammotion-HA 0.6.17: see docs/MAMMOTION_ACCOUNT.md.
 */
data class RobotDetails(
    val sysStatus: Int? = null, val chargeState: Int? = null, val battery: Int? = null,
    val lockState: Int? = null, val batteryHeat: Boolean? = null, val statusAt: Long? = null,
    val task: TaskProgress? = null,
    val rtk: RtkDetails? = null,
    val network: NetworkDetails? = null,
    val firmware: String? = null, val modules: List<FirmwareModule> = emptyList(),
    val maintenance: Maintenance? = null,
    val base: BaseStation? = null,
    val cutterMode: Int? = null, val cutterRpm: Int? = null,
    val updatedAt: Long? = null,
)

/** Current task: `work.area` = percentage << 16 | area m², `work.progress` = remaining << 16 | total (min). */
data class TaskProgress(val percent: Int, val areaM2: Int, val totalMinutes: Int, val leftMinutes: Int,
                        val knifeHeightMm: Int?, val speedMps: Double?) {
    val elapsedMinutes: Int get() = (totalMinutes - leftMinutes).coerceAtLeast(0)
    val active: Boolean get() = totalMinutes > 0 || percent > 0 || areaM2 > 0
}

/** Detailed GNSS quality: L1/L2 satellites extracted from `dis_status`, satellites in common view. */
data class RtkDetails(val status: Int, val ageSeconds: Int, val l1: Int, val l2: Int, val coViewL1: Int, val coViewL2: Int,
                      val roverScore: Int?, val baseScore: Int?, val baseMoved: Boolean?, val rtcmReady: Boolean)

data class NetworkDetails(val usedNet: Int, val wifiRssi: Int?, val mobileRssi: Int?, val downloadKbps: Int?, val uploadKbps: Int?)

data class FirmwareModule(val type: Int, val identify: String, val version: String)

/** Maintenance counters: metres, seconds, cycles (Mammotion-HA units). */
data class Maintenance(val mileageMeters: Long, val workSeconds: Int, val batteryCycles: Int,
                       val bladeUsedSeconds: Int, val bladeWarnSeconds: Int) {
    /** Blade wear relative to the alert threshold set in the Mammotion app (0..1+), null without threshold. */
    val bladeWear: Double? get() = bladeWarnSeconds.takeIf { it > 0 }?.let { bladeUsedSeconds.toDouble() / it }
}

data class BaseStation(val version: String, val status: Int, val connectStatus: Int)

/** One mowing from the account history (`work-report/page`). */
data class WorkRecord(val name: String, val type: Int?, val result: Int?, val areaM2: Double?, val progress: Int?,
                      val minutes: Double?, val start: Long?, val end: Long?) {
    val typeLabel: String get() = when (type) { 1 -> tr("Single task", "Tâche unique"); 2 -> tr("Schedule", "Planning"); 3 -> tr("On-demand mowing", "Tonte à la demande"); 4 -> tr("Resume", "Reprise"); else -> tr("Mowing", "Tonte") }
    val resultLabel: String get() = when (result) { 1 -> tr("in progress", "en cours"); 2 -> tr("paused", "en pause"); 3 -> tr("stopped", "arrêtée"); 4 -> tr("interrupted", "interrompue"); 5 -> tr("finished", "terminée"); else -> tr("unknown", "inconnu") }
}

/** Installed version and available update (`devices/version/check`); [cause] = cloud `causeMsg`. */
data class FirmwareCheck(val current: String?, val latest: String?, val upgradeable: Boolean, val upgrading: Boolean,
                         val progress: Int?, val releaseNote: String?, val cause: String? = null)

object RobotStates {
    /** Labels of `sys_status` / `deviceState` (PyMammotion WorkMode, taken from the Mammotion APK). */
    fun label(state: Int?, chargeState: Int? = null): String = when (state) {
        null -> tr("unknown", "inconnu")
        0 -> tr("not activated", "non activé"); 1 -> tr("online", "en ligne"); 2 -> tr("offline", "hors ligne"); 3 -> tr("powered off", "éteint"); 8 -> tr("disabled", "désactivé"); 10 -> tr("initializing", "initialisation")
        11 -> if (chargeState != null && chargeState != 0) tr("on the base", "sur la base") else tr("ready", "prêt")
        12 -> tr("not connected", "non connecté"); 13 -> tr("mowing", "tonte"); 14 -> tr("returning to base", "retour à la base"); 15 -> tr("charging", "en charge"); 16 -> tr("updating", "mise à jour")
        17 -> tr("locked", "verrouillé"); 18 -> tr("system error", "erreur système"); 19 -> tr("paused", "en pause"); 20 -> tr("manual mowing", "tonte manuelle"); 22 -> tr("update succeeded", "mise à jour réussie")
        23 -> tr("update failed", "échec de mise à jour"); 31 -> tr("drawing zone", "tracé de zone"); 32 -> tr("drawing obstacle", "tracé d'obstacle"); 34 -> tr("drawing channel", "tracé de passage")
        35 -> tr("erasing", "effacement"); 36 -> tr("editing border", "édition de bordure"); 37 -> tr("positioning error", "erreur de positionnement"); 38 -> tr("border skip", "saut de bordure")
        39 -> tr("recharge pause", "pause de recharge"); 43 -> tr("auto erase", "effacement auto"); 44 -> tr("drawing corridor", "tracé de couloir"); 45 -> tr("corridor mowing", "tonte de couloir")
        46 -> tr("corridor pause", "pause couloir"); 47 -> tr("corridor return", "retour couloir"); 48 -> tr("standby", "veille"); 50 -> tr("recharge reset", "recharge réinitialisée")
        51 -> tr("backup", "sauvegarde"); 52 -> tr("restore", "restauration")
        else -> tr("state $state", "état $state")
    }
    fun net(used: Int?): String = when (used) { 1 -> "Wi-Fi"; 2 -> "4G"; 3 -> "4G (TC)"; 0 -> tr("none", "aucun"); else -> "—" }
    fun rtk(status: Int?): String = when (status) { 0 -> tr("none", "aucune"); 1 -> tr("single", "simple"); 2 -> tr("float", "flottante"); 4 -> tr("fixed", "fixe"); null -> "—"; else -> tr("state $status", "état $status") }
}

/** Accumulates the account protobuf reports. Uses read-only fields only. */
class RobotDetailsReducer(initial: RobotDetails = RobotDetails()) {
    var value = initial
        private set

    fun accept(message: LubaMsg, now: Long): Boolean {
        if (!message.hasSys() || !message.sys.hasToappReportData()) return false
        val r = message.sys.toappReportData
        var v = value
        if (r.hasDev()) r.dev.let { d ->
            v = v.copy(sysStatus = d.sysStatus, chargeState = d.chargeState, battery = d.batteryVal.takeIf { it in 0..100 },
                lockState = if (d.hasLockState()) d.lockState.lockState else v.lockState,
                batteryHeat = d.batteryHeatFlag != 0, statusAt = now)
        }
        if (r.hasWork()) r.work.let { w ->
            v = v.copy(task = TaskProgress(percent = (w.area ushr 16).coerceIn(0, 100), areaM2 = w.area and 0xFFFF,
                totalMinutes = w.progress and 0xFFFF, leftMinutes = w.progress ushr 16,
                knifeHeightMm = w.knifeHeight.takeIf { it in 25..70 }, speedMps = w.manRunSpeed.takeIf { it > 0 }?.let { it / 100.0 }))
        }
        if (r.hasRtk()) r.rtk.let { k ->
            val score = if (k.hasScoreInfo()) k.scoreInfo else null
            v = v.copy(rtk = RtkDetails(k.status, k.age, ((k.disStatus shr 16) and 0xFF).toInt(), ((k.disStatus shr 24) and 0xFF).toInt(),
                k.coViewStars and 0xFF, (k.coViewStars shr 8) and 0xFF, score?.roverScore, score?.baseScore,
                score?.let { it.baseMoved != 0 }, k.rtcmReady))
        }
        if (r.hasConnect()) r.connect.let { c ->
            fun rssi(raw: Int) = raw.takeIf { it in -126..-1 }
            v = v.copy(network = NetworkDetails(c.usedNetValue, rssi(c.wifiRssi), rssi(c.mnetRssi),
                if (c.hasDevNetSpeed()) c.devNetSpeed.download else null, if (c.hasDevNetSpeed()) c.devNetSpeed.upload else null))
        }
        if (r.hasFwInfo()) r.fwInfo.let { f ->
            v = v.copy(firmware = f.version.ifBlank { v.firmware },
                modules = f.modList.filter { it.version.isNotBlank() }.map { FirmwareModule(it.type, it.identify, it.version) }.ifEmpty { v.modules })
        }
        if (r.hasMaintain()) r.maintain.let { m ->
            v = v.copy(maintenance = Maintenance(m.mileage, m.workTime, m.batCycles,
                if (m.hasBladeUsedTime()) m.bladeUsedTime.bladeUsedTime else 0, if (m.hasBladeUsedTime()) m.bladeUsedTime.bladeUsedWarnTime else 0))
        }
        if (r.hasBasestationInfo()) r.basestationInfo.let { b ->
            v = v.copy(base = BaseStation("${b.verMajor}.${b.verMinor}.${b.verPatch}.${b.verBuild}", b.basestationStatus, b.connectStatusSincePoweron))
        }
        if (r.hasCutterWorkModeInfo()) v = v.copy(cutterMode = r.cutterWorkModeInfo.currentCutterMode,
            cutterRpm = r.cutterWorkModeInfo.currentCutterRpm.takeIf { it > 0 } ?: v.cutterRpm)
        value = v.copy(updatedAt = now)
        return true
    }
}

/** Defensive decoding of the account HTTP responses (missing fields tolerated). */
object AccountJson {
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null
    private fun JSONObject.num(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null
    /** Timestamp in seconds or milliseconds, converted to milliseconds. */
    private fun millis(raw: Long?) = raw?.let { if (it < 100_000_000_000L) it * 1000 else it }

    private fun records(data: Any?): JSONArray = when (data) {
        is JSONArray -> data
        is JSONObject -> data.optJSONArray("records") ?: data.optJSONArray("list") ?: JSONArray()
        else -> JSONArray()
    }

    fun workRecords(response: JSONObject): List<WorkRecord> {
        val a = records(response.opt("data"))
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            WorkRecord(o.text("workName") ?: tr("Mowing", "Tonte"), o.int("workType"), o.int("workResult"), o.num("workArea"),
                o.int("workProgress"), o.num("workTimeUsed"), millis(o.long("startWorkTime")), millis(o.long("endWorkTime")))
        }.sortedByDescending { it.start ?: 0L }
    }

    fun firmware(response: JSONObject, iotId: String): FirmwareCheck? {
        val a = records(response.opt("data"))
        val o = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("deviceId") == iotId }
            ?: a.optJSONObject(0) ?: return null
        val info = o.optJSONObject("productVersionInfoVo")
        return FirmwareCheck(o.text("currentVersion"), info?.text("releaseVersion"), o.optBoolean("upgradeable"),
            o.optBoolean("isupgrading"), o.int("progress"), info?.text("releaseNote"), o.text("causeMsg"))
    }
}
