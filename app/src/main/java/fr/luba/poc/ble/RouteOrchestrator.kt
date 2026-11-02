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
import fr.luba.poc.autonomy.LegFrames
import fr.luba.poc.autonomy.LegObstacle
import fr.luba.poc.autonomy.LegOutcome
import fr.luba.poc.autonomy.LegProgress
import fr.luba.poc.autonomy.LegRunner
import fr.luba.poc.autonomy.LegSpec
import fr.luba.poc.autonomy.RemoteLegs
import fr.luba.poc.diagnostics.*
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.gateway.GatewayLink
import fr.luba.poc.gateway.GatewayOta
import fr.luba.poc.history.MowingCalibration
import fr.luba.poc.history.TaskEventType
import fr.luba.poc.history.TaskKind
import fr.luba.poc.history.TrackingLink
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.planning.DockClearance
import fr.luba.poc.planning.ReturnLaneStore
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import java.time.LocalTime
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Route missions (mowing, surveys, station trips): preparation, tracking from the phone or by the gateway, recharge and resume, checkpoints and gateway update resume.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class RouteOrchestrator(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private val alerts get() = host.alerts
    private val returnLanes = ReturnLaneStore(host.getApplication()) { c -> event("route_return_lane", c.logText, c.fields) } // lanes alternated against ruts ([ReturnLanes])
    private var bladeHeightRequest
        get() = host.bladeHeightRequest
        set(value) { host.bladeHeightRequest = value }
    private var bladeMayBeOn
        get() = host.bladeMayBeOn
        set(value) { host.bladeMayBeOn = value }
    private var bladeOffPending
        get() = host.bladeOffPending
        set(value) { host.bladeOffPending = value }
    private var bladeOffWrittenAt
        get() = host.bladeOffWrittenAt
        set(value) { host.bladeOffWrittenAt = value }
    private var bladeStopVerified
        get() = host.bladeStopVerified
        set(value) { host.bladeStopVerified = value }
    private val checkpointWrites get() = host.checkpointWrites
    private var closing
        get() = host.closing
        set(value) { host.closing = value }
    private var commands
        get() = host.commands
        set(value) { host.commands = value }
    private var confirmedHeading: Double?
        get() = host.confirmedHeading
        set(value) { host.confirmedHeading = value }
    private var devicePrevious
        get() = host.devicePrevious
        set(value) { host.devicePrevious = value }
    private val difficulties get() = host.difficulties
    private var gatewayOutageSince
        get() = host.gatewayOutageSince
        set(value) { host.gatewayOutageSince = value }
    private var gatt: RobotLink?
        get() = host.gatt
        set(value) { host.gatt = value }
    private val handler get() = host.handler
    private var heightTarget: Int?
        get() = host.heightTarget
        set(value) { host.heightTarget = value }
    private var lastTelemetry
        get() = host.lastTelemetry
        set(value) { host.lastTelemetry = value }
    private val legs get() = host.legs
    private val log get() = host.log
    private val navigation get() = host.navigation
    private val obstaclePhotos get() = host.obstaclePhotos
    private var periodic: Job?
        get() = host.periodic
        set(value) { host.periodic = value }
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private var preparationJob: Job?
        get() = host.preparationJob
        set(value) { host.preparationJob = value }
    private val queue get() = host.queue
    private var readyAt
        get() = host.readyAt
        set(value) { host.readyAt = value }
    private val recovery get() = host.recovery
    private var remoteLegActive
        get() = host.remoteLegActive
        set(value) { host.remoteLegActive = value }
    private var routeBladeGate: RouteBladeGate?
        get() = host.routeBladeGate
        set(value) { host.routeBladeGate = value }
    private var routeBypassArmed
        get() = host.routeBypassArmed
        set(value) { host.routeBypassArmed = value }
    private var routeContact: RouteContact?
        get() = host.routeContact
        set(value) { host.routeContact = value }
    private var routeIntent: RouteIntent?
        get() = host.routeIntent
        set(value) { host.routeIntent = value }
    private val safetyTests get() = host.safetyTests
    private var slideField
        get() = host.slideField
        set(value) { host.slideField = value }
    private val state get() = host.state
    private val calibration get() = host.calibration
    private val signalSurveys get() = host.signalSurveys
    private var current: RobotBle.Operation?
        get() = host.current
        set(value) { host.current = value }
    private val taskHistory get() = host.taskHistory
    private val trailStore get() = host.trailStore
    private val trailWrites get() = host.trailWrites
    private var userDisconnected
        get() = host.userDisconnected
        set(value) { host.userDisconnected = value }
    private var workWindowEnding
        get() = host.workWindowEnding
        set(value) { host.workWindowEnding = value }

    /**
     * Tracking handed to the gateway ([fr.luba.poc.autonomy.LegRunner] on the Redmi): waits for its outcome while keeping
     * the map up to date (progress, trail, signal surveys), never writing to the robot. Returns the index reached like
     * `followAll`, or throws [ObstacleEncounter] / a safety stop error.
     */
    internal suspend fun runRemoteLeg(link: GatewayLink, spec: LegSpec, path: List<SimEdge>,
                                     obstacles: () -> List<LocalObstacle>, planned: () -> Set<String>, originOf: (Int) -> Int,
                                     navProgress: ((LegProgress) -> Unit)? = null): Int {
        // Read-only mode: the gateway writes the leg itself, out of reach of the send() barrier, so it is refused here.
        ModelSupport.refusal(state.value.selected?.name)?.let { error(it) }
        fun legObstacles() = obstacles().map { o -> LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, o.id in planned()) }
        // Move to a point ([navProgress]): progress and trail kept by the navigation screen, not by the mowing route.
        val shown = spec.shown && navProgress == null
        val manoeuvreStats = ManoeuvreStats(path)
        val callbacks = object : RemoteLegs.Callbacks {
            override fun progress(progress: LegProgress) {
                navProgress?.let { show -> show(progress.copy(status = progress.status + if (progress.orphan) tr(" · Wi-Fi lost, the robot carries on alone", " · Wi-Fi coupé, le robot poursuit seul") else "")); return }
                if (!shown) return
                update { it.copy(robotRoute = it.robotRoute.copy(index = progress.index,
                    status = progress.status + if (progress.orphan) tr(" · Wi-Fi lost, the robot carries on alone", " · Wi-Fi coupé, le robot poursuit seul") else "")) }
                val live = state.value.telemetry
                val x = live.x; val y = live.y
                // Published point (8 cm behind the centre): enough for the duration, speed and deviation of a manoeuvre.
                if (progress.index >= 1) manoeuvreStats.update(Clock.now(), progress.index - 1,
                    if (x != null && y != null) Point(x, y) else null)?.let { host.logManoeuvre(it, "gateway") }
                if (x != null && y != null && progress.index >= 1) originOf(progress.index - 1).let { origin -> if (origin >= 0) recordMissionProgress(origin, Point(x, y)) }
            }
            override fun sample(sample: SignalSample) = signalSurveys.recordSurveySample(sample)
            override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) {
                host.event("gateway_leg_$kind", text, fields)
                if (kind == GatewayDetachment.EVENT) handler.post { alerts.gatewayDetached(text, fields) }
                // Slide measured by the gateway during a pivot: same learning as with local tracking.
                if (kind == LegRunner.PIVOT_SLIDE_EVENT) {
                    fun num(key: String) = (fields[key] as? Number)?.toDouble()
                    val x = num("x"); val y = num("y"); val vx = num("vx"); val vy = num("vy"); val seconds = num("seconds")
                    if (x != null && y != null && vx != null && vy != null && seconds != null)
                        handler.post { difficulties.learnSlide(SlideSample(x, y, vx, vy, seconds, (fields["atMs"] as? Number)?.toLong() ?: Clock.now()), "gateway") }
                }
            }
        }
        // The gateway alone writes to the robot as soon as tracking starts: nothing more is written from here (hold released on any exit),
        // and writes already queued or in flight finish first. A pilot write arriving after LEG_START would interrupt the tracking.
        remoteLegActive = true
        var leg: RemoteLegs.Leg? = null
        var delivered = false
        var windowAbort = false
        val result = try {
            val drainUntil = SystemClock.elapsedRealtime() + 4000
            while ((queue.any { it is RobotBle.Operation.Write } || current is RobotBle.Operation.Write) && SystemClock.elapsedRealtime() < drainUntil) delay(50)
            check(queue.none { it is RobotBle.Operation.Write } && current !is RobotBle.Operation.Write) { tr("Writes to the robot still in progress: autonomous tracking not started", "Écritures encore en cours vers le robot : suivi autonome non lancé") }
            val started = legs.start(spec, callbacks)
            leg = started
            // Sending impossible (link closed): nothing is handed to the gateway, no hold is set.
            if (started.result.isCompleted) { legs.release(started); leg = null; started.result.await() }
            link.holdUntil = Long.MAX_VALUE
            if (spec.cutting && (spec.from until path.size).any { path[it].bladesOn }) bladeMayBeOn = true
            // Task history: one more tracking handed to the gateway (compared with tracking from the pilot).
            if (shown) update { s -> s.mowingRecord?.let { r -> s.copy(mowingRecord = r.copy(gatewayLegs = r.gatewayLegs + 1,
                links = r.links + TrackingLink.GATEWAY)) } ?: s }
            // New tracking accepted by the gateway: the phone is back on the robot, the drop marker goes away.
            if (state.value.gatewayDrop != null) update { it.copy(gatewayDrop = null) }
            event("gateway_leg_start", tr("Tracking handed to the gateway", "Suivi confié à la passerelle"), mapOf("leg" to spec.legId, "from" to spec.from, "edges" to path.size, "shown" to shown,
                "cutting" to spec.cutting, "sampling" to spec.sampling, "obstacles" to spec.obstacles.size))
            var sentObstacles = spec.obstacles
            while (!started.result.isCompleted) {
                // End of the working hours during a mowing handed to the gateway: stop requested, the outcome returns the edge reached.
                if (workWindowEnding && spec.watchBattery && !windowAbort) {
                    windowAbort = true
                    started.abort()
                    event("gateway_leg_work_window", tr("Mission stop (working hours or rain): tracking stop requested from the gateway", "Arrêt de mission (plage horaire ou pluie) : arrêt du suivi demandé à la passerelle"), mapOf("leg" to spec.legId))
                }
                val now = SystemClock.elapsedRealtime()
                val live = state.value.telemetry
                val x = live.x; val y = live.y
                // Route trail from the telemetry received (good Wi-Fi); the trail during the outage comes with the outcome.
                if (shown && x != null && y != null && positionPrevious != 0L && now - positionPrevious in 0..2000) {
                    // Same point as the trail returned by the gateway and as local tracking: the rotation center.
                    val here = DeadReckoning.rotationCenter(Point(x, y), live.heading)
                    update { it.copy(robotRoute = it.robotRoute.copy(trail = MissionTrail.append(it.robotRoute.trail, here))) }
                }
                if (gatewayOutageSince != 0L) {
                    val outage = tr("Gateway Wi-Fi lost for ${(now - gatewayOutageSince) / 1000} s · the robot carries on alone", "Wi-Fi de la passerelle perdu depuis ${(now - gatewayOutageSince) / 1000} s · le robot poursuit seul")
                    if (navProgress != null) update { it.copy(navTest = it.navTest.copy(status = outage)) }
                    else update { it.copy(robotRoute = it.robotRoute.copy(status = outage)) }
                }
                // Obstacle added or discovered during tracking: the gateway checks the trajectory against the updated list.
                val known = legObstacles()
                if (known != sentObstacles) {
                    if (!started.obstacles(known)) event("gateway_leg_obstacles_unsent", tr("Obstacle list (${known.size}) not sent to the gateway", "Liste d'obstacles (${known.size}) non transmise à la passerelle"))
                    sentObstacles = known
                }
                delay(150)
            }
            try { started.result.await().also { delivered = true } }
            catch (lost: RemoteLegs.LostLeg) {
                // Edge reached: last reported progress (current edge, numbered from 1), otherwise the starting one.
                val reached = started.lastProgress?.index?.minus(1)?.coerceIn(spec.from, path.size - 1) ?: spec.from
                event("gateway_leg_lost", lost.message ?: tr("Tracking lost by the gateway", "Suivi perdu par la passerelle"), mapOf("leg" to spec.legId, "from" to spec.from, "index" to reached))
                throw RouteLegLost(reached, lost.message ?: tr("Tracking lost by the gateway", "Suivi perdu par la passerelle"))
            }
        } finally {
            val mine = leg
            if (mine == null) remoteLegActive = false   // tracking never handed over
            // Only the still-current tracking touches the hold: a tracking run cancelled then replaced must not release the new one's hold.
            else if (legs.current === mine) {
                remoteLegActive = false
                if (delivered) link.holdUntil = 0L
                // Outcome not received (cancellation, local failure, unreadable frame): the gateway may still be tracking, order it to stop.
                else { mine.abort(); link.holdUntil = Clock.now() + RobotBle.ABORT_HOLD_MS }
                legs.release(mine)
            }
        }
        result.calibration?.let(calibration::store)
        // Stop not confirmed by the gateway (robot link, GATT write…): the robot state is uncertain, do not continue.
        if (!result.stopConfirmed && result.outcome != LegOutcome.ABORTED)
            error(tr("Robot stop not confirmed by the gateway (${result.outcome}${result.text.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()})", "Arrêt du robot non confirmé par la passerelle (${result.outcome}${result.text.takeIf { it.isNotBlank() }?.let { " : $it" }.orEmpty()})"))
        fun mergeTrail() {
            if (result.trail.isEmpty() || navProgress != null) return
            update { it.copy(robotRoute = it.robotRoute.copy(trail = MissionTrail.merge(it.robotRoute.trail, result.trail))) }
        }
        event("gateway_leg_result", tr("Tracking returned by the gateway: ${result.outcome}", "Suivi rendu par la passerelle : ${result.outcome}"), mapOf("leg" to result.legId, "index" to result.index,
            "text" to result.text, "stopConfirmed" to result.stopConfirmed))
        if (windowAbort && result.outcome == LegOutcome.ABORTED) {
            check(result.stopConfirmed) { tr("Robot stop not confirmed by the gateway at the end of the working hours (${result.text})", "Arrêt du robot non confirmé par la passerelle en fin de plage horaire (${result.text})") }
            mergeTrail()
            return result.index
        }
        return when (result.outcome) {
            LegOutcome.DONE -> path.size
            LegOutcome.LOW_BATTERY -> result.index
            LegOutcome.CONTACT -> {
                mergeTrail()
                val c = requireNotNull(result.contact) { tr("Contact without pose", "Contact sans pose") }
                throw ObstacleEncounter(result.index, RouteContact(c.code, c.x, c.y, c.heading, c.atMs), emptyList(), tr("Bumper contact (${c.code})", "Contact bumper (${c.code})"))
            }
            LegOutcome.OBSTACLE_ADDED -> {
                mergeTrail()
                throw ObstacleEncounter(result.index, null, obstacles().filter { it.id in result.obstacleIds }, tr("Obstacle added on the route", "Obstacle ajouté sur le trajet"))
            }
            LegOutcome.FAILED -> {
                // Off the trajectory (deviation, footprint outside the corridor): the mission can resume from the returned edge.
                if (DriftRecovery.recoverable(result.text)) { mergeTrail(); throw RouteDrift(result.index, result.text) }
                // Transient protection raised by the robot: the mission waits for it to clear, then resumes.
                if (ProtectionPause.pausable(result.text)) { mergeTrail(); throw RouteProtection(result.index, result.text) }
                // Wheels turning without moving: the mission can defer the segment ([SmartResume]).
                if (SmartResume.blockage(result.text)) { mergeTrail(); throw RouteBlocked(result.index, result.text) }
                error(result.text)
            }
            LegOutcome.ABORTED -> error(tr("Autonomous tracking interrupted: ${result.text}", "Suivi autonome interrompu : ${result.text}"))
        }
    }
    private var routeJob: Job? = null
    /** Physical geometry of the current route, for clearance segments. */
    private var routePhysicalRoutes: TrajectoryPlanner? = null
    /** Short positioning loss ridden through during a route ([PositioningGrace]), kept from one range to the next. */
    private val routePositioningGrace = PositioningGrace()
    private var routeRidingPositioning = false
    /**
     * Contacts at the limit of the task area ([fr.luba.poc.simulation.EdgeContact]) of the current route: drawn border and
     * bumper points of the limit contacts already handled (their count is the mission's). Kept here rather than as locals of
     * the route coroutine: every local captured by its local functions widens their calls, and the coroutine body went past
     * the Dalvik register threshold of the Fold build (`checkFoldDexRegisters`, 235 > 224).
     */
    private var routeEdgeBorder: EdgeContact.Border? = null
    private val routeEdgeLimits = mutableListOf<Point>()
    /** Tracking running on the gateway: stop it first (it stops the robot), before any stop order from here. */
    internal fun releaseRemoteLeg() {
        if (remoteLegActive || legs.current != null) {
            remoteLegActive = false
            legs.current?.abort()
            // Wi-Fi cut at that moment: keep the link a little longer so the stop order still gets through.
            (gatt as? GatewayLink)?.holdUntil = Clock.now() + RobotBle.ABORT_HOLD_MS
        }
    }
    internal fun cancelRobotRoute(reason: String) {
        rechargeCycle = false
        taskHistory.historyEnd(reason, completed = false)
        releaseRemoteLeg()
        signalSurveys.flushSurveys()
        routeJob?.cancel(); routeJob = null
        routeCheckpoint?.let { checkpoint ->
            routeCheckpoint = null; routePlan = null
            persistCheckpoint(checkpoint.copy(interruption = reason, updatedAt = Clock.now()), force = true)
            event("mission_checkpoint_interrupted", tr("Mowing interrupted: progress kept for resume", "Tonte interrompue : avancement conservé pour reprise"),
                mapOf("reason" to reason, "edge" to checkpoint.edge, "progress" to checkpoint.progress))
        }
        if (state.value.robotRoute.running) update { it.copy(robotRoute = it.robotRoute.copy(running=false,phase=if(it.robotRoute.phase==RoutePhase.COMPLETE) RoutePhase.COMPLETE else RoutePhase.STOPPED,status=reason)) }
        // Difficulties of the finished route: presumed obstacles and avoided zones for the next routes.
        difficulties.publishDifficulties(state.value.difficulties.events, persist = false)
    }

    /**
     * [avoidance]: presumed obstacles and avoided zones counted by the preparation of [engine], so that the resume
     * re-prepares the same plan; null for a map preparation, which follows the current difficulties. Supervised tests
     * (border, painted zone) prepare without them.
     */
    fun startRobotRoute(engine: MowingSimulation, cutting: Boolean, heightMm: Int, avoidance: MissionAvoidance? = null): Boolean =
        startRoute(engine,RobotRoute.cuttingAllowed(engine.preview,cutting),heightMm,RouteIntent.FOLLOW,planningAvoidance=avoidance)

    /**
     * Resume of the interrupted mowing: [engine] must be re-prepared from the saved preview.
     * Refused if the resulting plan differs (map, base, access or obstacles changed) or if the robot differs.
     */
    fun resumeRobotRoute(engine: MowingSimulation, skipTo: MissionResume? = null): Boolean {
        val checkpoint = state.value.missionCheckpoint
        if (checkpoint == null) { update { it.copy(robotRoute = it.robotRoute.copy(status = tr("Start refused: no mowing to resume", "Départ refusé : aucune tonte à reprendre"))) }; return false }
        return startRoute(engine, checkpoint.cutting, checkpoint.heightMm, RouteIntent.FOLLOW, checkpoint, skipTo)
    }

    /** Explicit abandon: the progress is erased, the next mowing starts from the beginning. */
    fun discardMissionCheckpoint() {
        if (state.value.robotRoute.running) return
        val id = state.value.missionCheckpoint?.robotId ?: return
        update { it.copy(missionCheckpoint = null) }
        checkpointWrites.trySend(id to null)
        trailWrites.trySend(RobotBle.TrailWrite(id, 0L, null)); routeTrailMission = null
        event("mission_checkpoint_discarded", tr("Mowing progress abandoned by the user", "Avancement de tonte abandonné par l'utilisateur"))
    }

    internal var routeCheckpoint: MissionCheckpoint? = null
    private var routePlan: MissionEdges? = null
    private var routeEngineEdges: List<SimEdge> = emptyList()
    private var checkpointSavedAt = 0L
    private var checkpointSavedMeters = 0.0
    /** Start ([MissionCheckpoint.startedAt]) of the mowing the displayed trail belongs to; null outside mowing. */
    private var routeTrailMission: Long? = null
    private var trailSavedAt = 0L

    /** Memory and disk; written at most every 5 s or every 2 m of cutting, unless [force]. */
    private fun persistCheckpoint(checkpoint: MissionCheckpoint, force: Boolean = false) {
        // Cutting progress of the mowing bar: only during this mission's route.
        update { it.copy(missionCheckpoint = checkpoint, robotRoute = if (it.robotRoute.running && routeCheckpoint != null)
            it.robotRoute.copy(cutProgress = checkpoint.progress) else it.robotRoute) }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - checkpointSavedAt < 5000 && abs(checkpoint.doneMeters - checkpointSavedMeters) < 2.0) return
        checkpointSavedAt = now; checkpointSavedMeters = checkpoint.doneMeters
        checkpointWrites.trySend(checkpoint.robotId to checkpoint)
        // Mowing trail: at most every 15 s, immediately at start, on resume and on interruption.
        if (routeTrailMission == checkpoint.startedAt && (force || now - trailSavedAt >= 15_000)) {
            trailSavedAt = now
            trailWrites.trySend(RobotBle.TrailWrite(checkpoint.robotId, checkpoint.startedAt, state.value.robotRoute.trail))
        }
    }

    /** Position measured on edge [routeIndex] of the main route. */
    private fun recordMissionProgress(routeIndex: Int, position: Point) {
        val checkpoint = routeCheckpoint ?: return
        val plan = routePlan ?: return
        if (routeIndex >= plan.mowingTo) { finishCheckpoint(tr("All mowing stripes covered", "Toutes les bandes de tonte parcourues")); return }
        val edge = plan.engineEdge(routeIndex) ?: return
        SmartResume.advance(checkpoint, routeEngineEdges, edge, position, Clock.now())?.let { next ->
            routeCheckpoint = next; persistCheckpoint(next)
        }
    }

    private fun finishCheckpoint(reason: String) {
        taskHistory.historyMowingDone()
        val checkpoint = routeCheckpoint ?: return
        routeCheckpoint = null; routePlan = null
        // Stripes finished: the bar shows 100 % during the return to base.
        update { it.copy(missionCheckpoint = null, robotRoute = it.robotRoute.copy(cutProgress = it.robotRoute.cutProgress?.let { 1.0 })) }
        checkpointWrites.trySend(checkpoint.robotId to null)
        trailWrites.trySend(RobotBle.TrailWrite(checkpoint.robotId, checkpoint.startedAt, null))
        event("mission_checkpoint_done", reason, mapOf("resumes" to checkpoint.resumes, "rechargeCycles" to checkpoint.rechargeCycles))
    }

    /**
     * Charging station. Recharge: local DumbyLuba route to the approach point in front of the base (blades OFF),
     * confirmed stop, then docking through the official Mammotion return ([startRoute], docking phase).
     */
    fun baseAction(engine: MowingSimulation, leave: Boolean): Boolean {
        val intent = if (leave) RouteIntent.LEAVE_BASE else RouteIntent.RETURN_TO_BASE
        if (intent == RouteIntent.RETURN_TO_BASE) {
            if (state.value.nativeReturnActive || state.value.telemetry.robotState == 14) {
                update { it.copy(nativeReturnStatus=tr("Mammotion return already in progress", "Retour Mammotion déjà en cours")) }; return false
            }
            val t = state.value.telemetry
            // Already stopped in front of the base: no local route, official return directly.
            if (t.chargeState !in setOf(1,2) && t.x != null && t.y != null &&
                Point(t.x,t.y).distance(engine.baseApproach) <= RobotBle.NATIVE_HANDOFF_APPROACH_M) {
                recovery.nativeReturnToBase(); return !state.value.nativeReturnStatus.startsWith(tr("Native return refused", "Retour natif refusé"))
            }
            update { it.copy(nativeReturnStatus="") }
        }
        return startRoute(engine,false,state.value.telemetry.bladeHeightMm ?: 60,intent)
    }

    /**
     * Base used to prepare [engine], for the resume: the current base of its map if it matches the route exactly
     * (point, axis and approach); otherwise null and resume on the current base, as before 0.64.2.
     */
    private fun missionBase(engine: MowingSimulation): MapBase? = runCatching {
        BaseStore(host.getApplication(), engine.map).resolve(engine.map)
    }.getOrNull()?.takeIf { it.point.distance(engine.base) < 1e-6 &&
        it.entryHeadingDegrees == engine.config.baseEntryHeadingDegrees && it.approachDistanceM == engine.config.baseApproachMeters }

    private fun startRoute(engine: MowingSimulation, cutting: Boolean, startHeightMm: Int, intent: RouteIntent, saved: MissionCheckpoint? = null,
                           skipTo: MissionResume? = null, planningAvoidance: MissionAvoidance? = null): Boolean {
        // Current height: that of the pass in progress ([SimEdge.heightMm], passes of decreasing height), reused by
        // the runs, the blade keep-alives, the back-offs and the blade stop.
        var heightMm = startHeightMm
        try {
            RobotRoute.requireExecutable(engine.preview)
            check(!state.value.silenceTest.running) { tr("Finish the command cutoff test first", "Terminer l'essai de coupure des commandes") }
            // Read-only mode: no route (mowing, survey, move, return or base exit) on a model that is not validated.
            ModelSupport.refusal(state.value.selected?.name)?.let { error(it) }
            val robotId = requireNotNull(state.value.selected?.address) { tr("No robot selected", "Robot non sélectionné") }
            val engineFingerprint = if (intent == RouteIntent.FOLLOW) MissionProgress.fingerprint(engine.edges) else ""
            if (saved != null) {
                check(intent == RouteIntent.FOLLOW && saved.robotId == robotId) { tr("Resume reserved for the robot of the interrupted mowing", "Reprise réservée au robot de la tonte interrompue") }
                check(saved.mapKey == MissionProgress.mapKey(engine.map)) { tr("Map changed since the interruption: resume impossible, start a new mowing", "Carte modifiée depuis l'interruption : reprise impossible, relancer une tonte") }
                check(saved.planFingerprint == engineFingerprint && saved.edgeCount == engine.edges.size) {
                    tr("Re-prepared route differs (base, access or obstacles changed): resume impossible, start a new mowing", "Trajet re-préparé différent (base, accès ou obstacles modifiés) : reprise impossible, relancer une tonte") }
            }
            // "Resume further on": point chosen on the map, further along the remaining stripes, without overlap.
            if (saved != null && skipTo != null) check(saved.linear) { tr("Resume further on unavailable: the remainder was reordered (smart resume)", "Reprendre plus loin indisponible : le reste a été réordonné (reprise intelligente)") }
            val savedResume = saved?.let { MissionProgress.skipped(it, engine.edges, skipTo) }
            val skippedNow = if (saved != null && savedResume != null) savedResume.skippedMeters - saved.skippedMeters else 0.0
            if (savedResume != null) check(!SmartResume.mowingDone(engine.edges, savedResume)) { tr("Mowing already finished: use return to base", "Tonte déjà terminée : utiliser le retour à la base") }
            state.value.cutHeights.requireHeight(heightMm)
            // Working hours: neither mowing nor leaving the base outside them; return to base remains allowed.
            if (intent != RouteIntent.RETURN_TO_BASE) WorkWindowSetting.value.refusal(LocalTime.now())?.let { error(it) }
            val recharge = RouteRecharge(engine.config.returnPercent.toInt(), engine.config.resumePercent.toInt())
            // Watched return threshold: the low threshold, or the [FinishPolicy] floor while finishing a mowing below the low threshold.
            var lowWatch = recharge.low
            // Never Die Outside ([ReturnReserve]): far from the base, the return cost plus the reserve raises the watched threshold.
            val returnReserve = ReturnReserve.sanitize(Math.round(engine.config.returnReservePercent).toInt(), recharge.low)
            var returnDetour = ReturnReserve.DEFAULT_DETOUR
            var reserveWatch = recharge.low
            var reserveWatchAt = 0L
            check(engine.config.baseEntryHeadingDegrees != null) { tr("Real base axis required for docking", "Axe réel de la base requis pour l'accostage") }
            val t = state.value.telemetry
            val initiallyDocked = t.chargeState in setOf(1,2)
            if (intent==RouteIntent.LEAVE_BASE) check(initiallyDocked) { tr("Charging contact required to leave the base", "Contact de charge requis pour sortir de la base") }
            if (intent.returnsToBase && initiallyDocked) {
                update { it.copy(robotRoute=it.robotRoute.copy(status=tr("Already on the base: charging observed", "Déjà sur la base : charge observée"))) }; return true
            }
            if (initiallyDocked) check(t.x != null && t.y != null && Point(t.x,t.y).distance(engine.base) <= 0.25) { tr("Charging observed far from the configured base", "Charge observée loin de la base configurée") }
            check(!state.value.taskStopPending && !state.value.robotRoute.running && !state.value.manualActive && !state.value.mowing.active &&
                !state.value.navTest.running && !state.value.teach.running && preparationJob?.isActive != true && heightTarget == null && !bladeMayBeOn && !bladeOffPending &&
                !state.value.backOffPending && !state.value.unlockPending) { tr("Finish the current activity first", "Terminer l'activité en cours") }
            check(state.value.ready && gatt != null && !closing && log.healthy && queue.size <= 4) { tr("BLE not ready", "BLE non prêt") }
            check(engine.map == state.value.robotMap && engine.map.complete) { tr("The route must use the connected robot's map", "Le trajet doit utiliser la carte du robot connecté") }
            NavSafety.startFailure(state.value.selected?.name, SystemClock.elapsedRealtime()-devicePrevious,
                t.battery,if (initiallyDocked && t.robotState in setOf(11,15,39)) 11 else t.robotState,host.guardSensors(t.sensorStatus),t.selfCheck,state.value.campaign.running,
                state.value.bladeTestActive,state.value.mapLoading,heightMm,if(initiallyDocked) -1 else RouteRecharge.RESERVE_PERCENT,heights = state.value.cutHeights)?.let { error(it) }
            NavSafety.automaticPositionFailure(SystemClock.elapsedRealtime()-positionPrevious,t.x,t.y)?.let { error(it) }
            // Physical geometry (obstacles at their real radius, no margin): possible clearance at the start.
            val physicalRoutes = RoutePlanning.physical(engine.map, state.value.planningObstacles,
                recordedBoundary = !engine.map.source.contains("DÉMONSTRATION", true))
            routePhysicalRoutes = physicalRoutes
            val routeStart = if (initiallyDocked) engine.baseApproach else Point(requireNotNull(t.x),requireNotNull(t.y))
            // Resume from the base (after a recharge): order of the remainder that minimises the transit ([SmartResume]).
            val smartResume = if (savedResume != null && skipTo == null && initiallyDocked) runCatching {
                val dockHeading = (engine.dockEntryHeading + 180.0) % 360.0
                SmartResume.afterRecharge(engine.edges, savedResume, engine.baseApproach, dockHeading, engine.baseApproach, dockHeading, SmartResume.linkMeters(engine.routes))
            }.getOrNull() else null
            val resume = smartResume?.checkpoint ?: savedResume
            smartResume?.let { choice -> event("route_smart_resume", tr("Resume from the base reordered: shortest transit", "Reprise depuis la base réordonnée : transit le plus court"),
                mapOf("defaultTransitM" to choice.defaultTransitM, "transitM" to choice.transitM, "entryX" to choice.entry.x, "entryY" to choice.entry.y,
                    "portions" to 1 + choice.checkpoint.deferred.size)) }
            // Obstacles near the docking corridor: same adjusted margin as when computing the route ([DockClearance]),
            // for the Fold's guards as well as the gateway's.
            val dockCorridor = DockClearance.corridor(engine.base, engine.config.baseEntryHeadingDegrees, engine.config.baseApproachMeters)
            fun dockFitted(obstacles: List<LocalObstacle>) = DockClearance.fit(obstacles, dockCorridor)
            dockFitted(state.value.obstacles).filter { it !in state.value.obstacles }.forEach { o ->
                event("obstacle_dock_margin", tr("Obstacle margin reduced near the docking corridor", "Marge d'obstacle réduite près du couloir d'accostage"), mapOf("id" to o.id, "x" to o.x, "y" to o.y,
                    "radiusM" to o.radiusM, "marginM" to o.marginM, "savedMarginM" to state.value.obstacles.firstOrNull { it.id == o.id }?.marginM))
            }
            val missionPlan = if (intent == RouteIntent.FOLLOW) RobotRoute.missionEdges(engine,routeStart,requireNotNull(t.heading),physicalRoutes,
                resume?.let { MissionResume.of(it,overlap = skipTo == null) },returnLanes) else null
            val edges = missionPlan?.edges ?: RobotRoute.actionEdges(engine,routeStart,requireNotNull(t.heading),intent,physicalRoutes,returnLanes)
            // Resume during a second pass, for example: the first remaining mowing edge gives the starting height.
            if (cutting) edges.firstNotNullOfOrNull { it.heightMm }?.let { heightMm = it }
            state.value.cutHeights.requireHeight(heightMm)
            engine.routes.baseSafety?.let { (station, approach, radius) ->
                event("robot_route_base_zone", tr("Base safety zone applied to the route", "Zone de sécurité de la base appliquée au trajet"), mapOf("baseX" to station.x, "baseY" to station.y,
                    "approachX" to approach.x, "approachY" to approach.y, "radiusM" to radius,
                    "minDistanceM" to edges.filterNot { it.escape || (it.a == station || it.b == station) }.minOfOrNull { Geometry.distance(station,it.a,it.b) }))
            } ?: event("robot_route_base_zone", tr("No safety zone: station or docking axis unknown", "Aucune zone de sécurité : station ou axe d'accostage inconnu"))
            // Manoeuvres of the route (rolling U-turns, loops, curves) with their planned duration: the field log compares
            // directly with the execution bench ([RouteManoeuvres], turn tracking plan step C5).
            RouteManoeuvres.find(edges).takeIf { it.isNotEmpty() }?.let { manoeuvres ->
                event("route_manoeuvres", tr("Route manoeuvres: ${manoeuvres.count { it.turning }} U-turns or loops, ${manoeuvres.count { !it.turning }} curves",
                    "Manœuvres du trajet : ${manoeuvres.count { it.turning }} demi-tours ou boucles, ${manoeuvres.count { !it.turning }} courbes"),
                    mapOf("uTurns" to manoeuvres.count { it.kind == RouteManoeuvres.Kind.U_TURN }, "loops" to manoeuvres.count { it.kind == RouteManoeuvres.Kind.LOOP },
                        "curves" to manoeuvres.count { it.kind == RouteManoeuvres.Kind.CURVE },
                        "list" to RouteManoeuvres.describe(edges, manoeuvres, PathFollower.linearFor(state.value.trajectorySpeedPercent) * DeadReckoning.DEFAULT_METERS_PER_UNIT)))
            }
            edges.firstOrNull { it.escape }?.let { escape -> event("robot_route_escape", tr("Start inside an obstacle's reserve: straight-line clearance", "Départ dans la réserve d'un obstacle : dégagement en ligne droite"),
                mapOf("fromX" to escape.a.x, "fromY" to escape.a.y, "toX" to escape.b.x, "toY" to escape.b.y, "meters" to escape.meters)) }
            signalSurveys.beginRoute(if (intent == RouteIntent.FOLLOW && engine.preview.mode.isSurvey) engine.preview.borders + engine.preview.runs else emptyList()) {
                SignalSurvey(requireNotNull(state.value.selected).address,SignalCoverage.mapKey(engine.map),engine.preview.zoneIds,
                    sessionId=UUID.randomUUID().toString(),startedAt=Clock.now(),mode=engine.preview.mode,mapSnapshot=engine.map)
            }
            bladeHeightRequest = heightMm
            bladeOffWrittenAt = 0L; bladeStopVerified = false
            // Resume: the trail of the interrupted mowing is kept (in memory if it is still there, otherwise on disk).
            val initialTrail = resume?.let { r -> state.value.robotRoute.trail.takeIf { routeTrailMission == r.startedAt && it.isNotEmpty() }
                ?: trailStore.read(robotId, r.startedAt) }.orEmpty()
            routeTrailMission = null
            if (resume != null) event("mission_trail_restored", tr("Trail of the interrupted mowing kept", "Trace de la tonte interrompue conservée"), mapOf("points" to initialTrail.size))
            update { it.copy(robotRoute=RobotRouteUi(trail=initialTrail,running=true,started=true,phase=RoutePhase.FOLLOWING,heightMm=heightMm,cutHeights=if (cutting) engine.preview.cutHeights else emptyList(),returnPercent=recharge.low,resumePercent=recharge.high,count=edges.size,planned=listOf(Point(requireNotNull(t.x),requireNotNull(t.y)))+edges.map { edge -> edge.b },status=tr("DumbyLuba BLE control", "Pilotage DumbyLuba BLE"))) }
            event("robot_route_start",tr("Simulated route applied through manual commands", "Trajet simulé appliqué par commandes manuelles"),mapOf("cutting" to cutting,"intent" to intent.name,"linearSpeedRaw" to PathFollower.linearFor(state.value.trajectorySpeedPercent),"returnPercent" to recharge.low,"resumePercent" to recharge.high,"heightMm" to heightMm,"edges" to edges.map { edge -> mapOf("fromX" to edge.a.x,"fromY" to edge.a.y,"toX" to edge.b.x,"toY" to edge.b.y,"cutting" to edge.cutting) }))
            // Progress saved for every coverage task (mowing, blades stopped, survey, border, painted zone):
            // resume after STOP, BLE loss, closing or restarting the app, at the point reached.
            routeCheckpoint = null; routePlan = null
            if (missionPlan != null) {
                // New coverage task: an interrupted move to a point no longer needs to be resumed.
                state.value.pointResume?.let { navigation.setPointResume(it.robotId, null) }
                val now = Clock.now()
                // Presumed obstacles and avoided areas of the plan: those of the start, kept from one resume to the next.
                val checkpoint = resume?.copy(interruption = null, updatedAt = now, resumes = resume.resumes + 1, heightMm = heightMm,
                    returnPercent = recharge.low, resumePercent = recharge.high,
                    avoidance = MissionAvoidance.forResume(resume, state.value.difficulties, state.value.obstacles))
                    ?: MissionCheckpoint(robotId, MissionProgress.mapKey(engine.map), engineFingerprint, engine.edges.size, engine.preview,
                        cutting, heightMm, recharge.low, recharge.high, totalMeters = MissionProgress.totalCuttingMeters(engine.edges), startedAt = now,
                        avoidance = planningAvoidance ?: MissionAvoidance.of(state.value.difficulties), bladeZones = engine.bladeZones,
                        base = missionBase(engine))
                routeCheckpoint = checkpoint; routePlan = missionPlan; routeEngineEdges = engine.edges
                checkpointEngine = engine
                routeTrailMission = checkpoint.startedAt
                persistCheckpoint(checkpoint, force = true)
                taskHistory.historyBegin(engine, checkpoint, resume != null, t.battery, when {
                    engine.preview.mode.isSurvey -> TaskKind.SURVEY
                    cutting -> TaskKind.MOW
                    else -> TaskKind.FOLLOW
                }, heightMm)
                event(if (resume != null) "mission_resume" else "mission_checkpoint_start",
                    if (resume != null) tr("Resuming the interrupted task", "Reprise de la tâche interrompue") else tr("Progress saved for resume", "Avancement sauvegardé pour reprise"),
                    mapOf("task" to checkpoint.task.name, "edge" to checkpoint.edge, "progress" to checkpoint.progress, "resumes" to checkpoint.resumes,
                        "fingerprint" to engineFingerprint, "skippedMeters" to skippedNow, "skippedTotalMeters" to checkpoint.skippedMeters))
            }
            host.send(host.reportsCommand(false,periodMs=200),"ROUTE_REPORTS")
            routeIntent = intent
            workWindowEnding = false
            routeJob = host.viewModelScope.launch {
                try {
                    RouteMission(engine, cutting, intent, saved, heightMm, recharge, lowWatch, returnReserve, returnDetour, reserveWatch,
                        reserveWatchAt, t, initiallyDocked, physicalRoutes, resume, ::dockFitted, edges).run()
                } catch (cancel: CancellationException) { throw cancel }
                catch (end: WorkWindowEnded) {
                    val reason=alerts.stopReason()
                    event("work_window_docked",reason)
                    host.requestStop(false,reason,intentional=true); alerts.rain.docked(engine)
                }
                catch (error: Exception) {
                    // Gateway link failing during the recharge: automatic resume, robot charging ([DockResume]).
                    val resumeEngine=checkpointEngine
                    val dockResume=(state.value.viaGateway || dockResumeAnyLink) && DockResume.wanted(rechargeCycle,state.value.robotRoute.running,routeCheckpoint!=null) &&
                        DockResume.linkCause(error.message,gatewayOutageSince!=0L)
                    host.requestStop(false,tr("Route interrupted: ${error.message}", "Trajet interrompu : ${error.message}"))
                    if (dockResume && resumeEngine!=null) planDockResume(resumeEngine,error.message ?: tr("gateway link", "liaison passerelle"))
                }
            }
            return true
        } catch (error: Exception) {
            update { it.copy(robotRoute=it.robotRoute.copy(status=tr("Start refused: ${error.message}", "Départ refusé : ${error.message}"))) }
            event("robot_route_refused",error.message ?: tr("Start refused", "Départ refusé"))
            host.status(tr("Mowing refused: ${error.message}", "Tonte refusée : ${error.message}"))
            return false
        }
    }

    /**
     * One route mission started by [startRoute], run inside its coroutine: tracking, recharge, obstacles and recoveries
     * share the mission state as fields. The constructor takes what [startRoute] validated and prepared; built inside
     * the coroutine, it reads the robot state at the same moment as the steps that follow.
     */
    private inner class RouteMission(
        private val engine: MowingSimulation,
        private val cutting: Boolean,
        private val intent: RouteIntent,
        private val saved: MissionCheckpoint?,
        /** Height of the pass in progress ([SimEdge.heightMm]), reused by the runs, keep-alives, back-offs and blade stop. */
        private var heightMm: Int,
        private val recharge: RouteRecharge,
        /** Watched return threshold: the low threshold, or the [FinishPolicy] floor while finishing below it. */
        private var lowWatch: Int,
        private val returnReserve: Int,
        private var returnDetour: Double,
        private var reserveWatch: Int,
        private var reserveWatchAt: Long,
        /** Telemetry read when the mission was validated. */
        private val t: Telemetry,
        private val initiallyDocked: Boolean,
        private val physicalRoutes: TrajectoryPlanner,
        private val resume: MissionCheckpoint?,
        private val dockFitted: (List<LocalObstacle>) -> List<LocalObstacle>,
        private val edges: List<SimEdge>,
    ) {
        // Height: in the READY state the driver setpoint alone is ignored (firmware 1.30); it is carried
        // by mow_ctrl_by_hand on every range and awaited with the blades running and the robot stopped.
        // Calibration kept from one range to the next for the whole route. Pose predicted at the moment
        // the command takes effect (≈ 0.6 s measured during the debug mowing of 10-10): without this
        // compensation, tracking zigzagged by ±10–20 cm on the stripes and pivoted mid-stripe.
        // Gains already calibrated on this robot (previous mission): more accurate first curves.
        fun newRouteReckoning() = DeadReckoning(commandDelayMs = DeadReckoning.ROUTE_COMMAND_DELAY_MS, calibration = calibration.saved(),
            publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M).also { it.slide = difficulties.slideVelocity() }
        var routeReckoning = newRouteReckoning()
        val routeSlideMeter = PivotSlideMeter()
        // Main route, repaired after a discovered obstacle: edges, the original edge of each one
        // (-1: bypass), navigation for the rest (discovered exclusions included).
        var routeEdges = edges
        var routeOrigins: List<Int>? = null
        var missionRoutes = engine.routes
        val discovered = mutableListOf<LocalObstacle>()
        // Presumed obstacles at launch (repeated or recent contacts): checked like the recorded obstacles.
        // Already avoided by the prepared route, they count as planned; otherwise (resume of a mowing
        // prepared before the contact), they trigger a bypass.
        val presumedObstacles = dockFitted(if (state.value.difficulties.avoidPresumed) state.value.difficulties.presumed else emptyList())
        val plannedObstacles = (state.value.obstacles.map { it.id } + presumedObstacles.filter { o ->
            FollowObstacleGuard.keptOut(o, edges.filterNot { it.escape }.map { it.a to it.b })
        }.map { it.id }).toMutableSet()
        var bypassCount = 0
        // Base safety zone: drift tolerated on the return only if it is known.
        val routeBaseZone = engine.routes.baseSafety?.let { (station,_,radius) -> ReturnDrift.BaseZone(station,radius) }
        // Pivot slip on slopes, tracked from one range to the next (same rule as the gateway).
        val routeSlide = PivotSlide()
        val speedCaps = PathSpeedCaps { missionRoutes.speedZones }
        /** Follows path[from until to) with a single blade state, without STOP at aligned vertices.
         * Returns the index reached: `to` if the range is finished, otherwise the edge interrupted by the battery. */
        /** Battery points needed to return from the robot's position ([FinishPolicy]); null without a mowing measurement. */
        fun returnNeed(): Double? {
            if (intent!=RouteIntent.FOLLOW) return null
            val record=state.value.mowingRecord ?: return null
            val t=state.value.telemetry
            val here=Point(t.x ?: return null, t.y ?: return null)
            val meters=ReturnReserve.returnMeters(here.distance(engine.baseApproach), returnDetour, engine.base.distance(engine.baseApproach))
            val (autonomy,factor)=MowingCalibration.finishModel(record)
            return runCatching { FinishPolicy.neededPercent(FinishPolicy.restSeconds(meters,record.speedMps,factor),autonomy) }.getOrNull()
        }
        /** Threshold watched now: the low threshold raised by the return reserve (refreshed every 5 s), or the finish floor. */
        fun batteryWatch(): Int {
            if (lowWatch!=recharge.low) return lowWatch
            val now=SystemClock.elapsedRealtime()
            if (now-reserveWatchAt>=5_000) {
                reserveWatchAt=now
                val previous=reserveWatch
                reserveWatch=returnNeed()?.let { ReturnReserve.threshold(recharge.low,recharge.high,it,returnReserve) } ?: recharge.low
                if (reserveWatch!=previous) update { it.copy(robotRoute=it.robotRoute.copy(lowWatch=reserveWatch.takeIf { w -> w>recharge.low })) }
            }
            return maxOf(lowWatch,reserveWatch)
        }
        /** Detour of the real return path from here (off the control loop, at the start of each range). */
        suspend fun calibrateReturn() {
            if (intent!=RouteIntent.FOLLOW || lowWatch!=recharge.low) return
            val t=state.value.telemetry
            val here=Point(t.x ?: return, t.y ?: return); val heading=t.heading ?: return
            val straight=here.distance(engine.baseApproach)
            if (straight<2.0) return
            val routes=missionRoutes
            val path=withContext(Dispatchers.Default) {
                runCatching { routes.between(here,engine.baseApproach,heading,(engine.dockEntryHeading+180.0)%360.0) }.getOrNull() } ?: return
            returnDetour=ReturnReserve.detour(path.zipWithNext().sumOf { (a,b) -> a.distance(b) },straight)
            reserveWatchAt=0L
        }
        suspend fun followPath(path: List<SimEdge>, from: Int, to: Int, shown: Boolean, watchBattery: Boolean = false, departure: Boolean = false,
                               finalArrivalM: Double = if (path[to-1].b==engine.baseApproach) 0.05 else RangeArrival.TARGET_M): Int {
            if (watchBattery) {
                calibrateReturn()
                val live=state.value.telemetry
                NavSafety.tickFailure(SystemClock.elapsedRealtime()-devicePrevious,live.battery,host.guardSensors(live.sensorStatus),true,RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                if (requireNotNull(live.battery) <= batteryWatch() || workWindowEnding) return from
            }
            val blades = cutting && path[from].bladesOn
            check((from until to).all { (cutting && path[it].bladesOn) == blades }) { tr("Inconsistent blade range", "Plage de lames incohérente") }
            fun showIndex(index: Int) { if (shown) update { it.copy(robotRoute=it.robotRoute.copy(index=index+1,status="${if (blades) tr("Cutting", "Coupe") else tr("Transit blades OFF", "Transit lames OFF")} · ${index+1}/${routeEdges.size}")) } }
            showIndex(from)
            // Manual mode (state 20) also with blades OFF: the firmware only publishes the bumper contact
            // (-1014) that way (obstacle test of 10-08). Except when leaving the station.
            val bumperWatch = !departure
            if ((blades || bumperWatch) && state.value.telemetry.robotState != 20) {
                if (blades) bladeMayBeOn = true
                host.send(commands.manualMow(1,0,heightMm),"ROUTE_MANUAL_MODE")
                val modeDeadline = SystemClock.elapsedRealtime()+3000
                while (state.value.telemetry.robotState != 20) {
                    check(state.value.ready && !closing && log.healthy && SystemClock.elapsedRealtime()<modeDeadline) {
                        if (blades) tr("Manual mowing mode not confirmed", "Mode tonte manuelle non confirmé") else tr("Bumper detection inactive: manual mode with blades OFF not confirmed", "Détection bumper inactive : mode manuel lames OFF non confirmé") }
                    delay(200)
                }
            }
            // Stop only when the blade state changes; OFF confirmed before any link.
            host.send(commands.stop(),"ROUTE_WAYPOINT_STOP")
            routeReckoning.sent(SystemClock.elapsedRealtime(),0,0)
            val bladeGate = RouteBladeGate.unique("ROUTE_BLADES_${from}_${if (blades) "ON" else "OFF"}",blades)
            routeBladeGate = bladeGate
            bladeMayBeOn = bladeMayBeOn || blades
            host.send(if (blades || !bumperWatch) commands.manualBlades(blades,heightMm) else commands.manualMow(1,0,heightMm),bladeGate.label)
            val begin = SystemClock.elapsedRealtime()
            val meters = (from until to).sumOf { path[it].meters }
            // Long range (full border): proportional budget, at least 5 cm/s on average.
            val budgetMs = 120_000L + (meters/0.05*1000).toLong()
            val motionProgress=MotionProgressGuard()
            // Slip map re-read on every range: pivots already measured during the mission are used right away.
            val follower=PathFollower(path,from,to,finalArrivalM).also { it.slide = difficulties.slideVelocity(); routeReckoning.slide = it.slide }
            val manoeuvreStats = ManoeuvreStats(path)
            val returnFrom=ReturnDrift.returnFrom(path,engine.baseApproach)
            // End of range confirmed by a position measured after the stop (except the base approach,
            // followed by docking): the next range starts at the real corner.
            val arrival = if (path[to-1].b==engine.baseApproach) null else RangeArrival(DeadReckoning.ROUTE_COMMAND_DELAY_MS)
            var fixAt=0L
            var progressAt = begin; var best = Double.POSITIVE_INFINITY; var bestIndex = from
            var bladeObserved = !blades; var keepalive = 0L; var queryAt = 0L
            val heightSince = Clock.now()
            val heightBudgetMs = HeightSequence.timeoutMs(state.value.telemetry.bladeHeightMm, heightMm)
            var heightReady = !blades
            /** Protection raised while waiting for the contact code: time and stop reason if no contact follows. */
            var latch: Pair<Long,String>? = null
            // Bumper contact on the main edges: bypass rather than stopping the mission.
            routeBypassArmed = shown
            try { while (true) {
                val now = SystemClock.elapsedRealtime()
                routeContact?.let { contact -> throw ObstacleEncounter(follower.index, contact, emptyList(), tr("Bumper contact (${contact.code})", "Contact bumper (${contact.code})")) }
                check(state.value.ready && !closing && log.healthy && queue.size <= 4) { tr("BLE interrupted or queue saturated", "BLE interrompu ou file saturée") }
                val live = state.value.telemetry
                NavSafety.automaticPositionFailure(now-positionPrevious,live.x,live.y)?.let { error(it) }
                NavSafety.tickFailure(now-devicePrevious,live.battery,host.guardSensors(live.sensorStatus),true,RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                val protectionUp = live.robotState !in (if (departure) listOf(11,15,20,39) else listOf(11,20)) || !SelfCheck.nominal(live.selfCheck)
                // Protection the robot can clear by itself: the mission waits then resumes ([ProtectionPause]).
                fun protectionStop(reason: String): Nothing = if (ProtectionPause.pausable(reason)) throw RouteProtection(follower.index,reason) else error(reason)
                // Protection waiting for the contact code: cleared, or delay elapsed without contact → stop on the protection.
                latch?.let { (since, reason) -> if (!protectionUp || now-since >= CollisionLatch.CONTACT_GRACE_MS) protectionStop(reason) }
                // Positioning loss (weak satellite signal) with the robot still driving: ridden through for a few seconds.
                val riding = protectionUp && latch == null &&
                    routePositioningGrace.rides(now,live.robotState,live.selfCheck,live.x?.let { x -> live.y?.let { y -> Point(x,y) } })
                if (!protectionUp) routePositioningGrace.reset()
                if (riding && !routeRidingPositioning) event(PositioningGrace.EVENT,
                    tr("Positioning lost (self-check ${live.selfCheck}): route continued for a few seconds", "Positionnement perdu (self-check ${live.selfCheck}) : trajet poursuivi quelques secondes"),
                    mapOf("selfCheck" to live.selfCheck,"edge" to follower.index,"x" to live.x,"y" to live.y,"maxMs" to PositioningGrace.MAX_MS,"maxM" to PositioningGrace.MAX_M))
                if (!riding && routeRidingPositioning && protectionUp) event(PositioningGrace.EVENT+"_spent",
                    tr("Positioning still lost after the grace: robot stopped", "Positionnement toujours perdu après le délai de grâce : robot arrêté"),
                    mapOf("selfCheck" to live.selfCheck,"elapsedMs" to routePositioningGrace.elapsedMs(now),"edge" to follower.index))
                routeRidingPositioning = riding
                if (protectionUp && !riding) {
                    val reason = ProtectionPause.reason(live.robotState,live.selfCheck)
                    // Collision lock (25) published before the contact code (10-15, 11:00:06): immediate stop, then the
                    // contact read from the error history gives the back-off and the bypass ([CollisionLatch]).
                    if (latch == null) {
                        if (!CollisionLatch.mayAnnounceContact(live.robotState,live.selfCheck)) protectionStop(reason)
                        latch = now to reason
                        host.purgeQueuedCommands(); host.send(commands.movement(0,0),"ROUTE_LATCH_STOP"); routeReckoning.sent(now,0,0)
                        event("route_latch_wait",tr("Robot protection: stop, contact code awaited", "Protection du robot : arrêt, code de contact attendu"),mapOf("selfCheck" to live.selfCheck,
                            "robotState" to live.robotState,"edge" to follower.index,"graceMs" to CollisionLatch.CONTACT_GRACE_MS))
                        keepalive = 0L; queryAt = 0L
                    }
                    // Blades stopped, manual mode kept alive (the robot only publishes the contact that way), errors polled.
                    if ((blades || bumperWatch) && now>=keepalive) { host.send(commands.manualMow(1,0,heightMm),"ROUTE_LATCH_BUMPER_OFF",periodic=true); keepalive=now+500 }
                    if (now>=queryAt) { host.send(commands.errorQuery(),"ROUTE_LATCH_ERRORS",periodic=true); queryAt=now+500 }
                    delay(100); continue
                }
                val published = Point(requireNotNull(live.x),requireNotNull(live.y))
                val reportedHeading = requireNotNull(live.heading).also { check(it.isFinite()) }
                val bladeBits = (requireNotNull(live.sensorStatus) shr 9) and 7
                if (blades && bladeGate.confirmed(devicePrevious,bladeBits)) bladeObserved = true
                if (!blades && bladeGate.confirmed(devicePrevious,bladeBits)) bladeMayBeOn = false
                if (!blades && !(bladeGate.confirmed(devicePrevious,bladeBits))) {
                    check(now-begin < 3000) { tr("Blade stop not confirmed", "Arrêt lames non confirmé") }
                    delay(200); continue
                }
                if (!heightReady) {
                    if (HeightSequence.reached(live, heightMm, heightSince)) {
                        heightReady = true
                        event("route_height", tr("Height $heightMm mm reported before cutting", "Hauteur $heightMm mm rapportée avant la coupe"), mapOf("waitMs" to now-begin))
                    } else {
                        check(now-begin < heightBudgetMs) { tr("Height $heightMm mm not reported (${live.bladeHeightMm ?: "—"} mm)", "Hauteur $heightMm mm non rapportée (${live.bladeHeightMm ?: "—"} mm)") }
                        if (blades && bladeObserved) check(bladeBits == 1) { tr("Blade rotation interrupted", "Rotation des lames interrompue") }
                        if (shown) update { it.copy(robotRoute=it.robotRoute.copy(status=tr("Setting height $heightMm mm · ${live.bladeHeightMm ?: "—"} mm reported", "Réglage hauteur $heightMm mm · ${live.bladeHeightMm ?: "—"} mm rapportés"))) }
                        progressAt = now
                        delay(200); continue
                    }
                }
                if (watchBattery && requireNotNull(live.battery) <= batteryWatch()) {
                    host.purgeQueuedCommands(); host.send(commands.stop(), "ROUTE_LOW_BATTERY_STOP")
                    return follower.index
                }
                // End of the working hours: same stop as the low threshold, then return to base.
                if (watchBattery && workWindowEnding) {
                    host.purgeQueuedCommands(); host.send(commands.stop(), "ROUTE_WORK_WINDOW_STOP")
                    return follower.index
                }
                if (blades && bladeObserved) check(bladeBits == 1) { tr("Blade rotation interrupted", "Rotation des lames interrompue") }
                if (blades) check(bladeObserved || now-begin < 5000) { tr("Blade rotation not confirmed", "Rotation lames non confirmée") }
                // Positions at 1 Hz: command on the predicted pose, checks on the measured pose.
                // Published heading realigned on the direction travelled (the LUBA 2 may publish a wrong heading when stopped).
                val fixed = positionPrevious != fixAt
                if (fixed) { fixAt = positionPrevious; routeReckoning.fix(published,reportedHeading,fixAt); calibration.save(routeReckoning) }
                val heading = routeReckoning.heading(reportedHeading)
                // Rotation centre: the published point is 8 cm behind it ([DeadReckoning.PUBLISHED_POINT_BEHIND_M]).
                val position = routeReckoning.measured ?: routeReckoning.center(published,heading)
                if (fixed) routeSlideMeter.update(position,Clock.now(),routeReckoning.rotationSlide)?.let { difficulties.learnSlide(it,"local") }
                val headingTrusted = routeReckoning.headingConfirmed
                confirmedHeading = heading.takeIf { headingTrusted }
                val predicted = routeReckoning.predict(now) ?: RobotPose(position,heading)
                val previousIndex = follower.index
                val speedPercent = state.value.trajectorySpeedPercent
                // Heading not confirmed, or pivot not measured yet: slow advance until the next position.
                val setLinear = AdaptiveSpeed.setLinear(speedPercent,speedCaps.of(path),path,follower.index,predicted.position,live,Clock.now())
                val linearLimit = if (!headingTrusted) minOf(setLinear, DeadReckoning.UNCONFIRMED_LINEAR) else routeReckoning.postPivotLinear(setLinear)
                val command = follower.command(predicted.position,predicted.headingDegrees,linearLimit,
                    PathFollower.angularLimit(speedPercent),routeReckoning.degreesPerUnit,routeReckoning.metersPerUnit,
                    RangeArrival.APPROACH_MIN_LINEAR,follower.deviation(position),routeReckoning.rotationCalibrated)
                if (follower.index != previousIndex) showIndex(follower.index)
                manoeuvreStats.update(Clock.now(), follower.index, position)?.let { host.logManoeuvre(it, "local") }
                if (shown) (routeOrigins?.get(follower.index) ?: follower.index).let { origin -> if (origin >= 0) recordMissionProgress(origin, position) }
                val edge = path[follower.index]
                // Return to base: drift tolerated up to 1 m, chassis within the safe surface and outside the base zone.
                val sliding = routeSlide.update(follower.pivoting,position,follower.deviation(position))
                ReturnDrift.failure(follower.deviation(position),follower.index,follower.index>=returnFrom,position,routeBaseZone,sliding) { p ->
                    missionRoutes.bodySafe(p) }?.let { if (DriftRecovery.recoverable(it)) throw RouteDrift(follower.index,it) else error(it) }
                if (signalSurveys.surveyContours.isNotEmpty() && shown && edge.cutting && state.value.robotRoute.phase == RoutePhase.FOLLOWING) {
                    Clock.now().let { now -> SignalCoverage.sample(live,now,signalSurveys.surveyContours,RobotTilt.current(now)) }
                        ?.let(signalSurveys::recordSurveySample)
                }
                update { it.copy(robotRoute=it.robotRoute.copy(trail=MissionTrail.append(it.robotRoute.trail,position))) }
                if (command.done) {
                    // Very short range: wait for blade confirmation while stopped rather than failing.
                    if (blades && !bladeObserved) { host.send(commands.movement(0,0),"ROUTE_WAIT_BLADES"); routeReckoning.sent(now,0,0); delay(200); continue }
                    when (RangeArrival.settle(arrival,now,fixAt,position,follower,path[to-1].b)) {
                        null -> {
                            // Commanded stop: normal immobility, blades and bumper detection kept alive.
                            motionProgress.reset(); progressAt = now
                            if (blades && now>=keepalive) { host.send(commands.manualBlades(true,heightMm),"ROUTE_BLADES_KEEPALIVE",periodic=true); keepalive=now+600 }
                            if (!blades && bumperWatch && now>=keepalive) { host.send(commands.manualMow(1,0,heightMm),"ROUTE_BUMPER_KEEPALIVE_OFF",periodic=true); keepalive=now+500 }
                            if (now>=queryAt) { host.send(commands.errorQuery(),"ROUTE_ERRORS",periodic=true); queryAt=now+500 }
                            host.send(commands.movement(0,0),"ROUTE_SETTLE $to"); routeReckoning.sent(now,0,0); delay(200); continue
                        }
                        false -> {
                            event("route_settle",tr("Stopped before the end-of-range point: approach resumed", "Arrêt avant le point de fin de plage : approche reprise"),mapOf("edge" to to,"approach" to arrival?.approaches,
                                "distanceM" to arrival?.distanceM,"remainingM" to arrival?.remainingM,"x" to position.x,"y" to position.y))
                            continue
                        }
                        true -> break
                    }
                }
                val distance = position.distance(edge.b)
                if (follower.index > bestIndex || distance < best-0.03) { bestIndex=follower.index; best=distance; progressAt=now }
                val (linear,angular) = command.linear to command.angular
                if (linear == 0) progressAt=now
                if (now-progressAt >= 15000) throw RouteBlocked(follower.index,tr("No progress for 15 s (edge ${follower.index+1})", "Absence de progression depuis 15 s (arête ${follower.index+1})"))
                check(now-begin < budgetMs) { tr("Range too long: %.0f m not covered in %d s", "Plage trop longue : %.0f m non parcourus en %d s").format(meters,budgetMs/1000) }
                if (motionProgress.blocked(position,positionPrevious,now,linear)) throw RouteBlocked(follower.index,tr("Presumed blockage: no translation for 3 s, bumper contact not confirmed", "Blocage présumé : aucune translation depuis 3 s, contact bumper non confirmé"))
                val rad = Math.toRadians(heading)
                // Projection ahead of the robot: distance covered before the next position (1 Hz)
                // and the reaction, bounded by the next stop (corner or end of range).
                val reach = minOf(maxOf(0.25,linear*routeReckoning.metersPerUnit*1.5),command.stopDistanceM)
                val next = if (linear == 0) RobotPose(position,heading+if (angular>0) 6.0 else -6.0)
                    else RobotPose(Point(position.x+reach*cos(rad),position.y+reach*sin(rad)),heading)
                // Clearance out of an obstacle's reserve: same checks, obstacles at their physical radius.
                // Recorded obstacles and those discovered during the mission (contacts) checked together.
                val escaping = follower.escaping(position)
                val intruding = dockFitted(state.value.obstacles + presumedObstacles + discovered).filter { obstacle ->
                    FollowObstacleGuard.intrudes(Point(obstacle.x,obstacle.y),position,next.position,
                        obstacle.radiusM,obstacle.exclusionRadiusM,obstacle.id in plannedObstacles,escaping)
                }
                // Obstacle added during the mission (absent from the prepared route): bypass.
                val added = intruding.filter { it.id !in plannedObstacles }
                if (shown && added.isNotEmpty()) throw ObstacleEncounter(follower.index, null, added, tr("Obstacle added on the route", "Obstacle ajouté sur le trajet"))
                // Obstacle already bypassed by the route, robot entered its reserve (slip, pivot): back-off and link.
                if (intruding.isNotEmpty() && added.isEmpty()) throw RouteDrift(follower.index,DriftRecovery.OBSTACLE_RESERVE)
                check(intruding.isEmpty()) { tr("Local obstacle added inside the robot envelope", "Obstacle local ajouté dans l'enveloppe robot") }
                val envelopeRoutes = if (escaping) routePhysicalRoutes ?: missionRoutes else missionRoutes
                // Oriented envelope only with a confirmed heading; otherwise slow advance, centre and deviation checked.
                // Escape in a straight line of a chassis already overlapping an edge: allowed while it only moves away.
                val leaving = escaping && next.position != position && envelopeRoutes.leavesStraight(position,next.position,heading)
                if (headingTrusted && !leaving && envelopeRoutes.motionCollision(RobotPose(position,heading),next)!=null)
                    throw RouteDrift(follower.index,tr("Robot envelope outside the safe corridor", "Enveloppe robot hors du couloir sûr"))
                if (blades && now>=keepalive) { host.send(commands.manualBlades(true,heightMm),"ROUTE_BLADES_KEEPALIVE"); keepalive=now+600 }
                if (!blades && bumperWatch && now>=keepalive) { host.send(commands.manualMow(1,0,heightMm),"ROUTE_BUMPER_KEEPALIVE_OFF"); keepalive=now+500 }
                // Bumper contact read from the error history: polled twice per second.
                if (now>=queryAt) { host.send(commands.errorQuery(),"ROUTE_ERRORS"); queryAt=now+500 }
                host.send(commands.movement(linear,angular),"ROUTE_MOVE ${follower.index} $linear $angular")
                routeReckoning.sent(now,linear,angular)
                delay(200)
            } } finally { routeBypassArmed = false }
            host.purgeQueuedCommands(); host.send(commands.stop(), "ROUTE_SEGMENT_STOP")
            routeReckoning.sent(SystemClock.elapsedRealtime(),0,0)
            calibration.save(routeReckoning, force = true)
            return to
        }
        /** Successive ranges with the same blade state; returns the edge interrupted by the battery, or path.size. */
        suspend fun followAllLocal(path: List<SimEdge>, from: Int = 0, shown: Boolean = false, watchBattery: Boolean = false,
                              departure: Boolean = false, lastArrivalM: Double? = null): Int {
            var index = from
            while (index < path.size) {
                // Blades running continuously within a zone: stop only when the zone changes and for the base.
                val blades = cutting && path[index].bladesOn
                var to = index + 1
                while (to < path.size && (cutting && path[to].bladesOn) == blades) to++
                val reached = if (lastArrivalM != null && to == path.size) followPath(path,index,to,shown,watchBattery,departure,lastArrivalM)
                    else followPath(path,index,to,shown,watchBattery,departure)
                if (reached < to) return reached
                index = to
            }
            return path.size
        }
        /**
         * Same contract as [followAllLocal], but tracking is run by the gateway (phone mounted on the robot): the
         * route and envelope surfaces are sent to it once, it follows without depending on Wi-Fi and returns the
         * outcome (end, low battery, contact, added obstacle, safety stop). Good Wi-Fi: live progress, surveys and
         * telemetry; Wi-Fi down: the robot carries on, the link is re-established and the state replayed.
         */
        suspend fun followAllRemote(path: List<SimEdge>, from: Int = 0, shown: Boolean = false, watchBattery: Boolean = false,
                                    departure: Boolean = false, lastArrivalM: Double? = null): Int {
            val link = gatt as? GatewayLink ?: error(tr("Autonomous tracking: no gateway link", "Suivi autonome : liaison passerelle absente"))
            fun knownObstacles() = dockFitted(state.value.obstacles + presumedObstacles + discovered).map { o ->
                LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, o.id in plannedObstacles) }
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val routeId = legs.ensureRoute(path)
                    val guardId = legs.ensureGuard(missionRoutes.physicalSurface)
                    val physicalId = (routePhysicalRoutes ?: missionRoutes).let { if (it === missionRoutes) guardId else legs.ensureGuard(it.physicalSurface) }
                    val spec = LegSpec(legs.nextLegId(), routeId, from, cutting, shown, watchBattery, departure, lastArrivalM,
                        heightMm, state.value.trajectorySpeedPercent.coerceIn(10, 100), if (watchBattery) { calibrateReturn(); batteryWatch() } else lowWatch, engine.baseApproach, guardId, physicalId,
                        calibration.saved(), knownObstacles(), sampling = shown && signalSurveys.surveyContours.isNotEmpty(), baseZone = routeBaseZone,
                        slideField = difficulties.slideFor(path), speedCaps = speedCaps.of(path).take(LegSpec.MAX_SPEED_CAPS)).fitted {
                        event("gateway_speed_caps_dropped", tr("Speed limits too large for the gateway: set speed everywhere", "Limites de vitesse trop volumineuses pour la passerelle : vitesse réglée partout")) }
                    // Obstacle list too long for one frame: tracked from here rather than a send that would cut the link.
                    if (spec.encode().size > LegFrames.MAX_PAYLOAD) {
                        event("gateway_leg_fallback", tr("Tracking too large for the gateway (${spec.obstacles.size} obstacles): tracked from here", "Suivi trop volumineux pour la passerelle (${spec.obstacles.size} obstacles) : suivi d'ici"))
                        return followAllLocal(path, from, shown, watchBattery, departure, lastArrivalM)
                    }
                    return runRemoteLeg(link, spec, path, obstacles = { dockFitted(state.value.obstacles + discovered) },
                        planned = { plannedObstacles }, originOf = { index -> routeOrigins?.get(index) ?: index })
                } catch (refused: RemoteLegs.RefusedLeg) {
                    // Route forgotten by the gateway (restarted): a single new send.
                    if (attempt >= 2) throw refused
                    event("gateway_leg_resend", tr("Tracking refused (${refused.message}): route sent again", "Suivi refusé (${refused.message}) : trajet renvoyé"))
                }
            }
        }
        /** Height passes: tracking split at every height change ([SimEdge.heightMm]); the next range stops,
         * sets the new height and waits for it before cutting ([HeightSequence]).
         * Edge without a height (transit, bypass): height of the previous range. */
        suspend fun followAll(path: List<SimEdge>, from: Int = 0, shown: Boolean = false, watchBattery: Boolean = false,
                              departure: Boolean = false, lastArrivalM: Double? = null): Int {
            var index = from
            while (index < path.size) {
                val (end, height) = RobotRoute.heightRange(path, index, heightMm, cutting)
                if (height != heightMm) {
                    event("route_pass_height", tr("Next pass: height $heightMm → $height mm", "Passe suivante : hauteur $heightMm → $height mm"),
                        mapOf("fromMm" to heightMm, "toMm" to height, "edge" to index, "pass" to engine.preview.cutHeights.indexOf(height) + 1,
                            "passes" to engine.preview.cutHeights.size))
                    heightMm = height; bladeHeightRequest = height
                    if (shown) update { it.copy(robotRoute = it.robotRoute.copy(heightMm = height)) }
                    routeCheckpoint = routeCheckpoint?.copy(heightMm = height)
                }
                val (segment, arrival) = if (end == path.size) path to lastArrivalM else path.subList(0, end) to null
                val reached = if (host.autonomousLegsAvailable()) followAllRemote(segment, index, shown, watchBattery, departure, arrival)
                    else followAllLocal(segment, index, shown, watchBattery, departure, arrival)
                if (reached < end) return reached
                index = end
            }
            return path.size
        }
        fun routeStatus(phase: RoutePhase, message: String) {
            update { it.copy(robotRoute=it.robotRoute.copy(phase=phase,status=message)) }
            event("robot_route_phase",message,mapOf("phase" to phase.name))
        }
        /** Fresh robot state; [contactLatch]: right after a contact, the collision lock (25) is accepted ([CollisionLatch]). */
        fun freshState(requirePosition: Boolean = true, charging: Boolean = false, contactLatch: Boolean = false): Telemetry {
            val now = SystemClock.elapsedRealtime()
            check(state.value.ready && !closing && log.healthy && queue.size <= 4) { tr("BLE interrupted or queue saturated", "BLE interrompu ou file saturée") }
            val live = state.value.telemetry
            NavSafety.tickFailure(now-devicePrevious,live.battery,host.guardSensors(live.sensorStatus),false,
                if (charging) -1 else RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
            check(SelfCheck.nominal(live.selfCheck) || (contactLatch && CollisionLatch.latched(live.selfCheck))) { tr("Robot protection active", "Protection robot active") }
            check(live.robotState in listOf(11,15,20,39)) { tr("Robot state incompatible with the station (state ${live.robotState})", "État robot incompatible avec la station (état ${live.robotState})") }
            if (requirePosition) NavSafety.automaticPositionFailure(now-positionPrevious,live.x,live.y)?.let { error(it) }
            return live
        }
        /** [contactLatch]: right after a contact, the collision lock (25) is accepted ([CollisionLatch]); it only
         * clears once the robot has moved away from the object, through the back-off that follows this stop. */
        suspend fun bladesOff(contactLatch: Boolean = false) {
            host.purgeQueuedCommands(); host.send(commands.stop(), "ROUTE_RECHARGE_STOP")
            val stopAt = SystemClock.elapsedRealtime()
            val gate = RouteBladeGate.unique("ROUTE_RECHARGE_OFF",false)
            routeBladeGate = gate
            host.send(commands.manualBlades(false,heightMm),gate.label)
            val deadline = SystemClock.elapsedRealtime()+5000
            while (true) {
                val live = state.value.telemetry
                check(state.value.ready && !closing && log.healthy && SystemClock.elapsedRealtime()<deadline) { tr("Blade stop not confirmed", "Arrêt des lames non confirmé") }
                check(SystemClock.elapsedRealtime()-devicePrevious in 0..2000) { tr("Robot state stale", "État robot périmé") }
                if (gate.confirmed(devicePrevious,((live.sensorStatus ?: -1) shr 9) and 7)) break
                delay(200)
            }
            bladeMayBeOn=false
            // Two positions published after the STOP takes effect (1 Hz, delay ≈ 0.6 s), stationary,
            // before storing the resume point.
            if (state.value.telemetry.chargeState !in setOf(1,2)) {
                val stopped = StopConfirmation(stopAt)
                while (true) {
                    val live = freshState(contactLatch=contactLatch)
                    val result = stopped.observe(SystemClock.elapsedRealtime(),positionPrevious,Point(requireNotNull(live.x),requireNotNull(live.y)))
                    if (result == true) break
                    check(result == null) { tr("Physical stop not confirmed (%.0f cm between two positions after the STOP)", "Arrêt physique non confirmé (%.0f cm entre deux positions après le STOP)").format(stopped.lastMoveM*100) }
                    delay(100)
                }
                event("route_stop_confirmed",tr("Physical stop confirmed by two positions", "Arrêt physique confirmé par deux positions"),mapOf("waitMs" to SystemClock.elapsedRealtime()-stopAt))
            }
        }
        /**
         * Docking: robot stopped at the approach point in front of the base, blades OFF and manual mode left, then
         * the official Mammotion return, which only handles docking from the front of the base. Alignment and
         * back-off driven from here (test of 10-13: rotation of 20 had no effect, back-off at ~1 cm/s) were dropped.
         * End: charging contact reported after the request. STOP here: Mammotion return cancelled ([requestStop]).
         */
        suspend fun dockHeld() {
            // Reports at 1 Hz through the gateway: a late report (2.1 s on 10-13) does not fail docking;
            // a held Wi-Fi outage is waited out (1 min at most), the robot being stopped or in Mammotion return.
            suspend fun settledState(): Telemetry {
                val began=SystemClock.elapsedRealtime()
                while (true) {
                    val attempt=runCatching { freshState() }
                    attempt.getOrNull()?.let { return it }
                    val waited=SystemClock.elapsedRealtime()-began
                    check(waited<4000 || (gatewayOutageSince!=0L && waited<60_000)) { attempt.exceptionOrNull()?.message ?: tr("Robot state stale", "État robot périmé") }
                    delay(200)
                }
            }
            // Docking already requested by the gateway while the pilot was away (OrphanDock, tests of 10-14):
            // write nothing (blades OFF or STOP would interrupt the official return), watch until the charging contact.
            val before=settledState()
            val alreadyDocking=NativeDocking.inProgress(before.robotState,before.chargeState)
            if (!alreadyDocking) bladesOff()
            val live=if (alreadyDocking) before else settledState()
            val position=Point(requireNotNull(live.x),requireNotNull(live.y))
            if (alreadyDocking) event("native_return_already",tr("Mammotion docking already in progress (requested by the gateway): monitoring only", "Accostage Mammotion déjà en cours (demandé par la passerelle) : surveillance seule"),
                mapOf("robotState" to live.robotState,"chargeState" to live.chargeState,"x" to position.x,"y" to position.y))
            else check(position.distance(engine.baseApproach)<=RobotBle.NATIVE_HANDOFF_APPROACH_M) {
                tr("Point in front of the base not reached (%.0f cm): Mammotion docking not triggered", "Point devant la base non atteint (%.0f cm) : accostage Mammotion non déclenché").format(position.distance(engine.baseApproach)*100) }
            routeStatus(RoutePhase.DOCKING,tr("In front of the base · docking through the official Mammotion return", "Devant la base · accostage par le retour officiel Mammotion"))
            if (!alreadyDocking) {
                // The official return is ignored in manual mode: state 20 left (blades OFF written by bladesOff).
                val modeDeadline=SystemClock.elapsedRealtime()+4000
                while (state.value.telemetry.robotState==20) {
                    check(SystemClock.elapsedRealtime()<modeDeadline) { tr("Manual mode not left: Mammotion docking not triggered", "Mode manuel non quitté : accostage Mammotion non déclenché") }
                    delay(200)
                }
                settledState()
                host.purgeQueuedCommands()
                host.send(commands.stop(),"ROUTE_DOCK_ZERO")
                host.send(commands.returnToDock(),"ROUTE_NATIVE_DOCK",nativeReturn=true)
            }
            val requestedAt=SystemClock.elapsedRealtime()
            update { it.copy(nativeReturnStatus=tr("Docking through the Mammotion return requested", "Accostage par le retour Mammotion demandé")) }
            if (!alreadyDocking) event("native_return_request",tr("Docking through the official Mammotion return from the approach point", "Accostage par le retour officiel Mammotion depuis le point d'approche"),
                mapOf("robotState" to live.robotState,"battery" to live.battery,"x" to position.x,"y" to position.y,
                    "approachDistanceM" to position.distance(engine.baseApproach)))
            // Already in progress: NativeDocking requires a later report, so the request is dated just before.
            val watch=NativeDocking(if (alreadyDocking) requestedAt-1 else requestedAt)
            while (true) {
                check(state.value.ready && !closing && log.healthy) { tr("Link lost during Mammotion docking: check the robot", "Liaison perdue pendant l'accostage Mammotion : vérifier le robot") }
                val now=SystemClock.elapsedRealtime()
                val current=state.value.telemetry
                current.x?.let { x -> current.y?.let { y -> Point(x,y) } }?.let { here ->
                    update { it.copy(robotRoute=it.robotRoute.copy(trail=MissionTrail.append(it.robotRoute.trail,here))) }
                }
                when (val step=watch.observe(now,devicePrevious,current.robotState,current.chargeState)) {
                    NativeDocking.Step.Docked -> break
                    NativeDocking.Step.Returning -> update { it.copy(nativeReturnStatus=tr("Mammotion docking in progress", "Accostage Mammotion en cours")) }
                    NativeDocking.Step.Waiting -> Unit
                    is NativeDocking.Step.Failed -> {
                        event("native_return_failed",step.reason,mapOf("robotState" to current.robotState,"chargeState" to current.chargeState))
                        error(step.reason)
                    }
                }
                delay(300)
            }
            // Charging contact reported while the robot is still returning (state 14 for 1 s on 10-15):
            // recharging requires a station state (11, 15, 39). Wait for it to leave the return rather than
            // interrupting the mowing ("Robot state incompatible with the station" while it was charging).
            val settleDeadline=SystemClock.elapsedRealtime()+NativeDocking.SETTLE_TIMEOUT_MS
            while (!NativeDocking.settled(state.value.telemetry.robotState)) {
                check(state.value.ready && !closing && log.healthy) { tr("Link lost during Mammotion docking: check the robot", "Liaison perdue pendant l'accostage Mammotion : vérifier le robot") }
                check(SystemClock.elapsedRealtime()<settleDeadline) {
                    tr("Robot still in Mammotion return (state ${state.value.telemetry.robotState}) despite the charging contact", "Robot toujours en retour Mammotion (état ${state.value.telemetry.robotState}) malgré le contact de charge") }
                delay(300)
            }
            val docked=state.value.telemetry
            update { it.copy(nativeReturnStatus=tr("Mammotion docking finished: charging contact confirmed", "Accostage Mammotion terminé : contact de charge confirmé")) }
            event("native_return_docked",tr("Charging contact after Mammotion docking", "Contact de charge après l'accostage Mammotion"),mapOf("waitMs" to SystemClock.elapsedRealtime()-requestedAt,
                "x" to docked.x,"y" to docked.y,"baseDistanceM" to docked.x?.let { x -> docked.y?.let { y -> Point(x,y).distance(engine.base) } }))
        }
        suspend fun dock() {
            // Docking (official Mammotion return): gateway link held as during charging. On 10-16 at 16:28:54,
            // 2 s of Wi-Fi silence during docking closed the link and abandoned the mowing ([DockResume]).
            val link=gatt as? GatewayLink
            if (link!=null) link.holdUntil=Long.MAX_VALUE
            try { dockHeld() }
            finally { if (link!=null) link.holdUntil=if (gatewayOutageSince!=0L) Clock.now()+RobotBle.ABORT_HOLD_MS else 0L }
        }
        /** High threshold reached: slow straight forward drive to the link point, without pivoting. */
        suspend fun leaveDockStraight() {
            routeStatus(RoutePhase.RESUMING,tr("Leaving the base in a straight line, slowly · blades OFF", "Sortie de base en ligne droite, lentement · lames OFF"))
            val began=SystemClock.elapsedRealtime()
            val exit=host.dockExit.straightDockExit(engine.base, engine.baseApproach, engine.routes, heightMm, recharge.low,
                obstacles = { dockFitted(state.value.obstacles) }, live = { freshState(charging=true) },
                onPosition = { position -> update { it.copy(robotRoute=it.robotRoute.copy(trail=MissionTrail.append(it.robotRoute.trail,position))) } },
                onGateway = { routeStatus(RoutePhase.RESUMING,tr("Leaving the base in a straight line, slowly · blades OFF · run by the gateway", "Sortie de base en ligne droite, lentement · lames OFF · exécutée par la passerelle")) },
                stopLabel = "ROUTE_DOCK_EXIT")
            if (exit.viaGateway) event("route_dock_exit",tr("Slow straight exit to the link point (gateway)", "Sortie rectiligne lente jusqu'au point de liaison (passerelle)"),mapOf("durationMs" to SystemClock.elapsedRealtime()-began))
            else event("route_dock_exit",tr("Slow straight exit to the link point", "Sortie rectiligne lente jusqu'au point de liaison"),mapOf("durationMs" to SystemClock.elapsedRealtime()-began,
                "headingJumpDeg" to exit.headingJump))
        }
        /** Recharge incident according to [ChargeWatch]: logged, tolerated while it does not last, then the mission stops. */
        fun chargeIncident(step: ChargeWatch.Step, phase: String) {
            when (step) {
                is ChargeWatch.Step.Started -> {
                    event("route_charge_incident",tr("$phase: incident tolerated for at most ${ChargeWatch.GRACE_MS/1000} s", "$phase : incident toléré pendant ${ChargeWatch.GRACE_MS/1000} s au plus"),mapOf("reason" to step.reason))
                    routeStatus(RoutePhase.CHARGING,tr("$phase · ${step.reason} · retrying, the robot stays on the base", "$phase · ${step.reason} · nouvel essai, le robot reste sur la base"))
                }
                is ChargeWatch.Step.Cleared -> {
                    event("route_charge_incident_cleared",tr("$phase: incident over", "$phase : incident terminé"),mapOf("reason" to step.reason,"durationMs" to step.ms))
                    if (phase=="Recharge") routeStatus(RoutePhase.CHARGING,state.value.robotRoute.chargeTarget?.let { t ->
                        tr("Actual recharge · departure at $t % (short remainder) instead of ${recharge.high} %", "Recharge réelle · départ à $t % (reste court) au lieu de ${recharge.high} %") } ?: tr("Actual recharge · resume at ${recharge.high} %", "Recharge réelle · reprise à ${recharge.high} %"))
                }
                is ChargeWatch.Step.Failed -> error(tr("${step.reason} for ${step.ms/1000} s: resume refused", "${step.reason} depuis ${step.ms/1000} s : reprise refusée"))
                else -> Unit
            }
        }
        /**
         * Battery points needed to finish from edge [from] ([FinishPolicy]): [extraMeters] (base exit, link) then
         * the remaining edges, final return included, at the estimate's speed and with the most cautious duration
         * factor. Null without a mowing measurement (survey, debug mowing): the robot returns and recharges as before.
         */
        fun restNeed(from: Int, extraMeters: Double): Double? {
            if (intent!=RouteIntent.FOLLOW) return null
            val record=state.value.mowingRecord ?: return null
            val (autonomy,factor)=MowingCalibration.finishModel(record)
            val meters=extraMeters+routeEdges.drop(from).sumOf { it.meters }
            return runCatching { FinishPolicy.neededPercent(FinishPolicy.restSeconds(meters,record.speedMps,factor),autonomy) }.getOrNull()
        }
        /**
         * [restNeed]: battery points needed to finish from the base ([FinishPolicy], margin included); the robot
         * leaves before the high threshold as soon as the battery covers that remainder above the low threshold.
         */
        suspend fun chargeAndDepart(waitForCharge: Boolean = true, restNeed: Double? = null) {
            val target=restNeed?.let { FinishPolicy.chargeTarget(it,recharge.low.toDouble(),recharge.high.toDouble()) }?.let { ceil(it).toInt() }
            update { it.copy(robotRoute=it.robotRoute.copy(chargeTarget=target?.takeIf { t -> t < recharge.high && waitForCharge })) }
            val chargeStatus=if (target!=null && target<recharge.high) tr("Actual recharge · departure at $target % (short remainder) instead of ${recharge.high} %", "Recharge réelle · départ à $target % (reste court) au lieu de ${recharge.high} %")
                else tr("Actual recharge · resume at ${recharge.high} %", "Recharge réelle · reprise à ${recharge.high} %")
            if (waitForCharge) routeStatus(RoutePhase.CHARGING,chargeStatus)
            val began=SystemClock.elapsedRealtime()
            val afterStop=devicePrevious
            val link=gatt as? GatewayLink
            // Recharge through the gateway (30 to 60 min): a Wi-Fi outage is recovered without stopping the mission,
            // the robot stays still on the base ([ChargeWatch]).
            if (waitForCharge && link!=null) link.holdUntil=Long.MAX_VALUE
            try {
                val watch=ChargeWatch()
                while (waitForCharge) {
                    // End of the working hours during the recharge: the robot stays on the base, the mission stops.
                    if (workWindowEnding) throw WorkWindowEnded()
                    val now=SystemClock.elapsedRealtime()
                    check(now-began<6*60*60*1000L) { tr("Recharge timed out", "Recharge expirée") }
                    val attempt=runCatching { freshState(requirePosition=false,charging=true) }
                    val live=attempt.getOrNull()
                    val issue=when {
                        gatewayOutageSince!=0L -> tr("Gateway Wi-Fi down", "Wi-Fi de la passerelle coupé")
                        live==null -> attempt.exceptionOrNull()?.message ?: tr("Robot state unavailable", "État du robot indisponible")
                        live.chargeState !in setOf(1,2) -> tr("Charging contact lost", "Contact de charge perdu")
                        else -> null
                    }
                    chargeIncident(watch.observe(now,issue),"Recharge")
                    if(issue==null && live!=null && devicePrevious>afterStop &&
                        recharge.resumeAllowed(requireNotNull(live.battery),live.chargeState,SystemClock.elapsedRealtime()-devicePrevious)) break
                    // Short remainder: leave as soon as the battery covers it above the low threshold ([FinishPolicy]).
                    val level=live?.battery
                    if(issue==null && level!=null && devicePrevious>afterStop && restNeed!=null &&
                        FinishPolicy.earlyDeparture(level.toDouble(),restNeed,recharge.low.toDouble())) {
                        event("route_early_departure",tr("Battery sufficient to finish the mowing: leaving before the high threshold", "Batterie suffisante pour finir la tonte : départ avant le seuil haut"),
                            mapOf("battery" to level,"neededPercent" to restNeed,"returnPercent" to recharge.low,"resumePercent" to recharge.high))
                        break
                    }
                    delay(500)
                }
                // High threshold reached (or direct departure): fresh and nominal state required, a late report is waited for.
                val watch2=ChargeWatch()
                while (true) {
                    val now=SystemClock.elapsedRealtime()
                    val attempt=runCatching { freshState() }
                    val live=attempt.getOrNull()
                    val issue=if (gatewayOutageSince!=0L) tr("Gateway Wi-Fi down", "Wi-Fi de la passerelle coupé") else attempt.exceptionOrNull()?.let { it.message ?: tr("Robot state unavailable", "État du robot indisponible") }
                    chargeIncident(watch2.observe(now,issue),tr("Leaving the base", "Départ de la base"))
                    if (issue==null && live!=null) {
                        val position=Point(requireNotNull(live.x),requireNotNull(live.y))
                        check(position.distance(engine.base)<=0.25) { tr("Base position lost", "Position de base perdue") }
                        break
                    }
                    delay(500)
                }
            } finally {
                update { it.copy(robotRoute=it.robotRoute.copy(chargeTarget=null)) }
                // Outage in progress: held a little longer so that a stop order can arrive; otherwise released.
                if (waitForCharge && link!=null) link.holdUntil=if (gatewayOutageSince!=0L) Clock.now()+RobotBle.ABORT_HOLD_MS else 0L
            }
            if (workWindowEnding) throw WorkWindowEnded()
            // Leaving the base: the robot leaves the charging contact, a resume from the base alone no longer applies.
            rechargeCycle=false
            leaveDockStraight()
        }
        /**
         * Obstacle discovered during tracking: STOP and blades OFF confirmed, obstacle estimated at the contact
         * (or added), straight back-off along the trail after a contact, then the remaining route repaired by the
         * mission's navigation (borders, no-go, obstacles, base zone). Returns the resume edge.
         */
        /** Station route in progress ([followStation]): the mission route is not the one being followed. */
        var stationActive=false
        /**
         * Mission route rebuilt from [start] (heading [heading]) for the reordered progress [next] ([SmartResume]):
         * prepared edges of the remaining portions, blades-OFF links between them, return in front of the base, then
         * the same repair around the obstacles discovered during the mission. False if no safe route exists (the
         * caller keeps its usual behaviour).
         */
        suspend fun replanMission(next: MissionCheckpoint, start: Point, heading: Double): Boolean {
            if (routePlan == null) return false
            val obstaclesNow=dockFitted(discovered.toList())
            val zones=engine.map.shapes.filter { it.kind==ShapeKind.ZONE && it.id in engine.preview.zoneIds }.map { it.points }
            val escape=routePhysicalRoutes ?: physicalRoutes
            val built=withContext(Dispatchers.Default) { runCatching {
                val mission=RobotRoute.missionEdges(engine,start,heading,escape,MissionResume.of(next),returnLanes)
                mission to if (obstaclesNow.isEmpty()) null else ObstacleBypass.repair(engine.routes,physicalRoutes,mission.edges,null,0,start,heading,obstaclesNow,zones,false)
            } }.getOrElse { error ->
                event("route_replan_failed",tr("Reordered remainder impossible to follow: usual resume kept", "Reste réordonné impossible à suivre : reprise habituelle conservée"),mapOf("reason" to error.message))
                return false
            }
            val (mission,repair)=built
            routeEdges=repair?.edges ?: mission.edges; routeOrigins=repair?.origins
            repair?.let { missionRoutes=it.routes; routePhysicalRoutes=it.physical }
            routePlan=mission
            routeCheckpoint=next.copy(updatedAt=Clock.now()).also { persistCheckpoint(it,force=true) }
            update { it.copy(robotRoute=it.robotRoute.copy(count=routeEdges.size,planned=listOf(routeEdges.first().a)+routeEdges.map { e -> e.b })) }
            return true
        }
        /** Return from [start] to the approach point once nothing is left to mow ([next]): the stripes are finished on the first progress. */
        suspend fun homeRoute(next: MissionCheckpoint, start: Point, heading: Double): Boolean {
            val plan=routePlan ?: return false
            val routesNow=missionRoutes; val escape=routePhysicalRoutes ?: physicalRoutes
            val path=withContext(Dispatchers.Default) { runCatching { RobotRoute.returning(engine,routesNow,start,heading,returnLanes) { s,h,t,th -> RobotRoute.departure(routesNow,escape,s,h,t,th) } }.getOrNull() }
            if (path.isNullOrEmpty()) return false
            routeEdges=path; routeOrigins=path.map { plan.mowingTo }
            routeCheckpoint=next.copy(updatedAt=Clock.now()).also { persistCheckpoint(it,force=true) }
            update { it.copy(robotRoute=it.robotRoute.copy(count=routeEdges.size,planned=listOf(routeEdges.first().a)+routeEdges.map { e -> e.b })) }
            return true
        }
        var blockages=0
        /**
         * Blockage while mowing (wheels turning without moving, bypass impossible, repeated exits at the same place):
         * position memorised (difficulty and deferred portion), short straight back-off along the trail, then the
         * segment is deferred to the end of the mowing and the mowing goes on [SmartResume.CLEARANCE_M] further
         * ([SmartResume.deferBlocked]); blocked again during that retry, the segment is given up. Otherwise (station
         * route, no progress saved, too many blockages, no remainder far enough), the stop [reason] is kept.
         * Returns the resume edge.
         */
        suspend fun deferBlockage(reason: String, contact: Boolean = false, keep: (suspend (Point, Double) -> Int)? = null): Int {
            val checkpoint=routeCheckpoint
            if (stationActive || intent!=RouteIntent.FOLLOW || checkpoint==null || blockages>=SmartResume.MAX_BLOCKAGES) error(reason)
            routeStatus(RoutePhase.FOLLOWING,tr("$reason · stop, blades OFF", "$reason · arrêt, lames OFF"))
            bladesOff(contactLatch=contact)
            val live=freshState(contactLatch=contact)
            val position=Point(requireNotNull(live.x),requireNotNull(live.y))
            val heading=routeReckoning.heading(requireNotNull(live.heading))
            val deferral=SmartResume.deferBlocked(engine.edges,checkpoint,position) ?: error(reason)
            blockages++
            difficulties.recordDifficulty(DifficultyKind.BLOCKED,reason,position,null,heading)
            val physical=routePhysicalRoutes ?: missionRoutes
            val starts=DriftRecovery.starts(physical,position,heading,state.value.robotRoute.trail.takeLast(400)).filterNotNull()
            val backOff=starts.firstOrNull { it.distance(position)>=SmartResume.BACK_OFF_M } ?: starts.lastOrNull()
            if (contact && backOff==null) error(tr("$reason: back-off impossible", "$reason : recul impossible"))
            backOff?.let { target ->
                routeStatus(RoutePhase.FOLLOWING,tr("Blockage: straight back-off of %.0f cm · blades OFF", "Blocage : recul de %.0f cm en ligne droite · lames OFF").format(target.distance(position)*100))
                try { safetyTests.reverseStraight(position,target,heading,physical,heightMm,"ROUTE",recovering=!contact,afterContact=contact,degreesPerUnit=routeReckoning.degreesPerUnit) }
                catch (deviation: BackOffDeviation) { event("route_blockage_back_off",deviation.message ?: tr("Back-off deviation", "Écart au recul")) }
            }
            if (contact) safetyTests.awaitCollisionLatchCleared("ROUTE",heightMm)
            val after=freshState()
            val start=Point(requireNotNull(after.x),requireNotNull(after.y))
            val startHeading=routeReckoning.heading(requireNotNull(after.heading))
            // Nothing left to mow (last retried segment given up): straight back to the base.
            suspend fun follow(checkpoint: MissionCheckpoint) = if (SmartResume.mowingDone(engine.edges,checkpoint)) homeRoute(checkpoint,start,startHeading)
                else replanMission(checkpoint,start,startHeading)
            var next=deferral.checkpoint; var abandoned=deferral.abandonedM
            if (!follow(next)) {
                // Deferred segment impossible to route around the obstacles found there: given up.
                val (fallback,dropped)=SmartResume.abandonNear(engine.edges,next,position)
                if (dropped<=0.0 || !follow(fallback)) {
                    // Deferral asked by the detour budget ([ObstacleBypass.detourRefusal]): never worse than before, the repair is kept.
                    keep?.let { return it(start,startHeading) }
                    error(reason)
                }
                next=fallback; abandoned+=dropped
            }
            event("route_blockage_deferred",tr("Blockage: segment deferred to the end of the mowing", "Blocage : segment reporté en fin de tonte"),mapOf("reason" to reason,
                "x" to position.x,"y" to position.y,"heading" to heading,"contact" to contact,"count" to blockages,"deferredM" to deferral.deferredM,
                "abandonedM" to abandoned,"targetX" to deferral.target.x,"targetY" to deferral.target.y,"backOffM" to backOff?.distance(position),
                "portions" to 1+next.deferred.size))
            routeStatus(RoutePhase.FOLLOWING,if (abandoned>0.0) tr("Blocked again at the same place: %.1f m given up, mowing goes on", "Bloqué de nouveau au même endroit : %.1f m abandonnés, la tonte continue").format(abandoned)
                else tr("Blockage: %.1f m deferred to the end of the mowing, mowing goes on", "Blocage : %.1f m reportés en fin de tonte, la tonte continue").format(deferral.deferredM))
            return 0
        }
        /**
         * Wheels turning without moving (soft ground, root, hidden object without bumper code): the place is
         * memorised as an estimated obstacle in front of the robot and bypassed like after a contact (back-off, tight
         * detour, blockage on the map); a bypass impossible defers the segment ([deferBlockage]).
         */
        fun stallEncounter(blocked: RouteBlocked): ObstacleEncounter {
            val live=state.value.telemetry
            val x=live.x ?: throw blocked; val y=live.y ?: throw blocked; val h=live.heading ?: throw blocked
            return ObstacleEncounter(blocked.index,RouteContact(0,x,y,routeReckoning.heading(h),Clock.now()),emptyList(),
                blocked.message ?: tr("Presumed blockage", "Blocage présumé"),blockage=true)
        }
        /**
         * Contact at the limit of the task area ([fr.luba.poc.simulation.EdgeContactKind.LIMIT]): the robot drifted
         * onto the border, nothing to avoid. No estimated obstacle, no bypass (the 8-bypass budget is untouched), no
         * photo, no teach proposal: straight back-off of at least 30 cm along the trail (collision lock accepted),
         * wait for the lock to clear, then the blades-OFF link of a trajectory exit back onto the same route
         * ([DriftRecovery.plan]). Returns the resume edge, or null when no back-off is possible or the resume link
         * exceeds [fr.luba.poc.simulation.EdgeContact.MAX_LINK_M] (obstacle path then, not counted).
         */
        suspend fun limitContact(encounter: ObstacleEncounter, contact: RouteContact, edge: EdgeContactClass,
                                 position: Point, heading: Double): Int? {
            EdgeContact.refusal(routeEdgeLimits.size)?.let { error(tr("${encounter.message}: $it", "${encounter.message} : $it")) }
            val physical=routePhysicalRoutes ?: missionRoutes
            val trail=state.value.robotRoute.trail.takeLast(400)
            val pathNow=routeEdges; val originsNow=routeOrigins; val routesNow=missionRoutes
            val plan=try { withContext(Dispatchers.Default) {
                DriftRecovery.plan(routesNow,physical,pathNow,originsNow,encounter.index.coerceIn(pathNow.indices),position,heading,trail,afterContact=true) } }
                catch (error: IllegalArgumentException) {
                    event("route_edge_contact_fallback",tr("Border contact: no back-off along the trail, handled as an obstacle", "Contact en bordure : pas de recul le long de la trace, traité comme un obstacle"),
                        mapOf("reason" to error.message,"x" to position.x,"y" to position.y,"heading" to heading))
                    return null
                }
            // Resume link too long (it would go around an obstacle or far along the route): obstacle path instead.
            EdgeContact.longLink(plan.rejoinMeters)?.let { reason ->
                event("route_edge_contact_fallback",tr("Border contact: $reason", "Contact en bordure : $reason"),
                    mapOf("reason" to reason,"rejoinM" to plan.rejoinMeters,"x" to position.x,"y" to position.y,"heading" to heading))
                return null
            }
            routeEdgeLimits += edge.bumper
            val coded = contact.code != CollisionLatch.VALUE.toInt()
            val contactAt = state.value.obstaclePhoto.request?.contactAt ?: contact.atMs
            // Nothing to photograph or to teach: the pending photo and the proposal of this contact are dropped.
            update { it.copy(obstaclePhoto=it.obstaclePhoto.copy(request=null,status=tr("Border contact: no photo", "Contact en bordure : pas de photo")),
                teach=it.teach.proposal?.takeIf { p -> p.estimated.distance(edge.bumper) < 2.0 }
                    ?.let { _ -> it.teach.copy(proposal=null,status=tr("Border contact: no obstacle proposal", "Contact en bordure : pas de proposition d'obstacle")) } ?: it.teach) }
            val note=if (coded) tr("Border contact (${contact.code})", "Contact en bordure (${contact.code})") else tr("Border contact (collision lock)", "Contact en bordure (verrou de collision)")
            difficulties.recordDifficulty(DifficultyKind.EDGE,note,position,contact.code.takeIf { coded },heading,edge.bumper,contactAt)
            taskHistory.taskEvent(TaskEventType.CONTACT,note,mapOf("kind" to edge.kind.code,"cause" to edge.cause))
            event("route_edge_contact",tr("Border contact: back-off, then the route resumes", "Contact en bordure de zone : recul puis reprise du trajet"),mapOf(
                "kind" to edge.kind.code,"code" to contact.code.takeIf { coded },"selfCheck" to state.value.telemetry.selfCheck,"sensors" to state.value.telemetry.sensorStatus,
                "x" to position.x,"y" to position.y,"heading" to heading,"borderDistM" to edge.borderDistM,"centerBorderDistM" to edge.centerBorderDistM,
                "driftM" to edge.driftM,"approachDeg" to edge.approachDegrees,"cause" to edge.cause,"backOffM" to plan.backOff?.distance(position),
                "rejoinM" to plan.rejoinMeters,"skippedCuttingM" to plan.skippedCuttingMeters,"count" to routeEdgeLimits.size,"sameSpot" to edge.sameSpot))
            routeStatus(RoutePhase.FOLLOWING,tr("Border contact: back-off, then the route resumes", "Contact en bordure de zone : recul puis reprise du trajet"))
            plan.backOff?.let { target ->
                try {
                    safetyTests.reverseStraight(position,target,heading,physical,heightMm,"ROUTE",recovering=true,afterContact=true,degreesPerUnit=routeReckoning.degreesPerUnit)
                } catch (deviation: BackOffDeviation) {
                    // Robot yawed while reversing (slip): brought back like after a trajectory exit.
                    safetyTests.awaitCollisionLatchCleared("ROUTE",heightMm)
                    throw RouteDrift(encounter.index.coerceIn(pathNow.indices),deviation.message ?: tr("Back-off deviation", "Écart au recul"))
                }
            }
            routeStatus(RoutePhase.FOLLOWING,tr("Border contact: waiting for the robot's protection to clear · blades OFF", "Contact en bordure : attente de la levée de la protection du robot · lames OFF"))
            safetyTests.awaitCollisionLatchCleared("ROUTE",heightMm)
            routeEdges=plan.edges; routeOrigins=plan.origins
            update { it.copy(robotRoute=it.robotRoute.copy(count=plan.edges.size,planned=listOf(plan.edges.first().a)+plan.edges.map { e -> e.b })) }
            routeStatus(RoutePhase.FOLLOWING,tr("Resuming after the border contact · %.1f m link with blades OFF", "Reprise après le contact en bordure · raccord de %.1f m lames OFF").format(plan.rejoinMeters))
            return 0
        }
        suspend fun avoidObstacle(encounter: ObstacleEncounter): Int {
            val contact = encounter.contact
            routeContact = null
            routeStatus(RoutePhase.FOLLOWING,tr("${encounter.message} · stop, blades OFF", "${encounter.message} · arrêt, lames OFF"))
            // Collision lock published with the contact (10-15): without this tolerance, the stop confirmation
            // failed with "Protection robot active" before the back-off, which alone clears the lock.
            bladesOff(contactLatch=contact!=null)
            val live=freshState(contactLatch=contact!=null)
            val position=Point(requireNotNull(live.x),requireNotNull(live.y))
            val heading=contact?.heading ?: routeReckoning.heading(requireNotNull(live.heading))
            // Contact at the border ([EdgeContact]), from the stopped pose: limit (back-off and resume), object at the
            // edge (tighter estimate) or object. A lock without a code arrives here with the code 25.
            val edge = contact?.takeIf { !encounter.blockage }?.let { c ->
                val coded = c.code != CollisionLatch.VALUE.toInt()
                EdgeContact.classify(coded,if (coded) live.selfCheck else CollisionLatch.VALUE,live.sensorStatus,position,heading,
                    routeEdgeBorder,routeEdges,encounter.index,state.value.robotRoute.trail.takeLast(400),routeEdgeLimits.toList(),discovered.toList())
            }
            if (contact != null && edge?.kind == EdgeContactKind.LIMIT) limitContact(encounter,contact,edge,position,heading)?.let { return it }
            check(bypassCount < ObstacleBypass.MAX_BYPASSES) { tr("${encounter.message}: ${ObstacleBypass.MAX_BYPASSES} bypasses already done, stop maintained", "${encounter.message} : ${ObstacleBypass.MAX_BYPASSES} contournements déjà effectués, arrêt maintenu") }
            bypassCount++
            taskHistory.historyObstacle(encounter.message ?: "")
            var touched: LocalObstacle? = null
            if (contact != null) {
                // Stopped position (two stationary positions): more accurate than the contact report (1 Hz). Object at the
                // border: tighter planning margin ([ObstacleBypass.CONTACT_EDGE_MARGIN_M]).
                val obstacle=ObstacleBypass.contactObstacle("mission-${UUID.randomUUID()}",position,heading,Clock.now(),
                    marginM=edge?.let { EdgeContact.marginM(it.kind) } ?: ObstacleBypass.CONTACT_MARGIN_M)
                    .let { if (encounter.blockage) it.copy(label=tr("Blockage during the mission", "Blocage pendant la mission")) else it }
                touched = obstacle
                ObstacleBypass.contactFailure(discovered,obstacle,bypassCount-1)?.let { failure ->
                    // Object too large to bypass: avoided by the links, its segment deferred to the end of the mowing.
                    discovered += obstacle; plannedObstacles += obstacle.id
                    return deferBlockage(tr("${encounter.message}: $failure", "${encounter.message} : $failure"),contact=true)
                }
                discovered += obstacle; plannedObstacles += obstacle.id
                if (!encounter.blockage) update { it.copy(teach=it.teach.copy(proposal=TeachSafety.proposal(TeachContact(position.x,position.y,heading,contact.atMs),ObstacleKind.OTHER.defaultRadiusM))) }
            } else encounter.added.forEach { obstacle -> discovered += obstacle; plannedObstacles += obstacle.id }
            val zones=engine.map.shapes.filter { it.kind==ShapeKind.ZONE && it.id in engine.preview.zoneIds }.map { it.points }
            val trail=state.value.robotRoute.trail.takeLast(400)
            val pathNow=routeEdges; val originsNow=routeOrigins; val obstaclesNow=dockFitted(discovered.toList())
            val hardship=difficulties.difficultyCost()
            val repair=try { withContext(Dispatchers.Default) {
                ObstacleBypass.repair(engine.routes,physicalRoutes,pathNow,originsNow,encounter.index,position,heading,
                    obstaclesNow,zones,contact!=null,trail,hardship) } }
                catch (error: IllegalArgumentException) {
                    touched?.let { difficulties.recordDifficulty(if (encounter.blockage) DifficultyKind.BLOCKED else DifficultyKind.CONTACT, tr("${encounter.message}: bypass impossible", "${encounter.message} : contournement impossible"), position,
                        contact?.code, heading, Point(it.x,it.y), state.value.obstaclePhoto.request?.contactAt ?: contact?.atMs) }
                    // Bypass impossible: the segment is deferred to the end of the mowing rather than stopping it.
                    return deferBlockage(tr("${encounter.message}: ${error.message}", "${encounter.message} : ${error.message}"),contact=contact!=null)
                }
            // Detour budget ([ObstacleBypass.detourRefusal]): a repair costing far more transit than the cutting it skips,
            // on a cutting edge, defers the segment to the end of the mowing instead; a transit contact keeps its repair
            // (deferring cannot shorten the way to its destination), and so does a station route or a mission without saved progress.
            val refusal=if (pathNow[encounter.index.coerceIn(pathNow.indices)].cutting) ObstacleBypass.detourRefusal(repair) else null
            if (refusal != null) {
                val checkpoint=routeCheckpoint
                val deferrable=!stationActive && intent==RouteIntent.FOLLOW && checkpoint!=null && blockages<SmartResume.MAX_BLOCKAGES &&
                    SmartResume.deferBlocked(engine.edges,checkpoint,position)!=null
                event("route_detour_refused",refusal,mapOf("bypassM" to repair.bypassMeters,"skippedCuttingM" to repair.skippedCuttingMeters,
                    "budgetM" to ObstacleBypass.detourBudgetM(repair.skippedCuttingMeters),"links" to repair.bypasses,"deferred" to deferrable,
                    "edgeKind" to edge?.kind?.code,"x" to position.x,"y" to position.y))
                if (deferrable) {
                    touched?.let { difficulties.recordDifficulty(if (encounter.blockage) DifficultyKind.BLOCKED else DifficultyKind.CONTACT, tr("${encounter.message}: $refusal", "${encounter.message} : $refusal"), position,
                        contact?.code, heading, Point(it.x,it.y), state.value.obstaclePhoto.request?.contactAt ?: contact?.atMs) }
                    return deferBlockage(tr("${encounter.message}: $refusal", "${encounter.message} : $refusal"),contact=contact!=null) { start, startHeading ->
                        // Deferral impossible after the back-off: the repair is computed again from here and kept.
                        val again=withContext(Dispatchers.Default) {
                            ObstacleBypass.repair(engine.routes,physicalRoutes,pathNow,originsNow,encounter.index,start,startHeading,obstaclesNow,zones,false,
                                state.value.robotRoute.trail.takeLast(400),hardship) }
                        missionRoutes=again.routes; routePhysicalRoutes=again.physical
                        routeEdges=again.edges; routeOrigins=again.origins
                        update { it.copy(robotRoute=it.robotRoute.copy(count=again.edges.size,planned=listOf(again.edges.first().a)+again.edges.map { e -> e.b })) }
                        event("route_detour_kept",tr("Deferral impossible: the long bypass is kept", "Report impossible : le long contournement est conservé"),mapOf("bypassM" to again.bypassMeters))
                        0
                    }
                }
            }
            // Obstacle encountered: on the map (Encountered obstacles layer), linked to its photo by the contact time.
            touched?.let { difficulties.recordDifficulty(if (encounter.blockage) DifficultyKind.BLOCKED else DifficultyKind.CONTACT, encounter.message ?: tr("Bumper contact", "Contact pare-chocs"), position, contact?.code, heading,
                Point(it.x,it.y), state.value.obstaclePhoto.request?.contactAt ?: contact?.atMs,
                repair.detours.firstOrNull { d -> d.center.distance(Point(it.x,it.y)) < 1e-6 }) }
            missionRoutes=repair.routes; routePhysicalRoutes=repair.physical
            event("route_obstacle_bypass",tr("Remaining route repaired around the obstacle", "Trajet restant réparé autour de l'obstacle"),mapOf("reason" to encounter.message,
                "x" to position.x,"y" to position.y,"heading" to heading,"obstacles" to obstaclesNow.map { mapOf("x" to it.x,"y" to it.y,"radiusM" to it.radiusM,"marginM" to it.marginM) },
                "backOffM" to repair.backOff?.distance(position),"bypasses" to repair.bypasses,"bypassM" to repair.bypassMeters,
                "skippedCuttingM" to repair.skippedCuttingMeters,"edges" to repair.edges.size,"count" to bypassCount,
                "edgeKind" to edge?.kind?.code,"borderDistM" to edge?.borderDistM,"driftM" to edge?.driftM,"edgeCause" to edge?.cause,
                "detours" to repair.detours.map { d -> mapOf("x" to d.center.x,"y" to d.center.y,"side" to d.side,"radiusM" to d.radiusM,
                    "meters" to d.meters,"borderClearanceM" to d.borderClearanceM,"bladesOn" to d.bladesOn,"inner" to d.inner) }))
            repair.backOff?.let { target ->
                routeStatus(RoutePhase.FOLLOWING,tr("Straight back-off of %.0f cm · blades OFF", "Recul de %.0f cm en ligne droite · lames OFF").format(target.distance(position)*100))
                try {
                    safetyTests.reverseStraight(position,target,heading,requireNotNull(repair.physical),heightMm,"ROUTE",afterContact=contact!=null,
                        degreesPerUnit=routeReckoning.degreesPerUnit)
                } catch (deviation: BackOffDeviation) {
                    // Robot yawed while reversing (slip): the repaired route is kept and the robot is brought back
                    // like after a trajectory exit (back-off along its trail into the safe surface, then link).
                    routeEdges=repair.edges; routeOrigins=repair.origins
                    update { it.copy(robotRoute=it.robotRoute.copy(count=repair.edges.size,planned=listOf(repair.edges.first().a)+repair.edges.map { e -> e.b })) }
                    if (contact != null) safetyTests.awaitCollisionLatchCleared("ROUTE",heightMm)
                    throw RouteDrift(0,deviation.message ?: tr("Back-off deviation", "Écart au recul"))
                }
                if (contact != null && !encounter.blockage) {
                    routeStatus(RoutePhase.FOLLOWING,tr("Obstacle photo · robot still, blades OFF", "Photo de l'obstacle · robot immobile, lames OFF"))
                    obstaclePhotos.holdForObstaclePhoto("ROUTE",heightMm)
                }
            }
            // Collision lock cleared by the robot once it has moved away from the object, before resuming the route.
            if (contact != null) {
                routeStatus(RoutePhase.FOLLOWING,tr("Contact: waiting for the robot's protection to clear · blades OFF", "Contact : attente de la levée de la protection du robot · lames OFF"))
                safetyTests.awaitCollisionLatchCleared("ROUTE",heightMm)
            }
            routeEdges=repair.edges; routeOrigins=repair.origins
            update { it.copy(robotRoute=it.robotRoute.copy(count=repair.edges.size,planned=listOf(repair.edges.first().a)+repair.edges.map { e -> e.b })) }
            routeStatus(RoutePhase.FOLLOWING,tr("Obstacle bypassed${ObstacleBypass.sideLabel(repair.detours.firstOrNull())} · %d link(s), %.1f m of cutting left around it", "Obstacle contourné${ObstacleBypass.sideLabel(repair.detours.firstOrNull())} · %d raccord(s), %.1f m de coupe laissés autour").format(repair.bypasses,repair.skippedCuttingMeters))
            return 0
        }
        var driftRecoveries=0
        var lastDrift: Point?=null
        var driftStreak=0
        /**
         * Leaving the trajectory (route deviation, footprint outside the safe corridor): STOP and blades OFF confirmed,
         * straight back-off along the trail until the chassis is back within the safe surface, then a blades-OFF link
         * to the route ([DriftRecovery]). Returns the resume edge.
         */
        suspend fun recoverDrift(drift: RouteDrift, protection: Boolean = false): Int {
            routeStatus(RoutePhase.FOLLOWING,tr("${drift.message} · stop, blades OFF", "${drift.message} · arrêt, lames OFF"))
            bladesOff()
            val live=freshState()
            val position=Point(requireNotNull(live.x),requireNotNull(live.y))
            val heading=routeReckoning.heading(requireNotNull(live.heading))
            // Pause on protection: no trajectory exit at the same spot, no skipped cutting.
            if (!protection) {
                driftStreak=if (lastDrift?.let { it.distance(position) < DriftRecovery.SAME_SPOT_M } == true) driftStreak+1 else 0
                lastDrift=position
                DriftRecovery.refusal(driftRecoveries,driftStreak)?.let { refusal ->
                    // Same tricky passage again and again: deferred to the end of the mowing ([SmartResume]).
                    if (driftStreak>=DriftRecovery.MAX_SAME_SPOT && !stationActive && routeCheckpoint!=null) {
                        driftStreak=0; lastDrift=null
                        return deferBlockage(tr("${drift.message}: $refusal", "${drift.message} : $refusal"))
                    }
                    error(tr("${drift.message}: $refusal", "${drift.message} : $refusal"))
                }
                driftRecoveries++
            }
            val physical=routePhysicalRoutes ?: missionRoutes
            val trail=state.value.robotRoute.trail.takeLast(400)
            val pathNow=routeEdges; val originsNow=routeOrigins; val routesNow=missionRoutes
            val plan=try { withContext(Dispatchers.Default) {
                DriftRecovery.plan(routesNow,physical,pathNow,originsNow,drift.index.coerceIn(pathNow.indices),position,heading,trail,
                    skipM=if (protection) 0.0 else driftStreak*DriftRecovery.SAME_SPOT_M) } }
                catch (error: IllegalArgumentException) { throw IllegalStateException(tr("${drift.message}: ${error.message}", "${drift.message} : ${error.message}")) }
            event("route_drift_recovery",tr("Trajectory exit recovered: back-off then link to the route", "Sortie de trajectoire rattrapée : recul puis raccord au trajet"),mapOf("reason" to drift.message,
                "edge" to drift.index,"x" to position.x,"y" to position.y,"heading" to heading,"count" to driftRecoveries,"streak" to driftStreak,
                "backOffM" to plan.backOff?.distance(position),"rejoinM" to plan.rejoinMeters,"skippedCuttingM" to plan.skippedCuttingMeters,
                "edges" to plan.edges.size))
            plan.backOff?.let { target ->
                routeStatus(RoutePhase.FOLLOWING,tr("Back-off of %.0f cm towards the safe surface · blades OFF", "Recul de %.0f cm vers la surface sûre · lames OFF").format(target.distance(position)*100))
                safetyTests.reverseStraight(position,target,heading,physical,heightMm,"ROUTE",recovering=true,degreesPerUnit=routeReckoning.degreesPerUnit)
            }
            routeEdges=plan.edges; routeOrigins=plan.origins
            update { it.copy(robotRoute=it.robotRoute.copy(count=plan.edges.size,planned=listOf(plan.edges.first().a)+plan.edges.map { e -> e.b })) }
            routeStatus(RoutePhase.FOLLOWING,tr("Resuming after trajectory exit · %.1f m link with blades OFF", "Reprise après sortie de trajectoire · raccord de %.1f m lames OFF").format(plan.rejoinMeters))
            return 0
        }
        var protectionPauses=0
        /**
         * Transient protection cleared by the robot ([ProtectionPause]): STOP and blades OFF, wait for the protection
         * to clear (stable for [ProtectionPause.STABLE_MS], at most [ProtectionPause.WAIT_MS]), then the same resume
         * as after a trajectory exit, without skipped cutting. Returns the resume edge.
         */
        suspend fun pauseForProtection(pause: RouteProtection): Int {
            // Collision lock (25) without a contact code: the robot is against an object and will not clear its
            // protection by itself (10-15, 17:36: 30 s wait then mowing stopped). Same handling as a contact: back-off,
            // wait for the lock to clear, estimated obstacle and bypass. At the limit of the task area (beyond the zones
            // or less than 35 cm inside, sensors clear), a border contact instead: back-off and resume ([EdgeContact]).
            val latched=state.value.telemetry
            // 25 without a contact code at a no-go zone (2026-10-25, 15:33: rear of the chassis inside it after a
            // pivot): the robot left its task area, nothing was hit. No estimated obstacle, no back-off into the
            // no-go: stop, the joystick takes it out ([edgeLatchIgnored]), then resume. Elsewhere, unchanged.
            if (OffMapLatch.ignored(latched.selfCheck,host.guardSensors(latched.sensorStatus),
                    PointPlanner.atExclusion(state.value.robotMap,latched.x?.let { x -> latched.y?.let { y -> Point(x,y) } }))) {
                event("route_exclusion_latch",tr("Self-check 25 at a no-go zone without a contact code: stop without an obstacle", "Self-check 25 contre une zone interdite sans code de contact : arrêt sans obstacle"),
                    mapOf("edge" to pause.index,"x" to latched.x,"y" to latched.y,"heading" to latched.heading))
                error(tr("Robot outside its task area (self-check 25) against a no-go zone: take it out with the joystick, blades stopped, then resume",
                    "Robot hors de sa zone de travail (self-check 25) contre une zone interdite : le sortir au joystick, lames arrêtées, puis reprendre"))
            }
            if (CollisionLatch.latched(latched.selfCheck) && latched.x!=null && latched.y!=null && latched.heading!=null) {
                val heading=routeReckoning.heading(requireNotNull(latched.heading))
                // At the limit of the task area, [avoidObstacle] classifies it as a border contact ([EdgeContact]):
                // back-off and resume, logged as route_edge_contact.
                if (!OffMapLatch.ignored(latched.selfCheck,latched.sensorStatus,
                        PointPlanner.beyondMapped(state.value.robotMap,Point(requireNotNull(latched.x),requireNotNull(latched.y)))))
                    event("route_latch_contact",tr("Collision lock without a contact code: handled as a contact", "Verrou de collision sans code de contact : traité comme un contact"),
                        mapOf("edge" to pause.index,"x" to latched.x,"y" to latched.y,"heading" to heading))
                return try { avoidObstacle(ObstacleEncounter(pause.index,RouteContact(CollisionLatch.VALUE.toInt(),requireNotNull(latched.x),
                    requireNotNull(latched.y),heading,Clock.now()),emptyList(),tr("Contact (collision lock)", "Contact (verrou de collision)"))) }
                    catch (drift: RouteDrift) { recoverDrift(drift) }
            }
            ProtectionPause.refusal(protectionPauses)?.let { error(tr("${pause.message}: $it", "${pause.message} : $it")) }
            protectionPauses++
            host.purgeQueuedCommands(); host.send(commands.stop(),"ROUTE_PROTECTION_STOP")
            routeStatus(RoutePhase.FOLLOWING,tr("${pause.message} · paused, blades OFF, resuming as soon as the robot clears its protection", "${pause.message} · pause, lames OFF, reprise dès que le robot lève sa protection"))
            val began=SystemClock.elapsedRealtime()
            event("route_protection_pause",tr("Robot protection during mowing: waiting for it to clear", "Protection du robot pendant la tonte : attente de sa levée"),mapOf("reason" to pause.message,
                "edge" to pause.index,"count" to protectionPauses,"waitMs" to ProtectionPause.waitMs(state.value.telemetry.selfCheck),
                "selfCheckName" to SelfCheck.name(state.value.telemetry.selfCheck)))
            val wait=ProtectionPause.Wait(began)
            try {
                while (true) {
                    check(state.value.ready && !closing && log.healthy) { tr("BLE interrupted during the pause", "BLE interrompu pendant la pause") }
                    val now=SystemClock.elapsedRealtime()
                    check(now-devicePrevious in 0..2000) { tr("Robot state stale during the pause", "État robot périmé pendant la pause") }
                    val live=state.value.telemetry
                    if (wait.update(now,live.robotState,live.selfCheck)) break
                    delay(200)
                }
            } catch (error: IllegalStateException) { throw IllegalStateException(tr("${pause.message}: ${error.message}", "${pause.message} : ${error.message}")) }
            event("route_protection_cleared",tr("Protection cleared by the robot: mowing resumes", "Protection levée par le robot : reprise de la tonte"),mapOf("reason" to pause.message,
                "waitedMs" to SystemClock.elapsedRealtime()-began,"count" to protectionPauses))
            return recoverDrift(RouteDrift(pause.index,pause.message ?: tr("Protection cleared", "Protection levée")),protection=true)
        }
        var lostLegRecoveries=0
        /**
         * Tracking lost by the gateway ([RouteLegLost]): start order lost in a Wi-Fi outage (return at the low
         * threshold on 10-15, 16:15:10) or gateway restarted; robot stopped by it. Same resume as after a trajectory
         * exit, from the real pose and without skipped cutting. Returns the resume edge.
         */
        suspend fun recoverLostLeg(lost: RouteLegLost): Int {
            check(lostLegRecoveries < MAX_LOST_LEG_RECOVERIES) { tr("${lost.message}: $MAX_LOST_LEG_RECOVERIES resumes already done, link too unstable", "${lost.message} : $MAX_LOST_LEG_RECOVERIES reprises déjà faites, liaison trop instable") }
            lostLegRecoveries++
            event("route_lost_leg_recovery",tr("Tracking lost by the gateway: resuming from the robot's real position", "Suivi perdu par la passerelle : reprise depuis la position réelle du robot"),
                mapOf("edge" to lost.index,"count" to lostLegRecoveries))
            // Robot reports resumed after reconnection: waited for (10 s at most) before the confirmed stop and the link.
            val deadline=SystemClock.elapsedRealtime()+10_000
            while (gatewayOutageSince!=0L || runCatching { freshState() }.isFailure) {
                if (SystemClock.elapsedRealtime()>=deadline) break
                delay(300)
            }
            return recoverDrift(RouteDrift(lost.index,lost.message ?: tr("Tracking lost by the gateway", "Suivi perdu par la passerelle")),protection=true)
        }
        /**
         * Station route (return to base at the low threshold, resume towards the interrupted point after the high
         * threshold) followed with the same recoveries as mowing: bypass after a contact, recovery from a trajectory exit,
         * pause on protection, tracking lost by the gateway. Before, the slightest incident stopped the mission far from
         * the base. The mowing route, its progress and its drawing are restored afterwards.
         */
        suspend fun followStation(path: List<SimEdge>, phase: RoutePhase, status: String, lastArrivalM: Double? = null) {
            val missionEdges=routeEdges; val missionOrigins=routeOrigins
            val shownPlan=state.value.robotRoute.let { it.count to it.planned }
            routeEdges=path; routeOrigins=null
            val stationBefore=stationActive
            stationActive=true
            try {
                var at=0
                while (at<routeEdges.size) {
                    routeStatus(phase,status)
                    at=try { followAll(routeEdges,at,lastArrivalM=lastArrivalM) }
                        catch (encounter: ObstacleEncounter) { try { avoidObstacle(encounter) } catch (drift: RouteDrift) { recoverDrift(drift) } }
                        catch (drift: RouteDrift) { recoverDrift(drift) }
                        catch (pause: RouteProtection) { pauseForProtection(pause) }
                        catch (lost: RouteLegLost) { recoverLostLeg(lost) }
                        catch (blocked: RouteBlocked) { try { avoidObstacle(stallEncounter(blocked)) } catch (drift: RouteDrift) { recoverDrift(drift) } }
                }
            } finally {
                stationActive=stationBefore
                routeEdges=missionEdges; routeOrigins=missionOrigins
                update { it.copy(robotRoute=it.robotRoute.copy(count=shownPlan.first,planned=shownPlan.second)) }
            }
        }
        suspend fun run() {
            // Contacts at the limit of the task area ([EdgeContact]): drawn border, count and bumper points of the mission.
            routeEdgeBorder = EdgeContact.border(engine.map)
            routeEdgeLimits.clear()
            routeContact = null
            if (initiallyDocked) {
                if (host.autonomousLegsAvailable()) legs.prefetch(host.viewModelScope, listOf(RobotRoute.heightSegment(routeEdges, 0, heightMm, cutting)), listOf(missionRoutes.physicalSurface, (routePhysicalRoutes ?: missionRoutes).physicalSurface))
                bladesOff()
                // Leave the base for any route started while charging (mowing, survey, painted-zone or border debug mowing):
                // immediately if the battery is enough, after recharging otherwise.
                val battery=state.value.telemetry.battery
                val waitForCharge=intent==RouteIntent.FOLLOW && !recharge.departureAllowed(battery)
                event("route_dock_departure",if (waitForCharge) tr("Battery insufficient: recharge before leaving the base", "Batterie insuffisante : recharge avant la sortie de base") else tr("Leaving the base without waiting for the recharge", "Sortie de base sans attendre la recharge"),
                    mapOf("battery" to battery,"returnPercent" to recharge.low,"resumePercent" to recharge.high,"intent" to intent.name))
                rechargeCycle=intent==RouteIntent.FOLLOW
                chargeAndDepart(waitForCharge=waitForCharge,restNeed=if (waitForCharge) restNeed(0,engine.base.distance(engine.baseApproach)) else null)
                rechargeCycle=false
            }
            if(intent==RouteIntent.LEAVE_BASE) {
                routeStatus(RoutePhase.COMPLETE,tr("Base exit confirmed · blades OFF", "Sortie de base confirmée · lames OFF"))
                host.requestStop(false,tr("Base exit finished", "Sortie de base terminée"),intentional=true)
                return
            }
            var index=0
            while(index<routeEdges.size) {
                routeStatus(RoutePhase.FOLLOWING,tr("BLE tracking · ${index+1}/${routeEdges.size}", "Suivi BLE · ${index+1}/${routeEdges.size}"))
                val reached=try { followAll(routeEdges,index,shown=true,watchBattery=intent==RouteIntent.FOLLOW) }
                    catch (encounter: ObstacleEncounter) { index=try { avoidObstacle(encounter) } catch (drift: RouteDrift) { recoverDrift(drift) }; continue }
                    catch (drift: RouteDrift) { index=recoverDrift(drift); continue }
                    catch (pause: RouteProtection) { index=pauseForProtection(pause); continue }
                    catch (lost: RouteLegLost) { index=recoverLostLeg(lost); continue }
                    catch (blocked: RouteBlocked) { index=try { avoidObstacle(stallEncounter(blocked)) } catch (drift: RouteDrift) { recoverDrift(drift) }; continue }
                if (reached>=routeEdges.size) {
                    index=reached
                } else {
                    index=reached
                    if (workWindowEnding) {
                        // End of the working hours: return with blades OFF along the same route as for recharging, docking, end of
                        // mission (progress kept: the mowing resumes from the interrupted point).
                        bladesOff()
                        val live=freshState()
                        val saved=Point(requireNotNull(live.x),requireNotNull(live.y))
                        val windowStatus=alerts.stopReturnStatus()
                        routeStatus(RoutePhase.RETURNING,windowStatus)
                        val paths=withContext(Dispatchers.Default) { RobotRoute.rechargePaths(engine,saved,requireNotNull(live.heading),missionRoutes,returnLanes) }
                        if (paths.returning.isNotEmpty()) followStation(paths.returning,RoutePhase.RETURNING,windowStatus)
                        dock()
                        throw WorkWindowEnded()
                    }
                    // Low threshold: finish without recharging if the remainder allows it ([FinishPolicy]); cutting resumes at once at the same point.
                    val battery=state.value.telemetry.battery
                    // Return reserve: stopped above the low threshold because the return cost plus the reserve was reached.
                    val reserveReturn=lowWatch==recharge.low && battery!=null && battery>recharge.low
                    val trigger=if (reserveReturn) tr("Return reserve ($reserveWatch %)", "Réserve de retour ($reserveWatch %)") else tr("Low threshold ${recharge.low} %", "Seuil bas ${recharge.low} %")
                    if (reserveReturn) event("route_return_reserve",tr("Battery near the return cost plus the reserve: return decision above the low threshold", "Batterie proche du coût du retour plus la réserve : décision de retour au-dessus du seuil bas"),
                        mapOf("battery" to battery,"watchPercent" to reserveWatch,"reservePercent" to returnReserve,"lowPercent" to recharge.low,"detour" to returnDetour,"edge" to index))
                    val need=if (lowWatch==recharge.low) restNeed(index,0.0) else null
                    if (battery!=null && need!=null && FinishPolicy.finishWithoutRecharge(battery.toDouble(),need)) {
                        lowWatch=FinishPolicy.ARRIVAL_FLOOR_PERCENT
                        update { it.copy(robotRoute=it.robotRoute.copy(lowWatch=lowWatch)) }
                        event("route_finish_below_low",tr("Low threshold: battery sufficient to finish the mowing then return", "Seuil bas : batterie suffisante pour finir la tonte puis rentrer"),
                            mapOf("battery" to battery,"neededPercent" to need,"edge" to index,"floorPercent" to lowWatch))
                        routeStatus(RoutePhase.FOLLOWING,tr("Low threshold ${recharge.low} %%: battery sufficient to finish (≈ %.0f %% needed) · emergency return at %d %%", "Seuil bas ${recharge.low} %% : batterie suffisante pour finir (≈ %.0f %% nécessaires) · retour d'urgence à %d %%").format(need,lowWatch))
                        continue
                    }
                    if (need!=null) event("route_finish_refused",tr("Low threshold: battery insufficient to finish, returning to recharge", "Seuil bas : batterie insuffisante pour finir, retour en recharge"),
                        mapOf("battery" to battery,"neededPercent" to need,"edge" to index))
                    lowWatch=recharge.low
                    update { it.copy(robotRoute=it.robotRoute.copy(lowWatch=null)) }
                    bladesOff()
                    val live=freshState()
                    val saved=Point(requireNotNull(live.x),requireNotNull(live.y))
                    val savedHeading=requireNotNull(live.heading)
                    routeStatus(RoutePhase.RETURNING,tr("$trigger · BLE return · blades OFF", "$trigger · retour BLE · lames OFF"))
                    rechargeCycle=true
                    update { it.copy(robotRoute=it.robotRoute.copy(rechargeCycles=it.robotRoute.rechargeCycles+1)) }
                    routeCheckpoint?.let { checkpoint -> routeCheckpoint = checkpoint.copy(rechargeCycles = checkpoint.rechargeCycles + 1).also { persistCheckpoint(it, force = true) } }
                    val paths=withContext(Dispatchers.Default) { RobotRoute.rechargePaths(engine,saved,savedHeading,missionRoutes,returnLanes) }
                    if (paths.returning.isNotEmpty()) followStation(paths.returning,RoutePhase.RETURNING,tr("$trigger · BLE return · blades OFF", "$trigger · retour BLE · lames OFF"))
                    dock()
                    // Remainder from the base: exit, link to the interrupted point, remaining mowing and final return.
                    chargeAndDepart(restNeed=restNeed(index,paths.resuming.sumOf { it.meters }+engine.base.distance(engine.baseApproach)))
                    rechargeCycle=false
                    // Docking, charging and leaving the base happen outside tracking: the pose estimate from before the return
                    // (last position, setpoints, fused heading) no longer holds. When kept, it took the base → exit point route
                    // for a straight line under the old setpoint and "confirmed" a heading 150° off (end-to-end bench):
                    // pivot the wrong way and stop inside the base zone. Calibration kept.
                    routeReckoning = newRouteReckoning()
                    event("route_reckoning_reset",tr("Pose estimate reset after the recharge (calibration kept)", "Estimation de pose réinitialisée après la recharge (étalonnage conservé)"))
                    // Shortest resume from the real pose in front of the base, normal route speed;
                    // link prepared before the return if the recomputation fails.
                    val out=freshState()
                    val outPose=Point(requireNotNull(out.x),requireNotNull(out.y))
                    val outHeading=requireNotNull(out.heading)
                    // Smart resume: rather than going back to the interrupted point, the remainder may start with the block
                    // that minimises the total transit from here back to the base, the interrupted cell finished later ([SmartResume]).
                    val routesNow=missionRoutes
                    val smart=routeCheckpoint?.let { checkpoint -> withContext(Dispatchers.Default) {
                        runCatching { SmartResume.afterRecharge(engine.edges,checkpoint,outPose,outHeading,engine.baseApproach,(engine.dockEntryHeading+180.0)%360.0,
                            SmartResume.linkMeters(routesNow)) }.getOrNull() } }
                    if (smart!=null && replanMission(smart.checkpoint,outPose,outHeading)) {
                        event("route_smart_resume",tr("Resume after recharge reordered: shortest transit", "Reprise après recharge réordonnée : transit le plus court"),
                            mapOf("defaultTransitM" to smart.defaultTransitM,"transitM" to smart.transitM,"entryX" to smart.entry.x,"entryY" to smart.entry.y,
                                "interruptedX" to saved.x,"interruptedY" to saved.y,"portions" to 1+smart.checkpoint.deferred.size))
                        routeStatus(RoutePhase.RESUMING,tr("Smart resume after recharge: %.0f m of transit saved, interrupted point mowed later", "Reprise intelligente après recharge : %.0f m de transit économisés, point interrompu tondu plus tard").format(smart.savedM))
                        index=0
                        continue
                    }
                    val resuming=withContext(Dispatchers.Default) {
                        runCatching { RobotRoute.departure(missionRoutes,routePhysicalRoutes,outPose,outHeading,saved,savedHeading) }.getOrNull()
                    } ?: paths.resuming
                    event("route_resume_path",tr("Resume link towards the interrupted point", "Raccord de reprise vers le point interrompu"),mapOf("meters" to resuming.sumOf { it.meters },
                        "recomputed" to (resuming !== paths.resuming)))
                    if (resuming.isNotEmpty()) followStation(resuming,RoutePhase.RESUMING,tr("Shortest resume towards the interrupted point", "Reprise au plus court vers le point interrompu"),lastArrivalM=0.05)
                    val resumed=freshState()
                    index=RobotRoute.resumeIndex(routeEdges,index,Point(requireNotNull(resumed.x),requireNotNull(resumed.y)),engine.baseApproach)
                    routeStatus(RoutePhase.RESUMING,tr("Resuming at the interrupted point · segment ${minOf(index+1,routeEdges.size)}", "Reprise au point interrompu · segment ${minOf(index+1,routeEdges.size)}"))
                    // Keep the index and the remaining part of the segment, never redo the mowing from the start.
                }
            }
            finishCheckpoint(tr("Mowing finished: all edges covered", "Tonte terminée : toutes les arêtes parcourues"))
            routeStatus(RoutePhase.RETURNING,tr("End of the route · final docking", "Fin du trajet · accostage final"))
            dock()
            taskHistory.historyEnd(tr("Mowing finished · charging contact confirmed", "Tonte terminée · contact de charge confirmé"), completed = true)
            routeStatus(RoutePhase.COMPLETE,if(intent==RouteIntent.RETURN_TO_BASE) tr("Return to base · charging contact confirmed", "Retour à la base · contact de charge confirmé") else tr("Mowing finished · charging contact confirmed", "Tonte terminée · contact de charge confirmé"))
            host.requestStop(false,tr("Route finished — on the base, charging contact confirmed", "Trajet terminé — sur la base, contact de charge confirmé"), intentional=true)
        }
    }
    /** Route of the current or interrupted mowing: resumed as is after a gateway update. */
    internal var checkpointEngine: MowingSimulation? = null
    /** Announcement of a gateway update ([fr.luba.poc.gateway.GatewayProtocol.OTA_BEGIN_EVENT]); 0 otherwise. */
    private var gatewayUpdateAt = 0L
    internal var gatewayUpdateResume: Job? = null

    /** Gateway update announced and not finished yet (resume pending or restart window). */
    internal fun gatewayUpdating() = gatewayUpdateAt != 0L && (gatewayUpdateResume?.isActive == true ||
        SystemClock.elapsedRealtime() - gatewayUpdateAt <= GatewayOta.RESUME_WINDOW_MS && !state.value.ready)

    internal fun gatewayUpdateRestart(link: RobotLink, code: Int) = link is GatewayLink &&
        GatewayLink.gatewayDisconnected(code) && gatewayUpdateAt != 0L &&
        SystemClock.elapsedRealtime() - gatewayUpdateAt <= GatewayOta.RESTART_WINDOW_MS

    /**
     * The ESP32 gateway is about to restart for an update: it stops the robot (blades then speed) and closes the session
     * within 3 s. Mowing in progress and the "Updates during mowing" setting on (otherwise the gateway would have refused it):
     * the mowing resumes by itself from its saved progress as soon as the gateway and the robot are back.
     */
    internal fun gatewayUpdateAnnounced(text: String) {
        gatewayUpdateAt = SystemClock.elapsedRealtime()
        val s = state.value
        val engine = checkpointEngine
        val mowing = s.robotRoute.running && routeCheckpoint != null && engine != null
        event("gateway_update_announced", text, mapOf("mowing" to mowing, "resumeAllowed" to GatewayOta.duringMowing))
        if (mowing && GatewayOta.duringMowing) engine?.let { planGatewayUpdateResume(it) }
        else host.status(tr("Gateway update: restarting, automatic reconnection when it is back", "Mise à jour de la passerelle : redémarrage, reconnexion automatique à son retour"))
    }

    /**
     * Mowing resume after the gateway restarts: the only automatic resume after a link loss, because the stop was
     * announced then written by the gateway. Bounded by [fr.luba.poc.gateway.GatewayOta.RESUME_WINDOW_MS] and 3 attempts;
     * a refusal (map, position, battery…) leaves the resume to the user and raises an alert.
     */
    private fun planGatewayUpdateResume(engine: MowingSimulation) {
        gatewayUpdateResume?.cancel()
        gatewayUpdateResume = host.viewModelScope.launch {
            val began = SystemClock.elapsedRealtime()
            val deadline = began + GatewayOta.RESUME_WINDOW_MS
            var attempts = 0
            var lastRefusal = tr("gateway or robot not back in time", "passerelle ou robot non revenus à temps")
            var lastRetry = 0L
            delay(5000)
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(2000)
                val now = SystemClock.elapsedRealtime()
                val s = state.value
                if (userDisconnected) { event("gateway_update_resume_cancelled", tr("Resume canceled: disconnection requested", "Reprise annulée : déconnexion demandée")); return@launch }
                if (s.robotRoute.running) { event("gateway_update_resume_skipped", tr("Mowing already restarted", "Tonte déjà relancée")); return@launch }
                val checkpoint = s.missionCheckpoint
                if (checkpoint == null) { event("gateway_update_resume_skipped", tr("No mowing to resume", "Aucune tonte à reprendre")); return@launch }
                // Gateway back but link not reopened yet (reconnections exhausted during the write): restart the search.
                if (gatt == null && !closing && now - lastRetry >= 5000) { lastRetry = now; host.retryGatewayLink() }
                val settled = s.ready && gatt != null && !closing && readyAt > gatewayUpdateAt && now - readyAt >= 8000 &&
                    lastTelemetry != 0L && positionPrevious != 0L && now - positionPrevious <= 2000 && !s.mapLoading
                if (!settled) continue
                attempts++
                if (startRoute(engine, checkpoint.cutting, checkpoint.heightMm, RouteIntent.FOLLOW, checkpoint)) {
                    event("gateway_update_resumed", tr("Mowing resumed after the gateway update", "Tonte reprise après la mise à jour de la passerelle"),
                        mapOf("attempt" to attempts, "afterMs" to now - gatewayUpdateAt, "edge" to checkpoint.edge, "progress" to checkpoint.progress))
                    host.status(tr("Mowing resumed after the gateway update", "Tonte reprise après la mise à jour de la passerelle"))
                    return@launch
                }
                lastRefusal = state.value.robotRoute.status.removePrefix(tr("Start refused: ", "Départ refusé : "))
                if (attempts >= 3) break
                delay(8000)
            }
            event("gateway_update_resume_failed", tr("Automatic resume impossible after the gateway update", "Reprise automatique impossible après la mise à jour de la passerelle"),
                mapOf("attempts" to attempts, "reason" to lastRefusal))
            alerts.alert(AlertKind.RESUME_FAILED, Severity.WARNING, tr("Gateway update: automatic resume impossible ($lastRefusal). Resume manually.", "Mise à jour de la passerelle : reprise automatique impossible ($lastRefusal). Reprendre à la main."))
        }
    }

    /** Recharge cycle of a mowing in progress (return at the low threshold, docking, charging, until leaving the base) ([DockResume]). */
    @Volatile internal var rechargeCycle = false
    /** Test benches: simulated link treated as the gateway for [DockResume] (resume reserved for the gateway). */
    internal var dockResumeAnyLink = false
    private var dockResumeJob: Job? = null

    /**
     * Mowing resume after losing the gateway during the recharge ([DockResume]): link back and stable, robot charging on
     * the base (Mammotion docking finished), then resume from the saved progress, like "Resume" (charge up to the high
     * threshold if needed, leave the base, link). Bounded by [DockResume.WINDOW_MS] and [DockResume.MAX_ATTEMPTS]
     * attempts; a refusal leaves the resume to the user and raises an alert.
     */
    internal fun planDockResume(engine: MowingSimulation, cause: String) {
        if (dockResumeJob?.isActive == true) return
        val since = SystemClock.elapsedRealtime()
        event("dock_resume_planned", tr("Gateway lost during the recharge: mowing resumes by itself once the robot is charging and the link is back", "Passerelle perdue pendant la recharge : tonte reprise seule, robot en charge et liaison revenue"),
            mapOf("cause" to cause))
        val waiting = tr("Automatic resume as soon as the robot is charging on the base and the gateway is back", "Reprise automatique dès que le robot est en charge sur la base et la passerelle revenue")
        update { it.copy(autoResumeStatus = waiting) }
        dockResumeJob = host.viewModelScope.launch {
            fun done(text: String) { update { it.copy(autoResumeStatus = "") }; host.status(text) }
            val deadline = since + DockResume.WINDOW_MS
            var attempts = 0
            var lastRefusal = tr("gateway or robot not back in time", "passerelle ou robot non revenus à temps")
            var lastRetry = 0L
            delay(3000)
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(2000)
                val now = SystemClock.elapsedRealtime()
                val s = state.value
                if (userDisconnected) { event("dock_resume_cancelled", tr("Resume canceled: disconnection requested", "Reprise annulée : déconnexion demandée")); done(tr("Automatic resume canceled", "Reprise automatique annulée")); return@launch }
                if (s.robotRoute.running) { event("dock_resume_skipped", tr("Mowing already restarted", "Tonte déjà relancée")); update { it.copy(autoResumeStatus = "") }; return@launch }
                val checkpoint = s.missionCheckpoint
                if (checkpoint == null) { event("dock_resume_skipped", tr("No mowing to resume (abandoned)", "Aucune tonte à reprendre (abandonnée)")); done(tr("Automatic resume canceled", "Reprise automatique annulée")); return@launch }
                // Automatic reconnections exhausted: restart the gateway search.
                if (gatt == null && !closing && now - lastRetry >= 5000) { lastRetry = now; host.retryGatewayLink() }
                val settled = s.ready && gatt != null && !closing && now - readyAt >= DockResume.SETTLE_MS && lastTelemetry != 0L &&
                    now - devicePrevious <= DockResume.FRESH_REPORT_MS && !s.mapLoading && checkpoint.robotId == s.selected?.address
                if (!settled) continue
                val t = s.telemetry
                if (!DockResume.docked(t.robotState, t.chargeState)) {
                    lastRefusal = tr("robot not charging on the base (state ${t.robotState ?: "?"}, charge ${t.chargeState ?: "?"})", "robot pas en charge sur la base (état ${t.robotState ?: "?"}, charge ${t.chargeState ?: "?"})")
                    update { it.copy(autoResumeStatus = tr("$waiting · robot not charging yet", "$waiting · robot pas encore en charge")) }
                    continue
                }
                attempts++
                if (startRoute(engine, checkpoint.cutting, checkpoint.heightMm, RouteIntent.FOLLOW, checkpoint)) {
                    event("dock_resume_resumed", tr("Mowing resumed on its own after the gateway was lost during recharging", "Tonte reprise seule après la perte de la passerelle pendant la recharge"),
                        mapOf("attempt" to attempts, "afterMs" to now - since, "edge" to checkpoint.edge, "progress" to checkpoint.progress))
                    done(tr("Mowing resumed after the gateway was lost during recharging", "Tonte reprise après la perte de la passerelle pendant la recharge"))
                    return@launch
                }
                lastRefusal = state.value.robotRoute.status.removePrefix(tr("Start refused: ", "Départ refusé : "))
                if (attempts >= DockResume.MAX_ATTEMPTS) break
                delay(8000)
            }
            event("dock_resume_failed", tr("Automatic resume impossible after the gateway was lost during recharging", "Reprise automatique impossible après la perte de la passerelle pendant la recharge"),
                mapOf("attempts" to attempts, "reason" to lastRefusal))
            done(tr("Automatic resume impossible: $lastRefusal", "Reprise automatique impossible : $lastRefusal"))
            alerts.alert(AlertKind.RESUME_FAILED, Severity.WARNING, tr("Gateway lost during recharging: automatic resume impossible ($lastRefusal). Resume manually.", "Passerelle perdue pendant la recharge : reprise automatique impossible ($lastRefusal). Reprendre à la main."))
        }
    }
}
