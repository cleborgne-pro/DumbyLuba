@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Safety tests: command silence (motor watchdog) and obstacle approach tests with their contact, back-off and latch handling.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class SafetyTestRunner(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var bladeOffPending
        get() = host.bladeOffPending
        set(value) { host.bladeOffPending = value }
    private var bumperValidatedConnection
        get() = host.bumperValidatedConnection
        set(value) { host.bumperValidatedConnection = value }
    private val campaign get() = host.campaign
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var connectionId
        get() = host.connectionId
        set(value) { host.connectionId = value }
    private var current: RobotBle.Operation?
        get() = host.current
        set(value) { host.current = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private var heightTarget: Int?
        get() = host.heightTarget
        set(value) { host.heightTarget = value }
    private val log get() = host.log
    private var obstacleManualMode
        get() = host.obstacleManualMode
        set(value) { host.obstacleManualMode = value }
    private var periodic: Job?
        get() = host.periodic
        set(value) { host.periodic = value }
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private val queue get() = host.queue
    private var routeBladeGate: RouteBladeGate?
        get() = host.routeBladeGate
        set(value) { host.routeBladeGate = value }
    private var silenceJob: Job?
        get() = host.silenceJob
        set(value) { host.silenceJob = value }
    private var silenceProbe: CommandSilenceProbe?
        get() = host.silenceProbe
        set(value) { host.silenceProbe = value }
    private val state get() = host.state
    private val watchdogPreferences get() = host.watchdogPreferences

    private var teachJob: Job? = null
    private var teachStarted = 0L
    private var teachId = ""
    internal var teachConfig = TeachConfig()
    private var obstacleApproach = false
    private var obstacleStart: NavSample? = null

    /**
     * Supervised "Command cutoff" test (button held): slow straight-line forward drive, blades OFF, then no
     * more motion commands while the telemetry is observed. Does the firmware stop the robot on its own?
     * Otherwise the app sends STOP, at the latest after [CommandSilenceProbe.maxSilenceMs] or
     * [CommandSilenceProbe.maxDriftM].
     */
    fun startSilenceTest(linearRaw: Int) {
        if (silenceJob != null) return
        try {
            require(linearRaw in 10..120) { tr("Speed out of range (10–120 units)", "Vitesse hors plage (10–120 unités)") }
            val ui = state.value
            val t = ui.telemetry
            val now = SystemClock.elapsedRealtime()
            check(!ui.taskStopPending && ui.ready && !closing && log.healthy && !ui.robotRoute.running && !ui.mowing.active && !ui.manualActive &&
                !ui.navTest.running && !ui.teach.running && heightTarget == null && !bladeMayBeOn && !bladeOffPending) { tr("Finish the mission or test in progress", "Terminer la mission ou l'essai en cours") }
            TeachSafety.startFailure(ui.selected?.name, if (devicePrevious == 0L) null else now - devicePrevious, t.battery, t.robotState,
                host.guardSensors(t.sensorStatus), t.selfCheck, ui.campaign.running, ui.bladeTestActive, ui.navTest.running, ui.mapLoading,
                modelAllowed = ModelTrial.testAllowed(ui.selected?.name, now))?.let { error(it) }
            NavSafety.automaticPositionFailure(now - positionPrevious, t.x, t.y)?.let { error(it) }
            check(t.rtkFix) { tr("RTK FIX position required", "Position RTK FIX requise") }
            SilenceClearance.failure(ui.robotMap, ui.obstacles, Point(requireNotNull(t.x), requireNotNull(t.y)), t.heading ?: Double.NaN,
                RobotEnvelope.LUBA2.halfWidthM + 0.10)?.let { error(it) }
        } catch (error: Exception) {
            update { it.copy(silenceTest = it.silenceTest.copy(running = false, status = tr("Test refused: ${error.message}", "Essai refusé : ${error.message}"))) }
            event("silence_test_refused", error.message ?: tr("Test refused", "Essai refusé"))
            return
        }
        val probe = CommandSilenceProbe()
        silenceProbe = probe
        val robotId = requireNotNull(state.value.selected).address
        val firmware = state.value.telemetry.firmware
        update { it.copy(silenceTest = SilenceTestUi(running = true, phase = tr("Forward", "Avance"), status = tr("Slow straight-line forward drive · blades OFF", "Avance lente en ligne droite · lames OFF"), linearRaw = linearRaw)) }
        event("silence_test_start", tr("Command cutoff test: forward drive, then silence", "Essai coupure des commandes : avance puis silence"), mapOf("linearRaw" to linearRaw,
            "maxSilenceMs" to probe.maxSilenceMs, "maxDriftM" to probe.maxDriftM))
        host.send(host.reportsCommand(false, periodMs = 200), "SILENCE_REPORTS")
        silenceJob = host.viewModelScope.launch {
            var verdict: SilenceVerdict? = null
            try {
                fun guard() {
                    val live = state.value.telemetry
                    val now = SystemClock.elapsedRealtime()
                    check(state.value.ready && !closing && log.healthy) { tr("BLE link interrupted", "Liaison BLE interrompue") }
                    check(ModelTrial.testAllowed(state.value.selected?.name, now)) { tr("Model trial ended", "Essai du modèle terminé") }
                    TeachSafety.tickFailure(now - devicePrevious, live.battery, live.sensorStatus, 30)?.let { error(it) }
                    check(SelfCheck.nominal(live.selfCheck) && !TeachSafety.bumperHealthAlert(host.guardSensors(live.sensorStatus))) { tr("Protection or sensor alert", "Protection ou alerte capteur") }
                    check(live.robotState in listOf(11, 20)) { tr("Robot state changed (${live.robotState})", "État robot changé (${live.robotState})") }
                    NavSafety.automaticPositionFailure(now - positionPrevious, live.x, live.y)?.let { error(it) }
                }
                fun position() = Point(requireNotNull(state.value.telemetry.x), requireNotNull(state.value.telemetry.y))
                probe.startDrive(SystemClock.elapsedRealtime())
                var fixAt = 0L
                var lastCommandAt = 0L
                while (true) {
                    guard()
                    if (positionPrevious != fixAt) { fixAt = positionPrevious; probe.driveFix(fixAt, position()) }
                    if (probe.readyToSilence(SystemClock.elapsedRealtime())) break
                    check(queue.size <= 4) { tr("BLE queue saturated", "File BLE saturée") }
                    if (current == null && queue.isEmpty()) {
                        host.send(commands.movement(linearRaw, 0), "SILENCE_DRIVE $linearRaw")
                        lastCommandAt = SystemClock.elapsedRealtime()
                    }
                    delay(200)
                }
                // Last command = time it was queued: the measured bound stays an upper bound.
                probe.startSilence(lastCommandAt)
                event("silence_test_silence", tr("No more motion commands: observing the stop", "Plus aucune commande de mouvement : observation de l'arrêt"),
                    mapOf("drivenM" to probe.drivenMeters, "speedMps" to probe.speedMps))
                update { it.copy(silenceTest = it.silenceTest.copy(phase = "Silence", status = tr("No command sent · the robot must stop on its own", "Aucune commande envoyée · le robot doit s'arrêter seul"))) }
                while (verdict == null) {
                    val now = SystemClock.elapsedRealtime()
                    check(state.value.ready && !closing) { tr("BLE link interrupted during the cutoff", "Liaison BLE interrompue pendant la coupure") }
                    val live = state.value.telemetry
                    check(SelfCheck.nominal(live.selfCheck)) { tr("Robot protection active", "Protection robot active") }
                    if (positionPrevious != fixAt && live.x != null && live.y != null) {
                        fixAt = positionPrevious
                        verdict = probe.silentFix(fixAt, position())
                        update { it.copy(silenceTest = it.silenceTest.copy(driftM = probe.driftOf(position()))) }
                    }
                    if (verdict == null) verdict = probe.tick(now)
                    if (verdict == null) delay(100)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { verdict = probe.abort(error.message ?: tr("Test interrupted", "Essai interrompu")) }
            finishSilenceTest(robotId, firmware, linearRaw, requireNotNull(verdict))
        }
    }

    private fun finishSilenceTest(robotId: String, firmware: String?, linearRaw: Int, verdict: SilenceVerdict) {
        silenceJob = null; silenceProbe = null
        // Systematic STOP: after a confirmed self-stop it changes nothing, otherwise it stops the robot.
        if (state.value.ready && !closing) { host.purgeQueuedCommands(); host.send(commands.stop(), "SILENCE_FINAL_STOP") }
        val result = MotorWatchdogResult.of(robotId, Clock.now(), firmware, linearRaw, verdict)
        val kept = recordWatchdog(result)
        update { it.copy(motorWatchdog = kept, silenceTest = it.silenceTest.copy(running = false, phase = "", status = "${verdict.title} — ${result.detail}", driftM = result.driftM)) }
        event("silence_test_result", verdict.title, mapOf("outcome" to result.outcome, "boundMs" to result.boundMs, "driftM" to result.driftM,
            "speedMps" to result.speedMps, "linearRaw" to linearRaw, "demonstrated" to result.demonstrated, "detail" to result.detail))
    }

    /** An inconclusive test does not erase the last conclusive result of the same robot. */
    internal fun recordWatchdog(result: MotorWatchdogResult): MotorWatchdogResult {
        val previous = host.watchdogResult(result.robotId)
        if (result.outcome == MotorWatchdogResult.INCONCLUSIVE && previous != null && previous.outcome != MotorWatchdogResult.INCONCLUSIVE) return previous
        watchdogPreferences.edit { putString(result.robotId, result.toJson()) }
        return result
    }

    /** Release, leaving the screen or STOP: inconclusive test and immediate STOP. */
    fun stopSilenceTest(reason: String = tr("Button released", "Bouton relâché")) {
        val job = silenceJob ?: return
        val probe = silenceProbe
        job.cancel()
        val id = state.value.selected?.address
        if (probe != null && id != null) finishSilenceTest(id, state.value.telemetry.firmware, state.value.silenceTest.linearRaw,
            probe.abort(tr("$reason: STOP sent by the app, test inconclusive", "$reason : STOP envoyé par l'application, essai non concluant")))
        else { silenceJob = null; silenceProbe = null; update { it.copy(silenceTest = it.silenceTest.copy(running = false, status = reason)) } }
    }

    fun startObstacleTest(approach: Boolean, manualMode: Boolean = false) {
        if (state.value.robotRoute.running) { host.status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { host.status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mowing.active) { host.status(tr("Finish the mowing mission first", "Terminer la mission de tonte")); return }
        if (state.value.teach.running) return
        if (state.value.borderFrontal.running) { host.status(tr("Finish the frontal border test first", "Terminer l'essai frontal de bordure")); return }
        try {
            val t = state.value.telemetry
            val failure = TeachSafety.startFailure(state.value.selected?.name,
                if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious,
                t.battery, t.robotState, host.guardSensors(t.sensorStatus), t.selfCheck, state.value.campaign.running,
                state.value.bladeTestActive, state.value.navTest.running, state.value.mapLoading)
            check(failure == null) { failure ?: tr("Robot unavailable", "Robot indisponible") }
            check(state.value.ready && gatt != null && !closing && log.healthy && !bladeMayBeOn && !bladeOffPending && heightTarget == null) { tr("BLE / blade stop / height not ready", "BLE / arrêt lames / hauteur non prêt") }
            check(queue.size <= 4) { tr("BLE queue busy", "File BLE occupée") }
            // Bumper published only in manual mode (state 20): the approach always drives in manual mode, blades OFF.
            val bumperMode = manualMode || approach
            if (approach) check(TeachSafety.approachFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y, t.x, t.y) == null) { tr("Fresh position required for the approach", "Position fraîche obligatoire pour l'approche") }
            obstacleApproach = approach
            obstacleManualMode = bumperMode
            obstacleStart = t.x?.let { x -> t.y?.let { NavSample(x, it, t.heading, 0) } }
            teachStarted = SystemClock.elapsedRealtime(); teachId = UUID.randomUUID().toString()
            update { it.copy(teach = TeachUi(id = teachId, running = true, contactValidated=bumperValidatedConnection==connectionId, outcome = "RUNNING", status = if (approach) tr("Slow approach: stop on contact, 1 m / 30 s max", "Approche lente : arrêt au contact, 1 m / 30 s max") else tr("Stationary: wait for a bumper contact, 30 s max", "Immobile : attendre un contact bumper, 30 s max"))) }
            event("obstacle_test_start", state.value.teach.status, mapOf("approach" to approach, "manualMode" to manualMode, "speedRaw" to if (approach) teachConfig.linearSpeed else 0))
            host.send(host.reportsCommand(false, periodMs = 200), "OBSTACLE_RPT_FAST")
            host.send(commands.errorQuery(), "OBSTACLE_ERROR_QUERY")
            teachJob = host.viewModelScope.launch {
                try {
                    val progress=MotionProgressGuard()
                    var nextManualKeepalive = 0L
                    var nextDiagnosticQuery = 0L
                    if (bumperMode) {
                        host.purgeQueuedCommands(); host.send(commands.stop(),"BUMPER_MODE_STOP")
                        val gate=RouteBladeGate.unique("BUMPER_MANUAL_OFF",false)
                        routeBladeGate=gate
                        host.send(commands.manualMow(1,0,host.holdHeightMm()),gate.label)
                        val deadline=SystemClock.elapsedRealtime()+5000
                        while (state.value.telemetry.robotState!=20 || !gate.confirmed(devicePrevious,((state.value.telemetry.sensorStatus ?: -1) shr 9) and 7)) {
                            check(state.value.ready && !closing && log.healthy && SystemClock.elapsedRealtime()<deadline) { tr("Manual mode with blades OFF not confirmed", "Mode manuel avec lames OFF non confirmé") }
                            TeachSafety.tickFailure(SystemClock.elapsedRealtime()-devicePrevious,state.value.telemetry.battery,state.value.telemetry.sensorStatus,teachConfig.batteryFloor)?.let { error(it) }
                            delay(200)
                        }
                        teachStarted=SystemClock.elapsedRealtime()
                        update { it.copy(teach=it.teach.copy(status=if (approach) tr("Slow approach in manual mode, blades OFF: stop on contact, 1 m / 30 s max", "Approche lente en mode manuel, lames OFF : arrêt au contact, 1 m / 30 s max")
                            else tr("Stationary in manual mode, blades OFF confirmed: wait for the contact", "Immobile en mode manuel, lames OFF confirmées : attendre le contact"))) }
                    }
                    while (SystemClock.elapsedRealtime() - teachStarted < teachConfig.maxDurationMs) {
                        val now = SystemClock.elapsedRealtime(); val currentTelemetry = state.value.telemetry
                        val tickFailure = TeachSafety.tickFailure(if (devicePrevious == 0L) null else now - devicePrevious,
                            currentTelemetry.battery, currentTelemetry.sensorStatus, teachConfig.batteryFloor)
                        check(log.healthy && state.value.ready && !closing) { tr("BLE / log unavailable", "BLE / journal indisponible") }
                        check(tickFailure == null) { tickFailure ?: tr("Sensors unavailable", "Capteurs indisponibles") }
                        // Slow approach: the collision lock (25) or another protection that may announce the contact is the
                        // expected outcome, not a failure ([awaitApproachContact]).
                        val contactProtection = approach && CollisionLatch.mayAnnounceContact(currentTelemetry.robotState, currentTelemetry.selfCheck)
                        check(contactProtection || SelfCheck.nominal(currentTelemetry.selfCheck)) { tr("Robot protection active", "Protection robot active") }
                        if (TeachSafety.bumperHealthAlert(host.guardSensors(currentTelemetry.sensorStatus))) {
                            finishObstacle("ABORTED", tr("Bumper health alert: STOP requested; contact not confirmed", "Alerte de santé bumper : STOP demandé ; contact non confirmé"))
                            return@launch
                        }
                        check(currentTelemetry.robotState in listOf(11, 20)) { tr("Robot state changed", "État robot changé") }
                        if (approach) {
                            val approachFailure = TeachSafety.approachFailure(if (positionPrevious == 0L) null else now - positionPrevious,
                                currentTelemetry.x, currentTelemetry.y, obstacleStart?.x, obstacleStart?.y)
                            check(approachFailure == null) { approachFailure ?: tr("Approach unavailable", "Approche indisponible") }
                            val here = Point(requireNotNull(currentTelemetry.x), requireNotNull(currentTelemetry.y))
                            val stalled = progress.blocked(here, positionPrevious, now, teachConfig.linearSpeed)
                            if (stalled || contactProtection) {
                                val start = obstacleStart
                                awaitApproachContact(stalled, if (start == null) 0.0 else here.distance(Point(start.x, start.y)))
                                return@launch
                            }
                            host.send(commands.movement(teachConfig.linearSpeed, 0), "OBSTACLE_APPROACH")
                            if (now >= nextManualKeepalive) {
                                host.send(commands.manualMow(1,0,host.holdHeightMm()), "APPROACH_MANUAL_KEEPALIVE_OFF", periodic = true)
                                nextManualKeepalive = now + 500
                            }
                        } else if (manualMode) {
                            if (now >= nextManualKeepalive) {
                                host.send(commands.manualMow(1,0,host.holdHeightMm()), "BUMPER_MANUAL_KEEPALIVE_OFF")
                                nextManualKeepalive = now + 500
                            }
                        } else host.send(commands.stop(), "OBSTACLE_STATIC_ZERO")
                        if (now >= nextDiagnosticQuery) {
                            event("bumper_diagnostic", tr("State while waiting for contact", "État pendant attente contact"), mapOf("testId" to teachId,
                                "manualRequested" to bumperMode, "manualObserved" to (currentTelemetry.robotState == 20),
                                "robotState" to currentTelemetry.robotState, "sensorStatus" to currentTelemetry.sensorStatus,
                                "selfCheck" to currentTelemetry.selfCheck, "knifeStatus" to currentTelemetry.knifeStatus,
                                "cutterRpm" to currentTelemetry.cutterRpm, "stateAgeMs" to now-devicePrevious))
                            host.send(commands.errorQuery(), "OBSTACLE_ERROR_QUERY")
                            host.send(commands.cutterQuery(), "OBSTACLE_CUTTER_QUERY")
                            nextDiagnosticQuery = now + 1000
                        }
                        delay(200)
                    }
                    finishObstacle("NOT_DETECTED", tr("No collision signal received within 30 s; detection not validated, approach blocked", "Aucun signal de collision reçu en 30 s ; détection non validée, approche bloquée"))
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { finishObstacle("ABORTED", error.message ?: tr("Obstacle error", "Erreur obstacle")) }
            }
        } catch (error: Exception) {
            update { it.copy(teach = it.teach.copy(status = tr("Test refused: ${error.message}", "Test refusé : ${error.message}"))) }
            event("obstacle_test_refused", error.message ?: tr("Test refused", "Test refusé"))
        }
    }

    /**
     * Slow approach halted by the robot itself: collision protection (25…) or no translation for 3 s. Until 2026-10-22
     * both ended the test as ABORTED ("Robot protection active", "Presumed blockage"), although the robot stopping
     * against the object is what the approach is for. The robot is stopped and the error history read every 500 ms for
     * [ManualContact.GRACE_MS]: a 1014 gives CONTACT_DETECTED (error handler). Without a code: collision lock (25) =
     * contact; robot stopped after [TeachSafety.APPROACH_MIN_TRAVEL_M] of travel = BLOCKED_AHEAD, obstacle proposed to
     * check (bumper not validated); robot that never moved = ABORTED.
     */
    private suspend fun awaitApproachContact(stalled: Boolean, travelledM: Double) {
        host.purgeQueuedCommands(); host.send(commands.stop(), "OBSTACLE_APPROACH_HALT"); host.send(commands.errorQuery(), "OBSTACLE_CONTACT_ERRORS")
        val first = state.value.telemetry
        event("obstacle_approach_halt", tr("Slow approach halted by the robot: waiting for the contact code", "Approche lente arrêtée par le robot : attente du code de contact"),
            mapOf("testId" to teachId, "stalled" to stalled, "selfCheck" to first.selfCheck, "sensorStatus" to first.sensorStatus, "travelledM" to travelledM))
        update { it.copy(teach = it.teach.copy(status = tr("Robot stopped in front of an object: waiting for the contact code…", "Robot arrêté devant un objet : attente du code de contact…"))) }
        val deadline = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
        var queryAt = SystemClock.elapsedRealtime() + 500
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!state.value.teach.running) return
            val now = SystemClock.elapsedRealtime()
            if (now >= queryAt) {
                host.send(commands.manualMow(1, 0, host.holdHeightMm()), "APPROACH_HALT_MANUAL_KEEPALIVE_OFF", periodic = true)
                host.send(commands.errorQuery(), "OBSTACLE_CONTACT_ERRORS", periodic = true); queryAt = now + 500
            }
            delay(200)
        }
        if (!state.value.teach.running) return
        val t = state.value.telemetry
        val cm = round(travelledM * 100).toInt()
        when {
            CollisionLatch.latched(t.selfCheck) -> finishObstacle("CONTACT_DETECTED", tr("Contact reported by the collision lock (self-check 25) without code 1014: STOP requested; check the physical stop",
                "Contact signalé par le verrou de collision (self-check 25) sans code 1014 : STOP demandé ; vérifier l'arrêt physique"))
            travelledM >= TeachSafety.APPROACH_MIN_TRAVEL_M -> finishObstacle("BLOCKED_AHEAD", tr("Robot stopped in front of an object after $cm cm, without a bumper contact reported: obstacle proposed, to check",
                "Robot arrêté devant un objet après $cm cm, sans contact pare-chocs rapporté : obstacle proposé, à vérifier"))
            else -> finishObstacle("ABORTED", tr("The robot did not move forward at the approach speed ($cm cm): ground, slope or robot already against an object",
                "Le robot n'a pas avancé à la vitesse d'approche ($cm cm) : terrain, pente ou robot déjà contre un objet"))
        }
    }

    fun recordBumperBeep() {
        val t = state.value.telemetry
        event("bumper_beep_observed", tr("Beep / contact observed by the user — collision signal not confirmed", "Bip / contact observé par l'utilisateur — signal collision non confirmé"),
            mapOf("testId" to teachId, "sensorStatus" to t.sensorStatus, "robotState" to t.robotState,
                "selfCheck" to t.selfCheck, "deviceAt" to t.lastDeviceAt))
        if (state.value.ready && !closing) host.send(commands.errorQuery(), "BUMPER_OBSERVATION_QUERY")
        update { it.copy(teach = it.teach.copy(status = tr("Observed beep recorded; waiting for a robot event. Keep the test open until it ends.", "Bip observé consigné ; attente d'un événement robot. Gardez l'essai ouvert jusqu'à sa fin."))) }
    }

    fun stopObstacleTest() { finishObstacle("ABORTED", tr("User stop / left the screen", "Arrêt utilisateur / sortie écran")) }

    internal fun finishObstacle(outcome: String, detail: String, sendStop: Boolean = true) {
        if (teachId.isBlank()) return
        if(outcome == "ABORTED") host.alerts.alertMotionFailure(detail)
        teachJob?.cancel(); teachJob = null
        if (sendStop && state.value.ready && !closing) {
            host.purgeQueuedCommands()
            if(obstacleManualMode) host.send(commands.manualBlades(false,host.holdHeightMm()),"BUMPER_EXIT_MANUAL_OFF")
            host.send(commands.stop(), "OBSTACLE_STOP")
        }
        obstacleManualMode=false
        val contact = state.value.telemetry
        val proposal = if (TeachSafety.canProposeObstacle(obstacleApproach,outcome,SystemClock.elapsedRealtime()-positionPrevious) && contact.x != null && contact.y != null && contact.heading?.isFinite()==true && positionPrevious != 0L) {
            TeachSafety.proposal(TeachContact(contact.x, contact.y, contact.heading, Clock.now()), ObstacleKind.OTHER.defaultRadiusM)
        } else null
        val result = TestResult(teachId, if (obstacleApproach) "Apprentissage d'obstacle" else "Bumper immobile", outcome,
            SystemClock.elapsedRealtime() - teachStarted, detail)
        val fields = campaign.resultFields(result) + mapOf("kind" to "obstacle_detection", "approach" to obstacleApproach,
            "sensorStatus" to state.value.telemetry.sensorStatus, "x" to state.value.telemetry.x, "y" to state.value.telemetry.y,
            "estimatedX" to proposal?.estimated?.x, "estimatedY" to proposal?.estimated?.y)
        log.report(teachId, fields); event("obstacle_test_result", detail, fields)
        update { it.copy(teach = it.teach.copy(running = false, outcome = outcome, status = detail,
            proposal = proposal ?: it.teach.proposal, results = it.teach.results + result)) }
        teachId = ""
    }

    /** After STOP only: a proposal to check, never an automatically confirmed obstacle. */
    /** Pose at contact: fresh position (≤ 2 s) and fused tracking heading, otherwise null. */
    internal fun contactPose(code: Int): RouteContact? {
        val t = state.value.telemetry
        if (positionPrevious == 0L || SystemClock.elapsedRealtime()-positionPrevious !in 0..2000) return null
        val heading = host.contactHeading(t.heading)?.takeIf { it.isFinite() } ?: return null
        val x = t.x?.takeIf { it.isFinite() } ?: return null
        val y = t.y?.takeIf { it.isFinite() } ?: return null
        return RouteContact(code, x, y, heading, Clock.now())
    }

    /**
     * Straight back-off after a contact, heading unchanged, blades OFF: from [from] to [target], a point chosen on
     * the travelled trace ([ObstacleBypass.backOffCandidates]). Lateral deviation, progress and chassis envelope
     * ([physical]: borders, no-go zones, physical obstacles) checked at every step; bumper contact monitored;
     * physical stop confirmed by two stationary positions.
     */
    /**
     * Straight back-off from [from] to [target], heading [heading] unchanged, blades OFF. [recovering]: return into the
     * area after leaving the trajectory; while the footprint is still outside, the robot goes back over its own trace
     * (line checked at planning time, [DriftRecovery.backOff]) and the envelope is only checked once the chassis is back in.
     */
    internal suspend fun reverseStraight(from: Point, target: Point, heading: Double, physical: TrajectoryPlanner, heightMm: Int, prefix: String,
                                        recovering: Boolean = false, afterContact: Boolean = false,
                                        degreesPerUnit: Double = DeadReckoning.DEFAULT_DEGREES_PER_UNIT) {
        val length = from.distance(target)
        if (length < 0.05) return
        val ux = (target.x-from.x)/length; val uy = (target.y-from.y)/length
        val began = SystemClock.elapsedRealtime()
        val motionProgress = MotionProgressGuard()
        var keepalive = 0L; var queryAt = 0L
        // Heading hold ([BackOffSteering]): in-place pivots when the robot yaws while reversing (wheel slip).
        val reportedAtStart = state.value.telemetry.heading?.takeIf { it.isFinite() }
        var pivotSince = 0L; var pivotMs = 0L; var pivots = 0; var maxError = 0.0; var pivotFrom = BackOffSteering.FREE_START_M
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                check(state.value.ready && !closing && log.healthy && queue.size <= 4) { tr("BLE interrupted during the back-off", "BLE interrompu pendant le recul") }
                val live = state.value.telemetry
                NavSafety.automaticPositionFailure(now-positionPrevious,live.x,live.y)?.let { error(it) }
                NavSafety.tickFailure(now-devicePrevious,live.battery,host.guardSensors(live.sensorStatus),false,RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                // Right after a contact, the collision lock (25) does not prevent moving away from the object ([CollisionLatch]).
                check(CollisionLatch.reverseAllowed(live.robotState,live.selfCheck,afterContact)) { tr("Protection / robot state changed during the back-off", "Protection / état robot changé pendant le recul") }
                check(((requireNotNull(live.sensorStatus) shr 9) and 7) == 0) { tr("Blades active during the back-off", "Lames actives pendant le recul") }
                val position = Point(requireNotNull(live.x),requireNotNull(live.y))
                val along = (position.x-from.x)*ux + (position.y-from.y)*uy
                val lateral = abs((position.x-from.x)*uy - (position.y-from.y)*ux)
                if (lateral > 0.12) throw BackOffDeviation(tr("Back-off deviation >12 cm (%.0f cm)", "Écart au recul >12 cm (%.0f cm)").format(lateral*100))
                if (along >= length-0.03) break
                check(now-began-pivotMs < BACK_OFF_TIMEOUT_MS) { tr("Back-off timed out: clearance point not reached", "Recul expiré : point de dégagement non atteint") }
                val reported = live.heading?.takeIf { it.isFinite() }
                val bodyHeading = if (reportedAtStart != null && reported != null) BackOffSteering.current(heading,reportedAtStart,reported) else heading
                val headingError = BackOffSteering.error(BackOffSteering.wanted(heading,from,target,position),bodyHeading)
                maxError = maxOf(maxError,abs(headingError))
                val pivoting = pivotSince != 0L
                val pivotWanted = reportedAtStart != null && reported != null && along >= pivotFrom &&
                    (pivoting || pivots < BackOffSteering.MAX_PIVOTS) && BackOffSteering.pivotNeeded(headingError,pivoting) && (!pivoting || now-pivotSince < BackOffSteering.PIVOT_TIMEOUT_MS) &&
                    physical.footprintSafe(RobotPose(position,bodyHeading)) &&
                    physical.motionCollision(RobotPose(position,bodyHeading),RobotPose(position,bodyHeading+sign(headingError)*6.0)) == null
                if (pivotWanted) {
                    if (!pivoting) { pivotSince = now; pivots++ }
                    motionProgress.reset()
                    if (now >= keepalive) { host.send(commands.manualMow(1,0,heightMm),"${prefix}_BACKOFF_BUMPER_OFF",periodic=true); keepalive = now+500 }
                    if (now >= queryAt) { host.send(commands.errorQuery(),"${prefix}_BACKOFF_ERRORS",periodic=true); queryAt = now+500 }
                    host.send(commands.movement(0,BackOffSteering.pivotAngular(headingError,degreesPerUnit)),"${prefix}_BACKOFF_PIVOT")
                    delay(200)
                    continue
                }
                if (pivoting) {
                    // Pivot finished or abandoned: the next one only after some more reverse.
                    if (now-pivotSince >= BackOffSteering.PIVOT_TIMEOUT_MS) pivotFrom = along+BackOffSteering.RETRY_AFTER_M
                    pivotMs += now-pivotSince; pivotSince = 0L
                }
                check(!motionProgress.blocked(position,positionPrevious,now,-BACK_OFF_LINEAR)) {
                    if (CollisionLatch.latched(live.selfCheck)) tr("Back-off refused by the robot under collision lock (self-check 25): ${StopUnlock.PROTECTION_PHYSICAL}", "Recul refusé par le robot sous verrou de collision (self-check 25) : ${StopUnlock.PROTECTION_PHYSICAL}")
                    else tr("Back-off blocked for 3 s", "Recul bloqué depuis 3 s") }
                // The first 10 centimetres move away from the obstacle that was hit (line already checked at planning time).
                if (along >= 0.10 && (!recovering || physical.footprintSafe(RobotPose(position,bodyHeading)))) {
                    val step = minOf(0.10,length-along)
                    val next = Point(position.x+ux*step,position.y+uy*step)
                    check(physical.motionCollision(RobotPose(position,bodyHeading),RobotPose(next,bodyHeading)) == null) { tr("Back-off outside the safe envelope", "Recul hors enveloppe sûre") }
                }
                // Keep-alive and error reads are not stacked: through the gateway (≈ 430 ms per write on 10-14 at
                // 14:35), they piled up beyond 4 in the queue and the back-off stopped with "BLE interrupted".
                if (now >= keepalive) { host.send(commands.manualMow(1,0,heightMm),"${prefix}_BACKOFF_BUMPER_OFF",periodic=true); keepalive = now+500 }
                if (now >= queryAt) { host.send(commands.errorQuery(),"${prefix}_BACKOFF_ERRORS",periodic=true); queryAt = now+500 }
                host.send(commands.movement(-BACK_OFF_LINEAR,0),"${prefix}_BACKOFF")
                delay(200)
            }
        } finally { host.purgeQueuedCommands(); host.send(commands.stop(),"${prefix}_BACKOFF_STOP") }
        val stopped = StopConfirmation(SystemClock.elapsedRealtime())
        while (true) {
            check(state.value.ready && !closing && log.healthy) { tr("BLE interrupted after the back-off", "BLE interrompu après le recul") }
            val live = state.value.telemetry
            val result = stopped.observe(SystemClock.elapsedRealtime(),positionPrevious,Point(requireNotNull(live.x),requireNotNull(live.y)))
            if (result == true) break
            check(result == null) { tr("Physical stop not confirmed after the back-off", "Arrêt physique non confirmé après le recul") }
            delay(100)
        }
        event("${prefix.lowercase()}_back_off",if (recovering) tr("Straight back-off to the safe area finished", "Recul rectiligne vers la surface sûre terminé") else tr("Straight back-off after contact finished", "Recul rectiligne après contact terminé"),mapOf("fromX" to from.x,"fromY" to from.y,
            "toX" to target.x,"toY" to target.y,"meters" to length,"durationMs" to SystemClock.elapsedRealtime()-began,
            "headingPivots" to pivots,"maxHeadingErrorDegrees" to maxError,"pivotMs" to pivotMs))
    }

    /**
     * After the back-off that follows a contact: waits for the robot to release the collision lock (self-check 25,
     * [CollisionLatch]), stable for [ProtectionPause.STABLE_MS], at most [ProtectionPause.WAIT_MS]; robot kept stationary,
     * blades OFF, manual mode kept alive. Nothing to wait for if the self-check is already nominal.
     */
    internal suspend fun awaitCollisionLatchCleared(prefix: String, heightMm: Int) {
        if (SelfCheck.nominal(state.value.telemetry.selfCheck)) return
        val began = SystemClock.elapsedRealtime()
        val first = state.value.telemetry.selfCheck
        event("${prefix.lowercase()}_latch_wait", tr("Waiting for the robot protection to be released after the back-off", "Attente de la levée de la protection du robot après le recul"), mapOf("selfCheck" to first))
        val wait = ProtectionPause.Wait(began)
        var keepalive = 0L
        try {
            while (true) {
                check(state.value.ready && !closing && log.healthy) { tr("BLE interrupted while waiting for the protection", "BLE interrompu pendant l'attente de la protection") }
                val now = SystemClock.elapsedRealtime()
                check(now - devicePrevious in 0..2000) { tr("Robot state stale while waiting for the protection", "État robot périmé pendant l'attente de la protection") }
                val live = state.value.telemetry
                if (wait.update(now, live.robotState, live.selfCheck)) break
                if (now >= keepalive) {
                    host.send(commands.stop(), "${prefix}_LATCH_STOP")
                    host.send(commands.manualMow(1, 0, heightMm), "${prefix}_LATCH_BUMPER_OFF", periodic = true)
                    host.send(commands.errorQuery(), "${prefix}_LATCH_ERRORS", periodic = true)
                    keepalive = now + 500
                }
                delay(200)
            }
        } catch (error: IllegalStateException) {
            event("${prefix.lowercase()}_latch_kept", tr("Protection kept after the back-off", "Protection maintenue après le recul"), mapOf("selfCheck" to state.value.telemetry.selfCheck,
                "waitedMs" to SystemClock.elapsedRealtime() - began))
            throw IllegalStateException(tr("Bumper contact: ${error.message}. ${StopUnlock.PROTECTION_PHYSICAL}", "Contact bumper : ${error.message}. ${StopUnlock.PROTECTION_PHYSICAL}"))
        }
        event("${prefix.lowercase()}_latch_cleared", tr("Protection released by the robot after the back-off", "Protection levée par le robot après le recul"), mapOf("selfCheck" to first,
            "waitedMs" to SystemClock.elapsedRealtime() - began))
    }

    internal fun proposeRouteCollision() {
        val t=state.value.telemetry
        if(positionPrevious==0L || SystemClock.elapsedRealtime()-positionPrevious !in 0..2000 ||
            t.x?.isFinite()!=true || t.y?.isFinite()!=true || t.heading?.isFinite()!=true) return
        val proposal=TeachSafety.proposal(TeachContact(requireNotNull(t.x),requireNotNull(t.y),requireNotNull(host.contactHeading(t.heading)),Clock.now()),ObstacleKind.OTHER.defaultRadiusM)
        update { it.copy(teach=it.teach.copy(proposal=proposal,status=tr("Collision 1014: robot stopped. Check the obstacle proposal in Service → Obstacle tests.", "Collision 1014 : robot arrêté. Vérifier la proposition d'obstacle dans Service → Essais d'obstacle."))) }
        event("route_obstacle_proposed",tr("Fresh 1014 contact after STOP: local proposal to check", "Contact 1014 frais après STOP : proposition locale à vérifier"),mapOf("x" to proposal.estimated.x,"y" to proposal.estimated.y))
    }
}
