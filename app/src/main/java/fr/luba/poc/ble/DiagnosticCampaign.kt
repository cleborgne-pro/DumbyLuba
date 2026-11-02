@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Diagnostic campaign: planned link and command cases (stops, reconnections, watchdog), results and metrics.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class DiagnosticCampaign(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var activeCase
        get() = host.activeCase
        set(value) { host.activeCase = value }
    private val adapter get() = host.adapter
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var campaignFailure: String?
        get() = host.campaignFailure
        set(value) { host.campaignFailure = value }
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var config
        get() = host.config
        set(value) { host.config = value }
    private var current: RobotBle.Operation?
        get() = host.current
        set(value) { host.current = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private var expectedFailure: String?
        get() = host.expectedFailure
        set(value) { host.expectedFailure = value }
    private var expectedFaultObserved
        get() = host.expectedFaultObserved
        set(value) { host.expectedFaultObserved = value }
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private var heightTarget: Int?
        get() = host.heightTarget
        set(value) { host.heightTarget = value }
    private var ignoreTelemetry
        get() = host.ignoreTelemetry
        set(value) { host.ignoreTelemetry = value }
    private var lastTelemetry
        get() = host.lastTelemetry
        set(value) { host.lastTelemetry = value }
    private val log get() = host.log
    private var logicalCommandsCompleted
        get() = host.logicalCommandsCompleted
        set(value) { host.logicalCommandsCompleted = value }
    private var maxLoopDelayMs
        get() = host.maxLoopDelayMs
        set(value) { host.maxLoopDelayMs = value }
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private val queue get() = host.queue
    private val state get() = host.state
    private val stats get() = host.telemetryStats
    private var stopAckCount
        get() = host.stopAckCount
        set(value) { host.stopAckCount = value }
    private var writeTimes
        get() = host.writeTimes
        set(value) { host.writeTimes = value }
    private var zeroCommandsCompleted
        get() = host.zeroCommandsCompleted
        set(value) { host.zeroCommandsCompleted = value }
    private var zeroCommandsQueued
        get() = host.zeroCommandsQueued
        set(value) { host.zeroCommandsQueued = value }

    internal var batteryStart: Int? = null
    private var campaignJob: Job? = null
    private var campaignStart = 0L

    private fun ensureHealthy(requireState: Boolean = true) {
        campaignFailure?.let { error(it) }
        check(log.healthy) { tr("Log unavailable: campaign not allowed", "Journal indisponible : campagne interdite") }
        check(gatt != null && state.value.ready && !closing) { tr("BLE link not ready", "Liaison BLE non prête") }
        if (requireState) {
            check(devicePrevious != 0L && SystemClock.elapsedRealtime() - devicePrevious < state.value.timeoutSeconds * 1000L) { tr("Robot state missing or stale", "État robot absent ou périmé") }
            val telemetry = state.value.telemetry
            check(telemetry.battery != null && telemetry.battery > config.batteryFloor) { tr("Battery threshold ${config.batteryFloor} % reached / battery unknown", "Seuil batterie ${config.batteryFloor} % atteint / batterie inconnue") }
            check(telemetry.sensorStatus != null && (telemetry.sensorStatus shr 9) and 7 == 0) { tr("Blades not confirmed OFF by telemetry", "Lames non confirmées OFF par la télémétrie") }
        }
    }

    fun startCampaign(options: CampaignConfig) {
        if (state.value.robotRoute.running) { host.status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { host.status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mowing.active) { host.status(tr("Finish the mowing mission first", "Terminer la mission de tonte")); return }
        if (state.value.mapLoading) { host.status(tr("Wait for the map reading to finish", "Attendre la lecture de carte")); return }
        if (state.value.teach.running || state.value.bladeTestActive || bladeMayBeOn || heightTarget != null) { host.status(tr("Finish the obstacle / cutting tests before the campaign", "Terminer les tests obstacle / coupe avant la campagne")); return }
        if (state.value.navTest.running) { host.status(tr("Finish the random drive before the campaign", "Terminer la marche aléatoire avant la campagne")); return }
        if (state.value.campaign.running || closing) return
        config = options
        campaignFailure = null; ignoreTelemetry = false; expectedFailure = null
        try { ensureHealthy() } catch (error: Exception) { host.status(tr("Campaign refused: ${error.message}", "Campagne refusée : ${error.message}")); return }
        val selected = state.value.selected ?: return
        host.runLocalChecks()
        if (state.value.localResults.any { it.outcome != "PASS LOCAL" }) { host.status(tr("Local tests failed; campaign refused", "Tests locaux en échec ; campagne refusée")); return }
        writeTimes = TimingStats()
        stats.reset(state.value.telemetry)
        logicalCommandsCompleted = 0; zeroCommandsQueued = 0; zeroCommandsCompleted = 0
        maxLoopDelayMs = 0; batteryStart = state.value.telemetry.battery
        campaignStart = SystemClock.elapsedRealtime()
        val id = UUID.randomUUID().toString()
        update { it.copy(campaign = CampaignUi(id, true, "RUNNING")) }
        event("campaign_start", tr("Stationary BLE campaign", "Campagne BLE immobile"), mapOf("durationMinutes" to options.durationMinutes,
            "batteryFloor" to options.batteryFloor, "zeros5Hz" to options.zeros, "plannedCycles" to options.cycles,
            "localTelemetryMask" to options.watchdog, "localException" to options.exception, "robot" to selected.name, "address" to selected.address))
        persistCampaign()
        campaignJob = host.viewModelScope.launch {
            var caseStarted = campaignStart
            var activeStep: DiagnosticStep? = null
            try {
                val outcome = CampaignRunner.run(options, now = { SystemClock.elapsedRealtime() }, onStart = { step ->
                    activeStep = step; activeCase = step.id; caseStarted = SystemClock.elapsedRealtime()
                    update { it.copy(campaign = it.campaign.copy(step = step.title)) }
                    event("case_start", step.title, mapOf("plannedSeconds" to step.seconds))
                    persistCampaign()
                }, execute = { step ->
                    when (step.id) {
                        "baseline" -> observe(30, false)
                        "zero_5hz" -> observe(30, true)
                        "watchdog" -> testWatchdog(selected)
                        "exception" -> testException(selected)
                        "stability" -> observe(step.seconds, false, unlimited = options.durationMinutes == 0)
                        "final_stop" -> plannedStop(tr("End of campaign", "Fin campagne"))
                        else -> { plannedStop(tr("Planned cycle ${step.id}", "Cycle planifié ${step.id}")); reconnectPlanned(selected); observe(5, false) }
                    }
                }, onResult = ::addResult)
                if (outcome.success) finishCampaign("PASS BLE ONLY", tr("Campaign finished — transport stability only, not manual control while moving", "Campagne terminée — stabilité du transport, pas du contrôle manuel en mouvement"))
                else {
                    finishCampaign("FAILED", outcome.failure ?: tr("Diagnostic failed", "Échec diagnostic"))
                    if (gatt != null && !closing) host.requestStop(true, tr("Campaign failed", "Campagne en échec"))
                }
            } catch (error: CancellationException) {
                // The user action has already persisted ABORTED and requested STOP.
                throw error
            } catch (error: Exception) {
                activeStep?.let { addResult(TestResult(it.id, it.title, "FAILED", SystemClock.elapsedRealtime() - caseStarted, error.message ?: error.javaClass.simpleName)) }
                finishCampaign("FAILED", error.message ?: tr("Diagnostic failed", "Échec diagnostic"))
                if (gatt != null && !closing) host.requestStop(true, tr("Campaign failed", "Campagne en échec"))
            } finally {
                ignoreTelemetry = false; expectedFailure = null; activeCase = ""; campaignJob = null
            }
        }
    }

    internal suspend fun observe(seconds: Long, zero: Boolean, unlimited: Boolean = false) {
        val begin = SystemClock.elapsedRealtime()
        val reportsBefore = stats.reports
        var nextZero = begin
        while (unlimited || SystemClock.elapsedRealtime() - begin < seconds * 1000L) {
            ensureHealthy()
            val now = SystemClock.elapsedRealtime()
            update { it.copy(campaign = it.campaign.copy(elapsedMs = now - campaignStart)) }
            if (zero && now >= nextZero) {
                host.send(commands.stop(), "DIAGNOSTIC_ZERO linear=0 angular=0")
                nextZero = now + 200 // 5 Hz, no non-zero command.
            }
            delay(50)
        }
        ensureHealthy()
        check(stats.reports > reportsBefore) { tr("No new report during the case", "Aucun nouveau rapport pendant le cas") }
        // The last command must be acknowledged before concluding success.
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (current != null || queue.isNotEmpty()) {
            ensureHealthy()
            check(SystemClock.elapsedRealtime() < deadline) { tr("GATT queue not empty at the end of the case", "File GATT non vidée à la fin du cas") }
            delay(50)
        }
    }

    private suspend fun plannedStop(reason: String) {
        ensureHealthy()
        val before = stopAckCount
        host.alerts.intentionalAlertStop()
        host.requestStop(true, reason, intentional=true)
        awaitClosedWithStop(before)
    }
    private suspend fun awaitClosedWithStop(before: Long) {
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (gatt != null) {
            campaignFailure?.let { error(it) }
            check(SystemClock.elapsedRealtime() < deadline) { tr("GATT close not observed", "Fermeture GATT non observée") }
            delay(50)
        }
        check(stopAckCount > before) { tr("STOP not confirmed by GATT; campaign interrupted, no reconnection", "STOP non confirmé par GATT ; campagne interrompue, aucune reconnexion") }
    }
    private suspend fun reconnectPlanned(robot: SeenRobot) {
        campaignFailure?.let { error(it) }
        event("planned_reconnect", tr("Reconnection allowed by the test case, after a confirmed STOP", "Reconnexion autorisée par le cas de test, après STOP confirmé"))
        delay(1000)
        host.connect(robot)
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!state.value.ready || lastTelemetry == 0L || devicePrevious == 0L) {
            campaignFailure?.let { error(it) }
            check(SystemClock.elapsedRealtime() < deadline) { tr("No telemetry after the planned reconnection", "Télémétrie absente après reconnexion planifiée") }
            delay(50)
        }
        ensureHealthy()
    }
    private suspend fun testException(robot: SeenRobot) {
        ensureHealthy()
        val before = stopAckCount
        expectedFaultObserved = 0L
        expectedFailure = tr("BLE: Deliberate local exception injection", "BLE : Injection locale exception volontaire")
        event("fault_injection_start", tr("Deliberate exception in the app dispatch, no invalid frame sent", "Exception volontaire dans le dispatch applicatif, aucune trame invalide envoyée"))
        try {
            host.dispatch(requireNotNull(gatt)) { throw IllegalStateException(tr("Deliberate local exception injection", "Injection locale exception volontaire")) }
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (expectedFaultObserved == 0L) {
                campaignFailure?.let { error(it) }
                check(SystemClock.elapsedRealtime() < deadline && gatt != null) { tr("Expected stop on exception not observed", "Arrêt sur exception attendu non observé") }
                delay(50)
            }
            awaitClosedWithStop(before)
        } finally { expectedFailure = null }
        reconnectPlanned(robot)
        observe(5, false)
    }
    private suspend fun testWatchdog(robot: SeenRobot) {
        ensureHealthy()
        val before = stopAckCount
        expectedFaultObserved = 0L; ignoreTelemetry = true; expectedFailure = tr("FAILED — valid telemetry timeout", "FAILED — timeout télémétrie valide")
        val begin = SystemClock.elapsedRealtime()
        event("fault_injection_start", tr("Decoded reports masked locally; heartbeat and radio reception maintained", "Rapports décodés masqués localement ; heartbeat et réception radio maintenus"))
        try {
            val deadline = begin + state.value.timeoutSeconds * 1000L + 5000
            while (expectedFaultObserved == 0L) {
                campaignFailure?.let { error(it) }
                check(SystemClock.elapsedRealtime() < deadline && gatt != null) { tr("Expected watchdog not observed", "Watchdog attendu non observé") }
                delay(50)
            }
            awaitClosedWithStop(before)
            event("fault_injection_result", tr("Expected watchdog and GATT STOP confirmed", "Watchdog attendu et STOP GATT confirmés"), mapOf("sinceInjectionMs" to expectedFaultObserved - begin))
        } finally { ignoreTelemetry = false; expectedFailure = null }
        reconnectPlanned(robot)
        observe(5, false)
    }
    private fun addResult(result: TestResult) {
        update { it.copy(campaign = it.campaign.copy(results = it.campaign.results + result)) }
        event("case_result", result.title, resultFields(result)); persistCampaign()
    }
    private fun finishCampaign(outcome: String, detail: String) {
        update { it.copy(campaign = it.campaign.copy(running = false, outcome = outcome,
            elapsedMs = SystemClock.elapsedRealtime() - campaignStart, summary = detail)) }
        event("campaign_result", detail, mapOf("outcome" to outcome, "elapsedMs" to state.value.campaign.elapsedMs))
        persistCampaign(); host.refreshHistory()
    }
    internal fun abortCampaign(reason: String) {
        if (!state.value.campaign.running) return
        val running = state.value.campaign
        addResult(TestResult(activeCase, running.step, "ABORTED", 0, reason))
        finishCampaign("ABORTED", reason)
        campaignJob?.cancel(); campaignJob = null
        ignoreTelemetry = false; expectedFailure = null
    }
    internal fun resultFields(result: TestResult): Map<String, Any?> = mapOf("id" to result.id,
        "title" to result.title, "outcome" to result.outcome, "durationMs" to result.durationMs, "detail" to result.detail)
    internal fun persistCampaign() {
        val c = state.value.campaign
        if (c.id.isBlank()) return
        log.report(c.id, mapOf("id" to c.id, "outcome" to c.outcome, "step" to c.step,
            "elapsedMs" to c.elapsedMs, "summary" to c.summary, "batteryFloor" to config.batteryFloor,
            "robot" to state.value.selected?.name, "firmware" to state.value.telemetry.firmware,
            "plannedCases" to DiagnosticPlan.steps(config).map { mapOf("id" to it.id, "title" to it.title, "seconds" to it.seconds) },
            "localResults" to state.value.localResults.map(::resultFields), "results" to c.results.map(::resultFields),
            "metrics" to metricsFields()))
    }
    private fun metricsFields(): Map<String, Any?> = mapOf("reports" to stats.reports, "positions" to stats.positions,
        "reportGaps" to stats.reportGaps.fields(), "positionGaps" to stats.positionGaps.fields(), "gattWriteLatency" to writeTimes.fields(),
        "logicalCommandsCompleted" to logicalCommandsCompleted, "zeroCommandsQueued" to zeroCommandsQueued,
        "zeroCommandsCompleted" to zeroCommandsCompleted, "maxMonitorTickIntervalMs" to maxLoopDelayMs,
        "robotBatteryStartPercent" to batteryStart, "robotBatteryMinPercent" to stats.batteryMin,
        "robotBatteryLatestPercent" to state.value.telemetry.battery, "positionTypeChanges" to stats.positionTypeChanges,
        "rtkStatusChanges" to stats.rtkStatusChanges, "maxStationaryPositionDriftMeters" to stats.maxStationaryDriftM,
        "interruptionsAppRun" to state.value.interruptions, "stopGATTConfirmationsAppRun" to stopAckCount)
    // Reported value only (free disk space in the diagnostic summary), no allocation decided from it.
    @SuppressLint("UsableSpace")
    internal fun diagnosticSnapshot(now: Long) {
        val battery = host.getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val memory = Runtime.getRuntime()
        val fields = metricsFields() + mapOf("bleRssiDbm" to state.value.rssi, "mtu" to state.value.mtu,
            "telemetryAgeMs" to if (lastTelemetry == 0L) null else now - lastTelemetry,
            "deviceStateAgeMs" to if (devicePrevious == 0L) null else now - devicePrevious,
            "positionAgeMs" to if (positionPrevious == 0L) null else now - positionPrevious,
            "bluetoothEnabled" to adapter?.isEnabled,
            "phoneBatteryLevel" to battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            "phoneBatteryScale" to battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            "phoneBatteryTemperatureTenthsC" to battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1),
            "phonePlugged" to battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            "phoneThermalStatus" to if (Build.VERSION.SDK_INT >= 29) host.getApplication<Application>().getSystemService(PowerManager::class.java).currentThermalStatus else null,
            "heapUsedBytes" to memory.totalMemory() - memory.freeMemory(), "diskAvailableBytes" to host.getApplication<Application>().filesDir.usableSpace)
        event("diagnostic_snapshot", tr("Periodic summary (5 s)", "Synthèse périodique (5 s)"), fields)
        val summary = tr("Reports ${stats.reports} · Positions ${stats.positions}\nReport interval p95 ${stats.reportGaps.percentile(95) ?: "—"} ms · max ${stats.reportGaps.maxMs} ms\nGATT p95 ${writeTimes.percentile(95) ?: "—"} ms · max ${writeTimes.maxMs} ms", "Rapports ${stats.reports} · Positions ${stats.positions}\nIntervalle rapports p95 ${stats.reportGaps.percentile(95) ?: "—"} ms · max ${stats.reportGaps.maxMs} ms\nGATT p95 ${writeTimes.percentile(95) ?: "—"} ms · max ${writeTimes.maxMs} ms")
        update { it.copy(diagnosticSummary = summary, campaign = if (it.campaign.running) it.campaign.copy(elapsedMs = now - campaignStart) else it.campaign) }
        if (state.value.campaign.running) persistCampaign()
    }
}
