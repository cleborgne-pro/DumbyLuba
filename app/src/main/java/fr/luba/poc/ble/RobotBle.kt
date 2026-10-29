@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import androidx.core.content.edit
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.net.Uri
import fr.luba.poc.map.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.luba.poc.alerts.*
import fr.luba.poc.EventLog
import fr.luba.poc.i18n.tr
import fr.luba.poc.diagnostics.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.mowing.*
import fr.luba.poc.simulation.*
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import fr.luba.poc.difficulty.DifficultyClassifier
import fr.luba.poc.difficulty.DifficultyKind

 data class SeenRobot(val name: String, val address: String, val rssi: Int?)

 /** BLE link state; the label is shown as is on the screens. */
 enum class ConnectionState(private val labelEn: String, private val labelFr: String) {
    Disconnected("Disconnected", "Déconnecté"),
    Connecting("Connecting…", "Connexion…"),
    Discovering("GATT discovery…", "Découverte GATT…"),
    WaitingTelemetry("BLE connected — waiting for telemetry", "BLE connecté — attente télémétrie"),
    Receiving("BLE connected — telemetry received", "BLE connecté — télémétrie reçue"),
    Closing("Stopping / closing…", "Arrêt / fermeture…"),
    Stopped("BLE connected — acquisition stopped", "BLE connecté — acquisition arrêtée");

    val label: String get() = tr(labelEn, labelFr)
 }

 data class NavTestUi(
    val id: String = "", val running: Boolean = false, val tonte: Boolean = false,
    val segmentIndex: Int = 0, val segmentCount: Int = 0,
    val status: String = tr("No route on the map", "Aucun parcours sur carte"), val outcome: String = "Non démarrée",
    val results: List<TestResult> = emptyList(), val trail: List<NavSample> = emptyList(),
    val observedMeters: Double = 0.0, val stopDelayMs: Long? = null,
    val planned: List<Point> = emptyList(), val plannedBands: List<List<Point>> = emptyList(),
    val plannedBorders: List<List<Point>> = emptyList(),
    /** Move to a point: sections of [planned] beyond the mapped zones (dotted on the map). */
    val plannedOffMap: List<List<Point>> = emptyList(),
    /** Straight exit from the base to the link point, driven before [planned] (robot on its base); empty otherwise. */
    val plannedExit: List<Point> = emptyList(),
    /** Preview recomputed periodically, separate from the status so that it never hides a stop reason. */
    val preview: String = "",
    /** Reason for an unrequested stop, shown until acknowledged. */
    val interruption: String? = null,
    /** Cutting test: blades commanded on for the current stretch, planned meters with blades on. */
    val bladesOn: Boolean = false, val plannedCutMeters: Double = 0.0,
    /** Obstacle bypass: targeted obstacle (Service → Obstacle tests → Bypass). */
    val courseObstacleId: String? = null,
)

/** "Command cutoff" test (Service → Motor watchdog). */
 data class SilenceTestUi(
    val running: Boolean = false, val phase: String = "", val status: String = tr("No test", "Aucun essai"),
    val linearRaw: Int = 30, val driftM: Double? = null,
)

/** Height adjustment test sequence (Service → Cutting height). */
 data class HeightSequenceUi(
    val running: Boolean = false, val targets: List<Int> = emptyList(), val index: Int = 0,
    val status: String = "", val startMm: Int? = null,
)

 data class TeachUi(
    val id: String = "", val running: Boolean = false, val contactValidated: Boolean = false,
    val status: String = tr("No obstacle learning", "Aucun apprentissage d'obstacle"), val outcome: String = "Non démarré",
    val results: List<TestResult> = emptyList(), val proposal: TeachProposal? = null,
)

/** Mammotion map [raw] published with its local corrections: [RobotUi.robotMap] becomes the corrected map. */
fun RobotUi.withMammotionMap(raw: RobotMap, corrections: List<MapCorrection> = mapCorrections): RobotUi {
    val corrected = MapCorrections.apply(raw, corrections)
    return copy(mammotionMap = raw, robotMap = corrected.map, mapCorrections = corrections, correctedMap = corrected)
}

 data class RobotUi(
    val signalSurvey: SignalSurvey = SignalSurvey(), val surveySaveError: Boolean = false,
    val robotRoute: RobotRouteUi = RobotRouteUi(),
    val manualActive: Boolean = false, val manualStatus: String = tr("Joystick ready — blades OFF", "Joystick prêt — lames OFF"),
    /** Zone, passage or no-go being recorded by driving the robot ([DrivenRecording]); null outside a recording. */
    val driven: DrivenTrace? = null,
    val lastKnown: LastKnownPosition? = null, val positionSaveError: Boolean = false,
    /** Map shown and used everywhere: the Mammotion map corrected by the local overlay ([mapCorrections]). */
    val robotMap: RobotMap = RobotMap(), val mapLoading: Boolean = false,
    /**
     * Why the last robot map reading failed or stayed incomplete, shown where the reading is offered until the next
     * reading, a new connection or the link closing; null otherwise.
     */
    val mapReadFailure: String? = null,
    /** Mammotion map as read (Bluetooth or cache), without corrections: the only one saved in the cache. */
    val mammotionMap: RobotMap = RobotMap(),
    /** Local map corrections (phone overlay), in order of application. */
    val mapCorrections: List<MapCorrection> = emptyList(),
    /** Effect of the corrections on the current map (status of each one, shapes affected). */
    val correctedMap: CorrectedMap = CorrectedMap(RobotMap()),
    val devices: List<SeenRobot> = emptyList(), val scanning: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected, val ready: Boolean = false,
    /** The open link goes through the Wi-Fi gateway (not through this phone's Bluetooth). */
    val viaGateway: Boolean = false,
    /** Gateway Wi-Fi lost during autonomous tracking: the robot carries on alone, the pilot restores the link. */
    val gatewayOutage: Boolean = false,
    /** Direct Bluetooth: a gateway was announced on the Wi-Fi during the last search (its address), to offer to use it. */
    val gatewaySeen: String? = null,
    val selected: SeenRobot? = null, val rssi: Int? = null,
    /** Internal model code of the selected robot remembered from a previous connection (H version or not). */
    val productType: String? = null,
    val telemetry: Telemetry = Telemetry(), val ageMs: Long? = null,
    val timeoutSeconds: Int = 10, val commandsSent: Long = 0,
    val interruptions: Int = 0, val status: String = tr("Ready — STOP always available", "Prêt — STOP toujours accessible"),
    val events: List<String> = emptyList(), val mtu: Int = 23,
    val campaign: CampaignUi = CampaignUi(), val localResults: List<TestResult> = emptyList(),
    val history: String = "", val diagnosticSummary: String = "",
    val trajectorySpeedPercent: Int = 30,
    /** Automatic resume planned after the gateway was lost during recharging ([DockResume]); empty otherwise. */
    val autoResumeStatus: String = "",
    val taskStopStatus: String = "", val taskStopPending: Boolean = false, val unlockStatus: String = "", val unlockPending: Boolean = false, val backOffStatus: String = "", val backOffPending: Boolean = false, val nativeReturnStatus: String = "", val nativeReturnActive: Boolean = false, val bladeHeightPending: Boolean = false, val bladeTestActive: Boolean = false, val bladeStatus: String = tr("No cutting test", "Aucun test de coupe"),
    val bladeResults: List<TestResult> = emptyList(),
    val heightSequence: HeightSequenceUi = HeightSequenceUi(),
    /** Last bumper event received from the robot (contact 1014 or fault 1207): code and wall-clock time. */
    val lastBumperCode: Int? = null, val lastBumperAt: Long? = null,
    /** Resting value of the bumper slot learnt on this connection (0: none), ignored by the guards ([fr.luba.poc.protocol.BumperRest]). */
    val bumperRest: Int = 0,
    /** Contact during manual control (difficulty id) whose sheet is open: keep it as an obstacle or dismiss it. */
    val manualContactId: String? = null,
    /** Last robot error received, decoded by [RobotErrorCatalog] (all codes, including informational ones). */
    val lastRobotError: RobotErrorReport? = null,
    val navTest: NavTestUi = NavTestUi(),
    val obstacles: List<LocalObstacle> = emptyList(),
    val teach: TeachUi = TeachUi(),
    /** Frontal border test (Service → Obstacle tests → Border). */
    val borderFrontal: BorderFrontalUi = BorderFrontalUi(),
    val mowing: MowUi = MowUi(),
    val sensorProbe: SensorProbeSnapshot = SensorProbeSnapshot(),
    /** Last local perception map pushed by the robot ([fr.luba.poc.diagnostics.PerceptionMapObserver]), outside any capture. */
    val perceptionMap: fr.luba.poc.diagnostics.PerceptionMapFrame? = null,
    /** Video state read from telemetry (fpv_info, network used, vi_switch acknowledgement). */
    val camera: fr.luba.poc.camera.CameraRobotState = fr.luba.poc.camera.CameraRobotState(),
    /** Obstacle photo after a bumper contact (pending request, setting, saved photos). */
    val obstaclePhoto: fr.luba.poc.camera.ObstaclePhotoUi = fr.luba.poc.camera.ObstaclePhotoUi(),
    /** Progress of the last unfinished local mowing (resume possible), for the selected robot. */
    val missionCheckpoint: MissionCheckpoint? = null, val checkpointSaveError: Boolean = false,
    /** Move to a point in progress or interrupted (resume possible), for the selected robot. */
    val pointResume: PointResume? = null,
    val silenceTest: SilenceTestUi = SilenceTestUi(),
    /** Last command cutoff test of the selected robot. */
    val motorWatchdog: MotorWatchdogResult? = null,
    /** History of real mowings (most recent first), mowing currently being measured and report to show. */
    val mowingHistory: List<fr.luba.poc.history.MowingRecord> = emptyList(),
    /** Directions last mowed per zone ("Change every session", [fr.luba.poc.planning.ZoneOrientationStore]). */
    val zoneOrientations: Map<Long, fr.luba.poc.planning.ZoneOrientation> = emptyMap(),
    val mowingRecord: fr.luba.poc.history.MowingRecord? = null,
    val mowingReport: fr.luba.poc.history.MowingRecord? = null,
    /** History task opened in detail (track replay), by identifier. */
    val taskDetail: String? = null,
    /** Difficulties encountered (contacts, blockages, wheel slip, lifts…), presumed obstacles and settings. */
    val difficulties: fr.luba.poc.difficulty.DifficultyUi = fr.luba.poc.difficulty.DifficultyUi(),
    /** Gateway phone fallen off the robot, detected by the gateway from its Bluetooth signal: where to pick it up. */
    val gatewayDrop: GatewayDrop? = null,
) {
    /** Cutting heights of the connected robot, from its model code (H versions: 55–100 mm, [fr.luba.poc.protocol.CutHeightRange]). */
    val cutHeights: fr.luba.poc.protocol.CutHeightRange get() =
        fr.luba.poc.protocol.CutHeightRange.forRobot(selected?.name ?: lastKnown?.robotName, telemetry.productType ?: productType, telemetry.bladeHeightMm)

    /** Obstacles taken into account by route preparation: saved ones, plus presumed obstacles when the option is on. */
    val planningObstacles: List<LocalObstacle> get() =
        if (difficulties.avoidPresumed && difficulties.presumed.isNotEmpty()) obstacles + difficulties.presumed else obstacles
}

/**
 * Gateway phone fallen off the robot ([fr.luba.poc.autonomy.GatewayDetachment]): [x], [y] where it most likely lies (map
 * frame, robot position at the last normal Bluetooth reading), [at] wall-clock time of the detection, [probable] when
 * concluded from a Bluetooth loss, [text] the gateway's reason.
 */
data class GatewayDrop(val x: Double, val y: Double, val at: Long, val probable: Boolean, val text: String)

/** Recoveries by recomputing the path, per move to a point. */
private const val MAX_NAV_RECOVERIES = 3
/** Minimum battery to start a sync with the Mammotion map (several outlines may be driven in a row). */
private const val SYNC_BATTERY_FLOOR = 40
/** Back-off after a contact: raw speed (≈ 10 cm/s, like leaving the station) and maximum duration. */
private const val BACK_OFF_LINEAR = 100
/** Maximum number of slide map cells sent to the gateway (≈ 20 bytes each, 16 KB frame). */
private const val SLIDE_CELLS_TO_GATEWAY = 300
private const val BACK_OFF_TIMEOUT_MS = 20_000L

/** Bumper contact during a route: pose at contact (fused tracking heading, cleared by the STOP). */
private data class RouteContact(val code: Int, val x: Double, val y: Double, val heading: Double, val atMs: Long)
/** Straight back-off that left its line sideways (wheel slip): after a contact during a mission, recovered like a trajectory exit. */
private class BackOffDeviation(message: String) : IllegalStateException(message)
/** Obstacle discovered while tracking a route: [index] = current edge. */
private class ObstacleEncounter(val index: Int, val contact: RouteContact?, val added: List<fr.luba.poc.obstacles.LocalObstacle>,
                                message: String,
                                /** Wheels turning without moving ([RouteBlocked]): handled like a contact without its code, recorded as a blockage. */
                                val blockage: Boolean = false) : Exception(message)
/**
 * Tracking handed to the gateway and lost by it (start order lost in a Wi-Fi outage, gateway restarted):
 * robot stopped by the gateway, [index] = last known edge. The route resumes from the actual pose.
 */
private class RouteLegLost(val index: Int, message: String) : IllegalStateException(message)
/** End of the working hours during a mowing: robot returned to (or stayed on) the base, mission stopped. */
private class WorkWindowEnded : IllegalStateException(tr("End of the working hours", "Fin de la plage horaire de travail"))
/** Maximum number of tracking runs lost by the gateway resumed during one mission (beyond that, the link is deemed too unstable). */
private const val MAX_LOST_LEG_RECOVERIES = 4
private const val MAX_RECONNECTS = 5

/** GATT serialized on the main thread; reconnections only in planned test cases. */
@SuppressLint("MissingPermission")
class RobotBle(app: Application) : AndroidViewModel(app) {
    private val handler = Handler(Looper.getMainLooper())
    private val positionStore = LastKnownPositionStore(app.filesDir)
    private val positionWrites = kotlinx.coroutines.channels.Channel<LastKnownPosition>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val positionPreferences = app.getSharedPreferences("last_position_robot", 0)
    /** Motion gains calibrated per robot (BLE address), reused at the start of the next mission. */
    private val calibrationPreferences = app.getSharedPreferences("motion_calibration", 0)
    private var calibrationSavedAt = 0L
    private fun savedCalibration(): DeadReckoning.Calibration? {
        val id = state.value.selected?.address ?: return null
        val meters = calibrationPreferences.getString("mpu_$id", null)?.toDoubleOrNull() ?: return null
        val degrees = calibrationPreferences.getString("dpu_$id", null)?.toDoubleOrNull() ?: return null
        if (!meters.isFinite() || !degrees.isFinite()) return null
        return DeadReckoning.Calibration(meters, degrees).also {
            event("motion_calibration", tr("Robot calibration reused from the previous mission", "Étalonnage du robot repris de la mission précédente"),
                mapOf("metersPerUnit" to it.metersPerUnit, "degreesPerUnit" to it.degreesPerUnit))
        }
    }
    /** Stores the calibration reached (at most every 30 s, and at the end of a route with [force]). */
    private fun saveCalibration(reckoning: DeadReckoning, force: Boolean = false) {
        val calibration = reckoning.calibration ?: return
        val id = state.value.selected?.address ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - calibrationSavedAt < 30_000) return
        calibrationSavedAt = now
        calibrationPreferences.edit {
            putString("mpu_$id", calibration.metersPerUnit.toString()); putString("dpu_$id", calibration.degreesPerUnit.toString())
        }
    }
    /** Stores the calibration returned by the gateway at the end of autonomous tracking. */
    private fun storeCalibration(calibration: DeadReckoning.Calibration) {
        val id = state.value.selected?.address ?: return
        calibrationSavedAt = SystemClock.elapsedRealtime()
        calibrationPreferences.edit {
            putString("mpu_$id", calibration.metersPerUnit.toString()); putString("dpu_$id", calibration.degreesPerUnit.toString())
        }
    }

    /** How routes are tracked right now, recorded in the task history to compare field runs. */
    private fun trackingLink(): fr.luba.poc.history.TrackingLink = when {
        autonomousLegsAvailable() -> fr.luba.poc.history.TrackingLink.GATEWAY
        gatt is fr.luba.poc.gateway.GatewayLink -> fr.luba.poc.history.TrackingLink.RELAY
        else -> fr.luba.poc.history.TrackingLink.BLUETOOTH
    }

    /** Routes are tracked by the gateway (Redmi) if it advertises it and the setting is not turned off. */
    private fun autonomousLegsAvailable(): Boolean =
        (gatt as? fr.luba.poc.gateway.GatewayLink)?.supportsLegs == true && fr.luba.poc.autonomy.AutonomySettings.enabled(getApplication()) &&
            fr.luba.poc.protocol.ModelSupport.motionValidated(state.value.selected?.name) &&
            // The gateway does not know the bumper resting value learnt here (DumbyLuba#1): its guards would stop at once,
            // the app tracks the route itself.
            bumperRest.value == 0

    /**
     * Tracking handed to the gateway ([fr.luba.poc.autonomy.LegRunner] on the Redmi): waits for its outcome while keeping
     * the map up to date (progress, trail, signal surveys), never writing to the robot. Returns the index reached like
     * `followAll`, or throws [ObstacleEncounter] / a safety stop error.
     */
    /** Result of [straightDockExit]: run by the gateway, or tracked from here with the heading jump observed. */
    private data class DockExitResult(val viaGateway: Boolean, val headingJump: Double?)

    /**
     * Slow straight exit from the base to the link point in front of it ([DockExit]), blades OFF, no pivot: shared by
     * routes (mowing, survey, leave the base) and moves (move to a point, map route test). Gateway on the robot: run by
     * it over Bluetooth ([fr.luba.poc.autonomy.LegRunner], dock exit leg), otherwise tracked from here at 200 ms with
     * the envelope ([routes]), [obstacles], blockage and 60 s checks, then the release of the charging contact.
     * [live]: fresh robot state (throws on a safety failure); [navProgress] for a move (navigation screen).
     */
    private suspend fun straightDockExit(base: Point, approach: Point, routes: TrajectoryPlanner, heightMm: Int, returnPercent: Int,
                                         obstacles: () -> List<LocalObstacle>, live: () -> fr.luba.poc.protocol.Telemetry,
                                         onPosition: (Point) -> Unit, onGateway: () -> Unit, stopLabel: String,
                                         navProgress: ((fr.luba.poc.autonomy.LegProgress) -> Unit)? = null): DockExitResult {
        val began=SystemClock.elapsedRealtime()
        // Gateway on the robot: the exit (commands every 200 ms) is run by it, over Bluetooth a few
        // centimetres away, rather than through Wi-Fi (jerks: -2804, stale positions, GATT delay).
        val link = gatt as? fr.luba.poc.gateway.GatewayLink
        if (link != null && link.supportsDockExit && autonomousLegsAvailable()) {
            onGateway()
            val exitPath = listOf(SimEdge(base, approach, false))
            val routeId = legs.ensureRoute(exitPath)
            val guardId = legs.ensureGuard(routes.physicalSurface)
            val spec = fr.luba.poc.autonomy.LegSpec(legs.nextLegId(), routeId, 0, false, false, false, true, null,
                state.value.cutHeights.clamp(heightMm), state.value.trajectorySpeedPercent.coerceIn(10, 100), returnPercent.coerceIn(10, 80), approach, guardId, -1,
                savedCalibration(), obstacles().map { o -> fr.luba.poc.autonomy.LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, true) },
                sampling = false, dockExit = true)
            // Exit lost by the gateway (Wi-Fi outage at the start) or refused (route forgotten): sent again, the exit
            // resumes from the real pose in the base corridor ([DockExit]).
            var attempt=0
            while (true) {
                attempt++
                try {
                    val leg = if (attempt==1) spec else spec.copy(legId=legs.nextLegId(), routeId=legs.ensureRoute(exitPath), guardId=legs.ensureGuard(routes.physicalSurface))
                    runRemoteLeg(link, leg, exitPath, obstacles = obstacles, planned = { state.value.obstacles.map { it.id }.toSet() }, originOf = { -1 }, navProgress = navProgress)
                    break
                } catch (retry: IllegalStateException) {
                    if (attempt>=3 || (retry !is RouteLegLost && retry !is fr.luba.poc.autonomy.RemoteLegs.RefusedLeg)) throw retry
                    event("route_dock_exit_retry",tr("Base exit sent again to the gateway: ${retry.message}", "Sortie de base renvoyée à la passerelle : ${retry.message}"),mapOf("attempt" to attempt))
                    delay(1000)
                }
            }
            return DockExitResult(true, null)
        }
        val exit=fr.luba.poc.simulation.DockExit(base, approach)
        val motionProgress=MotionProgressGuard()
        var overlapLogged=false
        while (true) {
            val t=live()
            val now=SystemClock.elapsedRealtime()
            val position=Point(requireNotNull(t.x),requireNotNull(t.y))
            onPosition(position)
            val reported=requireNotNull(t.heading).also { check(it.isFinite()) }
            val step=exit.step(position,reported)
            if (step.done) break
            check(now-began<60000) { tr("Station exit timed out: link point not reached", "Sortie de station expirée : point de liaison non atteint") }
            check(!motionProgress.blocked(position,positionPrevious,now,step.linear)) { tr("Station exit blocked for 3 s", "Sortie de station bloquée depuis 3 s") }
            val rad=Math.toRadians(step.heading)
            val distance=minOf(0.10,position.distance(approach))
            val next=RobotPose(Point(position.x+distance*kotlin.math.cos(rad),position.y+distance*kotlin.math.sin(rad)),step.heading)
            if (routes.motionCollision(RobotPose(position,step.heading),next)!=null) {
                // Chassis already overlapping the free surface on its station: the remaining straight exit must only
                // move it away and end inside the free surface (same rule as the gateway, [fr.luba.poc.autonomy.LegRunner]).
                val overlapping=!routes.footprintSafe(RobotPose(position,step.heading))
                check(overlapping && routes.leavesStraight(position,approach,step.heading)) { fr.luba.poc.simulation.DockExit.envelopeRefusal(position,base,overlapping) }
                if (!overlapLogged) {
                    overlapLogged=true
                    event("route_dock_exit_overlap",tr("Chassis overlapping the free surface on the base: straight exit allowed while it only moves away",
                        "Châssis débordant de la surface libre sur la base : sortie tout droit admise tant qu'elle ne fait que s'en éloigner"),
                        mapOf("x" to position.x,"y" to position.y,"heading" to step.heading,"baseDistanceM" to position.distance(base)))
                }
            }
            check(obstacles().none { obstacle -> Geometry.distance(Point(obstacle.x,obstacle.y),position,next.position) < obstacle.exclusionRadiusM+RobotEnvelope.LUBA2.turnClearanceM }) { tr("Obstacle on the station exit", "Obstacle sur la sortie de station") }
            send(commands.movement(step.linear,0),stopLabel)
            delay(200)
        }
        purgeQueuedCommands(); send(commands.stop(),"${stopLabel}_STOP")
        val deadline=SystemClock.elapsedRealtime()+5000
        while (live().chargeState!=0) {
            check(SystemClock.elapsedRealtime()<deadline) { tr("Station exit not confirmed", "Sortie de station non confirmée") }
            delay(200)
        }
        return DockExitResult(false, exit.headingJump)
    }

    private suspend fun runRemoteLeg(link: fr.luba.poc.gateway.GatewayLink, spec: fr.luba.poc.autonomy.LegSpec, path: List<SimEdge>,
                                     obstacles: () -> List<fr.luba.poc.obstacles.LocalObstacle>, planned: () -> Set<String>, originOf: (Int) -> Int,
                                     navProgress: ((fr.luba.poc.autonomy.LegProgress) -> Unit)? = null): Int {
        // Read-only mode: the gateway writes the leg itself, out of reach of the send() barrier, so it is refused here.
        fr.luba.poc.protocol.ModelSupport.refusal(state.value.selected?.name)?.let { error(it) }
        fun legObstacles() = obstacles().map { o -> fr.luba.poc.autonomy.LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, o.id in planned()) }
        // Move to a point ([navProgress]): progress and trail kept by the navigation screen, not by the mowing route.
        val shown = spec.shown && navProgress == null
        val manoeuvreStats = ManoeuvreStats(path)
        val callbacks = object : fr.luba.poc.autonomy.RemoteLegs.Callbacks {
            override fun progress(progress: fr.luba.poc.autonomy.LegProgress) {
                navProgress?.let { show -> show(progress.copy(status = progress.status + if (progress.orphan) tr(" · Wi-Fi lost, the robot carries on alone", " · Wi-Fi coupé, le robot poursuit seul") else "")); return }
                if (!shown) return
                update { it.copy(robotRoute = it.robotRoute.copy(index = progress.index,
                    status = progress.status + if (progress.orphan) tr(" · Wi-Fi lost, the robot carries on alone", " · Wi-Fi coupé, le robot poursuit seul") else "")) }
                val live = state.value.telemetry
                val x = live.x; val y = live.y
                // Published point (8 cm behind the centre): enough for the duration, speed and deviation of a manoeuvre.
                if (progress.index >= 1) manoeuvreStats.update(System.currentTimeMillis(), progress.index - 1,
                    if (x != null && y != null) Point(x, y) else null)?.let { logManoeuvre(it, "gateway") }
                if (x != null && y != null && progress.index >= 1) originOf(progress.index - 1).let { origin -> if (origin >= 0) recordMissionProgress(origin, Point(x, y)) }
            }
            override fun sample(sample: SignalSample) = recordSurveySample(sample)
            override fun gatewayEvent(kind: String, text: String, fields: Map<String, Any?>) {
                this@RobotBle.event("gateway_leg_$kind", text, fields)
                if (kind == fr.luba.poc.autonomy.GatewayDetachment.EVENT) handler.post { gatewayDetached(text, fields) }
                // Slide measured by the gateway during a pivot: same learning as with local tracking.
                if (kind == fr.luba.poc.autonomy.LegRunner.PIVOT_SLIDE_EVENT) {
                    fun num(key: String) = (fields[key] as? Number)?.toDouble()
                    val x = num("x"); val y = num("y"); val vx = num("vx"); val vy = num("vy"); val seconds = num("seconds")
                    if (x != null && y != null && vx != null && vy != null && seconds != null)
                        handler.post { learnSlide(SlideSample(x, y, vx, vy, seconds, (fields["atMs"] as? Number)?.toLong() ?: System.currentTimeMillis()), "gateway") }
                }
            }
        }
        // The gateway alone writes to the robot as soon as tracking starts: nothing more is written from here (hold released on any exit),
        // and writes already queued or in flight finish first. A pilot write arriving after LEG_START would interrupt the tracking.
        remoteLegActive = true
        var leg: fr.luba.poc.autonomy.RemoteLegs.Leg? = null
        var delivered = false
        var windowAbort = false
        val result = try {
            val drainUntil = SystemClock.elapsedRealtime() + 4000
            while ((queue.any { it is Operation.Write } || current is Operation.Write) && SystemClock.elapsedRealtime() < drainUntil) delay(50)
            check(queue.none { it is Operation.Write } && current !is Operation.Write) { tr("Writes to the robot still in progress: autonomous tracking not started", "Écritures encore en cours vers le robot : suivi autonome non lancé") }
            val started = legs.start(spec, callbacks)
            leg = started
            // Sending impossible (link closed): nothing is handed to the gateway, no hold is set.
            if (started.result.isCompleted) { legs.release(started); leg = null; started.result.await() }
            link.holdUntil = Long.MAX_VALUE
            if (spec.cutting && (spec.from until path.size).any { path[it].bladesOn }) bladeMayBeOn = true
            // Task history: one more tracking handed to the gateway (compared with tracking from the pilot).
            if (shown) update { s -> s.mowingRecord?.let { r -> s.copy(mowingRecord = r.copy(gatewayLegs = r.gatewayLegs + 1,
                links = r.links + fr.luba.poc.history.TrackingLink.GATEWAY)) } ?: s }
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
                    event("gateway_leg_work_window", tr("End of the working hours: tracking stop requested from the gateway", "Fin de la plage horaire : arrêt du suivi demandé à la passerelle"), mapOf("leg" to spec.legId))
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
            catch (lost: fr.luba.poc.autonomy.RemoteLegs.LostLeg) {
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
                else { mine.abort(); link.holdUntil = System.currentTimeMillis() + ABORT_HOLD_MS }
                legs.release(mine)
            }
        }
        result.calibration?.let(::storeCalibration)
        // Stop not confirmed by the gateway (robot link, GATT write…): the robot state is uncertain, do not continue.
        if (!result.stopConfirmed && result.outcome != fr.luba.poc.autonomy.LegOutcome.ABORTED)
            error(tr("Robot stop not confirmed by the gateway (${result.outcome}${result.text.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()})", "Arrêt du robot non confirmé par la passerelle (${result.outcome}${result.text.takeIf { it.isNotBlank() }?.let { " : $it" }.orEmpty()})"))
        fun mergeTrail() {
            if (result.trail.isEmpty() || navProgress != null) return
            update { it.copy(robotRoute = it.robotRoute.copy(trail = MissionTrail.merge(it.robotRoute.trail, result.trail))) }
        }
        event("gateway_leg_result", tr("Tracking returned by the gateway: ${result.outcome}", "Suivi rendu par la passerelle : ${result.outcome}"), mapOf("leg" to result.legId, "index" to result.index,
            "text" to result.text, "stopConfirmed" to result.stopConfirmed))
        if (windowAbort && result.outcome == fr.luba.poc.autonomy.LegOutcome.ABORTED) {
            check(result.stopConfirmed) { tr("Robot stop not confirmed by the gateway at the end of the working hours (${result.text})", "Arrêt du robot non confirmé par la passerelle en fin de plage horaire (${result.text})") }
            mergeTrail()
            return result.index
        }
        return when (result.outcome) {
            fr.luba.poc.autonomy.LegOutcome.DONE -> path.size
            fr.luba.poc.autonomy.LegOutcome.LOW_BATTERY -> result.index
            fr.luba.poc.autonomy.LegOutcome.CONTACT -> {
                mergeTrail()
                val c = requireNotNull(result.contact) { tr("Contact without pose", "Contact sans pose") }
                throw ObstacleEncounter(result.index, RouteContact(c.code, c.x, c.y, c.heading, c.atMs), emptyList(), tr("Bumper contact (${c.code})", "Contact bumper (${c.code})"))
            }
            fr.luba.poc.autonomy.LegOutcome.OBSTACLE_ADDED -> {
                mergeTrail()
                throw ObstacleEncounter(result.index, null, obstacles().filter { it.id in result.obstacleIds }, tr("Obstacle added on the route", "Obstacle ajouté sur le trajet"))
            }
            fr.luba.poc.autonomy.LegOutcome.FAILED -> {
                // Off the trajectory (deviation, footprint outside the corridor): the mission can resume from the returned edge.
                if (DriftRecovery.recoverable(result.text)) { mergeTrail(); throw RouteDrift(result.index, result.text) }
                // Transient protection raised by the robot: the mission waits for it to clear, then resumes.
                if (ProtectionPause.pausable(result.text)) { mergeTrail(); throw RouteProtection(result.index, result.text) }
                // Wheels turning without moving: the mission can defer the segment ([SmartResume]).
                if (SmartResume.blockage(result.text)) { mergeTrail(); throw RouteBlocked(result.index, result.text) }
                error(result.text)
            }
            fr.luba.poc.autonomy.LegOutcome.ABORTED -> error(tr("Autonomous tracking interrupted: ${result.text}", "Suivi autonome interrompu : ${result.text}"))
        }
    }
    private val mapCache = MapCache(app)
    private val obstacleStore = ObstacleStore(app)
    private val surveyStore = SignalSurveyStore(app.filesDir)
    /** Survey writes off the main thread: survey and "last shown" (continuous map) or archive only (dedicated survey). */
    private val surveyWrites = kotlinx.coroutines.channels.Channel<Pair<SignalSurvey, Boolean>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var surveyContours: List<List<Point>> = emptyList()
    /** Dedicated survey in progress (signal survey route): archived separately, its samples also feed the continuous map. */
    private var surveySession: SignalSurvey? = null
    private var surveySavedAt = 0L
    /** Continuous signal map ([SignalLiveMap]): in-memory version, published and saved at intervals. */
    private var liveMap: SignalSurvey? = null
    private var liveLast: SignalSample? = null
    private var livePublishedAt = 0L
    private var liveSavedAt = 0L

    /** Sample of a dedicated survey (gateway or local tracking), at most one per second. */
    private fun recordSurveySample(sample: SignalSample) {
        surveySession?.let { survey ->
            if (sample.at - (survey.samples.lastOrNull()?.at ?: 0) >= 1000) {
                val next = survey.copy(samples = survey.samples + sample)
                surveySession = next
                val now = SystemClock.elapsedRealtime()
                if (now - surveySavedAt >= SignalLiveMap.SAVE_MS) { surveySavedAt = now; surveyWrites.trySend(next to false) }
            }
        }
        recordLiveSample(sample)
    }

    /** Position received: continuous map sample if the robot has moved (outside a dedicated survey, which already feeds it). */
    private fun recordLiveSignal() {
        val ui = state.value
        if (surveySession != null && ui.robotRoute.running) return
        val t = ui.telemetry
        if (t.chargeState in setOf(1, 2)) return
        val now = System.currentTimeMillis()
        SignalCoverage.sample(t, now, null, fr.luba.poc.map.RobotTilt.current(now))?.let(::recordLiveSample)
    }

    private fun recordLiveSample(sample: SignalSample) {
        val ui = state.value
        val robotId = ui.selected?.address ?: return
        val map = ui.robotMap
        if (!map.complete || map.source.contains("DÉMONSTRATION", true)) return
        if (!SignalLiveMap.accepts(liveLast, sample)) return
        liveLast = sample
        val wall = System.currentTimeMillis()
        val current = liveMap?.takeIf { it.robotId == robotId } ?: ui.signalSurvey
        val version = SignalLiveMap.version(current, robotId, map, wall, SignalLiveMap.newId(wall, UUID.randomUUID().toString()))
        val opened = version.sessionId != current.sessionId
        if (opened) event("signal_live_version", tr("New version of the continuous signal map", "Nouvelle version de la carte continue des signaux"),
            mapOf("sessionId" to version.sessionId, "previous" to current.sessionId, "samples" to version.samples.size))
        val next = version.copy(samples = SignalLiveMap.merge(version.samples, sample))
        liveMap = next
        val now = SystemClock.elapsedRealtime()
        if (opened || now - livePublishedAt >= SignalLiveMap.PUBLISH_MS) { livePublishedAt = now; update { it.copy(signalSurvey = next) } }
        if (opened || now - liveSavedAt >= SignalLiveMap.SAVE_MS) { liveSavedAt = now; surveyWrites.trySend(next to true) }
    }

    /** End of activity: last samples published and saved (dedicated survey closed). */
    private fun flushSurveys() {
        surveySession?.let { surveyWrites.trySend(it to false) }
        surveySession = null
        liveMap?.let { live ->
            if (state.value.signalSurvey !== live) update { it.copy(signalSurvey = live) }
            surveyWrites.trySend(live to true)
            liveSavedAt = SystemClock.elapsedRealtime(); livePublishedAt = liveSavedAt
        }
    }
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val restoredId = positionPreferences.getString("id", null)
    private val restoredPosition = restoredId?.let(positionStore::read)
    private val checkpointStore = MissionCheckpointStore(app.filesDir)
    private val pointResumeStore = PointResumeStore(app.filesDir)
    /** Move-to-point writes serialized off the main thread; null = cleared. */
    private val pointResumeWrites = kotlinx.coroutines.channels.Channel<Pair<String, PointResume?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    /** Progress writes serialized off the main thread; null = mission finished or abandoned. */
    private val checkpointWrites = kotlinx.coroutines.channels.Channel<Pair<String, MissionCheckpoint?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    /** Actual mowing trail, kept from one resume to the next ([MissionTrail]); null list = trail cleared. */
    private val trailStore = MissionTrailStore(app.filesDir)
    private data class TrailWrite(val robotId: String, val startedAt: Long, val trail: List<Point>?)
    private val trailWrites = kotlinx.coroutines.channels.Channel<TrailWrite>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val watchdogPreferences = app.getSharedPreferences("motor_watchdog", 0)
    private fun watchdogResult(id: String?): MotorWatchdogResult? =
        id?.let { watchdogPreferences.getString(it, null) }?.let(MotorWatchdogResult::fromJson)?.takeIf { it.robotId == id }
    /** Route travel speed (%), kept between launches (10–100, 30 by default). */
    private val speedPreferences = app.getSharedPreferences("trajectory_speed", 0)
    /** Internal model code per robot ([Telemetry.productType]): cutting heights known as soon as it reconnects. */
    private val productPreferences = app.getSharedPreferences("robot_product_type", 0)
    private fun storedProductType(id: String?): String? = id?.let { productPreferences.getString(it, null) }
    /** Local map corrections: phone overlay, never sent to the robot nor written to the Mammotion cache. */
    private val correctionStore = MapCorrectionStore(app.filesDir)
    private val mutable = MutableStateFlow(RobotUi(signalSurvey = restoredId?.let(surveyStore::read) ?: SignalSurvey(), lastKnown = restoredPosition,
        missionCheckpoint = restoredId?.let(checkpointStore::read), pointResume = restoredId?.let(pointResumeStore::read),
        motorWatchdog = watchdogResult(restoredId), productType = storedProductType(restoredId),
        trajectorySpeedPercent = speedPreferences.getInt("percent", 30).coerceIn(10, 100))
        .withMammotionMap(restoredId?.let(mapCache::read) ?: RobotMap(), correctionStore.load()))
    val state = mutable.asStateFlow()
    private val notifications = LocalNotifications(app, viewModelScope)
    val localAlerts = notifications.state
    private val alertMonitor = AlertMonitor()
    private var alertLastTelemetryAt = 0L
    private var alertDeviceAt: Long? = null
    private var alertRtkAt: Long? = null
    private var alertCommunicationExpected = false
    private var alertLinkFault = false
    fun refreshNotifications() = notifications.refresh()
    fun markNotificationsSeen() = notifications.markSeen()
    fun clearNotificationHistory() = notifications.clearHistory()
    fun testNotification(severity: Severity) = alert(AlertKind.TEST, severity, tr("Local test requested — no robot command", "Test local demandé — aucune commande robot"))
    private fun alert(kind: AlertKind, severity: Severity, detail: String = kind.title, photo: String? = null) {
        val ui = state.value
        val known = ui.lastKnown?.takeIf { ui.selected == null || it.robotId == ui.selected.address }
        val point = known?.last
        val position = point?.let { tr("x=%.2f m, y=%.2f m · %s (last FIX)", "x=%.2f m, y=%.2f m · %s (dernier FIX)").format(fr.luba.poc.i18n.I18n.locale, it.x, it.y,
            java.text.SimpleDateFormat("dd/MM HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(java.util.Date(it.at))) }
        notifications.emit(RobotAlert(kind, severity, System.currentTimeMillis(), AlertContext(
            ui.selected?.address ?: known?.robotId ?: tr("unknown", "inconnu"), ui.selected?.name ?: known?.robotName ?: "Robot",
            tr("${ui.connection.label} · state ${ui.telemetry.robotState ?: known?.robotState ?: "unknown"} · ${ui.robotRoute.phase}", "${ui.connection.label} · état ${ui.telemetry.robotState ?: known?.robotState ?: "inconnu"} · ${ui.robotRoute.phase}"),
            ui.telemetry.battery ?: known?.battery, position), detail, photo = photo))
    }
    /**
     * "Obstacle encountered" notification for a bumper contact followed by a back-off (local route, move to a point),
     * once the photo is taken or missed: obstacle photo attached in large format if it exists.
     */
    private fun alertObstacle(request: fr.luba.poc.camera.ObstaclePhotoRequest, outcome: String, photo: java.io.File? = null) {
        val where = if (request.x != null && request.y != null)
            tr(" at x=%.2f m, y=%.2f m", " à x=%.2f m, y=%.2f m").format(fr.luba.poc.i18n.I18n.locale, request.x, request.y) else ""
        alert(AlertKind.OBSTACLE, Severity.WARNING, tr("Bumper contact (${request.code})$where · $outcome", "Contact pare-chocs (${request.code})$where · $outcome"), photo?.absolutePath)
    }
    /** Activity that moves or may move the robot or spin the blades. */
    private fun motionActive(): Boolean {
        val s = state.value
        return s.robotRoute.running || s.navTest.running || s.manualActive || s.teach.running || s.silenceTest.running ||
            s.bladeTestActive || s.heightSequence.running || s.mowing.running || bladeMayBeOn ||
            preparationJob?.isActive == true || liftGuard.moving(SystemClock.elapsedRealtime())
    }
    /**
     * Triage of each robot error code according to [RobotErrorCatalog]: label, log, alert and stop.
     * Returns true for lift / bumper codes, which keep their dedicated safety handling.
     */
    private fun triageRobotError(error: RobotError): Boolean {
        val info = RobotErrorCatalog.describe(error.code)
        update { it.copy(lastRobotError = RobotErrorReport(info, error.source, error.timestamp, System.currentTimeMillis())) }
        DifficultyClassifier.fromErrorCode(error.code)?.let { recordDifficulty(it, tr("Robot error ${info.summary}", "Erreur robot ${info.summary}"), code = error.code) }
        if (info.response == RobotErrorResponse.SAFETY) return true
        event("robot_error", tr("Robot error ${info.summary}", "Erreur robot ${info.summary}"), mapOf("code" to error.code, "source" to error.source,
            "robotErrorTime" to error.timestamp, "category" to info.category.name, "response" to info.response.name, "known" to info.known))
        when (info.response) {
            RobotErrorResponse.LOG, RobotErrorResponse.SAFETY -> Unit
            RobotErrorResponse.WARN -> {
                status(tr("Robot: ${info.summary}", "Robot : ${info.summary}"))
                alert(AlertKind.ROBOT_ERROR, Severity.WARNING, "${info.summary} — ${info.advice}")
            }
            RobotErrorResponse.STOP -> {
                val active = motionActive()
                // Route, navigation and manual control already get the "Unexpected stop" alert from requestStop.
                val alerted = active && (state.value.robotRoute.running || state.value.navTest.running || state.value.manualActive)
                if (active) requestStop(false, tr("Robot error ${info.summary}", "Erreur robot ${info.summary}")) else status(tr("Robot: ${info.summary}", "Robot : ${info.summary}"))
                if (!alerted) alert(AlertKind.ROBOT_ERROR, if (active) Severity.CRITICAL else Severity.WARNING, "${info.summary} — ${info.advice}")
            }
        }
        return false
    }
    private fun observeAlerts() {
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
        val weather = fr.luba.poc.weather.WeatherService.flow.value
        val rain = if (weather.monitoring && weather.location != null && (ui.mowing.running || ui.robotRoute.running))
            fr.luba.poc.weather.MowingRainCheck.check(weather.assessment, weather.forecast, System.currentTimeMillis(), 30).takeIf { it.warn } else null
        alertMonitor.condition(AlertKind.RAIN, rain?.let { Severity.WARNING }) { kind, severity -> alert(kind, severity, rain?.message ?: kind.title) }
        // Blades to replace: counter received over Bluetooth, interval and last change noted on the phone (no robot command).
        val blades = t.maintenance?.let { m -> ui.selected?.address?.let { id ->
            fr.luba.poc.maintenance.BladeWear.status(m, fr.luba.poc.maintenance.BladeMaintenanceSettings.change(id), fr.luba.poc.maintenance.BladeMaintenanceSettings.intervalHours) } }
        alertMonitor.condition(AlertKind.BLADE_WEAR, if (blades?.due == true) Severity.WARNING else null) { kind, severity -> alert(kind, severity, blades?.detail ?: kind.title) }
        observeGatewayBattery(ui)
    }
    /**
     * Working hours ([fr.luba.poc.mowing.WorkWindowSetting]), checked every second. Outside them: a mowing in progress
     * stops at the next tracking check, then returns to base ([workWindowEnding], [startRoute] loop);
     * an idle robot left outside is brought back once per out-of-hours period ([workWindowReturn]).
     */
    private fun observeWorkWindow() {
        val window = fr.luba.poc.mowing.WorkWindowSetting.value
        if (window.allows(java.time.LocalTime.now())) { workWindowReturnTried = false; return }
        val ui = state.value
        if (ui.robotRoute.running) {
            if (routeIntent == RouteIntent.FOLLOW && !workWindowEnding) {
                workWindowEnding = true
                event("work_window_end", tr("End of the working hours (${window.label}): mowing stopped and return to base", "Fin de la plage horaire de travail (${window.label}) : arrêt de la tonte et retour à la base"),
                    mapOf("window" to window.label))
                status(tr("End of the working hours (${window.label}): return to base", "Fin de la plage horaire de travail (${window.label}) : retour à la base"))
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
        val store = fr.luba.poc.simulation.BaseStore(getApplication(), map)
        val base = store.resolve(map) ?: return
        // More than one meter from the base: outside, even if the charge state of a docked robot is not published.
        if (Point(x, y).distance(base.point) <= WORK_WINDOW_OUTSIDE_M) return
        workWindowReturnTried = true
        workWindowReturn(window, store, base, map, Point(x, y))
    }

    /** Return to base of a robot left outside out of hours: same station route as the "Recharge" button. */
    private fun workWindowReturn(window: fr.luba.poc.mowing.WorkWindow, store: fr.luba.poc.simulation.BaseStore,
                                 base: fr.luba.poc.simulation.MapBase, map: RobotMap, start: Point) {
        event("work_window_return", tr("Outside the working hours (${window.label}): robot left outside brought back to base", "Hors plage horaire (${window.label}) : robot resté dehors ramené à la base"),
            mapOf("window" to window.label, "x" to start.x, "y" to start.y))
        status(tr("Outside the working hours (${window.label}): automatic return to base", "Hors plage horaire (${window.label}) : retour automatique à la base"))
        viewModelScope.launch {
            val failure = try {
                val task = fr.luba.poc.map.MowingTaskStore(getApplication()).read()
                val config = fr.luba.poc.simulation.SimConfig(returnPercent = task.chargeLow.toDouble(), resumePercent = task.chargeHigh.toDouble(),
                    baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM, instantCharging = true)
                val engine = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val routes = fr.luba.poc.planning.RoutePlanning.mission(map, state.value.planningObstacles, store.accesses(), base,
                        recordedBoundary = !map.source.contains("DÉMONSTRATION", true), avoidAreas = state.value.difficulties.transitAvoidance)
                    fr.luba.poc.planning.RoutePlanning.station(routes, map, base, start, config)
                }
                if (baseAction(engine, leave = false)) null
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
        val health = fr.luba.poc.gateway.GatewayHealthStore.latest.value?.takeIf { it.fresh() }?.health ?: return
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
    private fun gatewayDetached(text: String, fields: Map<String, Any?>) {
        // Text rebuilt in this phone's language from the fields; the gateway's own text otherwise.
        val verdict = fr.luba.poc.autonomy.GatewayDetachment.Verdict.fromFields(fields, System.currentTimeMillis())
        val shown = verdict?.text ?: fr.luba.poc.gateway.GatewayWireText.display(text)
        verdict?.let { v -> update { it.copy(gatewayDrop = GatewayDrop(v.point.x, v.point.y, System.currentTimeMillis(), v.probable, shown)) } }
        status(tr("$shown · robot stopped: pick up the phone before restarting", "$shown · robot arrêté : récupérer le téléphone avant de relancer"))
        alert(AlertKind.GATEWAY_DETACHED, Severity.CRITICAL, shown + if (verdict != null) tr(" · marker on the map", " · repère sur la carte") else "")
    }
    /** The gateway phone no longer responds (Wi-Fi off, switched off, app closed): one alert until telemetry returns. */
    private fun alertGatewayLost(detail: String) {
        if (!alertMonitor.armed) return
        val severity = GatewayAlertPolicy.lost(motionActive())
        alertMonitor.condition(AlertKind.GATEWAY_LOST, severity) { kind, level -> alert(kind, level, detail) }
    }
    /**
     * Safety stop written by the gateway during the outage, reported on reconnection: the loss had been reported as
     * "STOP not guaranteed". Confirmed by GATT only: the mechanical stop still has to be checked on site.
     */
    private fun gatewayStopReported(text: String) {
        val confirmed = fr.luba.poc.gateway.GatewayStopReport.confirmed(text)
        status(if (confirmed) tr("$text — confirmed by GATT, check the physical stop", "$text — confirmé par GATT, vérifier l'arrêt physique") else tr("$text — robot stop not guaranteed", "$text — arrêt du robot non garanti"))
        if (confirmed) alert(AlertKind.RECOVERY, Severity.INFO, tr("$text (confirmed by GATT, not mechanically)", "$text (confirmé par GATT, pas mécaniquement)"))
        else alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, tr("$text: check that the robot has physically stopped", "$text : vérifier l'arrêt physique du robot"))
    }
    private fun intentionalAlertStop() { alertMonitor.disarm(); alertCommunicationExpected=false; alertLinkFault=false }
    private fun alertRouteFailure(detail: String) {
        if(!state.value.robotRoute.running) return
        RouteAlertPolicy.failure(state.value.robotRoute.phase)?.let { alert(it, Severity.CRITICAL, detail) }
    }
    private fun alertMotionFailure(detail: String) {
        val blocked = detail.contains(tr("block", "bloqu"), ignoreCase=true) || detail.contains(tr("progress", "progression"), ignoreCase=true)
        if(blocked) alert(AlertKind.BLOCKED, Severity.CRITICAL, detail)
    }
    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for ((survey, latest) in surveyWrites) {
                val saved = runCatching { surveyStore.save(survey, latest); if (survey.live && latest) surveyStore.pruneLive() }.isSuccess
                handler.post { if (state.value.signalSurvey.sessionId == survey.sessionId) update { it.copy(surveySaveError = !saved) } }
            }
        }
        fr.luba.poc.mowing.WorkWindowSetting.init(getApplication())
        viewModelScope.launch { while (true) { delay(1000); observeAlerts(); observeWorkWindow() } }
        // Control through the gateway: automatic search and reconnection, screen on or off (startup, update,
        // Wi-Fi or Redmi back, BLE reconnections exhausted). No mission or command is resumed, except a mowing
        // interrupted by an announced update of the ESP32 gateway, if the setting allows it ([planGatewayUpdateResume]).
        fr.luba.poc.gateway.GatewayOta.load(getApplication())
        viewModelScope.launch {
            delay(1500)
            while (true) {
                retryGatewayLink()
                delay(fr.luba.poc.gateway.GatewayRetry.delayMs(gatewayFailures))
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for ((id, checkpoint) in checkpointWrites) {
                val saved = runCatching { if (checkpoint == null) checkpointStore.delete(id) else checkpointStore.save(checkpoint) }.isSuccess
                handler.post { if (state.value.selected?.address == id || state.value.selected == null) update { it.copy(checkpointSaveError = !saved) } }
            }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for ((id, resume) in pointResumeWrites) runCatching { if (resume == null) pointResumeStore.delete(id) else pointResumeStore.save(resume) }
                .onFailure { e -> handler.post { event("point_resume_save_failed", tr("Move to a point not saved: ${e.message}", "Déplacement au point non sauvegardé : ${e.message}")) } }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (write in trailWrites) runCatching {
                if (write.trail == null) trailStore.delete(write.robotId) else trailStore.save(write.robotId, write.startedAt, write.trail)
            }.onFailure { e -> handler.post { event("mission_trail_save_failed", tr("Mowing trail not saved: ${e.message}", "Trace de la tonte non sauvegardée : ${e.message}")) } }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            for (known in positionWrites) {
                val saved = runCatching { positionStore.save(known) }.isSuccess
                handler.post { if (state.value.lastKnown?.robotId == known.robotId) update { it.copy(positionSaveError = !saved) } }
            }
        }
    }
    // Mowing history: estimate frozen at launch, measured time and battery, end report.
    private val historyStore = fr.luba.poc.history.MowingHistoryStore(app.filesDir)
    private val historyWrites = kotlinx.coroutines.channels.Channel<List<fr.luba.poc.history.MowingRecord>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var historyTracker: fr.luba.poc.history.MowingTracker? = null
    private var historyJob: Job? = null
    private var historyRechargeBase = 0
    // "Change every session": directions of the zones actually mowed, written after each real mowing.
    private val orientationStore = fr.luba.poc.planning.ZoneOrientationStore(app.filesDir)
    private val orientationWrites = kotlinx.coroutines.channels.Channel<Map<Long, fr.luba.poc.planning.ZoneOrientation>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** Plan of the mowing being measured (preview and map), to record the directions it actually mowed. */
    private var orientationPlan: Pair<Preview, RobotMap>? = null
    private var historySavedAt = 0L
    // Task tracks: timestamped actual positions, map and prepared route, for replay from the history.
    // Declared before the init block: its write coroutine may start before the constructor finishes.
    private class TrackWrite(val id: String, val samples: List<fr.luba.poc.history.TrackSample> = emptyList(),
                             val meta: fr.luba.poc.history.TrackMeta? = null, val keep: List<String>? = null,
                             val events: List<fr.luba.poc.history.TaskEvent> = emptyList(), val seal: Boolean = false,
                             val boxes: List<fr.luba.poc.history.BlackBox> = emptyList())
    private val trackStore = fr.luba.poc.history.TaskTrackStore(app.filesDir)
    private val trackWrites = kotlinx.coroutines.channels.Channel<TrackWrite>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val stored = historyStore.read()
            val loaded = fr.luba.poc.history.MowingHistoryStore.closeStale(stored)
            if (loaded != stored) runCatching { historyStore.save(loaded) }
            handler.post { update { ui -> ui.copy(mowingHistory = (ui.mowingHistory + loaded.filter { r -> ui.mowingHistory.none { it.id == r.id } })
                .sortedByDescending { it.startedAt }) } }
            coverageRestore(loaded)
            for (records in historyWrites) runCatching { historyStore.save(records) }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val loaded = orientationStore.read()
            handler.post { update { ui -> ui.copy(zoneOrientations = loaded + ui.zoneOrientations) } }
            // Merged with the file: a mowing that ends before the read above has reached the state keeps the other zones.
            for (memory in orientationWrites) runCatching { orientationStore.merge(memory) }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
    @Volatile private var trackFull = false
    private var trackSampler: fr.luba.poc.history.TrackSampler? = null
    // Typed journal of the running task (recharges, obstacles, blockages, STOP, link losses…), written with its track.
    private var taskEvents: fr.luba.poc.history.TaskEventRecorder? = null
    // Last speed command written by this phone (raw units) and when (elapsed realtime), for the track samples.
    private var lastSetpoint: MotionSetpoints.Setpoint? = null
    private var lastSetpointAt = 0L
    // Previous position received (elapsed realtime, rotation centre): measured speed of the track samples.
    private var trackLastAt = 0L
    private var trackLastPoint: Point? = null
    private var trackLastSpeed = Double.NaN
    // Black box: sliding memory of the running task (frames, commands, log events), frozen at each incident.
    private val blackBox = fr.luba.poc.history.BlackBoxRecorder()
    private val blackBoxTrigger = fr.luba.poc.history.BlackBoxTrigger()
    private val trackBuffer = ArrayList<fr.luba.poc.history.TrackSample>()
    private var trackJob: Job? = null
    private var trackPositionAt = 0L
    private var trackFlushedAt = 0L

    /** Track of a history task (read from file); points still in memory included for the current task. */
    suspend fun loadTaskTrack(id: String): fr.luba.poc.history.TaskTrack? {
        val pending = if (state.value.mowingRecord?.id == id) trackBuffer.toList() else emptyList()
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { trackStore.read(id) }
        if (pending.isEmpty()) return saved
        return fr.luba.poc.history.TaskTrack(saved?.meta, (saved?.samples.orEmpty() + pending).distinctBy { it.atMs }.sortedBy { it.atMs })
    }
    private val log = EventLog(app) { text -> handler.post { if (gatt != null) fail(text) else status(text) } }
    private var gatt: RobotLink? = null
    /**
     * Link opening, replaceable by JVM tests (simulated robot in virtual time). Null in production:
     * gateway link ([fr.luba.poc.gateway.GatewayLink]) or this phone's Bluetooth ([GattLink]).
     */
    internal var linkFactory: ((SeenRobot, RobotLink.Listener) -> RobotLink?)? = null
    /** Tracking runs handed to the gateway (phone mounted on the robot): see [fr.luba.poc.autonomy.RemoteLegs]. */
    private val legs = fr.luba.poc.autonomy.RemoteLegs(fr.luba.poc.autonomy.LegTransport { type, payload -> (gatt as? fr.luba.poc.gateway.GatewayLink)?.legTransport?.send(type, payload) ?: false })
    /** A tracking run is handed to the gateway: this phone writes nothing more to the robot (the gateway holds it). */
    @Volatile private var remoteLegActive = false
    private var gatewayOutageSince = 0L
    /** Last gateway recovery after a ridden-out Wi-Fi outage (monotonic clock). */
    private var gatewayResumedAt = 0L
    private var blufi = Blufi()
    private var commands = Commands()
    private var reducer = TelemetryReducer()
    /** Resting value of the bumper slot learnt on this connection ([fr.luba.poc.protocol.BumperRest], DumbyLuba#1). */
    private val bumperRest = fr.luba.poc.protocol.BumperRest()
    /** A contact code (1014) was received on this connection: no bumper resting value is learnt any more. */
    private var contactThisConnection = false
    private val sensorProbe = SensorProbe()
    private val cameraObserver = fr.luba.poc.camera.CameraRobotObserver()
    private val perceptionMaps = fr.luba.poc.diagnostics.PerceptionMapObserver()
    // Automatic reconnection after an unexpected link loss: link only, commands are never resumed.
    private var unexpectedLoss = false
    private var wantLink = false
    private var readyAt = 0L
    private var reconnectMark = 0L
    private var reconnectAttempts = 0
    private var reconnectTimer: Job? = null
    private var watchdog = TelemetryWatchdog()
    private var started = 0L
    private var lastTelemetry = 0L
    private var periodic: Job? = null
    private var handshake: Job? = null
    private var scanTimeout: Job? = null
    // Automatic connection to the single robot found by a search started with autoConnect.
    private var autoConnectPending = false
    private var autoConnectCheck: Job? = null
    /** Emitted when an automatic search finds several robots: the user must choose. */
    val robotChoice = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var stationReader = fr.luba.poc.map.StationReader()
    private var mapTransfer: MapTransfer? = null
    private var liveMapRead = false
    private var mapJob: Job? = null
    private var closing = false
    private var closeAfterStop = false
    private val queue = ArrayDeque<Operation>()
    private var current: Operation? = null
    private var operationTimeout: Job? = null
    private var stopPending = false
    private var stopReason = ""
    private var batchCounter = 0L

    private sealed interface Operation {
        data class Write(val bytes: ByteArray, val label: String, val batch: Long, val finalStop: Boolean = false, val queuedAt: Long = SystemClock.elapsedRealtime(),
            val setpoint: MotionSetpoints.Setpoint? = null) : Operation
        data object Rssi : Operation
    }
    private fun update(block: (RobotUi) -> RobotUi) { mutable.value = block(mutable.value) }
    private var connectionId = 0L
    private var operationStarted = 0L
    private var stopRequestedAt = 0L
    private var stopAckCount = 0L
    private var connectRequestedAt = 0L
    private var reportCount = 0L
    private var positionCount = 0L
    private var reportPrevious = 0L
    private var positionPrevious = 0L
    private var devicePrevious = 0L
    private var reportGaps = TimingStats()
    private var positionGaps = TimingStats()
    private var writeTimes = TimingStats()
    private var logicalCommandsCompleted = 0L
    private var zeroCommandsQueued = 0L
    /** Motion setpoint frames removed from the queue, replaced by a more recent setpoint. */
    private var setpointsReplaced = 0L
    private var periodicSkipped = 0L
    /** Commands dropped by the read-only mode of a model that is not validated. */
    private var readOnlyRefused = 0L
    private var motionStarvedLogged = false
    /** Last non-zero motion setpoint confirmed as written (0: none since the stop). */
    private var motionWrittenAt = 0L
    private var zeroCommandsCompleted = 0L
    private var maxLoopDelayMs = 0L
    private var batteryStart: Int? = null
    private var batteryMin: Int? = null
    private var positionTypeChanges = 0L
    private var rtkStatusChanges = 0L
    private var driftOrigin: Pair<Double, Double>? = null
    private var maxStationaryDriftM = 0.0
    private var campaignJob: Job? = null
    private var campaignStart = 0L
    private var campaignFailure: String? = null
    private var ignoreTelemetry = false
    private var expectedFailure: String? = null
    private var expectedFaultObserved = 0L
    private var config = CampaignConfig()
    private var activeCase = ""
    private var bladeJob: Job? = null
    private var bladeMayBeOn = false
    private var bladeOffPending = false
    private var bladeOffGeneration = 0L
    /** Last height explicitly requested (height setting, blade test, route, navigation), 60 mm until then. */
    private var bladeHeightRequest = 60
    /**
     * Height of the manual-mode commands that must leave the deck where it is (joystick, slow approach, bumper test,
     * STOP, moves with blades stopped): the one the robot reports, unless a height setting owns the deck ([HoldHeight],
     * DumbyLuba#2).
     */
    private fun holdHeightMm(): Int = HoldHeight.mm(state.value.telemetry, bladeHeightRequest, state.value.cutHeights,
        adjusting = heightTarget != null || heightJob != null || bladeMayBeOn || bladeOffPending || state.value.bladeTestActive ||
            state.value.heightSequence.running || state.value.robotRoute.running || state.value.navTest.running)
    private var bladeStopVerified = false
    private var bladeOffWrittenAt = 0L
    private var bladeTestStarted = 0L
    private var bladeOnObserved = false
    private var bladeModeObserved = false
    private var bladeAltUsed = false
    private var bladeTestId = ""
    private var bladeDeviceAt = 0L
    private var heightTestId = ""
    private var heightTarget: Int? = null
    private var heightStarted = 0L
    private var heightJob: Job? = null
    /** Manual mowing mode (blades OFF) requested to apply the height: to be exited at the end of the adjustment. */
    private var heightModeSent = false
    private var heightSequenceJob: Job? = null
    private var mowPathTransfer: MowPathTransfer? = null
    private var mowPathDeadline = 0L
    private var routeJob: Job? = null
    /** Intent of the current route: only a mowing ([RouteIntent.FOLLOW]) stops at the end of the working hours. */
    private var routeIntent: RouteIntent? = null
    /** Working hours closed during the mowing: stop at the next check, return to base, end of mission. */
    @Volatile private var workWindowEnding = false
    /** Automatic return of a robot left outside already attempted for this out-of-hours period. */
    private var workWindowReturnTried = false
    private var routeBladeGate: RouteBladeGate? = null
    private var manualJob: Job? = null
    private var manualCommand = 0 to 0
    private var manualInputAt = 0L
    private var manualBlocked = true
    /** Last non-zero joystick speed (sign: forward / reverse), to place the object touched while driving. */
    private var manualLastLinear = 0
    /** Until then (elapsed realtime), a 1014 refines the contact of the joystick stopped on a protection. */
    private var manualContactUntil = 0L
    private var preparationJob: Job? = null
    private var mowJob: Job? = null
    private var mowOrigin: Point? = null
    private var mowDeadline = 0L
    private var mowCommandAt = 0L
    private var mowExpectedAction: Int? = null
    private var navJob: Job? = null
    private var navStarted = 0L
    private var navTestId = ""
    private var navConfig = NavConfig()
    private var navStart: NavSample? = null
    private var navMapPlan: MapNavPlan? = null
    /** Heading gap confirmed at the end of the last drive to a point that reached its end ([NavConfig.keepHeading]). */
    private var navHeadingMemory: NavHeadingMemory? = null
    /** Obstacle bypass in progress: obstacle, opposite point and return. */
    private var navCourse: LocalObstacle? = null
    private var navCourseFar: Point? = null
    private var navCourseHome: Point? = null
    /** Obstacles already excluded by the current plan: only the others trigger the preventive stop. */
    private var navPlanObstacles: Set<LocalObstacle> = emptySet()
    /** Fused heading confirmed by movement (route or drive in progress), otherwise null. */
    private var confirmedHeading: Double? = null
    /** Heading used to place an obstacle at contact: fused if confirmed, otherwise the published heading. */
    private fun contactHeading(reported: Double?): Double? = confirmedHeading ?: reported
    /** Physical geometry of the current route, for clearance segments. */
    private var routePhysicalRoutes: TrajectoryPlanner? = null
    /** Bypass armed while tracking the main edges; contact awaiting processing. */
    private var routeBypassArmed = false
    /** Short positioning loss ridden through during a route ([PositioningGrace]), kept from one range to the next. */
    private val routePositioningGrace = PositioningGrace()
    private var routeRidingPositioning = false
    private var routeContact: RouteContact? = null
    /**
     * Contacts at the limit of the task area ([fr.luba.poc.simulation.EdgeContact]) of the current route: drawn border and
     * bumper points of the limit contacts already handled (their count is the mission's). Kept here rather than as locals of
     * the route coroutine: every local captured by its local functions widens their calls, and the coroutine body went past
     * the Dalvik register threshold of the Fold build (`checkFoldDexRegisters`, 235 > 224).
     */
    private var routeEdgeBorder: fr.luba.poc.simulation.EdgeContact.Border? = null
    private val routeEdgeLimits = mutableListOf<Point>()
    private var navContact: RouteContact? = null
    private var localIncidentPending = false
    /**
     * Obstacle created at the last contact during a move to a point, estimated from the position published when the code
     * was received (up to 1 s old): moved in front of the stopped position before the back-off.
     */
    private var navContactObstacle: String? = null
    /** Manual mode with blades OFF requested for the map test: to be exited on stop. */
    private var navBumperMode = false
    private var bumperValidatedConnection = -1L
    private val robotErrors = RobotErrorMonitor()
    /** Lift during a BLE drive: robot codes, safety bits, wheels without progress. */
    private val liftGuard = fr.luba.poc.diagnostics.LiftGuard()
    private var teachJob: Job? = null
    private var teachStarted = 0L
    private var teachId = ""
    private var teachConfig = TeachConfig()
    private var obstacleApproach = false
    private var obstacleManualMode = false
    private var obstacleStart: NavSample? = null
    /** Frontal border test: job, identifier, start. */
    private var borderJob: Job? = null
    private var borderId = ""
    private var borderStarted = 0L
    /** Move to a point started by the frontal test itself (the only drive allowed during the test). */
    private var borderPositioning = false
    /** Manual mode with blades OFF for the frontal test: bumper contact handled by the test (STOP, measurement, no obstacle created). */
    private var borderBumperWatch = false
    private var borderContactCode: Int? = null
    private var borderErrorCode: Int? = null
    private enum class BorderRequest { OK, TOO_FAR, CONTINUE, EDGE }
    private var borderRequest: BorderRequest? = null
    // Obstacle photo after contact: declared before the init that reads the setting.
    // Difficulty log: declared before the init block that reads it (Kotlin initialization order).
    private val difficultyStore = fr.luba.poc.difficulty.DifficultyStore(app.filesDir)
    /** Pivot slides learned during mowings ([SlideField]), remembered from one mowing to the next. */
    private val slideStore = SlideFieldStore(app.filesDir)
    @Volatile private var slideField = SlideField()
    private val difficultyPrefs = app.getSharedPreferences("difficulties", android.content.Context.MODE_PRIVATE)
    private val photoPrefs = app.getSharedPreferences("obstacle_photo", android.content.Context.MODE_PRIVATE)
    /** Last obstacle notification without a photo (photo disabled): bounces of the same contact are ignored. */
    private var lastObstacleAlertAt = 0L
    private val photoStore = fr.luba.poc.camera.ObstaclePhotoStore(app.filesDir, runCatching { app.getExternalFilesDir("obstacle-photos") }.getOrNull())
    private var photoDone: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    /** Phone Wi-Fi lost or restored: logged (on 2026-10-14 the outage only showed up in `dumpsys wifi`). */
    private val phoneWifiListener: (String) -> Unit = { text -> event("phone_wifi", text, mapOf("wifiEnabled" to fr.luba.poc.gateway.PhoneWifi.enabled(),
        "wifiConnected" to fr.luba.poc.gateway.PhoneWifi.connected)) }

    init {
        fr.luba.poc.gateway.PhoneWifi.listen(phoneWifiListener)
        event("app_start", tr("Diagnostics started", "Démarrage diagnostics"), mapOf("androidApi" to android.os.Build.VERSION.SDK_INT,
            "androidRelease" to android.os.Build.VERSION.RELEASE, "model" to android.os.Build.MODEL,
            "appVersion" to fr.luba.poc.BuildConfig.VERSION_NAME, "appVersionCode" to fr.luba.poc.BuildConfig.VERSION_CODE, "timeoutSeconds" to state.value.timeoutSeconds))
        refreshHistory()
        update { it.copy(obstacles = obstacleStore.load(), obstaclePhoto = it.obstaclePhoto.copy(enabled = photoPrefs.getBoolean("enabled", true),
            camera = fr.luba.poc.camera.ObstaclePhotoUi.cameraOf(photoPrefs.getInt("camera", fr.luba.poc.camera.ObstaclePhotoUi.CAMERA_FRONT)))) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val photos = runCatching { photoStore.list() }.getOrDefault(emptyList())
            handler.post { update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(photos = photos)) } }
        }
        update { it.copy(difficulties = it.difficulties.copy(avoidPresumed = difficultyPrefs.getBoolean("avoidPresumed", true),
            avoidHot = difficultyPrefs.getBoolean("avoidHot", true))) }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val events = difficultyStore.load()
            handler.post { publishDifficulties(events, persist = false) }
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val field = synchronized(slideStore) { slideStore.load() }
            handler.post { if (slideField.isEmpty) slideField = field }
        }
    }

    // ───── Pivot slides: map learned during mowing ─────

    /**
     * Wet grass according to the garden weather ([fr.luba.poc.weather.GrassWetness]), if recent; null:
     * grass state unknown (garden location not set, weather missing or stale), no correction.
     */
    private fun grassWetness(): fr.luba.poc.weather.GrassWetness? = fr.luba.poc.weather.WeatherService.flow.value.wetness
        ?.takeIf { System.currentTimeMillis() - it.atSec * 1000 in 0..fr.luba.poc.weather.GrassWetness.STALE_MS }

    /**
     * Current ratio of slide / slide on dry grass: the map keeps slides normalized to dry grass,
     * rescaled to the current grass for tracking.
     */
    private fun wetSlideFactor(): Double = grassWetness()?.let { fr.luba.poc.weather.GrassWetness.slideFactor(it.index) } ?: 1.0

    /** Expected pivot slide at a point, or null: map empty or without enough samples. */
    private fun slideVelocity(): ((Point) -> Point?)? = slideField.takeUnless { it.isEmpty }?.scaled(wetSlideFactor())?.let { field -> field::velocity }

    /** Known slides around [path], bounded to fit in a gateway tracking frame. */
    private fun slideFor(path: List<SimEdge>): SlideField? =
        slideField.takeUnless { it.isEmpty }?.around(path.flatMap { listOf(it.a, it.b) }, 2.0, SLIDE_CELLS_TO_GATEWAY)
            ?.takeUnless { it.isEmpty }?.scaled(wetSlideFactor())

    /** Slide measured during a pivot (local or gateway tracking): map enriched (dry-grass equivalent) and saved. */
    private fun learnSlide(sample: SlideSample, source: String) {
        val factor = wetSlideFactor()
        slideField = slideField.learned(sample.copy(vx = sample.vx / factor, vy = sample.vy / factor))
        if (source == "local") event("route_pivot_slide", tr("Pivot slide measured", "Glissement au pivot mesuré"), mapOf("x" to sample.x, "y" to sample.y,
            "vx" to sample.vx, "vy" to sample.vy, "seconds" to sample.seconds, "grassWetness" to grassWetness()?.index, "wetSlideFactor" to factor))
        val snapshot = slideField
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { synchronized(slideStore) { if (slideField === snapshot) slideStore.save(snapshot) } }
                .exceptionOrNull()?.let { e -> handler.post { event("slide_save_error", tr("Slide map not saved", "Carte des glissements non enregistrée"), mapOf("reason" to (e.message ?: e.javaClass.simpleName))) } }
        }
    }

    // ───── Difficulties encountered: log, heatmap, presumed obstacles ─────


    /** Publishes [events] (presumed obstacles and difficult areas recomputed) and saves them if [persist]. */
    private fun publishDifficulties(events: List<fr.luba.poc.difficulty.DifficultyEvent>, persist: Boolean) {
        // During a route, presumed obstacles and avoided areas stay those of the launch (prepared route and
        // map preview unchanged); they are recomputed at the end of the route (cancelRobotRoute).
        update { it.copy(difficulties = fr.luba.poc.difficulty.DifficultyUi.derive(it.difficulties, events, it.obstacles,
            System.currentTimeMillis(), planning = !it.robotRoute.running)) }
        if (!persist) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Always the last published state: successive writes never go backwards.
            val saved = runCatching { synchronized(difficultyStore) { difficultyStore.save(state.value.difficulties.events) } }
            handler.post {
                update { it.copy(difficulties = it.difficulties.copy(saveError = saved.isFailure)) }
                saved.exceptionOrNull()?.let { e -> event("difficulty_save_error", tr("Difficulty log not saved", "Journal des difficultés non enregistré"), mapOf("reason" to (e.message ?: e.javaClass.simpleName))) }
            }
        }
    }

    /** Origin of a difficulty: autonomous movement in progress, or null (no movement, nothing to record). Persisted value, not translated. */
    private fun difficultySource(): String? = when {
        state.value.robotRoute.running -> if (remoteLegActive) "passerelle" else "trajet"
        state.value.navTest.running -> "navigation"
        state.value.mowing.running -> "tonte native"
        else -> null
    }

    /**
     * Records a difficulty at [position] (otherwise the robot's last position) during an autonomous movement.
     * Duplicates (same type within 2 m and 30 s: related stop and error) are ignored.
     */
    private fun recordDifficulty(kind: fr.luba.poc.difficulty.DifficultyKind, note: String, position: Point? = null,
                                 code: Int? = null, heading: Double? = null, obstacle: Point? = null, contactAt: Long? = null,
                                 detour: BypassDetour? = null, source: String? = difficultySource()): String? {
        if (source == null) return null
        val t = state.value.telemetry
        val p = position ?: if (t.x != null && t.y != null) Point(t.x, t.y) else return null
        if (!p.x.isFinite() || !p.y.isFinite()) return null
        val now = System.currentTimeMillis()
        val entry = runCatching { fr.luba.poc.difficulty.DifficultyEvent(UUID.randomUUID().toString(), now, kind, p.x, p.y,
            robotId = state.value.selected?.address ?: "", code = code, note = note.take(300), headingDeg = heading?.takeIf { it.isFinite() },
            obstacleX = obstacle?.x, obstacleY = obstacle?.y, contactAtMs = contactAt, source = source,
            side = detour?.side, inner = detour?.inner) }.getOrNull() ?: return null
        val events = state.value.difficulties.events
        // Contact recorded on receipt: refined (same entry) instead of being ignored as a duplicate.
        val next = fr.luba.poc.difficulty.DifficultyLog.refine(events, entry)
        // Id of the logged entry (new, or the contact it refines).
        val id = next.firstOrNull { it.id == entry.id }?.id ?: next.firstOrNull { it.kind == entry.kind && fr.luba.poc.difficulty.DifficultyLog.sameEncounter(it, entry) }?.id
        if (next == events) return id
        publishDifficulties(next, persist = true)
        event("difficulty", tr("${kind.label} recorded on the map", "${kind.label} consigné sur la carte"), mapOf("kind" to kind.code, "x" to p.x, "y" to p.y,
            "code" to code, "source" to source, "obstacleX" to obstacle?.x, "obstacleY" to obstacle?.y, "note" to note.take(120)))
        return id
    }

    /** Joystick running, or just stopped on a protection that may announce a contact ([ManualContact.GRACE_MS]). */
    private fun manualContactContext(): Boolean = state.value.manualActive || SystemClock.elapsedRealtime() < manualContactUntil

    /**
     * Contact while driving by hand ([code] 1014, or null for a collision protection / bumper alert without code yet):
     * logged on the map ("pilotage" origin), object in front of the bumper (behind when reversing), and its sheet opened
     * wherever the user is, to keep it as an obstacle with its size or dismiss it. The late 1014 refines the same entry.
     */
    private fun recordManualContact(code: Int?) {
        val t = state.value.telemetry
        val x = t.x?.takeIf { it.isFinite() } ?: return
        val y = t.y?.takeIf { it.isFinite() } ?: return
        val fresh = positionPrevious != 0L && SystemClock.elapsedRealtime() - positionPrevious in 0..2000
        val heading = t.heading?.takeIf { it.isFinite() && fresh }
        manualContactUntil = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
        val robot = Point(x, y)
        val obstacle = ManualContact.obstacleCenter(robot, heading, manualLastLinear < 0)
        val note = if (code != null) tr("Bumper contact ($code) while driving", "Contact pare-chocs ($code) pendant le pilotage")
            else tr("Collision protection while driving (self-check ${t.selfCheck}, sensors ${t.sensorStatus})",
                "Protection collision pendant le pilotage (self-check ${t.selfCheck}, capteurs ${t.sensorStatus})")
        val id = recordDifficulty(DifficultyKind.CONTACT, note, robot, code, heading, obstacle, System.currentTimeMillis(), source = "pilotage") ?: return
        update { it.copy(manualContactId = id) }
        event("manual_contact", tr("Contact while driving: obstacle sheet opened", "Contact pendant le pilotage : fiche obstacle ouverte"),
            mapOf("id" to id, "code" to code, "x" to x, "y" to y, "heading" to heading, "reversing" to (manualLastLinear < 0),
                "obstacleX" to obstacle.x, "obstacleY" to obstacle.y, "selfCheck" to t.selfCheck, "sensorStatus" to t.sensorStatus))
    }

    /** Sheet of the contact met while driving closed (saved, dismissed or kept in the log for later). */
    fun closeManualContact() { update { it.copy(manualContactId = null) } }

    /**
     * Bumper contact confirmed during an autonomous movement: recorded on the map as soon as it is received, object estimated
     * in front of the bumper (radius of a contact), whatever happens next (back-off, bypass, or mission stop).
     * A successful bypass refines it afterwards ([fr.luba.poc.difficulty.DifficultyLog.refine]). On 2026-10-15 at 11:00, the
     * post that was hit was recorded nowhere: the mission had stopped without a bypass, the only place where the
     * contact was recorded, so it could neither be seen nor saved as an obstacle.
     */
    private fun recordContactOnReceipt(code: Int) {
        if (difficultySource() == null) return
        val t = state.value.telemetry
        val x = t.x?.takeIf { it.isFinite() } ?: return
        val y = t.y?.takeIf { it.isFinite() } ?: return
        val heading = contactHeading(t.heading)?.takeIf { it.isFinite() }
        val now = System.currentTimeMillis()
        val robot = Point(x, y)
        // Without a heading, the object is placed on the robot: the contact stays visible and can be saved.
        val obstacle = heading?.let { h -> ObstacleBypass.contactObstacle("contact", robot, h, now).let { Point(it.x, it.y) } } ?: robot
        recordDifficulty(DifficultyKind.CONTACT, tr("Bumper contact ($code)", "Contact pare-chocs ($code)"), robot, code, heading, obstacle,
            state.value.obstaclePhoto.request?.contactAt ?: now)
    }

    /** Dismissed difficulty (object removed, false contact): it no longer counts in the heatmap or presumed obstacles. */
    fun dismissDifficulty(id: String, dismissed: Boolean = true) {
        val events = state.value.difficulties.events.map { if (it.id == id) it.copy(dismissed = dismissed) else it }
        publishDifficulties(events, persist = true)
        event("difficulty_dismissed", if (dismissed) tr("Difficulty dismissed", "Difficulté écartée") else tr("Difficulty restored", "Difficulté rétablie"), mapOf("id" to id))
    }

    /** Clears the whole difficulty log (heatmap and presumed obstacles). */
    fun clearDifficulties() {
        publishDifficulties(emptyList(), persist = true)
        event("difficulty_cleared", tr("Difficulty log cleared", "Journal des difficultés effacé"))
    }

    fun setAvoidPresumedObstacles(enabled: Boolean) {
        difficultyPrefs.edit().putBoolean("avoidPresumed", enabled).apply()
        update { it.copy(difficulties = it.difficulties.copy(avoidPresumed = enabled)) }
    }

    fun setAvoidDifficultAreas(enabled: Boolean) {
        difficultyPrefs.edit().putBoolean("avoidHot", enabled).apply()
        update { it.copy(difficulties = it.difficulties.copy(avoidHot = enabled)) }
    }

    /** Cost of known difficulties for bypasses (equivalent meters per meter travelled), or null. */
    private fun difficultyCost(): ((Point) -> Double)? {
        val events = state.value.difficulties.events
        val field = fr.luba.poc.difficulty.DifficultyField(events, System.currentTimeMillis())
        return if (field.isEmpty) null else field::costPerMeter
    }

    // ───── Obstacle photo after a bumper contact ─────

    fun setObstaclePhotoEnabled(enabled: Boolean) {
        photoPrefs.edit().putBoolean("enabled", enabled).apply()
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(enabled = enabled, request = if (enabled) it.obstaclePhoto.request else null)) }
        if (!enabled) photoDone?.complete(false)
    }

    /** Camera for the obstacle photo (1 front/left, 2 right); a pending request keeps its camera. */
    fun setObstaclePhotoCamera(camera: Int) {
        val uid = fr.luba.poc.camera.ObstaclePhotoUi.cameraOf(camera)
        photoPrefs.edit().putInt("camera", uid).apply()
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(camera = uid)) }
    }

    /**
     * Bumper contact during a local route or a move to a point: the video stream is prepared right away
     * (cloud token, ≈ 13 s until the first frame) to photograph the obstacle after the back-off.
     */
    private fun armObstaclePhoto(code: Int) {
        val photo = state.value.obstaclePhoto
        // Only when a back-off will follow the contact: local route (or gateway leg) with bypass,
        // move to a point with physical guard (not in a cutting test or an obstacle course).
        val reverseFollows = (state.value.robotRoute.running && (routeBypassArmed || remoteLegActive)) ||
            (state.value.navTest.running && navMapPlan?.physicalGuard == true && !navConfig.tonte && navCourse == null)
        if (!reverseFollows) return
        val now = System.currentTimeMillis()
        // Bounce of the same contact: the pending request is enough.
        if (photo.request != null && now - photo.request.contactAt < fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS * 2) return
        val t = state.value.telemetry
        val request = fr.luba.poc.camera.ObstaclePhotoRequest(id = now, contactAt = now, code = code, x = t.x, y = t.y, camera = photo.camera)
        // Local mode: no camera (Mammotion cloud), handled like a disabled photo, the robot does not wait.
        val localOnly = fr.luba.poc.auth.AccountMode.localOnly
        if (!photo.enabled || localOnly) {
            // Photo disabled: the encounter is notified without an image (bounces of the same contact ignored).
            if (now - lastObstacleAlertAt >= fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS * 2) { lastObstacleAlertAt = now
                alertObstacle(request, if (localOnly) tr("local mode, no photo", "mode local, sans photo") else tr("photo disabled", "photo désactivée")) }
            return
        }
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = request,
            status = tr("Contact: preparing the ${fr.luba.poc.camera.ObstaclePhotoUi.cameraLabel(photo.camera)}", "Contact : préparation de la ${fr.luba.poc.camera.ObstaclePhotoUi.cameraLabel(photo.camera)}"))) }
        event("obstacle_photo_request", tr("Bumper contact: obstacle photo requested after the back-off", "Contact pare-chocs : photo de l'obstacle demandée après le recul"),
            mapOf("code" to code, "x" to t.x, "y" to t.y, "camera" to photo.camera))
    }

    /**
     * After the back-off (robot stopped, blades OFF): waits for the photo for at most [ObstaclePhotoUi.HOLD_MS], robot
     * held still (zero speed, manual mode with blades stopped, errors polled). The route then resumes,
     * whether or not the photo was taken.
     */
    private suspend fun holdForObstaclePhoto(prefix: String, heightMm: Int) {
        val request = state.value.obstaclePhoto.request ?: return
        if (!state.value.obstaclePhoto.enabled || fr.luba.poc.auth.AccountMode.localOnly) return
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>().also { photoDone = it }
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = request.copy(readyAt = System.currentTimeMillis()), status = tr("Obstacle photo · robot still", "Photo de l'obstacle · robot immobile"))) }
        val began = SystemClock.elapsedRealtime()
        var keepalive = 0L
        try {
            while (!done.isCompleted && SystemClock.elapsedRealtime() - began < fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS) {
                check(state.value.ready && !closing && log.healthy) { tr("BLE interrupted during the obstacle photo", "BLE interrompu pendant la photo de l'obstacle") }
                if (!state.value.robotRoute.running && !state.value.navTest.running) break
                val now = SystemClock.elapsedRealtime()
                if (now >= keepalive) {
                    send(commands.stop(), "${prefix}_PHOTO_STOP")
                    send(commands.manualMow(1, 0, heightMm), "${prefix}_PHOTO_BUMPER_OFF", periodic = true)
                    send(commands.errorQuery(), "${prefix}_PHOTO_ERRORS", periodic = true)
                    keepalive = now + 500
                }
                delay(100)
            }
        } finally { if (photoDone === done) photoDone = null }
        val taken = done.isCompleted && done.getCompleted()
        if (!taken) {
            val reason = state.value.obstaclePhoto.status.takeIf { it.startsWith(tr("Photo failed", "Photo impossible")) || it.startsWith(tr("Photo not saved", "Photo non enregistrée")) }
                ?: tr("Photo not taken within ${fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS / 1000} s", "Photo non prise en ${fr.luba.poc.camera.ObstaclePhotoUi.HOLD_MS / 1000} s")
            update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = null, status = tr("$reason · route resumed", "$reason · trajet repris"))) }
            event("obstacle_photo_missed", reason, mapOf("code" to request.code, "waitMs" to SystemClock.elapsedRealtime() - began))
            alertObstacle(request, reason.replaceFirstChar { it.lowercase() })
        }
    }

    /** Photo received from the video player for request [id]: saved, then the route resumes. */
    fun obstaclePhotoCaptured(id: Long, jpeg: ByteArray, camera: Int = fr.luba.poc.camera.ObstaclePhotoUi.CAMERA_FRONT,
                              timing: fr.luba.poc.camera.ObstaclePhotoTiming = fr.luba.poc.camera.ObstaclePhotoTiming()) {
        val request = state.value.obstaclePhoto.request?.takeIf { it.id == id } ?: return
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = null, status = tr("Obstacle photo saved", "Photo de l'obstacle enregistrée"))) }
        val done = photoDone
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val takenAt = System.currentTimeMillis()
            val saved = runCatching { photoStore.save(jpeg, takenAt, request.contactAt, request.code, request.x, request.y) }
            val photos = runCatching { photoStore.list() }.getOrDefault(emptyList())
            handler.post {
                saved.onSuccess { photo ->
                    val label = fr.luba.poc.camera.ObstaclePhotoUi.cameraLabel(camera)
                    val stale = if (timing.fresh) "" else tr(" · image possibly taken before the back-off (stream lagging)", " · image peut-être antérieure au recul (flux en retard)")
                    update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(photos = photos, status = tr("Obstacle photo saved ($label, ${photo.delaySeconds} s after the contact)", "Photo de l'obstacle enregistrée ($label, ${photo.delaySeconds} s après le contact)") +
                        (if (camera != request.camera) tr(" · ${fr.luba.poc.camera.ObstaclePhotoUi.cameraLabel(request.camera)} without image", " · ${fr.luba.poc.camera.ObstaclePhotoUi.cameraLabel(request.camera)} sans image") else "") + stale)) }
                    event("obstacle_photo", tr("Obstacle photo saved", "Photo de l'obstacle enregistrée"), mapOf("file" to photo.file.name, "bytes" to jpeg.size,
                        "code" to request.code, "x" to request.x, "y" to request.y, "delayMs" to takenAt - request.contactAt,
                        "camera" to camera, "wantedCamera" to request.camera, "fresh" to timing.fresh, "frameAgeMs" to timing.frameAgeMs,
                        "streamDelayMs" to timing.delayMs, "afterStopMs" to timing.afterStopMs))
                    alertObstacle(request, tr("photo ($label) ${photo.delaySeconds} s after the contact$stale", "photo ($label) ${photo.delaySeconds} s après le contact$stale"), photo.file)
                }.onFailure { error ->
                    update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(status = tr("Photo not saved: ${error.message ?: error.javaClass.simpleName}", "Photo non enregistrée : ${error.message ?: error.javaClass.simpleName}"))) }
                    event("obstacle_photo_error", tr("Photo not saved", "Photo non enregistrée"), mapOf("reason" to (error.message ?: error.javaClass.simpleName)))
                }
                done?.complete(saved.isSuccess)
            }
        }
    }

    /** Photo failed (account, network, stream): the robot waits no longer. */
    fun obstaclePhotoFailed(id: Long, reason: String) {
        val request = state.value.obstaclePhoto.request?.takeIf { it.id == id } ?: return
        update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(request = null, status = tr("Photo failed: $reason", "Photo impossible : $reason"))) }
        event("obstacle_photo_failed", tr("Obstacle photo failed", "Photo de l'obstacle impossible"), mapOf("reason" to reason))
        // Before the back-off, no wait will notify the encounter; after it, holdForObstaclePhoto takes care of it.
        if (request.readyAt == null) alertObstacle(request, tr("photo failed: $reason", "photo impossible : $reason"))
        photoDone?.complete(false)
    }
    /** Measured manoeuvre ([ManoeuvreStats]), local tracking or gateway progress: `route_curve_stats`. */
    private fun logManoeuvre(summary: ManoeuvreStats.Summary, source: String) {
        val m = summary.manoeuvre
        event("route_curve_stats", tr("Manoeuvre %s driven in %.1f s, largest deviation %.0f cm", "Manœuvre %s suivie en %.1f s, écart max %.0f cm")
            .format(m.kind.name, summary.durationS, summary.maxDeviationM * 100), mapOf("kind" to m.kind.name, "first" to m.first, "last" to m.last,
            "degrees" to m.degrees, "lengthM" to m.lengthM, "durationS" to summary.durationS, "medianSpeedMps" to summary.medianSpeedMps,
            "maxDeviationM" to summary.maxDeviationM, "source" to source))
    }
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) {
        taskEvents?.let { recorder ->
            if (kind !in fr.luba.poc.history.BlackBoxRecorder.NOISY) blackBox.note(fr.luba.poc.history.BlackBoxNote(System.currentTimeMillis(), kind, text.take(300)))
            taskPose().let { (x, y, battery) -> taskEventWrite(recorder.fromLog(kind, text, fields, System.currentTimeMillis(), x, y, battery)) }
        }
        log.append(kind, text, fields + mapOf("connectionId" to connectionId,
            "campaignId" to state.value.campaign.id, "caseId" to activeCase, "queueDepth" to queue.size,
            "bladeTestId" to bladeTestId, "heightTestId" to heightTestId))
        update { it.copy(events = (it.events + "${java.time.LocalTime.now()} $text").takeLast(150)) }
    }
    /**
     * Ultrasound/perception capture: no motion command; the telemetry subscription adds the vision streams
     * for the duration of the capture. Full frames go to the log.
     */
    fun startSensorProbe() {
        sensorProbe.start(System.currentTimeMillis())
        update { it.copy(sensorProbe = sensorProbe.snapshot()) }
        event("sensor_probe", tr("Sensor capture started (decoded ultrasound + vision streams)", "Capture capteurs démarrée (ultrasons décodés + flux vision)"))
        if (gatt != null) send(reportsCommand(false, periodMs = reportPeriodMs()), "PROBE_RPT_VISION")
    }
    /** Current telemetry subscription: vision streams added while the sensor probe is running. */
    private fun reportsCommand(keep: Boolean, periodMs: Int = 1000) =
        commands.reports(keep, periodMs = periodMs, vision = sensorProbe.isRunning)
    /**
     * Report period: 200 ms during a tracked movement (route, learning, tests), 1 s otherwise. The LUBA 2 (firmware
     * 1.30.31.19) stays at 1 Hz whatever the request (measured on 2026-10-20, see [Commands.reports]).
     */
    private fun reportPeriodMs(): Int = if (state.value.robotRoute.running || state.value.teach.running || state.value.navTest.running ||
        state.value.silenceTest.running || state.value.driven?.paused == false) 200 else 1000
    /** Firmware debug switch during the capture (no movement); reset to 0 on stop. */
    fun setSensorProbeDebug(on: Boolean) {
        if (gatt == null || !sensorProbe.isRunning || sensorProbe.debugEnabled == on) return
        send(commands.debugEnable(on), if (on) "PROBE_DEBUG_ON" else "PROBE_DEBUG_OFF")
        sensorProbe.debugEnabled = on
        update { it.copy(sensorProbe = sensorProbe.snapshot()) }
        event("sensor_probe", if (on) tr("Firmware debug enabled for the capture", "Débogage firmware activé pour la capture") else tr("Firmware debug disabled", "Débogage firmware désactivé"))
    }
    private var zmqJob: Job? = null
    @Volatile private var zmqSocket: java.net.Socket? = null
    private fun zmqUpdate(block: (ProbeZmq) -> ProbeZmq) {
        sensorProbe.zmq = block(sensorProbe.zmq)
        update { it.copy(sensorProbe = sensorProbe.snapshot()) }
    }

    /**
     * Robot DDS → ZMQ debug bridge (sensor probe, no movement): asks for the robot IP, enables
     * `todev_set_dds2zmq`, then listens on its port 5555 over ZMTP on the local Wi-Fi. Disabled when the capture stops.
     */
    fun setSensorProbeZmq(on: Boolean) {
        if (!on) { stopSensorProbeZmq(sendOff = true); return }
        if (gatt == null || !sensorProbe.isRunning || sensorProbe.zmq.enabled) return
        send(commands.networkInfo(), "PROBE_NETWORK_INFO")
        send(commands.perceptionZmq(true), "PROBE_ZMQ_ON")
        zmqUpdate { it.copy(enabled = true, status = tr("Bridge requested: waiting for the robot's IP address", "Pont demandé : attente de l'adresse IP du robot")) }
        event("sensor_probe_zmq", tr("DDS → ZMQ perception bridge requested ($PERCEPTION_TOPIC → $PERCEPTION_ZMQ_URL)", "Pont perception DDS → ZMQ demandé ($PERCEPTION_TOPIC → $PERCEPTION_ZMQ_URL)"))
        zmqJob?.cancel()
        zmqJob = viewModelScope.launch { listenPerceptionZmq() }
    }

    private fun stopSensorProbeZmq(sendOff: Boolean) {
        val z = sensorProbe.zmq
        if (!z.enabled) return
        zmqJob?.cancel(); zmqJob = null
        runCatching { zmqSocket?.close() }; zmqSocket = null
        if (sendOff && gatt != null) send(commands.perceptionZmq(false), "PROBE_ZMQ_OFF")
        zmqUpdate { it.copy(enabled = false, status = tr("Bridge disabled · ${it.messages} message(s) received", "Pont désactivé · ${it.messages} message(s) reçus")) }
        event("sensor_probe_zmq", tr("ZMQ perception bridge disabled: ${z.messages} message(s), ${z.bytes} bytes", "Pont perception ZMQ désactivé : ${z.messages} message(s), ${z.bytes} octets"),
            mapOf("robotIp" to z.robotIp, "peer" to z.peer, "messages" to z.messages, "bytes" to z.bytes,
                "minSize" to z.minSize, "maxSize" to z.maxSize, "samples" to z.samples.joinToString(" | ")))
    }

    private suspend fun listenPerceptionZmq() {
        val began = SystemClock.elapsedRealtime()
        var ip = sensorProbe.zmq.robotIp
        var asked = 1
        while (ip == null && sensorProbe.zmq.enabled) {
            val elapsed = SystemClock.elapsedRealtime() - began
            if (elapsed > 20_000) {
                zmqUpdate { it.copy(status = tr("Robot IP unknown: toapp_networkinfo_rsp not received (robot without Wi-Fi?)", "IP du robot inconnue : toapp_networkinfo_rsp non reçu (robot sans Wi-Fi ?)")) }
                event("sensor_probe_zmq", tr("ZMQ bridge: robot IP not received within 20 s", "Pont ZMQ : IP du robot non reçue en 20 s")); return
            }
            if (elapsed > asked * 5_000L && gatt != null) { asked++; send(commands.networkInfo(), "PROBE_NETWORK_INFO") }
            delay(300)
            ip = sensorProbe.zmq.robotIp
        }
        if (ip == null) return
        event("sensor_probe_zmq", tr("ZMQ bridge: robot at $ip, connecting to port $PERCEPTION_ZMQ_PORT", "Pont ZMQ : robot à $ip, connexion au port $PERCEPTION_ZMQ_PORT"), mapOf("network" to sensorProbe.zmq.network))
        var socketType = "SUB"
        var attempt = 0
        while (sensorProbe.zmq.enabled) {
            attempt++
            val socket = java.net.Socket()
            zmqSocket = socket
            try {
                val input = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    socket.connect(java.net.InetSocketAddress(ip, PERCEPTION_ZMQ_PORT), 3_000)
                    socket.soTimeout = 0
                    java.io.DataInputStream(java.io.BufferedInputStream(socket.getInputStream()))
                }
                val handshake = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    socket.soTimeout = 5_000
                    Zmtp.handshake(input, socket.getOutputStream(), socketType).also { socket.soTimeout = 0 }
                }
                val peer = "${handshake.peerSocketType.ifEmpty { "?" }} · ZMTP ${handshake.version} ${handshake.mechanism}" +
                    handshake.properties.filterKeys { it != "Socket-Type" }.entries.joinToString("") { " · ${it.key}=${it.value}" }
                if (handshake.error != null) {
                    zmqUpdate { it.copy(peer = peer, status = tr("Refused by the robot: ${handshake.error}", "Refusé par le robot : ${handshake.error}")) }
                    event("sensor_probe_zmq", tr("ZMQ bridge refused: ${handshake.error}", "Pont ZMQ refusé : ${handshake.error}"), mapOf("peer" to peer)); return
                }
                val wanted = Zmtp.compatible(handshake.peerSocketType)
                if (wanted != socketType && attempt < 4) {
                    event("sensor_probe_zmq", tr("ZMQ bridge: the robot is ${handshake.peerSocketType}, reconnecting as $wanted", "Pont ZMQ : le robot est ${handshake.peerSocketType}, reconnexion en $wanted"))
                    socketType = wanted
                    runCatching { socket.close() }
                    continue
                }
                zmqUpdate { it.copy(peer = peer, status = tr("Connected to $ip:$PERCEPTION_ZMQ_PORT as $socketType: listening", "Connecté à $ip:$PERCEPTION_ZMQ_PORT en $socketType : en écoute")) }
                event("sensor_probe_zmq", tr("ZMQ bridge connected to $ip:$PERCEPTION_ZMQ_PORT", "Pont ZMQ connecté à $ip:$PERCEPTION_ZMQ_PORT"), mapOf("peer" to peer, "socketType" to socketType))
                while (sensorProbe.zmq.enabled) {
                    val parts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Zmtp.readMessage(input) }
                    recordZmqMessage(parts)
                }
            } catch (e: CancellationException) {
                runCatching { socket.close() }; throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
                if (!sensorProbe.zmq.enabled) return
                val reason = if (Zmtp.isEnd(e)) tr("connection closed by the robot", "connexion fermée par le robot") else (e.message ?: e.javaClass.simpleName)
                zmqUpdate { it.copy(status = tr("Port $PERCEPTION_ZMQ_PORT of $ip: $reason (attempt $attempt, retrying in 3 s)", "Port $PERCEPTION_ZMQ_PORT de $ip : $reason (essai $attempt, nouvel essai dans 3 s)")) }
                if (attempt == 1 || attempt % 10 == 0) event("sensor_probe_zmq", tr("ZMQ bridge: $reason", "Pont ZMQ : $reason"), mapOf("attempt" to attempt, "ip" to ip))
                if (attempt >= 60) {
                    zmqUpdate { it.copy(status = tr("Port $PERCEPTION_ZMQ_PORT of $ip unreachable after 3 min: $reason", "Port $PERCEPTION_ZMQ_PORT de $ip injoignable après 3 min : $reason")) }
                    event("sensor_probe_zmq", tr("ZMQ bridge abandoned after $attempt attempts: $reason", "Pont ZMQ abandonné après $attempt essais : $reason")); return
                }
                delay(3_000)
            }
        }
    }

    private fun recordZmqMessage(parts: List<ByteArray>) {
        val size = parts.sumOf { it.size }
        val z = sensorProbe.zmq
        val n = z.messages + 1
        val sample = tr("#$n · $size B · ", "#$n · $size o · ") + parts.joinToString(" | ") { tr("${it.size} B ${Zmtp.preview(it)}", "${it.size} o ${Zmtp.preview(it)}") }
        zmqUpdate { it.copy(messages = n, bytes = it.bytes + size, lastAt = System.currentTimeMillis(),
            minSize = if (it.messages == 0L) size else minOf(it.minSize, size), maxSize = maxOf(it.maxSize, size),
            samples = if (it.samples.size < ProbeZmq.MAX_SAMPLES) it.samples + sample else it.samples) }
        // Full frames (at most 4 KB per part) for the first messages, then one checkpoint every 100.
        if (n <= 20) event("sensor_probe_zmq_msg", sample.take(200), mapOf("index" to n, "size" to size,
            "parts" to parts.joinToString(" | ") { Zmtp.hex(it, minOf(it.size, 4096)) }))
        else if (n % 100 == 0L) event("sensor_probe_zmq", tr("ZMQ bridge: $n messages received", "Pont ZMQ : $n messages reçus"), mapOf("bytes" to z.bytes + size))
    }

    fun stopSensorProbe() {
        if (!sensorProbe.isRunning) return
        stopSensorProbeZmq(sendOff = true)
        if (sensorProbe.debugEnabled && gatt != null) send(commands.debugEnable(false), "PROBE_DEBUG_OFF")
        sensorProbe.debugEnabled = false
        sensorProbe.stop()
        if (gatt != null) send(reportsCommand(false, periodMs = reportPeriodMs()), "PROBE_RPT_RESTORE")
        val snapshot = sensorProbe.snapshot()
        update { it.copy(sensorProbe = snapshot) }
        event("sensor_probe", tr("Sensor capture stopped: ${snapshot.verdict}", "Capture capteurs arrêtée : ${snapshot.verdict}"), mapOf("messages" to snapshot.messages,
            "streams" to snapshot.streams.joinToString { "${it.key}×${it.count}" },
            "sensorValues" to snapshot.sensorValues.joinToString { "${it.value}×${it.count}" },
            "unknown" to snapshot.unknown.joinToString { "${it.path} ${it.lastFields}" },
            "perception" to snapshot.perceptionMessages + snapshot.peptPayloads, "keywordHits" to snapshot.keywordHits.joinToString("; "),
            "slots" to snapshot.slots.joinToString { "${it.slot.name}=${it.current}/max ${it.max}/×${it.changes}" },
            "visionPoints" to snapshot.vision.reportPoints + snapshot.vision.tunnelPoints, "vio" to snapshot.vision.vio,
            "visionStatistics" to snapshot.vision.statistics, "lastVisionPoints" to snapshot.vision.lastPoints,
            "debugReports" to snapshot.debugEntries.joinToString("; ") { "${it.source} ${it.key}=${it.value.take(80)}" },
            "rawArrays" to snapshot.arrays.joinToString("; ") { "${it.name}×${it.count} varie ${it.varying.joinToString(" ")}" },
            "robotStates" to snapshot.robotStatesLabel, "obstacles" to snapshot.extras.obstacleMessages, "visionState" to snapshot.extras.visionState, "vslam" to snapshot.extras.vslamStatus))
    }

    /**
     * Camera: multimedia module command (vi_switch or refresh_fpv) over the current robot link
     * (direct Bluetooth or gateway). No motion command. False if no link is ready.
     */
    fun sendCameraCommand(payload: ByteArray, label: String): Boolean {
        if (gatt == null || !state.value.ready) return false
        send(payload, label)
        return true
    }
    fun cameraVideo(on: Boolean): Boolean = sendCameraCommand(commands.video(on), if (on) "CAMERA_VIDEO_ON" else "CAMERA_VIDEO_OFF").also {
        if (it) event("camera", if (on) tr("Camera: publishing requested (vi_switch=1)", "Caméra : publication demandée (vi_switch=1)") else tr("Camera: stop requested (vi_switch=0)", "Caméra : arrêt demandé (vi_switch=0)"))
    }
    fun cameraRefresh(): Boolean = sendCameraCommand(commands.refreshFpv(), "CAMERA_REFRESH_FPV")
    fun cameraEvent(text: String, fields: Map<String, Any?> = emptyMap()) = event("camera", text, fields)
    fun resetSensorProbe() { sensorProbe.reset(); update { it.copy(sensorProbe = sensorProbe.snapshot()) } }
    private fun status(text: String) { update { it.copy(status = text) }; event("status", text) }
    fun setTimeout(seconds: Int) {
        if (gatt == null) update { it.copy(timeoutSeconds = seconds.coerceIn(5, 30)) }
    }
    fun export(uri: Uri, archive: Boolean = false, anonymize: Boolean = true) {
        if (state.value.campaign.running) return
        log.export(uri, archive, anonymize) { result -> handler.post { status(result) } }
    }
    fun refreshHistory() { log.history { value -> handler.post { update { it.copy(history = value) } } } }
    fun runLocalChecks() {
        if (state.value.campaign.running) return
        val results = LocalChecks.run()
        results.forEach { event("local_check", it.title, mapOf("outcome" to it.outcome, "detail" to it.detail)) }
        update { it.copy(localResults = results) }
    }

    fun recordSimulationPreparation(detail: String, fields: Map<String, Any?>) {
        event("simulation_preparation",detail,fields)
    }
    fun recordSimulationTransit(engine:fr.luba.poc.simulation.MowingSimulation,state:fr.luba.poc.simulation.SimState) {
        event("simulation_transit",state.phase.label,mapOf(
            "phase" to state.phase.name,"cycle" to state.cycles,"coveredMeters" to state.covered,
            "heading" to state.heading,"baseX" to engine.base.x,"baseY" to engine.base.y,
            "baseHeading" to engine.dockEntryHeading,"finalReturn" to state.completedReturn,
            "distanceMeters" to state.transit.zipWithNext().sumOf { (a,b)->a.distance(b) },
            "batteryPercent" to state.battery,"elapsedSeconds" to state.elapsed,"x" to state.position.x,"y" to state.position.y,
            "message" to state.log.lastOrNull(),
            "path" to state.transit.map { mapOf("x" to it.x,"y" to it.y) }))
    }
    fun recordMapPreview(preview: Preview) { event("planned_path_preview", tr("Coverage preview computed, no motor command", "Aperçu de couverture calculé, aucune commande moteur"), mapOf("zoneIds" to preview.zoneIds, "zoneAngles" to preview.zoneAngles, "mode" to preview.mode.name, "outerLoops" to preview.outerLoops, "noGoLoops" to preview.noGoLoops, "distanceMeters" to preview.meters, "angleDegrees" to preview.angle, "sections" to preview.runs.size, "runs" to preview.runs.map { run -> run.map { mapOf("x" to it.x, "y" to it.y) } })) }
    /** Reason why the BLE map cannot be read now, null when the reading may start. */
    private fun mapReadRefusal(): String? = when {
        state.value.robotRoute.running -> tr("Finish the BLE route first", "Terminer le trajet BLE")
        state.value.manualActive -> tr("Release the joystick before this action", "Relâcher le joystick avant cette action")
        state.value.mowing.active -> tr("Finish the mission before re-reading the map", "Terminer la mission avant de relire la carte")
        state.value.teach.running || state.value.navTest.running || !state.value.ready || state.value.mapLoading || state.value.campaign.running ||
            state.value.bladeTestActive || bladeMayBeOn || heightTarget != null -> tr("Map: finish the tests and connect the robot", "Carte : terminer les essais et connecter le robot")
        else -> null
    }
    /** Connection for which the automatic map reading was already attempted (one attempt per connection). */
    private var autoMapReadConnection = -1L
    /** Bound of a BLE map reading that keeps going without ending (each element has its own retries). */
    private val MAP_READ_MAX_MS = 300_000L
    /**
     * Automatic BLE reading when no map is known for the connected robot (new installation, cache cleared, robot
     * never read): without it the map stays empty while the robot is connected, the account map being shown only
     * without Bluetooth. Called by the screen once telemetry is received; at most once per connection, silent when
     * the robot is busy (the manual reading stays available). Returns true when the reading started.
     */
    fun autoFetchMap(): Boolean {
        if (autoMapReadConnection == connectionId || state.value.connection != ConnectionState.Receiving ||
            state.value.mammotionMap.shapes.isNotEmpty() || mapReadRefusal() != null) return false
        autoMapReadConnection = connectionId
        event("map_auto_read", tr("No map known for this robot: automatic BLE reading", "Aucune carte connue pour ce robot : lecture BLE automatique"))
        fetchMap()
        return true
    }
    fun fetchMap() {
        mapReadRefusal()?.let { status(it); return }
        mapJob?.cancel()
        val transfer = MapTransfer(initialStation = stationReader.station ?: state.value.robotMap.station, clock = { SystemClock.elapsedRealtime() })
        mapTransfer = transfer
        update { it.copy(mapLoading = true, mapReadFailure = null).withMammotionMap(RobotMap(source = tr("Reading the BLE map…", "Lecture de la carte BLE…"), station = stationReader.station ?: it.mammotionMap.station)) }
        send(commands.sync(), "MAP_SYNC"); send(commands.mapHashes(), "MAP_HASHES")
        event("map_start", tr("Read-only BLE outlines", "Lecture seule des contours BLE"), mapOf("model" to fr.luba.poc.ui.RobotModel.detect(state.value.selected?.name).name, "productType" to state.value.productType,
            "navReceiver" to commands.navReceiver.name, "viaGateway" to state.value.viaGateway))
        // Each element that stops answering is asked again then left out (MapTransfer.tick): the reading moves on
        // instead of stalling; the overall bound only covers a robot that keeps the link without ever finishing.
        mapJob = viewModelScope.launch {
            val end = SystemClock.elapsedRealtime() + MAP_READ_MAX_MS
            while (mapTransfer === transfer && SystemClock.elapsedRealtime() < end) {
                delay(250)
                if (mapTransfer !== transfer) return@launch
                val retries = transfer.tick(commands)
                retries.forEach { send(it, "MAP_RETRY") }
                mapProgress(transfer, retries.isNotEmpty())
            }
            if (mapTransfer !== transfer) return@launch
            mapTransfer = null
            val reason = tr("Map incomplete: ${MAP_READ_MAX_MS / 60_000} min timeout, restart the reading", "Carte incomplète : timeout ${MAP_READ_MAX_MS / 60_000} min, relancer la lecture")
            update { it.copy(mapLoading = false, mapReadFailure = reason, status = reason) }
            event("map_timeout", tr("Map not confirmed complete", "Carte non confirmée complète"), mapOf("source" to transfer.snapshot().source,
                "navReceiver" to commands.navReceiver.name, "navFrames" to transfer.navFrames, "navMessages" to transfer.navSummary))
        }
    }
    /** Records the reading steps and ends the reading when the map is complete or nothing more will come. */
    private fun mapProgress(transfer: MapTransfer, changed: Boolean) {
        val notices = transfer.notices()
        notices.forEach { event(it.kind, it.detail, it.fields) }
        if (!changed && notices.isEmpty() && !transfer.finished) return
        val snapshot = transfer.snapshot()
        update { it.withMammotionMap(snapshot) }
        if (snapshot.complete) {
            liveMapRead = true
            state.value.selected?.address?.let { id -> viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { mapCache.save(id, snapshot) }.onFailure { handler.post { event("map_cache_error", tr("Cache failure: ${it.javaClass.simpleName}", "Échec du cache : ${it.javaClass.simpleName}")) } } } }
            mapTransfer = null; update { it.copy(mapLoading = false, mapReadFailure = null) }; event("map_complete", snapshot.source); mapJob?.cancel()
        } else if (transfer.finished) {
            mapTransfer = null
            val reason = transfer.failure?.let { tr("Map incomplete: $it, restart the reading", "Carte incomplète : $it, relancer la lecture") }
                ?: tr("Map incomplete: ${transfer.missing} element(s) not read (${snapshot.source}), restart the reading", "Carte incomplète : ${transfer.missing} élément(s) non lu(s) (${snapshot.source}), relancer la lecture")
            update { it.copy(mapLoading = false, mapReadFailure = reason, status = reason) }
            event("map_incomplete", reason, mapOf("source" to snapshot.source, "missing" to transfer.missing,
                "navReceiver" to commands.navReceiver.name, "navFrames" to transfer.navFrames, "navMessages" to transfer.navSummary))
            mapJob?.cancel()
        }
    }
    private val scanAddresses = mutableSetOf<String>()
    private val scannerCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            if (!state.value.scanning) return@post
            try {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (scanAddresses.add(result.device.address)) event("scan_advertisement", tr("BLE advertisement", "Annonce BLE"), mapOf("name" to name, "address" to result.device.address, "rssiDbm" to result.rssi, "serviceUuids" to result.scanRecord?.serviceUuids?.map { it.toString() }))
                if (name == null) return@post
                // Every Mammotion mower is listed; only the validated models (Luba-VS, Luba-VP) leave read-only mode.
                if (!fr.luba.poc.protocol.ModelSupport.isMower(name)) return@post
                val device = SeenRobot(name, result.device.address, result.rssi)
                event("scan_result", tr("BLE robot detected", "Robot BLE détecté"), mapOf("name" to name, "address" to device.address,
                    "rssiDbm" to result.rssi, "advertisedServiceUuids" to result.scanRecord?.serviceUuids?.map { it.toString() }))
                update { it.copy(devices = (it.devices.filterNot { old -> old.address == device.address } + device).sortedBy { d -> d.name }) }
            } catch (error: Exception) { stopScan(); status(tr("Scan: ${error.javaClass.simpleName}", "Scan : ${error.javaClass.simpleName}")) }
        } }
        override fun onScanFailed(code: Int) { handler.post { stopScan(); status(tr("Failed BLE scan: $code", "Échec scan BLE : $code")) } }
    }
    /** BLE search; with [autoConnect], connects to the robot if it is the only one detected, otherwise asks for a choice. */
    fun scan(autoConnect: Boolean = false) {
        if (gatt != null || state.value.campaign.running || state.value.bladeTestActive) return
        val gateway = fr.luba.poc.gateway.GatewayConfig.read(getApplication())
        when (gateway.role) {
            // No valid gateway code yet: nothing to authenticate with, the robot is searched over direct Bluetooth.
            fr.luba.poc.gateway.GatewayRole.CLIENT -> if (gateway.validPilotCode()) scanThroughGateway(gateway, autoConnect) else scanDirect(autoConnect)
            fr.luba.poc.gateway.GatewayRole.GATEWAY -> {
                // Gateway phone: the link is held by the gateway service, not by this screen.
                val held = fr.luba.poc.gateway.GatewayService.status.value.robot
                if (held.address != null) status(tr("Gateway: ${held.label}", "Passerelle : ${held.label}"))
                else scanDirect(autoConnect, gatewayPhone = true)
            }
            fr.luba.poc.gateway.GatewayRole.OFF -> if (!autoConnect) scanDirect(false) else {
                // Direct Bluetooth: a gateway present on the Wi-Fi keeps priority over automatic connection,
                // otherwise this phone would take the robot's only Bluetooth link and the gateway would no longer see it.
                stopScan()
                update { it.copy(scanning = true, status = tr("Searching for the robot…", "Recherche du robot…")) }
                scanTimeout = viewModelScope.launch {
                    val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { fr.luba.poc.gateway.GatewayClient.discover(getApplication(), 2000) }.getOrNull()
                    }
                    scanTimeout = null
                    if (!state.value.scanning || gatt != null) return@launch
                    if (found != null) event("gateway_seen", tr("Gateway advertised on the Wi-Fi: no direct automatic connection", "Passerelle annoncée sur le Wi-Fi : pas de connexion automatique directe"), mapOf("host" to found.first))
                    update { it.copy(gatewaySeen = found?.first) }
                    scanDirect(found == null, gatewaySeen = found != null)
                }
            }
        }
    }
    /** The pilot switches to direct Bluetooth when its gateway is unreachable; reset as soon as it responds. */
    private var gatewayFallback = false
    private fun scanDirect(autoConnect: Boolean, gatewayPhone: Boolean = false, fallback: Boolean = false, gatewaySeen: Boolean = false) {
        val connectAuto = autoConnect
        try {
            if (adapter?.isEnabled != true) { stopScan(); status(tr("Turn on Android Bluetooth", "Activez le Bluetooth Android")); return }
            stopScan()
            scanAddresses.clear()
            val paired = adapter.bondedDevices.mapNotNull { device ->
                val name = device.name ?: return@mapNotNull null
                event("bonded_device", tr("Paired device", "Appareil appairé"), mapOf("name" to name, "address" to device.address, "type" to device.type))
                if (fr.luba.poc.protocol.ModelSupport.isMower(name)) SeenRobot(name, device.address, null) else null
            }
            update { it.copy(scanning = true, devices = if (gatewayPhone) emptyList() else paired,
                status = if (gatewayPhone) tr("Gateway: Bluetooth search for the robot (range check)…", "Passerelle : recherche Bluetooth du robot (vérification de portée)…") else tr("BLE search; ${paired.size} paired LUBA available", "Recherche BLE ; ${paired.size} LUBA appairé(s) disponible(s)")) }
            adapter.bluetoothLeScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scannerCallback)
            event("scan", tr("BLE detection for 15 seconds", "Détection BLE pendant 15 secondes"), mapOf("autoConnect" to connectAuto, "gatewayPhone" to gatewayPhone))
            autoConnectPending = connectAuto
            if (connectAuto) autoConnectCheck = viewModelScope.launch { delay(AUTO_CONNECT_SETTLE_MS); resolveAutoConnect(final = false) }
            scanTimeout = viewModelScope.launch {
                delay(15000)
                val auto = autoConnectPending
                stopScan()
                val found = state.value.devices.filter { it.rssi != null }
                status(when {
                    gatewayPhone && found.isEmpty() -> tr("Gateway: no LUBA visible. Robot off, out of range, or connected to another phone (Mammotion app, DumbyLuba over direct Bluetooth).", "Passerelle : aucun LUBA visible. Robot éteint, hors de portée, ou connecté à un autre téléphone (app Mammotion, DumbyLuba en Bluetooth direct).")
                    gatewayPhone -> tr("Gateway: ${found.joinToString { r -> "${r.name} (${r.rssi} dBm)" }} visible. Tap the robot to have the gateway connect to it.", "Passerelle : ${found.joinToString { r -> "${r.name} (${r.rssi} dBm)" }} visible(s). Toucher le robot pour que la passerelle s'y connecte.")
                    gatewaySeen -> tr("Gateway detected on the Wi-Fi: no direct automatic connection (the robot stays free for it). Use this gateway, or tap the robot to connect directly.", "Passerelle détectée sur le Wi-Fi : pas de connexion automatique directe (le robot reste libre pour elle). Utilisez cette passerelle, ou touchez le robot pour vous connecter directement.")
                    fallback && state.value.devices.isEmpty() -> tr("Gateway unreachable and no LUBA within direct Bluetooth range.", "Passerelle injoignable et aucun LUBA à portée Bluetooth directe.")
                    fallback -> tr("Gateway unreachable: direct Bluetooth, ${state.value.devices.size} LUBA available", "Passerelle injoignable : Bluetooth direct, ${state.value.devices.size} LUBA disponible(s)")
                    state.value.devices.isEmpty() -> fr.luba.poc.diagnostics.SetupCheck.notFoundHint(state.value.lastKnown?.robotName, viaGateway = false)
                    else -> tr("Detection finished: ${state.value.devices.size} LUBA available", "Détection terminée : ${state.value.devices.size} LUBA disponible(s)")
                })
                if (auto) resolveAutoConnect(final = true)
            }
        } catch (error: Exception) { stopScan(); status(tr("Scan: ${error.javaClass.simpleName}", "Scan : ${error.javaClass.simpleName}")) }
    }
    /** Search performed by the gateway phone mounted on the robot; same automatic resolution afterwards. */
    /** [directFallback]: gateway unreachable → direct Bluetooth search (search requested); false for automatic reconnection. */
    private fun scanThroughGateway(config: fr.luba.poc.gateway.GatewayConfig, autoConnect: Boolean, directFallback: Boolean = true) {
        stopScan()
        update { it.copy(scanning = true, devices = emptyList(), status = tr("Searching for the robot through the Wi-Fi gateway…", "Recherche du robot par la passerelle Wi-Fi…")) }
        event("scan", tr("BLE detection through the gateway", "Détection BLE par la passerelle"), mapOf("autoConnect" to autoConnect, "gateway" to config.host))
        autoConnectPending = autoConnect
        scanTimeout = viewModelScope.launch {
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { fr.luba.poc.gateway.GatewayClient.scan(getApplication(), config) }
            }
            if (!state.value.scanning) return@launch
            val auto = autoConnectPending
            (outcome.exceptionOrNull() as? fr.luba.poc.gateway.GatewayScanFailed)?.let { refused ->
                // Gateway reached but its Bluetooth search is impossible (Bluetooth off…): neither "unreachable" nor a
                // fallback to direct Bluetooth, which would hide the cause to fix on the gateway phone.
                gatewayFailures = 0; gatewayFallback = false
                scanTimeout = null
                stopScan()
                event("gateway_scan_failed", tr("Bluetooth search impossible on the gateway", "Recherche Bluetooth impossible sur la passerelle"), mapOf("error" to refused.message))
                status(tr("Gateway reached, but Bluetooth search impossible: ${refused.message}", "Passerelle jointe, mais recherche Bluetooth impossible : ${refused.message}"))
                return@launch
            }
            if (outcome.isFailure && !directFallback) {
                // Automatic reconnection: the robot stays free for the gateway, new attempt later.
                gatewayFailures++
                scanTimeout = null
                stopScan()
                val next = fr.luba.poc.gateway.GatewayRetry.delayMs(gatewayFailures) / 1000
                if (gatewayFailures == 1) event("gateway_retry_failed", tr("Gateway unreachable: automatic reconnection", "Passerelle injoignable : reconnexion automatique"),
                    mapOf("error" to outcome.exceptionOrNull()?.message))
                status(tr("Gateway unreachable (${outcome.exceptionOrNull()?.message ?: "?"}): new attempt in $next s", "Passerelle injoignable (${outcome.exceptionOrNull()?.message ?: "?"}) : nouvelle tentative dans $next s") +
                    fr.luba.poc.gateway.GatewayHealthStore.weakWifiHint())
                return@launch
            }
            if (outcome.isSuccess) {
                if (gatewayFailures > 0) event("gateway_retry_reached", tr("Gateway reached again", "Passerelle de nouveau jointe"), mapOf("failures" to gatewayFailures))
                gatewayFailures = 0
            }
            gatewayFallback = outcome.isFailure
            if (outcome.isFailure) {
                // Redmi absent or off: fall back to direct Bluetooth so control is not blocked.
                event("gateway_fallback", tr("Gateway unreachable: direct Bluetooth search", "Passerelle injoignable : recherche Bluetooth directe"), mapOf("error" to outcome.exceptionOrNull()?.message))
                scanTimeout = null
                scanDirect(auto, fallback = true)
                return@launch
            }
            outcome.onSuccess { found ->
                found.forEach { device -> event("scan_result", tr("Robot detected by the gateway", "Robot détecté par la passerelle"), mapOf("name" to device.name, "address" to device.address, "rssiDbm" to device.rssi)) }
                update { it.copy(devices = found.sortedBy { d -> d.name }) }
            }
            scanTimeout = null
            stopScan()
            status(outcome.fold({ if (it.isEmpty()) tr("Gateway reached. ", "Passerelle jointe. ") + fr.luba.poc.diagnostics.SetupCheck.notFoundHint(state.value.lastKnown?.robotName, viaGateway = true) else tr("Gateway: ${it.size} LUBA available", "Passerelle : ${it.size} LUBA disponible(s)") },
                { tr("Gateway unreachable: ${it.message ?: it.javaClass.simpleName}", "Passerelle injoignable : ${it.message ?: it.javaClass.simpleName}") }))
            if (auto && outcome.isSuccess) resolveAutoConnect(final = true)
        }
    }
    /**
     * After a few seconds of listening (then at the end of the search): a single robot actually received → connection;
     * several → ask for a choice. Robots that are only paired, with no advertisement received, do not count.
     */
    private fun resolveAutoConnect(final: Boolean) {
        if (!autoConnectPending && !final) return
        val s = state.value
        if (gatt != null || s.connection != ConnectionState.Disconnected || s.campaign.running || s.bladeTestActive) { autoConnectPending = false; return }
        val seen = s.devices.filter { it.rssi != null }
        when {
            seen.size == 1 -> {
                autoConnectPending = false
                event("auto_connect", tr("Automatic connection to the only robot detected", "Connexion automatique au seul robot détecté"), mapOf("name" to seen[0].name, "address" to seen[0].address))
                connect(seen[0])
            }
            seen.size > 1 -> {
                autoConnectPending = false
                event("auto_connect_choice", tr("Several robots detected: choice requested", "Plusieurs robots détectés : choix demandé"), mapOf("count" to seen.size))
                status(tr("Several robots detected: choose the one to connect", "Plusieurs robots détectés : choisissez celui à connecter"))
                robotChoice.tryEmit(Unit)
            }
            final -> autoConnectPending = false
        }
    }
    private fun stopScan() {
        scanTimeout?.cancel(); scanTimeout = null
        autoConnectCheck?.cancel(); autoConnectCheck = null; autoConnectPending = false
        try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch (_: Exception) { }
        update { it.copy(scanning = false) }
    }
    fun connect(device: SeenRobot) {
        if (gatt != null) return
        if (fr.luba.poc.gateway.GatewayConfig.read(getApplication()).role == fr.luba.poc.gateway.GatewayRole.GATEWAY) {
            // The gateway holds the link itself (kept alive, shared with the pilot phone).
            stopScan()
            fr.luba.poc.gateway.GatewayService.connectRobot(getApplication(), device.address, device.name)
            event("gateway_robot", tr("Robot handed over to the gateway", "Robot confié à la passerelle"), mapOf("name" to device.name, "address" to device.address))
            status(tr("Gateway: Bluetooth connection to ${device.name}; link kept alive and reconnected automatically (status in Service → Wi-Fi gateway)", "Passerelle : connexion Bluetooth à ${device.name} ; liaison maintenue et reconnectée automatiquement (état dans Service → Passerelle Wi-Fi)")); return
        }
        wantLink = true; unexpectedLoss = false; userDisconnected = false
        val recovering = alertLinkFault && state.value.selected?.address == device.address
        if(!recovering) intentionalAlertStop()
        alertLinkFault = recovering
        alertDeviceAt = null; alertRtkAt = null
        stopScan()
        liveMapRead = false
        robotErrors.reset()
        update { it.copy(teach = it.teach.copy(contactValidated = false)) }
        connectionId++; connectRequestedAt = SystemClock.elapsedRealtime()
        reportPrevious = 0L; positionPrevious = 0L; devicePrevious = 0L; driftOrigin = null
        blufi = Blufi(); commands = Commands.forRobot(device.name); reducer = TelemetryReducer(); bumperRest.reset(); contactThisConnection = false; liftGuard.reset(); motionWrittenAt = 0L; cameraObserver.reset(); if (sensorProbe.isRunning) stopSensorProbe()
        legs.forgetUploads(); remoteLegActive = false; gatewayOutageSince = 0L; update { it.copy(gatewayOutage = false) }
        watchdog = TelemetryWatchdog()
        bladeStopVerified = false; bladeOffWrittenAt = 0L; bladeDeviceAt = 0L
        lastTelemetry = 0L; closing = false; stopPending = false; closeAfterStop = false
        positionPreferences.edit().putString("id", device.address).apply()
        flushSurveys(); liveMap = null; liveLast = null
        update { it.copy(signalSurvey = surveyStore.read(device.address) ?: SignalSurvey(), surveySaveError = false, lastKnown = positionStore.read(device.address), positionSaveError = false, selected = device, mapReadFailure = null, productType = storedProductType(device.address), missionCheckpoint = it.missionCheckpoint?.takeIf { cp -> cp.robotId == device.address } ?: checkpointStore.read(device.address), pointResume = it.pointResume?.takeIf { p -> p.robotId == device.address } ?: pointResumeStore.read(device.address), motorWatchdog = watchdogResult(device.address), rssi = device.rssi, connection = ConnectionState.Connecting, telemetry = Telemetry(), bumperRest = 0, ageMs = null, ready = false, mtu = 23).withMammotionMap(mapCache.read(device.address) ?: RobotMap()) }
        stationReader = fr.luba.poc.map.StationReader(state.value.robotMap.station)
        event("connect", tr("Connecting ${device.name} ${device.address}", "Connexion ${device.name} ${device.address}"))
        try {
            val gateway = fr.luba.poc.gateway.GatewayConfig.read(getApplication())
            val factory = linkFactory
            gatt = if (factory != null) factory(device, linkListener)
                else if (gateway.role == fr.luba.poc.gateway.GatewayRole.CLIENT && !gatewayFallback) fr.luba.poc.gateway.GatewayLink.open(gateway, device.address, device.name, linkListener)
                else GattLink.open(getApplication(), adapter, device.address, linkListener)
            if (gatt == null) { status(tr("Bluetooth unavailable", "Bluetooth indisponible")); release(); return }
            update { it.copy(viaGateway = gatt is fr.luba.poc.gateway.GatewayLink) }
            event("link_opened", tr("Link ${gatt!!.description}", "Liaison ${gatt!!.description}"), mapOf("transport" to gatt!!.description))
            handshake = viewModelScope.launch { delay(15000); fail(handshakeTimeoutReason()) }
        } catch (error: Exception) { fail(tr("Connection: ${error.javaClass.simpleName}", "Connexion : ${error.javaClass.simpleName}")) }
    }

    /**
     * Connection timeout. Through a gateway that is still reachable (health received), it is the robot that does not
     * answer over Bluetooth (off, out of range): say so, rather than suggesting the gateway is down.
     */
    private fun handshakeTimeoutReason(): String {
        val reach = fr.luba.poc.gateway.GatewayHealthStore.reach.value
        return if (gatt is fr.luba.poc.gateway.GatewayLink && reach?.reachable == true && reach.fresh())
            tr("Robot not answering over Bluetooth (off or out of range of the gateway); gateway reachable", "Robot sans réponse en Bluetooth (éteint ou hors de portée de la passerelle) ; passerelle joignable")
        else tr("Connection / GATT discovery timeout", "Timeout connexion / découverte GATT")
    }
    private fun dispatch(source: RobotLink, block: () -> Unit) { handler.post {
        if (source !== gatt) return@post
        try { block() } catch (error: Exception) {
            event("exception", tr("BLE callback exception", "Exception callback BLE"), mapOf("type" to error.javaClass.name, "message" to error.message, "stack" to error.stackTraceToString()))
            fail(tr("BLE: ${error.message ?: error.javaClass.simpleName}", "BLE : ${error.message ?: error.javaClass.simpleName}"))
        }
    } }
    private val linkListener = object : RobotLink.Listener {
        override fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?>) = dispatch(link) {
            event(kind, text, fields + mapOf("sinceConnectMs" to SystemClock.elapsedRealtime() - connectRequestedAt, "closing" to closing))
            if (kind == "gateway_" + fr.luba.poc.gateway.EMERGENCY_STOP_EVENT) gatewayStopReported(text)
            if (kind == "gateway_" + fr.luba.poc.gateway.GatewayProtocol.OTA_BEGIN_EVENT) gatewayUpdateAnnounced(text)
            // Wi-Fi cut during autonomous tracking: the link is resumed, the robot carries on alone.
            if (kind == "gateway_outage") { gatewayOutageSince = SystemClock.elapsedRealtime(); update { it.copy(gatewayOutage = true) } }
            if (kind == "gateway_resumed") {
                gatewayOutageSince = 0L; gatewayResumedAt = SystemClock.elapsedRealtime()
                // Reception resumed at a message start: the BluFi assembler from before the cut is discarded.
                blufi = Blufi(); watchdog.recordValidReport(SystemClock.elapsedRealtime())
                update { it.copy(gatewayOutage = false) }
            }
        }
        override fun onLegFrame(link: RobotLink, type: Int, payload: ByteArray) = dispatch(link) { legs.onFrame(type, payload) }
        override fun onFailure(link: RobotLink, reason: String) = dispatch(link) {
            // Gateway being updated (writing, restart): unreachable for a few tens of seconds, no alert.
            if (link is fr.luba.poc.gateway.GatewayLink && !closing && fr.luba.poc.gateway.GatewayLink.gatewayUnreachable(reason) &&
                !gatewayUpdating()) alertGatewayLost(reason)
            fail(reason)
        }
        override fun onDiscovering(link: RobotLink) = dispatch(link) { update { it.copy(connection = ConnectionState.Discovering) } }
        override fun onLost(link: RobotLink, code: Int, reason: String) = dispatch(link) {
                if (!closing && gatewayUpdateRestart(link, code)) {
                    // Announced restart (update): the gateway has written the safety stop; not a failure.
                    unexpectedLoss = true
                    event("gateway_update_restart", tr("Gateway updating: link closed by it after stopping the robot", "Passerelle en mise à jour : liaison fermée par elle après l'arrêt du robot"),
                        mapOf("code" to code, "reason" to reason, "resume" to (gatewayUpdateResume?.isActive == true)))
                    status(if (gatewayUpdateResume?.isActive == true) tr("Gateway update: robot stopped, mowing resumes when it is back", "Mise à jour de la passerelle : robot arrêté, reprise de la tonte à son retour")
                        else tr("Gateway update: robot stopped, reconnecting when it is back", "Mise à jour de la passerelle : robot arrêté, reconnexion à son retour"))
                } else if (!closing) {
                    unexpectedLoss = true
                    if (link is fr.luba.poc.gateway.GatewayLink && fr.luba.poc.gateway.GatewayLink.gatewayDisconnected(code)) alertGatewayLost(reason.replaceFirstChar { it.uppercase() })
                    alertLinkFault = alertMonitor.armed
                    alertCommunicationExpected = alertMonitor.armed
                    // Loss of the gateway (Wi-Fi), not of the robot's Bluetooth: on 2026-10-14, "BLE lost" misled the analysis.
                    val lost = if (link is fr.luba.poc.gateway.GatewayLink && fr.luba.poc.gateway.GatewayLink.gatewayDisconnected(code)) tr("Gateway lost", "Perte passerelle") else tr("BLE lost", "Perte BLE")
                    taskEvent(fr.luba.poc.history.TaskEventType.LINK_LOST, "$lost: $reason", mapOf("code" to code))
                    alertRouteFailure(tr("$lost: return / resume interrupted", "$lost : retour / reprise interrompu"))
                    if(state.value.robotRoute.running || state.value.navTest.running || state.value.manualActive) alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, tr("$lost: physical STOP not guaranteed", "$lost : STOP physique non garanti"))
                    campaignFailure = tr("$lost unexpectedly ($code)", "$lost inattendue ($code)")
                    event("failure", campaignFailure!!, mapOf("code" to code, "stopTransmitted" to false))
                    update { it.copy(interruptions = it.interruptions + 1) }
                    status(tr("FAILED — $reason, STOP not guaranteed", "FAILED — $reason, STOP non garanti"))
                }
                release()
        }
        override fun onReady(link: RobotLink, mtu: Int) = dispatch(link) {
            update { it.copy(mtu = mtu) }
            if (closing) return@dispatch
            handshake?.cancel(); handshake = null
            started = SystemClock.elapsedRealtime()
            watchdog.start(started)
            update { it.copy(connection = ConnectionState.WaitingTelemetry, ready = true) }
            reconnectTimer?.cancel(); reconnectTimer = null
            readyAt = SystemClock.elapsedRealtime()
            fr.luba.poc.relay.RobotLinkService.start(getApplication())
            event("connected", tr("GATT ready, MTU ${state.value.mtu}", "GATT prêt, MTU ${state.value.mtu}"))
            send(commands.sync(), "sync(2)")
            send(reportsCommand(false), "RPT_START")
            send(commands.errorQuery(), "ERROR_BASELINE")
            // Internal model code: tells the H versions (high cut) apart, asked again until answered (3 times at most).
            send(commands.productModel(), "PRODUCT_MODEL")
            periodic = viewModelScope.launch {
                var productAsks = 1
                var nextProduct = started + 10_000
                var nextSync = started + 5000
                var nextKeep = started + 8000
                var nextRssi = started + 5000
                var nextSummary = started + 5000
                var previousTick = started
                try {
                while (!closing && gatt != null) {
                    delay(250)
                    val now = SystemClock.elapsedRealtime()
                    maxLoopDelayMs = maxOf(maxLoopDelayMs, now - previousTick)
                    previousTick = now
                    val since = now - if (lastTelemetry == 0L) started else lastTelemetry
                    update { it.copy(ageMs = if (lastTelemetry == 0L) null else since,
                        telemetry = it.telemetry.copy(positionRate = reducer.rate(now))) }
                    if (!state.value.gatewayOutage && watchdog.expired(now, state.value.timeoutSeconds * 1000L)) { fail(tr("FAILED — valid telemetry timeout", "FAILED — timeout télémétrie valide")); break }
                    // Gateway Wi-Fi cut held (autonomous tracking, recharging): keep-alive suspended, resumed on reconnection.
                    val outage = state.value.gatewayOutage
                    if (!outage && now >= nextProduct && productAsks < 3 && reducer.value.productType == null) { send(commands.productModel(), "PRODUCT_MODEL"); productAsks++; nextProduct = now + 10_000 }
                    if (!outage && now >= nextSync) { send(commands.sync(), "sync(2)"); nextSync = now + if (state.value.mapLoading) 1500 else 5000 }
                    if (!outage && now >= nextKeep) { send(reportsCommand(lastTelemetry != 0L, periodMs = reportPeriodMs()), if (lastTelemetry != 0L) "RPT_KEEP" else "RPT_START"); nextKeep = now + 8000 }
                    if (!outage && now >= nextRssi) {
                        // Gateway: Redmi ↔ robot RSSI (phone mounted on it, constant); Wi-Fi round trip avoided while moving.
                        val gatewayMotion = state.value.viaGateway && (autonomousRunning() || needsScreen())
                        if (!gatewayMotion) { queue.addLast(Operation.Rssi); pump() }
                        nextRssi = now + if (state.value.viaGateway) 30_000 else 5000
                    }
                    if (now >= nextSummary) { diagnosticSnapshot(now); nextSummary = now + 5000 }
                }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { fail(tr("BLE monitoring: ${error.message ?: error.javaClass.simpleName}", "Surveillance BLE : ${error.message ?: error.javaClass.simpleName}")) }
            }
        }
        override fun onWritten(link: RobotLink, code: Int) = dispatch(link) {
            val op = current as? Operation.Write ?: return@dispatch
            val latency = SystemClock.elapsedRealtime() - operationStarted
            writeTimes.add(latency)
            event("gatt_write_callback", tr("Write delay ${op.label}", "Délai écriture ${op.label}"), mapOf("statusCode" to code, "latencyMs" to latency,
                "label" to op.label, "batchId" to op.batch, "finalStop" to op.finalStop))
            if (code != BluetoothGatt.GATT_SUCCESS) { fail(tr("GATT write: $code", "Écriture GATT : $code")); return@dispatch }
            operationTimeout?.cancel(); operationTimeout = null; current = null
            update { it.copy(commandsSent = it.commandsSent + 1) }
            if (queue.none { it is Operation.Write && it.batch == op.batch }) {
                logicalCommandsCompleted++
                if (taskEvents != null) blackBox.command(fr.luba.poc.history.BlackBoxCommand(System.currentTimeMillis(), op.label, op.setpoint?.linear, op.setpoint?.angular))
                op.setpoint?.let {
                    val writtenAt = SystemClock.elapsedRealtime()
                    liftGuard.commanded(it.linear, writtenAt)
                    motionWrittenAt = if (it.zero) 0L else writtenAt
                    lastSetpoint = it; lastSetpointAt = writtenAt
                }
                routeBladeGate?.written(op.label,SystemClock.elapsedRealtime())
                if (op.label.startsWith("DIAGNOSTIC_ZERO")) zeroCommandsCompleted++
                event("command_completed", op.label, mapOf("batchId" to op.batch, "zeroCommandsCompleted" to zeroCommandsCompleted))
            }
            if (op.label == "BLADES_OFF" && queue.none { it is Operation.Write && it.batch == op.batch }) {
                bladeOffWrittenAt = SystemClock.elapsedRealtime()
                update { it.copy(bladeStatus = tr("Blades OFF written; wait for a fresh OFF state from the robot", "Lames OFF écrites ; attendre un état OFF frais du robot")) }
            }
            if(op.label=="NATIVE_TASK_ZERO") nativeCancelWrittenAt=SystemClock.elapsedRealtime()
            if(op.label=="UNLOCK_ERROR_QUERY") unlockWrittenAt=SystemClock.elapsedRealtime()
            event("write_completed", tr("GATT write confirmed ${op.label} (does not prove a mechanical stop)", "Écriture GATT confirmée ${op.label} (ne prouve pas l'arrêt mécanique)"))
            if (op.finalStop) {
                stopPending = false
                stopAckCount++
                event("stop_confirmed_gatt", tr("Zero STOP confirmed by GATT, not mechanically", "STOP nul confirmé par GATT, pas mécaniquement"), mapOf(
                    "latencyMs" to SystemClock.elapsedRealtime() - stopRequestedAt, "ackCount" to stopAckCount))
                persistCampaign()
                status(tr("$stopReason — zero-speed STOP written; check the physical stop", "$stopReason — STOP vitesse nulle écrit ; arrêt physique à vérifier"))
                if (closeAfterStop) { release(); return@dispatch }
            }
            pump()
        }
        override fun onRssi(link: RobotLink, rssi: Int, code: Int) = dispatch(link) {
            if (current != Operation.Rssi) return@dispatch
            operationTimeout?.cancel(); operationTimeout = null; current = null
            event("rssi_callback", "RSSI", mapOf("rssiDbm" to rssi, "statusCode" to code, "latencyMs" to SystemClock.elapsedRealtime() - operationStarted))
            if (code == BluetoothGatt.GATT_SUCCESS) { update { it.copy(rssi = rssi) } }
            pump()
        }
        override fun onNotification(link: RobotLink, bytes: ByteArray) = receive(link, bytes)
    }
    private fun receive(g: RobotLink, bytes: ByteArray) = dispatch(g) {
        if (closing || !state.value.ready) return@dispatch
        event("ble_rx", bytes.hex())
        val payload = blufi.receive(bytes) ?: return@dispatch
        // The legacy transport may also carry JSON; no watchdog refresh.
        if (payload.firstOrNull() == '{'.code.toByte()) { event("legacy_json", tr("BLE JSON received, not interpreted", "JSON BLE reçu, non interprété")); return@dispatch }
        val message = LubaMsg.parseFrom(payload)
        event("protobuf_decoded", "${message.msgtype} seq=${message.seqs}", mapOf("msgType" to message.msgtypeValue,
            "sender" to message.senderValue, "receiver" to message.rcverValue, "msgAttr" to message.msgattrValue,
            // Without `subtype`: the robot echoes the Mammotion account number there (DumbyLuba#3), only its presence is kept.
            "protobufSeq" to message.seqs, "robotTimestamp" to message.timestamp, "accountEcho" to (message.subtype != 0),
            "decoded" to message.toBuilder().clearSubtype().build().toString()))
        if (ignoreTelemetry && message.hasSys() && message.sys.hasToappReportData()) {
            event("fault_injection_drop", tr("Report hidden locally to test the watchdog; radio still active", "Rapport masqué localement pour tester le watchdog ; radio toujours active"))
            return@dispatch
        }
        robotErrors.accept(message).filter(::triageRobotError).forEach { error ->
            if (fr.luba.poc.diagnostics.LiftGuard.liftCode(error.code)) {
                val reason = tr("Lift reported by the robot: ${fr.luba.poc.diagnostics.LiftGuard.liftLabel(error.code)} (${error.code})", "Soulèvement signalé par le robot : ${fr.luba.poc.diagnostics.LiftGuard.liftLabel(error.code)} (${error.code})")
                if (liftGuard.moving(SystemClock.elapsedRealtime()) || autonomousRunning() || needsScreen()) liftDetected(reason, "error_code")
                else event("lift_reported", reason, mapOf("code" to error.code, "source" to error.source))
            }
            if(BumperEvents.confirmsContact(error) || BumperEvents.latchedFault(error)) update { it.copy(lastBumperCode = error.code, lastBumperAt = System.currentTimeMillis()) }
            if(BumperEvents.confirmsContact(error)) {
                contactThisConnection = true
                armObstaclePhoto(error.code)
                // Read long after it happened: the robot moved since, the contact is not placed at its current position.
                if (BumperEvents.placedLate(error, System.currentTimeMillis())) event("contact_late", tr("Bumper contact read late: not placed on the map", "Contact pare-chocs lu en retard : non placé sur la carte"),
                    mapOf("code" to error.code, "robotErrorTime" to error.timestamp, "ageS" to System.currentTimeMillis() / 1000 - (error.timestamp ?: 0L)))
                else if (manualContactContext()) recordManualContact(error.code) else recordContactOnReceipt(error.code)
            }
            if(BumperEvents.confirmsContact(error) || BumperEvents.latchedFault(error)) alertMonitor.bumper(SystemClock.elapsedRealtime(), BumperEvents.latchedFault(error)) { kind, severity -> alert(kind, severity, tr("Collision / robot fault ${error.code}", "Collision / défaut robot ${error.code}")) }

            if (state.value.robotRoute.running && !remoteLegActive) {
                val contact = BumperEvents.confirmsContact(error)
                // Proposal first: it uses the route's fused heading, cleared by the STOP.
                if (contact) proposeRouteCollision()
                // Bypass: immediate stop; the tracking turns the blades off, backs off and repairs the remaining route.
                val pose = if (contact && routeBypassArmed && routeContact == null) contactPose(error.code) else null
                if (contact && routeContact != null) {
                    // Bounce of the same contact before it is handled: stop repeated, bypass already planned.
                    purgeQueuedCommands(); send(commands.stop(), "ROUTE_CONTACT_STOP")
                } else if (pose != null) {
                    purgeQueuedCommands(); send(commands.stop(), "ROUTE_CONTACT_STOP")
                    routeContact = pose
                    event("route_contact", tr("Bumper contact (${error.code}): stopping, bypass being prepared", "Contact bumper (${error.code}) : arrêt, contournement en préparation"),
                        mapOf("code" to error.code, "x" to pose.x, "y" to pose.y, "heading" to pose.heading))
                } else requestStop(false, if (contact) tr("Bumper contact (${error.code}): robot stopped, blades OFF", "Contact bumper (${error.code}) : robot arrêté, lames OFF") else tr("Robot error ${error.code}", "Erreur robot ${error.code}"))
            }
            if (BumperEvents.latchedFault(error)) {
                bumperValidatedConnection=-1L
                update { it.copy(teach=it.teach.copy(contactValidated=false)) }
            }
            if (state.value.manualActive) {
                // Contact while driving: STOP, and the obstacle sheet (recorded above) replaces the critical alert.
                if (BumperEvents.confirmsContact(error)) stopManual(tr("Bumper contact (${error.code}): robot stopped, obstacle to check", "Contact pare-chocs (${error.code}) : robot arrêté, obstacle à vérifier"))
                else { alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, tr("Control stopped: robot error ${error.code}", "Pilotage arrêté : erreur robot ${error.code}")); stopManual(tr("Robot error ${error.code}", "Erreur robot ${error.code}")) }
            }
            event("robot_error", tr("Robot error ${error.code}", "Erreur robot ${error.code}"), mapOf("code" to error.code, "source" to error.source, "robotErrorTime" to error.timestamp))
            if (borderBumperWatch) {
                // Frontal test: contact is the expected result, immediate stop; any other error stops the test.
                if (BumperEvents.confirmsContact(error)) {
                    purgeQueuedCommands(); send(commands.stop(), "BORDER_CONTACT_STOP")
                    if (borderContactCode == null) borderContactCode = error.code
                } else if (borderContactCode == null && !BumperEvents.latchedFault(error)) borderErrorCode = error.code
            }
            if (state.value.teach.running && BumperEvents.confirmsContact(error)) {
                bumperValidatedConnection = connectionId
                update { it.copy(teach = it.teach.copy(contactValidated = true)) }
                finishObstacle("CONTACT_DETECTED", tr("Collision reported (${error.code}): STOP requested; check the physical stop", "Collision rapportée (${error.code}) : STOP demandé ; vérifier l'arrêt physique"))
            } else if (state.value.teach.running) finishObstacle("ABORTED", tr("Robot error ${error.code}", "Erreur robot ${error.code}"))
            if (state.value.navTest.running) {
                if (BumperEvents.confirmsContact(error)) {
                    // Move to a point with blades OFF: back-off, then a new path around the recorded obstacle.
                    val pose = if (navMapPlan?.physicalGuard == true && !navConfig.tonte && !navConfig.tracing && navCourse == null && navContact == null)
                        contactPose(error.code) else null
                    captureLocalIncident(error.code)
                    if (pose != null && localIncidentPending && state.value.navTest.running) navContact = pose
                    else stopNavTest(tr("Bumper contact (${error.code}): robot stopped, obstacle recorded", "Contact bumper (${error.code}) : robot arrêté, obstacle enregistré"))
                } else stopNavTest(tr("Robot error ${error.code}", "Erreur robot ${error.code}"))
            }
            if (state.value.mowing.running && (BumperEvents.confirmsContact(error) || BumperEvents.latchedFault(error))) {
                captureLocalIncident(error.code)
                requestStop(false, tr("Obstacle: native mission stopped, firmware route cannot be repaired locally", "Obstacle : mission native arrêtée, parcours firmware non réparable localement"))
            }
        }
        if (message.hasNav()) mapSyncReply(message.nav)
        mapTransfer?.let { transfer ->
            try {
                val replies = transfer.accept(message, commands)
                replies.forEach { send(it, "MAP_REQUEST_ACK") }
                mapProgress(transfer, replies.isNotEmpty())
            } catch (error: Exception) {
                mapJob?.cancel(); mapTransfer = null
                val reason = tr("Map: ${error.message}", "Carte : ${error.message}")
                update { it.copy(mapLoading = false, mapReadFailure = reason, status = reason) }; event("map_error", error.message ?: tr("Invalid map", "Carte invalide"))
            }
        }
        if (mapTransfer == null && state.value.mammotionMap.complete) {
            val station = stationReader.accept(message)
            if (station != null && station != state.value.mammotionMap.station) {
                // The cache only receives the Mammotion map, never the corrected map.
                val map = state.value.mammotionMap.copy(station = station)
                update { it.withMammotionMap(map) }
                state.value.selected?.address?.let { id ->
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { mapCache.save(id, map) } }
                }
            }
        } else stationReader.accept(message)
        if (cameraObserver.accept(message, System.currentTimeMillis())) {
            val camera = cameraObserver.value
            if (message.hasMul() && message.mul.hasSetVideoAck()) event("camera", tr("Camera: vi_switch ${fr.luba.poc.camera.CameraRobotState.ackLabel(camera.lastAck)}", "Caméra : vi_switch ${fr.luba.poc.camera.CameraRobotState.ackLabel(camera.lastAck)}"), mapOf("code" to camera.lastAck))
            update { it.copy(camera = camera) }
        }
        perceptionMaps.accept(message, System.currentTimeMillis(), reducer.value.robotState)?.let { frame ->
            update { it.copy(perceptionMap = frame) }
            event("perception_map", tr("Robot perception map: ${frame.info.label}", "Carte de perception du robot : ${frame.info.label}"),
                mapOf("format" to frame.info.format, "width" to frame.info.width, "height" to frame.info.height, "bytes" to frame.info.bytes,
                    "robotState" to frame.robotState, "count" to frame.count,
                    "imageBase64" to frame.image.takeIf { it.isNotEmpty() && it.size <= fr.luba.poc.diagnostics.PerceptionMapObserver.LOG_BYTES }
                        ?.let { java.util.Base64.getEncoder().encodeToString(it) }))
        }
        if (sensorProbe.isRunning) {
            val changed = sensorProbe.accept(message, System.currentTimeMillis())
            update { it.copy(sensorProbe = sensorProbe.snapshot()) }
            changed.forEach { (slot, raw) -> event("sensor_probe_slot", tr("${slot.label}: ${fr.luba.poc.diagnostics.SensorSlot.stateLabel(raw)}", "${slot.label} : ${fr.luba.poc.diagnostics.SensorSlot.stateLabel(raw)}"),
                mapOf("slot" to slot.name, "raw" to raw, "sensorStatus" to message.sys.toappReportData.dev.sensorStatus)) }
        }
        receiveMowing(message)
        val previousTelemetry = reducer.value
        val accepted = reducer.accept(message, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        if (accepted) {
            state.value.selected?.let { robot ->
                val known = (state.value.lastKnown ?: LastKnownPosition(robot.address, robot.name)).accept(reducer.value)
                update { it.copy(lastKnown = known) }
                // A single IO writer; on a burst, keep the most recent snapshot.
                positionWrites.trySend(known)
            }
        }
        reducer.value.productType?.takeIf { it != state.value.productType }?.let { code ->
            state.value.selected?.address?.let { id -> productPreferences.edit().putString(id, code).apply() }
            update { it.copy(productType = code) }
            event("robot_product_type", tr("Robot model: $code (${reducer.value.productName ?: "?"}), cutting height ${state.value.cutHeights.label}",
                "Modèle du robot : $code (${reducer.value.productName ?: "?"}), hauteur de coupe ${state.value.cutHeights.label}"),
                mapOf("productType" to code, "productName" to reducer.value.productName, "cutMinMm" to state.value.cutHeights.minMm, "cutMaxMm" to state.value.cutHeights.maxMm))
        }
        update { it.copy(telemetry = reducer.value) }
        if(message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasRtk()) alertRtkAt = SystemClock.elapsedRealtime()
        if(accepted && message.sys.toappReportData.hasDev()) alertDeviceAt = SystemClock.elapsedRealtime()
        if (message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasDev()) observeMowing()
        if (accepted) {
            lastTelemetry = SystemClock.elapsedRealtime()
            watchdog.recordValidReport(lastTelemetry)
            alertMonitor.report()
            alertLastTelemetryAt = lastTelemetry
            if (alertLinkFault && !alertMonitor.communicationLost) alert(AlertKind.RECOVERY, Severity.INFO)
            alertMonitor.condition(AlertKind.GATEWAY_LOST, null) { _, _ -> }
            alertLinkFault = false
            alertCommunicationExpected = true
            observeAlerts()
            if (message.sys.toappReportData.hasDev()) {
                devicePrevious = lastTelemetry; bladeDeviceAt = lastTelemetry
                observeBumperRest(reducer.value)
                if (state.value.teach.running && TeachSafety.bumperHealthAlert(guardSensors(reducer.value.sensorStatus)))
                    finishObstacle("ABORTED", tr("Bumper health alert: STOP requested; contact not confirmed", "Alerte de santé bumper : STOP demandé ; contact non confirmé"))
                if (state.value.navTest.running && TeachSafety.bumperHealthAlert(guardSensors(reducer.value.sensorStatus)))
                    stopNavTest(tr("Bumper health alert", "Alerte de santé bumper"))
                val bladeBits = (reducer.value.sensorStatus ?: 0) shr 9 and 7
                if (bladeOffWrittenAt > 0 && lastTelemetry >= bladeOffWrittenAt && bladeBits == 0) {
                    bladeStopVerified = true; bladeMayBeOn = false; bladeOffPending = false
                    update { it.copy(bladeStatus = tr("Blades OFF state received after the OFF command", "État lames OFF reçu après la commande OFF")) }
                    if (bladeTestId.isNotBlank() && !bladeMayBeOn) recordBladeResult(
                        if (bladeOnObserved) "ON_AND_OFF_REPORTED" else "OFF_REPORTED_ON_UNCONFIRMED",
                        tr("OFF reported after the command. ${if (bladeOnObserved) "ON state observed during the test." else "No ON state observed; rotation not demonstrated."}", "OFF rapporté après commande. ${if (bladeOnObserved) "État ON observé pendant le test." else "Aucun état ON observé ; rotation non démontrée."}"))
                }
                if (state.value.bladeTestActive) {
                    if (bladeBits == 1) bladeOnObserved = true
                    if (bladeBits > 1) stopBladeTest(tr("Raw blade state not recognized", "État lames brut non reconnu"))
                    if (reducer.value.robotState !in listOf(11, 20) || !fr.luba.poc.protocol.SelfCheck.nominal(reducer.value.selfCheck) || (guardSensors(reducer.value.sensorStatus) ?: 7) and 7 != 0) stopBladeTest(tr("Mammotion state/protection changed", "État/protection Mammotion changé"))
                }
            }
            reportCount++
            if (reportPrevious != 0L) reportGaps.add(lastTelemetry - reportPrevious)
            else event("first_telemetry", tr("First telemetry", "Première télémétrie"), mapOf("sinceConnectMs" to lastTelemetry - connectRequestedAt))
            reportPrevious = lastTelemetry
            if (message.sys.toappReportData.locationsCount > 0) {
                positionCount++
                if (positionPrevious != 0L) positionGaps.add(lastTelemetry - positionPrevious)
                positionPrevious = lastTelemetry
            }
            update { it.copy(telemetry = reducer.value, connection = ConnectionState.Receiving, ageMs = 0) }
            if (message.sys.toappReportData.locationsCount > 0) recordLiveSignal()
            val t = reducer.value
            val liftNow = SystemClock.elapsedRealtime()
            val sensorLift = t.sensorStatus?.let { liftGuard.sensors(it, liftNow) }
            val wheelLift = if (message.sys.toappReportData.locationsCount > 0 && t.x != null && t.y != null)
                liftGuard.position(Point(t.x, t.y), liftNow) else null
            (sensorLift ?: wheelLift)?.let { liftDetected(it, if (sensorLift != null) "sensor_status" else "wheel_progress") }
            if (sensorLift == null && wheelLift == null && liftGuard.slipping) recordDifficulty(DifficultyKind.SLIP,
                tr("Slipping: %.0f %% of the usual progress", "Patinage : %.0f %% de la progression habituelle").format(fr.luba.poc.i18n.I18n.locale, (liftGuard.progressRatio ?: 0.0) * 100))
            t.battery?.let { batteryMin = minOf(batteryMin ?: it, it) }
            if (previousTelemetry.positionType != null && t.positionType != previousTelemetry.positionType) positionTypeChanges++
            if (previousTelemetry.rtkStatus != null && t.rtkStatus != previousTelemetry.rtkStatus) rtkStatusChanges++
            if (t.x != null && t.y != null) {
                if (driftOrigin == null) driftOrigin = t.x to t.y
                driftOrigin?.let { origin -> maxStationaryDriftM = maxOf(maxStationaryDriftM, kotlin.math.hypot(t.x - origin.first, t.y - origin.second)) }
                // Applied to the trace current at update time: a pause or an erase requested meanwhile is kept.
                if (message.sys.toappReportData.locationsCount > 0 && state.value.driven?.paused == false) {
                    val at = System.currentTimeMillis()
                    val join = drivenJoin
                    var added = false
                    update { current -> current.driven?.let { trace ->
                        val next = DrivenRecording.add(trace, t.x, t.y, t.rtkFix, at, joinAfterPause = join)
                        added = next.points.size > trace.points.size
                        current.copy(driven = next)
                    } ?: current }
                    if (added) drivenJoin = false
                }
                if (state.value.navTest.running && message.sys.toappReportData.locationsCount > 0) {
                    val sample = NavSample(t.x, t.y, t.heading, SystemClock.elapsedRealtime() - navStarted)
                    val previous = state.value.navTest.trail.lastOrNull()
                    update { current ->
                        current.copy(navTest = current.navTest.copy(
                            trail = (current.navTest.trail + sample).takeLast(12000),
                            observedMeters = current.navTest.observedMeters +
                                (previous?.let { step -> kotlin.math.hypot(sample.x - step.x, sample.y - step.y) } ?: 0.0)))
                    }
                }
            }
            if (previousTelemetry.robotState != t.robotState || previousTelemetry.selfCheck != t.selfCheck || previousTelemetry.sensorStatus != t.sensorStatus) {
                event("robot_state_change", tr("State / protection change", "Changement état / protection"), mapOf("beforeState" to previousTelemetry.robotState,
                    "state" to t.robotState, "selfCheck" to t.selfCheck, "selfCheckName" to SelfCheck.name(t.selfCheck), "sensorStatus" to t.sensorStatus))
                // Undocumented protection (25, on 2026-10-14): request the self-check details (read-only, response
                // logged as protobuf_decoded). Never during tracking handed over to the gateway (no write from the pilot).
                if (previousTelemetry.selfCheck != t.selfCheck && StopUnlock.blocked(t.selfCheck) && !remoteLegActive)
                    send(commands.selfCheckInfo(), "SELF_CHECK_INFO")
                if (previousTelemetry.selfCheck != t.selfCheck && edgeLatchIgnored(t))
                    event("off_map_latch_ignored", tr("Self-check 25 beyond the mapped zones or at a no-go zone: ignored for blade-OFF moves", "Self-check 25 hors des zones cartographiées ou contre une zone interdite : ignoré pour les déplacements lames arrêtées"),
                        mapOf("x" to t.x, "y" to t.y, "sensorStatus" to t.sensorStatus))
            }
            event("telemetry", tr("State / position received", "État / position reçus"), mapOf("batteryPercent" to t.battery,
                "robotState" to t.robotState, "sensorStatus" to t.sensorStatus, "selfCheck" to t.selfCheck,
                "xMeters" to t.x, "yMeters" to t.y, "headingDegrees" to t.heading, "rawCompassHeadingDegrees" to t.rawHeading, "positionType" to t.positionType,
                "rtkStatus" to t.rtkStatus, "positioningLevel" to t.positioningLevel, "firmware" to t.firmware,
                "heightMoving" to t.heightMoving, "heightTargetMm" to t.heightTargetMm, "bladeHeightMm" to t.bladeHeightMm, "cutterRpm" to t.cutterRpm, "hasDeviceState" to message.sys.toappReportData.hasDev(), "hasPosition" to (message.sys.toappReportData.locationsCount > 0),
                "robotTimestamp" to message.timestamp, "positionRateHz" to t.positionRate))
        } else { event("protobuf", tr("Message ${message.msgtype} seq=${message.seqs} outside telemetry", "Message ${message.msgtype} seq=${message.seqs} hors télémétrie")) }
    }
    /** [periodic]: command re-sent at a fixed interval, not queued if the previous one is not yet written. */
    private fun send(payload: ByteArray, label: String, nativeReturn: Boolean = false, periodic: Boolean = false) {
        check(ExternalNavigationPolicy.allows(payload, nativeReturn)) { tr("Firmware navigation forbidden: use the local BLE route", "Navigation firmware interdite : utiliser le trajet BLE local") }
        if (gatt == null) return
        // Read-only mode (docs/features/supported-models.md): a model that is not validated only receives read and
        // stop commands. Last barrier, whatever the screen or the activity that built the command.
        val robotName = state.value.selected?.name
        // Model trial armed by the owner: the payloads of the three unlocking tests too, within the trial ceilings.
        val trialAllows = fr.luba.poc.protocol.ModelTrial.active(robotName, SystemClock.elapsedRealtime()) && fr.luba.poc.protocol.ReadOnlyPolicy.allowsTrial(payload, commands.navReceiver)
        if (!fr.luba.poc.protocol.ModelSupport.motionValidated(robotName) && !fr.luba.poc.protocol.ReadOnlyPolicy.allows(payload, commands.navReceiver) && !trialAllows) {
            readOnlyRefused++
            event("read_only_refused", tr("Command refused: robot model in read-only mode", "Commande refusée : modèle de robot en lecture seule"),
                mapOf("label" to label, "robotName" to robotName, "model" to fr.luba.poc.ui.RobotModel.detect(robotName).name,
                    "totalRefused" to readOnlyRefused, "payloadHex" to payload.hex()))
            fr.luba.poc.protocol.ModelSupport.refusal(robotName)?.let { status(it) }
            return
        }
        // Autonomous tracking: the gateway alone writes to the robot (link keep-alive included). Every stop order is
        // preceded by stopping the tracking (cancelRobotRoute), which lifts this hold.
        if (remoteLegActive) return
        if (queue.size > 64) { fail(tr("GATT queue saturated", "File GATT saturée")); return }
        if (periodic && MotionSetpoints.periodicPending(queue, current, label) { (it as? Operation.Write)?.label }) {
            periodicSkipped++
            event("periodic_skipped", tr("Periodic command still waiting to be written: not re-sent", "Commande périodique encore en attente d'écriture : non réémise"),
                mapOf("label" to label, "totalSkipped" to periodicSkipped, "queueDepth" to queue.size))
            return
        }
        // The lift guard counts setpoints actually written (onWritten), not those replaced in the queue.
        val setpoint = MotionSetpoints.of(payload)
        // Slow writes (gateway Wi-Fi): the new setpoint replaces the setpoints still pending.
        val activeBatch = (current as? Operation.Write)?.batch
        val batch = ++batchCounter
        val frames = blufi.fragment(payload, state.value.mtu).map { Operation.Write(it, label, batch, setpoint = setpoint) }
        // A setpoint takes the place of the one it replaces: at the end of the queue it fell behind the keep-alive.
        val replaced = MotionSetpoints.enqueue(queue, frames, setpoint, { (it as? Operation.Write)?.setpoint }, { (it as? Operation.Write)?.batch == activeBatch })
        if (replaced > 0) {
            setpointsReplaced += replaced
            event("setpoint_replaced", tr("Pending motion setpoint replaced by the most recent one", "Consigne de mouvement en attente remplacée par la plus récente"),
                mapOf("label" to label, "framesDropped" to replaced, "totalDropped" to setpointsReplaced))
        }
        if (label.startsWith("DIAGNOSTIC_ZERO")) zeroCommandsQueued++
        event("command_enqueued", label, mapOf("label" to label, "batchId" to batch, "payloadHex" to payload.hex()))
        pump()
    }
    private fun pump() {
        if (current != null) return
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        current = op
        operationStarted = SystemClock.elapsedRealtime()
        try {
            val accepted = when (op) {
                is Operation.Write -> {
                    val frame = blufi.forWrite(op.bytes)
                    event("ble_tx", op.label, mapOf("frameHex" to frame.hex(), "batchId" to op.batch, "queueWaitMs" to operationStarted - op.queuedAt))
                    g.write(frame)
                }
                Operation.Rssi -> g.readRssi()
            }
            check(accepted) { tr("GATT operation refused", "Opération GATT refusée") }
            val keepalive = state.value.viaGateway && fr.luba.poc.gateway.KeepaliveLoss.keepalive(op is Operation.Rssi, (op as? Operation.Write)?.label)
            operationTimeout = viewModelScope.launch {
                delay(fr.luba.poc.gateway.KeepaliveLoss.timeoutMs(state.value.viaGateway, keepalive, needsScreen(), autonomousRunning()))
                // Link keep-alive (sync, report subscription, RSSI) sent into a held gateway Wi-Fi cut: its confirmation
                // can only come back after the reconnection, which is awaited. Every other write keeps the strict timeout.
                val started = operationStarted
                while (current === op && !closing &&
                    fr.luba.poc.gateway.KeepaliveLoss.waiting(keepalive, started, SystemClock.elapsedRealtime(), gatewayOutageSince, gatewayResumedAt)) delay(500)
                if (current === op) {
                    // Reconnected without confirmation: the frame stayed in the cut link. Dropped; this is not a
                    // robot failure.
                    if (!closing && fr.luba.poc.gateway.KeepaliveLoss.dropped(keepalive, started, gatewayResumedAt)) {
                        event("gateway_keepalive_lost", tr("Link keep-alive lost in a gateway Wi-Fi cut: dropped", "Entretien de liaison perdu dans une coupure Wi-Fi de la passerelle : abandonné"),
                            mapOf("label" to ((op as? Operation.Write)?.label ?: "RSSI"), "waitMs" to SystemClock.elapsedRealtime() - started))
                        operationTimeout = null; current = null
                        pump()
                        return@launch
                    }
                    // Android may keep an operation busy: do not claim STOP can be sent.
                    val waited = tr("%s without confirmation in %.1f s", "%s sans confirmation en %.1f s").format(fr.luba.poc.i18n.I18n.locale, (op as? Operation.Write)?.label ?: "RSSI",
                        (SystemClock.elapsedRealtime() - started) / 1000.0)
                    if (closing) { status(tr("STOP not confirmed — GATT timeout ($waited); physical stop required", "STOP non confirmé — timeout GATT ($waited) ; arrêt physique requis")); release() }
                    else fail(tr("FAILED — GATT timeout ($waited), STOP possibly unavailable", "FAILED — timeout GATT ($waited), STOP potentiellement indisponible"))
                }
            }
        } catch (error: Exception) {
            if (closing) { status(tr("STOP not transmitted: ${error.javaClass.simpleName}", "STOP non transmis : ${error.javaClass.simpleName}")); release() }
            else fail(tr("GATT: ${error.javaClass.simpleName}", "GATT : ${error.javaClass.simpleName}"))
        }
    }
    fun stop() {
        wantLink = false; reconnectTimer?.cancel(); reconnectTimer = null
        intentionalAlertStop()
        abortCampaign(tr("Manual STOP", "STOP manuel"))
        if (closing) return
        requestStop(true, tr("Manual STOP", "STOP manuel"), intentional=true)
    }
    /** Disconnection requested by the user: automatic reconnection through the gateway waits for a new manual search. */
    private var userDisconnected = false
    /**
     * Control through the gateway: as long as the robot is not linked (gateway still unreachable, robot not yet
     * taken by the Redmi), restarts the automatic search so every mode unlocks as soon as the gateway holds it.
     */
    fun retryGatewayLink() {
        val s = state.value
        if (userDisconnected || gatt != null || s.scanning || s.connection != ConnectionState.Disconnected || s.campaign.running || s.bladeTestActive) return
        if (reconnectTimer?.isActive == true || closing) return
        val config = fr.luba.poc.gateway.GatewayConfig.read(getApplication())
        if (config.role != fr.luba.poc.gateway.GatewayRole.CLIENT || !config.validPilotCode()) return
        scanThroughGateway(config, autoConnect = true, directFallback = false)
    }
    /** Consecutive failures of the automatic reconnection to the gateway (spacing of attempts). */
    private var gatewayFailures = 0
    /**
     * Arms the trial of the connected model that is not validated ([fr.luba.poc.protocol.ModelTrial]): the three
     * unlocking tests only, for [fr.luba.poc.protocol.ModelTrial.DURATION_MS]. Returns the refusal, null once armed.
     */
    fun armModelTrial(): String? {
        val name = state.value.selected?.name
        if (!state.value.ready) return tr("Connect the robot first", "Connecter d'abord le robot")
        return try {
            val sensors = state.value.telemetry.sensorStatus
            val armed = fr.luba.poc.protocol.ModelTrial.arm(name, SystemClock.elapsedRealtime(), sensors)
            event("model_trial_armed", tr("Model trial armed: command cutoff, low-speed joystick and blade test only", "Essai du modèle armé : coupure des commandes, joystick lent et essai des lames seulement"),
                mapOf("robotName" to name, "model" to fr.luba.poc.ui.RobotModel.detect(name).name, "firmware" to state.value.telemetry.firmware,
                    "durationMs" to fr.luba.poc.protocol.ModelTrial.DURATION_MS, "maxLinear" to fr.luba.poc.protocol.ModelTrial.MAX_LINEAR,
                    "maxAngular" to fr.luba.poc.protocol.ModelTrial.MAX_ANGULAR, "untilMs" to armed.untilMs,
                    "sensorStatus" to sensors, "bumperRest" to armed.bumperRest))
            null
        } catch (error: IllegalArgumentException) { error.message }
    }

    /**
     * `sensor_status` seen by the safety guards: bumper slot at its resting value cleared, learnt on this connection on a
     * validated model ([fr.luba.poc.protocol.BumperRest], DumbyLuba#1), or kept by the armed trial of another model
     * ([fr.luba.poc.protocol.ModelTrial.sensors]). The raw value stays in the telemetry, the log and Service → Sensors.
     */
    private fun guardSensors(sensors: Int?): Int? =
        fr.luba.poc.protocol.BumperRest.guard(state.value.selected?.name, sensors, bumperRest.value, SystemClock.elapsedRealtime())

    /**
     * Bumper slot at rest on a validated model ([fr.luba.poc.protocol.BumperRest]): learnt when it reads "alert" (1) from
     * the connection on, robot ready, no collision lock nor contact, no motion driven by the app; forgotten for the rest
     * of the connection as soon as it reads another value. Logged with the raw `sensor_status`.
     */
    private fun observeBumperRest(t: fr.luba.poc.protocol.Telemetry) {
        if (!fr.luba.poc.protocol.ModelSupport.motionValidated(state.value.selected?.name)) return
        val idle = !motionActive() && !state.value.borderFrontal.running && !remoteLegActive
        when (bumperRest.observe(t.sensorStatus, t.robotState, t.selfCheck, idle, contactThisConnection)) {
            fr.luba.poc.protocol.BumperRest.Change.LEARNT -> {
                update { it.copy(bumperRest = bumperRest.value) }
                event("bumper_rest_learnt", tr("Bumper reads \"alert\" at rest since the connection, no contact: kept as this robot's resting value for the connection",
                    "Pare-chocs en « alerte » au repos depuis la connexion, sans contact : gardé comme valeur de repos du robot pour la connexion"),
                    mapOf("sensorStatus" to t.sensorStatus, "bumperRest" to bumperRest.value, "robotState" to t.robotState, "selfCheck" to t.selfCheck))
            }
            fr.luba.poc.protocol.BumperRest.Change.FORGOTTEN -> {
                update { it.copy(bumperRest = 0) }
                event("bumper_rest_forgotten", tr("Bumper value changed: resting value forgotten until the next connection",
                    "Valeur du pare-chocs changée : valeur de repos oubliée jusqu'à la prochaine connexion"),
                    mapOf("sensorStatus" to t.sensorStatus, "robotState" to t.robotState, "selfCheck" to t.selfCheck, "contact" to contactThisConnection))
            }
            null -> Unit
        }
    }

    /** Ends the model trial (button, disconnection); a running test stops through its own guards. */
    fun endModelTrial(reason: String) {
        val ended = fr.luba.poc.protocol.ModelTrial.end() ?: return
        stopManual(tr("Model trial ended", "Essai du modèle terminé"))
        stopSilenceTest(tr("Model trial ended", "Essai du modèle terminé"))
        if (state.value.bladeTestActive) stopOwnedBladeTest(tr("Model trial ended", "Essai du modèle terminé"))
        event("model_trial_ended", reason, mapOf("robotName" to ended.robotName, "durationMs" to SystemClock.elapsedRealtime() - ended.sinceMs))
    }

    fun disconnect() {
        endModelTrial(tr("Disconnection", "Déconnexion"))
        userDisconnected = true
        gatewayUpdateResume?.cancel(); gatewayUpdateResume = null
        wantLink = false; reconnectTimer?.cancel(); reconnectTimer = null
        intentionalAlertStop()
        abortCampaign(tr("User disconnection", "Déconnexion utilisateur"))
        stopScan()
        if (gatt == null || closing) return
        requestStop(true, tr("Disconnection requested", "Déconnexion demandée"), intentional=true)
    }
    /** Activities that require the operator on screen (hold, joystick): stopped in the background. */
    private fun needsScreen(): Boolean {
        val s = state.value
        return s.manualActive || s.teach.running || s.bladeTestActive || s.campaign.running || s.silenceTest.running ||
            (s.navTest.running && !navConfig.automatic)
    }
    /** Supervised automatic missions: continued in the background as long as the link keeps them. */
    private fun autonomousRunning(): Boolean {
        val s = state.value
        return s.robotRoute.running || s.mowing.running || (s.navTest.running && navConfig.automatic)
    }
    fun background(keepConnection: Boolean = false) {
        event("lifecycle", tr("Application in the background", "Application hors premier plan"))
        stopScan()
        val keep = (keepConnection || fr.luba.poc.relay.RobotLinkService.active.value) && gatt != null && !closing && state.value.ready
        if (keep) {
            // Link kept by the foreground service: only hold-to-run activities stop.
            if (needsScreen()) {
                intentionalAlertStop()
                abortCampaign(tr("Application in the background", "Application hors premier plan"))
                requestStop(false, tr("Application in the background: hold-to-run activity stopped", "Application hors premier plan : activité à maintien arrêtée"), intentional=true)
            } else if (autonomousRunning()) {
                event("lifecycle", tr("Automatic mission continued in the background; link kept by the service", "Mission automatique poursuivie en arrière-plan ; liaison maintenue par le service"))
            }
            return
        }
        intentionalAlertStop()
        abortCampaign(tr("Application in the background", "Application hors premier plan"))
        // A relay connection may still be negotiating GATT when the screen turns off.
        // No trial can run before ready; leave its handshake alive in relay mode.
        if (gatt != null && !closing && (!keepConnection || state.value.ready)) {
            requestStop(!keepConnection, tr("Application in the background", "Application hors premier plan"), intentional=true)
        }
    }
    private fun fail(reason: String) {
        if (closing) return
        unexpectedLoss = true
        alertLinkFault = alertMonitor.armed
        alertCommunicationExpected = alertMonitor.armed
        if (expectedFailure == reason && state.value.campaign.running) {
            expectedFaultObserved = SystemClock.elapsedRealtime()
            event("expected_fault", tr("Expected stop on local injection", "Arrêt attendu sur injection locale"), mapOf("reason" to reason, "timeoutSeconds" to state.value.timeoutSeconds))
        } else {
            campaignFailure = reason
            update { it.copy(interruptions = it.interruptions + 1) }
            event("failure", reason)
        }
        requestStop(true, reason)
    }
    /** Tracking running on the gateway: stop it first (it stops the robot), before any stop order from here. */
    private fun releaseRemoteLeg() {
        if (remoteLegActive || legs.current != null) {
            remoteLegActive = false
            legs.current?.abort()
            // Wi-Fi cut at that moment: keep the link a little longer so the stop order still gets through.
            (gatt as? fr.luba.poc.gateway.GatewayLink)?.holdUntil = System.currentTimeMillis() + ABORT_HOLD_MS
        }
    }
    private fun cancelRobotRoute(reason: String) {
        rechargeCycle = false
        historyEnd(reason, completed = false)
        releaseRemoteLeg()
        flushSurveys()
        routeJob?.cancel(); routeJob = null
        routeCheckpoint?.let { checkpoint ->
            routeCheckpoint = null; routePlan = null
            persistCheckpoint(checkpoint.copy(interruption = reason, updatedAt = System.currentTimeMillis()), force = true)
            event("mission_checkpoint_interrupted", tr("Mowing interrupted: progress kept for resume", "Tonte interrompue : avancement conservé pour reprise"),
                mapOf("reason" to reason, "edge" to checkpoint.edge, "progress" to checkpoint.progress))
        }
        if (state.value.robotRoute.running) update { it.copy(robotRoute = it.robotRoute.copy(running=false,phase=if(it.robotRoute.phase==RoutePhase.COMPLETE) RoutePhase.COMPLETE else RoutePhase.STOPPED,status=reason)) }
        // Difficulties of the finished route: presumed obstacles and avoided zones for the next routes.
        publishDifficulties(state.value.difficulties.events, persist = false)
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
        trailWrites.trySend(TrailWrite(id, 0L, null)); routeTrailMission = null
        event("mission_checkpoint_discarded", tr("Mowing progress abandoned by the user", "Avancement de tonte abandonné par l'utilisateur"))
    }

    private var routeCheckpoint: MissionCheckpoint? = null
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
        if (!force && now - checkpointSavedAt < 5000 && kotlin.math.abs(checkpoint.doneMeters - checkpointSavedMeters) < 2.0) return
        checkpointSavedAt = now; checkpointSavedMeters = checkpoint.doneMeters
        checkpointWrites.trySend(checkpoint.robotId to checkpoint)
        // Mowing trail: at most every 15 s, immediately at start, on resume and on interruption.
        if (routeTrailMission == checkpoint.startedAt && (force || now - trailSavedAt >= 15_000)) {
            trailSavedAt = now
            trailWrites.trySend(TrailWrite(checkpoint.robotId, checkpoint.startedAt, state.value.robotRoute.trail))
        }
    }

    /** Position measured on edge [routeIndex] of the main route. */
    private fun recordMissionProgress(routeIndex: Int, position: Point) {
        val checkpoint = routeCheckpoint ?: return
        val plan = routePlan ?: return
        if (routeIndex >= plan.mowingTo) { finishCheckpoint(tr("All mowing stripes covered", "Toutes les bandes de tonte parcourues")); return }
        val edge = plan.engineEdge(routeIndex) ?: return
        SmartResume.advance(checkpoint, routeEngineEdges, edge, position, System.currentTimeMillis())?.let { next ->
            routeCheckpoint = next; persistCheckpoint(next)
        }
    }

    private fun finishCheckpoint(reason: String) {
        historyMowingDone()
        val checkpoint = routeCheckpoint ?: return
        routeCheckpoint = null; routePlan = null
        // Stripes finished: the bar shows 100 % during the return to base.
        update { it.copy(missionCheckpoint = null, robotRoute = it.robotRoute.copy(cutProgress = it.robotRoute.cutProgress?.let { 1.0 })) }
        checkpointWrites.trySend(checkpoint.robotId to null)
        trailWrites.trySend(TrailWrite(checkpoint.robotId, checkpoint.startedAt, null))
        event("mission_checkpoint_done", reason, mapOf("resumes" to checkpoint.resumes, "rechargeCycles" to checkpoint.rechargeCycles))
    }
    /**
     * Start (or resume) of the measurement of a real mowing: estimate frozen with the actual battery and
     * the calibration from previous mowings. A resume extends the record of the interrupted mowing;
     * a resume without a record (mowing older than the history) is not measured.
     */
    private fun historyBegin(engine: MowingSimulation, checkpoint: MissionCheckpoint?, resumed: Boolean, battery: Int?,
                             kind: fr.luba.poc.history.TaskKind = fr.luba.poc.history.TaskKind.MOW, heightMm: Int = checkpoint?.heightMm ?: 60) {
        historyJob?.cancel(); historyJob = null
        trackStop()
        val robotId = checkpoint?.robotId ?: state.value.selected?.address ?: return
        val startedAt = checkpoint?.startedAt ?: System.currentTimeMillis()
        val id = "$robotId-$startedAt"
        val history = state.value.mowingHistory
        val record = if (resumed && checkpoint != null) history.firstOrNull { it.id == id }?.let { r -> r.copy(outcome = fr.luba.poc.history.MowingOutcome.RUNNING,
            reason = null, endedAt = null, resumes = checkpoint.resumes, heightMm = checkpoint.heightMm, doneMeters = checkpoint.doneMeters,
            links = r.links + trackingLink()) } ?: return
        else {
            val c = engine.config
            val zones = engine.map.shapes.filter { it.kind == ShapeKind.ZONE && it.id in engine.preview.zoneIds }
            val forecast = runCatching { fr.luba.poc.history.MowingForecaster.forecast(engine.preview.meters, c.speedMps, c.autonomyMinutes,
                c.chargeMinutes, (battery ?: c.initialBattery.toInt()).toDouble(), c.returnPercent, c.resumePercent,
                fr.luba.poc.history.MowingCalibration.from(history, robotId)) }.getOrElse { return }
            // Survey or route with blades stopped: progress follows the distance of the prepared route.
            fr.luba.poc.history.MowingRecord(id, robotId, state.value.selected?.name, startedAt,
                zoneNames = engine.preview.zoneIds.mapNotNull { zid -> zones.firstOrNull { it.id == zid }?.name?.trim()?.ifBlank { null } },
                areaM2 = fr.luba.poc.map.CoveragePlanner.selectedArea(engine.map, engine.preview.zoneIds.toSet()), mode = engine.preview.mode.label,
                heightMm = heightMm, speedMps = c.speedMps, returnPercent = checkpoint?.returnPercent ?: c.returnPercent.toInt(),
                resumePercent = checkpoint?.resumePercent ?: c.resumePercent.toInt(),
                plannedMeters = engine.preview.meters, cuttingMeters = checkpoint?.totalMeters ?: engine.preview.meters,
                doneMeters = checkpoint?.doneMeters ?: 0.0, forecast = forecast, batteryStart = battery, batteryEnd = battery, kind = kind,
                links = setOf(trackingLink()))
        }
        orientationPlan = if (kind == fr.luba.poc.history.TaskKind.MOW) engine.preview to engine.map else null
        trackBegin(id, engine, kind == fr.luba.poc.history.TaskKind.MOW)
        taskEvent(if (resumed) fr.luba.poc.history.TaskEventType.RESUME else fr.luba.poc.history.TaskEventType.START,
            "${record.kind.label} · ${record.mode} · ${record.zoneNames.joinToString(", ")}",
            mapOf("mode" to record.mode, "zones" to record.zoneNames.size, "link" to trackingLink().name, "progress" to record.progress))
        historyTracker = fr.luba.poc.history.MowingTracker(record.totals)
        historyRechargeBase = record.recharges
        historySavedAt = 0L
        update { it.copy(mowingRecord = record) }
        historyPersist(record)
        event("mowing_history_start", if (resumed) tr("Mowing measurement resumed", "Reprise de la mesure de tonte") else tr("Mowing measurement started", "Mesure de tonte démarrée"),
            mapOf("id" to id, "forecastSeconds" to record.forecast.totalSeconds, "forecastRecharges" to record.forecast.recharges,
                "timeFactor" to record.forecast.timeFactor, "autonomyMinutes" to record.forecast.autonomyMinutes,
                "chargeMinutes" to record.forecast.chargeMinutes, "calibratedFrom" to record.forecast.calibratedFrom,
                "trackingLink" to trackingLink().name) + wetnessFields())
        historyJob = viewModelScope.launch { while (true) { historySample(); delay(2000) } }
    }

    /** Robot position (rotation centre, as in the track) and battery for a task event. */
    private fun taskPose(): Triple<Double?, Double?, Int?> {
        val t = state.value.telemetry
        val center = if (t.x != null && t.y != null) DeadReckoning.rotationCenter(Point(t.x, t.y), t.heading) else null
        return Triple(center?.x, center?.y, t.battery)
    }

    private fun taskEventWrite(event: fr.luba.poc.history.TaskEvent?) {
        val recorder = taskEvents ?: return
        if (event == null) return
        trackWrites.trySend(TrackWrite(recorder.taskId, events = listOf(event)))
        // Incident: black box frozen a few seconds later, so that it shows what followed (stop, recovery).
        if (event.type.incident && blackBoxTrigger.incident(recorder.taskId, event))
            handler.postDelayed({ blackBoxFreeze() }, fr.luba.poc.history.BlackBoxRecorder.AFTER_MS)
    }

    private fun blackBoxFreeze() {
        val pending = blackBoxTrigger.take() ?: return
        val box = blackBox.freeze(pending.atMs, pending.types, pending.detail)
        trackWrites.trySend(TrackWrite(pending.taskId, boxes = listOf(box)))
        event("task_black_box", tr("Black box frozen: ${pending.types.joinToString(" + ") { it.label }}", "Black Box figée : ${pending.types.joinToString(" + ") { it.label }}"),
            mapOf("frames" to box.frames.size, "commands" to box.commands.size, "notes" to box.notes.size))
    }

    /** Current state of the robot and of the link, for the black box. */
    private fun blackBoxFrame(t: fr.luba.poc.protocol.Telemetry, command: MotionSetpoints.Setpoint?): fr.luba.poc.history.BlackBoxFrame {
        val center = if (t.x != null && t.y != null) DeadReckoning.rotationCenter(Point(t.x, t.y), t.heading) else null
        return fr.luba.poc.history.BlackBoxFrame(System.currentTimeMillis(), center?.x ?: Double.NaN, center?.y ?: Double.NaN, t.heading ?: Double.NaN,
            command?.linear, command?.angular, trackLastSpeed, t.battery ?: -1, t.robotState ?: -1, t.sensorStatus ?: -1, t.rtkStatus ?: -1,
            t.gpsStars ?: -1, trackLinkState(), state.value.robotRoute.phase.name, t.bleRssi, t.wifiRssi)
    }

    /** Event of the running task signalled directly (start, end, STOP, link lost, obstacle). */
    private fun taskEvent(type: fr.luba.poc.history.TaskEventType, detail: String, data: Map<String, Any?> = emptyMap()) {
        val recorder = taskEvents ?: return
        val (x, y, battery) = taskPose()
        taskEventWrite(recorder.record(fr.luba.poc.history.TaskEvent(System.currentTimeMillis(), type, detail, x ?: Double.NaN, y ?: Double.NaN,
            battery ?: -1, data.mapNotNull { (k, v) -> v?.let { k to it.toString() } }.toMap())))
    }

    /** Link state for the track: autonomous tracking by the gateway (Wi-Fi cut or not), relay or direct Bluetooth. */
    private fun trackLinkState(): fr.luba.poc.history.TrackLinkState = when {
        gatt == null -> fr.luba.poc.history.TrackLinkState.UNKNOWN
        remoteLegActive && gatewayOutageSince != 0L -> fr.luba.poc.history.TrackLinkState.GATEWAY_OUTAGE
        remoteLegActive -> fr.luba.poc.history.TrackLinkState.GATEWAY
        gatt is fr.luba.poc.gateway.GatewayLink -> fr.luba.poc.history.TrackLinkState.RELAY
        else -> fr.luba.poc.history.TrackLinkState.BLUETOOTH
    }

    /**
     * Start (or resume) of the track of task [id]: context (map, prepared route) recorded once, then
     * each new position received, filtered by [fr.luba.poc.history.TrackSampler] and written in batches.
     */
    private fun trackBegin(id: String, engine: MowingSimulation, mowing: Boolean) {
        trackStop()
        taskEvents = fr.luba.poc.history.TaskEventRecorder(id)
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
        trackWrites.trySend(TrackWrite(id, meta = fr.luba.poc.history.TrackMeta(engine.map, engine.preview.cuttingPaths, transit, engine.preview.zoneIds)))
        trackSampler = fr.luba.poc.history.TrackSampler()
        trackPositionAt = 0L; trackFlushedAt = SystemClock.elapsedRealtime()
        trackJob = viewModelScope.launch { while (true) { trackSample(id); delay(200) } }
    }

    /** Robot activity for the track: recharging and return first, then blades according to the telemetry. */
    private fun trackPhase(t: fr.luba.poc.protocol.Telemetry): fr.luba.poc.history.TrackPhase {
        val phase = state.value.robotRoute.phase
        return when {
            t.chargeState in setOf(1, 2) || phase == RoutePhase.CHARGING -> fr.luba.poc.history.TrackPhase.CHARGING
            phase == RoutePhase.RETURNING || phase == RoutePhase.ALIGNING -> fr.luba.poc.history.TrackPhase.RETURN
            phase == RoutePhase.DOCKING -> fr.luba.poc.history.TrackPhase.DOCKING
            fr.luba.poc.ui.MowingLiveStats.bladeBits(t.sensorStatus).let { it == 1 || (it == null && bladeMayBeOn) } -> fr.luba.poc.history.TrackPhase.CUTTING
            else -> fr.luba.poc.history.TrackPhase.TRAVEL
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
            val sample = fr.luba.poc.history.TrackSample(System.currentTimeMillis(), center.x, center.y, t.heading ?: Double.NaN,
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
    private var coverageBuilder: fr.luba.poc.history.CoverageBuilder? = null
    private var coverageTask: String? = null
    private var coveragePublishedAt = 0L

    private fun coverageBegin(id: String, engine: MowingSimulation, mowing: Boolean) {
        coverageBuilder = null; coverageTask = null
        if (!mowing) return
        val builder = fr.luba.poc.history.CoverageBuilder(engine.map, engine.preview.zoneIds)
        coverageBuilder = builder; coverageTask = id
        coveragePublish(force = true)
        // Resume: the positions saved before the interruption are swept too.
        viewModelScope.launch {
            val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { trackStore.read(id)?.samples }.getOrNull() }
            if (saved.isNullOrEmpty() || coverageBuilder !== builder) return@launch
            builder.addRun(saved)
            coveragePublish(force = true)
        }
    }

    private fun coverageAdd(id: String, sample: fr.luba.poc.history.TrackSample) {
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
        fr.luba.poc.history.RealCoverage.publish(builder.snapshot()?.let { fr.luba.poc.history.CoverageView(id, it, running) })
    }

    private fun coverageEnd() {
        coveragePublish(force = true, running = false)
        coverageBuilder = null; coverageTask = null
    }

    /** At launch: coverage of the latest mowing with a recorded track, until a new mowing starts. */
    private suspend fun coverageRestore(history: List<fr.luba.poc.history.MowingRecord>) {
        val last = history.filter { it.kind == fr.luba.poc.history.TaskKind.MOW && it.finished }.maxByOrNull { it.startedAt } ?: return
        val coverage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { trackStore.read(last.id)?.takeIf { it.meta != null }?.let { fr.luba.poc.history.CoverageMap.of(it) } }.getOrNull()
        } ?: return
        handler.post { fr.luba.poc.history.RealCoverage.restore(fr.luba.poc.history.CoverageView(last.id, coverage, running = false)) }
    }

    /** Wet grass in the log (mowing start and report): data to recalibrate [fr.luba.poc.weather.GrassWetness.WET_SLIDE_GAIN]. */
    private fun wetnessFields(): Map<String, Any?> = grassWetness().let { w ->
        mapOf("grassWetness" to w?.index, "grassWetnessReason" to w?.reason, "rainWeightedMm" to w?.rainMm, "wetSlideFactor" to wetSlideFactor(),
            "slideCells" to slideField.size) }

    /** Time and battery sample; saved at most every 30 s. */
    private fun historySample() {
        val record = state.value.mowingRecord ?: return
        val tracker = historyTracker ?: return
        val t = state.value.telemetry
        val totals = tracker.sample(SystemClock.elapsedRealtime(), t.battery, t.chargeState in setOf(1, 2))
        val route = state.value.robotRoute
        // Saved progress (any coverage task); otherwise, the share of the route travelled.
        val done = routeCheckpoint?.doneMeters ?: if (record.kind == fr.luba.poc.history.TaskKind.MOW) 0.0
            else if (route.count > 0) record.cuttingMeters * route.index.coerceIn(0, route.count) / route.count else 0.0
        val next = record.copy(totals = totals, recharges = historyRechargeBase + route.rechargeCycles,
            doneMeters = maxOf(record.doneMeters, done), batteryEnd = t.battery ?: record.batteryEnd)
        update { it.copy(mowingRecord = next) }
        if (SystemClock.elapsedRealtime() - historySavedAt >= 30_000) historyPersist(next)
    }

    private fun historyPersist(record: fr.luba.poc.history.MowingRecord) {
        historySavedAt = SystemClock.elapsedRealtime()
        val records = fr.luba.poc.history.MowingHistoryStore.upsert(state.value.mowingHistory, record)
        update { it.copy(mowingHistory = records) }
        historyWrites.trySend(records)
    }

    /** All cutting stripes covered: the mowing is done, even if the final docking fails afterwards. */
    private fun historyMowingDone() {
        state.value.mowingRecord?.let { r -> update { it.copy(mowingRecord = r.copy(doneMeters = r.cuttingMeters)) } }
    }

    private fun historyObstacle(reason: String) {
        taskEvent(fr.luba.poc.history.TaskEventType.OBSTACLE, reason)
        state.value.mowingRecord?.let { r -> update { it.copy(mowingRecord = r.copy(obstacles = r.obstacles + 1)) } }
    }

    /** End of the measurement: report compared with the estimate, saved and displayed. */
    private fun historyEnd(reason: String, completed: Boolean) {
        if (state.value.mowingRecord == null) return
        historyJob?.cancel(); historyJob = null
        historySample()
        val record = requireNotNull(state.value.mowingRecord)
        val done = completed || record.progress >= 0.999
        val final = record.copy(outcome = if (done) fr.luba.poc.history.MowingOutcome.COMPLETE else fr.luba.poc.history.MowingOutcome.INTERRUPTED,
            reason = if (record.kind != fr.luba.poc.history.TaskKind.MOW && reason.startsWith(tr("Mowing finished", "Tonte terminée"))) reason.replaceFirst(tr("Mowing finished", "Tonte terminée"), tr("Route finished", "Trajet terminé")) else reason, endedAt = System.currentTimeMillis(), doneMeters = if (done) record.cuttingMeters else record.doneMeters)
        historyTracker = null
        val mowing = final.kind == fr.luba.poc.history.TaskKind.MOW
        // Zones mowed at 50 % or more remember their directions for the next "Change every session".
        orientationPlan?.takeIf { mowing }?.let { (preview, map) ->
            val current = state.value.zoneOrientations
            val updated = runCatching { fr.luba.poc.planning.ZoneProgress.update(current, preview, map, final.doneMeters,
                final.endedAt ?: System.currentTimeMillis(), final.cuttingMeters) }.getOrDefault(current)
            if (updated != current) { update { it.copy(zoneOrientations = updated) }; orientationWrites.trySend(updated) }
        }
        orientationPlan = null
        // Report opened at the end of a mowing only; surveys and routes remain available in the history.
        update { it.copy(mowingRecord = null, mowingReport = if (mowing) final else it.mowingReport) }
        historyPersist(final)
        taskEvent(fr.luba.poc.history.TaskEventType.END, final.reason?.let { fr.luba.poc.history.reasonText(it) } ?: final.outcome.label,
            mapOf("outcome" to final.outcome.name, "progress" to final.progress))
        trackStop(final.id, fr.luba.poc.history.TaskTrackStore.kept(state.value.mowingHistory))
        val comparison = fr.luba.poc.history.MowingComparison.of(final)
        event("mowing_report", tr("${final.kind.label} report: ${final.outcome.label} · ${comparison.durationVerdict} · ${comparison.rechargeVerdict}", "Rapport ${final.kind.label.lowercase()} : ${final.outcome.label} · ${comparison.durationVerdict} · ${comparison.rechargeVerdict}"),
            mapOf("id" to final.id, "kind" to final.kind.name, "outcome" to final.outcome.name, "progress" to final.progress,
                "expectedSeconds" to comparison.expectedSeconds, "actualSeconds" to comparison.actualSeconds,
                "activeSeconds" to final.totals.activeSeconds, "chargingSeconds" to final.totals.chargingSeconds,
                "expectedRecharges" to comparison.expectedRecharges, "actualRecharges" to comparison.actualRecharges,
                "batteryUsed" to final.totals.batteryUsed, "batteryCharged" to final.totals.batteryCharged,
                "observedTimeFactor" to comparison.observedTimeFactor, "observedAutonomyMinutes" to comparison.observedAutonomyMinutes,
                "observedChargeMinutes" to comparison.observedChargeMinutes, "trackingLinks" to final.links.joinToString(",") { it.name },
                "gatewayLegs" to final.gatewayLegs) + wetnessFields())
        if (done && mowing) alert(AlertKind.MOWING_REPORT, Severity.INFO, "${final.zoneNames.joinToString(", ").ifBlank { tr("Mowing", "Tonte") }} · ${comparison.durationVerdict}")
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
        if (record.outcome == fr.luba.poc.history.MowingOutcome.RUNNING) return
        val next = record.copy(excluded = excluded)
        historyPersist(next)
        if (state.value.mowingReport?.id == id) update { it.copy(mowingReport = next) }
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
                Point(t.x,t.y).distance(engine.baseApproach) <= NATIVE_HANDOFF_APPROACH_M) {
                nativeReturnToBase(); return !state.value.nativeReturnStatus.startsWith(tr("Native return refused", "Retour natif refusé"))
            }
            update { it.copy(nativeReturnStatus="") }
        }
        return startRoute(engine,false,state.value.telemetry.bladeHeightMm ?: 60,intent)
    }

    /**
     * Base used to prepare [engine], for the resume: the current base of its map if it matches the route exactly
     * (point, axis and approach); otherwise null and resume on the current base, as before 0.64.2.
     */
    private fun missionBase(engine: MowingSimulation): fr.luba.poc.simulation.MapBase? = runCatching {
        fr.luba.poc.simulation.BaseStore(getApplication(), engine.map).resolve(engine.map)
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
            fr.luba.poc.protocol.ModelSupport.refusal(state.value.selected?.name)?.let { error(it) }
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
            state.value.cutHeights.refusal(heightMm)?.let { throw IllegalArgumentException(it) }
            // Working hours: neither mowing nor leaving the base outside them; return to base remains allowed.
            if (intent != RouteIntent.RETURN_TO_BASE) fr.luba.poc.mowing.WorkWindowSetting.value.refusal(java.time.LocalTime.now())?.let { error(it) }
            val recharge = RouteRecharge(engine.config.returnPercent.toInt(), engine.config.resumePercent.toInt())
            // Watched return threshold: the low threshold, or the [FinishPolicy] floor while finishing a mowing below the low threshold.
            var lowWatch = recharge.low
            // Never Die Outside ([ReturnReserve]): far from the base, the return cost plus the reserve raises the watched threshold.
            val returnReserve = fr.luba.poc.simulation.ReturnReserve.sanitize(Math.round(engine.config.returnReservePercent).toInt(), recharge.low)
            var returnDetour = fr.luba.poc.simulation.ReturnReserve.DEFAULT_DETOUR
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
                t.battery,if (initiallyDocked && t.robotState in setOf(11,15,39)) 11 else t.robotState,guardSensors(t.sensorStatus),t.selfCheck,state.value.campaign.running,
                state.value.bladeTestActive,state.value.mapLoading,heightMm,if(initiallyDocked) -1 else RouteRecharge.RESERVE_PERCENT,heights = state.value.cutHeights)?.let { error(it) }
            NavSafety.automaticPositionFailure(SystemClock.elapsedRealtime()-positionPrevious,t.x,t.y)?.let { error(it) }
            // Physical geometry (obstacles at their real radius, no margin): possible clearance at the start.
            val physicalRoutes = fr.luba.poc.planning.RoutePlanning.physical(engine.map, state.value.planningObstacles,
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
            val dockCorridor = fr.luba.poc.planning.DockClearance.corridor(engine.base, engine.config.baseEntryHeadingDegrees, engine.config.baseApproachMeters)
            fun dockFitted(obstacles: List<LocalObstacle>) = fr.luba.poc.planning.DockClearance.fit(obstacles, dockCorridor)
            dockFitted(state.value.obstacles).filter { it !in state.value.obstacles }.forEach { o ->
                event("obstacle_dock_margin", tr("Obstacle margin reduced near the docking corridor", "Marge d'obstacle réduite près du couloir d'accostage"), mapOf("id" to o.id, "x" to o.x, "y" to o.y,
                    "radiusM" to o.radiusM, "marginM" to o.marginM, "savedMarginM" to state.value.obstacles.firstOrNull { it.id == o.id }?.marginM))
            }
            val missionPlan = if (intent == RouteIntent.FOLLOW) RobotRoute.missionEdges(engine,routeStart,requireNotNull(t.heading),physicalRoutes,
                resume?.let { MissionResume.of(it,overlap = skipTo == null) }) else null
            val edges = missionPlan?.edges ?: RobotRoute.actionEdges(engine,routeStart,requireNotNull(t.heading),intent,physicalRoutes)
            // Resume during a second pass, for example: the first remaining mowing edge gives the starting height.
            if (cutting) edges.firstNotNullOfOrNull { it.heightMm }?.let { heightMm = it }
            state.value.cutHeights.refusal(heightMm)?.let { throw IllegalArgumentException(it) }
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
            surveyContours = if (intent == RouteIntent.FOLLOW && engine.preview.mode.isSurvey) engine.preview.borders + engine.preview.runs else emptyList()
            surveySession = null
            if (surveyContours.isNotEmpty()) {
                // Dedicated survey archived separately; the display stays the continuous map, completed with its measurements.
                val survey = SignalSurvey(requireNotNull(state.value.selected).address,SignalCoverage.mapKey(engine.map),engine.preview.zoneIds,
                    sessionId=java.util.UUID.randomUUID().toString(),startedAt=System.currentTimeMillis(),mode=engine.preview.mode,mapSnapshot=engine.map)
                surveySession = survey; surveySavedAt = SystemClock.elapsedRealtime()
                update { it.copy(surveySaveError=false) }
                surveyWrites.trySend(survey to false)
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
                state.value.pointResume?.let { setPointResume(it.robotId, null) }
                val now = System.currentTimeMillis()
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
                historyBegin(engine, checkpoint, resume != null, t.battery, when {
                    engine.preview.mode.isSurvey -> fr.luba.poc.history.TaskKind.SURVEY
                    cutting -> fr.luba.poc.history.TaskKind.MOW
                    else -> fr.luba.poc.history.TaskKind.FOLLOW
                }, heightMm)
                event(if (resume != null) "mission_resume" else "mission_checkpoint_start",
                    if (resume != null) tr("Resuming the interrupted task", "Reprise de la tâche interrompue") else tr("Progress saved for resume", "Avancement sauvegardé pour reprise"),
                    mapOf("task" to checkpoint.task.name, "edge" to checkpoint.edge, "progress" to checkpoint.progress, "resumes" to checkpoint.resumes,
                        "fingerprint" to engineFingerprint, "skippedMeters" to skippedNow, "skippedTotalMeters" to checkpoint.skippedMeters))
            }
            send(reportsCommand(false,periodMs=200),"ROUTE_REPORTS")
            routeIntent = intent
            workWindowEnding = false
            routeJob = viewModelScope.launch {
                try {
                    // Height: in the READY state the driver setpoint alone is ignored (firmware 1.30); it is carried
                    // by mow_ctrl_by_hand on every range and awaited with the blades running and the robot stopped.
                    // Calibration kept from one range to the next for the whole route. Pose predicted at the moment
                    // the command takes effect (≈ 0.6 s measured during the debug mowing of 10-10): without this
                    // compensation, tracking zigzagged by ±10–20 cm on the stripes and pivoted mid-stripe.
                    // Gains already calibrated on this robot (previous mission): more accurate first curves.
                    fun newRouteReckoning() = DeadReckoning(commandDelayMs = DeadReckoning.ROUTE_COMMAND_DELAY_MS, calibration = savedCalibration(),
                        publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M).also { it.slide = slideVelocity() }
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
                        fr.luba.poc.obstacles.FollowObstacleGuard.keptOut(o, edges.filterNot { it.escape }.map { it.a to it.b })
                    }.map { it.id }).toMutableSet()
                    var bypassCount = 0
                    // Contacts at the limit of the task area ([EdgeContact]): drawn border, count and bumper points of the mission.
                    routeEdgeBorder = fr.luba.poc.simulation.EdgeContact.border(engine.map)
                    routeEdgeLimits.clear()
                    // Base safety zone: drift tolerated on the return only if it is known.
                    val routeBaseZone = engine.routes.baseSafety?.let { (station,_,radius) -> ReturnDrift.BaseZone(station,radius) }
                    // Pivot slip on slopes, tracked from one range to the next (same rule as the gateway).
                    val routeSlide = fr.luba.poc.simulation.PivotSlide()
                    routeContact = null
                    /** Follows path[from until to) with a single blade state, without STOP at aligned vertices.
                     * Returns the index reached: `to` if the range is finished, otherwise the edge interrupted by the battery. */
                    /** Battery points needed to return from the robot's position ([FinishPolicy]); null without a mowing measurement. */
                    fun returnNeed(): Double? {
                        if (intent!=RouteIntent.FOLLOW) return null
                        val record=state.value.mowingRecord ?: return null
                        val t=state.value.telemetry
                        val here=Point(t.x ?: return null, t.y ?: return null)
                        val meters=fr.luba.poc.simulation.ReturnReserve.returnMeters(here.distance(engine.baseApproach), returnDetour, engine.base.distance(engine.baseApproach))
                        val (autonomy,factor)=fr.luba.poc.history.MowingCalibration.finishModel(record)
                        return runCatching { FinishPolicy.neededPercent(FinishPolicy.restSeconds(meters,record.speedMps,factor),autonomy) }.getOrNull()
                    }
                    /** Threshold watched now: the low threshold raised by the return reserve (refreshed every 5 s), or the finish floor. */
                    fun batteryWatch(): Int {
                        if (lowWatch!=recharge.low) return lowWatch
                        val now=SystemClock.elapsedRealtime()
                        if (now-reserveWatchAt>=5_000) {
                            reserveWatchAt=now
                            val previous=reserveWatch
                            reserveWatch=returnNeed()?.let { fr.luba.poc.simulation.ReturnReserve.threshold(recharge.low,recharge.high,it,returnReserve) } ?: recharge.low
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
                        val path=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching { routes.between(here,engine.baseApproach,heading,(engine.dockEntryHeading+180.0)%360.0) }.getOrNull() } ?: return
                        returnDetour=fr.luba.poc.simulation.ReturnReserve.detour(path.zipWithNext().sumOf { (a,b) -> a.distance(b) },straight)
                        reserveWatchAt=0L
                    }
                    suspend fun followPath(path: List<SimEdge>, from: Int, to: Int, shown: Boolean, watchBattery: Boolean = false, departure: Boolean = false,
                                           finalArrivalM: Double = if (path[to-1].b==engine.baseApproach) 0.05 else RangeArrival.TARGET_M): Int {
                        if (watchBattery) {
                            calibrateReturn()
                            val live=state.value.telemetry
                            NavSafety.tickFailure(SystemClock.elapsedRealtime()-devicePrevious,live.battery,guardSensors(live.sensorStatus),true,RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
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
                            send(commands.manualMow(1,0,heightMm),"ROUTE_MANUAL_MODE")
                            val modeDeadline = SystemClock.elapsedRealtime()+3000
                            while (state.value.telemetry.robotState != 20) {
                                check(state.value.ready && !closing && log.healthy && SystemClock.elapsedRealtime()<modeDeadline) {
                                    if (blades) tr("Manual mowing mode not confirmed", "Mode tonte manuelle non confirmé") else tr("Bumper detection inactive: manual mode with blades OFF not confirmed", "Détection bumper inactive : mode manuel lames OFF non confirmé") }
                                delay(200)
                            }
                        }
                        // Stop only when the blade state changes; OFF confirmed before any link.
                        send(commands.stop(),"ROUTE_WAYPOINT_STOP")
                        routeReckoning.sent(SystemClock.elapsedRealtime(),0,0)
                        val bladeGate = RouteBladeGate.unique("ROUTE_BLADES_${from}_${if (blades) "ON" else "OFF"}",blades)
                        routeBladeGate = bladeGate
                        bladeMayBeOn = bladeMayBeOn || blades
                        send(if (blades || !bumperWatch) commands.manualBlades(blades,heightMm) else commands.manualMow(1,0,heightMm),bladeGate.label)
                        val begin = SystemClock.elapsedRealtime()
                        val meters = (from until to).sumOf { path[it].meters }
                        // Long range (full border): proportional budget, at least 5 cm/s on average.
                        val budgetMs = 120_000L + (meters/0.05*1000).toLong()
                        val motionProgress=MotionProgressGuard()
                        // Slip map re-read on every range: pivots already measured during the mission are used right away.
                        val follower=PathFollower(path,from,to,finalArrivalM).also { it.slide = slideVelocity(); routeReckoning.slide = it.slide }
                        val manoeuvreStats = ManoeuvreStats(path)
                        val returnFrom=ReturnDrift.returnFrom(path,engine.baseApproach)
                        // End of range confirmed by a position measured after the stop (except the base approach,
                        // followed by docking): the next range starts at the real corner.
                        val arrival = if (path[to-1].b==engine.baseApproach) null else RangeArrival(DeadReckoning.ROUTE_COMMAND_DELAY_MS)
                        var fixAt=0L
                        var progressAt = begin; var best = Double.POSITIVE_INFINITY; var bestIndex = from
                        var bladeObserved = !blades; var keepalive = 0L; var queryAt = 0L
                        val heightSince = System.currentTimeMillis()
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
                            NavSafety.tickFailure(now-devicePrevious,live.battery,guardSensors(live.sensorStatus),true,RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                            val protectionUp = live.robotState !in (if (departure) listOf(11,15,20,39) else listOf(11,20)) || !fr.luba.poc.protocol.SelfCheck.nominal(live.selfCheck)
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
                                    purgeQueuedCommands(); send(commands.movement(0,0),"ROUTE_LATCH_STOP"); routeReckoning.sent(now,0,0)
                                    event("route_latch_wait",tr("Robot protection: stop, contact code awaited", "Protection du robot : arrêt, code de contact attendu"),mapOf("selfCheck" to live.selfCheck,
                                        "robotState" to live.robotState,"edge" to follower.index,"graceMs" to CollisionLatch.CONTACT_GRACE_MS))
                                    keepalive = 0L; queryAt = 0L
                                }
                                // Blades stopped, manual mode kept alive (the robot only publishes the contact that way), errors polled.
                                if ((blades || bumperWatch) && now>=keepalive) { send(commands.manualMow(1,0,heightMm),"ROUTE_LATCH_BUMPER_OFF",periodic=true); keepalive=now+500 }
                                if (now>=queryAt) { send(commands.errorQuery(),"ROUTE_LATCH_ERRORS",periodic=true); queryAt=now+500 }
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
                                purgeQueuedCommands(); send(commands.stop(), "ROUTE_LOW_BATTERY_STOP")
                                return follower.index
                            }
                            // End of the working hours: same stop as the low threshold, then return to base.
                            if (watchBattery && workWindowEnding) {
                                purgeQueuedCommands(); send(commands.stop(), "ROUTE_WORK_WINDOW_STOP")
                                return follower.index
                            }
                            if (blades && bladeObserved) check(bladeBits == 1) { tr("Blade rotation interrupted", "Rotation des lames interrompue") }
                            if (blades) check(bladeObserved || now-begin < 5000) { tr("Blade rotation not confirmed", "Rotation lames non confirmée") }
                            // Positions at 1 Hz: command on the predicted pose, checks on the measured pose.
                            // Published heading realigned on the direction travelled (the LUBA 2 may publish a wrong heading when stopped).
                            val fixed = positionPrevious != fixAt
                            if (fixed) { fixAt = positionPrevious; routeReckoning.fix(published,reportedHeading,fixAt); saveCalibration(routeReckoning) }
                            val heading = routeReckoning.heading(reportedHeading)
                            // Rotation centre: the published point is 8 cm behind it ([DeadReckoning.PUBLISHED_POINT_BEHIND_M]).
                            val position = routeReckoning.measured ?: routeReckoning.center(published,heading)
                            if (fixed) routeSlideMeter.update(position,System.currentTimeMillis(),routeReckoning.rotationSlide)?.let { learnSlide(it,"local") }
                            val headingTrusted = routeReckoning.headingConfirmed
                            confirmedHeading = heading.takeIf { headingTrusted }
                            val predicted = routeReckoning.predict(now) ?: RobotPose(position,heading)
                            val previousIndex = follower.index
                            val speedPercent = state.value.trajectorySpeedPercent
                            // Heading not confirmed, or pivot not measured yet: slow advance until the next position.
                            val linearLimit = if (!headingTrusted) minOf(PathFollower.linearFor(speedPercent), DeadReckoning.UNCONFIRMED_LINEAR)
                                else routeReckoning.postPivotLinear(PathFollower.linearFor(speedPercent))
                            val command = follower.command(predicted.position,predicted.headingDegrees,linearLimit,
                                PathFollower.angularLimit(speedPercent),routeReckoning.degreesPerUnit,routeReckoning.metersPerUnit,
                                RangeArrival.APPROACH_MIN_LINEAR,follower.deviation(position),routeReckoning.rotationCalibrated)
                            if (follower.index != previousIndex) showIndex(follower.index)
                            manoeuvreStats.update(System.currentTimeMillis(), follower.index, position)?.let { logManoeuvre(it, "local") }
                            if (shown) (routeOrigins?.get(follower.index) ?: follower.index).let { origin -> if (origin >= 0) recordMissionProgress(origin, position) }
                            val edge = path[follower.index]
                            // Return to base: drift tolerated up to 1 m, chassis within the safe surface and outside the base zone.
                            val sliding = routeSlide.update(follower.pivoting,position,follower.deviation(position))
                            ReturnDrift.failure(follower.deviation(position),follower.index,follower.index>=returnFrom,position,routeBaseZone,sliding) { p ->
                                missionRoutes.bodySafe(p) }?.let { if (DriftRecovery.recoverable(it)) throw RouteDrift(follower.index,it) else error(it) }
                            if (surveyContours.isNotEmpty() && shown && edge.cutting && state.value.robotRoute.phase == RoutePhase.FOLLOWING) {
                                System.currentTimeMillis().let { now -> SignalCoverage.sample(live,now,surveyContours,fr.luba.poc.map.RobotTilt.current(now)) }
                                    ?.let(::recordSurveySample)
                            }
                            update { it.copy(robotRoute=it.robotRoute.copy(trail=MissionTrail.append(it.robotRoute.trail,position))) }
                            if (command.done) {
                                // Very short range: wait for blade confirmation while stopped rather than failing.
                                if (blades && !bladeObserved) { send(commands.movement(0,0),"ROUTE_WAIT_BLADES"); routeReckoning.sent(now,0,0); delay(200); continue }
                                when (RangeArrival.settle(arrival,now,fixAt,position,follower,path[to-1].b)) {
                                    null -> {
                                        // Commanded stop: normal immobility, blades and bumper detection kept alive.
                                        motionProgress.reset(); progressAt = now
                                        if (blades && now>=keepalive) { send(commands.manualBlades(true,heightMm),"ROUTE_BLADES_KEEPALIVE",periodic=true); keepalive=now+600 }
                                        if (!blades && bumperWatch && now>=keepalive) { send(commands.manualMow(1,0,heightMm),"ROUTE_BUMPER_KEEPALIVE_OFF",periodic=true); keepalive=now+500 }
                                        if (now>=queryAt) { send(commands.errorQuery(),"ROUTE_ERRORS",periodic=true); queryAt=now+500 }
                                        send(commands.movement(0,0),"ROUTE_SETTLE $to"); routeReckoning.sent(now,0,0); delay(200); continue
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
                                else RobotPose(Point(position.x+reach*kotlin.math.cos(rad),position.y+reach*kotlin.math.sin(rad)),heading)
                            // Clearance out of an obstacle's reserve: same checks, obstacles at their physical radius.
                            // Recorded obstacles and those discovered during the mission (contacts) checked together.
                            val escaping = follower.escaping(position)
                            val intruding = dockFitted(state.value.obstacles + presumedObstacles + discovered).filter { obstacle ->
                                fr.luba.poc.obstacles.FollowObstacleGuard.intrudes(Point(obstacle.x,obstacle.y),position,next.position,
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
                            if (blades && now>=keepalive) { send(commands.manualBlades(true,heightMm),"ROUTE_BLADES_KEEPALIVE"); keepalive=now+600 }
                            if (!blades && bumperWatch && now>=keepalive) { send(commands.manualMow(1,0,heightMm),"ROUTE_BUMPER_KEEPALIVE_OFF"); keepalive=now+500 }
                            // Bumper contact read from the error history: polled twice per second.
                            if (now>=queryAt) { send(commands.errorQuery(),"ROUTE_ERRORS"); queryAt=now+500 }
                            send(commands.movement(linear,angular),"ROUTE_MOVE ${follower.index} $linear $angular")
                            routeReckoning.sent(now,linear,angular)
                            delay(200)
                        } } finally { routeBypassArmed = false }
                        purgeQueuedCommands(); send(commands.stop(), "ROUTE_SEGMENT_STOP")
                        routeReckoning.sent(SystemClock.elapsedRealtime(),0,0)
                        saveCalibration(routeReckoning, force = true)
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
                        val link = gatt as? fr.luba.poc.gateway.GatewayLink ?: error(tr("Autonomous tracking: no gateway link", "Suivi autonome : liaison passerelle absente"))
                        fun knownObstacles() = dockFitted(state.value.obstacles + presumedObstacles + discovered).map { o ->
                            fr.luba.poc.autonomy.LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, o.id in plannedObstacles) }
                        var attempt = 0
                        while (true) {
                            attempt++
                            try {
                                val routeId = legs.ensureRoute(path)
                                val guardId = legs.ensureGuard(missionRoutes.physicalSurface)
                                val physicalId = (routePhysicalRoutes ?: missionRoutes).let { if (it === missionRoutes) guardId else legs.ensureGuard(it.physicalSurface) }
                                val spec = fr.luba.poc.autonomy.LegSpec(legs.nextLegId(), routeId, from, cutting, shown, watchBattery, departure, lastArrivalM,
                                    heightMm, state.value.trajectorySpeedPercent.coerceIn(10, 100), if (watchBattery) { calibrateReturn(); batteryWatch() } else lowWatch, engine.baseApproach, guardId, physicalId,
                                    savedCalibration(), knownObstacles(), sampling = shown && surveyContours.isNotEmpty(), baseZone = routeBaseZone,
                                    slideField = slideFor(path)).let { full ->
                                    // Slip map too large: sent without it rather than tracked from here.
                                    if (full.slideField != null && full.encode().size > fr.luba.poc.autonomy.LegFrames.MAX_PAYLOAD) full.copy(slideField = null) else full }
                                // Obstacle list too long for one frame: tracked from here rather than a send that would cut the link.
                                if (spec.encode().size > fr.luba.poc.autonomy.LegFrames.MAX_PAYLOAD) {
                                    event("gateway_leg_fallback", tr("Tracking too large for the gateway (${spec.obstacles.size} obstacles): tracked from here", "Suivi trop volumineux pour la passerelle (${spec.obstacles.size} obstacles) : suivi d'ici"))
                                    return followAllLocal(path, from, shown, watchBattery, departure, lastArrivalM)
                                }
                                return runRemoteLeg(link, spec, path, obstacles = { dockFitted(state.value.obstacles + discovered) },
                                    planned = { plannedObstacles }, originOf = { index -> routeOrigins?.get(index) ?: index })
                            } catch (refused: fr.luba.poc.autonomy.RemoteLegs.RefusedLeg) {
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
                            val segment = if (end == path.size) path else path.subList(0, end)
                            val arrival = if (end == path.size) lastArrivalM else null
                            val reached = if (autonomousLegsAvailable()) followAllRemote(segment, index, shown, watchBattery, departure, arrival)
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
                    fun freshState(requirePosition: Boolean = true, charging: Boolean = false, contactLatch: Boolean = false): fr.luba.poc.protocol.Telemetry {
                        val now = SystemClock.elapsedRealtime()
                        check(state.value.ready && !closing && log.healthy && queue.size <= 4) { tr("BLE interrupted or queue saturated", "BLE interrompu ou file saturée") }
                        val live = state.value.telemetry
                        NavSafety.tickFailure(now-devicePrevious,live.battery,guardSensors(live.sensorStatus),false,
                            if (charging) -1 else RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                        check(fr.luba.poc.protocol.SelfCheck.nominal(live.selfCheck) || (contactLatch && CollisionLatch.latched(live.selfCheck))) { tr("Robot protection active", "Protection robot active") }
                        check(live.robotState in listOf(11,15,20,39)) { tr("Robot state incompatible with the station (state ${live.robotState})", "État robot incompatible avec la station (état ${live.robotState})") }
                        if (requirePosition) NavSafety.automaticPositionFailure(now-positionPrevious,live.x,live.y)?.let { error(it) }
                        return live
                    }
                    /** [contactLatch]: right after a contact, the collision lock (25) is accepted ([CollisionLatch]); it only
                     * clears once the robot has moved away from the object, through the back-off that follows this stop. */
                    suspend fun bladesOff(contactLatch: Boolean = false) {
                        purgeQueuedCommands(); send(commands.stop(), "ROUTE_RECHARGE_STOP")
                        val stopAt = SystemClock.elapsedRealtime()
                        val gate = RouteBladeGate.unique("ROUTE_RECHARGE_OFF",false)
                        routeBladeGate = gate
                        send(commands.manualBlades(false,heightMm),gate.label)
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
                        suspend fun settledState(): fr.luba.poc.protocol.Telemetry {
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
                        else check(position.distance(engine.baseApproach)<=NATIVE_HANDOFF_APPROACH_M) {
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
                            purgeQueuedCommands()
                            send(commands.stop(),"ROUTE_DOCK_ZERO")
                            send(commands.returnToDock(),"ROUTE_NATIVE_DOCK",nativeReturn=true)
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
                        val link=gatt as? fr.luba.poc.gateway.GatewayLink
                        if (link!=null) link.holdUntil=Long.MAX_VALUE
                        try { dockHeld() }
                        finally { if (link!=null) link.holdUntil=if (gatewayOutageSince!=0L) System.currentTimeMillis()+ABORT_HOLD_MS else 0L }
                    }
                    /** High threshold reached: slow straight forward drive to the link point, without pivoting. */
                    suspend fun leaveDockStraight() {
                        routeStatus(RoutePhase.RESUMING,tr("Leaving the base in a straight line, slowly · blades OFF", "Sortie de base en ligne droite, lentement · lames OFF"))
                        val began=SystemClock.elapsedRealtime()
                        val exit=straightDockExit(engine.base, engine.baseApproach, engine.routes, heightMm, recharge.low,
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
                        val (autonomy,factor)=fr.luba.poc.history.MowingCalibration.finishModel(record)
                        val meters=extraMeters+routeEdges.drop(from).sumOf { it.meters }
                        return runCatching { FinishPolicy.neededPercent(FinishPolicy.restSeconds(meters,record.speedMps,factor),autonomy) }.getOrNull()
                    }
                    /**
                     * [restNeed]: battery points needed to finish from the base ([FinishPolicy], margin included); the robot
                     * leaves before the high threshold as soon as the battery covers that remainder above the low threshold.
                     */
                    suspend fun chargeAndDepart(waitForCharge: Boolean = true, restNeed: Double? = null) {
                        val target=restNeed?.let { FinishPolicy.chargeTarget(it,recharge.low.toDouble(),recharge.high.toDouble()) }?.let { kotlin.math.ceil(it).toInt() }
                        update { it.copy(robotRoute=it.robotRoute.copy(chargeTarget=target?.takeIf { t -> t < recharge.high && waitForCharge })) }
                        val chargeStatus=if (target!=null && target<recharge.high) tr("Actual recharge · departure at $target % (short remainder) instead of ${recharge.high} %", "Recharge réelle · départ à $target % (reste court) au lieu de ${recharge.high} %")
                            else tr("Actual recharge · resume at ${recharge.high} %", "Recharge réelle · reprise à ${recharge.high} %")
                        if (waitForCharge) routeStatus(RoutePhase.CHARGING,chargeStatus)
                        val began=SystemClock.elapsedRealtime()
                        val afterStop=devicePrevious
                        val link=gatt as? fr.luba.poc.gateway.GatewayLink
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
                            if (waitForCharge && link!=null) link.holdUntil=if (gatewayOutageSince!=0L) System.currentTimeMillis()+ABORT_HOLD_MS else 0L
                        }
                        if (workWindowEnding) throw WorkWindowEnded()
                        // Leaving the base: the robot leaves the charging contact, a resume from the base alone no longer applies.
                        rechargeCycle=false
                        leaveDockStraight()
                    }
                    if (initiallyDocked) {
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
                        requestStop(false,tr("Base exit finished", "Sortie de base terminée"),intentional=true)
                        return@launch
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
                        val built=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching {
                            val mission=RobotRoute.missionEdges(engine,start,heading,escape,MissionResume.of(next))
                            mission to if (obstaclesNow.isEmpty()) null else ObstacleBypass.repair(engine.routes,physicalRoutes,mission.edges,null,0,start,heading,obstaclesNow,zones,false)
                        } }.getOrElse { error ->
                            event("route_replan_failed",tr("Reordered remainder impossible to follow: usual resume kept", "Reste réordonné impossible à suivre : reprise habituelle conservée"),mapOf("reason" to error.message))
                            return false
                        }
                        val (mission,repair)=built
                        routeEdges=repair?.edges ?: mission.edges; routeOrigins=repair?.origins
                        repair?.let { missionRoutes=it.routes; routePhysicalRoutes=it.physical }
                        routePlan=mission
                        routeCheckpoint=next.copy(updatedAt=System.currentTimeMillis()).also { persistCheckpoint(it,force=true) }
                        update { it.copy(robotRoute=it.robotRoute.copy(count=routeEdges.size,planned=listOf(routeEdges.first().a)+routeEdges.map { e -> e.b })) }
                        return true
                    }
                    /** Return from [start] to the approach point once nothing is left to mow ([next]): the stripes are finished on the first progress. */
                    suspend fun homeRoute(next: MissionCheckpoint, start: Point, heading: Double): Boolean {
                        val plan=routePlan ?: return false
                        val routesNow=missionRoutes; val escape=routePhysicalRoutes ?: physicalRoutes
                        val path=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching {
                            RobotRoute.departure(routesNow,escape,start,heading,engine.baseApproach,(engine.dockEntryHeading+180.0)%360.0) }.getOrNull() }
                        if (path.isNullOrEmpty()) return false
                        routeEdges=path; routeOrigins=path.map { plan.mowingTo }
                        routeCheckpoint=next.copy(updatedAt=System.currentTimeMillis()).also { persistCheckpoint(it,force=true) }
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
                        recordDifficulty(DifficultyKind.BLOCKED,reason,position,null,heading)
                        val physical=routePhysicalRoutes ?: missionRoutes
                        val starts=DriftRecovery.starts(physical,position,heading,state.value.robotRoute.trail.takeLast(400)).filterNotNull()
                        val backOff=starts.firstOrNull { it.distance(position)>=SmartResume.BACK_OFF_M } ?: starts.lastOrNull()
                        if (contact && backOff==null) error(tr("$reason: back-off impossible", "$reason : recul impossible"))
                        backOff?.let { target ->
                            routeStatus(RoutePhase.FOLLOWING,tr("Blockage: straight back-off of %.0f cm · blades OFF", "Blocage : recul de %.0f cm en ligne droite · lames OFF").format(target.distance(position)*100))
                            try { reverseStraight(position,target,heading,physical,heightMm,"ROUTE",recovering=!contact,afterContact=contact,degreesPerUnit=routeReckoning.degreesPerUnit) }
                            catch (deviation: BackOffDeviation) { event("route_blockage_back_off",deviation.message ?: tr("Back-off deviation", "Écart au recul")) }
                        }
                        if (contact) awaitCollisionLatchCleared("ROUTE",heightMm)
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
                        return ObstacleEncounter(blocked.index,RouteContact(0,x,y,routeReckoning.heading(h),System.currentTimeMillis()),emptyList(),
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
                    suspend fun limitContact(encounter: ObstacleEncounter, contact: RouteContact, edge: fr.luba.poc.simulation.EdgeContactClass,
                                             position: Point, heading: Double): Int? {
                        fr.luba.poc.simulation.EdgeContact.refusal(routeEdgeLimits.size)?.let { error(tr("${encounter.message}: $it", "${encounter.message} : $it")) }
                        val physical=routePhysicalRoutes ?: missionRoutes
                        val trail=state.value.robotRoute.trail.takeLast(400)
                        val pathNow=routeEdges; val originsNow=routeOrigins; val routesNow=missionRoutes
                        val plan=try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            DriftRecovery.plan(routesNow,physical,pathNow,originsNow,encounter.index.coerceIn(pathNow.indices),position,heading,trail,afterContact=true) } }
                            catch (error: IllegalArgumentException) {
                                event("route_edge_contact_fallback",tr("Border contact: no back-off along the trail, handled as an obstacle", "Contact en bordure : pas de recul le long de la trace, traité comme un obstacle"),
                                    mapOf("reason" to error.message,"x" to position.x,"y" to position.y,"heading" to heading))
                                return null
                            }
                        // Resume link too long (it would go around an obstacle or far along the route): obstacle path instead.
                        fr.luba.poc.simulation.EdgeContact.longLink(plan.rejoinMeters)?.let { reason ->
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
                        recordDifficulty(DifficultyKind.EDGE,note,position,contact.code.takeIf { coded },heading,edge.bumper,contactAt)
                        taskEvent(fr.luba.poc.history.TaskEventType.CONTACT,note,mapOf("kind" to edge.kind.code,"cause" to edge.cause))
                        event("route_edge_contact",tr("Border contact: back-off, then the route resumes", "Contact en bordure de zone : recul puis reprise du trajet"),mapOf(
                            "kind" to edge.kind.code,"code" to contact.code.takeIf { coded },"selfCheck" to state.value.telemetry.selfCheck,"sensors" to state.value.telemetry.sensorStatus,
                            "x" to position.x,"y" to position.y,"heading" to heading,"borderDistM" to edge.borderDistM,"centerBorderDistM" to edge.centerBorderDistM,
                            "driftM" to edge.driftM,"approachDeg" to edge.approachDegrees,"cause" to edge.cause,"backOffM" to plan.backOff?.distance(position),
                            "rejoinM" to plan.rejoinMeters,"skippedCuttingM" to plan.skippedCuttingMeters,"count" to routeEdgeLimits.size,"sameSpot" to edge.sameSpot))
                        routeStatus(RoutePhase.FOLLOWING,tr("Border contact: back-off, then the route resumes", "Contact en bordure de zone : recul puis reprise du trajet"))
                        plan.backOff?.let { target ->
                            try {
                                reverseStraight(position,target,heading,physical,heightMm,"ROUTE",recovering=true,afterContact=true,degreesPerUnit=routeReckoning.degreesPerUnit)
                            } catch (deviation: BackOffDeviation) {
                                // Robot yawed while reversing (slip): brought back like after a trajectory exit.
                                awaitCollisionLatchCleared("ROUTE",heightMm)
                                throw RouteDrift(encounter.index.coerceIn(pathNow.indices),deviation.message ?: tr("Back-off deviation", "Écart au recul"))
                            }
                        }
                        routeStatus(RoutePhase.FOLLOWING,tr("Border contact: waiting for the robot's protection to clear · blades OFF", "Contact en bordure : attente de la levée de la protection du robot · lames OFF"))
                        awaitCollisionLatchCleared("ROUTE",heightMm)
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
                            fr.luba.poc.simulation.EdgeContact.classify(coded,if (coded) live.selfCheck else CollisionLatch.VALUE,live.sensorStatus,position,heading,
                                routeEdgeBorder,routeEdges,encounter.index,state.value.robotRoute.trail.takeLast(400),routeEdgeLimits.toList(),discovered.toList())
                        }
                        if (contact != null && edge?.kind == fr.luba.poc.simulation.EdgeContactKind.LIMIT) limitContact(encounter,contact,edge,position,heading)?.let { return it }
                        check(bypassCount < ObstacleBypass.MAX_BYPASSES) { tr("${encounter.message}: ${ObstacleBypass.MAX_BYPASSES} bypasses already done, stop maintained", "${encounter.message} : ${ObstacleBypass.MAX_BYPASSES} contournements déjà effectués, arrêt maintenu") }
                        bypassCount++
                        historyObstacle(encounter.message ?: "")
                        var touched: LocalObstacle? = null
                        if (contact != null) {
                            // Stopped position (two stationary positions): more accurate than the contact report (1 Hz). Object at the
                            // border: tighter planning margin ([ObstacleBypass.CONTACT_EDGE_MARGIN_M]).
                            val obstacle=ObstacleBypass.contactObstacle("mission-${UUID.randomUUID()}",position,heading,System.currentTimeMillis(),
                                marginM=edge?.let { fr.luba.poc.simulation.EdgeContact.marginM(it.kind) } ?: ObstacleBypass.CONTACT_MARGIN_M)
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
                        val hardship=difficultyCost()
                        val repair=try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            ObstacleBypass.repair(engine.routes,physicalRoutes,pathNow,originsNow,encounter.index,position,heading,
                                obstaclesNow,zones,contact!=null,trail,hardship) } }
                            catch (error: IllegalArgumentException) {
                                touched?.let { recordDifficulty(if (encounter.blockage) DifficultyKind.BLOCKED else DifficultyKind.CONTACT, tr("${encounter.message}: bypass impossible", "${encounter.message} : contournement impossible"), position,
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
                                touched?.let { recordDifficulty(if (encounter.blockage) DifficultyKind.BLOCKED else DifficultyKind.CONTACT, tr("${encounter.message}: $refusal", "${encounter.message} : $refusal"), position,
                                    contact?.code, heading, Point(it.x,it.y), state.value.obstaclePhoto.request?.contactAt ?: contact?.atMs) }
                                return deferBlockage(tr("${encounter.message}: $refusal", "${encounter.message} : $refusal"),contact=contact!=null) { start, startHeading ->
                                    // Deferral impossible after the back-off: the repair is computed again from here and kept.
                                    val again=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
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
                        touched?.let { recordDifficulty(if (encounter.blockage) DifficultyKind.BLOCKED else DifficultyKind.CONTACT, encounter.message ?: tr("Bumper contact", "Contact pare-chocs"), position, contact?.code, heading,
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
                                reverseStraight(position,target,heading,requireNotNull(repair.physical),heightMm,"ROUTE",afterContact=contact!=null,
                                    degreesPerUnit=routeReckoning.degreesPerUnit)
                            } catch (deviation: BackOffDeviation) {
                                // Robot yawed while reversing (slip): the repaired route is kept and the robot is brought back
                                // like after a trajectory exit (back-off along its trail into the safe surface, then link).
                                routeEdges=repair.edges; routeOrigins=repair.origins
                                update { it.copy(robotRoute=it.robotRoute.copy(count=repair.edges.size,planned=listOf(repair.edges.first().a)+repair.edges.map { e -> e.b })) }
                                if (contact != null) awaitCollisionLatchCleared("ROUTE",heightMm)
                                throw RouteDrift(0,deviation.message ?: tr("Back-off deviation", "Écart au recul"))
                            }
                            if (contact != null && !encounter.blockage) {
                                routeStatus(RoutePhase.FOLLOWING,tr("Obstacle photo · robot still, blades OFF", "Photo de l'obstacle · robot immobile, lames OFF"))
                                holdForObstaclePhoto("ROUTE",heightMm)
                            }
                        }
                        // Collision lock cleared by the robot once it has moved away from the object, before resuming the route.
                        if (contact != null) {
                            routeStatus(RoutePhase.FOLLOWING,tr("Contact: waiting for the robot's protection to clear · blades OFF", "Contact : attente de la levée de la protection du robot · lames OFF"))
                            awaitCollisionLatchCleared("ROUTE",heightMm)
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
                        val plan=try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            DriftRecovery.plan(routesNow,physical,pathNow,originsNow,drift.index.coerceIn(pathNow.indices),position,heading,trail,
                                skipM=if (protection) 0.0 else driftStreak*DriftRecovery.SAME_SPOT_M) } }
                            catch (error: IllegalArgumentException) { throw IllegalStateException(tr("${drift.message}: ${error.message}", "${drift.message} : ${error.message}")) }
                        event("route_drift_recovery",tr("Trajectory exit recovered: back-off then link to the route", "Sortie de trajectoire rattrapée : recul puis raccord au trajet"),mapOf("reason" to drift.message,
                            "edge" to drift.index,"x" to position.x,"y" to position.y,"heading" to heading,"count" to driftRecoveries,"streak" to driftStreak,
                            "backOffM" to plan.backOff?.distance(position),"rejoinM" to plan.rejoinMeters,"skippedCuttingM" to plan.skippedCuttingMeters,
                            "edges" to plan.edges.size))
                        plan.backOff?.let { target ->
                            routeStatus(RoutePhase.FOLLOWING,tr("Back-off of %.0f cm towards the safe surface · blades OFF", "Recul de %.0f cm vers la surface sûre · lames OFF").format(target.distance(position)*100))
                            reverseStraight(position,target,heading,physical,heightMm,"ROUTE",recovering=true,degreesPerUnit=routeReckoning.degreesPerUnit)
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
                        if (fr.luba.poc.protocol.OffMapLatch.ignored(latched.selfCheck,guardSensors(latched.sensorStatus),
                                fr.luba.poc.simulation.PointPlanner.atExclusion(state.value.robotMap,latched.x?.let { x -> latched.y?.let { y -> Point(x,y) } }))) {
                            event("route_exclusion_latch",tr("Self-check 25 at a no-go zone without a contact code: stop without an obstacle", "Self-check 25 contre une zone interdite sans code de contact : arrêt sans obstacle"),
                                mapOf("edge" to pause.index,"x" to latched.x,"y" to latched.y,"heading" to latched.heading))
                            error(tr("Robot outside its task area (self-check 25) against a no-go zone: take it out with the joystick, blades stopped, then resume",
                                "Robot hors de sa zone de travail (self-check 25) contre une zone interdite : le sortir au joystick, lames arrêtées, puis reprendre"))
                        }
                        if (CollisionLatch.latched(latched.selfCheck) && latched.x!=null && latched.y!=null && latched.heading!=null) {
                            val heading=routeReckoning.heading(requireNotNull(latched.heading))
                            // At the limit of the task area, [avoidObstacle] classifies it as a border contact ([EdgeContact]):
                            // back-off and resume, logged as route_edge_contact.
                            if (!fr.luba.poc.protocol.OffMapLatch.ignored(latched.selfCheck,latched.sensorStatus,
                                    fr.luba.poc.simulation.PointPlanner.beyondMapped(state.value.robotMap,Point(requireNotNull(latched.x),requireNotNull(latched.y)))))
                                event("route_latch_contact",tr("Collision lock without a contact code: handled as a contact", "Verrou de collision sans code de contact : traité comme un contact"),
                                    mapOf("edge" to pause.index,"x" to latched.x,"y" to latched.y,"heading" to heading))
                            return try { avoidObstacle(ObstacleEncounter(pause.index,RouteContact(CollisionLatch.VALUE.toInt(),requireNotNull(latched.x),
                                requireNotNull(latched.y),heading,System.currentTimeMillis()),emptyList(),tr("Contact (collision lock)", "Contact (verrou de collision)"))) }
                                catch (drift: RouteDrift) { recoverDrift(drift) }
                        }
                        ProtectionPause.refusal(protectionPauses)?.let { error(tr("${pause.message}: $it", "${pause.message} : $it")) }
                        protectionPauses++
                        purgeQueuedCommands(); send(commands.stop(),"ROUTE_PROTECTION_STOP")
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
                                val windowStatus=tr("End of the working hours ${fr.luba.poc.mowing.WorkWindowSetting.value.label} · BLE return · blades OFF", "Fin de la plage horaire ${fr.luba.poc.mowing.WorkWindowSetting.value.label} · retour BLE · lames OFF")
                                routeStatus(RoutePhase.RETURNING,windowStatus)
                                val paths=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RobotRoute.rechargePaths(engine,saved,requireNotNull(live.heading),missionRoutes) }
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
                            val paths=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { RobotRoute.rechargePaths(engine,saved,savedHeading,missionRoutes) }
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
                            val smart=routeCheckpoint?.let { checkpoint -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
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
                            val resuming=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
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
                    historyEnd(tr("Mowing finished · charging contact confirmed", "Tonte terminée · contact de charge confirmé"), completed = true)
                    routeStatus(RoutePhase.COMPLETE,if(intent==RouteIntent.RETURN_TO_BASE) tr("Return to base · charging contact confirmed", "Retour à la base · contact de charge confirmé") else tr("Mowing finished · charging contact confirmed", "Tonte terminée · contact de charge confirmé"))
                    requestStop(false,tr("Route finished — on the base, charging contact confirmed", "Trajet terminé — sur la base, contact de charge confirmé"), intentional=true)
                } catch (cancel: CancellationException) { throw cancel }
                catch (end: WorkWindowEnded) {
                    val reason=tr("End of the working hours (${fr.luba.poc.mowing.WorkWindowSetting.value.label}): robot on the base, mowing to be resumed", "Fin de la plage horaire de travail (${fr.luba.poc.mowing.WorkWindowSetting.value.label}) : robot sur la base, tonte à reprendre")
                    event("work_window_docked",reason)
                    requestStop(false,reason,intentional=true)
                }
                catch (error: Exception) {
                    // Gateway link failing during the recharge: automatic resume, robot charging ([DockResume]).
                    val resumeEngine=checkpointEngine
                    val dockResume=(state.value.viaGateway || dockResumeAnyLink) && DockResume.wanted(rechargeCycle,state.value.robotRoute.running,routeCheckpoint!=null) &&
                        DockResume.linkCause(error.message,gatewayOutageSince!=0L)
                    requestStop(false,tr("Route interrupted: ${error.message}", "Trajet interrompu : ${error.message}"))
                    if (dockResume && resumeEngine!=null) planDockResume(resumeEngine,error.message ?: tr("gateway link", "liaison passerelle"))
                }
            }
            return true
        } catch (error: Exception) {
            update { it.copy(robotRoute=it.robotRoute.copy(status=tr("Start refused: ${error.message}", "Départ refusé : ${error.message}"))) }
            event("robot_route_refused",error.message ?: tr("Start refused", "Départ refusé"))
            status(tr("Mowing refused: ${error.message}", "Tonte refusée : ${error.message}"))
            return false
        }
    }

    /** Immediate stop on lift: critical alert, zero-speed STOP and end of any activity in progress. */
    private fun liftDetected(reason: String, source: String) {
        val t = state.value.telemetry
        // Joystick stopped by the wheels without progress: often the bumper pushing against an object. Its 1014 comes with
        // the next error read: same grace window as a collision protection, so the contact is still logged on the map.
        val manualStall = source == "wheel_progress" && state.value.manualActive
        event("lift_detected", reason, mapOf("source" to source, "sensorStatus" to t.sensorStatus, "x" to t.x, "y" to t.y,
            "robotState" to t.robotState, "selfCheck" to t.selfCheck))
        liftGuard.reset()
        alert(AlertKind.LIFTED, Severity.CRITICAL, reason)
        requestStop(false, reason)
        if (manualStall) {
            manualContactUntil = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
            if (state.value.ready && !closing) send(commands.errorQuery(), "MANUAL_STALL_ERRORS")
        }
    }
    private fun requestStop(close: Boolean, reason: String, intentional: Boolean = false) {
        if (stopPending) {
            // A shutdown must still close a link whose non-closing STOP is already in flight.
            if (close && !closing) {
                closeAfterStop = true
                closing = true
                periodic?.cancel(); periodic = null
                update { it.copy(ready = false, connection = ConnectionState.Closing) }
                val stoppingGatt = gatt
                viewModelScope.launch {
                    delay(2000)
                    if (gatt === stoppingGatt && gatt != null && closing) release()
                }
            }
            return
        }
        taskEvent(fr.luba.poc.history.TaskEventType.STOP, reason, mapOf("intentional" to intentional))
        if (intentional) intentionalAlertStop()
        // Stop on a terrain difficulty (blockage, wheel slip, lift, deviation…): recorded on the map.
        if (!intentional) DifficultyClassifier.fromReason(reason)?.let { recordDifficulty(it, reason) }
        val external = state.value.robotRoute.running
        if (!alertLinkFault) alertCommunicationExpected = false
        if(!intentional && (external || state.value.navTest.running || state.value.manualActive)) {
            alertRouteFailure(reason)
            alertMotionFailure(reason)
            alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, reason)
        }
        // Manual mode held for bumper detection (obstacle test, navigation, mowing): leave it on STOP.
        val bumperManualMode = (state.value.teach.running && obstacleManualMode) || navBumperMode || state.value.telemetry.robotState == 20
        // Captured before the route, tests and height adjustment are cancelled: their target if one owned the deck.
        val stopHeightMm = holdHeightMm()
        navBumperMode = false
        confirmedHeading = null
        cancelRobotRoute(reason)
        backOffJob?.cancel()
        preparationJob?.cancel(); preparationJob = null
        stopManual(reason, sendStop = false)
        abortSilenceTest(reason)
        val wasReady = state.value.ready
        stopReason = reason
        bladeJob?.cancel(); bladeJob = null
        update { it.copy(bladeTestActive = false) }
        if (bladeTestId.isNotBlank()) recordBladeResult("INTERRUPTED_OFF_UNCONFIRMED", reason)
        stopHeightSequence(reason)
        abortBladeHeight(reason)
        val nativeMission = !external && (state.value.mowing.running || state.value.telemetry.robotState in setOf(13, 14))
        interruptMowing(reason)
        abandonNav(reason, userInitiated = intentional)
        finishObstacle("ABORTED", reason, sendStop = false)
        stopRequestedAt = SystemClock.elapsedRealtime()
        liftGuard.commanded(0, stopRequestedAt)
        event("stop_request", reason, mapOf("linear" to 0, "angular" to 0, "ready" to wasReady))
        if (close) { periodic?.cancel(); periodic = null }
        handshake?.cancel(); handshake = null
        // Finish the fragmented message already in flight; never append the STOP protobuf to it.
        val continuation = (current as? Operation.Write)?.let { active ->
            queue.filterIsInstance<Operation.Write>().filter { it.batch == active.batch }
        }.orEmpty()
        queue.clear()
        queue.addAll(continuation)
        closeAfterStop = close
        if (close) { closing = true; update { it.copy(ready = false, connection = ConnectionState.Closing) } }
        status(tr("$reason — zero-speed STOP requested", "$reason — STOP vitesse nulle demandé"))
        if (gatt == null || !wasReady) {
            status(tr("STOP not sent: GATT unavailable", "STOP non transmis : GATT indisponible"))
            if (close) release()
            return
        }
        if (stopPending) return
        stopPending = true
        // The TX sequence is assigned in pump(), after the purge, without creating a gap.
        if (bumperManualMode || bladeMayBeOn || (fr.luba.poc.protocol.ModelTrial.testAllowed(state.value.selected?.name, SystemClock.elapsedRealtime()) && state.value.telemetry.sensorStatus?.let { ((it shr 9) and 7) != 0 } == true)) {
            val offBatch = ++batchCounter
            blufi.fragment(commands.manualBlades(false, stopHeightMm), state.value.mtu).forEach { queue.addLast(Operation.Write(it, "BLADES_OFF", offBatch)) }
        }
        // Docking of a route handed over to the Mammotion return (state 14): return cancelled before the STOP.
        if (external && state.value.telemetry.robotState == 14) {
            val cancelBatch = ++batchCounter
            blufi.fragment(commands.cancelReturnToDock(), state.value.mtu).forEach { queue.addLast(Operation.Write(it, "ROUTE_NATIVE_DOCK_CANCEL", cancelBatch)) }
        }
        if (nativeMission || (external && state.value.telemetry.robotState == 14)) {
            val pauseBatch = ++batchCounter
            blufi.fragment(commands.taskControl(2), state.value.mtu).forEach { queue.addLast(Operation.Write(it, "MOW_EMERGENCY_PAUSE", pauseBatch)) }
        }
        val frames = blufi.fragment(commands.stop(), state.value.mtu)
        val batch = ++batchCounter
        frames.forEachIndexed { index, frame -> queue.addLast(Operation.Write(frame, "STOP linear=0 angular=0", batch, index == frames.lastIndex)) }
        pump()
        val stoppingGatt = gatt
        if (close) viewModelScope.launch {
            delay(2000)
            if (gatt === stoppingGatt && gatt != null && closing) { status(tr("STOP not confirmed before closing — check that the robot has physically stopped", "STOP non confirmé avant fermeture — vérifier arrêt physique")); release() }
        }
        else {
            // The STOP ends the stream renewal; no automatic resume.
            update { it.copy(connection = ConnectionState.Receiving) }
        }
    }
    /** Global stop already in progress: the test is closed without an extra STOP. */
    private fun abortSilenceTest(reason: String) {
        val job = silenceJob ?: return
        job.cancel()
        val probe = silenceProbe
        val id = state.value.selected?.address
        silenceJob = null; silenceProbe = null
        if (probe == null || id == null) { update { it.copy(silenceTest = it.silenceTest.copy(running = false, status = reason)) }; return }
        val result = MotorWatchdogResult.of(id, System.currentTimeMillis(), state.value.telemetry.firmware, state.value.silenceTest.linearRaw,
            probe.abort(tr("$reason: inconclusive test", "$reason : essai non concluant")))
        val kept = recordWatchdog(result)
        update { it.copy(motorWatchdog = kept, silenceTest = it.silenceTest.copy(running = false, phase = "", status = tr("Inconclusive — ${result.detail}", "Non concluant — ${result.detail}"))) }
        event("silence_test_result", tr("Inconclusive", "Non concluant"), mapOf("outcome" to result.outcome, "detail" to result.detail))
    }
    /** Route of the current or interrupted mowing: resumed as is after a gateway update. */
    private var checkpointEngine: MowingSimulation? = null
    /** Announcement of a gateway update ([fr.luba.poc.gateway.GatewayProtocol.OTA_BEGIN_EVENT]); 0 otherwise. */
    private var gatewayUpdateAt = 0L
    private var gatewayUpdateResume: kotlinx.coroutines.Job? = null

    /** Gateway update announced and not finished yet (resume pending or restart window). */
    private fun gatewayUpdating() = gatewayUpdateAt != 0L && (gatewayUpdateResume?.isActive == true ||
        SystemClock.elapsedRealtime() - gatewayUpdateAt <= fr.luba.poc.gateway.GatewayOta.RESUME_WINDOW_MS && !state.value.ready)

    private fun gatewayUpdateRestart(link: RobotLink, code: Int) = link is fr.luba.poc.gateway.GatewayLink &&
        fr.luba.poc.gateway.GatewayLink.gatewayDisconnected(code) && gatewayUpdateAt != 0L &&
        SystemClock.elapsedRealtime() - gatewayUpdateAt <= fr.luba.poc.gateway.GatewayOta.RESTART_WINDOW_MS

    /**
     * The ESP32 gateway is about to restart for an update: it stops the robot (blades then speed) and closes the session
     * within 3 s. Mowing in progress and the "Updates during mowing" setting on (otherwise the gateway would have refused it):
     * the mowing resumes by itself from its saved progress as soon as the gateway and the robot are back.
     */
    private fun gatewayUpdateAnnounced(text: String) {
        gatewayUpdateAt = SystemClock.elapsedRealtime()
        val s = state.value
        val engine = checkpointEngine
        val mowing = s.robotRoute.running && routeCheckpoint != null && engine != null
        event("gateway_update_announced", text, mapOf("mowing" to mowing, "resumeAllowed" to fr.luba.poc.gateway.GatewayOta.duringMowing))
        if (mowing && fr.luba.poc.gateway.GatewayOta.duringMowing) planGatewayUpdateResume(engine!!)
        else status(tr("Gateway update: restarting, automatic reconnection when it is back", "Mise à jour de la passerelle : redémarrage, reconnexion automatique à son retour"))
    }

    /**
     * Mowing resume after the gateway restarts: the only automatic resume after a link loss, because the stop was
     * announced then written by the gateway. Bounded by [fr.luba.poc.gateway.GatewayOta.RESUME_WINDOW_MS] and 3 attempts;
     * a refusal (map, position, battery…) leaves the resume to the user and raises an alert.
     */
    private fun planGatewayUpdateResume(engine: MowingSimulation) {
        gatewayUpdateResume?.cancel()
        gatewayUpdateResume = viewModelScope.launch {
            val began = SystemClock.elapsedRealtime()
            val deadline = began + fr.luba.poc.gateway.GatewayOta.RESUME_WINDOW_MS
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
                if (gatt == null && !closing && now - lastRetry >= 5000) { lastRetry = now; retryGatewayLink() }
                val settled = s.ready && gatt != null && !closing && readyAt > gatewayUpdateAt && now - readyAt >= 8000 &&
                    lastTelemetry != 0L && positionPrevious != 0L && now - positionPrevious <= 2000 && !s.mapLoading
                if (!settled) continue
                attempts++
                if (startRoute(engine, checkpoint.cutting, checkpoint.heightMm, RouteIntent.FOLLOW, checkpoint)) {
                    event("gateway_update_resumed", tr("Mowing resumed after the gateway update", "Tonte reprise après la mise à jour de la passerelle"),
                        mapOf("attempt" to attempts, "afterMs" to now - gatewayUpdateAt, "edge" to checkpoint.edge, "progress" to checkpoint.progress))
                    status(tr("Mowing resumed after the gateway update", "Tonte reprise après la mise à jour de la passerelle"))
                    return@launch
                }
                lastRefusal = state.value.robotRoute.status.removePrefix(tr("Start refused: ", "Départ refusé : "))
                if (attempts >= 3) break
                delay(8000)
            }
            event("gateway_update_resume_failed", tr("Automatic resume impossible after the gateway update", "Reprise automatique impossible après la mise à jour de la passerelle"),
                mapOf("attempts" to attempts, "reason" to lastRefusal))
            alert(AlertKind.RESUME_FAILED, Severity.WARNING, tr("Gateway update: automatic resume impossible ($lastRefusal). Resume manually.", "Mise à jour de la passerelle : reprise automatique impossible ($lastRefusal). Reprendre à la main."))
        }
    }

    /** Recharge cycle of a mowing in progress (return at the low threshold, docking, charging, until leaving the base) ([DockResume]). */
    @Volatile private var rechargeCycle = false
    /** Test benches: simulated link treated as the gateway for [DockResume] (resume reserved for the gateway). */
    internal var dockResumeAnyLink = false
    private var dockResumeJob: kotlinx.coroutines.Job? = null

    /**
     * Mowing resume after losing the gateway during the recharge ([DockResume]): link back and stable, robot charging on
     * the base (Mammotion docking finished), then resume from the saved progress, like "Resume" (charge up to the high
     * threshold if needed, leave the base, link). Bounded by [DockResume.WINDOW_MS] and [DockResume.MAX_ATTEMPTS]
     * attempts; a refusal leaves the resume to the user and raises an alert.
     */
    private fun planDockResume(engine: MowingSimulation, cause: String) {
        if (dockResumeJob?.isActive == true) return
        val since = SystemClock.elapsedRealtime()
        event("dock_resume_planned", tr("Gateway lost during the recharge: mowing resumes by itself once the robot is charging and the link is back", "Passerelle perdue pendant la recharge : tonte reprise seule, robot en charge et liaison revenue"),
            mapOf("cause" to cause))
        val waiting = tr("Automatic resume as soon as the robot is charging on the base and the gateway is back", "Reprise automatique dès que le robot est en charge sur la base et la passerelle revenue")
        update { it.copy(autoResumeStatus = waiting) }
        dockResumeJob = viewModelScope.launch {
            fun done(text: String) { update { it.copy(autoResumeStatus = "") }; status(text) }
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
                if (gatt == null && !closing && now - lastRetry >= 5000) { lastRetry = now; retryGatewayLink() }
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
            alert(AlertKind.RESUME_FAILED, Severity.WARNING, tr("Gateway lost during recharging: automatic resume impossible ($lastRefusal). Resume manually.", "Passerelle perdue pendant la recharge : reprise automatique impossible ($lastRefusal). Reprendre à la main."))
        }
    }

    private fun release() {
        // Link lost while a mowing is recharging: resume on its own once the link is back ([DockResume]).
        val dockResume = unexpectedLoss && (state.value.viaGateway || dockResumeAnyLink) &&
            DockResume.wanted(rechargeCycle, state.value.robotRoute.running, routeCheckpoint != null)
        val resumeEngine = checkpointEngine
        abortSilenceTest(tr("Link closed", "Liaison fermée"))
        cancelRobotRoute(tr("Link closed", "Liaison fermée"))
        if (dockResume && resumeEngine != null) planDockResume(resumeEngine, tr("Link closed", "Liaison fermée"))
        preparationJob?.cancel(); preparationJob = null
        stopManual(tr("Link closed", "Liaison fermée"), sendStop = false)
        mapJob?.cancel(); mapTransfer = null; update { it.copy(mapLoading = false, mapReadFailure = null) }
        periodic?.cancel(); handshake?.cancel(); operationTimeout?.cancel()
        periodic = null; handshake = null; operationTimeout = null
        queue.clear(); current = null; stopPending = false
        val old = gatt; gatt = null
        try { old?.close() } catch (_: Exception) { }
        closing = false
        update { it.copy(connection = ConnectionState.Disconnected, ready = false, viaGateway = false, rssi = null, telemetry = it.telemetry.copy(positionRate = 0.0)) }
        bladeJob?.cancel(); bladeJob = null
        update { it.copy(bladeTestActive = false) }
        if (bladeTestId.isNotBlank()) recordBladeResult("LINK_CLOSED_OFF_UNCONFIRMED", tr("Link closed; physical stop required if in doubt", "Liaison fermée ; arrêt physique requis si doute"))
        stopHeightSequence(tr("Link closed", "Liaison fermée"))
        abortBladeHeight(tr("Link closed", "Liaison fermée"))
        interruptMowing(tr("Link closed", "Liaison fermée"))
        abandonNav(tr("Link closed", "Liaison fermée"))
        finishObstacle("ABORTED", tr("Link closed", "Liaison fermée"), sendStop = false)
        finishBorder("ABORTED", tr("Link closed", "Liaison fermée"), sendStop = false)
        bladeMayBeOn = false; bladeOffPending = false; bladeStopVerified = false
        event("disconnect", tr("GATT closed; no command resumed after a failure", "GATT fermé ; aucune commande reprise après panne"), mapOf("reason" to stopReason))
        val lost = unexpectedLoss; unexpectedLoss = false
        if (lost && wantLink && !state.value.campaign.running && expectedFailure == null && scheduleReconnect()) return
        wantLink = false
        fr.luba.poc.relay.RobotLinkService.stop(getApplication())
    }
    /** Tries to restore the link alone (telemetry); no mission or command is resumed. */
    private fun scheduleReconnect(): Boolean {
        val device = state.value.selected ?: return false
        val now = SystemClock.elapsedRealtime()
        // Counter reset only if the previous link stayed ready for more than a minute.
        if (readyAt > reconnectMark && now - readyAt > 60_000) reconnectAttempts = 0
        reconnectMark = now
        if (reconnectAttempts >= MAX_RECONNECTS) {
            status(tr("Link lost: automatic reconnection abandoned after $MAX_RECONNECTS attempts", "Liaison perdue : reconnexion automatique abandonnée après $MAX_RECONNECTS essais"))
            return false
        }
        val attempt = ++reconnectAttempts
        val wait = 2000L * attempt
        status(tr("Link lost — automatic reconnection $attempt/$MAX_RECONNECTS in ${wait / 1000} s; no command resumed", "Liaison perdue — reconnexion automatique $attempt/$MAX_RECONNECTS dans ${wait / 1000} s ; aucune commande reprise"))
        reconnectTimer?.cancel()
        reconnectTimer = viewModelScope.launch {
            delay(wait)
            if (gatt == null && wantLink && !closing) { event("reconnect", tr("Automatic reconnection $attempt", "Reconnexion automatique $attempt"), mapOf("attempt" to attempt)); connect(device) }
        }
        return true
    }
    /** [modelAllowed]: model check of the blade test, which a model trial opens (height adjustments keep the validated model). */
    private fun bladeGuard(heightMm: Int, modelAllowed: Boolean = fr.luba.poc.protocol.ModelSupport.motionValidated(state.value.selected?.name)) {
        check(!state.value.robotRoute.running && !state.value.mowing.active) { tr("Finish the mowing mission first", "Terminer la mission de tonte") }
        check(!state.value.taskStopPending && gatt != null && state.value.ready && !closing && log.healthy) { tr("BLE / log not ready", "BLE / journal non prêt") }
        check(!state.value.navTest.running) { tr("Finish the random walk before this cutting test", "Terminer la marche aléatoire avant cet essai de coupe") }
        check(!state.value.teach.running) { tr("Finish the obstacle learning before this cutting test", "Terminer l'apprentissage d'obstacle avant cet essai de coupe") }
        val t = state.value.telemetry
        BladeSafety.requireReady(state.value.selected?.name,
            if (bladeDeviceAt == 0L) null else SystemClock.elapsedRealtime() - bladeDeviceAt,
            t.battery, t.robotState, guardSensors(t.sensorStatus), t.selfCheck, state.value.campaign.running, heightMm, state.value.cutHeights, modelAllowed)
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
        if (state.value.robotRoute.running) { status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
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
            heightJob = viewModelScope.launch { runBladeHeight(id, mm, timeoutMs) }
        } catch (error: Exception) { update { it.copy(bladeStatus = tr("Height refused: ${error.message}", "Hauteur refusée : ${error.message}")) }; event("blade_test_refused", error.message ?: tr("Height refused", "Hauteur refusée")) }
    }

    private suspend fun runBladeHeight(id: String, mm: Int, timeoutMs: Long) {
        val since = System.currentTimeMillis()
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
                    if (!driverSent) { send(commands.bladeHeight(mm), "BLADE_HEIGHT $mm mm"); driverSent = true }
                    val moving = if (t.heightMoving == true) tr(" · deck moving towards ${t.heightTargetMm ?: "—"} mm", " · plateau en mouvement vers ${t.heightTargetMm ?: "—"} mm") else ""
                    update { it.copy(bladeStatus = tr("Height $mm mm requested · ${t.bladeHeightMm ?: "—"} mm reported$moving", "Hauteur $mm mm demandée · ${t.bladeHeightMm ?: "—"} mm rapportés$moving")) }
                } else {
                    check(modeObserved || now - began < 6000) { tr("Manual mowing mode not confirmed (state ${t.robotState ?: "—"}): height not applied", "Mode tonte manuelle non confirmé (état ${t.robotState ?: "—"}) : hauteur non appliquée") }
                    // Re-send if the mode is not (or no longer) active: same command, blades still OFF.
                    if (now - modeAt >= 2000) { heightModeSent = true; send(commands.manualMow(1, 0, mm), "BLADE_HEIGHT_MODE $mm mm"); modeAt = now }
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
            val exitWall = System.currentTimeMillis()
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
        send(commands.manualMow(0, 0, mm), "BLADE_HEIGHT_MODE_OFF")
        return true
    }

    /** External interruption: cancels the wait, leaves manual mode if possible, result unconfirmed. */
    private fun abortBladeHeight(reason: String) {
        heightJob?.cancel(); heightJob = null
        exitHeightMode(heightTarget ?: bladeHeightRequest)
        if (heightTarget != null) recordHeightResult("INTERRUPTED", reason)
    }
    /** Checks the OFF path before any ON; no implicit activation. */
    fun prepareBladeStop(heightMm: Int) {
        if (state.value.mowing.active) return
        if (heightTarget != null) { update { it.copy(bladeStatus = tr("Height pending: cancel the wait or wait for its result before checking OFF", "Hauteur en attente : annuler l’attente ou attendre son résultat avant de vérifier OFF")) }; return }
        if (!state.value.ready || closing || state.value.teach.running || state.value.campaign.running || state.value.bladeTestActive || state.value.navTest.running || heightTarget != null) return
        if (!fr.luba.poc.protocol.ModelTrial.testAllowed(state.value.selected?.name, SystemClock.elapsedRealtime())) return
        bladeHeightRequest = state.value.cutHeights.clamp(heightMm)
        bladeStopVerified = false; bladeOffWrittenAt = 0; bladeOffPending = true; bladeMayBeOn = true
        update { it.copy(bladeStatus = tr("Blade stop check: OFF requested", "Vérification arrêt lames : OFF demandé")) }
        queueBladeOff(tr("OFF check before the test", "Vérification OFF avant essai"))
    }
    /** Supervised rotation test: two-step sequence (manual mowing mode, then discs), with the disc command
     *  renewed during the test. No travel speed is sent: on firmware 1.30, a stream of zero speeds counts
     *  as "inactivity", which prevents the start. */
    fun startBladeTest(heightMm: Int, useKnifeControl: Boolean = false) {
        if (state.value.robotRoute.running) { status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mapLoading) { status(tr("Wait for the map reading", "Attendre la lecture de carte")); return }
        try {
            bladeGuard(heightMm, fr.luba.poc.protocol.ModelTrial.testAllowed(state.value.selected?.name, SystemClock.elapsedRealtime()))
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
            send(commands.cutterQuery(), "BLADE_CUTTER_QUERY")
            bladeJob = viewModelScope.launch { runBladeStart(heightMm, useKnifeControl) }
        } catch (error: Exception) { update { it.copy(bladeStatus = tr("Rotation refused: ${error.message}", "Rotation refusée : ${error.message}")) }; event("blade_test_refused", error.message ?: tr("Rotation refused", "Rotation refusée")) }
    }

    private suspend fun runBladeStart(heightMm: Int, useKnifeControl: Boolean) {
        try {
            send(commands.manualMow(mainCtrl = 1, cutKnifeCtrl = 0, heightMm = heightMm), "BLADE_MODE_ON")
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
                if (useKnifeControl) send(commands.knifeControl(true), "BLADES_ON_ALT_KNIFE")
                else send(commands.manualMow(mainCtrl = 1, cutKnifeCtrl = 1, heightMm = heightMm), "BLADES_ON_KEEPALIVE")
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
        if (state.value.robotRoute.running) { intentionalAlertStop(); requestStop(false,reason,intentional=true); return }
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
        val continuation = (current as? Operation.Write)?.let { active -> queue.filterIsInstance<Operation.Write>().filter { it.batch == active.batch } }.orEmpty()
        queue.clear(); queue.addAll(continuation)
        send(commands.manualBlades(false, bladeHeightRequest), "BLADES_OFF")
        if (bladeAltUsed) send(commands.knifeControl(false), "BLADES_OFF_ALT_KNIFE")
        send(commands.stop(), "BLADE_TEST_ZERO linear=0 angular=0")
        val connection = gatt
        viewModelScope.launch {
            delay(5000)
            if (gatt === connection && bladeOffPending && bladeOffGeneration == generation) {
                if (bladeTestId.isNotBlank()) recordBladeResult("OFF_UNCONFIRMED", tr("No fresh OFF state within 5 s after the command", "Pas d’état OFF frais après la commande dans les 5 s"))
                update { it.copy(bladeStatus = tr("Blade stop not confirmed; use the physical stop", "Arrêt lames non confirmé ; utiliser l’arrêt physique")) }
                requestStop(true, tr("Blade OFF not confirmed", "OFF lames non confirmé"))
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
    private fun recordBladeResult(outcome: String, detail: String) {
        val observed = tr("$detail · manual mowing mode ${if (bladeModeObserved) "observed" else "not observed"}", "$detail · mode tonte manuelle ${if (bladeModeObserved) "observé" else "non observé"}") +
            tr(" · disc ${if (bladeOnObserved) "ON observed" else "never ON"} · RPM ${state.value.telemetry.cutterRpm ?: "—"}", " · disque ${if (bladeOnObserved) "ON observé" else "jamais ON"} · RPM ${state.value.telemetry.cutterRpm ?: "—"}") +
            " · knife_status ${state.value.telemetry.knifeStatus ?: "—"}"
        persistBladeRotation(outcome, observed)
        val result = TestResult(bladeTestId, tr("Supervised blade rotation", "Rotation lames supervisée"), outcome, SystemClock.elapsedRealtime() - bladeTestStarted, observed)
        update { it.copy(bladeResults = it.bladeResults + result, bladeStatus = observed) }
        event("blade_test_result", observed, resultFields(result))
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
        val result = TestResult(heightTestId, "Hauteur ${heightTarget} mm", outcome, SystemClock.elapsedRealtime() - heightStarted, detail)
        update { it.copy(bladeHeightPending = false, bladeResults = it.bladeResults + result, bladeStatus = detail) }
        event("blade_height_result", detail, resultFields(result))
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
            heightSequenceJob = viewModelScope.launch {
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
                event("blade_height_sequence_result", detail, resultFields(result))
                log.report(result.id, resultFields(result) + mapOf("kind" to "blade_height_sequence", "targets" to targets, "startMm" to startMm))
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
        event("blade_height_observation", detail, resultFields(result) + mapOf("requestedMm" to bladeHeightRequest))
        log.report(result.id, resultFields(result) + mapOf("kind" to "height_observation", "requestedMm" to bladeHeightRequest))
    }

    private fun purgeQueuedCommands() {
        val continuation = (current as? Operation.Write)?.let { active -> queue.filterIsInstance<Operation.Write>().filter { it.batch == active.batch } }.orEmpty()
        queue.clear(); queue.addAll(continuation)
    }

    /** Input refreshed by the finger; BLE sending at 5 Hz with independent guards. */
    fun beginManualGesture() { manualBlocked = false }

    /**
     * Self-check 25 ignored for a blade-OFF move (joystick, move to a point): robot beyond the mapped zones, bumper and
     * lift clear ([fr.luba.poc.protocol.OffMapLatch]).
     */
    private fun offMapLatchIgnored(t: fr.luba.poc.protocol.Telemetry): Boolean =
        fr.luba.poc.protocol.OffMapLatch.ignored(t.selfCheck, guardSensors(t.sensorStatus),
            fr.luba.poc.simulation.PointPlanner.beyondMapped(state.value.robotMap, t.x?.let { x -> t.y?.let { y -> Point(x, y) } }))

    /**
     * Self-check 25 ignored for the joystick: robot beyond the mapped zones or at a no-go zone, bumper and lift clear
     * ([fr.luba.poc.protocol.OffMapLatch.atEdge]). 2026-10-25, 15:33: rear of the chassis inside a no-go zone after a
     * pivot, back-off impossible, joystick refused: the only way out was forward, by hand.
     */
    private fun edgeLatchIgnored(t: fr.luba.poc.protocol.Telemetry): Boolean =
        fr.luba.poc.protocol.OffMapLatch.ignored(t.selfCheck, guardSensors(t.sensorStatus),
            fr.luba.poc.protocol.OffMapLatch.atEdge(state.value.robotMap, t.x?.let { x -> t.y?.let { y -> Point(x, y) } }))

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
            check(fr.luba.poc.protocol.ModelTrial.testAllowed(robotName, SystemClock.elapsedRealtime())) { tr("LUBA 2 required (or model trial armed)", "LUBA 2 requis (ou essai du modèle armé)") }
            // 20: manual mode kept by the previous gesture for bumper detection (released a moment ago).
            check(t.robotState in listOf(11,20)) { tr("Robot not ready", "Robot non prêt") }
            val failure = TeachSafety.tickFailure(if (devicePrevious == 0L) null else SystemClock.elapsedRealtime()-devicePrevious,
                t.battery,t.sensorStatus,30)
            check(failure == null) { failure ?: tr("State unavailable", "État indisponible") }
            // Beyond the mapped zones or at a no-go zone, 25 does not prevent driving freely ([edgeLatchIgnored]); nor does a
            // positioning condition (41 weak satellite signal, 2026-10-22): the joystick does not need RTK and brings the robot under open sky.
            check(fr.luba.poc.protocol.SelfCheck.joystickAllowed(t.selfCheck) || edgeLatchIgnored(t)) { fr.luba.poc.protocol.SelfCheck.refusal(t.selfCheck) }
            check(!TeachSafety.bumperHealthAlert(guardSensors(t.sensorStatus))) {
                fr.luba.poc.protocol.BumperRest.refusal(guardSensors(t.sensorStatus), fr.luba.poc.protocol.ModelSupport.motionValidated(robotName)) }
            // Model trial: joystick bounded to the trial ceilings (low speed).
            manualCommand = fr.luba.poc.protocol.ModelTrial.joystick(robotName, linear, angular); manualInputAt = SystemClock.elapsedRealtime()
            if (manualCommand.first != 0) manualLastLinear = manualCommand.first
            if (manualJob == null) {
                val trial = !fr.luba.poc.protocol.ModelSupport.motionValidated(robotName)
                update { it.copy(manualActive = true, manualStatus = if (trial) tr("Model trial: low-speed control — release to stop", "Essai du modèle : pilotage lent — relâchez pour arrêter")
                    else tr("Control active — release to stop", "Pilotage actif — relâchez pour arrêter")) }
                event("manual_start", tr("Joystick control, blades OFF", "Pilotage joystick, lames OFF"), if (trial) mapOf("modelTrial" to true, "robotName" to robotName,
                    "maxLinear" to fr.luba.poc.protocol.ModelTrial.MAX_LINEAR, "maxAngular" to fr.luba.poc.protocol.ModelTrial.MAX_ANGULAR) else emptyMap())
                manualJob = viewModelScope.launch {
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
                            check(fr.luba.poc.protocol.ModelTrial.testAllowed(state.value.selected?.name, now)) { tr("Model trial ended", "Essai du modèle terminé") }
                            val guard = TeachSafety.tickFailure(if (devicePrevious == 0L) null else now-devicePrevious,
                                currentTelemetry.battery,currentTelemetry.sensorStatus,30)
                            // Precise reason in the log: on 10-14, "Protection / incompatible robot state" did not say that
                            // the robot had just published self-check 25.
                            check(guard == null) { guard ?: "" }
                            check(fr.luba.poc.protocol.SelfCheck.joystickAllowed(currentTelemetry.selfCheck) || edgeLatchIgnored(currentTelemetry)) {
                                fr.luba.poc.protocol.SelfCheck.refusal(currentTelemetry.selfCheck) }
                            check(!TeachSafety.bumperHealthAlert(guardSensors(currentTelemetry.sensorStatus))) {
                                fr.luba.poc.protocol.BumperRest.refusal(guardSensors(currentTelemetry.sensorStatus), fr.luba.poc.protocol.ModelSupport.motionValidated(state.value.selected?.name)) +
                                    tr(" (sensors ${currentTelemetry.sensorStatus})", " (capteurs ${currentTelemetry.sensorStatus})") }
                            check(currentTelemetry.robotState in listOf(11,20)) { tr("Incompatible robot state (${currentTelemetry.robotState})", "État robot incompatible (${currentTelemetry.robotState})") }
                            check(state.value.ready && !closing && log.healthy) { tr("Link unavailable", "Liaison indisponible") }
                            check(queue.size <= 4) { tr("BLE queue saturated", "File BLE saturée") }
                            if (current == null && queue.isEmpty()) send(commands.movement(manualCommand.first,manualCommand.second), "MANUAL_JOYSTICK")
                            if (now >= bumperModeAt && currentTelemetry.robotState in listOf(11, 20) && !bladeMayBeOn) {
                                send(commands.manualMow(1, 0, holdHeightMm()), "MANUAL_BUMPER_MODE", periodic = true); bumperModeAt = now + 500 }
                            if (now >= errorsAt) { send(commands.errorQuery(), "MANUAL_ERRORS", periodic = true); errorsAt = now + 500 }
                            delay(200)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Exception) { manualInterrupted(error.message ?: tr("Control interrupted", "Pilotage interrompu")) }
                }
            }
            return true
        } catch (error: Exception) {
            // Refused while driving (finger still on the joystick): a collision protection is handled like in the loop.
            if (state.value.manualActive && ManualContact.suspected(state.value.telemetry.selfCheck, guardSensors(state.value.telemetry.sensorStatus))) {
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
        val sensors = guardSensors(live.sensorStatus)
        val contact = ManualContact.suspected(live.selfCheck, sensors)
        if (!contact || TeachSafety.bumperHealthAlert(sensors)) alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, reason)
        stopManual(reason)
        if (!contact) return
        manualContactUntil = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
        if (state.value.ready && !closing) send(commands.errorQuery(), "MANUAL_CONTACT_ERRORS")
        recordManualContact(null)
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
            purgeQueuedCommands(); send(commands.stop(), "MANUAL_STOP")
            // Leaves the manual mode used for bumper detection (blades already OFF).
            if (manualBumperMode) send(commands.manualBlades(false, holdHeightMm()), "MANUAL_EXIT_MODE")
        }
        manualBumperMode = false
        event("manual_stop", reason)
    }

    private var silenceJob: Job? = null
    private var silenceProbe: CommandSilenceProbe? = null

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
                guardSensors(t.sensorStatus), t.selfCheck, ui.campaign.running, ui.bladeTestActive, ui.navTest.running, ui.mapLoading,
                modelAllowed = fr.luba.poc.protocol.ModelTrial.testAllowed(ui.selected?.name, now))?.let { error(it) }
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
        send(reportsCommand(false, periodMs = 200), "SILENCE_REPORTS")
        silenceJob = viewModelScope.launch {
            var verdict: SilenceVerdict? = null
            try {
                fun guard() {
                    val live = state.value.telemetry
                    val now = SystemClock.elapsedRealtime()
                    check(state.value.ready && !closing && log.healthy) { tr("BLE link interrupted", "Liaison BLE interrompue") }
                    check(fr.luba.poc.protocol.ModelTrial.testAllowed(state.value.selected?.name, now)) { tr("Model trial ended", "Essai du modèle terminé") }
                    TeachSafety.tickFailure(now - devicePrevious, live.battery, live.sensorStatus, 30)?.let { error(it) }
                    check(fr.luba.poc.protocol.SelfCheck.nominal(live.selfCheck) && !TeachSafety.bumperHealthAlert(guardSensors(live.sensorStatus))) { tr("Protection or sensor alert", "Protection ou alerte capteur") }
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
                        send(commands.movement(linearRaw, 0), "SILENCE_DRIVE $linearRaw")
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
                    check(fr.luba.poc.protocol.SelfCheck.nominal(live.selfCheck)) { tr("Robot protection active", "Protection robot active") }
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
        if (state.value.ready && !closing) { purgeQueuedCommands(); send(commands.stop(), "SILENCE_FINAL_STOP") }
        val result = MotorWatchdogResult.of(robotId, System.currentTimeMillis(), firmware, linearRaw, verdict)
        val kept = recordWatchdog(result)
        update { it.copy(motorWatchdog = kept, silenceTest = it.silenceTest.copy(running = false, phase = "", status = "${verdict.title} — ${result.detail}", driftM = result.driftM)) }
        event("silence_test_result", verdict.title, mapOf("outcome" to result.outcome, "boundMs" to result.boundMs, "driftM" to result.driftM,
            "speedMps" to result.speedMps, "linearRaw" to linearRaw, "demonstrated" to result.demonstrated, "detail" to result.detail))
    }

    /** An inconclusive test does not erase the last conclusive result of the same robot. */
    private fun recordWatchdog(result: MotorWatchdogResult): MotorWatchdogResult {
        val previous = watchdogResult(result.robotId)
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
        if (state.value.robotRoute.running) { status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mowing.active) { status(tr("Finish the mowing mission first", "Terminer la mission de tonte")); return }
        if (state.value.teach.running) return
        if (state.value.borderFrontal.running) { status(tr("Finish the frontal border test first", "Terminer l'essai frontal de bordure")); return }
        try {
            val t = state.value.telemetry
            val failure = TeachSafety.startFailure(state.value.selected?.name,
                if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious,
                t.battery, t.robotState, guardSensors(t.sensorStatus), t.selfCheck, state.value.campaign.running,
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
            send(reportsCommand(false, periodMs = 200), "OBSTACLE_RPT_FAST")
            send(commands.errorQuery(), "OBSTACLE_ERROR_QUERY")
            teachJob = viewModelScope.launch {
                try {
                    val progress=MotionProgressGuard()
                    var nextManualKeepalive = 0L
                    var nextDiagnosticQuery = 0L
                    if (bumperMode) {
                        purgeQueuedCommands(); send(commands.stop(),"BUMPER_MODE_STOP")
                        val gate=RouteBladeGate.unique("BUMPER_MANUAL_OFF",false)
                        routeBladeGate=gate
                        send(commands.manualMow(1,0,holdHeightMm()),gate.label)
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
                        check(contactProtection || fr.luba.poc.protocol.SelfCheck.nominal(currentTelemetry.selfCheck)) { tr("Robot protection active", "Protection robot active") }
                        if (TeachSafety.bumperHealthAlert(guardSensors(currentTelemetry.sensorStatus))) {
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
                            send(commands.movement(teachConfig.linearSpeed, 0), "OBSTACLE_APPROACH")
                            if (now >= nextManualKeepalive) {
                                send(commands.manualMow(1,0,holdHeightMm()), "APPROACH_MANUAL_KEEPALIVE_OFF", periodic = true)
                                nextManualKeepalive = now + 500
                            }
                        } else if (manualMode) {
                            if (now >= nextManualKeepalive) {
                                send(commands.manualMow(1,0,holdHeightMm()), "BUMPER_MANUAL_KEEPALIVE_OFF")
                                nextManualKeepalive = now + 500
                            }
                        } else send(commands.stop(), "OBSTACLE_STATIC_ZERO")
                        if (now >= nextDiagnosticQuery) {
                            event("bumper_diagnostic", tr("State while waiting for contact", "État pendant attente contact"), mapOf("testId" to teachId,
                                "manualRequested" to bumperMode, "manualObserved" to (currentTelemetry.robotState == 20),
                                "robotState" to currentTelemetry.robotState, "sensorStatus" to currentTelemetry.sensorStatus,
                                "selfCheck" to currentTelemetry.selfCheck, "knifeStatus" to currentTelemetry.knifeStatus,
                                "cutterRpm" to currentTelemetry.cutterRpm, "stateAgeMs" to now-devicePrevious))
                            send(commands.errorQuery(), "OBSTACLE_ERROR_QUERY")
                            send(commands.cutterQuery(), "OBSTACLE_CUTTER_QUERY")
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
        purgeQueuedCommands(); send(commands.stop(), "OBSTACLE_APPROACH_HALT"); send(commands.errorQuery(), "OBSTACLE_CONTACT_ERRORS")
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
                send(commands.manualMow(1, 0, holdHeightMm()), "APPROACH_HALT_MANUAL_KEEPALIVE_OFF", periodic = true)
                send(commands.errorQuery(), "OBSTACLE_CONTACT_ERRORS", periodic = true); queryAt = now + 500
            }
            delay(200)
        }
        if (!state.value.teach.running) return
        val t = state.value.telemetry
        val cm = kotlin.math.round(travelledM * 100).toInt()
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
        if (state.value.ready && !closing) send(commands.errorQuery(), "BUMPER_OBSERVATION_QUERY")
        update { it.copy(teach = it.teach.copy(status = tr("Observed beep recorded; waiting for a robot event. Keep the test open until it ends.", "Bip observé consigné ; attente d'un événement robot. Gardez l'essai ouvert jusqu'à sa fin."))) }
    }

    fun stopObstacleTest() { finishObstacle("ABORTED", tr("User stop / left the screen", "Arrêt utilisateur / sortie écran")) }

    private fun finishObstacle(outcome: String, detail: String, sendStop: Boolean = true) {
        if (teachId.isBlank()) return
        if(outcome == "ABORTED") alertMotionFailure(detail)
        teachJob?.cancel(); teachJob = null
        if (sendStop && state.value.ready && !closing) {
            purgeQueuedCommands()
            if(obstacleManualMode) send(commands.manualBlades(false,holdHeightMm()),"BUMPER_EXIT_MANUAL_OFF")
            send(commands.stop(), "OBSTACLE_STOP")
        }
        obstacleManualMode=false
        val contact = state.value.telemetry
        val proposal = if (TeachSafety.canProposeObstacle(obstacleApproach,outcome,SystemClock.elapsedRealtime()-positionPrevious) && contact.x != null && contact.y != null && contact.heading?.isFinite()==true && positionPrevious != 0L) {
            TeachSafety.proposal(TeachContact(contact.x, contact.y, contact.heading, System.currentTimeMillis()), ObstacleKind.OTHER.defaultRadiusM)
        } else null
        val result = TestResult(teachId, if (obstacleApproach) "Apprentissage d'obstacle" else "Bumper immobile", outcome,
            SystemClock.elapsedRealtime() - teachStarted, detail)
        val fields = resultFields(result) + mapOf("kind" to "obstacle_detection", "approach" to obstacleApproach,
            "sensorStatus" to state.value.telemetry.sensorStatus, "x" to state.value.telemetry.x, "y" to state.value.telemetry.y,
            "estimatedX" to proposal?.estimated?.x, "estimatedY" to proposal?.estimated?.y)
        log.report(teachId, fields); event("obstacle_test_result", detail, fields)
        update { it.copy(teach = it.teach.copy(running = false, outcome = outcome, status = detail,
            proposal = proposal ?: it.teach.proposal, results = it.teach.results + result)) }
        teachId = ""
    }

    /** After STOP only: a proposal to check, never an automatically confirmed obstacle. */
    /** Pose at contact: fresh position (≤ 2 s) and fused tracking heading, otherwise null. */
    private fun contactPose(code: Int): RouteContact? {
        val t = state.value.telemetry
        if (positionPrevious == 0L || SystemClock.elapsedRealtime()-positionPrevious !in 0..2000) return null
        val heading = contactHeading(t.heading)?.takeIf { it.isFinite() } ?: return null
        val x = t.x?.takeIf { it.isFinite() } ?: return null
        val y = t.y?.takeIf { it.isFinite() } ?: return null
        return RouteContact(code, x, y, heading, System.currentTimeMillis())
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
    private suspend fun reverseStraight(from: Point, target: Point, heading: Double, physical: TrajectoryPlanner, heightMm: Int, prefix: String,
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
                NavSafety.tickFailure(now-devicePrevious,live.battery,guardSensors(live.sensorStatus),false,RouteRecharge.RESERVE_PERCENT)?.let { error(it) }
                // Right after a contact, the collision lock (25) does not prevent moving away from the object ([CollisionLatch]).
                check(CollisionLatch.reverseAllowed(live.robotState,live.selfCheck,afterContact)) { tr("Protection / robot state changed during the back-off", "Protection / état robot changé pendant le recul") }
                check(((requireNotNull(live.sensorStatus) shr 9) and 7) == 0) { tr("Blades active during the back-off", "Lames actives pendant le recul") }
                val position = Point(requireNotNull(live.x),requireNotNull(live.y))
                val along = (position.x-from.x)*ux + (position.y-from.y)*uy
                val lateral = kotlin.math.abs((position.x-from.x)*uy - (position.y-from.y)*ux)
                if (lateral > 0.12) throw BackOffDeviation(tr("Back-off deviation >12 cm (%.0f cm)", "Écart au recul >12 cm (%.0f cm)").format(lateral*100))
                if (along >= length-0.03) break
                check(now-began-pivotMs < BACK_OFF_TIMEOUT_MS) { tr("Back-off timed out: clearance point not reached", "Recul expiré : point de dégagement non atteint") }
                val reported = live.heading?.takeIf { it.isFinite() }
                val bodyHeading = if (reportedAtStart != null && reported != null) BackOffSteering.current(heading,reportedAtStart,reported) else heading
                val headingError = BackOffSteering.error(BackOffSteering.wanted(heading,from,target,position),bodyHeading)
                maxError = maxOf(maxError,kotlin.math.abs(headingError))
                val pivoting = pivotSince != 0L
                val pivotWanted = reportedAtStart != null && reported != null && along >= pivotFrom &&
                    (pivoting || pivots < BackOffSteering.MAX_PIVOTS) && BackOffSteering.pivotNeeded(headingError,pivoting) && (!pivoting || now-pivotSince < BackOffSteering.PIVOT_TIMEOUT_MS) &&
                    physical.footprintSafe(RobotPose(position,bodyHeading)) &&
                    physical.motionCollision(RobotPose(position,bodyHeading),RobotPose(position,bodyHeading+kotlin.math.sign(headingError)*6.0)) == null
                if (pivotWanted) {
                    if (!pivoting) { pivotSince = now; pivots++ }
                    motionProgress.reset()
                    if (now >= keepalive) { send(commands.manualMow(1,0,heightMm),"${prefix}_BACKOFF_BUMPER_OFF",periodic=true); keepalive = now+500 }
                    if (now >= queryAt) { send(commands.errorQuery(),"${prefix}_BACKOFF_ERRORS",periodic=true); queryAt = now+500 }
                    send(commands.movement(0,BackOffSteering.pivotAngular(headingError,degreesPerUnit)),"${prefix}_BACKOFF_PIVOT")
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
                if (now >= keepalive) { send(commands.manualMow(1,0,heightMm),"${prefix}_BACKOFF_BUMPER_OFF",periodic=true); keepalive = now+500 }
                if (now >= queryAt) { send(commands.errorQuery(),"${prefix}_BACKOFF_ERRORS",periodic=true); queryAt = now+500 }
                send(commands.movement(-BACK_OFF_LINEAR,0),"${prefix}_BACKOFF")
                delay(200)
            }
        } finally { purgeQueuedCommands(); send(commands.stop(),"${prefix}_BACKOFF_STOP") }
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
    private suspend fun awaitCollisionLatchCleared(prefix: String, heightMm: Int) {
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
                    send(commands.stop(), "${prefix}_LATCH_STOP")
                    send(commands.manualMow(1, 0, heightMm), "${prefix}_LATCH_BUMPER_OFF", periodic = true)
                    send(commands.errorQuery(), "${prefix}_LATCH_ERRORS", periodic = true)
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

    private fun proposeRouteCollision() {
        val t=state.value.telemetry
        if(positionPrevious==0L || SystemClock.elapsedRealtime()-positionPrevious !in 0..2000 ||
            t.x?.isFinite()!=true || t.y?.isFinite()!=true || t.heading?.isFinite()!=true) return
        val proposal=TeachSafety.proposal(TeachContact(requireNotNull(t.x),requireNotNull(t.y),requireNotNull(contactHeading(t.heading)),System.currentTimeMillis()),ObstacleKind.OTHER.defaultRadiusM)
        update { it.copy(teach=it.teach.copy(proposal=proposal,status=tr("Collision 1014: robot stopped. Check the obstacle proposal in Service → Obstacle tests.", "Collision 1014 : robot arrêté. Vérifier la proposition d'obstacle dans Service → Essais d'obstacle."))) }
        event("route_obstacle_proposed",tr("Fresh 1014 contact after STOP: local proposal to check", "Contact 1014 frais après STOP : proposition locale à vérifier"),mapOf("x" to proposal.estimated.x,"y" to proposal.estimated.y))
    }

    private class NavAbort(val motif: String) : Exception(motif)

    /** Route preview from the received position, without any motor command. */
    fun previewNavTest(radius: Double, tonte: Boolean = false) {
        if (state.value.navTest.running) return
        try {
            val t = state.value.telemetry
            check(NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime()-positionPrevious,t.x,t.y) == null) { tr("Fresh position required", "Position fraîche obligatoire") }
            val plan = MapNavPlanner.plan(state.value.robotMap,Point(requireNotNull(t.x),requireNotNull(t.y)),radius,state.value.obstacles)
            update { it.copy(navTest = it.navTest.copy(planned = plan.points,plannedBands = plan.bands,plannedBorders = plan.borders,
                plannedCutMeters = if (tonte) plan.cuttingMeters else 0.0,
                preview = (if (tonte) tr("Preview: ${plan.bands.size} stripes · ${plan.borders.size} borders · %.1f m including %.1f m with blades running. The start will be recalculated at launch.", "Aperçu : ${plan.bands.size} bandes · ${plan.borders.size} bordures · %.1f m dont %.1f m lames en marche. Le départ sera recalculé au lancement.").format(plan.meters, plan.cuttingMeters)
                    else tr("Preview: ${plan.bands.size} stripes · ${plan.borders.size} accessible borders · %.1f m. The start will be recalculated at launch.", "Aperçu : ${plan.bands.size} bandes · ${plan.borders.size} bordures accessibles · %.1f m. Le départ sera recalculé au lancement.").format(plan.meters)))) }
        } catch (error: Exception) { update { it.copy(navTest = it.navTest.copy(preview = tr("Preview unavailable: ${error.message}", "Aperçu impossible : ${error.message}"),planned=emptyList(),plannedBands=emptyList(),plannedBorders=emptyList())) } }
    }

    private fun captureLocalIncident(code: Int, known: LocalObstacle? = null) {
        purgeQueuedCommands()
        send(commands.stop(), "OBSTACLE_IMMEDIATE_STOP")
        localIncidentPending = true
        val t = state.value.telemetry
        val now = System.currentTimeMillis()
        event("navigation_incident", tr("Obstacle STOP", "STOP obstacle"), mapOf("code" to code, "x" to t.x, "y" to t.y,
            "heading" to t.heading, "positionAt" to t.lastPositionAt, "deviceAt" to t.lastDeviceAt,
            "segment" to state.value.navTest.segmentIndex, "testId" to navTestId, "nativeMissionId" to state.value.mowing.id))
        if (t.x == null || t.y == null || t.heading == null || positionPrevious == 0L ||
            SystemClock.elapsedRealtime()-positionPrevious !in 0..2000) {
            stopNavTest(tr("Incident without a fresh position: stop maintained", "Incident sans position fraîche : arrêt maintenu"))
            return
        }
        try {
            val center = TeachObstacle.estimateCenter(TeachContact(t.x,t.y,requireNotNull(contactHeading(t.heading)),now), 0.2)
            if (code != 0) recordDifficulty(DifficultyKind.CONTACT, tr("Bumper contact ($code)", "Contact pare-chocs ($code)"), Point(t.x,t.y), code,
                contactHeading(t.heading), center, state.value.obstaclePhoto.request?.contactAt ?: now)
            val existing = state.value.obstacles.minByOrNull { Point(it.x,it.y).distance(center) }
                ?.takeIf { Point(it.x,it.y).distance(center) <= it.exclusionRadiusM + 0.5 }
            val obstacle = known ?: existing ?: LocalObstacle(UUID.randomUUID().toString(), ObstacleKind.OTHER,
                center.x, center.y, 0.2, 0.3, createdAtMs = now, label = tr("Obstacle detected during navigation", "Obstacle détecté en navigation"))
                    .also { if (code != 0) navContactObstacle = it.id }
            saveObstacle(obstacle.withIncident(ObstacleIncident(now, if (code == 0) "obstacle_corridor" else "collision_$code",
                "Cap=${t.heading}; segment=${state.value.navTest.segmentIndex}; mission=$navTestId", t.x,t.y)))
            if (obstacle.incidents.size + 1 >= 3) event("incident_heatmap", tr("Permanent map correction proposed", "Correction permanente de carte proposée"),
                mapOf("id" to obstacle.id, "x" to obstacle.x, "y" to obstacle.y, "count" to obstacle.incidents.size+1))
        } catch (error: Exception) {
            stopNavTest(tr("Incident not recorded: stop maintained (${error.message})", "Incident non enregistré : arrêt maintenu (${error.message})"))
        }
    }

    private var unlockWrittenAt = 0L

    /**
     * Unlock attempt after the robot's STOP button (self-check 11), see [StopUnlock]: zero speed, task cancellation,
     * error re-read; no motion command.
     * Successful only if a report received after sending shows self-check 0 or 10.
     */
    fun unlockAfterStop() {
        val ui = state.value
        val busy = ui.robotRoute.running || ui.navTest.running || ui.manualActive || ui.teach.running ||
            ui.bladeTestActive || ui.campaign.running || ui.mowing.active || ui.taskStopPending || ui.unlockPending || ui.backOffPending
        val age = if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious
        val refusal = StopUnlock.refusal(ui.ready && gatt != null && !closing && log.healthy, busy, age,
            ui.telemetry.selfCheck, guardSensors(ui.telemetry.sensorStatus))
        if (refusal != null) {
            update { it.copy(unlockStatus = tr("Unlock refused: $refusal", "Déverrouillage refusé : $refusal")) }
            event("stop_unlock_refused", refusal, mapOf("selfCheck" to ui.telemetry.selfCheck)); return
        }
        if (CollisionLatch.latched(ui.telemetry.selfCheck)) { unlockCollisionLatch(); return }
        update { it.copy(unlockPending = true, unlockStatus = tr("Unlock requested from the robot…", "Déverrouillage demandé au robot…")) }
        event("stop_unlock_start", tr("Unlock requested (self-check ${ui.telemetry.selfCheck})", "Déverrouillage demandé (self-check ${ui.telemetry.selfCheck})"), mapOf("selfCheck" to ui.telemetry.selfCheck,
            "robotState" to ui.telemetry.robotState, "sensorStatus" to ui.telemetry.sensorStatus))
        unlockWrittenAt = 0L
        purgeQueuedCommands()
        send(commands.stop(), "UNLOCK_ZERO")
        send(commands.taskControl(4), "UNLOCK_TASK_CANCEL")
        send(commands.errorQuery(), "UNLOCK_ERROR_QUERY")
        if (!StopUnlock.locked(ui.telemetry.selfCheck)) send(commands.selfCheckInfo(), "UNLOCK_SELF_CHECK_INFO")
        val session = connectionId
        viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + StopUnlock.TIMEOUT_MS
            while (connectionId == session && state.value.ready && !closing && SystemClock.elapsedRealtime() < deadline) {
                val t = state.value.telemetry
                if (StopUnlock.confirmed(unlockWrittenAt, devicePrevious, SystemClock.elapsedRealtime() - devicePrevious, t.selfCheck)) {
                    update { it.copy(unlockPending = false, unlockStatus = tr("Robot unlocked (self-check ${t.selfCheck})", "Robot déverrouillé (self-check ${t.selfCheck})")) }
                    event("stop_unlock_done", tr("Nominal self-check received after unlocking", "Self-check nominal reçu après déverrouillage"), mapOf("selfCheck" to t.selfCheck))
                    return@launch
                }
                delay(200)
            }
            val t = state.value.telemetry
            update { it.copy(unlockPending = false, unlockStatus =
                tr("The robot stays locked (self-check ${t.selfCheck ?: "missing"}): it does not accept remote unlocking. ${StopUnlock.physical(t.selfCheck)}.", "Le robot reste verrouillé (self-check ${t.selfCheck ?: "absent"}) : il n'accepte pas le déverrouillage à distance. ${StopUnlock.physical(t.selfCheck)}.")) }
            event("stop_unlock_failed", tr("Self-check still not nominal after ${StopUnlock.TIMEOUT_MS} ms", "Self-check toujours non nominal après ${StopUnlock.TIMEOUT_MS} ms"),
                mapOf("selfCheck" to t.selfCheck, "writtenConfirmed" to (unlockWrittenAt > 0)))
        }
    }

    /**
     * Collision lock (self-check 25, [CollisionLatch]): the robot stays against the object it hit, and neither zero speed
     * nor task cancellation releases it (10-15, 11:00 → 11:10). Unlocked by a straight back-off of at most
     * [CollisionLatch.UNLOCK_BACK_OFF_M], heading unchanged, blades OFF, along the travelled trace (otherwise along a free
     * line of the map: borders, no-go zones and obstacles at their physical radius), with the guards of the back-off after
     * contact, then a wait for the lock to be released. The robot may refuse to move: it must then be freed by hand.
     */
    private fun unlockCollisionLatch() {
        val t = state.value.telemetry
        update { it.copy(unlockPending = true, unlockStatus = tr("Collision lock: back-off of %.0f cm along the trace, blades OFF…", "Verrou de collision : recul de %.0f cm sur la trace, lames OFF…").format(CollisionLatch.UNLOCK_BACK_OFF_M * 100)) }
        event("stop_unlock_start", tr("Releasing the collision lock with a back-off (self-check ${t.selfCheck})", "Déverrouillage du verrou de collision par un recul (self-check ${t.selfCheck})"), mapOf("selfCheck" to t.selfCheck,
            "robotState" to t.robotState, "sensorStatus" to t.sensorStatus, "mode" to "collision_back_off"))
        val heightMm = t.bladeHeightMm ?: bladeHeightRequest
        viewModelScope.launch {
            try {
                check(NavSafety.automaticPositionFailure(SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y) == null) { tr("Fresh position required", "Position fraîche obligatoire") }
                val here = Point(requireNotNull(t.x), requireNotNull(t.y))
                val heading = requireNotNull(t.heading?.takeIf { it.isFinite() }) { tr("Robot heading unknown", "Cap du robot inconnu") }
                val obstacles = state.value.obstacles
                // Trace of a route or of a move to a point (the latter was ignored: no trace after a move beyond the map).
                val recorded = listOf(state.value.robotRoute.trail.takeLast(400), state.value.navTest.trail.takeLast(400).map { Point(it.x, it.y) })
                    .filter { it.isNotEmpty() && it.last().distance(here) <= 0.5 }.minByOrNull { it.last().distance(here) }
                // Beyond the mapped zones, the trace driven there is the only known ground ([RoutePlanning.backOffPhysical]).
                val physical = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    fr.luba.poc.planning.RoutePlanning.backOffPhysical(state.value.robotMap, obstacles, here, recorded ?: emptyList()) }
                // No trace (app restarted): straight line behind the robot, checked against the map like the trace.
                val rad = Math.toRadians(heading)
                val trail = recorded ?: listOf(Point(here.x - 0.6 * kotlin.math.cos(rad), here.y - 0.6 * kotlin.math.sin(rad)), here)
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    ObstacleBypass.backOffCandidates(physical, here, heading, trail, CollisionLatch.UNLOCK_BACK_OFF_M).lastOrNull() }
                    ?: error(if (recorded == null && state.value.robotMap.shapes.none { it.kind == fr.luba.poc.map.ShapeKind.ZONE && it.points.size >= 3 && fr.luba.poc.map.Geometry.inside(here, it.points) })
                        tr("robot beyond the mapped zones without a recorded trace, no known ground behind it", "robot hors des zones cartographiées sans trace enregistrée, aucun sol connu derrière lui")
                        else tr("no safe back-off behind the robot (trace, borders, no-go zones or obstacles)", "aucun recul sûr derrière le robot (trace, bords, no-go ou obstacles)"))
                event("stop_unlock_back_off", tr("Unlock back-off computed", "Recul de déverrouillage calculé"), mapOf("x" to here.x, "y" to here.y, "heading" to heading,
                    "toX" to back.x, "toY" to back.y, "meters" to back.distance(here), "trail" to (recorded != null)))
                purgeQueuedCommands(); send(commands.stop(), "UNLOCK_ZERO")
                reverseStraight(here, back, heading, physical, heightMm, "UNLOCK", afterContact = true)
                update { it.copy(unlockStatus = tr("Back-off done: waiting for the robot protection to be released…", "Recul effectué : attente de la levée de la protection du robot…")) }
                awaitCollisionLatchCleared("UNLOCK", heightMm)
                val selfCheck = state.value.telemetry.selfCheck
                update { it.copy(unlockPending = false, unlockStatus = tr("Robot unlocked (self-check $selfCheck) after a back-off of %.0f cm", "Robot déverrouillé (self-check $selfCheck) après un recul de %.0f cm").format(back.distance(here) * 100)) }
                event("stop_unlock_done", tr("Collision lock released after the back-off", "Verrou de collision levé après le recul"), mapOf("selfCheck" to selfCheck, "meters" to back.distance(here)))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                purgeQueuedCommands(); send(commands.stop(), "UNLOCK_BACK_OFF_STOP")
                val reason = failure.message ?: failure.javaClass.simpleName
                update { it.copy(unlockPending = false, unlockStatus = tr("The robot stays locked (self-check ${state.value.telemetry.selfCheck ?: "missing"}): ", "Le robot reste verrouillé (self-check ${state.value.telemetry.selfCheck ?: "absent"}) : ") +
                    "$reason${if (reason.contains(StopUnlock.PROTECTION_PHYSICAL)) "" else ". ${StopUnlock.PROTECTION_PHYSICAL}"}.") }
                event("stop_unlock_failed", tr("Unlock back-off impossible: $reason", "Recul de déverrouillage impossible : $reason"), mapOf("selfCheck" to state.value.telemetry.selfCheck))
            }
        }
    }

    private var backOffJob: kotlinx.coroutines.Job? = null

    /**
     * "Unblock" from the interrupted-mowing panel: straight back-off of at most [ResumeBackOff.MAX_M], heading unchanged,
     * blades OFF, to the nearest point where the robot can pivot and travel with the mowing margins ([engine]: the resume's
     * re-prepared route, its margins and its plan), guided by the actual trace, otherwise by the route mowed before the
     * stop, otherwise by a free line behind the robot ([ResumeBackOff]). Same guards as the back-off after contact
     * ([reverseStraight]). Under the collision lock (25), the back-off is allowed as after a contact, then the lock
     * release is awaited. The robot then stays stopped: resuming remains a user action.
     */
    fun backOffBeforeResume(engine: MowingSimulation) {
        val ui = state.value
        val t = ui.telemetry
        val busy = ui.robotRoute.running || ui.navTest.running || ui.manualActive || ui.teach.running || ui.bladeTestActive ||
            ui.campaign.running || ui.mowing.active || ui.taskStopPending || ui.unlockPending || ui.backOffPending || ui.silenceTest.running ||
            preparationJob?.isActive == true || heightTarget != null || bladeMayBeOn || bladeOffPending
        val latched = CollisionLatch.latched(t.selfCheck)
        val refusal = when {
            !(ui.ready && gatt != null && !closing && log.healthy && queue.size <= 4) -> tr("BLE not ready", "BLE non prêt")
            busy -> tr("finish the current activity", "terminer l'activité en cours")
            devicePrevious == 0L || SystemClock.elapsedRealtime() - devicePrevious > 2000 -> tr("robot state stale", "état du robot périmé")
            t.chargeState in setOf(1, 2) -> tr("robot on the base, nothing to free", "robot sur la base, rien à dégager")
            StopUnlock.locked(t.selfCheck) -> tr("robot locked after STOP: “Unlock” first", "robot verrouillé après STOP : « Déverrouiller » d'abord")
            !CollisionLatch.reverseAllowed(t.robotState, t.selfCheck, afterContact = true) ->
                tr("protection or robot state incompatible (state ${t.robotState ?: "?"}, self-check ${t.selfCheck ?: "?"})", "protection ou état du robot incompatible (état ${t.robotState ?: "?"}, self-check ${t.selfCheck ?: "?"})")
            else -> NavSafety.automaticPositionFailure(SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y)
                ?: if (t.heading?.isFinite() != true) tr("robot heading unknown", "cap du robot inconnu") else null
        }
        if (refusal != null) {
            update { it.copy(backOffStatus = tr("Unblock refused: $refusal", "Déblocage refusé : $refusal")) }
            event("resume_back_off_refused", refusal, mapOf("selfCheck" to t.selfCheck, "robotState" to t.robotState)); return
        }
        val checkpoint = ui.missionCheckpoint
        val heightMm = t.bladeHeightMm ?: bladeHeightRequest
        update { it.copy(backOffPending = true, backOffStatus = tr("Backing off to a safe position, blades OFF…", "Recul vers une position sûre, lames OFF…")) }
        backOffJob = viewModelScope.launch {
            try {
                val here = Point(requireNotNull(t.x), requireNotNull(t.y))
                val heading = requireNotNull(t.heading)
                val obstacles = state.value.planningObstacles
                val (physical, planned) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    fr.luba.poc.planning.RoutePlanning.physical(state.value.robotMap, obstacles,
                        recordedBoundary = !engine.map.source.contains("DÉMONSTRATION", true)) to
                        (checkpoint?.let { ResumeBackOff.plannedBefore(engine.edges, it.edge, it.point) } ?: emptyList())
                }
                val (guide, trail) = ResumeBackOff.guide(here, heading, state.value.robotRoute.trail, planned)
                val back = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    ResumeBackOff.target(engine.routes, physical, here, heading, trail) }
                    ?: error(tr("no safe back-off behind the robot (trace, borders, no-go zones or obstacles): free it by hand", "aucun recul sûr derrière le robot (trace, bords, no-go ou obstacles) : le dégager à la main"))
                val safe = engine.routes.safe(back, back) && engine.routes.supportsPivot(back)
                event("resume_back_off_start", tr("Clearance before resume: back-off computed", "Dégagement avant reprise : recul calculé"), mapOf("x" to here.x, "y" to here.y, "heading" to heading,
                    "toX" to back.x, "toY" to back.y, "meters" to back.distance(here), "guide" to guide.name, "safe" to safe,
                    "selfCheck" to t.selfCheck, "latched" to latched))
                purgeQueuedCommands(); send(commands.stop(), "RESUME_BACKOFF_ZERO")
                reverseStraight(here, back, heading, physical, heightMm, "RESUME", afterContact = latched)
                if (latched) {
                    update { it.copy(backOffStatus = tr("Back-off done: waiting for the robot protection to be released…", "Recul effectué : attente de la levée de la protection du robot…")) }
                    awaitCollisionLatchCleared("RESUME", heightMm)
                }
                val meters = back.distance(here)
                update { it.copy(backOffPending = false, backOffStatus = tr("Robot backed off by %.0f cm%s: Resume, or Resume further on to avoid the obstacle.", "Robot reculé de %.0f cm%s : Reprendre, ou Reprendre plus loin pour éviter l'obstacle.")
                    .format(meters * 100, if (safe) "" else tr(" (tight position: the resume will plan a clearance)", " (position serrée : la reprise prévoira un dégagement)"))) }
                event("resume_back_off_done", tr("Clearance before resume finished", "Dégagement avant reprise terminé"), mapOf("meters" to meters, "safe" to safe, "selfCheck" to state.value.telemetry.selfCheck))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                purgeQueuedCommands(); send(commands.stop(), "RESUME_BACKOFF_STOP")
                update { it.copy(backOffPending = false, backOffStatus = tr("Unblock stopped", "Déblocage arrêté")) }
                event("resume_back_off_stopped", tr("Clearance before resume stopped", "Dégagement avant reprise arrêté"))
                throw cancel
            } catch (failure: Exception) {
                purgeQueuedCommands(); send(commands.stop(), "RESUME_BACKOFF_STOP")
                val reason = failure.message ?: failure.javaClass.simpleName
                update { it.copy(backOffPending = false, backOffStatus = tr("Unblock impossible: $reason", "Déblocage impossible : $reason")) }
                event("resume_back_off_failed", tr("Clearance before resume impossible: $reason", "Dégagement avant reprise impossible : $reason"), mapOf("selfCheck" to state.value.telemetry.selfCheck))
            } finally { backOffJob = null }
        }
    }

    /** Stops the unblock in progress (the panel's STOP button). */
    fun stopBackOff() { backOffJob?.cancel() }

    /** Supervised navigation: release, manual STOP and leaving the screen stop everything. */
    private var nativeCancelWrittenAt = 0L

    fun stopMammotionTask() {
        try {
            check(state.value.ready && gatt != null && !closing && log.healthy) { tr("BLE connection required", "Connexion BLE requise") }
            check(!state.value.robotRoute.running && !state.value.navTest.running && !state.value.manualActive &&
                !state.value.teach.running && !state.value.bladeTestActive && !state.value.campaign.running) { tr("Stop the local route or test first", "Arrêter le trajet ou l’essai local d’abord") }
            check(!state.value.taskStopPending) { tr("Cancellation already in progress", "Annulation déjà en cours") }
            check(devicePrevious>0 && SystemClock.elapsedRealtime()-devicePrevious<=2000) { tr("Robot state stale", "État robot périmé") }
            if (!NativeTaskStop.present(state.value.telemetry.robotState)) {
                update { it.copy(taskStopStatus=tr("No active or paused Mammotion task observed", "Aucune tâche Mammotion active ou en pause observée")) }; return
            }
            update { it.copy(taskStopPending=true,taskStopStatus=tr("Mammotion task cancellation requested; waiting for READY and blades OFF", "Annulation de la tâche Mammotion demandée ; attendre READY et lames OFF")) }
            nativeCancelWrittenAt=0L
            purgeQueuedCommands()
            if(state.value.telemetry.robotState==14) send(commands.cancelReturnToDock(),"NATIVE_RETURN_CANCEL")
            send(commands.taskControl(2),"NATIVE_TASK_PAUSE")
            send(commands.taskControl(4),"NATIVE_TASK_CANCEL")
            send(commands.manualBlades(false,state.value.telemetry.bladeHeightMm ?: 60),"NATIVE_TASK_BLADES_OFF")
            send(commands.stop(),"NATIVE_TASK_ZERO")
            val session=connectionId
            viewModelScope.launch {
                val deadline=SystemClock.elapsedRealtime()+15000
                while(connectionId==session && state.value.ready && !closing && SystemClock.elapsedRealtime()<deadline) {
                    val t=state.value.telemetry
                    if(NativeTaskStop.confirmed(nativeCancelWrittenAt,devicePrevious,SystemClock.elapsedRealtime()-devicePrevious,t.robotState,t.sensorStatus)) {
                        update { it.copy(taskStopPending=false,taskStopStatus=tr("Task stopped: READY and blades OFF received", "Tâche arrêtée : READY et lames OFF reçus")) }
                        event("native_task_stopped",tr("READY / OFF observed after cancellation", "READY / OFF observés après annulation"))
                        return@launch
                    }
                    delay(200)
                }
                update { it.copy(taskStopPending=false,taskStopStatus=tr("Cancellation not confirmed: check the state in Mammotion; no local resume", "Annulation non confirmée : vérifier l’état dans Mammotion ; aucune reprise locale")) }
            }
        } catch(e: Exception) { update { it.copy(taskStopStatus=tr("Task stop refused: ${e.message}", "Arrêt tâche refusé : ${e.message}")) } }
    }

    /**
     * Official Mammotion return, on the user's explicit choice, triggered only once the robot has stopped in
     * front of the base (native [baseAction]). Blades off and zero speed first; tracking waits for the RETURN
     * state, then the charging contact. "Stop the Mammotion task" cancels it.
     */
    private fun nativeReturnToBase() {
        try {
            check(state.value.ready && gatt != null && !closing && log.healthy) { tr("BLE connection required", "Connexion BLE requise") }
            check(!state.value.robotRoute.running && !state.value.navTest.running && !state.value.manualActive &&
                !state.value.teach.running && !state.value.bladeTestActive && !state.value.campaign.running) { tr("Stop the local route or test first", "Arrêter le trajet ou l’essai local d’abord") }
            check(!state.value.nativeReturnActive && !state.value.taskStopPending) { tr("Native return or cancellation already in progress", "Retour natif ou annulation déjà en cours") }
            check(devicePrevious>0 && SystemClock.elapsedRealtime()-devicePrevious<=2000) { tr("Robot state stale", "État robot périmé") }
            val t=state.value.telemetry
            if (t.chargeState in setOf(1,2)) { update { it.copy(nativeReturnStatus=tr("Already on the base: charging observed", "Déjà sur la base : charge observée")) }; return }
            check(t.robotState != 14) { tr("Mammotion return already in progress", "Retour Mammotion déjà en cours") }
            purgeQueuedCommands()
            send(commands.manualBlades(false,t.bladeHeightMm ?: 60),"NATIVE_RETURN_BLADES_OFF")
            send(commands.stop(),"NATIVE_RETURN_ZERO")
            send(commands.returnToDock(),"NATIVE_RETURN_TO_DOCK",nativeReturn=true)
            val requestedAt=SystemClock.elapsedRealtime()
            update { it.copy(nativeReturnActive=true,nativeReturnStatus=tr("Native Mammotion return requested; waiting for the return state", "Retour natif Mammotion demandé ; attente de l’état retour")) }
            event("native_return_request",tr("Return to base by the Mammotion firmware (user choice)", "Retour à la base par le firmware Mammotion (choix utilisateur)"),
                mapOf("robotState" to t.robotState,"battery" to t.battery,"x" to t.x,"y" to t.y))
            val session=connectionId
            viewModelScope.launch {
                var returning=false
                try {
                    while(connectionId==session && state.value.ready && !closing) {
                        val live=state.value.telemetry
                        val now=SystemClock.elapsedRealtime()
                        if (devicePrevious>requestedAt && live.chargeState in setOf(1,2)) {
                            update { it.copy(nativeReturnStatus=tr("Native return finished: charging contact confirmed", "Retour natif terminé : contact de charge confirmé")) }
                            event("native_return_docked",tr("Charging contact after native return", "Contact de charge après retour natif")); return@launch
                        }
                        if (live.robotState==14 && !returning) {
                            returning=true
                            update { it.copy(nativeReturnStatus=tr("Native Mammotion return in progress · can be stopped here", "Retour natif Mammotion en cours · arrêt possible ici")) }
                        }
                        if (returning && live.robotState!=14 && devicePrevious>requestedAt) {
                            update { it.copy(nativeReturnStatus=tr("Native return interrupted (state ${live.robotState}) without charging contact", "Retour natif interrompu (état ${live.robotState}) sans contact de charge")) }
                            event("native_return_interrupted",tr("Native return interrupted", "Retour natif interrompu"),mapOf("robotState" to live.robotState)); return@launch
                        }
                        if (!returning && now-requestedAt>15000) {
                            update { it.copy(nativeReturnStatus=tr("Native return not started by the robot (state ${live.robotState})", "Retour natif non démarré par le robot (état ${live.robotState})")) }
                            event("native_return_refused",tr("No return state observed", "Aucun état retour observé"),mapOf("robotState" to live.robotState)); return@launch
                        }
                        if (now-requestedAt>30*60*1000L) {
                            update { it.copy(nativeReturnStatus=tr("Native return taking too long: check the robot", "Retour natif trop long : vérifier le robot")) }; return@launch
                        }
                        delay(500)
                    }
                    update { it.copy(nativeReturnStatus=tr("Native return tracking lost (BLE): check the robot", "Suivi du retour natif perdu (BLE) : vérifier le robot")) }
                } finally { update { it.copy(nativeReturnActive=false) } }
            }
        } catch(e: Exception) { update { it.copy(nativeReturnStatus=tr("Native return refused: ${e.message}", "Retour natif refusé : ${e.message}")) } }
    }

    fun setTrajectorySpeed(percent: Int) {
        require(percent in 10..100)
        update { it.copy(trajectorySpeedPercent = percent) }
        speedPreferences.edit { putInt("percent", percent) }
        event("trajectory_speed", tr("Speed changed without automatic resume", "Vitesse modifiée sans reprise automatique"), mapOf("percent" to percent, "linearRaw" to PathFollower.linearFor(percent), "angularLimitRaw" to PathFollower.angularLimit(percent)))
    }

    // ───────────── Frontal border test (Service → Obstacle tests → Border) ─────────────

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
                startMapNavigation(NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
                    angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0), preset = nav)
            } finally { borderPositioning = false }
            if (!state.value.navTest.running) { finishBorder("ABORTED", tr("Positioning refused: ${state.value.navTest.status}", "Positionnement refusé : ${state.value.navTest.status}"), sendStop = false); return }
            borderJob = viewModelScope.launch { runBorderFrontal(plan) }
        } catch (error: Exception) {
            update { it.copy(borderFrontal = it.borderFrontal.copy(status = tr("Test refused: ${error.message}", "Essai refusé : ${error.message}"))) }
            event("border_frontal_refused", error.message ?: tr("Test refused", "Essai refusé"))
        }
    }

    /** Visual verdict at the stop at the end of the pass: [ok] false if the robot's front goes past the real border. */
    fun borderPassEndVerdict(ok: Boolean) {
        if (state.value.borderFrontal.phase == BorderPhase.PASS_END) borderRequest = if (ok) BorderRequest.OK else BorderRequest.TOO_FAR
    }

    fun continueBorderToEdge() {
        if (state.value.borderFrontal.phase == BorderPhase.PASS_END) borderRequest = BorderRequest.CONTINUE
    }

    /** Edge seen visually during the advance: immediate STOP, then measurement. */
    fun borderEdgeSeen() {
        if (state.value.borderFrontal.phase != BorderPhase.EDGE) return
        purgeQueuedCommands(); send(commands.stop(), "BORDER_EDGE_SEEN_STOP")
        borderRequest = BorderRequest.EDGE
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
                    BorderRequest.OK -> { finishBorder("PASS", tr("Pass end judged correct · ", "Bout de passe jugé correct · ") + BorderFrontal.summary(plan, passGap, null)); return }
                    BorderRequest.TOO_FAR -> { finishBorder("FAILED", tr("Pass end too close to or beyond the border · ", "Bout de passe trop près ou au-delà de la bordure · ") + BorderFrontal.summary(plan, passGap, null)); return }
                    BorderRequest.CONTINUE -> break
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

    private fun stopLabel(stop: BorderStop): String = when (stop) {
        BorderStop.CONTACT -> tr("Bumper contact (${borderContactCode ?: "?"})", "Contact pare-chocs (${borderContactCode ?: "?"})")
        BorderStop.SENSOR_ALERT -> tr("Bumper sensor alert without a contact code", "Alerte capteur pare-chocs sans code de contact")
        BorderStop.EDGE_SEEN -> tr("Edge reported visually", "Bord signalé visuellement")
        BorderStop.LIMIT -> tr("Advance limit reached", "Limite d'avance atteinte")
        BorderStop.TARGET -> tr("Pass end reached", "Bout de passe atteint")
    }

    /** Manual mode with blades OFF (state 20): the only mode in which the LUBA 2 publishes bumper contact. */
    private suspend fun enterBorderBumperMode() {
        purgeQueuedCommands(); send(commands.stop(), "BORDER_BUMPER_MODE_STOP")
        val gate = RouteBladeGate.unique("BORDER_BUMPER_MANUAL_OFF", false)
        routeBladeGate = gate
        send(commands.manualMow(1, 0, navConfig.heightMm), gate.label)
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
        check(fr.luba.poc.protocol.SelfCheck.nominal(t.selfCheck)) { tr("Robot protection active", "Protection robot active") }
        TeachSafety.tickFailure(if (devicePrevious == 0L) null else now - devicePrevious, t.battery, t.sensorStatus, teachConfig.batteryFloor)?.let { error(it) }
    }

    /** Robot held still in manual mode with blades OFF: zero setpoint, mode keep-alive and error reading. */
    private fun borderHold(nextKeep: Long): Long {
        send(commands.movement(0, 0), "BORDER_HOLD")
        val now = SystemClock.elapsedRealtime()
        if (now < nextKeep) return nextKeep
        send(commands.errorQuery(), "BORDER_ERROR_QUERY", periodic = true)
        send(commands.manualMow(1, 0, navConfig.heightMm), "BORDER_BUMPER_KEEPALIVE_OFF", periodic = true)
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
        val metersPerUnit = savedCalibration()?.metersPerUnit ?: DeadReckoning.DEFAULT_METERS_PER_UNIT
        val progress = MotionProgressGuard(windowMs = 4000)
        var nextKeep = 0L
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (borderContactCode != null) return BorderStop.CONTACT
                borderErrorCode?.let { error(tr("Robot error $it", "Erreur robot $it")) }
                if (borderRequest == BorderRequest.EDGE) return BorderStop.EDGE_SEEN
                if (TeachSafety.bumperHealthAlert(guardSensors(state.value.telemetry.sensorStatus))) return BorderStop.SENSOR_ALERT
                check(now - began < timeoutMs) { tr("Maximum advance duration reached (${timeoutMs / 1000} s)", "Durée maximale de l'avance atteinte (${timeoutMs / 1000} s)") }
                borderTick()
                val center = borderCenter()
                val lateral = plan.lateral(center)
                check(kotlin.math.abs(lateral) <= BorderFrontal.MAX_LATERAL_M) { tr("Lateral deviation of %.0f cm: the robot is not driving straight at the outline", "Écart latéral de %.0f cm : le robot ne roule pas face au contour").format(lateral * 100) }
                val gap = plan.centerGap(center)
                val predicted = BorderFrontal.predictedCenterGap(gap, now - positionPrevious, DeadReckoning.NAV_COMMAND_DELAY_MS, linear, metersPerUnit)
                update { it.copy(borderFrontal = it.borderFrontal.copy(liveFrontGapM = plan.frontGap(center))) }
                if (stopGap != null && predicted <= stopGap) return BorderStop.TARGET
                if (predicted <= plan.limitCenterM) return BorderStop.LIMIT
                check(!progress.blocked(center, positionPrevious, now, linear)) { tr("No advance observed for 4 s, with no contact reported", "Aucune avance observée depuis 4 s, sans contact signalé") }
                if (now >= nextKeep) {
                    send(commands.errorQuery(), "BORDER_ERROR_QUERY", periodic = true)
                    send(commands.manualMow(1, 0, navConfig.heightMm), "BORDER_BUMPER_KEEPALIVE_OFF", periodic = true)
                    nextKeep = now + 500
                }
                send(commands.movement(linear, 0), "BORDER_ADVANCE linear=$linear")
                delay(200)
            }
        } finally {
            // Test already finished (STOP and manual-mode exit queued): purge and rewrite nothing.
            if (borderBumperWatch && state.value.ready && !closing) { purgeQueuedCommands(); send(commands.movement(0, 0), "BORDER_ADVANCE_END") }
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

    private fun finishBorder(outcome: String, detail: String, sendStop: Boolean = true) {
        if (borderId.isBlank()) return
        val positioning = state.value.borderFrontal.phase == BorderPhase.POSITIONING
        // Also called from the job itself: cancellation only takes effect at its next suspension, after this summary.
        borderJob?.cancel(); borderJob = null
        if (positioning && state.value.navTest.running) stopNavTest(tr("Frontal test stopped: $detail", "Essai frontal arrêté : $detail"), userInitiated = true)
        if (sendStop && state.value.ready && !closing) {
            purgeQueuedCommands()
            send(commands.stop(), "BORDER_STOP")
            if (borderBumperWatch) send(commands.manualBlades(false, navConfig.heightMm), "BORDER_EXIT_MANUAL_OFF")
        }
        borderBumperWatch = false
        val plan = state.value.borderFrontal.plan
        val ui = state.value.borderFrontal
        val result = TestResult(borderId, tr("Frontal test · ${plan?.label ?: "border"}", "Essai frontal · ${plan?.label ?: "bordure"}"), outcome, SystemClock.elapsedRealtime() - borderStarted, detail)
        val fields = resultFields(result) + mapOf("kind" to "border_frontal", "shapeId" to plan?.shapeId, "borderKind" to plan?.kind?.name,
            "contactX" to plan?.contact?.x, "contactY" to plan?.contact?.y, "passEndM" to plan?.passEndM, "outerOffsetM" to ui.outerOffsetM,
            "plannedFrontGapM" to plan?.plannedFrontGapM, "plannedPivotGapM" to plan?.plannedPivotGapM,
            "passEndFrontGapM" to ui.passEndFrontGapM, "passEndOvershootM" to ui.passEndOvershootM, "edgeFrontGapM" to ui.edgeFrontGapM,
            "contactCode" to borderContactCode, "x" to state.value.telemetry.x, "y" to state.value.telemetry.y, "heading" to state.value.telemetry.heading)
        log.report(borderId, fields); event("border_frontal_result", detail, fields)
        update { it.copy(borderFrontal = it.borderFrontal.copy(running = false, phase = BorderPhase.IDLE, outcome = outcome, status = detail,
            results = it.borderFrontal.results + result)) }
        borderId = ""; borderRequest = null
    }

    fun startNavTest(config: NavConfig) = startMapNavigation(config)

    /** Bypass preview from the received position, without any motor command. */
    fun previewObstacleCourse(obstacleId: String) {
        if (state.value.navTest.running) return
        try {
            val (obstacle, plan) = obstacleCourse(obstacleId)
            val center = ObstacleCourse.plannedCenterDistance(plan.points, obstacle)
            update { it.copy(navTest = it.navTest.copy(planned = plan.points, plannedBands = emptyList(), plannedBorders = emptyList(), courseObstacleId = obstacle.id,
                preview = tr("Preview: %.1f m round trip · passing %.2f m from the center (reserve %.2f m). Recalculated at launch.", "Aperçu : %.1f m aller-retour · passage à %.2f m du centre (réserve %.2f m). Recalculé au lancement.").format(plan.meters, center, ObstacleCourse.keepOutM(obstacle)))) }
        } catch (error: Exception) {
            update { it.copy(navTest = it.navTest.copy(planned = emptyList(), plannedBands = emptyList(), plannedBorders = emptyList(),
                courseObstacleId = obstacleId, preview = tr("Route unavailable: ${error.message}", "Parcours indisponible : ${error.message}"))) }
        }
    }

    /**
     * Real bypass of a saved obstacle: drive to the opposite side and back, blades stopped, bumper detection
     * active, same guards as driving to a point. Result: minimum observed gap.
     */
    fun startObstacleCourse(obstacleId: String) {
        if (state.value.navTest.running || navJob != null) return
        try {
            val (obstacle, plan) = obstacleCourse(obstacleId)
            val t = state.value.telemetry
            navCourse = obstacle; navCourseHome = Point(requireNotNull(t.x), requireNotNull(t.y))
            navCourseFar = ObstacleCourse.farSide(requireNotNull(navCourseHome), obstacle)
            event("obstacle_course_plan", tr("Obstacle bypass prepared", "Contournement d'obstacle préparé"), mapOf("obstacleId" to obstacle.id, "x" to obstacle.x, "y" to obstacle.y,
                "keepOutM" to ObstacleCourse.keepOutM(obstacle), "plannedCenterDistanceM" to ObstacleCourse.plannedCenterDistance(plan.points, obstacle)))
            startMapNavigation(NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
                angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0), preset = plan)
        } catch (error: Exception) {
            update { it.copy(navTest = it.navTest.copy(status = tr("Navigation refused: ${error.message}", "Navigation refusée : ${error.message}"))) }
            event("nav_test_refused", error.message ?: tr("Bypass refused", "Contournement refusé"))
        }
        if (!state.value.navTest.running) clearNavCourse()
    }

    private fun obstacleCourse(obstacleId: String): Pair<LocalObstacle, MapNavPlan> {
        val t = state.value.telemetry
        check(NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime()-positionPrevious, t.x, t.y) == null) { tr("Fresh position required", "Position fraîche obligatoire") }
        val obstacle = state.value.obstacles.firstOrNull { it.id == obstacleId } ?: error(tr("Obstacle not found", "Obstacle introuvable"))
        val start = Point(requireNotNull(t.x), requireNotNull(t.y))
        ObstacleCourse.startFailure(start, obstacle)?.let { error(it) }
        return obstacle to ObstacleCoursePlanner.plan(state.value.robotMap, start, ObstacleCourse.farSide(start, obstacle), start, state.value.obstacles)
    }

    /**
     * Full operation while moving: height set and reported, then a short route on the map (stripes and borders
     * with blades running, links with blades stopped), with the test-route guards; on end, stop or anomaly:
     * STOP and confirmed blade stop.
     */
    fun startBladeMoveTest(radiusM: Double, heightMm: Int, maxDurationS: Int) = startMapNavigation(NavConfig(
        linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
        angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent).coerceAtMost(Commands.MAX_ANGULAR_SPEED),
        tonte = true, heightMm = heightMm, automatic = true, maxDurationMs = maxDurationS * 1000L, maxRadiusM = radiusM))

    fun acknowledgeNavInterruption() = update { it.copy(navTest = it.navTest.copy(interruption = null)) }

    fun startPointNavigation(target: Point) {
        val busy = state.value.navTest.running || navJob != null
        startMapNavigation(NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent),
            angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0), target)
        // Drive started: destination saved, resume possible after a stop or an app restart.
        val robotId = state.value.selected?.address
        if (!busy && state.value.navTest.running && robotId != null) {
            pointNavRunning = true
            val resumed = state.value.pointResume?.takeIf { it.robotId == robotId && it.target == target }
            setPointResume(robotId, PointResume(robotId, target, System.currentTimeMillis()))
            event(if (resumed != null) "point_resume" else "point_resume_saved",
                if (resumed != null) tr("Resuming the interrupted move to a point", "Reprise du déplacement au point interrompu") else tr("Move-to-point destination saved", "Destination du déplacement au point sauvegardée"),
                mapOf("x" to target.x, "y" to target.y))
        }
    }

    /** Explicit abandonment of the interrupted move to a point. */
    fun discardPointResume() {
        if (state.value.navTest.running) return
        val id = state.value.pointResume?.robotId ?: return
        setPointResume(id, null)
        event("point_resume_discarded", tr("Interrupted move to a point abandoned by the user", "Déplacement au point interrompu abandonné par l'utilisateur"))
    }

    /** Move to a point in progress (not a navigation test): its end updates the saved state. */
    private var pointNavRunning = false

    private fun setPointResume(robotId: String, value: PointResume?) {
        update { it.copy(pointResume = value) }
        pointResumeWrites.trySend(robotId to value)
    }

    /** End of a move to a point: saved state cleared if it succeeded, otherwise kept with its reason. */
    private fun pointNavigationEnded(outcome: String, reason: String) {
        if (!pointNavRunning) return
        pointNavRunning = false
        val current = state.value.pointResume ?: return
        setPointResume(current.robotId, PointResume.after(current, outcome, reason))
    }

    private fun startMapNavigation(requested: NavConfig, target: Point? = null, preset: MapNavPlan? = null) {
        // Blades stopped (move to a point, bypass, border positioning, map sync, navigation test): no height chosen, the
        // deck stays at the reported height instead of `NavConfig`'s 60 mm ([HoldHeight], DumbyLuba#2). Cutting test: its height.
        val config = if (requested.tonte) requested
            else requested.copy(heightMm = HoldHeight.mm(state.value.telemetry, bladeHeightRequest, state.value.cutHeights))
        if (state.value.robotRoute.running) { status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mowing.active) { status(tr("Finish the mowing mission first", "Terminer la mission de tonte")); return }
        if (state.value.navTest.running || navJob != null) return
        if (state.value.borderFrontal.running && !borderPositioning) { status(tr("Finish the frontal border test first", "Terminer l'essai frontal de bordure")); return }
        try {
            val t = state.value.telemetry
            // Robot on its base: charging states accepted, it leaves in a straight line first ([BaseDeparture]).
            val charging = t.chargeState in setOf(1,2)
            val failure = NavSafety.startFailure(
                state.value.selected?.name,
                if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious,
                // Recorded outline (sync with the Mammotion app): the robot is in its recording state, not READY.
                t.battery, if ((charging && t.robotState in setOf(11,15,39)) || (config.drawState != null && t.robotState == config.drawState)) 11 else t.robotState,
                guardSensors(t.sensorStatus), t.selfCheck,
                state.value.campaign.running, state.value.bladeTestActive, state.value.mapLoading,
                config.heightMm, config.batteryFloor, ignoreOffMapLatch = config.pointDestination && !config.tonte && offMapLatchIgnored(t),
                heights = state.value.cutHeights)
            if (failure != null) throw IllegalArgumentException(failure)
            check(!state.value.taskStopPending && gatt != null && state.value.ready && !closing && log.healthy) { tr("BLE / log not ready", "BLE / journal non prêt") }
            check(!state.value.teach.running && heightTarget == null && !bladeMayBeOn && !bladeOffPending) { tr("Finish the obstacle / height / blade-stop test first", "Terminer le test obstacle / hauteur / arrêt lames") }
            check(queue.size <= 4) { tr("BLE queue busy: wait for the operations to finish", "File BLE occupée : attendre la fin des opérations") }
            check(NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime()-positionPrevious, t.x, t.y) == null) { tr("Fresh position required to navigate on the map", "Position fraîche obligatoire pour naviguer sur la carte") }
            check(t.heading?.isFinite() == true) { tr("Heading missing or invalid", "Cap absent ou invalide") }
            check(!config.tonte || target == null) { tr("Move to a point: blades always stopped", "Déplacement au point : lames toujours arrêtées") }
            navConfig = config
            localIncidentPending = false
            navContactObstacle = null
            navContact = null
            val here = Point(requireNotNull(t.x),requireNotNull(t.y))
            val navMap = state.value.robotMap
            val store = fr.luba.poc.simulation.BaseStore(getApplication(), navMap)
            navBase = runCatching { store.resolve(navMap) }.getOrNull()
            navAccesses = runCatching { store.accesses() }.getOrDefault(emptyList())
            // Robot on its base or in its safety zone: straight exit to the link point, the path starts there.
            val departure = fr.luba.poc.simulation.BaseDeparture.required(navBase, here, charging)
            if (departure != null) require(preset == null) { tr("Robot on its base: leave the station first (Charging station → Leave the station)",
                "Robot sur sa base : sortir d'abord de la station (Station de charge → Sortir de la station)") }
            navMapPlan = preset ?: if (target == null) MapNavPlanner.plan(navMap, departure?.approach ?: here, config.maxRadiusM, state.value.obstacles).copy(departure = departure)
                else fr.luba.poc.planning.RoutePlanning.point(navMap, here, target, state.value.obstacles,
                    offMap = config.pointDestination, base = navBase, charging = charging, accesses = navAccesses)
            navPlanObstacles = state.value.obstacles.toSet()
            navStarted = SystemClock.elapsedRealtime()
            navTestId = UUID.randomUUID().toString()
            navStart = t.x?.let { x -> t.y?.let { y -> NavSample(x, y, t.heading, 0) } }
            bladeHeightRequest = config.heightMm
            if (config.tonte) { bladeOffWrittenAt = 0L; bladeStopVerified = false }
            val cutMeters = if (config.tonte) requireNotNull(navMapPlan).cuttingMeters else 0.0
            update {
                it.copy(navTest = NavTestUi(id = navTestId, running = true, tonte = config.tonte, preview = it.navTest.preview, plannedCutMeters = cutMeters, courseObstacleId = navCourse?.id,
                    segmentCount = requireNotNull(navMapPlan).points.size-1, outcome = "RUNNING",
                    planned = requireNotNull(navMapPlan).points, plannedBands = requireNotNull(navMapPlan).bands,
                    plannedOffMap = requireNotNull(navMapPlan).offMap,
                    plannedExit = requireNotNull(navMapPlan).departure?.let { d -> listOf(d.from, d.approach) }.orEmpty(),
                    plannedBorders = requireNotNull(navMapPlan).borders,
                    status = if (navCourse != null) tr("Obstacle bypass · %.1f m round trip", "Contournement de l'obstacle · %.1f m aller-retour").format(requireNotNull(navMapPlan).meters)
                        else if (target != null) tr("Move to a point · %.1f m", "Déplacement au point · %.1f m").format(requireNotNull(navMapPlan).meters)
                        else if (config.tonte) tr("Cutting test while moving: ${requireNotNull(navMapPlan).bands.size} stripes · ${requireNotNull(navMapPlan).borders.size} borders · %.1f m including %.1f m with blades running", "Essai de coupe en déplacement : ${requireNotNull(navMapPlan).bands.size} bandes · ${requireNotNull(navMapPlan).borders.size} bordures · %.1f m dont %.1f m lames en marche").format(requireNotNull(navMapPlan).meters, cutMeters)
                        else tr("Map route: ${requireNotNull(navMapPlan).bands.size} stripes · ${requireNotNull(navMapPlan).borders.size} borders · %.1f m", "Parcours sur carte : ${requireNotNull(navMapPlan).bands.size} bandes · ${requireNotNull(navMapPlan).borders.size} bordures · %.1f m").format(requireNotNull(navMapPlan).meters)))
            }
            motionWrittenAt = 0L; motionStarvedLogged = false
            event("nav_test_start", if (target == null) tr("Supervised map navigation", "Navigation sur carte supervisée") else tr("Supervised move to a point", "Déplacement au point supervisé"), mapOf("testId" to navTestId,
                "robot" to state.value.selected?.name, "firmware" to t.firmware,
                "planned" to requireNotNull(navMapPlan).points.map { mapOf("x" to it.x,"y" to it.y) },
                "linearSpeedRaw" to config.linearSpeed, "angularSpeedRaw" to config.angularSpeed,
                "tonte" to config.tonte, "heightMm" to config.heightMm, "automatic" to config.automatic,
                "maxDurationMs" to config.maxDurationMs, "maxRadiusM" to config.maxRadiusM,
                "startX" to navStart?.x, "startY" to navStart?.y, "destinationX" to target?.x, "destinationY" to target?.y))
            // Move beyond the mapped zones: recalled by a notification, the section has no border check.
            if (target != null && requireNotNull(navMapPlan).offMap.isNotEmpty()) {
                val offMeters = requireNotNull(navMapPlan).offMapMeters
                event("point_off_map", tr("Move to a point beyond the mapped zones", "Déplacement au point au-delà des zones cartographiées"),
                    mapOf("testId" to navTestId, "offMapM" to offMeters, "sections" to requireNotNull(navMapPlan).offMap.size))
                alert(AlertKind.OFF_MAP, Severity.WARNING, tr("Move to a point: %.1f m beyond the mapped zones (dotted on the map), without border check",
                    "Déplacement au point : %.1f m au-delà des zones cartographiées (en pointillé sur la carte), sans contrôle des bords")
                    .format(fr.luba.poc.i18n.I18n.locale, offMeters))
            }
            persistNav("RUNNING", tr("Map route planned", "Parcours sur carte planifié"))
            send(reportsCommand(false, periodMs = 200), "NAV_RPT_FAST")
            navJob = viewModelScope.launch { runMapNavTest(config, requireNotNull(navMapPlan)) }
        } catch (error: Exception) {
            update { it.copy(navTest = it.navTest.copy(status = tr("Navigation refused: ${error.message}", "Navigation refusée : ${error.message}"))) }
            event("nav_test_refused", error.message ?: tr("Drive refused", "Marche refusée"))
        }
    }

    /** User stop of the drive: STOP (+ BLADES_OFF when mowing), the link stays open. */
    fun stopNavTest(reason: String = tr("Release / manual stop", "Relâchement / arrêt manuel"), userInitiated: Boolean = false) {
        if (!state.value.navTest.running && navTestId.isBlank()) return
        if (!userInitiated && state.value.navTest.running) {
            alertMotionFailure(reason)
            alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, reason)
        }
        abandonNav(reason, userInitiated)
        navStopAndCleanup(reason)
    }

    /** Base and manual accesses of the current move (planning and straight exit from the station). */
    private var navBase: fr.luba.poc.simulation.MapBase? = null
    private var navAccesses: List<List<Point>> = emptyList()

    /** Fresh state during a straight exit from the base: charging states accepted, as for routes. */
    private fun navExitState(): fr.luba.poc.protocol.Telemetry {
        if (!log.healthy || !state.value.ready || closing) throw NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
        check(queue.size <= 4) { tr("BLE interrupted or queue saturated", "BLE interrompu ou file saturée") }
        val now = SystemClock.elapsedRealtime()
        val live = state.value.telemetry
        NavSafety.tickFailure(if (devicePrevious == 0L) null else now - devicePrevious, live.battery, guardSensors(live.sensorStatus), false, -1)?.let { throw NavAbort(it) }
        if (!fr.luba.poc.protocol.SelfCheck.nominal(live.selfCheck)) throw NavAbort(tr("Robot protection active", "Protection robot active"))
        if (live.robotState !in listOf(11,15,20,39)) throw NavAbort(tr("Robot state incompatible with the station (state ${live.robotState})", "État robot incompatible avec la station (état ${live.robotState})"))
        NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else now - positionPrevious, live.x, live.y)?.let { throw NavAbort(it) }
        return live
    }

    /**
     * Move started on the base (or inside its safety zone): slow straight exit to the link point ([straightDockExit],
     * same exit as routes), blades OFF, before any pivot; the path then starts from the link point.
     */
    private suspend fun navDockExit(config: NavConfig, exit: fr.luba.poc.simulation.BaseDeparture.Exit) {
        val base = requireNotNull(navBase) { tr("Base unknown", "Base inconnue") }
        val began = SystemClock.elapsedRealtime()
        update { it.copy(navTest = it.navTest.copy(status = tr("Leaving the base in a straight line, slowly · blades OFF", "Sortie de base en ligne droite, lentement · lames OFF"))) }
        purgeQueuedCommands(); send(commands.stop(), "NAV_DOCK_EXIT_PREPARE")
        val routes = fr.luba.poc.simulation.PointPlanner.stationRoutes(state.value.robotMap, state.value.obstacles, base, navAccesses)
        val result = try {
            straightDockExit(exit.base, exit.approach, routes, config.heightMm, RouteRecharge.RESERVE_PERCENT,
                obstacles = { state.value.obstacles }, live = { navExitState() }, onPosition = {},
                onGateway = { update { it.copy(navTest = it.navTest.copy(status = tr("Leaving the base in a straight line, slowly · blades OFF · run by the gateway",
                    "Sortie de base en ligne droite, lentement · lames OFF · exécutée par la passerelle"))) } },
                stopLabel = "NAV_DOCK_EXIT", navProgress = {})
        } catch (abort: NavAbort) { throw abort }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { throw NavAbort(tr("Base exit interrupted: ${error.message}", "Sortie de base interrompue : ${error.message}")) }
        update { it.copy(navTest = it.navTest.copy(plannedExit = emptyList())) }
        event("nav_dock_exit", tr("Slow straight exit to the link point before the move", "Sortie rectiligne lente jusqu'au point de liaison avant le déplacement"),
            mapOf("testId" to navTestId, "durationMs" to SystemClock.elapsedRealtime() - began, "viaGateway" to result.viaGateway,
                "headingJumpDeg" to result.headingJump, "fromX" to exit.from.x, "fromY" to exit.from.y, "approachX" to exit.approach.x, "approachY" to exit.approach.y))
    }

    private suspend fun runMapNavTest(config: NavConfig, initialPlan: MapNavPlan) {
        var plan = initialPlan
        // Kept after a recovery (new path from the actual position, built without the flag).
        val alignAtStart = initialPlan.alignAtStart
        var index = 1
        var repairs = 0
        var recoveries = 0
        // Fused heading kept from one replanning to the next (offset between published heading and travelled direction).
        // Navigation test: pose predicted at the moment the command takes effect (BLE + firmware delay).
        val reckoning = DeadReckoning(commandDelayMs = DeadReckoning.NAV_COMMAND_DELAY_MS, calibration = savedCalibration(),
            publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M).also { it.slide = slideVelocity() }
        // Chained drive (sync with the Mammotion app): heading gap of the previous drive taken over when the robot has not
        // moved since and it was measured over a long enough straight line. Confirmed again over 15 cm of the short link
        // before the outline, it was off by 10° once in ten draws of the bench (1 cm of RTK noise), and the outline then
        // drifted into the element ([MapSyncSweepTest]).
        val remembered = navHeadingMemory.also { navHeadingMemory = null }
        if (config.keepHeading && remembered != null) {
            val t = state.value.telemetry
            val reason = remembered.refusal(SystemClock.elapsedRealtime(), t.x?.let { x -> t.y?.let { y -> Point(x, y) } }, t.heading)
            if (reason == null) reckoning.inheritHeading(remembered.offset, remembered.supportM)
            event("nav_heading_kept", if (reason == null) tr("Heading gap of the previous drive kept", "Écart de cap du trajet précédent conservé")
                else tr("Heading confirmed again: $reason", "Cap reconfirmé : $reason"), mapOf("testId" to navTestId, "offset" to remembered.offset, "supportM" to remembered.supportM, "kept" to (reason == null)))
        }
        val navSlideMeter = PivotSlideMeter()
        var fixAt = 0L
        var recovery: String? = null
        try {
            // Robot on its base: slow straight exit to the link point before anything else (no pivot near the station).
            plan.departure?.let { exit -> navDockExit(config, exit); plan = plan.copy(departure = null); navMapPlan = plan }
            // Manual mode (state 20) carries the height: in the READY state the setpoint alone is ignored.
            // Recorded outline: the manual mode would end the recording; the recording state is checked instead.
            if (config.tracing) confirmDrawState(config) else enterNavBumperMode(config)
            if (config.tonte) navSetHeight(config.heightMm)
            while (index < plan.points.size) {
                // Blades running: no automatic bypass, the test stops and cuts the blades.
                if (config.tonte && localIncidentPending) throw NavAbort(tr("Obstacle or contact during the cutting test: stopped, blades cut", "Obstacle ou contact pendant l'essai de coupe : arrêt, lames coupées"))
                recovery?.let { reason ->
                    // Recorded outline: a new path would be drawn into the element; stop instead.
                    if (config.tracing) throw NavAbort(tr("$reason: outline left, recording abandoned", "$reason : contour quitté, enregistrement abandonné"))
                    // Recovery: STOP, fresh still position, new path from the actual position.
                    recovery = null
                    if (++recoveries > MAX_NAV_RECOVERIES) throw NavAbort(tr("$reason; $MAX_NAV_RECOVERIES recoveries already attempted", "$reason ; $MAX_NAV_RECOVERIES rattrapages déjà tentés"))
                    purgeQueuedCommands(); send(commands.stop(), "NAV_RECOVERY_STOP")
                    reckoning.sent(SystemClock.elapsedRealtime(), 0, 0)
                    update { it.copy(navTest = it.navTest.copy(status = tr("Recovery $recoveries/$MAX_NAV_RECOVERIES: $reason", "Rattrapage $recoveries/$MAX_NAV_RECOVERIES : $reason"))) }
                    val stoppedAt = positionPrevious
                    delay(1300)
                    navTick(config, null)
                    val t = state.value.telemetry
                    if (positionPrevious <= stoppedAt || t.x == null || t.y == null) throw NavAbort(tr("$reason; no fresh position after STOP", "$reason ; position fraîche absente après STOP"))
                    val here = Point(t.x, t.y)
                    plan = try {
                        val course = navCourse
                        // Bypass: still head for the opposite side if it was not reached, then come back.
                        if (course != null) ObstacleCoursePlanner.plan(state.value.robotMap, here, requireNotNull(navCourseFar), requireNotNull(navCourseHome),
                            state.value.obstacles, includeFar = plan.labels.drop(index).contains(ObstacleCourse.FAR_LABEL))
                        else fr.luba.poc.planning.RoutePlanning.point(state.value.robotMap, here, plan.points.last(), state.value.obstacles,
                            offMap = config.pointDestination, base = navBase, charging = t.chargeState in setOf(1,2), accesses = navAccesses) }
                        catch (e: Exception) { throw NavAbort(tr("$reason; no safe path from the position reached (${e.message})", "$reason ; aucun chemin sûr depuis la position atteinte (${e.message})")) }
                    navPlanObstacles = state.value.obstacles.toSet()
                    index = 1
                    navMapPlan = plan
                    update { it.copy(navTest = it.navTest.copy(planned = plan.points, plannedOffMap = plan.offMap, segmentCount = plan.points.size-1,
                        status = tr("Recovery $recoveries/$MAX_NAV_RECOVERIES: new path from the actual position", "Rattrapage $recoveries/$MAX_NAV_RECOVERIES : nouveau chemin depuis la position réelle"))) }
                    event("nav_recovery", reason, mapOf("recovery" to recoveries, "x" to here.x, "y" to here.y,
                        "headingOffset" to reckoning.headingOffset, "headingConfirmed" to reckoning.headingConfirmed,
                        "points" to plan.points.map { mapOf("x" to it.x, "y" to it.y) }))
                }
                if (localIncidentPending) {
                    localIncidentPending = false
                    if (config.tracing) throw NavAbort(tr("Obstacle or contact on the outline: stopped, no bypass while recording", "Obstacle ou contact sur le contour : arrêt, pas de contournement pendant un enregistrement"))
                    if (++repairs > 3) throw NavAbort(tr("Three repairs: stop maintained, correct the map", "Trois réparations : arrêt maintenu, corriger la carte"))
                    // New telemetry required after STOP: two still positions published after it takes effect
                    // (1 Hz, delay ≈ 0.6 s); no resume on the old report.
                    val stopped = StopConfirmation(SystemClock.elapsedRealtime(), DeadReckoning.NAV_COMMAND_DELAY_MS)
                    var t = state.value.telemetry
                    while (true) {
                        navTick(config, if (plan.physicalGuard) null else plan.zone)
                        t = state.value.telemetry
                        val result = if (t.x == null || t.y == null) false else stopped.observe(SystemClock.elapsedRealtime(), positionPrevious, Point(t.x,t.y))
                        if (result == true) break
                        if (result == false) throw NavAbort(tr("Physical stop not confirmed by the positions: resume refused", "Arrêt physique non confirmé par les positions : reprise refusée"))
                        delay(100)
                    }
                    val here = Point(requireNotNull(t.x),requireNotNull(t.y))
                    val contact = navContact
                    navContact = null
                    plan = if (contact != null) {
                        // Contact while driving to a point: back-off along the trail, then a path recomputed around
                        // the saved obstacle (same navigation as mowing: borders, no-go zones, obstacles).
                        // Obstacle created at contact: moved in front of the stopped position (two still positions), as
                        // during mowing. Estimated from the last published position (1 Hz), it could overlap the chassis
                        // stopped 10 to 20 cm further on: no back-off possible, drive stopped (end-to-end bench).
                        navContactObstacle?.let { id -> state.value.obstacles.firstOrNull { it.id == id } }?.let { touched ->
                            val center = TeachObstacle.estimateCenter(TeachContact(here.x, here.y, contact.heading, System.currentTimeMillis()), touched.radiusM)
                            saveObstacle(touched.copy(x = center.x, y = center.y))
                        }
                        navContactObstacle = null
                        val obstacles = state.value.obstacles
                        val trail = state.value.navTest.trail.map { Point(it.x, it.y) }.takeLast(400)
                        // Contact beyond the map: back-off along the corridor of the trace driven there.
                        val physical = if (config.pointDestination) fr.luba.poc.planning.RoutePlanning.backOffPhysical(state.value.robotMap, obstacles, here, trail)
                            else fr.luba.poc.planning.RoutePlanning.physical(state.value.robotMap, obstacles)
                        val destination = plan.points.last()
                        val options = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            ObstacleBypass.backOffCandidates(physical, here, contact.heading, trail).mapNotNull { q ->
                                runCatching { q to fr.luba.poc.planning.RoutePlanning.point(state.value.robotMap, q, destination, obstacles,
                                    offMap = config.pointDestination, base = navBase, accesses = navAccesses) }.getOrNull()
                            }
                        }
                        val (back, next) = options.firstOrNull { (q, p) -> p.routes?.supportsPivot(q) == true } ?: options.firstOrNull()
                            ?: throw NavAbort(tr("Bumper contact: no back-off along the trail and no safe path to the destination, stop maintained", "Contact bumper : aucun recul sur la trace ni chemin sûr vers la destination, arrêt maintenu"))
                        update { it.copy(navTest = it.navTest.copy(status = tr("Contact: %.0f cm back-off in a straight line", "Contact : recul de %.0f cm en ligne droite").format(back.distance(here)*100))) }
                        try {
                            reverseStraight(here, back, contact.heading, physical, config.heightMm, "NAV", afterContact = true)
                            update { it.copy(navTest = it.navTest.copy(status = tr("Obstacle photo · robot still, blades OFF", "Photo de l'obstacle · robot immobile, lames OFF"))) }
                            holdForObstaclePhoto("NAV", config.heightMm)
                            awaitCollisionLatchCleared("NAV", config.heightMm)
                        }
                        catch (error: IllegalStateException) { throw NavAbort(error.message ?: tr("Back-off interrupted", "Recul interrompu")) }
                        event("nav_obstacle_bypass", tr("Path recomputed around the obstacle hit", "Chemin recalculé autour de l'obstacle touché"), mapOf("backOffM" to back.distance(here),
                            "points" to next.points.map { mapOf("x" to it.x, "y" to it.y) }))
                        next
                    } else LocalReplanner.repair(plan, here, index, state.value.obstacles)
                    navPlanObstacles = state.value.obstacles.toSet()
                    index = 1
                    navMapPlan = plan
                    update { it.copy(navTest = it.navTest.copy(planned = plan.points, plannedOffMap = plan.offMap, segmentCount = plan.points.size-1,
                        status = if (contact != null) tr("Obstacle bypassed: new path from the back-off", "Obstacle contourné : nouveau chemin depuis le recul") else tr("Safe local bypass computed", "Contournement local sûr calculé"))) }
                    if (contact == null) event("nav_local_replan", tr("Local link computed", "Raccord local calculé"), mapOf("windowM" to LocalReplanner.WINDOW_M,
                        "points" to plan.points.map { mapOf("x" to it.x, "y" to it.y) }))
                }
                // New path from a position inside the base safety zone: straight exit to the link point first.
                plan.departure?.let { exit -> navDockExit(config, exit); plan = plan.copy(departure = null); navMapPlan = plan; index = 1 }
                // Gateway mounted on the robot: same tracking as mowing (LegRunner, over Bluetooth on the robot), without
                // depending on Wi-Fi for each setpoint. Contact, added obstacle and deviation are still handled here as before.
                // Recorded outline: tracked from here (the gateway tracking enters the manual mode).
                if (!config.tonte && !config.tracing && plan.physicalGuard && plan.routes != null && autonomousLegsAvailable()) {
                    val outcome = try {
                        followNavRemote(plan, index) { target ->
                            // Points passed: result recorded as with tracking from here.
                            while (index < target) {
                                recordNavSegment(index, plan.labels[index], "PASS", SystemClock.elapsedRealtime()-navStarted, tr("Point passed (tracked by the gateway)", "Point franchi (suivi par la passerelle)"))
                                index++
                            }
                        }
                    }
                        catch (encounter: ObstacleEncounter) {
                            val contact = encounter.contact
                            if (contact != null) {
                                // Contact seen by the gateway (Wi-Fi down: never reported here): obstacle saved at the stop.
                                if (!localIncidentPending) captureLocalIncident(contact.code)
                                if (navContact == null) navContact = contact
                            }
                            localIncidentPending = true
                            if (!state.value.navTest.running) throw NavAbort(tr("${encounter.message}: stop maintained", "${encounter.message} : arrêt maintenu"))
                            continue
                        }
                        catch (failure: IllegalStateException) {
                            val reason = failure.message ?: tr("Tracking interrupted by the gateway", "Suivi interrompu par la passerelle")
                            // Deviation or envelope: same recovery as tracking from here (STOP, new path from the actual position).
                            if (RemoteNavOutcome.recoverable(reason)) { recovery = reason; continue }
                            throw NavAbort(reason)
                        }
                    if (outcome == null) event("gateway_leg_fallback", tr("Drive too large for the gateway: tracked from here", "Déplacement trop volumineux pour la passerelle : suivi d'ici"))
                    else {
                        val now = SystemClock.elapsedRealtime()
                        while (index < plan.points.size) {
                            recordNavSegment(index, plan.labels[index], "PASS", now-navStarted, tr("Point reached (tracked by the gateway)", "Point atteint (suivi par la passerelle)"))
                            index++
                        }
                        continue
                    }
                }
                // Continuous tracking of all remaining points: pivot only at marked corners.
                val path = plan.points.zipWithNext().map { (a,b) -> SimEdge(a,b,false) }
                // Cutting test: one range per blade state (stripe / border running, link stopped).
                val rangeEnd = if (config.tonte) plan.bladeRangeEnd(index-1) else path.size
                val endPoint = rangeEnd
                val blades = config.tonte && plan.cutting(index-1)
                val follower = PathFollower(path,index-1,rangeEnd,RangeArrival.TARGET_M).also { it.slide = slideVelocity(); if (alignAtStart) it.alignFirst() }
                // End of range confirmed by a position measured after the stop (the robot used to stop short).
                val arrival = RangeArrival(DeadReckoning.NAV_COMMAND_DELAY_MS)
                var started = SystemClock.elapsedRealtime()
                val gate = if (config.tonte) startNavBladeRange(index, blades, config, reckoning) else null
                val gateAt = SystemClock.elapsedRealtime()
                var bladeObserved = !blades
                var offPending = config.tonte && !blades && bladeMayBeOn
                var cutterQueryAt = 0L
                var bestDistance = Double.POSITIVE_INFINITY
                var bestIndex = follower.index
                var progressAt = started
                var nextErrorQuery = started
                val motionProgress=MotionProgressGuard()
                fun showPoint() = update { it.copy(navTest = it.navTest.copy(segmentIndex = index,
                    status = "${plan.labels[index]} · point $index/${plan.points.size-1}")) }
                showPoint()
                var finished = false
                while (true) {
                    if (localIncidentPending) break
                    navTick(config,if (plan.physicalGuard) null else plan.zone)
                    val now = SystemClock.elapsedRealtime()
                    if (now-navStarted >= config.maxDurationMs) throw NavAbort(tr("Maximum duration reached: route incomplete (point $index/${plan.points.size-1})", "Durée maximale atteinte : parcours incomplet (point $index/${plan.points.size-1})"))
                    val t = state.value.telemetry
                    NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else now-positionPrevious,t.x,t.y)?.let { throw NavAbort(it) }
                    if (gate != null) {
                        val bits = ((t.sensorStatus ?: -1) shr 9) and 7
                        if (gate.confirmed(devicePrevious, bits)) {
                            if (blades && !bladeObserved) { bladeObserved = true
                                recordNavSegment(index, tr("Blades running", "Lames en marche"), "PASS", now-gateAt, tr("Rotation confirmed by the robot in ${now-gateAt} ms", "Rotation confirmée par le robot en ${now-gateAt} ms")) }
                            if (offPending) { offPending = false; bladeMayBeOn = false
                                recordNavSegment(index, tr("Blades stopped", "Lames arrêtées"), "PASS", now-gateAt, tr("Stop confirmed by the robot in ${now-gateAt} ms", "Arrêt confirmé par le robot en ${now-gateAt} ms")) }
                        }
                        if (offPending) {
                            if (now-gateAt > 3000) throw NavAbort(tr("Blade stop not confirmed within 3 s before the link", "Arrêt des lames non confirmé en 3 s avant la liaison"))
                            delay(200); continue
                        }
                        if (!blades && bits != 0) throw NavAbort(tr("Blades active during a link", "Lames actives pendant une liaison"))
                        if (blades && bladeObserved && bits != 1) throw NavAbort(tr("Blade rotation interrupted", "Rotation des lames interrompue"))
                        if (blades && !bladeObserved && now-gateAt > 5000) throw NavAbort(tr("Blade rotation not confirmed within 5 s", "Rotation des lames non confirmée en 5 s"))
                        if (blades && now >= cutterQueryAt) { send(commands.cutterQuery(), "NAV_CUTTER_QUERY"); cutterQueryAt = now+2000 }
                    }
                    val published = Point(requireNotNull(t.x),requireNotNull(t.y))
                    val reportedHeading = t.heading?.takeIf { it.isFinite() } ?: throw NavAbort(tr("Heading missing or invalid", "Cap absent ou invalide"))
                    // Positions at 1 Hz: heading realigned on the travelled direction, command based on the predicted pose.
                    val fixed = positionPrevious != fixAt
                    if (fixed) { fixAt = positionPrevious; reckoning.fix(published,reportedHeading,fixAt); saveCalibration(reckoning) }
                    val heading = reckoning.heading(reportedHeading)
                    // Rotation centre: the published point is 8 cm behind it ([DeadReckoning.PUBLISHED_POINT_BEHIND_M]).
                    val position = reckoning.measured ?: reckoning.center(published,heading)
                    if (fixed) navSlideMeter.update(position,System.currentTimeMillis(),reckoning.rotationSlide)?.let { learnSlide(it,"local") }
                    val headingTrusted = reckoning.headingConfirmed
                    confirmedHeading = heading.takeIf { headingTrusted }
                    MapNavGuard.positionFailure(plan,position,heading,headingTrusted)?.let { reason ->
                        event("nav_margin", reason, mapOf("segment" to index, "x" to position.x, "y" to position.y, "heading" to heading,
                            "reportedHeading" to reportedHeading, "headingConfirmed" to headingTrusted))
                        throw NavAbort(reason)
                    }
                    val predicted = reckoning.predict(now) ?: RobotPose(position,heading)
                    val speedPercent = state.value.trajectorySpeedPercent
                    // Heading not confirmed by motion: slow advance until the first direction measurement.
                    // After a pivot: slow advance until the position that measures the heading reached.
                    // Recorded outline: speed capped by the drive (closer tracking, evenly spaced recorded points).
                    val chosenLinear = PathFollower.linearFor(speedPercent).let { if (config.tracing) minOf(it, config.linearSpeed) else it }
                    val linearLimit = if (!headingTrusted) minOf(chosenLinear, DeadReckoning.UNCONFIRMED_LINEAR)
                        else reckoning.postPivotLinear(chosenLinear)
                    val command = follower.command(predicted.position,predicted.headingDegrees,linearLimit,
                        minOf(config.angularSpeed,Commands.MAX_ANGULAR_SPEED),reckoning.degreesPerUnit,reckoning.metersPerUnit,
                        RangeArrival.APPROACH_MIN_LINEAR,follower.deviation(position),reckoning.rotationCalibrated)
                    // Very short range: wait while stopped for blade confirmation rather than leaving it.
                    if (command.done && blades && !bladeObserved) {
                        motionProgress.reset(); progressAt = now
                        send(commands.movement(0,0), "NAV_WAIT_BLADES"); reckoning.sent(now,0,0); delay(200); continue
                    }
                    var arrived = false
                    if (command.done) {
                        when (arrival.check(now, fixAt, position, follower, path[rangeEnd-1].b)) {
                            null -> {
                                // Stopped: a position taken after the stop is awaited before changing the blade state.
                                if (now >= nextErrorQuery) { navKeepAlive(blades, config); nextErrorQuery = now+500 }
                                // Commanded stop: standing still is not a blockage.
                                motionProgress.reset(); progressAt = now
                                send(commands.movement(0,0), "NAV_SETTLE point=$endPoint"); reckoning.sent(now,0,0); delay(200); continue
                            }
                            false -> {
                                event("nav_settle", tr("Stopped before the point: approach resumed", "Arrêt avant le point : approche reprise"), mapOf("testId" to navTestId, "point" to endPoint,
                                    "approach" to arrival.approaches, "distanceM" to arrival.distanceM, "remainingM" to arrival.remainingM,
                                    "x" to position.x, "y" to position.y))
                                continue
                            }
                            true -> {
                                arrived = true
                                event("nav_arrival", tr("End-of-range point confirmed by the measured position", "Point de fin de plage confirmé par la position mesurée"), mapOf("testId" to navTestId,
                                    "point" to endPoint, "approaches" to arrival.approaches, "distanceM" to arrival.distanceM,
                                    "remainingM" to arrival.remainingM, "x" to position.x, "y" to position.y))
                            }
                        }
                    }
                    // Points passed without stopping: result recorded for each one.
                    while (index < follower.index+1 || (arrived && index <= endPoint)) {
                        recordNavSegment(index,plan.labels[index],"PASS",now-started,tr("Point passed within 15 cm or overtaken on the route", "Point franchi à moins de 15 cm ou dépassé sur le trajet"))
                        started = now; index++
                        if (index > endPoint) break
                        showPoint()
                    }
                    if (arrived) { finished = true; break }
                    val edge = path[follower.index]
                    val deviation = follower.deviation(position)
                    if (deviation > MapNavFollower.MAX_DEVIATION_M) {
                        event("nav_deviation", tr("Stopped: deviation from the segment", "Arrêt : écart au segment"), mapOf("segment" to index,
                            "deviationM" to deviation, "limitM" to MapNavFollower.MAX_DEVIATION_M,
                            "x" to position.x, "y" to position.y, "fromX" to edge.a.x, "fromY" to edge.a.y,
                            "targetX" to edge.b.x, "targetY" to edge.b.y, "heading" to heading, "reportedHeading" to reportedHeading,
                            "headingOffset" to reckoning.headingOffset, "headingConfirmed" to headingTrusted,
                            "rawCompassHeading" to t.rawHeading, "positionAgeMs" to now-positionPrevious))
                        val reason = tr("Route deviation greater than 30 cm (%.1f cm measured)", "Écart au trajet supérieur à 30 cm (%.1f cm mesurés)").format(deviation*100)
                        if (plan.physicalGuard) { recovery = reason; break }
                        throw NavAbort(reason)
                    }
                    val blocked = MapNavGuard.unplannedObstacle(position,command.target,state.value.obstacles,navPlanObstacles)
                    if (blocked != null) {
                        captureLocalIncident(0, blocked)
                        break
                    }
                    val distance = position.distance(edge.b)
                    if (follower.index > bestIndex || distance < bestDistance-0.03) { bestIndex = follower.index; bestDistance = distance; progressAt = now }
                    val (linear,angular) = command.linear to command.angular
                    if (linear == 0) progressAt=now
                    if (now-progressAt > 15_000) throw NavAbort(tr("No progress towards the point for 15 s", "Aucune progression vers le point depuis 15 s"))
                    if (motionProgress.blocked(position,positionPrevious,now,linear)) throw NavAbort(tr("Presumed blockage for 3 s: bumper contact not confirmed", "Blocage présumé depuis 3 s : contact bumper non confirmé"))
                    // Envelope and corridor ahead of the robot, projected at most up to the anticipated target.
                    val motionFailure = MapNavGuard.motionFailure(plan,position,heading,command.target,linear,angular,headingTrusted)
                    if (motionFailure != null) {
                        event("nav_motion_guard", motionFailure, mapOf("segment" to index, "x" to position.x, "y" to position.y,
                            "heading" to heading, "reportedHeading" to reportedHeading, "linear" to linear, "angular" to angular,
                            "targetX" to command.target.x, "targetY" to command.target.y))
                        if (plan.physicalGuard) { recovery = motionFailure; break }
                        throw NavAbort(motionFailure)
                    }
                    // Bumper contact: error history polled twice per second, manual mode kept alive.
                    if (now >= nextErrorQuery) { navKeepAlive(blades, config); nextErrorQuery = now+500 }
                    // Link too slow (gateway): setpoints issued but no longer written, the robot stops by itself.
                    val motionGap = if (motionWrittenAt > 0) SystemClock.elapsedRealtime() - motionWrittenAt else 0L
                    if (linear != 0 && motionGap > 1500 && !motionStarvedLogged) {
                        motionStarvedLogged = true
                        event("nav_motion_starved", tr("No motion setpoint written for $motionGap ms: link too slow", "Aucune consigne de mouvement écrite depuis $motionGap ms : liaison trop lente"),
                            mapOf("testId" to navTestId, "segment" to index, "gapMs" to motionGap, "queueDepth" to queue.size,
                                "viaGateway" to state.value.viaGateway, "x" to position.x, "y" to position.y))
                    } else if (motionGap <= 1500) motionStarvedLogged = false
                    send(commands.movement(linear,angular), "NAV_MAP_MOVE linear=$linear angular=$angular point=$index")
                    reckoning.sent(now,linear,angular)
                    delay(200)
                }
                if (localIncidentPending || recovery != null) continue
                purgeQueuedCommands(); send(commands.stop(),"NAV_MAP_END_STOP")
                if (finished) index = endPoint+1
            }
            val end = state.value.telemetry
            navHeadingMemory = if (reckoning.headingConfirmed && end.x != null && end.y != null && end.heading?.isFinite() == true)
                NavHeadingMemory(reckoning.headingOffset, reckoning.headingSupportM, SystemClock.elapsedRealtime(), Point(end.x, end.y), end.heading) else null
            finishNav("PASS",(if (config.pointDestination) tr("Point reached, blades OFF · %.2f m observed", "Point atteint, lames OFF · %.2f m observés")
                else if (config.tonte) tr("Cutting test finished · %.2f m observed · blade stop requested", "Essai de coupe terminé · %.2f m observés · arrêt des lames demandé")
                else tr("Map route finished · %.2f m observed", "Parcours sur carte terminé · %.2f m observés")).format(state.value.navTest.observedMeters))
        } catch (abort: NavAbort) { finishNav("ABORTED",abort.motif) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { finishNav("FAILED",error.message ?: tr("Map navigation error", "Erreur navigation carte")) }
    }

    /**
     * Move to a point tracked by the gateway: route, physical surface and obstacles sent once, tracked by
     * [fr.luba.poc.autonomy.LegRunner] from point [fromPoint]. Returns the number of edges (route finished), or null if
     * the tracking is too large for one frame (tracked from here). Throws [ObstacleEncounter] or a safety-stop error.
     */
    private suspend fun followNavRemote(plan: MapNavPlan, fromPoint: Int, onPoint: (Int) -> Unit): Int? {
        val link = gatt as? fr.luba.poc.gateway.GatewayLink ?: error(tr("No gateway link", "Liaison passerelle absente"))
        val path = plan.points.zipWithNext().map { (a, b) -> SimEdge(a, b, false) }
        val routes = requireNotNull(plan.routes) { tr("Plan without a physical surface", "Plan sans surface physique") }
        val planned = navPlanObstacles.map { it.id }.toSet()
        var attempt = 0
        while (true) {
            attempt++
            try {
                val routeId = legs.ensureRoute(path)
                val guardId = legs.ensureGuard(routes.physicalSurface)
                val spec = fr.luba.poc.autonomy.LegSpec(legs.nextLegId(), routeId, fromPoint - 1, cutting = false, shown = true,
                    watchBattery = false, departure = false, lastArrivalM = null, heightMm = state.value.cutHeights.clamp(navConfig.heightMm),
                    speedPercent = state.value.trajectorySpeedPercent.coerceIn(10, 100), returnPercent = RouteRecharge.RESERVE_PERCENT.coerceIn(10, 80),
                    baseApproach = null, guardId = guardId, physicalGuardId = guardId, calibration = savedCalibration(),
                    obstacles = state.value.obstacles.map { o -> fr.luba.poc.autonomy.LegObstacle(o.id, o.x, o.y, o.radiusM, o.exclusionRadiusM, o.id in planned) },
                    sampling = false, slideField = slideFor(path),
                    offMapSections = if (navConfig.pointDestination && !navConfig.tonte) fr.luba.poc.autonomy.LegSpec.boundedSections(plan.offMap) else emptyList()).let { full ->
                    if (full.slideField != null && full.encode().size > fr.luba.poc.autonomy.LegFrames.MAX_PAYLOAD) full.copy(slideField = null) else full }
                if (spec.encode().size > fr.luba.poc.autonomy.LegFrames.MAX_PAYLOAD) return null
                update { it.copy(navTest = it.navTest.copy(status = tr("Drive tracked by the gateway · point $fromPoint/${plan.points.size-1}", "Déplacement suivi par la passerelle · point $fromPoint/${plan.points.size-1}"))) }
                return runRemoteLeg(link, spec, path, obstacles = { state.value.obstacles }, planned = { planned }, originOf = { -1 },
                    navProgress = { progress ->
                        val target = progress.index.coerceIn(1, plan.points.size - 1)
                        onPoint(target)
                        update { it.copy(navTest = it.navTest.copy(segmentIndex = target,
                            status = tr("${plan.labels[target]} · point $target/${plan.points.size-1} · gateway", "${plan.labels[target]} · point $target/${plan.points.size-1} · passerelle") +
                                if (progress.status.contains("Wi-Fi")) tr(" · Wi-Fi down, the robot continues on its own", " · Wi-Fi coupé, le robot poursuit seul") else "")) }
                    })
            } catch (refused: fr.luba.poc.autonomy.RemoteLegs.RefusedLeg) {
                if (attempt >= 2) throw refused
                event("gateway_leg_resend", tr("Drive refused (${refused.message}): route sent again", "Déplacement refusé (${refused.message}) : trajet renvoyé"))
            }
        }
    }

    /** Bumper contact: error history polled, manual mode (blades running or not) kept alive. */
    private fun navKeepAlive(blades: Boolean, config: NavConfig) {
        send(commands.errorQuery(), "NAV_ERROR_QUERY", periodic = true)
        // Recorded outline: no manual mode keep-alive, it would end the recording.
        if (config.tracing) return
        if (blades) send(commands.manualBlades(true,config.heightMm), "NAV_BLADES_KEEPALIVE", periodic = true)
        else send(commands.manualMow(1,0,config.heightMm), "NAV_BUMPER_KEEPALIVE_OFF", periodic = true)
    }

    /** Cutting test: height reported by the robot before any blade rotation.
     *  Called in manual mode with blades OFF ([enterNavBumperMode] has already sent the height). */
    private suspend fun navSetHeight(mm: Int) {
        val began = SystemClock.elapsedRealtime()
        val at = System.currentTimeMillis()
        val deadline = began + HeightSequence.timeoutMs(state.value.telemetry.bladeHeightMm, mm)
        update { it.copy(navTest = it.navTest.copy(status = tr("Setting the cutting height to $mm mm", "Réglage de la hauteur de coupe à $mm mm"))) }
        send(commands.bladeHeight(mm), "NAV_HEIGHT_$mm")
        while (!HeightSequence.reached(state.value.telemetry, mm, at)) {
            if (!state.value.ready || closing || !log.healthy) throw NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
            if (SystemClock.elapsedRealtime() >= deadline) throw NavAbort(tr("Height $mm mm not reported by the robot", "Hauteur $mm mm non rapportée par le robot"))
            delay(200)
        }
        recordNavSegment(0, tr("Height $mm mm", "Hauteur $mm mm"), "PASS", SystemClock.elapsedRealtime()-began, tr("Height reported before cutting", "Hauteur rapportée avant la coupe"))
    }

    /** Start of a cutting-test range: STOP then the blade-state command, confirmed afterwards in the loop. */
    private fun startNavBladeRange(point: Int, blades: Boolean, config: NavConfig, reckoning: DeadReckoning): RouteBladeGate {
        purgeQueuedCommands(); send(commands.stop(), "NAV_BLADE_RANGE_STOP")
        reckoning.sent(SystemClock.elapsedRealtime(), 0, 0)
        val gate = RouteBladeGate.unique("NAV_BLADES_${point}_${if (blades) "ON" else "OFF"}", blades)
        routeBladeGate = gate
        if (blades) bladeMayBeOn = true
        send(if (blades) commands.manualBlades(true, config.heightMm) else commands.manualMow(1, 0, config.heightMm), gate.label)
        update { it.copy(navTest = it.navTest.copy(bladesOn = blades)) }
        event("nav_blade_range", if (blades) tr("Range with blades running", "Plage lames en marche") else tr("Range with blades stopped", "Plage lames arrêtées"), mapOf("testId" to navTestId, "point" to point, "blades" to blades))
        return gate
    }

    /** Manual mode with blades OFF (state 20): the only mode in which the LUBA 2 publishes bumper contact. */
    private suspend fun enterNavBumperMode(config: NavConfig) {
        purgeQueuedCommands(); send(commands.stop(), "NAV_BUMPER_MODE_STOP")
        val gate = RouteBladeGate.unique("NAV_BUMPER_MANUAL_OFF", false)
        routeBladeGate = gate
        send(commands.manualMow(1,0,config.heightMm), gate.label)
        navBumperMode = true
        update { it.copy(navTest = it.navTest.copy(status = tr("Enabling bumper detection (manual mode, blades OFF)", "Activation de la détection bumper (mode manuel, lames OFF)"))) }
        val deadline = SystemClock.elapsedRealtime()+5000
        while (state.value.telemetry.robotState != 20 || !gate.confirmed(devicePrevious, ((state.value.telemetry.sensorStatus ?: -1) shr 9) and 7)) {
            if (!state.value.ready || closing || !log.healthy) throw NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
            if (SystemClock.elapsedRealtime() >= deadline) throw NavAbort(tr("Bumper detection inactive: manual mode with blades OFF not confirmed within 5 s", "Détection bumper inactive : mode manuel lames OFF non confirmé en 5 s"))
            delay(200)
        }
        event("nav_bumper_mode", tr("Manual mode with blades OFF confirmed: bumper contact monitored", "Mode manuel lames OFF confirmé : contact bumper surveillé"), mapOf("testId" to navTestId))
    }

    /**
     * Recorded outline: the robot must be in the recording state of [NavConfig.drawState] (it was put there by
     * [MapSyncLink.startRecording]); bumper contacts stay reported through the error history.
     */
    private suspend fun confirmDrawState(config: NavConfig) {
        purgeQueuedCommands(); send(commands.stop(), "NAV_TRACE_STOP")
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (state.value.telemetry.robotState != config.drawState) {
            if (!state.value.ready || closing || !log.healthy) throw NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
            if (SystemClock.elapsedRealtime() >= deadline) throw NavAbort(tr("Robot no longer recording (state ${state.value.telemetry.robotState}, ${config.drawState} expected)",
                "Robot plus en enregistrement (état ${state.value.telemetry.robotState}, ${config.drawState} attendu)"))
            delay(200)
        }
        event("nav_trace_mode", tr("Recording state confirmed: outline driven without manual mode", "État d'enregistrement confirmé : contour parcouru sans mode manuel"),
            mapOf("testId" to navTestId, "robotState" to config.drawState))
    }

    /** Continuous check of the drive; any anomaly throws NavAbort and stops all movement. */
    private fun navTick(config: NavConfig, zone: List<Point>?) {
        if (!log.healthy || !state.value.ready || closing) throw NavAbort(tr("BLE / log unavailable", "BLE / journal indisponible"))
        val t = state.value.telemetry
        NavSafety.automaticPositionFailure(if (positionPrevious == 0L) null else SystemClock.elapsedRealtime() - positionPrevious, t.x, t.y)?.let { throw NavAbort(it) }
        if (t.x != null && t.y != null && state.value.robotMap.shapes.any { it.excludes && Geometry.inside(Point(t.x, t.y), it.points) }) throw NavAbort(tr("Entered a no-go zone", "Entrée dans une zone interdite"))
        // Move to a point beyond the mapped zones: 25 published there is not a contact ([offMapLatchIgnored]).
        if (t.robotState !in (config.drawState?.let { listOf(it) } ?: listOf(11, 20)) || !(fr.luba.poc.protocol.SelfCheck.nominal(t.selfCheck) ||
                (config.pointDestination && !config.tonte && offMapLatchIgnored(t)))) throw NavAbort(tr("Robot state / protection changed", "État / protection robot changé"))
        val failure = NavSafety.tickFailure(
            if (devicePrevious == 0L) null else SystemClock.elapsedRealtime() - devicePrevious,
            t.battery, guardSensors(t.sensorStatus), config.tonte, config.batteryFloor,
            t.x, t.y, navStart, config.maxRadiusM, zone, NavSafety.ZONE_MARGIN_M)
        if (failure != null) throw NavAbort(failure)
    }

    private fun recordNavSegment(index: Int, title: String, outcome: String, durationMs: Long, detail: String) {
        val result = TestResult("${navTestId}_$index", title, outcome, durationMs, detail)
        update { it.copy(navTest = it.navTest.copy(results = it.navTest.results + result)) }
        event("nav_segment_result", detail, resultFields(result))
    }

    private fun finishNav(outcome: String, detail: String) {
        if (navTestId.isBlank()) return
        if (outcome in setOf("ABORTED", "FAILED")) {
            alertMotionFailure(detail)
            alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, detail)
            if(detail.contains(tr("resume", "reprise"),ignoreCase=true)) alert(AlertKind.RESUME_FAILED, Severity.CRITICAL, detail)
        }
        val elapsed = SystemClock.elapsedRealtime() - navStarted
        navStopAndCleanup(detail)
        flushSurveys()
        val course = navCourse
        val courseGap = course?.let { o -> ObstacleCourse.observedSurfaceGap(state.value.navTest.trail.map { Point(it.x, it.y) }, o) }
        if (course != null) recordNavSegment(state.value.navTest.segmentCount, tr("Distance to the obstacle", "Distance à l'obstacle"),
            if (ObstacleCourse.passed(courseGap)) "PASS" else "FAILED", elapsed,
            courseGap?.let { tr("Minimum observed gap %.2f m between the robot reference point and the surface (robot flank: %.2f m)", "Écart minimal observé %.2f m entre le repère robot et la surface (flanc du robot : %.2f m)").format(it, RobotEnvelope.LUBA2.halfWidthM) } ?: tr("No position observed", "Aucune position observée"))
        val courseOutcome = if (course != null && outcome == "PASS" && !ObstacleCourse.passed(courseGap)) "FAILED" else outcome
        clearNavCourse()
        val result = TestResult(navTestId,
            if (course != null) tr("Obstacle bypass", "Contournement d'obstacle") else if (navConfig.tonte) tr("Cutting test while moving", "Essai de coupe en déplacement") else tr("Light map route", "Parcours léger sur carte"),
            courseOutcome, elapsed, if (course != null && outcome == "PASS") tr("Round trip finished · ${courseGap?.let { tr("minimum gap %.2f m", "écart minimal %.2f m").format(it) } ?: tr("gap unknown", "écart inconnu")}", "Aller-retour terminé · ${courseGap?.let { "écart minimal %.2f m".format(it) } ?: "écart inconnu"}") else detail)
        update { it.copy(navTest = it.navTest.copy(running = false, outcome = result.outcome, status = result.detail,
            interruption = if (result.outcome == "PASS") null else result.detail, results = it.navTest.results + result)) }
        persistNav(result.outcome, result.detail)
        pointNavigationEnded(result.outcome, result.detail)
        event("nav_test_result", detail, resultFields(result) + mapOf(
            "observedMeters" to state.value.navTest.observedMeters, "tonte" to navConfig.tonte))
        navJob = null; navTestId = ""
    }

    /** Abandonment recorded without any BLE write: STOP/BLADES_OFF is already handled by the caller. */
    private fun clearNavCourse() { navCourse = null; navCourseFar = null; navCourseHome = null }

    private fun abandonNav(reason: String, userInitiated: Boolean = false) {
        // Drive tracked by the gateway: stop it before the STOP written from here (otherwise held until the tracking ends).
        if (navJob != null) releaseRemoteLeg()
        navJob?.cancel(); navJob = null
        clearNavCourse()
        if (navTestId.isBlank()) return
        val elapsed = SystemClock.elapsedRealtime() - navStarted
        val result = TestResult(navTestId, tr("Light map route", "Parcours léger sur carte"), "ABORTED", elapsed, reason)
        update { it.copy(navTest = it.navTest.copy(running = false, outcome = "ABORTED", status = reason,
            interruption = if (userInitiated) null else reason, results = it.navTest.results + result)) }
        persistNav("ABORTED", reason)
        pointNavigationEnded("ABORTED", reason)
        event("nav_test_result", reason, resultFields(result))
        navTestId = ""
    }

    /** End-of-drive STOP; if the blades were in use, the supervised OFF path is taken again. */
    private fun navStopAndCleanup(reason: String) {
        confirmedHeading = null
        update { it.copy(navTest = it.navTest.copy(bladesOn = false)) }
        // Blades possibly running: supervised OFF path (OFF, STOP, wait for a fresh OFF state).
        if (bladeMayBeOn) { navBumperMode = false; stopBladeTest(reason); return }
        purgeQueuedCommands()
        send(commands.stop(), "NAV_STOP linear=0 angular=0")
        if (navBumperMode) { send(commands.manualBlades(false, navConfig.heightMm), "NAV_EXIT_MANUAL_OFF"); navBumperMode = false }
    }

    private fun persistNav(outcome: String, detail: String) {
        if (navTestId.isBlank()) return
        log.report(navTestId, mapOf("id" to navTestId, "kind" to "map_navigation", "outcome" to outcome,
            "step" to tr("Light map route", "Parcours léger sur carte"), "detail" to detail,
            "elapsedMs" to SystemClock.elapsedRealtime() - navStarted,
            "tonte" to navConfig.tonte, "heightMm" to navConfig.heightMm, "automatic" to navConfig.automatic,
            "initialLinearSpeedRaw" to navConfig.linearSpeed, "linearSpeedRaw" to PathFollower.linearFor(state.value.trajectorySpeedPercent), "angularSpeedRaw" to navConfig.angularSpeed,
            "maxDurationMs" to navConfig.maxDurationMs, "maxRadiusM" to navConfig.maxRadiusM,
            "batteryFloor" to navConfig.batteryFloor,
            "observedMeters" to state.value.navTest.observedMeters, "stopDelayMs" to state.value.navTest.stopDelayMs,
            "startX" to navStart?.x, "startY" to navStart?.y,
            "planned" to state.value.navTest.planned.map { mapOf("x" to it.x, "y" to it.y) },
            "trail" to state.value.navTest.trail.map { sample ->
                mapOf("xMeters" to sample.x, "yMeters" to sample.y, "headingDegrees" to sample.heading, "elapsedMs" to sample.elapsedMs) },
            "results" to state.value.navTest.results.map(::resultFields),
            "robot" to state.value.selected?.name, "firmware" to state.value.telemetry.firmware,
            "batteryPercent" to state.value.telemetry.battery))
    }

    fun recordMowingCheck(key: String, checked: Boolean) {
        if (key !in setOf("start", "turns", "dock", "restart")) return
        update { it.copy(mowing = it.mowing.copy(manualChecks = if (checked) it.mowing.manualChecks + key else it.mowing.manualChecks - key)) }
        persistMowing()
    }

    private fun receiveMowing(message: LubaMsg) {
        mowPathTransfer?.let { transfer ->
            try {
                transfer.accept(message, commands).forEach { send(it, "MOW_PATH_READ") }
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
                requestStop(true, reason)
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
        mowPathTransfer = MowPathTransfer(System.currentTimeMillis())
        mowPathDeadline = SystemClock.elapsedRealtime() + 60_000
        update { it.copy(mowing = it.mowing.copy(pathStatus = tr("Reading the firmware route…", "Lecture du parcours firmware…"))) }
        requireNotNull(mowPathTransfer).start(commands).forEach { send(it, "MOW_PATH_READ") }
        persistMowing()
    }

    private fun observeMowing() {
        if (!state.value.mowing.running) return
        val t = state.value.telemetry
        if ((t.lastDeviceAt ?: 0L) <= mowCommandAt) return
        val positionFresh = t.lastPositionAt?.let { System.currentTimeMillis() - it in 0..2000 } == true
        val distance = if (positionFresh && t.x != null && t.y != null) mowOrigin?.distance(Point(t.x, t.y)) else null
        val before = state.value.mowing
        val after = MowObservation.accept(before, t, distance, t.lastDeviceAt!!)
        update { it.copy(mowing = after) }
        if (after.phase != before.phase && after.phase in setOf(MowPhase.WORKING, MowPhase.PAUSED, MowPhase.RETURNING, MowPhase.DOCKED, MowPhase.UNDOCKED)) mowDeadline = 0
        if (after.phase == MowPhase.DOCKED && before.phase != MowPhase.DOCKED && positionFresh && t.x != null && t.y != null) {
            mowOrigin = Point(t.x, t.y)
        }
        if (after != before) persistMowing()
    }

    private fun monitorMowing() {
        if (mowJob?.isActive == true) return
        mowJob = viewModelScope.launch {
            while (state.value.mowing.active) {
                delay(250)
                if (mowPathTransfer != null && SystemClock.elapsedRealtime() > mowPathDeadline) {
                    mowPathTransfer = null
                    update { it.copy(mowing = it.mowing.copy(pathStatus = tr("Route reading timed out — route not confirmed", "Lecture du parcours expirée — trajet non confirmé"))) }
                }
                val t = state.value.telemetry
                val failure = MowSafety.failure(state.value.selected?.name, state.value.ready && !closing && log.healthy,
                    false, t.copy(sensorStatus = guardSensors(t.sensorStatus)), System.currentTimeMillis())
                if (failure != null) { fail(tr("Mowing: $failure", "Tonte : $failure")); break }
                if (mowDeadline != 0L && SystemClock.elapsedRealtime() > mowDeadline) {
                    fail(tr("Mowing: transition not confirmed in time", "Tonte : transition non confirmée dans le délai")); break
                }
            }
        }
    }

    fun finishMowing() { intentionalAlertStop(); requestStop(true, tr("End of mowing supervision", "Fin de supervision de tonte"), intentional=true) }

    private fun interruptMowing(reason: String) {
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

    /**
     * Saves the complete list of local map corrections (addition, modification, deactivation, deletion) and
     * republishes the corrected map. Refused during a local route: the route's map does not change.
     */
    fun saveMapCorrections(corrections: List<MapCorrection>): Boolean {
        if (state.value.robotRoute.running) { status(tr("Corrections: stop the current route first", "Corrections : arrêter d'abord le trajet en cours")); return false }
        val before = state.value.mapCorrections
        return try {
            correctionStore.save(corrections)
            update { it.withMammotionMap(it.mammotionMap, corrections) }
            event("map_corrections_saved", tr("Local map corrections saved", "Corrections locales de la carte enregistrées"), mapOf("count" to corrections.size,
                "active" to corrections.count { it.enabled }, "previous" to before.size))
            true
        } catch (e: Exception) {
            status(tr("Corrections: could not be saved", "Corrections : enregistrement impossible"))
            event("map_corrections_error", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** The next recorded point follows a pause: straight join. */
    private var drivenJoin = false

    /**
     * Starts recording a zone, a passage or a no-go zone by driving the robot (RTK FIX positions of its centre, 200 ms
     * reports). Nothing is sent to the robot apart from the report period: the result is a local correction.
     */
    fun startDrivenRecording(kind: DrivenKind): Boolean {
        val ui = state.value
        val refusal = when {
            !ui.ready -> tr("Robot not connected", "Robot non connecté")
            !ui.mammotionMap.complete || ui.mammotionMap.source.contains("DÉMONSTRATION", true) -> tr("Read the robot map first", "Lisez d'abord la carte du robot")
            ui.robotRoute.running || ui.mowing.active -> tr("Stop the current route first", "Arrêtez d'abord le trajet en cours")
            ui.driven != null -> tr("A recording is already in progress", "Un enregistrement est déjà en cours")
            else -> null
        }
        if (refusal != null) { status(tr("Recording refused: $refusal", "Enregistrement refusé : $refusal")); return false }
        drivenJoin = false
        update { it.copy(driven = DrivenTrace(kind, System.currentTimeMillis())) }
        event("driven_record_start", tr("Recording by driving", "Enregistrement par pilotage"), mapOf("kind" to kind.name))
        if (state.value.ready && !closing) send(reportsCommand(false, periodMs = 200), "DRIVEN_REPORTS")
        return true
    }

    /** Pause (the robot is moved without recording) or resumption; the next point is joined in a straight line. */
    fun pauseDrivenRecording(paused: Boolean) {
        val trace = state.value.driven ?: return
        if (trace.paused == paused) return
        if (!paused) drivenJoin = trace.points.isNotEmpty()
        update { current -> current.driven?.let { current.copy(driven = it.copy(paused = paused)) } ?: current }
        event("driven_record_pause", if (paused) tr("Recording paused", "Enregistrement en pause") else tr("Recording resumed", "Enregistrement repris"),
            mapOf("points" to trace.points.size))
        if (state.value.ready && !closing) send(reportsCommand(false, periodMs = reportPeriodMs()), "DRIVEN_REPORTS")
    }

    /** Removes the last meter of the trace. */
    fun eraseDrivenRecording(meters: Double = 1.0) {
        update { current -> current.driven?.let { current.copy(driven = DrivenRecording.eraseBack(it, meters)) } ?: current }
    }

    /** Result of the current recording on the map shown (corrected), without ending it. */
    fun drivenOutcome(): DrivenOutcome? = state.value.driven?.let { DrivenRecording.finish(it, state.value.robotMap) }

    /** Ends the recording without saving anything. */
    fun cancelDrivenRecording(reason: String = tr("Recording cancelled", "Enregistrement annulé")) {
        val trace = state.value.driven ?: return
        update { it.copy(driven = null) }
        drivenJoin = false
        event("driven_record_cancel", reason, mapOf("kind" to trace.kind.name, "points" to trace.points.size))
        if (state.value.ready && !closing) send(reportsCommand(false, periodMs = reportPeriodMs()), "DRIVEN_REPORTS")
    }

    // ---------------------------------------------------------------- sync with the Mammotion map

    /**
     * Sync of the local corrections into the Mammotion map held by the robot ([fr.luba.poc.mapsync]): the robot drives
     * each outline on its own, blades stopped, while it records it. Page: Map sheet → Sync with the Mammotion app.
     */
    val mapSync: fr.luba.poc.mapsync.MapSyncRunner by lazy { fr.luba.poc.mapsync.MapSyncRunner(MapSyncLink(), viewModelScope) }

    /** Current differences between the robot map and the corrected map, traces checked (computed off the main thread). */
    suspend fun mapSyncDifferences(): List<fr.luba.poc.mapsync.SyncItem> = MapSyncLink().differences()

    /** Reason why the sync cannot start now, null if it can. */
    fun mapSyncRefusal(): String? = MapSyncLink().refusal()

    /** Last reply of the robot to a map writing command (logged, shown by the sync page). */
    private fun mapSyncReply(nav: fr.luba.poc.proto.MctrlNavProto.MctlNav) {
        if (!mapSync.state.value.running) return
        if (nav.hasToappGetCommondataAck() && mapTransfer == null) {
            val ack = nav.toappGetCommondataAck
            if (ack.action in setOf(0, 1, 6, 7)) event("map_sync_reply", tr("Robot reply to a map command", "Réponse du robot à une commande de carte"),
                mapOf("action" to ack.action, "type" to ack.type, "result" to ack.result, "hash" to ack.hash, "robotState" to state.value.telemetry.robotState))
        }
        if (nav.hasToappBstate()) event("map_sync_border_state", tr("Recording state reported by the robot", "État d'enregistrement signalé par le robot"),
            mapOf("bdstate" to nav.toappBstate.bdstate, "robotState" to state.value.telemetry.robotState))
    }

    private inner class MapSyncLink : fr.luba.poc.mapsync.MapSyncPort {
        override fun refusal(): String? {
            val ui = state.value
            return when {
                !fr.luba.poc.protocol.ModelSupport.motionValidated(ui.selected?.name) -> tr("Writing to the robot map is reserved for a validated LUBA 2 (Luba-VS, Luba-VP)",
                    "L'écriture dans la carte du robot est réservée à un LUBA 2 validé (Luba-VS, Luba-VP)")
                gatt == null || !ui.ready || closing -> tr("Connect the robot (Bluetooth or gateway) first", "Connectez d'abord le robot (Bluetooth ou passerelle)")
                !ui.mammotionMap.complete || ui.mammotionMap.source.contains("DÉMONSTRATION", true) -> tr("Read the robot map first", "Lisez d'abord la carte du robot")
                ui.mapLoading -> tr("Wait for the end of the map reading", "Attendez la fin de la lecture de la carte")
                ui.robotRoute.running || ui.mowing.active || ui.navTest.running || navJob != null || ui.manualActive || ui.driven != null ||
                    ui.borderFrontal.running || ui.teach.running || ui.campaign.running || ui.bladeTestActive -> tr("Stop the current activity of the robot first", "Arrêtez d'abord l'activité en cours du robot")
                (ui.telemetry.battery ?: 0) <= SYNC_BATTERY_FLOOR -> tr("Battery unknown or ≤ $SYNC_BATTERY_FLOOR %: charge the robot first", "Batterie inconnue ou ≤ $SYNC_BATTERY_FLOOR % : rechargez d'abord le robot")
                else -> null
            }
        }

        override fun robotPosition(): Point? = state.value.telemetry.let { t ->
            val x = t.x; val y = t.y
            if (x != null && y != null && x.isFinite() && y.isFinite()) Point(x, y) else null
        }

        private fun base(map: RobotMap): fr.luba.poc.simulation.MapBase? = runCatching { BaseStore(getApplication(), map).resolve(map) }.getOrNull()

        private fun station(map: RobotMap): Point? = base(map)?.point ?: map.station?.point

        /** Station kept out of the outlines: its safety zone (the moves neither enter nor start there) plus a margin. */
        private fun stationClearance(map: RobotMap): Double = base(map)?.takeIf { it.entryHeadingDegrees != null }?.let {
            maxOf(fr.luba.poc.mapsync.MapSyncPlanner.STATION_CLEARANCE_M, TrajectoryPlanner.baseSafetyRadius(it.approachDistanceM) + fr.luba.poc.mapsync.MapSyncPlanner.STATION_MARGIN_M)
        } ?: fr.luba.poc.mapsync.MapSyncPlanner.STATION_CLEARANCE_M

        private fun accesses(map: RobotMap): List<List<Point>> = runCatching { BaseStore(getApplication(), map).accesses() }.getOrDefault(emptyList())

        private fun check(map: RobotMap, obstacles: List<fr.luba.poc.obstacles.LocalObstacle>, accesses: List<List<Point>>): (List<Point>) -> String? = { trace ->
            try { PointPlanner.tracePlan(map, trace, obstacles, accesses); null }
            catch (e: IllegalArgumentException) { e.message ?: e.javaClass.simpleName }
            catch (e: IllegalStateException) { e.message ?: e.javaClass.simpleName }
        }

        override suspend fun differences(): List<fr.luba.poc.mapsync.SyncItem> {
            val ui = state.value
            val station = station(ui.robotMap)
            val clearance = stationClearance(ui.robotMap)
            val accesses = accesses(ui.robotMap)
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                fr.luba.poc.mapsync.MapSyncPlanner.differences(ui.mammotionMap, ui.correctedMap, ui.mapCorrections, station = station,
                    check = check(ui.robotMap, ui.obstacles, accesses), stationClearanceM = clearance)
            }
        }

        override fun recreate(shape: MapShape): fr.luba.poc.mapsync.SyncItem? {
            val ui = state.value
            return fr.luba.poc.mapsync.MapSyncPlanner.recreate(shape, station = station(ui.robotMap), check = check(ui.robotMap, ui.obstacles, accesses(ui.robotMap)),
                stationClearanceM = stationClearance(ui.robotMap))
        }

        private fun config(drawState: Int?) = NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent)
            .let { if (drawState != null) minOf(it, fr.luba.poc.mapsync.MapSyncPlanner.TRACE_LINEAR) else it },
            angularSpeed = PathFollower.angularLimit(state.value.trajectorySpeedPercent), automatic = true, maxDurationMs = 600_000, maxRadiusM = 200.0,
            batteryFloor = SYNC_BATTERY_FLOOR - 5, drawState = drawState, keepHeading = true)

        /**
         * Starts a drive with [start] and waits for its end: null if it reached its end, otherwise its refusal or stop
         * reason (a refusal without its own drive status is read from the general status line).
         */
        /** The last [drive] really started (false: refused before any movement). */
        private var started = false

        private suspend fun drive(progress: ((Double) -> Unit)?, start: () -> Unit): String? {
            // A move only starts from READY (or the recording state for a recorded outline): after the previous drive the
            // robot leaves its manual mode within about a second.
            // Robot on its base: charging states accepted like a move to a point (it leaves the station in a straight line).
            val stateOk: (Int?) -> Boolean = { it == 11 || it in NavConfig.DRAW_STATES ||
                (state.value.telemetry.chargeState in setOf(1, 2) && it in setOf(15, 39)) }
            if (!waitState(6000, stateOk)) return tr("robot not ready (state ${state.value.telemetry.robotState})", "robot non prêt (état ${state.value.telemetry.robotState})")
            val navBefore = state.value.navTest.status
            val statusBefore = state.value.status
            start()
            started = state.value.navTest.running
            if (!started) return when {
                state.value.navTest.status != navBefore -> state.value.navTest.status
                state.value.status != statusBefore -> state.value.status
                else -> tr("drive refused", "déplacement refusé")
            }
            while (state.value.navTest.running || navJob != null) {
                val nav = state.value.navTest
                if (progress != null && nav.segmentCount > 0) progress(nav.segmentIndex.toDouble() / nav.segmentCount)
                delay(250)
            }
            val nav = state.value.navTest
            return if (nav.outcome == "PASS") null else nav.status
        }

        /** Waits until [ok] accepts the robot state; false after [timeoutMs] or if the link drops. */
        private suspend fun waitState(timeoutMs: Long, ok: (Int?) -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!ok(state.value.telemetry.robotState)) {
                if (!state.value.ready || closing || SystemClock.elapsedRealtime() >= deadline) return false
                delay(200)
            }
            return true
        }

        override suspend fun driveTo(targets: List<Point>): String? {
            var refusal: String? = null
            for (target in targets) {
                val reason = drive(null) { startMapNavigation(config(null), target) } ?: return null
                // Interrupted after it started: no other attempt. Refused before moving: next point.
                if (started) return reason
                event("map_sync_approach_refused", reason, mapOf("x" to target.x, "y" to target.y))
                refusal = reason
            }
            return refusal ?: tr("no point to drive to", "aucun point à rejoindre")
        }

        override suspend fun startRecording(kind: fr.luba.poc.mapsync.SyncKind): String? {
            // After a move the manual mode is left (blades OFF): wait for READY, insist once if it lingers.
            if (!waitState(3000) { it == 11 } && state.value.telemetry.robotState == 20 && state.value.ready && !closing)
                send(commands.manualBlades(false, navConfig.heightMm), "MAP_SYNC_EXIT_MANUAL")
            if (!waitState(4000) { it == 11 }) return tr("robot not ready (state ${state.value.telemetry.robotState}, 11 expected)",
                "robot non prêt (état ${state.value.telemetry.robotState}, 11 attendu)")
            send(commands.startMapRecording(kind.type), "MAP_SYNC_RECORD_START type=${kind.type}")
            event("map_sync_record_start", tr("Recording requested from the robot", "Enregistrement demandé au robot"), mapOf("type" to kind.type, "expectedState" to kind.drawState))
            if (!waitState(10_000) { it == kind.drawState }) return tr("robot state ${state.value.telemetry.robotState} after 10 s, ${kind.drawState} expected",
                "état du robot ${state.value.telemetry.robotState} après 10 s, ${kind.drawState} attendu")
            return null
        }

        override fun firstDrivable(paths: List<List<Point>>): Int? {
            val ui = state.value
            val routes = try { PointPlanner.traceRoutes(ui.robotMap, ui.obstacles, accesses(ui.robotMap)) }
                catch (e: IllegalArgumentException) { event("map_sync_join_refused", e.message ?: "routes"); return null }
            return paths.indexOfFirst { path -> runCatching { routes.requirePath(path) }.isSuccess }.takeIf { it >= 0 }
        }

        override suspend fun driveExact(path: List<Point>): String? {
            val ui = state.value
            val plan = try { PointPlanner.tracePlan(ui.robotMap, path, ui.obstacles, accesses(ui.robotMap)) }
                catch (e: IllegalArgumentException) { return e.message ?: e.javaClass.simpleName }
                catch (e: IllegalStateException) { return e.message ?: e.javaClass.simpleName }
            // Straight join ending on the outline (half a chassis + 15 cm from the element): faced in place first, an arc
            // under the 25° pivot threshold swung the chassis off the line towards the element (envelope refused).
            return drive(null) { startMapNavigation(config(null), preset = plan.copy(alignAtStart = true)) }
        }

        override suspend fun driveTrace(trace: List<Point>, kind: fr.luba.poc.mapsync.SyncKind, progress: (Double) -> Unit): String? {
            val here = robotPosition() ?: return tr("robot position unknown", "position du robot inconnue")
            val ui = state.value
            // Joined beforehand: the robot stands on the start (arrival tolerance); a short link is only added beyond it.
            val path = if (here.distance(trace.first()) > 0.3) listOf(here) + trace else trace
            val plan = try { PointPlanner.tracePlan(ui.robotMap, path, ui.obstacles, accesses(ui.robotMap)) }
                catch (e: IllegalArgumentException) { return e.message ?: e.javaClass.simpleName }
                catch (e: IllegalStateException) { return e.message ?: e.javaClass.simpleName }
            // Faced along the first edge in place first: the join leaves the robot at up to 25° from the outline, and the
            // arc that corrected it while recording took the chassis 15 cm towards the element (footprint or corridor
            // check, outline abandoned): 53 failures out of 300 draws of the bench before, 35 after ([MapSyncSweepTest]; 1 with
            // the heading kept from the join, [NavHeadingMemory]).
            // A pivot on the spot adds no recorded point.
            return drive(progress) { startMapNavigation(config(kind.drawState), preset = plan.copy(alignAtStart = true)) }
        }

        override suspend fun endRecording(kind: fr.luba.poc.mapsync.SyncKind): String? {
            send(commands.endMapRecording(kind.type), "MAP_SYNC_RECORD_END type=${kind.type}")
            event("map_sync_record_end", tr("End of recording requested from the robot", "Fin d'enregistrement demandée au robot"), mapOf("type" to kind.type))
            return if (waitState(20_000) { it != kind.drawState }) null
                else tr("robot still recording after 20 s (state ${state.value.telemetry.robotState})", "robot toujours en enregistrement après 20 s (état ${state.value.telemetry.robotState})")
        }

        override suspend fun saveRecording(): String? {
            if (!state.value.ready || closing) return tr("robot disconnected", "robot déconnecté")
            send(commands.saveMapTask(), "MAP_SYNC_SAVE")
            event("map_sync_save_sent", tr("Explicit save of the mapping requested", "Sauvegarde explicite de la cartographie demandée"))
            delay(2000)
            return null
        }

        override fun cancelRecording() {
            if (!state.value.ready || closing) return
            runCatching { send(commands.cancelMapRecording(), "MAP_SYNC_RECORD_CANCEL") }
            event("map_sync_record_cancel", tr("Recording abandoned in the robot", "Enregistrement abandonné dans le robot"))
        }

        override suspend fun deleteElement(kind: fr.luba.poc.mapsync.SyncKind, hash: Long): String? {
            if (!state.value.ready || closing) return tr("robot disconnected", "robot déconnecté")
            send(commands.deleteMapElement(kind.type, hash), "MAP_SYNC_DELETE type=${kind.type}")
            event("map_sync_delete", tr("Deletion of a map element requested", "Suppression d'un élément de carte demandée"), mapOf("type" to kind.type, "hash" to hash))
            delay(2000)
            return null
        }

        override suspend fun readMap(): RobotMap? {
            delay(1500)
            mapReadRefusal()?.let { event("map_sync_read_refused", it); return null }
            fetchMap()
            val deadline = SystemClock.elapsedRealtime() + MAP_READ_MAX_MS + 15_000
            delay(300)
            while (state.value.mapLoading && SystemClock.elapsedRealtime() < deadline) delay(300)
            val map = state.value.mammotionMap
            return map.takeIf { !state.value.mapLoading && it.complete && !it.source.contains("DÉMONSTRATION", true) }
        }

        override fun corrections(): List<MapCorrection> = state.value.mapCorrections

        override fun saveCorrections(corrections: List<MapCorrection>): Boolean = saveMapCorrections(corrections)

        override fun stopMotion(reason: String) {
            if (state.value.navTest.running || navJob != null) stopNavTest(reason, userInitiated = true)
            else if (state.value.ready && !closing) { purgeQueuedCommands(); send(commands.stop(), "MAP_SYNC_STOP") }
        }

        override fun log(kind: String, text: String, fields: Map<String, Any?>) = event(kind, text, fields)
    }

    /** Saves the recorded shape as a local correction and ends the recording. */
    fun saveDrivenRecording(correction: MapCorrection): Boolean {
        val trace = state.value.driven ?: return false
        if (!saveMapCorrections(state.value.mapCorrections + correction)) return false
        update { it.copy(driven = null) }
        drivenJoin = false
        event("driven_record_saved", correction.label, mapOf("kind" to trace.kind.name, "points" to trace.points.size,
            "lengthM" to trace.lengthM, "correction" to correction.javaClass.simpleName))
        if (state.value.ready && !closing) send(reportsCommand(false, periodMs = reportPeriodMs()), "DRIVEN_REPORTS")
        return true
    }

    /** Local saving of an obstacle: private app file, no Mammotion no-go zone created. */
    fun saveObstacle(obstacle: LocalObstacle) {
        val kept = (state.value.obstacles.filter { it.id != obstacle.id } + obstacle).sortedBy { it.id }
        obstacleStore.save(kept)
        update { it.copy(obstacles = kept) }
        publishDifficulties(state.value.difficulties.events, persist = false)
        event("obstacle_saved", tr("Local obstacle saved", "Obstacle local enregistré"), mapOf("id" to obstacle.id,
            "kind" to obstacle.kind.name, "x" to obstacle.x, "y" to obstacle.y,
            "radiusM" to obstacle.radiusM, "marginM" to obstacle.marginM, "incidents" to obstacle.incidents.size))
    }

    /**
     * Deletes a saved obstacle and dismisses the bumper contacts it hid ([fr.luba.poc.difficulty.PresumedObstacles.hiddenContacts]):
     * otherwise the same obstacle came back at once as a presumed obstacle, still avoided and impossible to delete.
     */
    fun deleteObstacle(id: String) {
        val removed = state.value.obstacles.firstOrNull { it.id == id }
        val kept = state.value.obstacles.filter { it.id != id }
        val events = state.value.difficulties.events
        val hidden = removed?.let { fr.luba.poc.difficulty.PresumedObstacles.hiddenContacts(events, it, kept, System.currentTimeMillis()) }.orEmpty()
            .map { it.id }.toSet()
        obstacleStore.save(kept)
        update { it.copy(obstacles = kept) }
        if (hidden.isEmpty()) publishDifficulties(events, persist = false)
        else publishDifficulties(events.map { if (it.id in hidden) it.copy(dismissed = true) else it }, persist = true)
        event("obstacle_deleted", tr("Local obstacle deleted", "Obstacle local supprimé"), mapOf("id" to id, "contactsDismissed" to hidden.size))
    }

    /** Presumed obstacle dismissed (object removed, false contacts): all its contacts are dismissed at once. */
    fun dismissPresumedObstacle(id: String) {
        val events = state.value.difficulties.events
        val contacts = fr.luba.poc.difficulty.PresumedObstacles.contactsOf(events, id, System.currentTimeMillis()).map { it.id }.toSet()
        if (contacts.isEmpty()) return
        publishDifficulties(events.map { if (it.id in contacts) it.copy(dismissed = true) else it }, persist = true)
        event("presumed_obstacle_dismissed", tr("Presumed obstacle dismissed", "Obstacle présumé écarté"), mapOf("id" to id, "contacts" to contacts.size))
    }

    fun addObstacleIncident(id: String, incident: ObstacleIncident) {
        val target = state.value.obstacles.firstOrNull { it.id == id } ?: return
        saveObstacle(target.withIncident(incident))
        event("obstacle_incident", tr("Incident linked to an obstacle", "Incident associé à un obstacle"), mapOf("id" to id,
            "kind" to incident.kind, "note" to incident.note))
    }

    fun dismissTeachProposal() { update { it.copy(teach = it.teach.copy(proposal = null)) } }

    /**
     * Obstacle pointed at without contact (Service → Obstacle tests → Learn): proposal from the robot's fresh pose,
     * touching the bumper by default; type, size and distance are adjusted before saving. No robot command.
     */
    fun proposeObstacleAhead() {
        val t = state.value.telemetry
        val fresh = positionPrevious != 0L && SystemClock.elapsedRealtime() - positionPrevious in 0..2000
        val x = t.x; val y = t.y; val heading = t.heading
        if (!fresh || x == null || y == null || heading == null || !x.isFinite() || !y.isFinite() || !heading.isFinite()) {
            update { it.copy(teach = it.teach.copy(status = tr("Fresh position and heading required to place an obstacle", "Position et cap frais requis pour placer un obstacle"))) }
            return
        }
        val proposal = TeachSafety.pointedProposal(TeachContact(x, y, heading, System.currentTimeMillis()), ObstacleKind.OTHER.defaultRadiusM)
        update { it.copy(teach = it.teach.copy(proposal = proposal, status = tr("Obstacle placed in front of the robot: adjust it, then save", "Obstacle placé devant le robot : ajustez-le puis enregistrez"))) }
        event("obstacle_pointed", tr("Obstacle pointed at without contact", "Obstacle désigné sans contact"), mapOf("x" to proposal.estimated.x, "y" to proposal.estimated.y, "heading" to heading))
    }

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
        if (state.value.robotRoute.running) { status(tr("Finish the BLE route first", "Terminer le trajet BLE")); return }
        if (state.value.manualActive) { status(tr("Release the joystick before this action", "Relâcher le joystick avant cette action")); return }
        if (state.value.mowing.active) { status(tr("Finish the mowing mission first", "Terminer la mission de tonte")); return }
        if (state.value.mapLoading) { status(tr("Wait for the map reading to finish", "Attendre la lecture de carte")); return }
        if (state.value.teach.running || state.value.bladeTestActive || bladeMayBeOn || heightTarget != null) { status(tr("Finish the obstacle / cutting tests before the campaign", "Terminer les tests obstacle / coupe avant la campagne")); return }
        if (state.value.navTest.running) { status(tr("Finish the random drive before the campaign", "Terminer la marche aléatoire avant la campagne")); return }
        if (state.value.campaign.running || closing) return
        config = options
        campaignFailure = null; ignoreTelemetry = false; expectedFailure = null
        try { ensureHealthy() } catch (error: Exception) { status(tr("Campaign refused: ${error.message}", "Campagne refusée : ${error.message}")); return }
        val selected = state.value.selected ?: return
        runLocalChecks()
        if (state.value.localResults.any { it.outcome != "PASS LOCAL" }) { status(tr("Local tests failed; campaign refused", "Tests locaux en échec ; campagne refusée")); return }
        reportGaps = TimingStats(); positionGaps = TimingStats(); writeTimes = TimingStats()
        reportCount = 0; positionCount = 0
        logicalCommandsCompleted = 0; zeroCommandsQueued = 0; zeroCommandsCompleted = 0
        maxLoopDelayMs = 0; batteryStart = state.value.telemetry.battery; batteryMin = batteryStart
        positionTypeChanges = 0; rtkStatusChanges = 0; maxStationaryDriftM = 0.0
        driftOrigin = state.value.telemetry.let { if (it.x != null && it.y != null) it.x to it.y else null }
        campaignStart = SystemClock.elapsedRealtime()
        val id = UUID.randomUUID().toString()
        update { it.copy(campaign = CampaignUi(id, true, "RUNNING")) }
        event("campaign_start", tr("Stationary BLE campaign", "Campagne BLE immobile"), mapOf("durationMinutes" to options.durationMinutes,
            "batteryFloor" to options.batteryFloor, "zeros5Hz" to options.zeros, "plannedCycles" to options.cycles,
            "localTelemetryMask" to options.watchdog, "localException" to options.exception, "robot" to selected.name, "address" to selected.address))
        persistCampaign()
        campaignJob = viewModelScope.launch {
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
                    if (gatt != null && !closing) requestStop(true, tr("Campaign failed", "Campagne en échec"))
                }
            } catch (error: CancellationException) {
                // The user action has already persisted ABORTED and requested STOP.
                throw error
            } catch (error: Exception) {
                activeStep?.let { addResult(TestResult(it.id, it.title, "FAILED", SystemClock.elapsedRealtime() - caseStarted, error.message ?: error.javaClass.simpleName)) }
                finishCampaign("FAILED", error.message ?: tr("Diagnostic failed", "Échec diagnostic"))
                if (gatt != null && !closing) requestStop(true, tr("Campaign failed", "Campagne en échec"))
            } finally {
                ignoreTelemetry = false; expectedFailure = null; activeCase = ""; campaignJob = null
            }
        }
    }

    private suspend fun observe(seconds: Long, zero: Boolean, unlimited: Boolean = false) {
        val begin = SystemClock.elapsedRealtime()
        val reportsBefore = reportCount
        var nextZero = begin
        while (unlimited || SystemClock.elapsedRealtime() - begin < seconds * 1000L) {
            ensureHealthy()
            val now = SystemClock.elapsedRealtime()
            update { it.copy(campaign = it.campaign.copy(elapsedMs = now - campaignStart)) }
            if (zero && now >= nextZero) {
                send(commands.stop(), "DIAGNOSTIC_ZERO linear=0 angular=0")
                nextZero = now + 200 // 5 Hz, no non-zero command.
            }
            delay(50)
        }
        ensureHealthy()
        check(reportCount > reportsBefore) { tr("No new report during the case", "Aucun nouveau rapport pendant le cas") }
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
        intentionalAlertStop()
        requestStop(true, reason, intentional=true)
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
        connect(robot)
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
            dispatch(requireNotNull(gatt)) { throw IllegalStateException(tr("Deliberate local exception injection", "Injection locale exception volontaire")) }
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
        persistCampaign(); refreshHistory()
    }
    private fun abortCampaign(reason: String) {
        if (!state.value.campaign.running) return
        val running = state.value.campaign
        addResult(TestResult(activeCase, running.step, "ABORTED", 0, reason))
        finishCampaign("ABORTED", reason)
        campaignJob?.cancel(); campaignJob = null
        ignoreTelemetry = false; expectedFailure = null
    }
    private fun resultFields(result: TestResult): Map<String, Any?> = mapOf("id" to result.id,
        "title" to result.title, "outcome" to result.outcome, "durationMs" to result.durationMs, "detail" to result.detail)
    private fun persistCampaign() {
        val c = state.value.campaign
        if (c.id.isBlank()) return
        log.report(c.id, mapOf("id" to c.id, "outcome" to c.outcome, "step" to c.step,
            "elapsedMs" to c.elapsedMs, "summary" to c.summary, "batteryFloor" to config.batteryFloor,
            "robot" to state.value.selected?.name, "firmware" to state.value.telemetry.firmware,
            "plannedCases" to DiagnosticPlan.steps(config).map { mapOf("id" to it.id, "title" to it.title, "seconds" to it.seconds) },
            "localResults" to state.value.localResults.map(::resultFields), "results" to c.results.map(::resultFields),
            "metrics" to metricsFields()))
    }
    private fun metricsFields(): Map<String, Any?> = mapOf("reports" to reportCount, "positions" to positionCount,
        "reportGaps" to reportGaps.fields(), "positionGaps" to positionGaps.fields(), "gattWriteLatency" to writeTimes.fields(),
        "logicalCommandsCompleted" to logicalCommandsCompleted, "zeroCommandsQueued" to zeroCommandsQueued,
        "zeroCommandsCompleted" to zeroCommandsCompleted, "maxMonitorTickIntervalMs" to maxLoopDelayMs,
        "robotBatteryStartPercent" to batteryStart, "robotBatteryMinPercent" to batteryMin,
        "robotBatteryLatestPercent" to state.value.telemetry.battery, "positionTypeChanges" to positionTypeChanges,
        "rtkStatusChanges" to rtkStatusChanges, "maxStationaryPositionDriftMeters" to maxStationaryDriftM,
        "interruptionsAppRun" to state.value.interruptions, "stopGATTConfirmationsAppRun" to stopAckCount)
    private fun diagnosticSnapshot(now: Long) {
        val battery = getApplication<Application>().registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val memory = Runtime.getRuntime()
        val fields = metricsFields() + mapOf("bleRssiDbm" to state.value.rssi, "mtu" to state.value.mtu,
            "telemetryAgeMs" to if (lastTelemetry == 0L) null else now - lastTelemetry,
            "deviceStateAgeMs" to if (devicePrevious == 0L) null else now - devicePrevious,
            "positionAgeMs" to if (positionPrevious == 0L) null else now - positionPrevious,
            "bluetoothEnabled" to adapter?.isEnabled,
            "phoneBatteryLevel" to battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1),
            "phoneBatteryScale" to battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1),
            "phoneBatteryTemperatureTenthsC" to battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1),
            "phonePlugged" to battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0),
            "phoneThermalStatus" to if (android.os.Build.VERSION.SDK_INT >= 29) getApplication<Application>().getSystemService(android.os.PowerManager::class.java).currentThermalStatus else null,
            "heapUsedBytes" to memory.totalMemory() - memory.freeMemory(), "diskAvailableBytes" to getApplication<Application>().filesDir.usableSpace)
        event("diagnostic_snapshot", tr("Periodic summary (5 s)", "Synthèse périodique (5 s)"), fields)
        val summary = tr("Reports $reportCount · Positions $positionCount\nReport interval p95 ${reportGaps.percentile(95) ?: "—"} ms · max ${reportGaps.maxMs} ms\nGATT p95 ${writeTimes.percentile(95) ?: "—"} ms · max ${writeTimes.maxMs} ms", "Rapports $reportCount · Positions $positionCount\nIntervalle rapports p95 ${reportGaps.percentile(95) ?: "—"} ms · max ${reportGaps.maxMs} ms\nGATT p95 ${writeTimes.percentile(95) ?: "—"} ms · max ${writeTimes.maxMs} ms")
        update { it.copy(diagnosticSummary = summary, campaign = if (it.campaign.running) it.campaign.copy(elapsedMs = now - campaignStart) else it.campaign) }
        if (state.value.campaign.running) persistCampaign()
    }

    override fun onCleared() {
        // Continuous map: latest measurements (under 30 s) written before the end; the background writer stops with the model.
        liveMap?.let { live -> runCatching { surveyStore.save(live, true) } }
        fr.luba.poc.gateway.PhoneWifi.unlisten(phoneWifiListener)
        stopScan(); release(); log.close(); super.onCleared()
    }
    companion object {
        val SERVICE: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Maximum distance to the approach point for handing docking over to the Mammotion return. */
        const val NATIVE_HANDOFF_APPROACH_M = 0.20
        /** Outside working hours, the robot is brought back only if it is farther than this from the base (m). */
        const val WORK_WINDOW_OUTSIDE_M = 1.0
        /** After a stop requested during autonomous tracking, how long a dropped Wi-Fi link is still re-established to deliver the order. */
        const val ABORT_HOLD_MS = 60_000L
    }
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Listening delay before concluding that only one robot is in range. */
private const val AUTO_CONNECT_SETTLE_MS = 4000L
