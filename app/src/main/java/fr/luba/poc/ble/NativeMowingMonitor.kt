@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observation of a mowing run by the robot firmware: checks, progress, interruption and persistence (no command sent).
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class NativeMowingMonitor(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private val log get() = host.log
    private val state get() = host.state

    private var mowPathTransfer: MowPathTransfer? = null
    private var mowPathDeadline = 0L
    private var mowJob: Job? = null
    private var mowOrigin: Point? = null
    private var mowDeadline = 0L
    private var mowCommandAt = 0L
    private var mowExpectedAction: Int? = null

    fun recordMowingCheck(key: String, checked: Boolean) {
        if (key !in setOf("start", "turns", "dock", "restart")) return
        update { it.copy(mowing = it.mowing.copy(manualChecks = if (checked) it.mowing.manualChecks + key else it.mowing.manualChecks - key)) }
        persistMowing()
    }

    internal fun receiveMowing(message: LubaMsg) {
        mowPathTransfer?.let { transfer ->
            try {
                transfer.accept(message, commands).forEach { host.send(it, "MOW_PATH_READ") }
                update { it.copy(mowing = it.mowing.copy(paths = transfer.paths(), pathStatus =
                    if (transfer.complete) tr("Firmware route: ${transfer.paths().size} lines", "Parcours firmware : ${transfer.paths().size} lignes") else tr("Reading the firmware route…", "Lecture du parcours firmware…"))) }
                if (transfer.complete) { mowPathTransfer = null; persistMowing() }
            } catch (error: Exception) {
                mowPathTransfer = null
                update { it.copy(mowing = it.mowing.copy(pathStatus = tr("Route unavailable: ${error.message}", "Parcours indisponible : ${error.message}"))) }
                event("mowing_path_error", error.message ?: tr("Invalid route", "Parcours invalide"))
            }
        }

        if (state.value.mowing.active && message.hasNav() && message.nav.hasTodevTaskctrlAck()) {
            val ack = message.nav.todevTaskctrlAck
            event("mowing_task_ack", tr("Task response: action=${ack.action}, result=${ack.result}, state=${ack.navState}", "Retour tâche : action=${ack.action}, résultat=${ack.result}, état=${ack.navState}"))
            MowTaskAck.rejection(mowExpectedAction, ack.action, ack.result)?.let { reason ->
                host.requestStop(true, reason)
                return
            }
            if (ack.action == mowExpectedAction && ack.result == 0) mowExpectedAction = null
        }
        if (state.value.mowing.phase != MowPhase.PREPARING || !message.hasNav() || !message.nav.hasBidireReqconverPath()) return
        val route = message.nav.bidireReqconverPath
        val config = state.value.mowing.config ?: return
        // An old configuration / an empty echo does not validate the new preparation.
        if (!MowPreparation.matches(route, config)) return
        event("mowing_route_response", tr("Preparation response", "Réponse préparation"), mapOf("result" to route.result, "pathHash" to route.pathHash))
        if (route.result != 0 || route.pathHash in setOf(0L, 1L)) {
            interruptMowing(tr("Route not confirmed (result=${route.result}, hash=${route.pathHash})", "Parcours non confirmé (résultat=${route.result}, hash=${route.pathHash})")); return
        }
        update { it.copy(mowing = it.mowing.copy(phase = MowPhase.PREPARED, status = MowPhase.PREPARED.label)) }
        mowDeadline = 0
        mowPathTransfer = MowPathTransfer(Clock.now())
        mowPathDeadline = SystemClock.elapsedRealtime() + 60_000
        update { it.copy(mowing = it.mowing.copy(pathStatus = tr("Reading the firmware route…", "Lecture du parcours firmware…"))) }
        requireNotNull(mowPathTransfer).start(commands).forEach { host.send(it, "MOW_PATH_READ") }
        persistMowing()
    }

    internal fun observeMowing() {
        if (!state.value.mowing.running) return
        val t = state.value.telemetry
        val deviceAt = t.lastDeviceAt ?: return
        if (deviceAt <= mowCommandAt) return
        val positionFresh = t.lastPositionAt?.let { Clock.now() - it in 0..2000 } == true
        val distance = if (positionFresh && t.x != null && t.y != null) mowOrigin?.distance(Point(t.x, t.y)) else null
        val before = state.value.mowing
        val after = MowObservation.accept(before, t, distance, deviceAt)
        update { it.copy(mowing = after) }
        if (after.phase != before.phase && after.phase in setOf(MowPhase.WORKING, MowPhase.PAUSED, MowPhase.RETURNING, MowPhase.DOCKED, MowPhase.UNDOCKED)) mowDeadline = 0
        if (after.phase == MowPhase.DOCKED && before.phase != MowPhase.DOCKED && positionFresh && t.x != null && t.y != null) {
            mowOrigin = Point(t.x, t.y)
        }
        if (after != before) persistMowing()
    }

    private fun monitorMowing() {
        if (mowJob?.isActive == true) return
        mowJob = host.viewModelScope.launch {
            while (state.value.mowing.active) {
                delay(250)
                if (mowPathTransfer != null && SystemClock.elapsedRealtime() > mowPathDeadline) {
                    mowPathTransfer = null
                    update { it.copy(mowing = it.mowing.copy(pathStatus = tr("Route reading timed out — route not confirmed", "Lecture du parcours expirée — trajet non confirmé"))) }
                }
                val t = state.value.telemetry
                val failure = MowSafety.failure(state.value.selected?.name, state.value.ready && !closing && log.healthy,
                    false, t.copy(sensorStatus = host.guardSensors(t.sensorStatus)), Clock.now())
                if (failure != null) { host.fail(tr("Mowing: $failure", "Tonte : $failure")); break }
                if (mowDeadline != 0L && SystemClock.elapsedRealtime() > mowDeadline) {
                    host.fail(tr("Mowing: transition not confirmed in time", "Tonte : transition non confirmée dans le délai")); break
                }
            }
        }
    }

    fun finishMowing() { host.alerts.intentionalAlertStop(); host.requestStop(true, tr("End of mowing supervision", "Fin de supervision de tonte"), intentional=true) }

    internal fun interruptMowing(reason: String) {
        mowExpectedAction = null
        mowPathTransfer = null
        mowJob?.cancel(); mowJob = null; mowDeadline = 0
        if (!state.value.mowing.active) return
        update { it.copy(mowing = it.mowing.copy(phase = MowPhase.STOPPED, status = reason)) }
        persistMowing()
    }

    private fun persistMowing() {
        val m = state.value.mowing
        if (m.id.isBlank()) return
        log.report(m.id, mapOf("id" to m.id, "kind" to "native_mowing",
            "outcome" to if (m.active) "RUNNING" else "INTERRUPTED_OFF_UNCONFIRMED", "step" to m.status, "phase" to m.phase.name,
            "pathStatus" to m.pathStatus, "nativePaths" to m.paths.map { line -> line.map { mapOf("x" to it.x, "y" to it.y) } },
            "status" to m.status, "zoneIds" to m.config?.zones, "heightMm" to m.config?.heightMm,
            "checkerboard" to m.config?.checkerboard, "spacingCm" to m.config?.spacingCm, "speedMps" to m.config?.speedMps,
            "movementObserved" to m.moved, "mowingObserved" to m.mowingObserved,
            "returningObserved" to m.returningObserved, "dockContactObserved" to m.dockedObserved,
            "departureObserved" to m.leftDockObserved, "manualChecks" to m.manualChecks.toList(),
            "observations" to m.observations, "firmware" to state.value.telemetry.firmware,
            "physicalValidation" to tr("Local observations required; no automatic certification", "Observations locales requises ; aucune certification automatique")))
        event("mowing_observation", m.status, mapOf("phase" to m.phase.name, "missionId" to m.id))
    }
}
