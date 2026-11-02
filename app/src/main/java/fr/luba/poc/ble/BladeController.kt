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
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Blades: height changes, rotation and height tests, guarded blade stop (queued OFF, verification) and their results.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class BladeController(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var bladeDeviceAt
        get() = host.bladeDeviceAt
        set(value) { host.bladeDeviceAt = value }
    private var bladeHeightRequest
        get() = host.bladeHeightRequest
        set(value) { host.bladeHeightRequest = value }
    private var bladeJob: Job?
        get() = host.bladeJob
        set(value) { host.bladeJob = value }
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var bladeOffPending
        get() = host.bladeOffPending
        set(value) { host.bladeOffPending = value }
    private var bladeOffWrittenAt
        get() = host.bladeOffWrittenAt
        set(value) { host.bladeOffWrittenAt = value }
    private var bladeOnObserved
        get() = host.bladeOnObserved
        set(value) { host.bladeOnObserved = value }
    private var bladeStopVerified
        get() = host.bladeStopVerified
        set(value) { host.bladeStopVerified = value }
    private var bladeTestId
        get() = host.bladeTestId
        set(value) { host.bladeTestId = value }
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var current: RobotBle.Operation?
        get() = host.current
        set(value) { host.current = value }
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private var heightJob: Job?
        get() = host.heightJob
        set(value) { host.heightJob = value }
    private var heightTarget: Int?
        get() = host.heightTarget
        set(value) { host.heightTarget = value }
    private var heightTestId
        get() = host.heightTestId
        set(value) { host.heightTestId = value }
    private val log get() = host.log
    private val queue get() = host.queue
    private val state get() = host.state

    private var bladeOffGeneration = 0L
    private var bladeTestStarted = 0L
    private var bladeModeObserved = false
    private var bladeAltUsed = false
    private var heightStarted = 0L
    /** Manual mowing mode (blades OFF) requested to apply the height: to be exited at the end of the adjustment. */
    private var heightModeSent = false
    private var heightSequenceJob: Job? = null
    /** [modelAllowed]: model check of the blade test, which a model trial opens (height adjustments keep the validated model). */
    private fun bladeGuard(heightMm: Int, modelAllowed: Boolean = ModelSupport.motionValidated(state.value.selected?.name)) {
        check(!state.value.robotRoute.running && !state.value.mowing.active) { tr("Finish the mowing mission first", "Terminer la mission de tonte") }
        check(!state.value.taskStopPending && gatt != null && state.value.ready && !closing && log.healthy) { tr("BLE / log not ready", "BLE / journal non prêt") }
        check(!state.value.navTest.running) { tr("Finish the random walk before this cutting test", "Terminer la marche aléatoire avant cet essai de coupe") }
        check(!state.value.teach.running) { tr("Finish the obstacle learning before this cutting test", "Terminer l'apprentissage d'obstacle avant cet essai de coupe") }
        val t = state.value.telemetry
        BladeSafety.requireReady(state.value.selected?.name,
            if (bladeDeviceAt == 0L) null else SystemClock.elapsedRealtime() - bladeDeviceAt,
            t.battery, t.robotState, host.guardSensors(t.sensorStatus), t.selfCheck, state.value.campaign.running, heightMm, state.value.cutHeights, modelAllowed)
    }
    /**
     * Height adjustment outside a mission. Firmware 1.30.29.26 (logs of 10-10): `todev_knife_height_set` alone
     * is ignored in the READY state (11), with no response at all; the height is only applied with
     * `mow_ctrl_by_hand.cut_knife_height` (manual mowing mode, state 20), as during the rotation test that
     * produced `toapp_knife_status_change` 60 → 40 mm. Sequence: manual mode with blades OFF and the height,
     * driver setpoint repeated once state 20 is reached, height reported, mode exit, check that it is kept.
     * The discs are never commanded (`cut_knife_ctrl=0`).
     */
    fun setBladeHeight(mm: Int, timeoutMs: Long = 30_000) {
        if (state.value.robotRoute.running) { host.status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { host.status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        try {
            require(timeoutMs in 10_000..120_000) { tr("Timeout out of range", "Délai d'attente hors plage") }
            bladeGuard(mm)
            check(!bladeMayBeOn && !state.value.bladeTestActive && heightTarget == null && heightJob == null) { tr("Another cutting test is still active", "Autre test de coupe encore actif") }
            bladeHeightRequest = mm; heightTarget = mm; heightStarted = SystemClock.elapsedRealtime()
            heightTestId = UUID.randomUUID().toString()
            update { it.copy(bladeHeightPending = true, bladeStatus = tr("Height $mm mm: switching to manual mowing mode, blades stopped", "Hauteur $mm mm : passage en mode tonte manuelle, lames arrêtées")) }
            event("blade_height_requested", tr("Height adjustment", "Réglage hauteur"), mapOf("testId" to heightTestId, "targetMm" to mm,
                "fromMm" to state.value.telemetry.bladeHeightMm, "sequence" to "manual_mode_off_blades_then_driver_height"))
            persistBladeHeight("RUNNING", tr("Manual mowing mode, blades OFF, with the requested height; waiting for the robot's response", "Mode tonte manuelle lames OFF avec la hauteur demandée ; attente du retour robot"))
            val id = heightTestId
            heightJob = host.viewModelScope.launch { runBladeHeight(id, mm, timeoutMs) }
        } catch (error: Exception) { update { it.copy(bladeStatus = tr("Height refused: ${error.message}", "Hauteur refusée : ${error.message}")) }; event("blade_test_refused", error.message ?: tr("Height refused", "Hauteur refusée")) }
    }

    private suspend fun runBladeHeight(id: String, mm: Int, timeoutMs: Long) {
        val since = Clock.now()
        val began = SystemClock.elapsedRealtime()
        val deadline = began + timeoutMs
        var modeAt = 0L; var modeObserved = false; var driverSent = false
        var failure: String? = null
        try {
            while (true) {
                check(log.healthy && state.value.ready && !closing && gatt != null) { tr("BLE / log unavailable", "BLE / journal indisponible") }
                val t = state.value.telemetry
                if (HeightSequence.reached(t, mm, since)) break
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) { failure = tr("Height $mm mm not reported within ${timeoutMs/1000} s (report ${t.bladeHeightMm ?: "—"} mm)", "Hauteur $mm mm non rapportée en ${timeoutMs/1000} s (rapport ${t.bladeHeightMm ?: "—"} mm)"); break }
                if (t.robotState == 20) {
                    if (!modeObserved) { modeObserved = true; event("blade_height_mode", tr("Manual mowing mode (20) observed, blades OFF", "Mode tonte manuelle (20) observé, lames OFF"), mapOf("testId" to id)) }
                    if (!driverSent) { host.send(commands.bladeHeight(mm), "BLADE_HEIGHT $mm mm"); driverSent = true }
                    val moving = if (t.heightMoving == true) tr(" · deck moving towards ${t.heightTargetMm ?: "—"} mm", " · plateau en mouvement vers ${t.heightTargetMm ?: "—"} mm") else ""
                    update { it.copy(bladeStatus = tr("Height $mm mm requested · ${t.bladeHeightMm ?: "—"} mm reported$moving", "Hauteur $mm mm demandée · ${t.bladeHeightMm ?: "—"} mm rapportés$moving")) }
                } else {
                    check(modeObserved || now - began < 6000) { tr("Manual mowing mode not confirmed (state ${t.robotState ?: "—"}): height not applied", "Mode tonte manuelle non confirmé (état ${t.robotState ?: "—"}) : hauteur non appliquée") }
                    // Re-send if the mode is not (or no longer) active: same command, blades still OFF.
                    if (now - modeAt >= 2000) { heightModeSent = true; host.send(commands.manualMow(1, 0, mm), "BLADE_HEIGHT_MODE $mm mm"); modeAt = now }
                }
                delay(200)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure = error.message ?: tr("Height adjustment error", "Erreur réglage hauteur") }
        if (heightTestId != id) return
        val reportedAt = SystemClock.elapsedRealtime()
        // Exit manual mode, then check that the height is kept in the READY state.
        var reverted: Int? = null
        if (exitHeightMode(mm)) {
            val exitWall = Clock.now()
            val watchEnd = SystemClock.elapsedRealtime() + 4000
            while (SystemClock.elapsedRealtime() < watchEnd && state.value.ready && !closing) {
                if (failure == null) HeightSequence.departed(state.value.telemetry, mm, exitWall)?.let { reverted = it }
                if (reverted != null) break
                delay(200)
            }
        }
        if (heightTestId != id) return
        heightJob = null
        val seconds = "%.1f".format((reportedAt - began) / 1000.0)
        when {
            failure != null -> recordHeightResult("UNCONFIRMED", failure)
            reverted != null -> recordHeightResult("REVERTED", tr("Height $mm mm reached in $seconds s, then back to $reverted mm on leaving manual mode", "Hauteur $mm mm atteinte en $seconds s, puis retour vers $reverted mm à la sortie du mode manuel"))
            else -> recordHeightResult("REPORTED", tr("Height $mm mm reported by the robot in $seconds s; kept after leaving manual mode", "Hauteur $mm mm rapportée par le robot en $seconds s ; conservée après la sortie du mode manuel"))
        }
    }

    /** Leaves the manual mowing mode opened by the adjustment (blades OFF, same height). */
    private fun exitHeightMode(mm: Int): Boolean {
        if (!heightModeSent) return false
        heightModeSent = false
        if (gatt == null || !state.value.ready || closing) return false
        host.send(commands.manualMow(0, 0, mm), "BLADE_HEIGHT_MODE_OFF")
        return true
    }

    /** External interruption: cancels the wait, leaves manual mode if possible, result unconfirmed. */
    internal fun abortBladeHeight(reason: String) {
        heightJob?.cancel(); heightJob = null
        exitHeightMode(heightTarget ?: bladeHeightRequest)
        if (heightTarget != null) recordHeightResult("INTERRUPTED", reason)
    }
    /** Checks the OFF path before any ON; no implicit activation. */
    fun prepareBladeStop(heightMm: Int) {
        if (state.value.mowing.active) return
        if (heightTarget != null) { update { it.copy(bladeStatus = tr("Height pending: cancel the wait or wait for its result before checking OFF", "Hauteur en attente : annuler l’attente ou attendre son résultat avant de vérifier OFF")) }; return }
        if (!state.value.ready || closing || state.value.teach.running || state.value.campaign.running || state.value.bladeTestActive || state.value.navTest.running || heightTarget != null) return
        if (!ModelTrial.testAllowed(state.value.selected?.name, SystemClock.elapsedRealtime())) return
        bladeHeightRequest = state.value.cutHeights.clamp(heightMm)
        bladeStopVerified = false; bladeOffWrittenAt = 0; bladeOffPending = true; bladeMayBeOn = true
        update { it.copy(bladeStatus = tr("Blade stop check: OFF requested", "Vérification arrêt lames : OFF demandé")) }
        queueBladeOff(tr("OFF check before the test", "Vérification OFF avant essai"))
    }
    /** Supervised rotation test: two-step sequence (manual mowing mode, then discs), with the disc command
     *  renewed during the test. No travel speed is sent: on firmware 1.30, a stream of zero speeds counts
     *  as "inactivity", which prevents the start. */
    fun startBladeTest(heightMm: Int, useKnifeControl: Boolean = false) {
        if (state.value.robotRoute.running) { host.status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { host.status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mapLoading) { host.status(tr("Wait for the map reading", "Attendre la lecture de carte")); return }
        try {
            bladeGuard(heightMm, ModelTrial.testAllowed(state.value.selected?.name, SystemClock.elapsedRealtime()))
            check(bladeStopVerified && !bladeMayBeOn && !bladeOffPending) { tr("Check OFF, then wait for an OFF state received after the write", "Vérifier OFF puis attendre un état OFF reçu après l’écriture") }
            check(heightTarget == null && !state.value.bladeTestActive && current == null && queue.isEmpty()) { tr("Wait for the BLE / height operations to finish", "Attendre la fin des opérations BLE / hauteur") }
            bladeHeightRequest = heightMm
            bladeTestId = UUID.randomUUID().toString(); bladeTestStarted = SystemClock.elapsedRealtime(); bladeOnObserved = false
            bladeStopVerified = false; bladeMayBeOn = true; bladeOffWrittenAt = 0
            bladeModeObserved = false; bladeAltUsed = useKnifeControl
            update { it.copy(bladeTestActive = true, bladeStatus = tr("Manual test: manual mowing mode, then disc start (10 s max)", "Essai manuel : mode tonte manuelle puis démarrage des disques (10 s max)")) }
            event("blade_rotation_requested", tr("Supervised activation", "Activation supervisée"), mapOf("testId" to bladeTestId, "heightMm" to heightMm,
                "maxSoftwareDurationMs" to 10000, "sequence" to "mode_then_disc_keepalive",
                "alternativeKnifeControl" to useKnifeControl))
            persistBladeRotation("RUNNING", tr("Sequence: manual mowing mode, then discs with keep-alive${if (useKnifeControl) " (alternative SysKnifeControl)" else ""}", "Séquence : mode tonte manuelle puis disques avec maintien${if (useKnifeControl) " (alternative SysKnifeControl)" else ""}"))
            host.send(commands.cutterQuery(), "BLADE_CUTTER_QUERY")
            bladeJob = host.viewModelScope.launch { runBladeStart(heightMm, useKnifeControl) }
        } catch (error: Exception) { update { it.copy(bladeStatus = tr("Rotation refused: ${error.message}", "Rotation refusée : ${error.message}")) }; event("blade_test_refused", error.message ?: tr("Rotation refused", "Rotation refusée")) }
    }

    private suspend fun runBladeStart(heightMm: Int, useKnifeControl: Boolean) {
        try {
            host.send(commands.manualMow(mainCtrl = 1, cutKnifeCtrl = 0, heightMm = heightMm), "BLADE_MODE_ON")
            val modeDeadline = SystemClock.elapsedRealtime() + 2000
            while (SystemClock.elapsedRealtime() < modeDeadline) {
                delay(200)
                check(log.healthy && state.value.ready && !closing) { tr("BLE / log unavailable", "BLE / journal indisponible") }
                if (state.value.telemetry.robotState == 20) { bladeModeObserved = true; break }
            }
            event("blade_mode_check", if (bladeModeObserved) tr("MODE_MANUAL_MOWING (20) observed", "MODE_MANUAL_MOWING (20) observé") else tr("Mode 20 not observed within 2 s", "Mode 20 non observé en 2 s"),
                mapOf("observed" to bladeModeObserved, "robotState" to state.value.telemetry.robotState))
            update { it.copy(bladeStatus = if (bladeModeObserved) tr("Manual mowing mode active — disc start requested", "Mode tonte manuelle actif — demande de démarrage des disques")
                else tr("Mode 20 not confirmed — activation refused", "Mode 20 non confirmé — activation refusée")) }
            check(bladeModeObserved) { tr("Manual mode not confirmed: no disc activation", "Mode manuel non confirmé : aucune activation des disques") }
            val end = bladeTestStarted + 10000
            while (SystemClock.elapsedRealtime() < end && state.value.bladeTestActive) {
                check(log.healthy && state.value.ready && !closing) { tr("BLE / log unavailable", "BLE / journal indisponible") }
                check(bladeDeviceAt > 0 && SystemClock.elapsedRealtime() - bladeDeviceAt <= 2000) { tr("Robot state stale", "État robot périmé") }
                check((state.value.telemetry.battery ?: 0) > 30) { tr("Battery ≤30 %", "Batterie ≤30 %") }
                if (useKnifeControl) host.send(commands.knifeControl(true), "BLADES_ON_ALT_KNIFE")
                else host.send(commands.manualMow(mainCtrl = 1, cutKnifeCtrl = 1, heightMm = heightMm), "BLADES_ON_KEEPALIVE")
                delay(500)
            }
            stopBladeTest(tr("Maximum software duration reached", "Durée maximale logicielle atteinte"))
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { stopBladeTest(tr("Test error: ${error.message}", "Erreur essai : ${error.message}")) }
    }
    fun stopOwnedBladeTest(reason: String) {
        if (BladeSafety.shouldStopTestOnExit(state.value.robotRoute.running,state.value.bladeTestActive,bladeTestId)) stopBladeTest(reason)
    }
    fun stopBladeTest(reason: String = tr("Button released", "Relâchement bouton")) {
        if (state.value.robotRoute.running) { host.alerts.intentionalAlertStop(); host.requestStop(false,reason,intentional=true); return }
        if (!bladeMayBeOn && !state.value.bladeTestActive) return
        if (bladeOffPending && !state.value.bladeTestActive) return
        bladeJob?.cancel(); bladeJob = null
        update { it.copy(bladeTestActive = false) }
        bladeStopVerified = false; bladeOffWrittenAt = 0; bladeOffPending = true
        queueBladeOff(reason)
    }
    private fun queueBladeOff(reason: String) {
        val generation = ++bladeOffGeneration
        event("blade_off_requested", reason, mapOf("testId" to bladeTestId))
        if (gatt == null || !state.value.ready || closing) {
            update { it.copy(bladeStatus = tr("OFF not sent: link unavailable; physical stop required", "OFF non transmis : liaison indisponible ; arrêt physique requis")) }
            if (bladeTestId.isNotBlank()) recordBladeResult("OFF_UNCONFIRMED", tr("Link unavailable", "Liaison indisponible"))
            return
        }
        val continuation = (current as? RobotBle.Operation.Write)?.let { active -> queue.filterIsInstance<RobotBle.Operation.Write>().filter { it.batch == active.batch } }.orEmpty()
        queue.clear(); queue.addAll(continuation)
        host.send(commands.manualBlades(false, bladeHeightRequest), "BLADES_OFF")
        if (bladeAltUsed) host.send(commands.knifeControl(false), "BLADES_OFF_ALT_KNIFE")
        host.send(commands.stop(), "BLADE_TEST_ZERO linear=0 angular=0")
        val connection = gatt
        host.viewModelScope.launch {
            delay(5000)
            if (gatt === connection && bladeOffPending && bladeOffGeneration == generation) {
                if (bladeTestId.isNotBlank()) recordBladeResult("OFF_UNCONFIRMED", tr("No fresh OFF state within 5 s after the command", "Pas d’état OFF frais après la commande dans les 5 s"))
                update { it.copy(bladeStatus = tr("Blade stop not confirmed; use the physical stop", "Arrêt lames non confirmé ; utiliser l’arrêt physique")) }
                host.requestStop(true, tr("Blade OFF not confirmed", "OFF lames non confirmé"))
            }
        }
    }
    private fun persistBladeRotation(outcome: String, detail: String) {
        if (bladeTestId.isBlank()) return
        log.report(bladeTestId, mapOf("id" to bladeTestId, "kind" to "supervised_blades", "outcome" to outcome,
            "step" to "Rotation manuelle", "detail" to detail, "elapsedMs" to SystemClock.elapsedRealtime() - bladeTestStarted,
            "heightMm" to bladeHeightRequest, "onStateObserved" to bladeOnObserved,
            "manualMowModeObserved" to bladeModeObserved, "alternativeKnifeControl" to bladeAltUsed,
            "knifeStatusLatest" to state.value.telemetry.knifeStatus, "cutterModeLatest" to state.value.telemetry.cutterMode,
            "cutterRpmLatest" to state.value.telemetry.cutterRpm))
    }
    internal fun recordBladeResult(outcome: String, detail: String) {
        val observed = tr("$detail · manual mowing mode ${if (bladeModeObserved) "observed" else "not observed"}", "$detail · mode tonte manuelle ${if (bladeModeObserved) "observé" else "non observé"}") +
            tr(" · disc ${if (bladeOnObserved) "ON observed" else "never ON"} · RPM ${state.value.telemetry.cutterRpm ?: "—"}", " · disque ${if (bladeOnObserved) "ON observé" else "jamais ON"} · RPM ${state.value.telemetry.cutterRpm ?: "—"}") +
            " · knife_status ${state.value.telemetry.knifeStatus ?: "—"}"
        persistBladeRotation(outcome, observed)
        val result = TestResult(bladeTestId, tr("Supervised blade rotation", "Rotation lames supervisée"), outcome, SystemClock.elapsedRealtime() - bladeTestStarted, observed)
        update { it.copy(bladeResults = it.bladeResults + result, bladeStatus = observed) }
        event("blade_test_result", observed, host.campaign.resultFields(result))
        bladeTestId = ""
    }
    private fun persistBladeHeight(outcome: String, detail: String) {
        if (heightTestId.isBlank()) return
        log.report(heightTestId, mapOf("id" to heightTestId, "kind" to "blade_height", "outcome" to outcome,
            "step" to "Hauteur manuelle", "detail" to detail, "targetMm" to heightTarget,
            "reportedMm" to state.value.telemetry.bladeHeightMm,
            "driverHeightMm" to state.value.telemetry.driverHeightMm, "workHeightMm" to state.value.telemetry.workHeightMm, "elapsedMs" to SystemClock.elapsedRealtime() - heightStarted))
    }
    private fun recordHeightResult(outcome: String, detail: String) {
        persistBladeHeight(outcome, detail)
        val result = TestResult(heightTestId, "Hauteur $heightTarget mm", outcome, SystemClock.elapsedRealtime() - heightStarted, detail)
        update { it.copy(bladeHeightPending = false, bladeResults = it.bladeResults + result, bladeStatus = detail) }
        event("blade_height_result", detail, host.campaign.resultFields(result))
        heightTarget = null; heightTestId = ""
    }

    fun cancelBladeHeight() {
        if (heightTarget == null) return
        abortBladeHeight(tr("Height wait canceled; mechanical height not confirmed", "Attente hauteur annulée ; hauteur mécanique non confirmée"))
    }

    /**
     * Chains height setpoints: each one must be reported by the robot before the next.
     * Blades are never commanded; any unconfirmed setpoint stops the sequence.
     */
    fun startHeightSequence(targets: List<Int>) {
        if (state.value.heightSequence.running || heightSequenceJob?.isActive == true) return
        try {
            require(targets.isNotEmpty() && targets.size <= 6 && targets.all { it in state.value.cutHeights }) { tr("Invalid height sequence", "Séquence de hauteur invalide") }
            check(heightTarget == null && !state.value.bladeHeightPending) { tr("Height adjustment already pending", "Réglage de hauteur déjà en attente") }
            bladeGuard(targets.first())
            val startMm = state.value.telemetry.bladeHeightMm
            val began = SystemClock.elapsedRealtime()
            update { it.copy(heightSequence = HeightSequenceUi(running = true, targets = targets, startMm = startMm,
                status = tr("Sequence ${targets.joinToString(" → ") { mm -> "$mm mm" }}", "Séquence ${targets.joinToString(" → ") { mm -> "$mm mm" }}"))) }
            event("blade_height_sequence_start", tr("Height sequence", "Séquence de hauteur"), mapOf("targets" to targets, "startMm" to startMm))
            heightSequenceJob = host.viewModelScope.launch {
                var failure: String? = null
                for ((index, mm) in targets.withIndex()) {
                    update { it.copy(heightSequence = it.heightSequence.copy(index = index, status = tr("Step ${index+1}/${targets.size}: $mm mm requested", "Étape ${index+1}/${targets.size} : $mm mm demandés"))) }
                    val from = state.value.telemetry.bladeHeightMm
                    setBladeHeight(mm, HeightSequence.timeoutMs(from, mm))
                    val id = heightTestId
                    if (heightTarget != mm || id.isBlank()) { failure = state.value.bladeStatus; break }
                    while (heightTarget != null && heightTestId == id) delay(200)
                    val result = state.value.bladeResults.lastOrNull { it.id == id }
                    if (result?.outcome != "REPORTED") { failure = tr("Step ${index+1}: ${result?.detail ?: "no result"}", "Étape ${index+1} : ${result?.detail ?: "résultat absent"}"); break }
                    if (index < targets.size - 1) delay(1500)
                }
                val elapsed = SystemClock.elapsedRealtime() - began
                val outcome = if (failure == null) "PASS" else "FAILED"
                val detail = failure ?: tr("${targets.size} setpoints reported by the robot · ${startMm ?: "—"} → ${state.value.telemetry.bladeHeightMm ?: "—"} mm", "${targets.size} consignes rapportées par le robot · ${startMm ?: "—"} → ${state.value.telemetry.bladeHeightMm ?: "—"} mm")
                val result = TestResult(UUID.randomUUID().toString(), "Séquence hauteur ${targets.joinToString(" → ")} mm", outcome, elapsed, detail)
                update { it.copy(heightSequence = it.heightSequence.copy(running = false, status = detail), bladeResults = it.bladeResults + result) }
                event("blade_height_sequence_result", detail, host.campaign.resultFields(result))
                log.report(result.id, host.campaign.resultFields(result) + mapOf("kind" to "blade_height_sequence", "targets" to targets, "startMm" to startMm))
                heightSequenceJob = null
            }
        } catch (error: Exception) {
            update { it.copy(heightSequence = it.heightSequence.copy(running = false, status = tr("Sequence refused: ${error.message}", "Séquence refusée : ${error.message}"))) }
            event("blade_test_refused", error.message ?: tr("Sequence refused", "Séquence refusée"))
        }
    }

    fun stopHeightSequence(reason: String = tr("Sequence canceled", "Séquence annulée")) {
        val job = heightSequenceJob ?: return
        heightSequenceJob = null
        job.cancel()
        abortBladeHeight(reason)
        update { it.copy(heightSequence = it.heightSequence.copy(running = false, status = reason)) }
        event("blade_height_sequence_result", reason)
    }

    fun recordHeightObservation(moved: Boolean) {
        val detail = if (moved) tr("Deck raising / lowering observed; exact height not measured", "Montée / descente des plateaux observée ; hauteur exacte non mesurée") else tr("No deck movement observed", "Aucun mouvement des plateaux observé")
        val result = TestResult(UUID.randomUUID().toString(), "Observation hauteur $bladeHeightRequest mm", "MANUAL_OBSERVATION", 0, detail)
        update { it.copy(bladeResults = it.bladeResults + result) }
        event("blade_height_observation", detail, host.campaign.resultFields(result) + mapOf("requestedMm" to bladeHeightRequest))
        log.report(result.id, host.campaign.resultFields(result) + mapOf("kind" to "height_observation", "requestedMm" to bladeHeightRequest))
    }
}
