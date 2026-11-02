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
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Frontal border test: the robot drives up to a border at low speed, bumper mode on, and records where it stops.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class BorderFrontalRunner(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var borderBumperWatch
        get() = host.borderBumperWatch
        set(value) { host.borderBumperWatch = value }
    private var borderContactCode: Int?
        get() = host.borderContactCode
        set(value) { host.borderContactCode = value }
    private var borderErrorCode: Int?
        get() = host.borderErrorCode
        set(value) { host.borderErrorCode = value }
    private var borderPositioning
        get() = host.navigation.borderPositioning
        set(value) { host.navigation.borderPositioning = value }
    private val calibration get() = host.calibration
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private val log get() = host.log
    private var navConfig
        get() = host.navConfig
        set(value) { host.navConfig = value }
    private var navJob: Job?
        get() = host.navJob
        set(value) { host.navJob = value }
    private var periodic: Job?
        get() = host.periodic
        set(value) { host.periodic = value }
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private var routeBladeGate: RouteBladeGate?
        get() = host.routeBladeGate
        set(value) { host.routeBladeGate = value }
    private val state get() = host.state
    private var teachConfig
        get() = host.safetyTests.teachConfig
        set(value) { host.safetyTests.teachConfig = value }

    /** Frontal border test: job, identifier, start. */
    private var borderJob: Job? = null
    private var borderId = ""
    private var borderStarted = 0L
    private var borderRequest: RobotBle.BorderRequest? = null

    /** Target point and planned distances for the outline [shapeId], from the received position, without any motor command. */
    fun previewBorderFrontal(shapeId: Long) {
        if (state.value.borderFrontal.running) return
        val offset = OuterOffsetSetting.meters
        try {
            check(state.value.robotMap.complete) { tr("Complete map required", "Carte complète requise") }
            val t = state.value.telemetry
            val x = t.x; val y = t.y
            check(x != null && y != null) { tr("Robot position unknown", "Position du robot inconnue") }
            val center = DeadReckoning.rotationCenter(Point(x, y), t.heading)
            val plan = BorderFrontal.plan(state.value.robotMap, shapeId, center, offset)
            val failure = BorderFrontal.lineFailure(state.value.robotMap, plan, state.value.obstacles)
            update { it.copy(borderFrontal = it.borderFrontal.copy(shapeId = shapeId, plan = plan, outerOffsetM = offset,
                previewRefused = failure != null,
                preview = failure ?: tr("Target point %.1f m from the robot, on the nearest side. Recalculated at launch.", "Point visé à %.1f m du robot, sur le côté le plus proche. Recalculé au lancement.").format(center.distance(plan.contact)))) }
        } catch (error: Exception) {
            update { it.copy(borderFrontal = it.borderFrontal.copy(shapeId = shapeId, plan = null, outerOffsetM = offset,
                previewRefused = true, preview = tr("Test unavailable: ${error.message}", "Essai indisponible : ${error.message}"))) }
        }
    }

    /**
     * Frontal test: drive to the point on the outline's normal (robot facing it), slow advance to the point where the
     * passes stop, stop for the visual check, then, on request, slow advance until bumper contact, the reported edge,
     * or the bumper 15 cm beyond the outline. Blades stopped throughout.
     */
    fun startBorderFrontal(shapeId: Long) {
        if (state.value.borderFrontal.running || state.value.navTest.running || navJob != null) return
        try {
            check(!state.value.robotRoute.running && !state.value.mowing.active) { tr("Finish the mowing in progress", "Terminer la tonte en cours") }
            check(!state.value.manualActive) { tr("Release the joystick before this action", "Relâcher le joystick avant cette action") }
            check(!state.value.teach.running) { tr("Finish the obstacle test in progress", "Terminer l'essai d'obstacle en cours") }
            val t = state.value.telemetry
            check(state.value.robotMap.complete) { tr("Complete map required", "Carte complète requise") }
            check(NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y) == null) { tr("Fresh position required", "Position fraîche obligatoire") }
            val published = Point(requireNotNull(t.x), requireNotNull(t.y))
            val center = DeadReckoning.rotationCenter(published, t.heading)
            val offset = OuterOffsetSetting.meters
            val plan = BorderFrontal.plan(state.value.robotMap, shapeId, center, offset)
            val nav = BorderFrontal.navPlan(state.value.robotMap, published, plan, state.value.obstacles)
            borderId = UUID.randomUUID().toString(); borderStarted = SystemClock.elapsedRealtime()
            borderContactCode = null; borderErrorCode = null; borderRequest = null
            update { it.copy(borderFrontal = it.borderFrontal.copy(running = true, phase = BorderPhase.POSITIONING, shapeId = shapeId, plan = plan,
                outerOffsetM = offset, passEndFrontGapM = null, passEndOvershootM = null, edgeFrontGapM = null, liveFrontGapM = null,
                outcome = "RUNNING", status = tr("Positioning facing the outline · %.1f m", "Positionnement face au contour · %.1f m").format(nav.meters))) }
            event("border_frontal_start", tr("Frontal border test", "Essai frontal de bordure"), mapOf("testId" to borderId, "shapeId" to shapeId, "kind" to plan.kind.name,
                "contactX" to plan.contact.x, "contactY" to plan.contact.y, "normalX" to plan.normalX, "normalY" to plan.normalY,
                "passEndM" to plan.passEndM, "outerOffsetM" to offset, "plannedFrontGapM" to plan.plannedFrontGapM,
                "plannedPivotGapM" to plan.plannedPivotGapM, "points" to nav.points.map { mapOf("x" to it.x, "y" to it.y) }))
            borderPositioning = true
            try {
                host.navigation.startMapNavigation(NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
                    angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0), preset = nav)
            } finally { borderPositioning = false }
            if (!state.value.navTest.running) { finishBorder("ABORTED", tr("Positioning refused: ${state.value.navTest.status}", "Positionnement refusé : ${state.value.navTest.status}"), sendStop = false); return }
            borderJob = host.viewModelScope.launch { runBorderFrontal(plan) }
        } catch (error: Exception) {
            update { it.copy(borderFrontal = it.borderFrontal.copy(status = tr("Test refused: ${error.message}", "Essai refusé : ${error.message}"))) }
            event("border_frontal_refused", error.message ?: tr("Test refused", "Essai refusé"))
        }
    }

    /** Visual verdict at the stop at the end of the pass: [ok] false if the robot's front goes past the real border. */
    fun borderPassEndVerdict(ok: Boolean) {
        if (state.value.borderFrontal.phase == BorderPhase.PASS_END) borderRequest = if (ok) RobotBle.BorderRequest.OK else RobotBle.BorderRequest.TOO_FAR
    }

    fun continueBorderToEdge() {
        if (state.value.borderFrontal.phase == BorderPhase.PASS_END) borderRequest = RobotBle.BorderRequest.CONTINUE
    }

    /** Edge seen visually during the advance: immediate STOP, then measurement. */
    fun borderEdgeSeen() {
        if (state.value.borderFrontal.phase != BorderPhase.EDGE) return
        host.purgeQueuedCommands(); host.send(commands.stop(), "BORDER_EDGE_SEEN_STOP")
        borderRequest = RobotBle.BorderRequest.EDGE
    }

    fun stopBorderFrontal(reason: String = tr("User stop", "Arrêt utilisateur")) {
        if (!state.value.borderFrontal.running) return
        finishBorder("ABORTED", reason)
    }

    private suspend fun runBorderFrontal(plan: BorderFrontalPlan) {
        val title = tr("Frontal test · ${plan.label}", "Essai frontal · ${plan.label}")
        try {
            while (state.value.navTest.running || navJob != null) delay(200)
            val nav = state.value.navTest
            if (nav.outcome != "PASS") { finishBorder("ABORTED", tr("Positioning interrupted: ${nav.status}", "Positionnement interrompu : ${nav.status}"), sendStop = false); return }
            enterBorderBumperMode()
            // Slow advance to the point where the planner ends the passes facing this outline.
            val first = borderAdvance(plan, BorderPhase.APPROACH, BorderFrontal.APPROACH_LINEAR, BorderFrontal.APPROACH_TIMEOUT_MS, plan.passEndM)
            val stopped = borderSettled()
            val passGap = plan.frontGap(stopped)
            update { it.copy(borderFrontal = it.borderFrontal.copy(passEndFrontGapM = passGap, passEndOvershootM = plan.passEndM - plan.centerGap(stopped), liveFrontGapM = passGap)) }
            if (first != BorderStop.TARGET) {
                // Physical edge before the pass end: the passes themselves would touch it.
                update { it.copy(borderFrontal = it.borderFrontal.copy(edgeFrontGapM = passGap)) }
                finishBorder(if (first == BorderStop.CONTACT) "CONTACT_BEFORE_PASS_END" else "FAILED",
                    tr("${stopLabel(first)} before the pass end · ", "${stopLabel(first)} avant le bout de passe · ") + BorderFrontal.summary(plan, null, passGap))
                return
            }
            update { it.copy(borderFrontal = it.borderFrontal.copy(phase = BorderPhase.PASS_END,
                status = tr("Pass end: bumper %.0f cm from the outline (planned %.0f cm). Does the robot go past the real border?", "Bout de passe : pare-chocs à %.0f cm du contour (prévu %.0f cm). Le robot dépasse-t-il la bordure réelle ?").format(passGap * 100, plan.plannedFrontGapM * 100))) }
            event("border_frontal_pass_end", tr("Stopped at the pass end", "Arrêt au bout de passe"), mapOf("testId" to borderId, "x" to stopped.x, "y" to stopped.y,
                "frontGapM" to passGap, "overshootM" to plan.passEndM - plan.centerGap(stopped), "lateralM" to plan.lateral(stopped)))
            borderRequest = null
            val waitFrom = SystemClock.elapsedRealtime()
            var nextKeep = 0L
            while (true) {
                borderErrorCode?.let { error(tr("Robot error $it", "Erreur robot $it")) }
                if (borderContactCode != null) {
                    finishBorder("CONTACT_BEFORE_PASS_END", tr("Bumper contact while stopped at the pass end · ", "Contact pare-chocs à l'arrêt en bout de passe · ") + BorderFrontal.summary(plan, passGap, passGap))
                    return
                }
                when (borderRequest) {
                    RobotBle.BorderRequest.OK -> { finishBorder("PASS", tr("Pass end judged correct · ", "Bout de passe jugé correct · ") + BorderFrontal.summary(plan, passGap, null)); return }
                    RobotBle.BorderRequest.TOO_FAR -> { finishBorder("FAILED", tr("Pass end too close to or beyond the border · ", "Bout de passe trop près ou au-delà de la bordure · ") + BorderFrontal.summary(plan, passGap, null)); return }
                    RobotBle.BorderRequest.CONTINUE -> break
                    else -> Unit
                }
                check(SystemClock.elapsedRealtime() - waitFrom < BorderFrontal.VERDICT_TIMEOUT_MS) { tr("No verdict within ${BorderFrontal.VERDICT_TIMEOUT_MS / 60_000} min", "Aucun verdict en ${BorderFrontal.VERDICT_TIMEOUT_MS / 60_000} min") }
                borderTick()
                nextKeep = borderHold(nextKeep)
                delay(200)
            }
            borderRequest = null
            val edge = borderAdvance(plan, BorderPhase.EDGE, BorderFrontal.EDGE_LINEAR, BorderFrontal.EDGE_TIMEOUT_MS, null)
            val atEdge = borderSettled()
            val edgeGap = plan.frontGap(atEdge)
            update { it.copy(borderFrontal = it.borderFrontal.copy(edgeFrontGapM = if (edge == BorderStop.LIMIT) null else edgeGap, liveFrontGapM = edgeGap)) }
            when (edge) {
                BorderStop.LIMIT -> finishBorder("NO_EDGE", tr("Bumper %.0f cm beyond the outline with no contact or reported edge · ", "Pare-chocs %.0f cm au-delà du contour sans contact ni bord signalé · ").format(-edgeGap * 100) +
                    BorderFrontal.summary(plan, passGap, null))
                else -> finishBorder(if (edge == BorderStop.CONTACT) "CONTACT" else "EDGE", "${stopLabel(edge)} · " + BorderFrontal.summary(plan, passGap, edgeGap))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { finishBorder("ABORTED", tr("$title: ${error.message ?: "error"}", "$title : ${error.message ?: "erreur"}")) }
    }

    internal fun stopLabel(stop: BorderStop): String = when (stop) {
        BorderStop.CONTACT -> tr("Bumper contact (${borderContactCode ?: "?"})", "Contact pare-chocs (${borderContactCode ?: "?"})")
        BorderStop.SENSOR_ALERT -> tr("Bumper sensor alert without a contact code", "Alerte capteur pare-chocs sans code de contact")
        BorderStop.EDGE_SEEN -> tr("Edge reported visually", "Bord signalé visuellement")
        BorderStop.LIMIT -> tr("Advance limit reached", "Limite d'avance atteinte")
        BorderStop.TARGET -> tr("Pass end reached", "Bout de passe atteint")
    }

    /** Manual mode with blades OFF (state 20): the only mode in which the LUBA 2 publishes bumper contact. */
    private suspend fun enterBorderBumperMode() {
        host.purgeQueuedCommands(); host.send(commands.stop(), "BORDER_BUMPER_MODE_STOP")
        val gate = RouteBladeGate.unique("BORDER_BUMPER_MANUAL_OFF", false)
        routeBladeGate = gate
        host.send(commands.manualMow(1, 0, navConfig.heightMm), gate.label)
        borderBumperWatch = true
        update { it.copy(borderFrontal = it.borderFrontal.copy(status = tr("Enabling bumper detection (manual mode, blades OFF)", "Activation de la détection pare-chocs (mode manuel, lames OFF)"))) }
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (state.value.telemetry.robotState != 20 || !gate.confirmed(devicePrevious, ((state.value.telemetry.sensorStatus ?: -1) shr 9) and 7)) {
            check(state.value.ready && !closing && log.healthy) { tr("BLE / log unavailable", "BLE / journal indisponible") }
            check(SystemClock.elapsedRealtime() < deadline) { tr("Bumper detection inactive: manual mode with blades OFF not confirmed within 5 s", "Détection pare-chocs inactive : mode manuel lames OFF non confirmé en 5 s") }
            delay(200)
        }
    }

    /** Continuous checks during the advance: link, fresh position, state, protections, battery, blades stopped. */
    private fun borderTick() {
        check(log.healthy && state.value.ready && !closing) { tr("BLE / log unavailable", "BLE / journal indisponible") }
        val t = state.value.telemetry
        val now = SystemClock.elapsedRealtime()
        NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else now - positionPrevious, t.x, t.y)?.let { error(it) }
        check(t.robotState in listOf(11, 20)) { tr("Robot state changed", "État robot changé") }
        check(SelfCheck.nominal(t.selfCheck)) { tr("Robot protection active", "Protection robot active") }
        TeachSafety.tickFailure(if (devicePrevious == 0L) null else now - devicePrevious, t.battery, t.sensorStatus, teachConfig.batteryFloor)?.let { error(it) }
    }

    /** Robot held still in manual mode with blades OFF: zero setpoint, mode keep-alive and error reading. */
    private fun borderHold(nextKeep: Long): Long {
        host.send(commands.movement(0, 0), "BORDER_HOLD")
        val now = SystemClock.elapsedRealtime()
        if (now < nextKeep) return nextKeep
        host.send(commands.errorQuery(), "BORDER_ERROR_QUERY", periodic = true)
        host.send(commands.manualMow(1, 0, navConfig.heightMm), "BORDER_BUMPER_KEEPALIVE_OFF", periodic = true)
        return now + 500
    }

    private fun borderCenter(): Point {
        val t = state.value.telemetry
        return DeadReckoning.rotationCenter(Point(requireNotNull(t.x), requireNotNull(t.y)), t.heading)
    }

    /**
     * Drives straight towards the outline until [stopGap] (predicted centre on the normal), contact, a reported edge
     * or the limit (bumper beyond the outline by the physical tolerance). Zero setpoint on exit.
     */
    private suspend fun borderAdvance(plan: BorderFrontalPlan, phase: BorderPhase, linear: Int, timeoutMs: Long, stopGap: Double?): BorderStop {
        update { it.copy(borderFrontal = it.borderFrontal.copy(phase = phase, status = phase.label)) }
        val began = SystemClock.elapsedRealtime()
        val metersPerUnit = calibration.saved()?.metersPerUnit ?: DeadReckoning.DEFAULT_METERS_PER_UNIT
        val progress = MotionProgressGuard(windowMs = 4000)
        var nextKeep = 0L
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (borderContactCode != null) return BorderStop.CONTACT
                borderErrorCode?.let { error(tr("Robot error $it", "Erreur robot $it")) }
                if (borderRequest == RobotBle.BorderRequest.EDGE) return BorderStop.EDGE_SEEN
                if (TeachSafety.bumperHealthAlert(host.guardSensors(state.value.telemetry.sensorStatus))) return BorderStop.SENSOR_ALERT
                check(now - began < timeoutMs) { tr("Maximum advance duration reached (${timeoutMs / 1000} s)", "Durée maximale de l'avance atteinte (${timeoutMs / 1000} s)") }
                borderTick()
                val center = borderCenter()
                val lateral = plan.lateral(center)
                check(abs(lateral) <= BorderFrontal.MAX_LATERAL_M) { tr("Lateral deviation of %.0f cm: the robot is not driving straight at the outline", "Écart latéral de %.0f cm : le robot ne roule pas face au contour").format(lateral * 100) }
                val gap = plan.centerGap(center)
                val predicted = BorderFrontal.predictedCenterGap(gap, now - positionPrevious, DeadReckoning.NAV_COMMAND_DELAY_MS, linear, metersPerUnit)
                update { it.copy(borderFrontal = it.borderFrontal.copy(liveFrontGapM = plan.frontGap(center))) }
                if (stopGap != null && predicted <= stopGap) return BorderStop.TARGET
                if (predicted <= plan.limitCenterM) return BorderStop.LIMIT
                check(!progress.blocked(center, positionPrevious, now, linear)) { tr("No advance observed for 4 s, with no contact reported", "Aucune avance observée depuis 4 s, sans contact signalé") }
                if (now >= nextKeep) {
                    host.send(commands.errorQuery(), "BORDER_ERROR_QUERY", periodic = true)
                    host.send(commands.manualMow(1, 0, navConfig.heightMm), "BORDER_BUMPER_KEEPALIVE_OFF", periodic = true)
                    nextKeep = now + 500
                }
                host.send(commands.movement(linear, 0), "BORDER_ADVANCE linear=$linear")
                delay(200)
            }
        } finally {
            // Test already finished (STOP and manual-mode exit queued): purge and rewrite nothing.
            if (borderBumperWatch && state.value.ready && !closing) { host.purgeQueuedCommands(); host.send(commands.movement(0, 0), "BORDER_ADVANCE_END") }
        }
    }

    /** Position after the stop: two positions published after it takes effect (5 s at most), robot held still. */
    private suspend fun borderSettled(): Point {
        update { it.copy(borderFrontal = it.borderFrontal.copy(phase = BorderPhase.SETTLING, status = BorderPhase.SETTLING.label)) }
        val stopAt = SystemClock.elapsedRealtime()
        var seen = positionPrevious
        var fixes = 0
        var nextKeep = 0L
        while (SystemClock.elapsedRealtime() - stopAt < 5000 && fixes < 2) {
            check(log.healthy && state.value.ready && !closing) { tr("BLE / log unavailable", "BLE / journal indisponible") }
            if (positionPrevious != seen) {
                seen = positionPrevious
                if (seen > stopAt + DeadReckoning.NAV_COMMAND_DELAY_MS) fixes++
            }
            nextKeep = borderHold(nextKeep)
            delay(200)
        }
        // After a contact the robot state may change: only position freshness gates the measurement.
        val t = state.value.telemetry
        NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y)?.let { error(tr("Measurement impossible: $it", "Mesure impossible : $it")) }
        return borderCenter()
    }

    internal fun finishBorder(outcome: String, detail: String, sendStop: Boolean = true) {
        if (borderId.isBlank()) return
        val positioning = state.value.borderFrontal.phase == BorderPhase.POSITIONING
        // Also called from the job itself: cancellation only takes effect at its next suspension, after this summary.
        borderJob?.cancel(); borderJob = null
        if (positioning && state.value.navTest.running) host.stopNavTest(tr("Frontal test stopped: $detail", "Essai frontal arrêté : $detail"), userInitiated = true)
        if (sendStop && state.value.ready && !closing) {
            host.purgeQueuedCommands()
            host.send(commands.stop(), "BORDER_STOP")
            if (borderBumperWatch) host.send(commands.manualBlades(false, navConfig.heightMm), "BORDER_EXIT_MANUAL_OFF")
        }
        borderBumperWatch = false
        val plan = state.value.borderFrontal.plan
        val ui = state.value.borderFrontal
        val result = TestResult(borderId, tr("Frontal test · ${plan?.label ?: "border"}", "Essai frontal · ${plan?.label ?: "bordure"}"), outcome, SystemClock.elapsedRealtime() - borderStarted, detail)
        val fields = host.campaign.resultFields(result) + mapOf("kind" to "border_frontal", "shapeId" to plan?.shapeId, "borderKind" to plan?.kind?.name,
            "contactX" to plan?.contact?.x, "contactY" to plan?.contact?.y, "passEndM" to plan?.passEndM, "outerOffsetM" to ui.outerOffsetM,
            "plannedFrontGapM" to plan?.plannedFrontGapM, "plannedPivotGapM" to plan?.plannedPivotGapM,
            "passEndFrontGapM" to ui.passEndFrontGapM, "passEndOvershootM" to ui.passEndOvershootM, "edgeFrontGapM" to ui.edgeFrontGapM,
            "contactCode" to borderContactCode, "x" to state.value.telemetry.x, "y" to state.value.telemetry.y, "heading" to state.value.telemetry.heading)
        log.report(borderId, fields); event("border_frontal_result", detail, fields)
        update { it.copy(borderFrontal = it.borderFrontal.copy(running = false, phase = BorderPhase.IDLE, outcome = outcome, status = detail,
            results = it.borderFrontal.results + result)) }
        borderId = ""; borderRequest = null
    }
}
