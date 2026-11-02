@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.autonomy.GatewayDetachment
import fr.luba.poc.camera.ObstaclePhotoRequest
import fr.luba.poc.diagnostics.*
import fr.luba.poc.difficulty.DifficultyClassifier
import fr.luba.poc.gateway.EMERGENCY_STOP_EVENT
import fr.luba.poc.gateway.GatewayHealthStore
import fr.luba.poc.gateway.GatewayStartReport
import fr.luba.poc.gateway.GatewayStopReport
import fr.luba.poc.gateway.GatewayWireText
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.maintenance.BladeMaintenanceSettings
import fr.luba.poc.maintenance.BladeWear
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import fr.luba.poc.weather.MowingRainCheck
import fr.luba.poc.weather.WeatherService
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local alerts: robot errors, work window, gateway battery and detachment, route and motion failures.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class RobotAlertCenter(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var alertCommunicationExpected
        get() = host.alertCommunicationExpected
        set(value) { host.alertCommunicationExpected = value }
    private var alertDeviceAt: Long?
        get() = host.alertDeviceAt
        set(value) { host.alertDeviceAt = value }
    private var alertLastTelemetryAt
        get() = host.alertLastTelemetryAt
        set(value) { host.alertLastTelemetryAt = value }
    private var alertLinkFault
        get() = host.alertLinkFault
        set(value) { host.alertLinkFault = value }
    private val alertMonitor get() = host.alertMonitor
    private var alertRtkAt: Long?
        get() = host.alertRtkAt
        set(value) { host.alertRtkAt = value }
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private val difficulties get() = host.difficulties
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private val liftGuard get() = host.liftGuard
    private val notifications get() = host.notifications
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private var preparationJob: Job?
        get() = host.preparationJob
        set(value) { host.preparationJob = value }
    private var routeIntent: RouteIntent?
        get() = host.routeIntent
        set(value) { host.routeIntent = value }
    private val state get() = host.state
    private var stopPending
        get() = host.stopPending
        set(value) { host.stopPending = value }
    private var workWindowEnding
        get() = host.workWindowEnding
        set(value) { host.workWindowEnding = value }

    /** Return to base on confirmed rain and resume after it ([fr.luba.poc.weather.RainReturn]). */
    internal val rain = RainReturnRunner(host)

    /** Reason of the mission stop requested through [workWindowEnding], robot on the base: rain or end of the working hours. */
    internal fun stopReason(): String = if (rain.stopping) tr("Rain confirmed: robot on the base, mowing resumes after the rain", "Pluie confirmée : robot sur la base, tonte reprise après la pluie")
        else tr("End of the working hours (${WorkWindowSetting.value.label}): robot on the base, mowing to be resumed", "Fin de la plage horaire de travail (${WorkWindowSetting.value.label}) : robot sur la base, tonte à reprendre")

    /** Status of the return of that stop. */
    internal fun stopReturnStatus(): String = if (rain.stopping) tr("Rain confirmed · BLE return · blades OFF", "Pluie confirmée · retour BLE · lames OFF")
        else tr("End of the working hours ${WorkWindowSetting.value.label} · BLE return · blades OFF", "Fin de la plage horaire ${WorkWindowSetting.value.label} · retour BLE · lames OFF")

    internal fun alert(kind: AlertKind, severity: Severity, detail: String = kind.title, photo: String? = null) {
        val ui = state.value
        val known = ui.lastKnown?.takeIf { ui.selected == null || it.robotId == ui.selected.address }
        val point = known?.last
        val position = point?.let { tr("x=%.2f m, y=%.2f m · %s (last FIX)", "x=%.2f m, y=%.2f m · %s (dernier FIX)").format(I18n.locale, it.x, it.y,
            SimpleDateFormat("dd/MM HH:mm:ss", I18n.locale).format(Date(it.at))) }
        notifications.emit(RobotAlert(kind, severity, Clock.now(), AlertContext(
            ui.selected?.address ?: known?.robotId ?: tr("unknown", "inconnu"), ui.selected?.name ?: known?.robotName ?: "Robot",
            tr("${ui.connection.label} · state ${ui.telemetry.robotState ?: known?.robotState ?: "unknown"} · ${ui.robotRoute.phase}", "${ui.connection.label} · état ${ui.telemetry.robotState ?: known?.robotState ?: "inconnu"} · ${ui.robotRoute.phase}"),
            ui.telemetry.battery ?: known?.battery, position), detail, photo = photo))
    }
    /**
     * "Obstacle encountered" notification for a bumper contact followed by a back-off (local route, move to a point),
     * once the photo is taken or missed: obstacle photo attached in large format if it exists.
     */
    internal fun alertObstacle(request: ObstaclePhotoRequest, outcome: String, photo: File? = null) {
        val where = if (request.x != null && request.y != null)
            tr(" at x=%.2f m, y=%.2f m", " à x=%.2f m, y=%.2f m").format(I18n.locale, request.x, request.y) else ""
        alert(AlertKind.OBSTACLE, Severity.WARNING, tr("Bumper contact (${request.code})$where · $outcome", "Contact pare-chocs (${request.code})$where · $outcome"), photo?.absolutePath)
    }
    /** Activity that moves or may move the robot or spin the blades. */
    internal fun motionActive(): Boolean {
        val s = state.value
        return s.robotRoute.running || s.navTest.running || s.manualActive || s.teach.running || s.silenceTest.running ||
            s.bladeTestActive || s.heightSequence.running || s.mowing.running || bladeMayBeOn ||
            preparationJob?.isActive == true || liftGuard.moving(SystemClock.elapsedRealtime())
    }
    /**
     * Triage of each robot error code according to [RobotErrorCatalog]: label, log, alert and stop.
     * Returns true for lift / bumper codes, which keep their dedicated safety handling.
     */
    internal fun triageRobotError(error: RobotError): Boolean {
        val info = RobotErrorCatalog.describe(error.code)
        update { it.copy(lastRobotError = RobotErrorReport(info, error.source, error.timestamp, Clock.now())) }
        DifficultyClassifier.fromErrorCode(error.code)?.let { difficulties.recordDifficulty(it, tr("Robot error ${info.summary}", "Erreur robot ${info.summary}"), code = error.code) }
        if (info.response == RobotErrorResponse.SAFETY) return true
        event("robot_error", tr("Robot error ${info.summary}", "Erreur robot ${info.summary}"), mapOf("code" to error.code, "source" to error.source,
            "robotErrorTime" to error.timestamp, "category" to info.category.name, "response" to info.response.name, "known" to info.known))
        when (info.response) {
            RobotErrorResponse.LOG, RobotErrorResponse.SAFETY -> Unit
            RobotErrorResponse.WARN -> {
                host.status(tr("Robot: ${info.summary}", "Robot : ${info.summary}"))
                alert(AlertKind.ROBOT_ERROR, Severity.WARNING, "${info.summary} — ${info.advice}")
            }
            RobotErrorResponse.STOP -> {
                val active = motionActive()
                // Route, navigation and manual control already get the "Unexpected stop" alert from requestStop.
                val alerted = active && (state.value.robotRoute.running || state.value.navTest.running || state.value.manualActive)
                if (active) host.requestStop(false, tr("Robot error ${info.summary}", "Erreur robot ${info.summary}")) else host.status(tr("Robot: ${info.summary}", "Robot : ${info.summary}"))
                if (!alerted) alert(AlertKind.ROBOT_ERROR, if (active) Severity.CRITICAL else Severity.WARNING, "${info.summary} — ${info.advice}")
            }
        }
        return false
    }
    internal fun observeAlerts() {
        val ui = state.value
        val now = SystemClock.elapsedRealtime()
        val t = ui.telemetry
        alertMonitor.tick(now, if(alertCommunicationExpected) alertLastTelemetryAt else now, t.battery,
            ui.ready && alertDeviceAt?.let { now-it in 0..5000 } == true,
            // RTK loss: watched only during movement (route, mowing, manual control, test), never with the robot idle at the base.
            RtkAlertPolicy.watched(ui.ready, motionActive(), alertLinkFault),
            RtkAlertPolicy.freshFix(t, alertRtkAt, now),
            ::alert)
        // Rain observed or forecast within 30 min during a mowing: alert only, no command to the robot.
        val weather = WeatherService.flow.value
        val rain = if (weather.monitoring && weather.location != null && (ui.mowing.running || ui.robotRoute.running))
            MowingRainCheck.check(weather.assessment, weather.forecast, Clock.now(), 30).takeIf { it.warn } else null
        alertMonitor.condition(AlertKind.RAIN, rain?.let { Severity.WARNING }) { kind, severity -> alert(kind, severity, rain?.message ?: kind.title) }
        this.rain.observe(ui, weather)
        // Blades to replace: counter received over Bluetooth, interval and last change noted on the phone (no robot command).
        val blades = t.maintenance?.let { m -> ui.selected?.address?.let { id ->
            BladeWear.status(m, BladeMaintenanceSettings.change(id), BladeMaintenanceSettings.intervalHours) } }
        alertMonitor.condition(AlertKind.BLADE_WEAR, if (blades?.due == true) Severity.WARNING else null) { kind, severity -> alert(kind, severity, blades?.detail ?: kind.title) }
        // Robot's own Wi-Fi level, compared with the gateway's in the weak Wi-Fi advice.
        if (ui.viaGateway) t.wifiRssi?.let { GatewayHealthStore.robotWifi(it) }
        observeGatewayBattery(ui)
    }
    /**
     * Working hours ([fr.luba.poc.mowing.WorkWindowSetting]), checked every second. Outside them: a mowing in progress
     * stops at the next tracking check, then returns to base ([workWindowEnding], [startRoute] loop);
     * an idle robot left outside is brought back once per out-of-hours period ([workWindowReturn]).
     */
    internal fun observeWorkWindow() {
        val window = WorkWindowSetting.value
        if (window.allows(LocalTime.now())) { workWindowReturnTried = false; return }
        val ui = state.value
        if (ui.robotRoute.running) {
            if (routeIntent == RouteIntent.FOLLOW && !workWindowEnding) {
                workWindowEnding = true
                rain.windowEnding()
                event("work_window_end", tr("End of the working hours (${window.label}): mowing stopped and return to base", "Fin de la plage horaire de travail (${window.label}) : arrêt de la tonte et retour à la base"),
                    mapOf("window" to window.label))
                host.status(tr("End of the working hours (${window.label}): return to base", "Fin de la plage horaire de travail (${window.label}) : retour à la base"))
            }
            return
        }
        if (workWindowReturnTried) return
        val t = ui.telemetry
        val now = SystemClock.elapsedRealtime()
        val idle = ui.ready && gatt != null && !closing && !stopPending && !ui.manualActive && !ui.navTest.running && !ui.teach.running &&
            !ui.mowing.active && !ui.nativeReturnActive && !ui.silenceTest.running && !ui.unlockPending && !ui.backOffPending &&
            !ui.taskStopPending && !ui.campaign.running && !ui.bladeTestActive && preparationJob?.isActive != true
        // Robot charging, Mammotion task in progress (mowing, return, pause) or protection active: nothing to bring back.
        val x = t.x; val y = t.y
        if (!idle || now - devicePrevious !in 0..3000 || now - positionPrevious !in 0..3000 || x == null || y == null ||
            t.chargeState in setOf(1, 2) || t.robotState in setOf(13, 14, 19, 39) || !SelfCheck.nominal(t.selfCheck)) return
        val map = ui.robotMap
        if (!map.complete) return
        val store = BaseStore(host.getApplication(), map)
        val base = store.resolve(map) ?: return
        // More than one meter from the base: outside, even if the charge state of a docked robot is not published.
        if (Point(x, y).distance(base.point) <= RobotBle.WORK_WINDOW_OUTSIDE_M) return
        workWindowReturnTried = true
        workWindowReturn(window, store, base, map, Point(x, y))
    }

    /** Return to base of a robot left outside out of hours: same station route as the "Recharge" button. */
    private fun workWindowReturn(window: WorkWindow, store: BaseStore,
                                 base: MapBase, map: RobotMap, start: Point) {
        event("work_window_return", tr("Outside the working hours (${window.label}): robot left outside brought back to base", "Hors plage horaire (${window.label}) : robot resté dehors ramené à la base"),
            mapOf("window" to window.label, "x" to start.x, "y" to start.y))
        host.status(tr("Outside the working hours (${window.label}): automatic return to base", "Hors plage horaire (${window.label}) : retour automatique à la base"))
        host.viewModelScope.launch {
            val failure = try {
                val task = MowingTaskStore(host.getApplication()).read()
                val config = SimConfig(returnPercent = task.chargeLow.toDouble(), resumePercent = task.chargeHigh.toDouble(),
                    baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true)
                val engine = withContext(Dispatchers.Default) {
                    val routes = RoutePlanning.mission(map, state.value.planningObstacles, store.accesses(), base,
                        recordedBoundary = !map.source.contains("DÉMONSTRATION", true), avoidAreas = state.value.difficulties.transitAvoidance)
                    RoutePlanning.station(routes, map, base, start, config)
                }
                if (host.baseAction(engine, leave = false)) null
                else state.value.robotRoute.status.removePrefix(tr("Start refused: ", "Départ refusé : ")).ifBlank { state.value.nativeReturnStatus }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { error.message ?: error.javaClass.simpleName }
            if (failure != null) {
                event("work_window_return_failed", tr("Automatic out-of-hours return failed: $failure", "Retour automatique hors plage impossible : $failure"), mapOf("window" to window.label))
                alert(AlertKind.RETURN_FAILED, Severity.WARNING, tr("Outside the working hours: automatic return to base failed ($failure)", "Hors plage horaire : retour automatique à la base impossible ($failure)"))
            }
        }
    }
    /** Gateway phone battery (fresh HEALTH frame); alert cleared with direct Bluetooth or once plugged in. */
    private fun observeGatewayBattery(ui: RobotUi) {
        if (!alertMonitor.armed) return
        if (!ui.viaGateway) { if (ui.ready) alertMonitor.condition(AlertKind.GATEWAY_BATTERY, null) { _, _ -> }; return }
        val health = GatewayHealthStore.latest.value?.takeIf { it.fresh() }?.health ?: return
        val level = GatewayAlertPolicy.battery(health.batteryPercent, health.charging, alertMonitor.severity(AlertKind.GATEWAY_BATTERY))
        alertMonitor.condition(AlertKind.GATEWAY_BATTERY, level) { kind, severity ->
            alert(kind, severity, tr("Gateway at ${health.batteryPercent} % on battery", "Passerelle à ${health.batteryPercent} % sur batterie") +
                if (severity == Severity.CRITICAL) tr(": shutdown imminent, robot link at risk", " : extinction proche, liaison robot menacée") else "")
        }
    }
    /**
     * Gateway phone fallen off the robot (event from the gateway, which has already stopped the robot): drop point on the
     * map, status and critical alert with where to pick it up.
     */
    internal fun gatewayDetached(text: String, fields: Map<String, Any?>) {
        // Text rebuilt in this phone's language from the fields; the gateway's own text otherwise.
        val verdict = GatewayDetachment.Verdict.fromFields(fields, Clock.now())
        val shown = verdict?.text ?: GatewayWireText.display(text)
        verdict?.let { v -> update { it.copy(gatewayDrop = GatewayDrop(v.point.x, v.point.y, Clock.now(), v.probable, shown)) } }
        host.status(tr("$shown · robot stopped: pick up the phone before restarting", "$shown · robot arrêté : récupérer le téléphone avant de relancer"))
        alert(AlertKind.GATEWAY_DETACHED, Severity.CRITICAL, shown + if (verdict != null) tr(" · marker on the map", " · repère sur la carte") else "")
    }
    /** The gateway phone no longer responds (Wi-Fi off, switched off, app closed): one alert until telemetry returns. */
    internal fun alertGatewayLost(detail: String) {
        if (!alertMonitor.armed) return
        val severity = GatewayAlertPolicy.lost(motionActive())
        alertMonitor.condition(AlertKind.GATEWAY_LOST, severity) { kind, level -> alert(kind, level, detail) }
    }
    /**
     * Safety stop written by the gateway during the outage, reported on reconnection: the loss had been reported as
     * "STOP not guaranteed". Confirmed by GATT only: the mechanical stop still has to be checked on site.
     */
    /** Events of the gateway that raise an alert: stop written while the pilot was away, restart of the ESP32 board. */
    internal fun gatewayEvent(kind: String, text: String) = when (kind) {
        "gateway_$EMERGENCY_STOP_EVENT" -> gatewayStopReported(text)
        "gateway_${GatewayStartReport.EVENT}" -> gatewayStarted(text)
        else -> Unit
    }
    private val gatewayStarts = GatewayStartReport.Watch()
    private fun gatewayStarted(text: String) {
        if (gatewayStarts.unexpectedRestart(text) != null) alert(AlertKind.GATEWAY_RESTARTED, Severity.WARNING, text)
    }
    internal fun gatewayStopReported(text: String) {
        val confirmed = GatewayStopReport.confirmed(text)
        host.status(if (confirmed) tr("$text — confirmed by GATT, check the physical stop", "$text — confirmé par GATT, vérifier l'arrêt physique") else tr("$text — robot stop not guaranteed", "$text — arrêt du robot non garanti"))
        if (confirmed) alert(AlertKind.RECOVERY, Severity.INFO, tr("$text (confirmed by GATT, not mechanically)", "$text (confirmé par GATT, pas mécaniquement)"))
        else alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, tr("$text: check that the robot has physically stopped", "$text : vérifier l'arrêt physique du robot"))
    }
    internal fun intentionalAlertStop() { alertMonitor.disarm(); alertCommunicationExpected=false; alertLinkFault=false }
    internal fun alertRouteFailure(detail: String) {
        if(!state.value.robotRoute.running) return
        RouteAlertPolicy.failure(state.value.robotRoute.phase)?.let { alert(it, Severity.CRITICAL, detail) }
    }
    internal fun alertMotionFailure(detail: String) {
        val blocked = detail.contains(tr("block", "bloqu"), ignoreCase=true) || detail.contains(tr("progress", "progression"), ignoreCase=true)
        if(blocked) alert(AlertKind.BLOCKED, Severity.CRITICAL, detail)
    }
    /** Automatic return of a robot left outside already attempted for this out-of-hours period. */
    private var workWindowReturnTried = false
}
