@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manual driving with the joystick: gestures, latches ignored on request, contact handling and stop.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class ManualDrive(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var bladeOffPending
        get() = host.bladeOffPending
        set(value) { host.bladeOffPending = value }
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var current: RobotBle.Operation?
        get() = host.current
        set(value) { host.current = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private val difficulties get() = host.difficulties
    private var heightTarget: Int?
        get() = host.heightTarget
        set(value) { host.heightTarget = value }
    private val log get() = host.log
    private var manualContactUntil
        get() = host.manualContactUntil
        set(value) { host.manualContactUntil = value }
    private var manualLastLinear
        get() = host.manualLastLinear
        set(value) { host.manualLastLinear = value }
    private var periodic: Job?
        get() = host.periodic
        set(value) { host.periodic = value }
    private val queue get() = host.queue
    private val state get() = host.state

    private var manualJob: Job? = null
    private var manualCommand = 0 to 0
    private var manualInputAt = 0L
    private var manualBlocked = true

    /** Input refreshed by the finger; BLE sending at 5 Hz with independent guards. */
    fun beginManualGesture() { manualBlocked = false }

    /**
     * Self-check 25 ignored for a blade-OFF move (joystick, move to a point): robot beyond the mapped zones, bumper and
     * lift clear ([fr.luba.poc.protocol.OffMapLatch]).
     */
    internal fun offMapLatchIgnored(t: Telemetry): Boolean =
        OffMapLatch.ignored(t.selfCheck, host.guardSensors(t.sensorStatus),
            PointPlanner.beyondMapped(state.value.robotMap, t.x?.let { x -> t.y?.let { y -> Point(x, y) } }))

    /**
     * Self-check 25 ignored for the joystick: robot beyond the mapped zones or at a no-go zone, bumper and lift clear
     * ([fr.luba.poc.protocol.OffMapLatch.atEdge]). 2026-10-25, 15:33: rear of the chassis inside a no-go zone after a
     * pivot, back-off impossible, joystick refused: the only way out was forward, by hand.
     */
    internal fun edgeLatchIgnored(t: Telemetry): Boolean =
        OffMapLatch.ignored(t.selfCheck, host.guardSensors(t.sensorStatus),
            OffMapLatch.atEdge(state.value.robotMap, t.x?.let { x -> t.y?.let { y -> Point(x, y) } }))

    fun manualInput(linear: Int, angular: Int): Boolean {
        if (state.value.robotRoute.running) return false
        try {
            check(!manualBlocked) { tr("New joystick press required", "Nouvel appui joystick requis") }
            check(linear in -Commands.JOYSTICK_MAX_LINEAR..Commands.JOYSTICK_MAX_LINEAR &&
                angular in -Commands.JOYSTICK_MAX_ANGULAR..Commands.JOYSTICK_MAX_ANGULAR) { tr("Joystick out of bounds", "Joystick hors limites") }
            val t = state.value.telemetry
            check(!state.value.taskStopPending && state.value.ready && !closing && log.healthy && !state.value.mowing.active &&
                !state.value.navTest.running && !state.value.teach.running && !state.value.borderFrontal.running && !state.value.campaign.running &&
                !state.value.bladeTestActive && !state.value.mapLoading && heightTarget == null &&
                !bladeMayBeOn && !bladeOffPending) { tr("Finish the mission or test in progress", "Terminer la mission ou l'essai en cours") }
            val robotName = state.value.selected?.name
            check(ModelTrial.testAllowed(robotName, SystemClock.elapsedRealtime())) { tr("LUBA 2 required (or model trial armed)", "LUBA 2 requis (ou essai du modèle armé)") }
            // 20: manual mode kept by the previous gesture for bumper detection (released a moment ago).
            check(t.robotState in listOf(11,20)) { tr("Robot not ready", "Robot non prêt") }
            val failure = TeachSafety.tickFailure(if (devicePrevious == 0L) null else SystemClock.elapsedRealtime()-devicePrevious,
                t.battery,t.sensorStatus,30)
            check(failure == null) { failure ?: tr("State unavailable", "État indisponible") }
            // Beyond the mapped zones or at a no-go zone, 25 does not prevent driving freely ([edgeLatchIgnored]); nor does a
            // positioning condition (41 weak satellite signal, 2026-10-22): the joystick does not need RTK and brings the robot under open sky.
            check(SelfCheck.joystickAllowed(t.selfCheck) || edgeLatchIgnored(t)) { SelfCheck.refusal(t.selfCheck) }
            check(!TeachSafety.bumperHealthAlert(host.guardSensors(t.sensorStatus))) {
                BumperRest.refusal(host.guardSensors(t.sensorStatus), ModelSupport.motionValidated(robotName)) }
            // Model trial: joystick bounded to the trial ceilings (low speed).
            manualCommand = ModelTrial.joystick(robotName, linear, angular); manualInputAt = SystemClock.elapsedRealtime()
            if (manualCommand.first != 0) manualLastLinear = manualCommand.first
            if (manualJob == null) {
                val trial = !ModelSupport.motionValidated(robotName)
                update { it.copy(manualActive = true, manualStatus = if (trial) tr("Model trial: low-speed control — release to stop", "Essai du modèle : pilotage lent — relâchez pour arrêter")
                    else tr("Control active — release to stop", "Pilotage actif — relâchez pour arrêter")) }
                event("manual_start", tr("Joystick control, blades OFF", "Pilotage joystick, lames OFF"), if (trial) mapOf("modelTrial" to true, "robotName" to robotName,
                    "maxLinear" to ModelTrial.MAX_LINEAR, "maxAngular" to ModelTrial.MAX_ANGULAR) else emptyMap())
                manualJob = host.viewModelScope.launch {
                    // Bumper contact: the LUBA 2 only gives code 1014 on a read of its error history (CollisionLatch). Read
                    // every 500 ms while driving, like routes do; before 2026-10-22 nothing read it during the joystick, so a
                    // contact was never seen (or ended as "Wheels commanded without progress").
                    var errorsAt = 0L
                    // The LUBA 2 only publishes the bumper contact (-1014) in manual mowing mode (state 20), never while
                    // driven in ready mode (11): Fold log of 2026-10-22 16:48-16:49, joystick and approach against the
                    // bumper in state 11, error history unchanged. Manual mode with blades OFF, renewed every 500 ms.
                    var bumperModeAt = 0L
                    manualBumperMode = true
                    try {
                        while (state.value.manualActive) {
                            val now = SystemClock.elapsedRealtime(); val currentTelemetry = state.value.telemetry
                            check(now-manualInputAt <= 500) { tr("Joystick input stale", "Entrée joystick périmée") }
                            check(ModelTrial.testAllowed(state.value.selected?.name, now)) { tr("Model trial ended", "Essai du modèle terminé") }
                            val guard = TeachSafety.tickFailure(if (devicePrevious == 0L) null else now-devicePrevious,
                                currentTelemetry.battery,currentTelemetry.sensorStatus,30)
                            // Precise reason in the log: on 10-14, "Protection / incompatible robot state" did not say that
                            // the robot had just published self-check 25.
                            check(guard == null) { guard ?: "" }
                            check(SelfCheck.joystickAllowed(currentTelemetry.selfCheck) || edgeLatchIgnored(currentTelemetry)) {
                                SelfCheck.refusal(currentTelemetry.selfCheck) }
                            check(!TeachSafety.bumperHealthAlert(host.guardSensors(currentTelemetry.sensorStatus))) {
                                BumperRest.refusal(host.guardSensors(currentTelemetry.sensorStatus), ModelSupport.motionValidated(state.value.selected?.name)) +
                                    tr(" (sensors ${currentTelemetry.sensorStatus})", " (capteurs ${currentTelemetry.sensorStatus})") }
                            check(currentTelemetry.robotState in listOf(11,20)) { tr("Incompatible robot state (${currentTelemetry.robotState})", "État robot incompatible (${currentTelemetry.robotState})") }
                            check(state.value.ready && !closing && log.healthy) { tr("Link unavailable", "Liaison indisponible") }
                            check(queue.size <= 4) { tr("BLE queue saturated", "File BLE saturée") }
                            if (current == null && queue.isEmpty()) host.send(commands.movement(manualCommand.first,manualCommand.second), "MANUAL_JOYSTICK")
                            if (now >= bumperModeAt && currentTelemetry.robotState in listOf(11, 20) && !bladeMayBeOn) {
                                host.send(commands.manualMow(1, 0, host.holdHeightMm()), "MANUAL_BUMPER_MODE", periodic = true); bumperModeAt = now + 500 }
                            if (now >= errorsAt) { host.send(commands.errorQuery(), "MANUAL_ERRORS", periodic = true); errorsAt = now + 500 }
                            delay(200)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { manualInterrupted(error.message ?: tr("Control interrupted", "Pilotage interrompu")) }
                }
            }
            return true
        } catch (error: Exception) {
            // Refused while driving (finger still on the joystick): a collision protection is handled like in the loop.
            if (state.value.manualActive && ManualContact.suspected(state.value.telemetry.selfCheck, host.guardSensors(state.value.telemetry.sensorStatus))) {
                manualInterrupted(error.message ?: tr("Control interrupted", "Pilotage interrompu"))
                return false
            }
            stopManual(tr("Control refused: ${error.message}", "Pilotage refusé : ${error.message}"))
            update { it.copy(manualStatus = tr("Control refused: ${error.message}", "Pilotage refusé : ${error.message}")) }
            return false
        }
    }

    /**
     * Joystick stopped by a guard while driving. Collision lock (25) or bumper alert: probably an object touched, the
     * obstacle sheet opens; the lock alone replaces the critical alert, bumper / lift bits keep it (lift possible).
     */
    private fun manualInterrupted(reason: String) {
        val live = state.value.telemetry
        val sensors = host.guardSensors(live.sensorStatus)
        val contact = ManualContact.suspected(live.selfCheck, sensors)
        if (!contact || TeachSafety.bumperHealthAlert(sensors)) host.alerts.alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, reason)
        stopManual(reason)
        if (!contact) return
        manualContactUntil = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
        if (state.value.ready && !closing) host.send(commands.errorQuery(), "MANUAL_CONTACT_ERRORS")
        difficulties.recordManualContact(null)
    }

    /** Joystick drove in manual mode with blades OFF (bumper detection): left on release. */
    private var manualBumperMode = false

    fun stopManual(reason: String = tr("Joystick released", "Joystick relâché"), sendStop: Boolean = true) {
        manualBlocked = true
        val active = state.value.manualActive
        manualJob?.cancel(); manualJob = null; manualCommand = 0 to 0
        if (!active) return
        update { it.copy(manualActive = false, manualStatus = tr("$reason — stop requested", "$reason — arrêt demandé")) }
        if (sendStop && state.value.ready && !closing) {
            host.purgeQueuedCommands(); host.send(commands.stop(), "MANUAL_STOP")
            // Leaves the manual mode used for bumper detection (blades already OFF).
            if (manualBumperMode) host.send(commands.manualBlades(false, host.holdHeightMm()), "MANUAL_EXIT_MODE")
        }
        manualBumperMode = false
        event("manual_stop", reason)
    }
}
