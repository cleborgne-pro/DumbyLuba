@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.gateway.GatewayLink
import fr.luba.poc.history.BlackBox
import fr.luba.poc.history.BlackBoxFrame
import fr.luba.poc.history.BlackBoxRecorder
import fr.luba.poc.history.BlackBoxTrigger
import fr.luba.poc.history.CoverageBuilder
import fr.luba.poc.history.CoverageMap
import fr.luba.poc.history.CoverageView
import fr.luba.poc.history.MowingCalibration
import fr.luba.poc.history.MowingComparison
import fr.luba.poc.history.MowingForecaster
import fr.luba.poc.history.MowingHistoryStore
import fr.luba.poc.history.MowingOutcome
import fr.luba.poc.history.MowingRecord
import fr.luba.poc.history.MowingTracker
import fr.luba.poc.history.RealCoverage
import fr.luba.poc.history.TaskEvent
import fr.luba.poc.history.TaskEventRecorder
import fr.luba.poc.history.TaskEventType
import fr.luba.poc.history.TaskKind
import fr.luba.poc.history.TaskTrack
import fr.luba.poc.history.TaskTrackStore
import fr.luba.poc.history.TrackLinkState
import fr.luba.poc.history.TrackMeta
import fr.luba.poc.history.TrackPhase
import fr.luba.poc.history.TrackSample
import fr.luba.poc.history.TrackSampler
import fr.luba.poc.history.reasonText
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.ZoneOrientation
import fr.luba.poc.planning.ZoneOrientationStore
import fr.luba.poc.planning.ZoneProgress
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.MowingLiveStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task history of a mission: record, black box, tracking trace and actual coverage, written off the main thread.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class TaskHistoryRecorder(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private var batteryStart: Int?
        get() = host.campaign.batteryStart
        set(value) { host.campaign.batteryStart = value }
    private val blackBox get() = host.blackBox
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private val difficulties get() = host.difficulties
    private var gatewayOutageSince
        get() = host.gatewayOutageSince
        set(value) { host.gatewayOutageSince = value }
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private val handler get() = host.handler
    private var lastSetpoint: MotionSetpoints.Setpoint?
        get() = host.lastSetpoint
        set(value) { host.lastSetpoint = value }
    private var lastSetpointAt
        get() = host.lastSetpointAt
        set(value) { host.lastSetpointAt = value }
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private var remoteLegActive
        get() = host.remoteLegActive
        set(value) { host.remoteLegActive = value }
    private var routeCheckpoint: MissionCheckpoint?
        get() = host.routes.routeCheckpoint
        set(value) { host.routes.routeCheckpoint = value }
    private var slideField
        get() = host.slideField
        set(value) { host.slideField = value }
    private val state get() = host.state
    private var taskEvents: TaskEventRecorder?
        get() = host.taskEvents
        set(value) { host.taskEvents = value }
    /** Track samples of the running task not written yet (flushed every 300 samples or 10 s). */
    private val trackBuffer = ArrayList<TrackSample>()

    // Local files written in request order by the coroutines started in init, off the main thread: mowing history
    // (estimate frozen at launch, measured time and battery, end report), directions of the zones actually mowed
    // ("change every session"), task tracks (timestamped positions, map and prepared route, for the replay).
    private val filesDir = host.getApplication<Application>().filesDir
    private val historyStore = MowingHistoryStore(filesDir)
    private val historyWrites = Channel<List<MowingRecord>>(Channel.CONFLATED)
    private val orientationStore = ZoneOrientationStore(filesDir)
    private val orientationWrites = Channel<Map<Long, ZoneOrientation>>(Channel.CONFLATED)
    private val trackStore = TaskTrackStore(filesDir)
    private val trackWrites = Channel<TrackWrite>(Channel.UNLIMITED)
    @Volatile private var trackFull = false

    private var historyTracker: MowingTracker? = null
    private var historyJob: Job? = null
    private var historyRechargeBase = 0
    /** Plan of the mowing being measured (preview and map), to record the directions it actually mowed. */
    private var orientationPlan: Pair<Preview, RobotMap>? = null
    private var historySavedAt = 0L
    private var trackSampler: TrackSampler? = null
    // Previous position received (elapsed realtime, rotation centre): measured speed of the track samples.
    private var trackLastAt = 0L
    private var trackLastPoint: Point? = null
    private var trackLastSpeed = Double.NaN
    private val blackBoxTrigger = BlackBoxTrigger()
    private var trackJob: Job? = null
    private var trackPositionAt = 0L
    private var trackFlushedAt = 0L
    /**
     * Start (or resume) of the measurement of a real mowing: estimate frozen with the actual battery and
     * the calibration from previous mowings. A resume extends the record of the interrupted mowing;
     * a resume without a record (mowing older than the history) is not measured.
     */
    internal fun historyBegin(engine: MowingSimulation, checkpoint: MissionCheckpoint?, resumed: Boolean, battery: Int?,
                             kind: TaskKind = TaskKind.MOW, heightMm: Int = checkpoint?.heightMm ?: 60) {
        historyJob?.cancel(); historyJob = null
        trackStop()
        val robotId = checkpoint?.robotId ?: state.value.selected?.address ?: return
        val startedAt = checkpoint?.startedAt ?: Clock.now()
        val id = "$robotId-$startedAt"
        val history = state.value.mowingHistory
        val record = if (resumed && checkpoint != null) history.firstOrNull { it.id == id }?.let { r -> r.copy(outcome = MowingOutcome.RUNNING,
            reason = null, endedAt = null, resumes = checkpoint.resumes, heightMm = checkpoint.heightMm, doneMeters = checkpoint.doneMeters,
            links = r.links + host.trackingLink()) } ?: return
        else {
            val c = engine.config
            val zones = engine.map.shapes.filter { it.kind == ShapeKind.ZONE && it.id in engine.preview.zoneIds }
            val forecast = runCatching { MowingForecaster.forecast(engine.preview.meters, c.speedMps, c.autonomyMinutes,
                c.chargeMinutes, (battery ?: c.initialBattery.toInt()).toDouble(), c.returnPercent, c.resumePercent,
                MowingCalibration.from(history, robotId)) }.getOrElse { return }
            // Survey or route with blades stopped: progress follows the distance of the prepared route.
            MowingRecord(id, robotId, state.value.selected?.name, startedAt,
                zoneNames = engine.preview.zoneIds.mapNotNull { zid -> zones.firstOrNull { it.id == zid }?.name?.trim()?.ifBlank { null } },
                areaM2 = CoveragePlanner.selectedArea(engine.map, engine.preview.zoneIds.toSet()), mode = engine.preview.mode.label,
                heightMm = heightMm, speedMps = c.speedMps, returnPercent = checkpoint?.returnPercent ?: c.returnPercent.toInt(),
                resumePercent = checkpoint?.resumePercent ?: c.resumePercent.toInt(),
                plannedMeters = engine.preview.meters, cuttingMeters = checkpoint?.totalMeters ?: engine.preview.meters,
                doneMeters = checkpoint?.doneMeters ?: 0.0, forecast = forecast, batteryStart = battery, batteryEnd = battery, kind = kind,
                links = setOf(host.trackingLink()))
        }
        orientationPlan = if (kind == TaskKind.MOW) engine.preview to engine.map else null
        trackBegin(id, engine, kind == TaskKind.MOW)
        taskEvent(if (resumed) TaskEventType.RESUME else TaskEventType.START,
            "${record.kind.label} · ${record.mode} · ${record.zoneNames.joinToString(", ")}",
            mapOf("mode" to record.mode, "zones" to record.zoneNames.size, "link" to host.trackingLink().name, "progress" to record.progress))
        historyTracker = MowingTracker(record.totals)
        historyRechargeBase = record.recharges
        historySavedAt = 0L
        update { it.copy(mowingRecord = record) }
        historyPersist(record)
        event("mowing_history_start", if (resumed) tr("Mowing measurement resumed", "Reprise de la mesure de tonte") else tr("Mowing measurement started", "Mesure de tonte démarrée"),
            mapOf("id" to id, "forecastSeconds" to record.forecast.totalSeconds, "forecastRecharges" to record.forecast.recharges,
                "timeFactor" to record.forecast.timeFactor, "autonomyMinutes" to record.forecast.autonomyMinutes,
                "chargeMinutes" to record.forecast.chargeMinutes, "calibratedFrom" to record.forecast.calibratedFrom,
                "trackingLink" to host.trackingLink().name) + wetnessFields())
        historyJob = host.viewModelScope.launch { while (true) { historySample(); delay(2000) } }
    }

    /** Robot position (rotation centre, as in the track) and battery for a task event. */
    internal fun taskPose(): Triple<Double?, Double?, Int?> {
        val t = state.value.telemetry
        val center = if (t.x != null && t.y != null) DeadReckoning.rotationCenter(Point(t.x, t.y), t.heading) else null
        return Triple(center?.x, center?.y, t.battery)
    }

    internal fun taskEventWrite(event: TaskEvent?) {
        val recorder = taskEvents ?: return
        if (event == null) return
        trackWrites.trySend(TrackWrite(recorder.taskId, events = listOf(event)))
        // Incident: black box frozen a few seconds later, so that it shows what followed (stop, recovery).
        if (event.type.incident && blackBoxTrigger.incident(recorder.taskId, event))
            handler.postDelayed({ blackBoxFreeze() }, BlackBoxRecorder.AFTER_MS)
    }

    private fun blackBoxFreeze() {
        val pending = blackBoxTrigger.take() ?: return
        val box = blackBox.freeze(pending.atMs, pending.types, pending.detail)
        trackWrites.trySend(TrackWrite(pending.taskId, boxes = listOf(box)))
        event("task_black_box", tr("Black box frozen: ${pending.types.joinToString(" + ") { it.label }}", "Black Box figée : ${pending.types.joinToString(" + ") { it.label }}"),
            mapOf("frames" to box.frames.size, "commands" to box.commands.size, "notes" to box.notes.size))
    }

    /** Current state of the robot and of the link, for the black box. */
    private fun blackBoxFrame(t: Telemetry, command: MotionSetpoints.Setpoint?): BlackBoxFrame {
        val center = if (t.x != null && t.y != null) DeadReckoning.rotationCenter(Point(t.x, t.y), t.heading) else null
        return BlackBoxFrame(Clock.now(), center?.x ?: Double.NaN, center?.y ?: Double.NaN, t.heading ?: Double.NaN,
            command?.linear, command?.angular, trackLastSpeed, t.battery ?: -1, t.robotState ?: -1, t.sensorStatus ?: -1, t.rtkStatus ?: -1,
            t.gpsStars ?: -1, trackLinkState(), state.value.robotRoute.phase.name, t.bleRssi, t.wifiRssi)
    }

    /** Event of the running task signalled directly (start, end, STOP, link lost, obstacle). */
    internal fun taskEvent(type: TaskEventType, detail: String, data: Map<String, Any?> = emptyMap()) {
        val recorder = taskEvents ?: return
        val (x, y, battery) = taskPose()
        taskEventWrite(recorder.record(TaskEvent(Clock.now(), type, detail, x ?: Double.NaN, y ?: Double.NaN,
            battery ?: -1, data.mapNotNull { (k, v) -> v?.let { k to it.toString() } }.toMap())))
    }

    /** Link state for the track: autonomous tracking by the gateway (Wi-Fi cut or not), relay or direct Bluetooth. */
    private fun trackLinkState(): TrackLinkState = when {
        gatt == null -> TrackLinkState.UNKNOWN
        remoteLegActive && gatewayOutageSince != 0L -> TrackLinkState.GATEWAY_OUTAGE
        remoteLegActive -> TrackLinkState.GATEWAY
        gatt is GatewayLink -> TrackLinkState.RELAY
        else -> TrackLinkState.BLUETOOTH
    }

    /**
     * Start (or resume) of the track of task [id]: context (map, prepared route) recorded once, then
     * each new position received, filtered by [fr.luba.poc.history.TrackSampler] and written in batches.
     */
    private fun trackBegin(id: String, engine: MowingSimulation, mowing: Boolean) {
        trackStop()
        taskEvents = TaskEventRecorder(id)
        trackFull = false; trackLastAt = 0L; trackLastPoint = null; trackLastSpeed = Double.NaN
        blackBox.clear()
        coverageBegin(id, engine, mowing)
        val transit = mutableListOf<List<Point>>()
        var line = mutableListOf<Point>()
        engine.edges.forEach { e ->
            if (e.cutting) { if (line.size >= 2) transit += line; line = mutableListOf(); return@forEach }
            if (line.lastOrNull() != e.a) { if (line.size >= 2) transit += line; line = mutableListOf(e.a) }
            line += e.b
        }
        if (line.size >= 2) transit += line
        trackWrites.trySend(TrackWrite(id, meta = TrackMeta(engine.map, engine.preview.cuttingPaths, transit, engine.preview.zoneIds)))
        trackSampler = TrackSampler()
        trackPositionAt = 0L; trackFlushedAt = SystemClock.elapsedRealtime()
        trackJob = host.viewModelScope.launch { while (true) { trackSample(id); delay(200) } }
    }

    /** Robot activity for the track: recharging and return first, then blades according to the telemetry. */
    private fun trackPhase(t: Telemetry): TrackPhase {
        val phase = state.value.robotRoute.phase
        return when {
            t.chargeState in setOf(1, 2) || phase == RoutePhase.CHARGING -> TrackPhase.CHARGING
            phase == RoutePhase.RETURNING || phase == RoutePhase.ALIGNING -> TrackPhase.RETURN
            phase == RoutePhase.DOCKING -> TrackPhase.DOCKING
            MowingLiveStats.bladeBits(t.sensorStatus).let { it == 1 || (it == null && bladeMayBeOn) } -> TrackPhase.CUTTING
            else -> TrackPhase.TRAVEL
        }
    }

    private fun trackSample(id: String) {
        val sampler = trackSampler ?: return
        val at = positionPrevious
        val t = state.value.telemetry
        if (at != 0L && at != trackPositionAt && t.x != null && t.y != null) {
            trackPositionAt = at
            // Same point as the map trail: the rotation centre, 8 cm ahead of the published point.
            val center = DeadReckoning.rotationCenter(Point(t.x, t.y), t.heading)
            // Measured speed between two received positions (positions about 1 s apart).
            val previous = trackLastPoint
            val speed = if (previous != null && at - trackLastAt in 200..5_000) previous.distance(center) * 1000.0 / (at - trackLastAt) else Double.NaN
            trackLastAt = at; trackLastPoint = center; trackLastSpeed = speed
            // Command written by this phone in the last 3 s; none while the gateway tracks the route.
            val command = lastSetpoint?.takeIf { !remoteLegActive && SystemClock.elapsedRealtime() - lastSetpointAt <= 3_000 }
            val sample = TrackSample(Clock.now(), center.x, center.y, t.heading ?: Double.NaN,
                trackPhase(t), t.battery ?: -1, command?.linear, command?.angular, speed, t.rtkStatus ?: -1, t.gpsStars ?: -1, trackLinkState())
            if (sampler.accept(sample)) { trackBuffer += sample; coverageAdd(id, sample) }
        }
        blackBox.frame(blackBoxFrame(t, lastSetpoint?.takeIf { !remoteLegActive && SystemClock.elapsedRealtime() - lastSetpointAt <= 3_000 }))
        if (trackBuffer.size >= 300 || (trackBuffer.isNotEmpty() && SystemClock.elapsedRealtime() - trackFlushedAt >= 10_000)) trackFlush(id)
    }

    private fun trackFlush(id: String, keep: List<String>? = null) {
        trackFlushedAt = SystemClock.elapsedRealtime()
        if (trackBuffer.isEmpty() && keep == null) return
        trackWrites.trySend(TrackWrite(id, trackBuffer.toList(), keep = keep, seal = keep != null))
        trackBuffer.clear()
    }

    /** End of the current track: last points written; [keep] limits the kept tracks to recent tasks. */
    private fun trackStop(id: String? = null, keep: List<String>? = null) {
        val job = trackJob ?: return
        job.cancel(); trackJob = null; trackSampler = null; taskEvents = null
        if (id != null) trackFlush(id, keep) else trackBuffer.clear()
        coverageEnd()
    }

    // Real coverage of the current mowing ([fr.luba.poc.history.CoverageMap]), fed by the track positions and shown on
    // the map ("Real coverage" layer); the last one stays displayed once the task ends.
    private var coverageBuilder: CoverageBuilder? = null
    private var coverageTask: String? = null
    private var coveragePublishedAt = 0L

    private fun coverageBegin(id: String, engine: MowingSimulation, mowing: Boolean) {
        coverageBuilder = null; coverageTask = null
        if (!mowing) return
        val builder = CoverageBuilder(engine.map, engine.preview.zoneIds)
        coverageBuilder = builder; coverageTask = id
        coveragePublish(force = true)
        // Resume: the positions saved before the interruption are swept too.
        host.viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { trackStore.read(id)?.samples }.getOrNull() }
            if (saved.isNullOrEmpty() || coverageBuilder !== builder) return@launch
            builder.addRun(saved)
            coveragePublish(force = true)
        }
    }

    private fun coverageAdd(id: String, sample: TrackSample) {
        if (coverageTask != id) return
        coverageBuilder?.add(sample) ?: return
        coveragePublish()
    }

    /** Snapshot for the map, at most every 2 s while the task runs. */
    private fun coveragePublish(force: Boolean = false, running: Boolean = true) {
        val builder = coverageBuilder ?: return
        val id = coverageTask ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - coveragePublishedAt < 2_000) return
        coveragePublishedAt = now
        RealCoverage.publish(builder.snapshot()?.let { CoverageView(id, it, running) })
    }

    private fun coverageEnd() {
        coveragePublish(force = true, running = false)
        coverageBuilder = null; coverageTask = null
    }

    /** At launch: coverage of the latest mowing with a recorded track, until a new mowing starts. */
    internal suspend fun coverageRestore(history: List<MowingRecord>) {
        val last = history.filter { it.kind == TaskKind.MOW && it.finished }.maxByOrNull { it.startedAt } ?: return
        val coverage = withContext(Dispatchers.Default) {
            runCatching { trackStore.read(last.id)?.takeIf { it.meta != null }?.let { CoverageMap.of(it) } }.getOrNull()
        } ?: return
        handler.post { RealCoverage.restore(CoverageView(last.id, coverage, running = false)) }
    }

    /** Wet grass in the log (mowing start and report): data to recalibrate [fr.luba.poc.weather.GrassWetness.WET_SLIDE_GAIN]. */
    private fun wetnessFields(): Map<String, Any?> = difficulties.grassWetness().let { w ->
        mapOf("grassWetness" to w?.index, "grassWetnessReason" to w?.reason, "rainWeightedMm" to w?.rainMm, "wetSlideFactor" to difficulties.wetSlideFactor(),
            "slideCells" to slideField.size) }

    /** Time and battery sample; saved at most every 30 s. */
    private fun historySample() {
        val record = state.value.mowingRecord ?: return
        val tracker = historyTracker ?: return
        val t = state.value.telemetry
        val totals = tracker.sample(SystemClock.elapsedRealtime(), t.battery, t.chargeState in setOf(1, 2))
        val route = state.value.robotRoute
        // Saved progress (any coverage task); otherwise, the share of the route travelled.
        val done = routeCheckpoint?.doneMeters ?: if (record.kind == TaskKind.MOW) 0.0
            else if (route.count > 0) record.cuttingMeters * route.index.coerceIn(0, route.count) / route.count else 0.0
        val next = record.copy(totals = totals, recharges = historyRechargeBase + route.rechargeCycles,
            doneMeters = maxOf(record.doneMeters, done), batteryEnd = t.battery ?: record.batteryEnd)
        update { it.copy(mowingRecord = next) }
        if (SystemClock.elapsedRealtime() - historySavedAt >= 30_000) historyPersist(next)
    }

    private fun historyPersist(record: MowingRecord) {
        historySavedAt = SystemClock.elapsedRealtime()
        val records = MowingHistoryStore.upsert(state.value.mowingHistory, record)
        update { it.copy(mowingHistory = records) }
        historyWrites.trySend(records)
    }

    /** All cutting stripes covered: the mowing is done, even if the final docking fails afterwards. */
    internal fun historyMowingDone() {
        state.value.mowingRecord?.let { r -> update { it.copy(mowingRecord = r.copy(doneMeters = r.cuttingMeters)) } }
    }

    internal fun historyObstacle(reason: String) {
        taskEvent(TaskEventType.OBSTACLE, reason)
        state.value.mowingRecord?.let { r -> update { it.copy(mowingRecord = r.copy(obstacles = r.obstacles + 1)) } }
    }

    /** End of the measurement: report compared with the estimate, saved and displayed. */
    internal fun historyEnd(reason: String, completed: Boolean) {
        if (state.value.mowingRecord == null) return
        historyJob?.cancel(); historyJob = null
        historySample()
        val record = requireNotNull(state.value.mowingRecord)
        val done = completed || record.progress >= 0.999
        val final = record.copy(outcome = if (done) MowingOutcome.COMPLETE else MowingOutcome.INTERRUPTED,
            reason = if (record.kind != TaskKind.MOW && reason.startsWith(tr("Mowing finished", "Tonte terminée"))) reason.replaceFirst(tr("Mowing finished", "Tonte terminée"), tr("Route finished", "Trajet terminé")) else reason, endedAt = Clock.now(), doneMeters = if (done) record.cuttingMeters else record.doneMeters)
        historyTracker = null
        val mowing = final.kind == TaskKind.MOW
        // Zones mowed at 50 % or more remember their directions for the next "Change every session".
        orientationPlan?.takeIf { mowing }?.let { (preview, map) ->
            val current = state.value.zoneOrientations
            val updated = runCatching { ZoneProgress.update(current, preview, map, final.doneMeters,
                final.endedAt ?: Clock.now(), final.cuttingMeters) }.getOrDefault(current)
            if (updated != current) { update { it.copy(zoneOrientations = updated) }; orientationWrites.trySend(updated) }
        }
        orientationPlan = null
        // Report opened at the end of a mowing only; surveys and routes remain available in the history.
        update { it.copy(mowingRecord = null, mowingReport = if (mowing) final else it.mowingReport) }
        historyPersist(final)
        taskEvent(TaskEventType.END, final.reason?.let { reasonText(it) } ?: final.outcome.label,
            mapOf("outcome" to final.outcome.name, "progress" to final.progress))
        trackStop(final.id, TaskTrackStore.kept(state.value.mowingHistory))
        val comparison = MowingComparison.of(final)
        event("mowing_report", tr("${final.kind.label} report: ${final.outcome.label} · ${comparison.durationVerdict} · ${comparison.rechargeVerdict}", "Rapport ${final.kind.label.lowercase()} : ${final.outcome.label} · ${comparison.durationVerdict} · ${comparison.rechargeVerdict}"),
            mapOf("id" to final.id, "kind" to final.kind.name, "outcome" to final.outcome.name, "progress" to final.progress,
                "expectedSeconds" to comparison.expectedSeconds, "actualSeconds" to comparison.actualSeconds,
                "activeSeconds" to final.totals.activeSeconds, "chargingSeconds" to final.totals.chargingSeconds,
                "expectedRecharges" to comparison.expectedRecharges, "actualRecharges" to comparison.actualRecharges,
                "batteryUsed" to final.totals.batteryUsed, "batteryCharged" to final.totals.batteryCharged,
                "observedTimeFactor" to comparison.observedTimeFactor, "observedAutonomyMinutes" to comparison.observedAutonomyMinutes,
                "observedChargeMinutes" to comparison.observedChargeMinutes, "trackingLinks" to final.links.joinToString(",") { it.name },
                "gatewayLegs" to final.gatewayLegs) + wetnessFields())
        if (done && mowing) host.alerts.alert(AlertKind.MOWING_REPORT, Severity.INFO, "${final.zoneNames.joinToString(", ").ifBlank { tr("Mowing", "Tonte") }} · ${comparison.durationVerdict}")
    }

    /** Details of a history task: report and replay of its track (or closing the details). */
    fun showTaskDetail(id: String?) { update { it.copy(taskDetail = id) } }

    /** Report of a mowing from the history (or closing the displayed report). */
    fun showMowingReport(id: String?) {
        update { ui -> ui.copy(mowingReport = id?.let { rid -> ui.mowingHistory.firstOrNull { it.id == rid } }) }
    }

    /** Excludes (or re-includes) a mowing from the calibration of the estimates. */
    fun setMowingExcluded(id: String, excluded: Boolean) {
        val record = state.value.mowingHistory.firstOrNull { it.id == id } ?: return
        if (record.outcome == MowingOutcome.RUNNING) return
        val next = record.copy(excluded = excluded)
        historyPersist(next)
        if (state.value.mowingReport?.id == id) update { it.copy(mowingReport = next) }
    }

    /** Track of a history task (read from file); points still in memory included for the current task. */
    suspend fun loadTaskTrack(id: String): TaskTrack? {
        val pending = if (state.value.mowingRecord?.id == id) trackBuffer.toList() else emptyList()
        val saved = withContext(Dispatchers.IO) { trackStore.read(id) }
        if (pending.isEmpty()) return saved
        return TaskTrack(saved?.meta, (saved?.samples.orEmpty() + pending).distinctBy { it.atMs }.sortedBy { it.atMs })
    }

    /** A failed write of a local file, logged from its writer coroutine. */
    private fun saveFailed(file: String, error: Throwable) = handler.post {
        event("local_save_failed", tr("Could not save the $file: ${error.javaClass.simpleName}", "Enregistrement impossible ($file) : ${error.javaClass.simpleName}"),
            mapOf("file" to file, "reason" to error.message))
    }

    // Last in the class: the writer coroutines may start before the constructor of the host finishes, and read the
    // stores declared above.
    init {
        host.viewModelScope.launch(Dispatchers.IO) {
            val stored = historyStore.read()
            val loaded = MowingHistoryStore.closeStale(stored)
            if (loaded != stored) runCatching { historyStore.save(loaded) }.onFailure { saveFailed("history", it) }
            handler.post { update { ui -> ui.copy(mowingHistory = (ui.mowingHistory + loaded.filter { r -> ui.mowingHistory.none { it.id == r.id } })
                .sortedByDescending { it.startedAt }) } }
            coverageRestore(loaded)
            for (records in historyWrites) runCatching { historyStore.save(records) }.onFailure { saveFailed("history", it) }
        }
        host.viewModelScope.launch(Dispatchers.IO) {
            val loaded = orientationStore.read()
            handler.post { update { ui -> ui.copy(zoneOrientations = loaded + ui.zoneOrientations) } }
            // Merged with the file: a mowing that ends before the read above has reached the state keeps the other zones.
            for (memory in orientationWrites) runCatching { orientationStore.merge(memory) }.onFailure { saveFailed("zone directions", it) }
        }
        host.viewModelScope.launch(Dispatchers.IO) {
            for (write in trackWrites) runCatching {
                write.meta?.let { meta -> if (!trackStore.hasMeta(write.id)) trackStore.writeMeta(write.id, meta) }
                if (!trackStore.append(write.id, write.samples) && !trackFull) {
                    trackFull = true
                    handler.post { event("task_track_full", tr("Track size limit reached: further positions of this task are not saved", "Taille maximale de la piste atteinte : les positions suivantes de cette tâche ne sont pas enregistrées")) }
                }
                trackStore.appendEvents(write.id, write.events)
                write.boxes.forEach { trackStore.appendBox(write.id, it) }
                // End of a run: raw positions compressed.
                if (write.seal) trackStore.seal(write.id)
                write.keep?.let(trackStore::prune)
            }.onFailure { error -> handler.post { event("task_track_save_failed", tr("Could not save the track: ${error.message}", "Sauvegarde de la piste impossible : ${error.message}")) } }
        }
    }
}

/** Write request of a task track: positions, events, black boxes, map and route, end of run and pruning. */
internal class TrackWrite(val id: String, val samples: List<TrackSample> = emptyList(),
                          val meta: TrackMeta? = null, val keep: List<String>? = null,
                          val events: List<TaskEvent> = emptyList(), val seal: Boolean = false,
                          val boxes: List<BlackBox> = emptyList())
