package fr.luba.poc.auth

import fr.luba.poc.proto.DevNetProto.DrvUpgradeReport
import org.json.JSONObject
import fr.luba.poc.i18n.tr

/*
 * Two Mammotion account actions, on explicit user request and without a BLE link: the native return to
 * base (action 5 of `todev_taskctrl`) and the firmware update (`ota/device/upgrade`). Pure logic: the
 * MQTT/HTTP connection stays in CloudViewModel.
 * Encodings taken from PyMammotion 0.10.10 (events.py, properties.py, device.py).
 */

// ---------------------------------------------------------------------------------------------
// Native return to base through the account
// ---------------------------------------------------------------------------------------------

enum class CloudReturnPhase { IDLE, CONNECTING, WAKING, REQUESTED, RETURNING, DOCKED, CANCELLED, FAILED }

data class CloudReturnUi(val phase: CloudReturnPhase = CloudReturnPhase.IDLE, val status: String = "", val updatedAt: Long? = null) {
    val active: Boolean get() = phase in setOf(CloudReturnPhase.CONNECTING, CloudReturnPhase.WAKING, CloudReturnPhase.REQUESTED, CloudReturnPhase.RETURNING)
    /** The robot is running (or about to run) the return: cancelling through the account makes sense. */
    val cancellable: Boolean get() = phase in setOf(CloudReturnPhase.REQUESTED, CloudReturnPhase.RETURNING)
}

/**
 * Tracks the native return from the `report_info.dev` reports received through the account (same rules as
 * the BLE native return): state 14 = returning, charging contact = finished. Timeouts allow for the cloud
 * latency (a report every few seconds).
 */
class CloudReturnTracker(private val requestedAt: Long, alreadyReturning: Boolean = false) {
    var value = CloudReturnUi(CloudReturnPhase.REQUESTED, tr("Native Mammotion return requested through the account; waiting for the return state", "Retour natif Mammotion demandé par le compte ; attente de l’état retour"), requestedAt)
        private set
    private var returning = alreadyReturning
    private var cancelRequested = false
    init { if (alreadyReturning) value = CloudReturnUi(CloudReturnPhase.RETURNING, tr("Mammotion return already in progress · followed through the account", "Retour Mammotion déjà en cours · suivi par le compte"), requestedAt) }

    val done: Boolean get() = !value.active

    @Synchronized fun cancelRequested(now: Long) {
        if (done) return
        cancelRequested = true
        value = value.copy(status = tr("Return cancellation sent through the account; waiting for the robot", "Annulation du retour envoyée par le compte ; attente du robot"), updatedAt = now)
    }

    /** State report received at [reportAt] (wall clock). Ignores reports older than the request. */
    @Synchronized fun accept(sysStatus: Int?, chargeState: Int?, reportAt: Long, now: Long = reportAt): CloudReturnUi {
        if (done || reportAt < requestedAt) return value
        value = when {
            chargeState in CHARGING -> CloudReturnUi(CloudReturnPhase.DOCKED, tr("Native return finished: charging contact confirmed", "Retour natif terminé : contact de charge confirmé"), now)
            sysStatus == RETURNING_STATE && !returning -> {
                returning = true
                CloudReturnUi(CloudReturnPhase.RETURNING, tr("Native Mammotion return in progress · can be canceled here", "Retour natif Mammotion en cours · annulation possible ici"), now)
            }
            sysStatus == RETURNING_STATE -> value.copy(updatedAt = now)
            returning && sysStatus != null && cancelRequested -> CloudReturnUi(CloudReturnPhase.CANCELLED, tr("Return canceled: robot stopped (${RobotStates.label(sysStatus, chargeState)})", "Retour annulé : robot arrêté (${RobotStates.label(sysStatus, chargeState)})"), now)
            returning && sysStatus != null -> CloudReturnUi(CloudReturnPhase.FAILED, tr("Native return interrupted (${RobotStates.label(sysStatus, chargeState)}) without charging contact", "Retour natif interrompu (${RobotStates.label(sysStatus, chargeState)}) sans contact de charge"), now)
            else -> value
        }
        return value
    }

    /** Timeouts: return not started, tracking too long. */
    @Synchronized fun tick(now: Long): CloudReturnUi {
        if (done) return value
        value = when {
            cancelRequested && !returning && now - requestedAt > START_TIMEOUT_MS -> CloudReturnUi(CloudReturnPhase.CANCELLED, tr("Return canceled before it started", "Retour annulé avant son démarrage"), now)
            !returning && now - requestedAt > START_TIMEOUT_MS -> CloudReturnUi(CloudReturnPhase.FAILED, tr("Native return not started by the robot (no return state within ${START_TIMEOUT_MS / 1000} s)", "Retour natif non démarré par le robot (aucun état retour en ${START_TIMEOUT_MS / 1000} s)"), now)
            now - requestedAt > MAX_DURATION_MS -> CloudReturnUi(CloudReturnPhase.FAILED, tr("Native return taking too long: check the robot", "Retour natif trop long : vérifier le robot"), now)
            else -> value
        }
        return value
    }

    companion object {
        const val RETURNING_STATE = 14
        val CHARGING = setOf(1, 2)
        /** The account relays states several seconds late: 45 s instead of the 15 s used over BLE. */
        const val START_TIMEOUT_MS = 45_000L
        const val MAX_DURATION_MS = 30 * 60_000L
    }
}

// ---------------------------------------------------------------------------------------------
// Firmware update
// ---------------------------------------------------------------------------------------------

/**
 * Progress frame (`toapp_upgrade_report` protobuf or `otaProgress` MQTT property).
 * [result]: 0 = finished successfully, 1 or negative = failure, 2 = in progress (PyMammotion events.py).
 */
data class OtaProgress(val progress: Int, val result: Int, val version: String = "", val otaId: String = "",
                       val message: String = "", val component: String = "") {
    val complete: Boolean get() = result == 0
    val failed: Boolean get() = result == 1 || result < 0
}

object OtaProgressJson {
    /** Missing ≠ success: a missing `result` field means "in progress", like PyMammotion. */
    private const val IN_PROGRESS = 2

    /**
     * Property posted on `…/thing/event/property/post`: flat Mammotion form (`params.otaProgress`, object or
     * JSON string) or Aliyun form (`params.items.otaProgress.value`).
     */
    fun parse(payload: String): OtaProgress? = runCatching {
        val params = JSONObject(payload).optJSONObject("params") ?: return null
        val raw = params.opt("otaProgress") ?: params.optJSONObject("items")?.optJSONObject("otaProgress")?.opt("value") ?: return null
        val o = when (raw) {
            is JSONObject -> raw
            is String -> JSONObject(raw)
            else -> return null
        }
        if (!o.has("progress") && !o.has("result")) return null
        OtaProgress(o.optInt("progress", 0).coerceIn(0, 100), if (o.has("result") && !o.isNull("result")) o.optInt("result", IN_PROGRESS) else IN_PROGRESS,
            o.optString("version"), o.optString("otaId"), o.optString("message"))
    }.getOrNull()

    /** `deviceState` (sys_status) posted as a property: flat or Aliyun form `items.deviceState.value`. */
    fun deviceState(payload: String): Int? = runCatching {
        val params = JSONObject(payload).optJSONObject("params") ?: return null
        val raw = if (params.has("deviceState")) params.opt("deviceState")
            else params.optJSONObject("items")?.optJSONObject("deviceState")?.opt("value") ?: return null
        when (raw) { is Number -> raw.toInt(); is String -> raw.trim().toIntOrNull(); else -> null }
    }.getOrNull()

    fun fromReport(r: DrvUpgradeReport): OtaProgress =
        OtaProgress(r.progress.coerceIn(0, 100), r.result, r.version, r.otaid, r.message, r.devname)
}

enum class FirmwarePhase { IDLE, REQUESTED, RUNNING, DONE, FAILED, LOST }

data class FirmwareUpdateUi(val phase: FirmwarePhase = FirmwarePhase.IDLE, val target: String? = null, val progress: Int? = null,
                            val stage: String = "", val status: String = "", val startedAt: Long? = null, val updatedAt: Long? = null) {
    val active: Boolean get() = phase == FirmwarePhase.REQUESTED || phase == FirmwarePhase.RUNNING
}

/**
 * Update progress from all sources: robot frames (MQTT/protobuf), `sys_status` state (16 in progress,
 * 22 succeeded, 23 failed) and HTTP re-read of `devices/version/check`. Progress is monotonic within one
 * `otaId`; the HTTP re-read, which lags behind the robot, ends the tracking only when the installed
 * version is the target version.
 */
class FirmwareUpdateTracker(private val target: String?, private val startedAt: Long, alreadyRunning: Boolean = false) {
    var value = FirmwareUpdateUi(if (alreadyRunning) FirmwarePhase.RUNNING else FirmwarePhase.REQUESTED, target, null, "",
        if (alreadyRunning) tr("Update in progress on the robot · followed through the account", "Mise à jour en cours sur le robot · suivi par le compte") else tr("Update requested; waiting for the robot to download it", "Mise à jour demandée ; attente du téléchargement par le robot"),
        startedAt, startedAt)
        private set
    private var otaId = ""
    private var lastSignal = startedAt

    val done: Boolean get() = !value.active

    fun accept(p: OtaProgress, now: Long): FirmwareUpdateUi {
        if (done) return value
        lastSignal = now
        val sameJob = p.otaId.isBlank() || otaId.isBlank() || p.otaId == otaId
        if (p.otaId.isNotBlank()) otaId = p.otaId
        val floor = if (sameJob) value.progress ?: 0 else 0
        val version = p.version.ifBlank { target.orEmpty() }
        value = when {
            p.complete -> value.copy(phase = FirmwarePhase.DONE, progress = 100, stage = "",
                status = tr("Update finished", "Mise à jour terminée") + (version.takeIf { it.isNotBlank() }?.let { tr(": version $it installed", " : version $it installée") } ?: ""), updatedAt = now)
            p.failed -> value.copy(phase = FirmwarePhase.FAILED, progress = maxOf(floor, p.progress), stage = p.message,
                status = tr("Update failed (code ${p.result})", "Échec de la mise à jour (code ${p.result})") + (p.message.takeIf { it.isNotBlank() }?.let { tr(": $it", " : $it") } ?: ""), updatedAt = now)
            else -> value.copy(phase = FirmwarePhase.RUNNING, progress = maxOf(floor, p.progress), stage = stage(p),
                status = tr("Update in progress", "Mise à jour en cours"), updatedAt = now)
        }
        return value
    }

    /** Robot `sys_status`: 16 updating, 22 succeeded, 23 failed. */
    fun acceptState(sysStatus: Int?, now: Long): FirmwareUpdateUi {
        if (done || sysStatus == null) return value
        value = when (sysStatus) {
            UPGRADING -> { lastSignal = now; value.copy(phase = FirmwarePhase.RUNNING, status = tr("Update in progress", "Mise à jour en cours"), updatedAt = now) }
            UPGRADE_OK -> { lastSignal = now; value.copy(phase = FirmwarePhase.DONE, progress = 100, stage = "", status = tr("Update succeeded (robot)", "Mise à jour réussie (robot)"), updatedAt = now) }
            UPGRADE_FAILED -> { lastSignal = now; value.copy(phase = FirmwarePhase.FAILED, status = tr("Update failed (robot)", "Échec de la mise à jour (robot)"), updatedAt = now) }
            else -> value
        }
        return value
    }

    /** HTTP re-read: progress if the cloud knows it; ends only when the target version is installed. */
    fun acceptCheck(c: FirmwareCheck, now: Long): FirmwareUpdateUi {
        if (done) return value
        value = when {
            target != null && c.current == target && !c.upgrading -> {
                lastSignal = now
                value.copy(phase = FirmwarePhase.DONE, progress = 100, stage = "", status = tr("Update finished: version $target installed", "Mise à jour terminée : version $target installée"), updatedAt = now)
            }
            c.upgrading -> {
                lastSignal = now
                value.copy(phase = FirmwarePhase.RUNNING, progress = maxOf(value.progress ?: 0, c.progress ?: 0).takeIf { it > 0 || value.progress != null },
                    status = tr("Update in progress", "Mise à jour en cours"), updatedAt = now)
            }
            else -> value
        }
        return value
    }

    fun tick(now: Long): FirmwareUpdateUi {
        if (done) return value
        value = when {
            now - startedAt > MAX_DURATION_MS -> value.copy(phase = FirmwarePhase.LOST, status = tr("Tracking stopped after ${MAX_DURATION_MS / 60_000} min: check the version in the Mammotion app", "Suivi arrêté après ${MAX_DURATION_MS / 60_000} min : vérifier la version dans l’app Mammotion"), updatedAt = now)
            value.phase == FirmwarePhase.REQUESTED && now - lastSignal > START_TIMEOUT_MS -> value.copy(phase = FirmwarePhase.LOST,
                status = tr("No progress received within ${START_TIMEOUT_MS / 60_000} min: the update may have been refused (battery, network, robot busy)", "Aucune progression reçue en ${START_TIMEOUT_MS / 60_000} min : mise à jour peut-être refusée (batterie, réseau, robot occupé)"), updatedAt = now)
            now - lastSignal > SILENCE_TIMEOUT_MS -> value.copy(phase = FirmwarePhase.LOST,
                status = tr("No news from the robot for ${SILENCE_TIMEOUT_MS / 60_000} min: check the installed version", "Plus de nouvelles du robot depuis ${SILENCE_TIMEOUT_MS / 60_000} min : vérifier la version installée"), updatedAt = now)
            else -> value
        }
        return value
    }

    private fun stage(p: OtaProgress): String {
        val msg = p.message.trim()
        return when {
            msg.startsWith("dl", ignoreCase = true) -> tr("Downloading", "Téléchargement") + (p.component.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            msg.isNotBlank() -> msg.take(80)
            p.component.isNotBlank() -> p.component
            else -> ""
        }
    }

    companion object {
        const val UPGRADING = 16
        const val UPGRADE_OK = 22
        const val UPGRADE_FAILED = 23
        const val START_TIMEOUT_MS = 10 * 60_000L
        /** The robot restarts to install: several minutes of silence are normal. */
        const val SILENCE_TIMEOUT_MS = 30 * 60_000L
        const val MAX_DURATION_MS = 120 * 60_000L
    }
}

object FirmwareUpdatePolicy {
    /** States where the robot is working or moving: no update (mowing, return, pause, corridor, update). */
    private val BUSY = setOf(13, 14, 16, 19, 20, 39, 45, 46, 47)

    /** Reason for refusal, or null if the update can be started. */
    fun refusal(check: FirmwareCheck?, details: RobotDetails): String? = when {
        check == null -> tr("Read the account first (Refresh from the account)", "Lisez d’abord le compte (Actualiser depuis le compte)")
        check.upgrading -> tr("An update is already in progress", "Une mise à jour est déjà en cours")
        !check.upgradeable || check.latest.isNullOrBlank() -> tr("No update available", "Aucune mise à jour disponible")
        details.sysStatus in BUSY -> tr("Robot busy (${RobotStates.label(details.sysStatus, details.chargeState)}): wait for the task to finish", "Robot occupé (${RobotStates.label(details.sysStatus, details.chargeState)}) : attendre la fin de la tâche")
        else -> null
    }
}
