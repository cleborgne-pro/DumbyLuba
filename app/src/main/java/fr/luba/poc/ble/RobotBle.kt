@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.luba.poc.BuildConfig
import fr.luba.poc.Clock
import fr.luba.poc.EventHealth
import fr.luba.poc.EventLog
import fr.luba.poc.alerts.*
import fr.luba.poc.autonomy.AutonomySettings
import fr.luba.poc.autonomy.LegTransport
import fr.luba.poc.autonomy.RemoteLegs
import fr.luba.poc.camera.CameraRobotObserver
import fr.luba.poc.camera.CameraRobotState
import fr.luba.poc.camera.ObstaclePhotoStore
import fr.luba.poc.camera.ObstaclePhotoTiming
import fr.luba.poc.camera.ObstaclePhotoUi
import fr.luba.poc.diagnostics.*
import fr.luba.poc.difficulty.DifficultyClassifier
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyStore
import fr.luba.poc.difficulty.PresumedObstacles
import fr.luba.poc.gateway.GatewayClient
import fr.luba.poc.gateway.GatewayConfig
import fr.luba.poc.gateway.GatewayHealthStore
import fr.luba.poc.gateway.GatewayLink
import fr.luba.poc.gateway.GatewayOta
import fr.luba.poc.gateway.GatewayProtocol
import fr.luba.poc.gateway.GatewayRetry
import fr.luba.poc.gateway.GatewayRole
import fr.luba.poc.gateway.GatewayScanFailed
import fr.luba.poc.gateway.GatewayService
import fr.luba.poc.gateway.KeepaliveLoss
import fr.luba.poc.gateway.PhoneWifi
import fr.luba.poc.history.BlackBoxCommand
import fr.luba.poc.history.BlackBoxNote
import fr.luba.poc.history.BlackBoxRecorder
import fr.luba.poc.history.TaskEventRecorder
import fr.luba.poc.history.TaskEventType
import fr.luba.poc.history.TaskTrack
import fr.luba.poc.history.TrackingLink
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mapsync.MapSyncPlanner
import fr.luba.poc.mapsync.MapSyncPort
import fr.luba.poc.mapsync.MapSyncRunner
import fr.luba.poc.mapsync.SyncItem
import fr.luba.poc.mapsync.SyncKind
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto
import fr.luba.poc.protocol.*
import fr.luba.poc.relay.RobotLinkService
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.RobotModel
import java.util.Base64
import java.util.UUID
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** GATT serialized on the main thread; reconnections only in planned test cases. */
@SuppressLint("MissingPermission")
class RobotBle(app: Application) : AndroidViewModel(app) {
    internal val handler = Handler(Looper.getMainLooper())
    private val positionStore = LastKnownPositionStore(app.filesDir)
    private val positionWrites = Channel<LastKnownPosition>(Channel.CONFLATED)
    private val positionPreferences = app.getSharedPreferences("last_position_robot", 0)
    /** Motion gains calibrated per robot (BLE address), reused at the start of the next mission. */
    internal val calibration = CalibrationStore(this)

    /** How routes are tracked right now, recorded in the task history to compare field runs. */
    internal fun trackingLink(): TrackingLink = when {
        autonomousLegsAvailable() -> TrackingLink.GATEWAY
        gatt is GatewayLink -> TrackingLink.RELAY
        else -> TrackingLink.BLUETOOTH
    }

    /** Routes are tracked by the gateway (Redmi) if it advertises it and the setting is not turned off. */
    internal fun autonomousLegsAvailable(): Boolean =
        (gatt as? GatewayLink)?.supportsLegs == true && AutonomySettings.enabled(getApplication()) &&
            ModelSupport.motionValidated(state.value.selected?.name) &&
            // The gateway does not know the bumper resting value learnt here (DumbyLuba#1): its guards would stop at once,
            // the app tracks the route itself.
            bumperRest.value == 0

    /** Slow straight exit from the base, shared by route missions and moves. */
    internal val dockExit = DockExitRunner(this)

    /** Route missions (mowing, surveys, station trips): preparation, tracking from the phone or by the gateway, recharge and resume, checkpoints and gateway update resume. */
    internal val routes = RouteOrchestrator(this)
    fun startRobotRoute(engine: MowingSimulation, cutting: Boolean, heightMm: Int, avoidance: MissionAvoidance? = null): Boolean = routes.startRobotRoute(engine, cutting, heightMm, avoidance)
    fun resumeRobotRoute(engine: MowingSimulation, skipTo: MissionResume? = null): Boolean = routes.resumeRobotRoute(engine, skipTo)
    fun discardMissionCheckpoint() = routes.discardMissionCheckpoint()
    fun baseAction(engine: MowingSimulation, leave: Boolean): Boolean = routes.baseAction(engine, leave)

    private val mapCache = MapCache(app)
    private val obstacleStore = ObstacleStore(app)
    /** Signal surveys: samples of a dedicated survey route and of the continuous signal map, published and saved at intervals. */
    internal val signalSurveys = SignalSurveyRecorder(this)

    internal val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val restoredId = positionPreferences.getString("id", null)
    private val restoredPosition = restoredId?.let(positionStore::read)
    private val checkpointStore = MissionCheckpointStore(app.filesDir)
    private val pointResumeStore = PointResumeStore(app.filesDir)
    /** Move-to-point writes serialized off the main thread; null = cleared. */
    internal val pointResumeWrites = Channel<Pair<String, PointResume?>>(Channel.UNLIMITED)
    /** Progress writes serialized off the main thread; null = mission finished or abandoned. */
    internal val checkpointWrites = Channel<Pair<String, MissionCheckpoint?>>(Channel.UNLIMITED)
    /** Actual mowing trail, kept from one resume to the next ([MissionTrail]); null list = trail cleared. */
    internal val trailStore = MissionTrailStore(app.filesDir)
    internal data class TrailWrite(val robotId: String, val startedAt: Long, val trail: List<Point>?)
    internal val trailWrites = Channel<TrailWrite>(Channel.UNLIMITED)
    internal val watchdogPreferences = app.getSharedPreferences("motor_watchdog", 0)
    internal fun watchdogResult(id: String?): MotorWatchdogResult? =
        id?.let { watchdogPreferences.getString(it, null) }?.let(MotorWatchdogResult::fromJson)?.takeIf { it.robotId == id }
    /** Route travel speed (%), kept between launches (10–100, 30 by default). */
    private val speedPreferences = app.getSharedPreferences("trajectory_speed", 0)
    /** Internal model code per robot ([Telemetry.productType]): cutting heights known as soon as it reconnects. */
    private val productPreferences = app.getSharedPreferences("robot_product_type", 0)
    private fun storedProductType(id: String?): String? = id?.let { productPreferences.getString(it, null) }
    /** Local map corrections: phone overlay, never sent to the robot nor written to the Mammotion cache. */
    private val correctionStore = MapCorrectionStore(app.filesDir)
    private val mutable = MutableStateFlow(RobotUi(signalSurvey = restoredId?.let(signalSurveys::stored) ?: SignalSurvey(), lastKnown = restoredPosition,
        missionCheckpoint = restoredId?.let(checkpointStore::read), pointResume = restoredId?.let(pointResumeStore::read),
        motorWatchdog = watchdogResult(restoredId), productType = storedProductType(restoredId),
        trajectorySpeedPercent = speedPreferences.getInt("percent", 30).coerceIn(10, 100))
        .withMammotionMap(restoredId?.let(mapCache::read) ?: RobotMap(), correctionStore.load()))
    val state = mutable.asStateFlow()
    /** Shell projection of [state] ([RobotShellUi]): emitted only when the connection or a running activity changes. */
    val shell: StateFlow<RobotShellUi> = state.map(RobotShellUi::from).stateIn(viewModelScope, SharingStarted.Eagerly, RobotShellUi.from(state.value))
    internal val notifications = LocalNotifications(app, viewModelScope)
    val localAlerts = notifications.state
    internal val alertMonitor = AlertMonitor()
    internal var alertLastTelemetryAt = 0L
    internal var alertDeviceAt: Long? = null
    internal var alertRtkAt: Long? = null
    internal var alertCommunicationExpected = false
    internal var alertLinkFault = false
    fun refreshNotifications() = notifications.refresh()
    fun markNotificationsSeen() = notifications.markSeen()
    fun clearNotificationHistory() = notifications.clearHistory()
    fun testNotification(severity: Severity) = alerts.alert(AlertKind.TEST, severity, tr("Local test requested — no robot command", "Test local demandé — aucune commande robot"))
    /** Local alerts: robot errors, work window, gateway battery and detachment, route and motion failures. */
    internal val alerts = RobotAlertCenter(this)

    init {
        signalSurveys.startWriter()
        WorkWindowSetting.init(getApplication()); SmartFeatures.init(getApplication())
        viewModelScope.launch { while (true) { delay(1000); alerts.observeAlerts(); alerts.observeWorkWindow() } }
        // Control through the gateway: automatic search and reconnection, screen on or off (startup, update,
        // Wi-Fi or Redmi back, BLE reconnections exhausted). No mission or command is resumed, except a mowing
        // interrupted by an announced update of the ESP32 gateway, if the setting allows it ([planGatewayUpdateResume]).
        GatewayOta.load(getApplication())
        viewModelScope.launch {
            delay(1500)
            while (true) {
                retryGatewayLink()
                delay(GatewayRetry.delayMs(gatewayFailures))
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for ((id, checkpoint) in checkpointWrites) {
                val saved = runCatching { if (checkpoint == null) checkpointStore.delete(id) else checkpointStore.save(checkpoint) }.isSuccess
                handler.post { if (state.value.selected?.address == id || state.value.selected == null) update { it.copy(checkpointSaveError = !saved) } }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for ((id, resume) in pointResumeWrites) runCatching { if (resume == null) pointResumeStore.delete(id) else pointResumeStore.save(resume) }
                .onFailure { e -> handler.post { event("point_resume_save_failed", tr("Move to a point not saved: ${e.message}", "Déplacement au point non sauvegardé : ${e.message}")) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (write in trailWrites) runCatching {
                if (write.trail == null) trailStore.delete(write.robotId) else trailStore.save(write.robotId, write.startedAt, write.trail)
            }.onFailure { e -> handler.post { event("mission_trail_save_failed", tr("Mowing trail not saved: ${e.message}", "Trace de la tonte non sauvegardée : ${e.message}")) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (known in positionWrites) {
                val saved = runCatching { positionStore.save(known) }.isSuccess
                handler.post { if (state.value.lastKnown?.robotId == known.robotId) update { it.copy(positionSaveError = !saved) } }
            }
        }
    }
    /** Task history of a mission: record, black box, tracking trace and actual coverage, written off the main thread. */
    internal val taskHistory = TaskHistoryRecorder(this)
    fun showTaskDetail(id: String?) = taskHistory.showTaskDetail(id)
    fun showMowingReport(id: String?) = taskHistory.showMowingReport(id)
    fun setMowingExcluded(id: String, excluded: Boolean) = taskHistory.setMowingExcluded(id, excluded)
    // Typed journal of the running task (recharges, obstacles, blockages, STOP, link losses…), written with its track.
    internal var taskEvents: TaskEventRecorder? = null
    // Last speed command written by this phone (raw units) and when (elapsed realtime), for the track samples.
    internal var lastSetpoint: MotionSetpoints.Setpoint? = null
    internal var lastSetpointAt = 0L
    // Black box: sliding memory of the running task (frames, commands, log events), frozen at each incident.
    internal val blackBox = BlackBoxRecorder()

    /** Track of a history task (read from file); points still in memory included for the current task. */
    suspend fun loadTaskTrack(id: String): TaskTrack? = taskHistory.loadTaskTrack(id)
    internal val log = EventLog(app) { text -> handler.post { if (gatt != null) fail(text) else status(text) } }
    internal var gatt: RobotLink? = null
    /**
     * Link opening, replaceable by JVM tests (simulated robot in virtual time). Null in production:
     * gateway link ([fr.luba.poc.gateway.GatewayLink]) or this phone's Bluetooth ([GattLink]).
     */
    internal var linkFactory: ((SeenRobot, RobotLink.Listener) -> RobotLink?)? = null
    /** Tracking runs handed to the gateway (phone mounted on the robot): see [fr.luba.poc.autonomy.RemoteLegs]. */
    internal val legs = RemoteLegs(LegTransport { type, payload -> (gatt as? GatewayLink)?.legTransport?.send(type, payload) ?: false })
    /** A tracking run is handed to the gateway: this phone writes nothing more to the robot (the gateway holds it). */
    @Volatile internal var remoteLegActive = false
    internal var gatewayOutageSince = 0L
    /** Last gateway recovery after a ridden-out Wi-Fi outage (monotonic clock). */
    private var gatewayResumedAt = 0L
    private var blufi = Blufi()
    private val receivedFrames = ReceivedFrames(::event)
    internal var commands = Commands()
    private var reducer = TelemetryReducer()
    /** Resting value of the bumper slot learnt on this connection ([fr.luba.poc.protocol.BumperRest], DumbyLuba#1). */
    private val bumperRest = BumperRest()
    /** A contact code (1014) was received on this connection: no bumper resting value is learnt any more. */
    private var contactThisConnection = false
    internal val sensorProbe = SensorProbe()
    private val cameraObserver = CameraRobotObserver()
    private val perceptionMaps = PerceptionMapObserver()
    // Automatic reconnection after an unexpected link loss: link only, commands are never resumed.
    private var unexpectedLoss = false
    private var wantLink = false
    internal var readyAt = 0L
    private var reconnectMark = 0L
    private var reconnectAttempts = 0
    private var reconnectTimer: Job? = null
    private var watchdog = TelemetryWatchdog()
    private var started = 0L
    internal var lastTelemetry = 0L
    internal var periodic: Job? = null
    private var handshake: Job? = null
    private var scanTimeout: Job? = null
    // Automatic connection to the single robot found by a search started with autoConnect.
    private var autoConnectPending = false
    private var autoConnectCheck: Job? = null
    /** Emitted when an automatic search finds several robots: the user must choose. */
    val robotChoice = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal var stationReader = StationReader()
    internal var closing = false
    private var closeAfterStop = false
    internal val queue = ArrayDeque<Operation>()
    internal var current: Operation? = null
    private var operationTimeout: Job? = null
    internal var stopPending = false
    private var stopReason = ""
    private var batchCounter = 0L

    internal sealed interface Operation {
        data class Write(val bytes: ByteArray, val label: String, val batch: Long, val finalStop: Boolean = false, val queuedAt: Long = SystemClock.elapsedRealtime(),
            val setpoint: MotionSetpoints.Setpoint? = null) : Operation
        data object Rssi : Operation
    }
    internal fun update(block: (RobotUi) -> RobotUi) { mutable.value = block(mutable.value) }
    internal var connectionId = 0L
    private var operationStarted = 0L
    private var stopRequestedAt = 0L
    internal var stopAckCount = 0L
    private var connectRequestedAt = 0L
    /** Report and position cadence, battery, positioning changes and drift, read by the diagnostic campaign. */
    internal val telemetryStats = TelemetryStats()
    private var reportPrevious = 0L
    internal var positionPrevious = 0L
    internal var devicePrevious = 0L
    internal var writeTimes = TimingStats()
    internal var logicalCommandsCompleted = 0L
    internal var zeroCommandsQueued = 0L
    /** Motion setpoint frames removed from the queue, replaced by a more recent setpoint. */
    private var setpointsReplaced = 0L
    private var periodicSkipped = 0L
    /** Commands dropped by the read-only mode of a model that is not validated. */
    private var readOnlyRefused = 0L
    /** Moves without mowing: navigation tests, obstacle courses, move to a point (with resume) and map route tests. */
    internal val navigation = NavigationRunner(this)
    fun startNavTest(config: NavConfig) = navigation.startNavTest(config)
    fun previewObstacleCourse(obstacleId: String) = navigation.previewObstacleCourse(obstacleId)
    fun startObstacleCourse(obstacleId: String) = navigation.startObstacleCourse(obstacleId)
    fun startBladeMoveTest(radiusM: Double, heightMm: Int, maxDurationS: Int) = navigation.startBladeMoveTest(radiusM, heightMm, maxDurationS)
    fun acknowledgeNavInterruption() = navigation.acknowledgeNavInterruption()
    fun startPointNavigation(target: Point) = navigation.startPointNavigation(target)
    fun discardPointResume() = navigation.discardPointResume()
    fun stopNavTest(reason: String = tr("Release / manual stop", "Relâchement / arrêt manuel"), userInitiated: Boolean = false) = navigation.stopNavTest(reason, userInitiated)
    /** Last non-zero motion setpoint confirmed as written (0: none since the stop). */
    internal var motionWrittenAt = 0L
    internal var zeroCommandsCompleted = 0L
    internal var maxLoopDelayMs = 0L
    /** Diagnostic campaign: planned link and command cases (stops, reconnections, watchdog), results and metrics. */
    internal val campaign = DiagnosticCampaign(this)
    fun startCampaign(options: CampaignConfig) = campaign.startCampaign(options)
    internal var campaignFailure: String? = null
    internal var ignoreTelemetry = false
    internal var expectedFailure: String? = null
    internal var expectedFaultObserved = 0L
    internal var config = CampaignConfig()
    internal var activeCase = ""
    internal var bladeJob: Job? = null
    internal var bladeMayBeOn = false
    internal var bladeOffPending = false
    /** Blades: height changes, rotation and height tests, guarded blade stop (queued OFF, verification) and their results. */
    internal val blades = BladeController(this)
    fun setBladeHeight(mm: Int, timeoutMs: Long = 30_000) = blades.setBladeHeight(mm, timeoutMs)
    fun prepareBladeStop(heightMm: Int) = blades.prepareBladeStop(heightMm)
    fun startBladeTest(heightMm: Int, useKnifeControl: Boolean = false) = blades.startBladeTest(heightMm, useKnifeControl)
    fun stopOwnedBladeTest(reason: String) = blades.stopOwnedBladeTest(reason)
    fun stopBladeTest(reason: String = tr("Button released", "Relâchement bouton")) = blades.stopBladeTest(reason)
    fun cancelBladeHeight() = blades.cancelBladeHeight()
    fun startHeightSequence(targets: List<Int>) = blades.startHeightSequence(targets)
    fun stopHeightSequence(reason: String = tr("Sequence canceled", "Séquence annulée")) = blades.stopHeightSequence(reason)
    fun recordHeightObservation(moved: Boolean) = blades.recordHeightObservation(moved)
    /** Last height explicitly requested (height setting, blade test, route, navigation), 60 mm until then. */
    internal var bladeHeightRequest = 60
    /**
     * Height of the manual-mode commands that must leave the deck where it is (joystick, slow approach, bumper test,
     * STOP, moves with blades stopped): the one the robot reports, unless a height setting owns the deck ([HoldHeight],
     * DumbyLuba#2).
     */
    internal fun holdHeightMm(): Int = HoldHeight.mm(state.value.telemetry, bladeHeightRequest, state.value.cutHeights,
        adjusting = heightTarget != null || heightJob != null || bladeMayBeOn || bladeOffPending || state.value.bladeTestActive ||
            state.value.heightSequence.running || state.value.robotRoute.running || state.value.navTest.running)
    internal var bladeStopVerified = false
    internal var bladeOffWrittenAt = 0L
    internal var bladeOnObserved = false
    internal var bladeTestId = ""
    internal var bladeDeviceAt = 0L
    internal var heightTestId = ""
    internal var heightTarget: Int? = null
    internal var heightJob: Job? = null
    /** Observation of a mowing run by the robot firmware: checks, progress, interruption and persistence (no command sent). */
    internal val nativeMowing = NativeMowingMonitor(this)
    fun recordMowingCheck(key: String, checked: Boolean) = nativeMowing.recordMowingCheck(key, checked)
    fun finishMowing() = nativeMowing.finishMowing()
    /** Intent of the current route: only a mowing ([RouteIntent.FOLLOW]) stops at the end of the working hours. */
    internal var routeIntent: RouteIntent? = null
    /** Working hours closed during the mowing: stop at the next check, return to base, end of mission. */
    @Volatile internal var workWindowEnding = false
    internal var routeBladeGate: RouteBladeGate? = null
    /** Manual driving with the joystick: gestures, latches ignored on request, contact handling and stop. */
    internal val manualDrive = ManualDrive(this)
    fun beginManualGesture() = manualDrive.beginManualGesture()
    fun manualInput(linear: Int, angular: Int): Boolean = manualDrive.manualInput(linear, angular)
    fun stopManual(reason: String = tr("Joystick released", "Joystick relâché"), sendStop: Boolean = true) = manualDrive.stopManual(reason, sendStop)
    /** Last non-zero joystick speed (sign: forward / reverse), to place the object touched while driving. */
    internal var manualLastLinear = 0
    /** Until then (elapsed realtime), a 1014 refines the contact of the joystick stopped on a protection. */
    internal var manualContactUntil = 0L
    internal var preparationJob: Job? = null
    internal var navJob: Job? = null
    internal var navStarted = 0L
    /** Recovery after a stop: local incident capture, collision latch unlock, back-off before resuming, Mammotion task stop and native return. */
    internal val recovery = RecoveryActions(this)
    fun unlockAfterStop() = recovery.unlockAfterStop()
    fun backOffBeforeResume(engine: MowingSimulation) = recovery.backOffBeforeResume(engine)
    fun stopBackOff() = recovery.stopBackOff()
    fun stopMammotionTask() = recovery.stopMammotionTask()
    internal var navConfig = NavConfig()
    internal var navMapPlan: MapNavPlan? = null
    /** Obstacle bypass in progress: obstacle, opposite point and return. */
    internal var navCourse: LocalObstacle? = null
    /** Fused heading confirmed by movement (route or drive in progress), otherwise null. */
    internal var confirmedHeading: Double? = null
    /** Heading used to place an obstacle at contact: fused if confirmed, otherwise the published heading. */
    internal fun contactHeading(reported: Double?): Double? = confirmedHeading ?: reported
    /** Bypass armed while tracking the main edges; contact awaiting processing. */
    internal var routeBypassArmed = false
    internal var routeContact: RouteContact? = null
    internal var navContact: RouteContact? = null
    internal var localIncidentPending = false
    /** Manual mode with blades OFF requested for the map test: to be exited on stop. */
    internal var navBumperMode = false
    internal var bumperValidatedConnection = -1L
    private val robotErrors = RobotErrorMonitor()
    /** Lift during a BLE drive: robot codes, safety bits, wheels without progress. */
    internal val liftGuard = LiftGuard()
    /** Safety tests: command silence (motor watchdog) and obstacle approach tests with their contact, back-off and latch handling. */
    internal val safetyTests = SafetyTestRunner(this)
    fun startSilenceTest(linearRaw: Int) = safetyTests.startSilenceTest(linearRaw)
    fun stopSilenceTest(reason: String = tr("Button released", "Bouton relâché")) = safetyTests.stopSilenceTest(reason)
    fun startObstacleTest(approach: Boolean, manualMode: Boolean = false) = safetyTests.startObstacleTest(approach, manualMode)
    fun recordBumperBeep() = safetyTests.recordBumperBeep()
    fun stopObstacleTest() = safetyTests.stopObstacleTest()
    internal var obstacleManualMode = false
    /** Frontal border test: the robot drives up to a border at low speed, bumper mode on, and records where it stops. */
    internal val borderFrontal = BorderFrontalRunner(this)
    fun previewBorderFrontal(shapeId: Long) = borderFrontal.previewBorderFrontal(shapeId)
    fun startBorderFrontal(shapeId: Long) = borderFrontal.startBorderFrontal(shapeId)
    fun borderPassEndVerdict(ok: Boolean) = borderFrontal.borderPassEndVerdict(ok)
    fun continueBorderToEdge() = borderFrontal.continueBorderToEdge()
    fun borderEdgeSeen() = borderFrontal.borderEdgeSeen()
    fun stopBorderFrontal(reason: String = tr("User stop", "Arrêt utilisateur")) = borderFrontal.stopBorderFrontal(reason)
    /** Manual mode with blades OFF for the frontal test: bumper contact handled by the test (STOP, measurement, no obstacle created). */
    internal var borderBumperWatch = false
    internal var borderContactCode: Int? = null
    internal var borderErrorCode: Int? = null
    internal enum class BorderRequest { OK, TOO_FAR, CONTINUE, EDGE }
    // Obstacle photo after contact: declared before the init that reads the setting.
    // Difficulty log: declared before the init block that reads it (Kotlin initialization order).
    internal val difficultyStore = DifficultyStore(app.filesDir)
    /** Pivot slides learned during mowings ([SlideField]), remembered from one mowing to the next. */
    internal val slideStore = SlideFieldStore(app.filesDir)
    @Volatile internal var slideField = SlideField()
    internal val difficultyPrefs = app.getSharedPreferences("difficulties", Context.MODE_PRIVATE)
    internal val photoPrefs = app.getSharedPreferences("obstacle_photo", Context.MODE_PRIVATE)
    /** Obstacle photo: robot held still in front of a presumed obstacle while the camera page takes a picture, stored with the incident. */
    internal val obstaclePhotos = ObstaclePhotoRunner(this)
    fun setObstaclePhotoEnabled(enabled: Boolean) = obstaclePhotos.setObstaclePhotoEnabled(enabled)
    fun setObstaclePhotoCamera(camera: Int) = obstaclePhotos.setObstaclePhotoCamera(camera)
    fun obstaclePhotoCaptured(id: Long, jpeg: ByteArray, camera: Int = ObstaclePhotoUi.CAMERA_FRONT,
                              timing: ObstaclePhotoTiming = ObstaclePhotoTiming()) = obstaclePhotos.obstaclePhotoCaptured(id, jpeg, camera, timing)
    fun obstaclePhotoFailed(id: Long, reason: String) = obstaclePhotos.obstaclePhotoFailed(id, reason)
    internal val photoStore = ObstaclePhotoStore(app.filesDir, runCatching { app.getExternalFilesDir("obstacle-photos") }.getOrNull())
    /** Phone Wi-Fi lost or restored: logged (on 2026-10-14 the outage only showed up in `dumpsys wifi`). */
    private val phoneWifiListener: (String) -> Unit = { text -> event("phone_wifi", text, mapOf("wifiEnabled" to PhoneWifi.enabled(),
        "wifiConnected" to PhoneWifi.connected)) }

    init {
        PhoneWifi.listen(phoneWifiListener)
        event("app_start", tr("Diagnostics started", "Démarrage diagnostics"), mapOf("androidApi" to Build.VERSION.SDK_INT,
            "androidRelease" to Build.VERSION.RELEASE, "model" to Build.MODEL,
            "appVersion" to BuildConfig.VERSION_NAME, "appVersionCode" to BuildConfig.VERSION_CODE, "timeoutSeconds" to state.value.timeoutSeconds))
        refreshHistory()
        update { it.copy(obstacles = obstacleStore.load(), obstaclePhoto = it.obstaclePhoto.copy(enabled = photoPrefs.getBoolean("enabled", true),
            camera = ObstaclePhotoUi.cameraOf(photoPrefs.getInt("camera", ObstaclePhotoUi.CAMERA_FRONT)))) }
        viewModelScope.launch(Dispatchers.IO) {
            val photos = runCatching { photoStore.list() }.getOrDefault(emptyList())
            handler.post { update { it.copy(obstaclePhoto = it.obstaclePhoto.copy(photos = photos)) } }
        }
        update { it.copy(difficulties = it.difficulties.copy(avoidPresumed = difficultyPrefs.getBoolean("avoidPresumed", true),
            avoidHot = difficultyPrefs.getBoolean("avoidHot", true))) }
        viewModelScope.launch(Dispatchers.IO) {
            val events = difficultyStore.load()
            handler.post { difficulties.publishDifficulties(events, persist = false) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val field = synchronized(slideStore) { slideStore.load() }
            handler.post { if (slideField.isEmpty) slideField = field }
        }
    }

    // ───── Pivot slides: map learned during mowing ─────

    /** Difficulties met by the robot (contacts, slides, blockages, presumed obstacles): recording, learning of the slide field and route costs. */
    internal val difficulties = DifficultyJournal(this)
    fun closeManualContact() = difficulties.closeManualContact()
    fun dismissDifficulty(id: String, dismissed: Boolean = true) = difficulties.dismissDifficulty(id, dismissed)
    fun clearDifficulties() = difficulties.clearDifficulties()
    fun setAvoidPresumedObstacles(enabled: Boolean) = difficulties.setAvoidPresumedObstacles(enabled)
    fun setAvoidDifficultAreas(enabled: Boolean) = difficulties.setAvoidDifficultAreas(enabled)

    /** Measured manoeuvre ([ManoeuvreStats]), local tracking or gateway progress: `route_curve_stats`. */
    internal fun logManoeuvre(summary: ManoeuvreStats.Summary, source: String) {
        val m = summary.manoeuvre
        event("route_curve_stats", tr("Manoeuvre %s driven in %.1f s, largest deviation %.0f cm", "Manœuvre %s suivie en %.1f s, écart max %.0f cm")
            .format(m.kind.name, summary.durationS, summary.maxDeviationM * 100), mapOf("kind" to m.kind.name, "first" to m.first, "last" to m.last,
            "degrees" to m.degrees, "lengthM" to m.lengthM, "durationS" to summary.durationS, "medianSpeedMps" to summary.medianSpeedMps,
            "maxDeviationM" to summary.maxDeviationM, "source" to source))
    }
    internal fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) {
        taskEvents?.let { recorder ->
            if (kind !in BlackBoxRecorder.NOISY) blackBox.note(BlackBoxNote(Clock.now(), kind, text.take(300)))
            taskHistory.taskPose().let { (x, y, battery) -> taskHistory.taskEventWrite(recorder.fromLog(kind, text, fields, Clock.now(), x, y, battery)) }
        }
        log.append(kind, text, fields + mapOf("connectionId" to connectionId,
            "campaignId" to state.value.campaign.id, "caseId" to activeCase, "queueDepth" to queue.size,
            "bladeTestId" to bladeTestId, "heightTestId" to heightTestId))
        update { it.copy(events = (it.events + "${java.time.LocalTime.now()} $text").takeLast(150)) }
    }
    /**
     * Event written on the robot log by a satellite service (foreground link service): a STOP or a disconnection that
     * fails there must leave a trace, like every other safety path ([event]). Audit of 2026-10-30.
     */
    fun journal(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = event(kind, text, fields)
    /** Sensor and perception capture (ultrasound, vision streams, ZMQ perception listener) and camera commands: no motion command. */
    internal val sensorProbes = SensorProbeSession(this)
    fun startSensorProbe() = sensorProbes.startSensorProbe()
    fun setSensorProbeDebug(on: Boolean) = sensorProbes.setSensorProbeDebug(on)
    fun setSensorProbeZmq(on: Boolean) = sensorProbes.setSensorProbeZmq(on)
    fun stopSensorProbe() = sensorProbes.stopSensorProbe()
    fun sendCameraCommand(payload: ByteArray, label: String): Boolean = sensorProbes.sendCameraCommand(payload, label)
    fun cameraVideo(on: Boolean): Boolean = sensorProbes.cameraVideo(on)
    fun cameraRefresh(): Boolean = sensorProbes.cameraRefresh()
    fun cameraEvent(text: String, fields: Map<String, Any?> = emptyMap()) = sensorProbes.cameraEvent(text, fields)
    fun resetSensorProbe() = sensorProbes.resetSensorProbe()
    /** Current telemetry subscription: vision streams added while the sensor probe is running. */
    internal fun reportsCommand(keep: Boolean, periodMs: Int = 1000) =
        commands.reports(keep, periodMs = periodMs, vision = sensorProbe.isRunning)
    /**
     * Report period: 200 ms during a tracked movement (route, learning, tests), 1 s otherwise. The LUBA 2 (firmware
     * 1.30.31.19) stays at 1 Hz whatever the request (measured on 2026-10-20, see [Commands.reports]).
     */
    internal fun reportPeriodMs(): Int = if (state.value.robotRoute.running || state.value.teach.running || state.value.navTest.running ||
        state.value.silenceTest.running || state.value.driven?.paused == false) 200 else 1000

    internal fun status(text: String) { update { it.copy(status = text) }; event("status", text) }
    fun setTimeout(seconds: Int) {
        if (gatt == null) update { it.copy(timeoutSeconds = seconds.coerceIn(5, 30)) }
    }
    fun export(uri: Uri, archive: Boolean = false, anonymize: Boolean = true) {
        if (state.value.campaign.running) return
        log.export(uri, archive, anonymize) { result -> handler.post { status(result) } }
    }
    fun refreshHistory() { log.history { value -> handler.post { update { it.copy(history = value) } } } }
    /** Aggregate health of this launch for the Service screen (stop/failure counts, last link loss, preparation time). */
    fun eventHealth(done: (EventHealth) -> Unit) = log.health { result -> handler.post { done(result) } }
    fun runLocalChecks() {
        if (state.value.campaign.running) return
        val results = LocalChecks.run()
        results.forEach { event("local_check", it.title, mapOf("outcome" to it.outcome, "detail" to it.detail)) }
        update { it.copy(localResults = results) }
    }

    fun recordSimulationPreparation(detail: String, fields: Map<String, Any?>) {
        event("simulation_preparation",detail,fields)
    }
    fun recordSimulationTransit(engine:MowingSimulation,state:SimState) {
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
    /** BLE map reading: requests, retries and end of the reading. */
    internal val mapReader = RobotMapReader(this)
    fun autoFetchMap(): Boolean = mapReader.autoFetch()
    fun fetchMap() = mapReader.fetch()
    private val scanAddresses = mutableSetOf<String>()
    private val scannerCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            if (!state.value.scanning) return@post
            try {
                val name = result.scanRecord?.deviceName ?: result.device.name
                if (scanAddresses.add(result.device.address)) event("scan_advertisement", tr("BLE advertisement", "Annonce BLE"), mapOf("name" to name, "address" to result.device.address, "rssiDbm" to result.rssi, "serviceUuids" to result.scanRecord?.serviceUuids?.map { it.toString() }))
                if (name == null) return@post
                // Every Mammotion mower is listed; only the validated models (Luba-VS, Luba-VP) leave read-only mode.
                if (!ModelSupport.isMower(name)) return@post
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
        val gateway = GatewayConfig.read(getApplication())
        when (gateway.role) {
            // No valid gateway code yet: nothing to authenticate with, the robot is searched over direct Bluetooth.
            GatewayRole.CLIENT -> if (gateway.validPilotCode()) scanThroughGateway(gateway, autoConnect) else scanDirect(autoConnect)
            GatewayRole.GATEWAY -> {
                // Gateway phone: the link is held by the gateway service, not by this screen.
                val held = GatewayService.status.value.robot
                if (held.address != null) status(tr("Gateway: ${held.label}", "Passerelle : ${held.label}"))
                else scanDirect(autoConnect, gatewayPhone = true)
            }
            GatewayRole.OFF -> if (!autoConnect) scanDirect(false) else {
                // Direct Bluetooth: a gateway present on the Wi-Fi keeps priority over automatic connection,
                // otherwise this phone would take the robot's only Bluetooth link and the gateway would no longer see it.
                stopScan()
                update { it.copy(scanning = true, status = tr("Searching for the robot…", "Recherche du robot…")) }
                scanTimeout = viewModelScope.launch {
                    val found = withContext(Dispatchers.IO) {
                        runCatching { GatewayClient.discover(getApplication(), 2000) }.getOrNull()
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
                if (ModelSupport.isMower(name)) SeenRobot(name, device.address, null) else null
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
                    state.value.devices.isEmpty() -> SetupCheck.notFoundHint(state.value.lastKnown?.robotName, viaGateway = false)
                    else -> tr("Detection finished: ${state.value.devices.size} LUBA available", "Détection terminée : ${state.value.devices.size} LUBA disponible(s)")
                })
                if (auto) resolveAutoConnect(final = true)
            }
        } catch (error: Exception) { stopScan(); status(tr("Scan: ${error.javaClass.simpleName}", "Scan : ${error.javaClass.simpleName}")) }
    }
    /** Search performed by the gateway phone mounted on the robot; same automatic resolution afterwards. */
    /** [directFallback]: gateway unreachable → direct Bluetooth search (search requested); false for automatic reconnection. */
    private fun scanThroughGateway(config: GatewayConfig, autoConnect: Boolean, directFallback: Boolean = true) {
        stopScan()
        update { it.copy(scanning = true, devices = emptyList(), status = tr("Searching for the robot through the Wi-Fi gateway…", "Recherche du robot par la passerelle Wi-Fi…")) }
        event("scan", tr("BLE detection through the gateway", "Détection BLE par la passerelle"), mapOf("autoConnect" to autoConnect, "gateway" to config.host))
        autoConnectPending = autoConnect
        scanTimeout = viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { GatewayClient.scan(getApplication(), config) }
            }
            if (!state.value.scanning) return@launch
            val auto = autoConnectPending
            (outcome.exceptionOrNull() as? GatewayScanFailed)?.let { refused ->
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
                val next = GatewayRetry.delayMs(gatewayFailures) / 1000
                if (gatewayFailures == 1) event("gateway_retry_failed", tr("Gateway unreachable: automatic reconnection", "Passerelle injoignable : reconnexion automatique"),
                    mapOf("error" to outcome.exceptionOrNull()?.message))
                status(tr("Gateway unreachable (${outcome.exceptionOrNull()?.message ?: "?"}): new attempt in $next s", "Passerelle injoignable (${outcome.exceptionOrNull()?.message ?: "?"}) : nouvelle tentative dans $next s") +
                    GatewayHealthStore.weakWifiHint())
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
            status(outcome.fold({ if (it.isEmpty()) tr("Gateway reached. ", "Passerelle jointe. ") + SetupCheck.notFoundHint(state.value.lastKnown?.robotName, viaGateway = true) else tr("Gateway: ${it.size} LUBA available", "Passerelle : ${it.size} LUBA disponible(s)") },
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
        try { adapter?.bluetoothLeScanner?.stopScan(scannerCallback) } catch (_: Exception) { /* Bluetooth off or scan already stopped */ }
        update { it.copy(scanning = false) }
    }
    fun connect(device: SeenRobot) {
        if (gatt != null) return
        if (GatewayConfig.read(getApplication()).role == GatewayRole.GATEWAY) {
            // The gateway holds the link itself (kept alive, shared with the pilot phone).
            stopScan()
            GatewayService.connectRobot(getApplication(), device.address, device.name)
            event("gateway_robot", tr("Robot handed over to the gateway", "Robot confié à la passerelle"), mapOf("name" to device.name, "address" to device.address))
            status(tr("Gateway: Bluetooth connection to ${device.name}; link kept alive and reconnected automatically (status in Service → Wi-Fi gateway)", "Passerelle : connexion Bluetooth à ${device.name} ; liaison maintenue et reconnectée automatiquement (état dans Service → Passerelle Wi-Fi)")); return
        }
        wantLink = true; unexpectedLoss = false; userDisconnected = false
        val recovering = alertLinkFault && state.value.selected?.address == device.address
        if(!recovering) alerts.intentionalAlertStop()
        alertLinkFault = recovering
        alertDeviceAt = null; alertRtkAt = null
        stopScan()
        robotErrors.reset()
        update { it.copy(teach = it.teach.copy(contactValidated = false)) }
        connectionId++; connectRequestedAt = SystemClock.elapsedRealtime()
        reportPrevious = 0L; positionPrevious = 0L; devicePrevious = 0L; telemetryStats.forgetDriftOrigin()
        blufi = Blufi(); commands = Commands.forRobot(device.name); reducer = TelemetryReducer(); bumperRest.reset(); contactThisConnection = false; liftGuard.reset(); motionWrittenAt = 0L; cameraObserver.reset(); if (sensorProbe.isRunning) stopSensorProbe()
        legs.forgetUploads(); remoteLegActive = false; gatewayOutageSince = 0L; update { it.copy(gatewayOutage = false) }
        watchdog = TelemetryWatchdog()
        bladeStopVerified = false; bladeOffWrittenAt = 0L; bladeDeviceAt = 0L
        lastTelemetry = 0L; closing = false; stopPending = false; closeAfterStop = false
        positionPreferences.edit { putString("id", device.address) }
        val survey = signalSurveys.switchRobot(device.address)
        update { it.copy(signalSurvey = survey, surveySaveError = false, lastKnown = positionStore.read(device.address), positionSaveError = false, selected = device, mapReadFailure = null, productType = storedProductType(device.address), missionCheckpoint = it.missionCheckpoint?.takeIf { cp -> cp.robotId == device.address } ?: checkpointStore.read(device.address), pointResume = it.pointResume?.takeIf { p -> p.robotId == device.address } ?: pointResumeStore.read(device.address), motorWatchdog = watchdogResult(device.address), rssi = device.rssi, connection = ConnectionState.Connecting, telemetry = Telemetry(), bumperRest = 0, ageMs = null, ready = false, mtu = 23).withMammotionMap(mapCache.read(device.address) ?: RobotMap()) }
        stationReader = StationReader(state.value.robotMap.station)
        event("connect", tr("Connecting ${device.name} ${device.address}", "Connexion ${device.name} ${device.address}"))
        try {
            val gateway = GatewayConfig.read(getApplication())
            val factory = linkFactory
            gatt = if (factory != null) factory(device, linkListener)
                else if (gateway.role == GatewayRole.CLIENT && !gatewayFallback) GatewayLink.open(gateway, device.address, device.name, linkListener)
                else GattLink.open(getApplication(), adapter, device.address, linkListener)
            if (gatt == null) { status(tr("Bluetooth unavailable", "Bluetooth indisponible")); release(); return }
            update { it.copy(viaGateway = gatt is GatewayLink) }
            val transport = gatt?.description ?: tr("unknown link", "liaison inconnue")
            event("link_opened", tr("Link $transport", "Liaison $transport"), mapOf("transport" to transport))
            handshake = viewModelScope.launch { delay(15000); fail(handshakeTimeoutReason()) }
        } catch (error: Exception) { fail(tr("Connection: ${error.javaClass.simpleName}", "Connexion : ${error.javaClass.simpleName}")) }
    }

    /**
     * Connection timeout. Through a gateway that is still reachable (health received), it is the robot that does not
     * answer over Bluetooth (off, out of range): say so, rather than suggesting the gateway is down.
     */
    private fun handshakeTimeoutReason(): String {
        val reach = GatewayHealthStore.reach.value
        return if (gatt is GatewayLink && reach?.reachable == true && reach.fresh())
            tr("Robot not answering over Bluetooth (off or out of range of the gateway); gateway reachable", "Robot sans réponse en Bluetooth (éteint ou hors de portée de la passerelle) ; passerelle joignable")
        else tr("Connection / GATT discovery timeout", "Timeout connexion / découverte GATT")
    }
    internal fun dispatch(source: RobotLink, block: () -> Unit) { handler.post {
        if (source !== gatt) return@post
        try { block() } catch (error: Exception) {
            event("exception", tr("BLE callback exception", "Exception callback BLE"), mapOf("type" to error.javaClass.name, "message" to error.message, "stack" to error.stackTraceToString()))
            fail(tr("BLE: ${error.message ?: error.javaClass.simpleName}", "BLE : ${error.message ?: error.javaClass.simpleName}"))
        }
    } }
    private val linkListener = object : RobotLink.Listener {
        override fun onEvent(link: RobotLink, kind: String, text: String, fields: Map<String, Any?>) = dispatch(link) {
            event(kind, text, fields + mapOf("sinceConnectMs" to SystemClock.elapsedRealtime() - connectRequestedAt, "closing" to closing))
            alerts.gatewayEvent(kind, text)
            if (kind == "gateway_" + GatewayProtocol.OTA_BEGIN_EVENT) routes.gatewayUpdateAnnounced(text)
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
            if (link is GatewayLink && !closing && GatewayLink.gatewayUnreachable(reason) &&
                !routes.gatewayUpdating()) alerts.alertGatewayLost(reason)
            fail(reason)
        }
        override fun onDiscovering(link: RobotLink) = dispatch(link) { update { it.copy(connection = ConnectionState.Discovering) } }
        override fun onLost(link: RobotLink, code: Int, reason: String) = dispatch(link) {
                if (!closing && routes.gatewayUpdateRestart(link, code)) {
                    // Announced restart (update): the gateway has written the safety stop; not a failure.
                    unexpectedLoss = true
                    event("gateway_update_restart", tr("Gateway updating: link closed by it after stopping the robot", "Passerelle en mise à jour : liaison fermée par elle après l'arrêt du robot"),
                        mapOf("code" to code, "reason" to reason, "resume" to (routes.gatewayUpdateResume?.isActive == true)))
                    status(if (routes.gatewayUpdateResume?.isActive == true) tr("Gateway update: robot stopped, mowing resumes when it is back", "Mise à jour de la passerelle : robot arrêté, reprise de la tonte à son retour")
                        else tr("Gateway update: robot stopped, reconnecting when it is back", "Mise à jour de la passerelle : robot arrêté, reconnexion à son retour"))
                } else if (!closing) {
                    unexpectedLoss = true
                    if (link is GatewayLink && GatewayLink.gatewayDisconnected(code)) alerts.alertGatewayLost(reason.replaceFirstChar { it.uppercase() })
                    alertLinkFault = alertMonitor.armed
                    alertCommunicationExpected = alertMonitor.armed
                    // Loss of the gateway (Wi-Fi), not of the robot's Bluetooth: on 2026-10-14, "BLE lost" misled the analysis.
                    val lost = if (link is GatewayLink && GatewayLink.gatewayDisconnected(code)) tr("Gateway lost", "Perte passerelle") else tr("BLE lost", "Perte BLE")
                    taskHistory.taskEvent(TaskEventType.LINK_LOST, "$lost: $reason", mapOf("code" to code))
                    alerts.alertRouteFailure(tr("$lost: return / resume interrupted", "$lost : retour / reprise interrompu"))
                    if(state.value.robotRoute.running || state.value.navTest.running || state.value.manualActive) alerts.alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, tr("$lost: physical STOP not guaranteed", "$lost : STOP physique non garanti"))
                    campaignFailure = tr("$lost unexpectedly ($code)", "$lost inattendue ($code)")
                    campaignFailure?.let { event("failure", it, mapOf("code" to code, "stopTransmitted" to false)) }
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
            RobotLinkService.start(getApplication())?.let { failure ->
                event("link_service_failed", tr("Background link service not started: ${failure.message}", "Service de liaison en arrière-plan non démarré : ${failure.message}"))
            }
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
                    if (now >= nextSummary) { campaign.diagnosticSnapshot(now); nextSummary = now + 5000 }
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
                if (taskEvents != null) blackBox.command(BlackBoxCommand(Clock.now(), op.label, op.setpoint?.linear, op.setpoint?.angular))
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
            if(op.label=="NATIVE_TASK_ZERO") recovery.nativeCancelWrittenAt=SystemClock.elapsedRealtime()
            if(op.label=="UNLOCK_ERROR_QUERY") recovery.unlockWrittenAt=SystemClock.elapsedRealtime()
            event("write_completed", tr("GATT write confirmed ${op.label} (does not prove a mechanical stop)", "Écriture GATT confirmée ${op.label} (ne prouve pas l'arrêt mécanique)"))
            if (op.finalStop) {
                stopPending = false
                stopAckCount++
                event("stop_confirmed_gatt", tr("Zero STOP confirmed by GATT, not mechanically", "STOP nul confirmé par GATT, pas mécaniquement"), mapOf(
                    "latencyMs" to SystemClock.elapsedRealtime() - stopRequestedAt, "ackCount" to stopAckCount))
                campaign.persistCampaign()
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
        val message = receivedFrames.decode(blufi, bytes, sensorProbe.isRunning) ?: return@dispatch
        if (ignoreTelemetry && message.hasSys() && message.sys.hasToappReportData()) {
            event("fault_injection_drop", tr("Report hidden locally to test the watchdog; radio still active", "Rapport masqué localement pour tester le watchdog ; radio toujours active"))
            return@dispatch
        }
        robotErrors.accept(message).filter(alerts::triageRobotError).forEach(::onRobotError)
        if (message.hasNav()) mapSyncReply(message.nav)
        onMapAndStation(message)
        onPerception(message)
        nativeMowing.receiveMowing(message)
        val previousTelemetry = reducer.value
        val accepted = reducer.accept(message, Clock.now(), SystemClock.elapsedRealtime())
        if (accepted) {
            state.value.selected?.let { robot ->
                val known = (state.value.lastKnown ?: LastKnownPosition(robot.address, robot.name)).accept(reducer.value)
                update { it.copy(lastKnown = known) }
                // A single IO writer; on a burst, keep the most recent snapshot.
                positionWrites.trySend(known)
            }
        }
        onProductType()
        update { it.copy(telemetry = reducer.value) }
        if(message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasRtk()) alertRtkAt = SystemClock.elapsedRealtime()
        if(accepted && message.sys.toappReportData.hasDev()) alertDeviceAt = SystemClock.elapsedRealtime()
        if (message.hasSys() && message.sys.hasToappReportData() && message.sys.toappReportData.hasDev()) nativeMowing.observeMowing()
        if (accepted) {
            lastTelemetry = SystemClock.elapsedRealtime()
            watchdog.recordValidReport(lastTelemetry)
            alertMonitor.report()
            alertLastTelemetryAt = lastTelemetry
            if (alertLinkFault && !alertMonitor.communicationLost) alerts.alert(AlertKind.RECOVERY, Severity.INFO)
            alertMonitor.condition(AlertKind.GATEWAY_LOST, null) { _, _ -> }
            alertLinkFault = false
            alertCommunicationExpected = true
            alerts.observeAlerts()
            if (message.sys.toappReportData.hasDev()) onDeviceState()
            telemetryStats.report(if (reportPrevious != 0L) lastTelemetry - reportPrevious else null)
            if (reportPrevious == 0L) event("first_telemetry", tr("First telemetry", "Première télémétrie"), mapOf("sinceConnectMs" to lastTelemetry - connectRequestedAt))
            reportPrevious = lastTelemetry
            if (message.sys.toappReportData.locationsCount > 0) {
                telemetryStats.position(if (positionPrevious != 0L) lastTelemetry - positionPrevious else null)
                positionPrevious = lastTelemetry
            }
            update { it.copy(telemetry = reducer.value, connection = ConnectionState.Receiving, ageMs = 0) }
            if (message.sys.toappReportData.locationsCount > 0) signalSurveys.recordLiveSignal()
            val t = reducer.value
            checkLift(message, t)
            recordTrails(message, previousTelemetry, t)
            onStateChange(previousTelemetry, t)
            logTelemetry(message, t)
        } else if (receivedFrames.lastWritten) { event("protobuf", tr("Message ${message.msgtype} seq=${message.seqs} outside telemetry", "Message ${message.msgtype} seq=${message.seqs} hors télémétrie")) }
    }
    /** Robot error or bumper contact: routed to every activity that reacts to it (route, moves, tests, mowing). */
    private fun onRobotError(error: RobotError) {
        if (LiftGuard.liftCode(error.code)) {
            val reason = tr("Lift reported by the robot: ${LiftGuard.liftLabel(error.code)} (${error.code})", "Soulèvement signalé par le robot : ${LiftGuard.liftLabel(error.code)} (${error.code})")
            if (liftGuard.moving(SystemClock.elapsedRealtime()) || autonomousRunning() || needsScreen()) liftDetected(reason, "error_code")
            else event("lift_reported", reason, mapOf("code" to error.code, "source" to error.source))
        }
        if(BumperEvents.confirmsContact(error) || BumperEvents.latchedFault(error)) update { it.copy(lastBumperCode = error.code, lastBumperAt = Clock.now()) }
        if(BumperEvents.confirmsContact(error)) {
            contactThisConnection = true
            obstaclePhotos.armObstaclePhoto(error.code)
            // Read long after it happened: the robot moved since, the contact is not placed at its current position.
            if (BumperEvents.placedLate(error, Clock.now())) event("contact_late", tr("Bumper contact read late: not placed on the map", "Contact pare-chocs lu en retard : non placé sur la carte"),
                mapOf("code" to error.code, "robotErrorTime" to error.timestamp, "ageS" to Clock.now() / 1000 - (error.timestamp ?: 0L)))
            else if (difficulties.manualContactContext()) difficulties.recordManualContact(error.code) else difficulties.recordContactOnReceipt(error.code)
        }
        if(BumperEvents.confirmsContact(error) || BumperEvents.latchedFault(error)) alertMonitor.bumper(SystemClock.elapsedRealtime(), BumperEvents.latchedFault(error)) { kind, severity -> alerts.alert(kind, severity, tr("Collision / robot fault ${error.code}", "Collision / défaut robot ${error.code}")) }

        if (state.value.robotRoute.running && !remoteLegActive) {
            val contact = BumperEvents.confirmsContact(error)
            // Proposal first: it uses the route's fused heading, cleared by the STOP.
            if (contact) safetyTests.proposeRouteCollision()
            // Bypass: immediate stop; the tracking turns the blades off, backs off and repairs the remaining route.
            val pose = if (contact && routeBypassArmed && routeContact == null) safetyTests.contactPose(error.code) else null
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
            else { alerts.alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, tr("Control stopped: robot error ${error.code}", "Pilotage arrêté : erreur robot ${error.code}")); stopManual(tr("Robot error ${error.code}", "Erreur robot ${error.code}")) }
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
            safetyTests.finishObstacle("CONTACT_DETECTED", tr("Collision reported (${error.code}): STOP requested; check the physical stop", "Collision rapportée (${error.code}) : STOP demandé ; vérifier l'arrêt physique"))
        } else if (state.value.teach.running) safetyTests.finishObstacle("ABORTED", tr("Robot error ${error.code}", "Erreur robot ${error.code}"))
        if (state.value.navTest.running) {
            if (BumperEvents.confirmsContact(error)) {
                // Move to a point with blades OFF: back-off, then a new path around the recorded obstacle.
                val pose = if (navMapPlan?.physicalGuard == true && !navConfig.tonte && !navConfig.tracing && navCourse == null && navContact == null)
                    safetyTests.contactPose(error.code) else null
                recovery.captureLocalIncident(error.code)
                if (pose != null && localIncidentPending && state.value.navTest.running) navContact = pose
                else stopNavTest(tr("Bumper contact (${error.code}): robot stopped, obstacle recorded", "Contact bumper (${error.code}) : robot arrêté, obstacle enregistré"))
            } else stopNavTest(tr("Robot error ${error.code}", "Erreur robot ${error.code}"))
        }
        if (state.value.mowing.running && (BumperEvents.confirmsContact(error) || BumperEvents.latchedFault(error))) {
            recovery.captureLocalIncident(error.code)
            requestStop(false, tr("Obstacle: native mission stopped, firmware route cannot be repaired locally", "Obstacle : mission native arrêtée, parcours firmware non réparable localement"))
        }
    }
    /** Map transfer in progress, then base position read from the robot messages. */
    private fun onMapAndStation(message: LubaMsg) {
        mapReader.accept(message)
        if (!mapReader.reading && state.value.mammotionMap.complete) {
            val station = stationReader.accept(message)
            if (station != null && station != state.value.mammotionMap.station) {
                // The cache only receives the Mammotion map, never the corrected map.
                val map = state.value.mammotionMap.copy(station = station)
                update { it.withMammotionMap(map) }
                cacheMammotionMap(map)
            }
        } else stationReader.accept(message)
    }
    /** Saves the Mammotion map of the selected robot off the main thread; a failure is logged. */
    internal fun cacheMammotionMap(map: RobotMap) {
        val id = state.value.selected?.address ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { mapCache.save(id, map) }.onFailure { handler.post { event("map_cache_error", tr("Cache failure: ${it.javaClass.simpleName}", "Échec du cache : ${it.javaClass.simpleName}")) } }
        }
    }
    /** Camera state, robot perception map and sensor probe slots carried by [message]. */
    private fun onPerception(message: LubaMsg) {
        if (cameraObserver.accept(message, Clock.now())) {
            val camera = cameraObserver.value
            if (message.hasMul() && message.mul.hasSetVideoAck()) event("camera", tr("Camera: vi_switch ${CameraRobotState.ackLabel(camera.lastAck)}", "Caméra : vi_switch ${CameraRobotState.ackLabel(camera.lastAck)}"), mapOf("code" to camera.lastAck))
            update { it.copy(camera = camera) }
        }
        perceptionMaps.accept(message, Clock.now(), reducer.value.robotState)?.let { frame ->
            update { it.copy(perceptionMap = frame) }
            event("perception_map", tr("Robot perception map: ${frame.info.label}", "Carte de perception du robot : ${frame.info.label}"),
                mapOf("format" to frame.info.format, "width" to frame.info.width, "height" to frame.info.height, "bytes" to frame.info.bytes,
                    "robotState" to frame.robotState, "count" to frame.count,
                    "imageBase64" to frame.image.takeIf { it.isNotEmpty() && it.size <= PerceptionMapObserver.LOG_BYTES }
                        ?.let { Base64.getEncoder().encodeToString(it) }))
        }
        if (sensorProbe.isRunning) {
            val changed = sensorProbe.accept(message, Clock.now())
            update { it.copy(sensorProbe = sensorProbe.snapshot()) }
            changed.forEach { (slot, raw) -> event("sensor_probe_slot", tr("${slot.label}: ${SensorSlot.stateLabel(raw)}", "${slot.label} : ${SensorSlot.stateLabel(raw)}"),
                mapOf("slot" to slot.name, "raw" to raw, "sensorStatus" to message.sys.toappReportData.dev.sensorStatus)) }
        }
    }
    /** Robot model reported for the first time or changed: saved and logged with its cutting height range. */
    private fun onProductType() {
        reducer.value.productType?.takeIf { it != state.value.productType }?.let { code ->
            state.value.selected?.address?.let { id -> productPreferences.edit { putString(id, code) } }
            update { it.copy(productType = code) }
            event("robot_product_type", tr("Robot model: $code (${reducer.value.productName ?: "?"}), cutting height ${state.value.cutHeights.label}",
                "Modèle du robot : $code (${reducer.value.productName ?: "?"}), hauteur de coupe ${state.value.cutHeights.label}"),
                mapOf("productType" to code, "productName" to reducer.value.productName, "cutMinMm" to state.value.cutHeights.minMm, "cutMaxMm" to state.value.cutHeights.maxMm))
        }
    }
    /** Accepted report carrying the device state: bumper rest, bumper health and blade state checks. */
    private fun onDeviceState() {
        devicePrevious = lastTelemetry; bladeDeviceAt = lastTelemetry
        observeBumperRest(reducer.value)
        if (state.value.teach.running && TeachSafety.bumperHealthAlert(guardSensors(reducer.value.sensorStatus)))
            safetyTests.finishObstacle("ABORTED", tr("Bumper health alert: STOP requested; contact not confirmed", "Alerte de santé bumper : STOP demandé ; contact non confirmé"))
        if (state.value.navTest.running && TeachSafety.bumperHealthAlert(guardSensors(reducer.value.sensorStatus)))
            stopNavTest(tr("Bumper health alert", "Alerte de santé bumper"))
        val bladeBits = (reducer.value.sensorStatus ?: 0) shr 9 and 7
        if (bladeOffWrittenAt > 0 && lastTelemetry >= bladeOffWrittenAt && bladeBits == 0) {
            bladeStopVerified = true; bladeMayBeOn = false; bladeOffPending = false
            update { it.copy(bladeStatus = tr("Blades OFF state received after the OFF command", "État lames OFF reçu après la commande OFF")) }
            if (bladeTestId.isNotBlank() && !bladeMayBeOn) blades.recordBladeResult(
                if (bladeOnObserved) "ON_AND_OFF_REPORTED" else "OFF_REPORTED_ON_UNCONFIRMED",
                tr("OFF reported after the command. ${if (bladeOnObserved) "ON state observed during the test." else "No ON state observed; rotation not demonstrated."}", "OFF rapporté après commande. ${if (bladeOnObserved) "État ON observé pendant le test." else "Aucun état ON observé ; rotation non démontrée."}"))
        }
        if (state.value.bladeTestActive) {
            if (bladeBits == 1) bladeOnObserved = true
            if (bladeBits > 1) stopBladeTest(tr("Raw blade state not recognized", "État lames brut non reconnu"))
            if (reducer.value.robotState !in listOf(11, 20) || !SelfCheck.nominal(reducer.value.selfCheck) || (guardSensors(reducer.value.sensorStatus) ?: 7) and 7 != 0) stopBladeTest(tr("Mammotion state/protection changed", "État/protection Mammotion changé"))
        }
    }
    /** Lift (sensors or wheel progress) and slipping, from the accepted report [t]. */
    private fun checkLift(message: LubaMsg, t: Telemetry) {
        val liftNow = SystemClock.elapsedRealtime()
        val sensorLift = t.sensorStatus?.let { liftGuard.sensors(it, liftNow) }
        val wheelLift = if (message.sys.toappReportData.locationsCount > 0 && t.x != null && t.y != null)
            liftGuard.position(Point(t.x, t.y), liftNow) else null
        (sensorLift ?: wheelLift)?.let { liftDetected(it, if (sensorLift != null) "sensor_status" else "wheel_progress") }
        if (sensorLift == null && wheelLift == null && liftGuard.slipping) difficulties.recordDifficulty(DifficultyKind.SLIP,
            tr("Slipping: %.0f %% of the usual progress", "Patinage : %.0f %% de la progression habituelle").format(I18n.locale, (liftGuard.progressRatio ?: 0.0) * 100))
    }
    /** Telemetry statistics, driven trace and navigation test trail, from the accepted report [t]. */
    private fun recordTrails(message: LubaMsg, previousTelemetry: Telemetry, t: Telemetry) {
        telemetryStats.observe(previousTelemetry, t)
        if (t.x != null && t.y != null) {
            // Applied to the trace current at update time: a pause or an erase requested meanwhile is kept.
            if (message.sys.toappReportData.locationsCount > 0 && state.value.driven?.paused == false) {
                val at = Clock.now()
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
                            (previous?.let { step -> hypot(sample.x - step.x, sample.y - step.y) } ?: 0.0)))
                }
            }
        }
    }
    /** State or protection change from [previousTelemetry] to [t]: logged, self-check details requested when blocked. */
    private fun onStateChange(previousTelemetry: Telemetry, t: Telemetry) {
        if (previousTelemetry.robotState != t.robotState || previousTelemetry.selfCheck != t.selfCheck || previousTelemetry.sensorStatus != t.sensorStatus) {
            event("robot_state_change", tr("State / protection change", "Changement état / protection"), mapOf("beforeState" to previousTelemetry.robotState,
                "state" to t.robotState, "selfCheck" to t.selfCheck, "selfCheckName" to SelfCheck.name(t.selfCheck), "sensorStatus" to t.sensorStatus))
            // Undocumented protection (25, on 2026-10-14): request the self-check details (read-only, response
            // logged as protobuf_decoded). Never during tracking handed over to the gateway (no write from the pilot).
            if (previousTelemetry.selfCheck != t.selfCheck && StopUnlock.blocked(t.selfCheck) && !remoteLegActive)
                send(commands.selfCheckInfo(), "SELF_CHECK_INFO")
            if (previousTelemetry.selfCheck != t.selfCheck && manualDrive.edgeLatchIgnored(t))
                event("off_map_latch_ignored", tr("Self-check 25 beyond the mapped zones or at a no-go zone: ignored for blade-OFF moves", "Self-check 25 hors des zones cartographiées ou contre une zone interdite : ignoré pour les déplacements lames arrêtées"),
                    mapOf("x" to t.x, "y" to t.y, "sensorStatus" to t.sensorStatus))
        }
    }
    private fun logTelemetry(message: LubaMsg, t: Telemetry) {
        event("telemetry", tr("State / position received", "État / position reçus"), mapOf("batteryPercent" to t.battery,
            "robotState" to t.robotState, "sensorStatus" to t.sensorStatus, "selfCheck" to t.selfCheck,
            "xMeters" to t.x, "yMeters" to t.y, "headingDegrees" to t.heading, "rawCompassHeadingDegrees" to t.rawHeading, "positionType" to t.positionType,
            "rtkStatus" to t.rtkStatus, "positioningLevel" to t.positioningLevel, "firmware" to t.firmware,
            "heightMoving" to t.heightMoving, "heightTargetMm" to t.heightTargetMm, "bladeHeightMm" to t.bladeHeightMm, "cutterRpm" to t.cutterRpm, "hasDeviceState" to message.sys.toappReportData.hasDev(), "hasPosition" to (message.sys.toappReportData.locationsCount > 0),
            "robotTimestamp" to message.timestamp, "positionRateHz" to t.positionRate))
    }
    /** [periodic]: command re-sent at a fixed interval, not queued if the previous one is not yet written. */
    internal fun send(payload: ByteArray, label: String, nativeReturn: Boolean = false, periodic: Boolean = false) {
        check(ExternalNavigationPolicy.allows(payload, nativeReturn)) { tr("Firmware navigation forbidden: use the local BLE route", "Navigation firmware interdite : utiliser le trajet BLE local") }
        if (gatt == null) return
        // Read-only mode (docs/features/supported-models.md): a model that is not validated only receives read and
        // stop commands. Last barrier, whatever the screen or the activity that built the command.
        val robotName = state.value.selected?.name
        // Model trial armed by the owner: the payloads of the three unlocking tests too, within the trial ceilings.
        val trialAllows = ModelTrial.active(robotName, SystemClock.elapsedRealtime()) && ReadOnlyPolicy.allowsTrial(payload, commands.navReceiver)
        if (!ModelSupport.motionValidated(robotName) && !ReadOnlyPolicy.allows(payload, commands.navReceiver) && !trialAllows) {
            readOnlyRefused++
            event("read_only_refused", tr("Command refused: robot model in read-only mode", "Commande refusée : modèle de robot en lecture seule"),
                mapOf("label" to label, "robotName" to robotName, "model" to RobotModel.detect(robotName).name,
                    "totalRefused" to readOnlyRefused, "payloadHex" to payload.hex()))
            ModelSupport.refusal(robotName)?.let { status(it) }
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
            val keepalive = state.value.viaGateway && KeepaliveLoss.keepalive(op is Operation.Rssi, (op as? Operation.Write)?.label)
            operationTimeout = viewModelScope.launch {
                delay(KeepaliveLoss.timeoutMs(state.value.viaGateway, keepalive, needsScreen(), autonomousRunning()))
                // Link keep-alive (sync, report subscription, RSSI) sent into a held gateway Wi-Fi cut: its confirmation
                // can only come back after the reconnection, which is awaited. Every other write keeps the strict timeout.
                val started = operationStarted
                while (current === op && !closing &&
                    KeepaliveLoss.waiting(keepalive, started, SystemClock.elapsedRealtime(), gatewayOutageSince, gatewayResumedAt)) delay(500)
                if (current === op) {
                    // Reconnected without confirmation: the frame stayed in the cut link. Dropped; this is not a
                    // robot failure.
                    if (!closing && KeepaliveLoss.dropped(keepalive, started, gatewayResumedAt)) {
                        event("gateway_keepalive_lost", tr("Link keep-alive lost in a gateway Wi-Fi cut: dropped", "Entretien de liaison perdu dans une coupure Wi-Fi de la passerelle : abandonné"),
                            mapOf("label" to ((op as? Operation.Write)?.label ?: "RSSI"), "waitMs" to SystemClock.elapsedRealtime() - started))
                        operationTimeout = null; current = null
                        pump()
                        return@launch
                    }
                    // Android may keep an operation busy: do not claim STOP can be sent.
                    val waited = tr("%s without confirmation in %.1f s", "%s sans confirmation en %.1f s").format(I18n.locale, (op as? Operation.Write)?.label ?: "RSSI",
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
        alerts.intentionalAlertStop()
        campaign.abortCampaign(tr("Manual STOP", "STOP manuel"))
        if (closing) return
        requestStop(true, tr("Manual STOP", "STOP manuel"), intentional=true)
    }
    /** Disconnection requested by the user: automatic reconnection through the gateway waits for a new manual search. */
    internal var userDisconnected = false
    /**
     * Control through the gateway: as long as the robot is not linked (gateway still unreachable, robot not yet
     * taken by the Redmi), restarts the automatic search so every mode unlocks as soon as the gateway holds it.
     */
    fun retryGatewayLink() {
        val s = state.value
        if (userDisconnected || gatt != null || s.scanning || s.connection != ConnectionState.Disconnected || s.campaign.running || s.bladeTestActive) return
        if (reconnectTimer?.isActive == true || closing) return
        val config = GatewayConfig.read(getApplication())
        if (config.role != GatewayRole.CLIENT || !config.validPilotCode()) return
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
            val armed = ModelTrial.arm(name, SystemClock.elapsedRealtime(), sensors)
            event("model_trial_armed", tr("Model trial armed: command cutoff, low-speed joystick and blade test only", "Essai du modèle armé : coupure des commandes, joystick lent et essai des lames seulement"),
                mapOf("robotName" to name, "model" to RobotModel.detect(name).name, "firmware" to state.value.telemetry.firmware,
                    "durationMs" to ModelTrial.DURATION_MS, "maxLinear" to ModelTrial.MAX_LINEAR,
                    "maxAngular" to ModelTrial.MAX_ANGULAR, "untilMs" to armed.untilMs,
                    "sensorStatus" to sensors, "bumperRest" to armed.bumperRest))
            null
        } catch (error: IllegalArgumentException) { error.message }
    }

    /**
     * `sensor_status` seen by the safety guards: bumper slot at its resting value cleared, learnt on this connection on a
     * validated model ([fr.luba.poc.protocol.BumperRest], DumbyLuba#1), or kept by the armed trial of another model
     * ([fr.luba.poc.protocol.ModelTrial.sensors]). The raw value stays in the telemetry, the log and Service → Sensors.
     */
    internal fun guardSensors(sensors: Int?): Int? =
        BumperRest.guard(state.value.selected?.name, sensors, bumperRest.value, SystemClock.elapsedRealtime())

    /**
     * Bumper slot at rest on a validated model ([fr.luba.poc.protocol.BumperRest]): learnt when it reads "alert" (1) from
     * the connection on, robot ready, no collision lock nor contact, no motion driven by the app; forgotten for the rest
     * of the connection as soon as it reads another value. Logged with the raw `sensor_status`.
     */
    private fun observeBumperRest(t: Telemetry) {
        if (!ModelSupport.motionValidated(state.value.selected?.name)) return
        val idle = !alerts.motionActive() && !state.value.borderFrontal.running && !remoteLegActive
        when (bumperRest.observe(t.sensorStatus, t.robotState, t.selfCheck, idle, contactThisConnection)) {
            BumperRest.Change.LEARNT -> {
                update { it.copy(bumperRest = bumperRest.value) }
                event("bumper_rest_learnt", tr("Bumper reads \"alert\" at rest since the connection, no contact: kept as this robot's resting value for the connection",
                    "Pare-chocs en « alerte » au repos depuis la connexion, sans contact : gardé comme valeur de repos du robot pour la connexion"),
                    mapOf("sensorStatus" to t.sensorStatus, "bumperRest" to bumperRest.value, "robotState" to t.robotState, "selfCheck" to t.selfCheck))
            }
            BumperRest.Change.FORGOTTEN -> {
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
        val ended = ModelTrial.end() ?: return
        stopManual(tr("Model trial ended", "Essai du modèle terminé"))
        stopSilenceTest(tr("Model trial ended", "Essai du modèle terminé"))
        if (state.value.bladeTestActive) stopOwnedBladeTest(tr("Model trial ended", "Essai du modèle terminé"))
        event("model_trial_ended", reason, mapOf("robotName" to ended.robotName, "durationMs" to SystemClock.elapsedRealtime() - ended.sinceMs))
    }

    fun disconnect() {
        endModelTrial(tr("Disconnection", "Déconnexion"))
        userDisconnected = true
        routes.gatewayUpdateResume?.cancel(); routes.gatewayUpdateResume = null
        wantLink = false; reconnectTimer?.cancel(); reconnectTimer = null
        alerts.intentionalAlertStop()
        campaign.abortCampaign(tr("User disconnection", "Déconnexion utilisateur"))
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
        val keep = (keepConnection || RobotLinkService.active.value) && gatt != null && !closing && state.value.ready
        if (keep) {
            // Link kept by the foreground service: only hold-to-run activities stop.
            if (needsScreen()) {
                alerts.intentionalAlertStop()
                campaign.abortCampaign(tr("Application in the background", "Application hors premier plan"))
                requestStop(false, tr("Application in the background: hold-to-run activity stopped", "Application hors premier plan : activité à maintien arrêtée"), intentional=true)
            } else if (autonomousRunning()) {
                event("lifecycle", tr("Automatic mission continued in the background; link kept by the service", "Mission automatique poursuivie en arrière-plan ; liaison maintenue par le service"))
            }
            return
        }
        alerts.intentionalAlertStop()
        campaign.abortCampaign(tr("Application in the background", "Application hors premier plan"))
        // A relay connection may still be negotiating GATT when the screen turns off.
        // No trial can run before ready; leave its handshake alive in relay mode.
        if (gatt != null && !closing && (!keepConnection || state.value.ready)) {
            requestStop(!keepConnection, tr("Application in the background", "Application hors premier plan"), intentional=true)
        }
    }
    internal fun fail(reason: String) {
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

    /** Immediate stop on lift: critical alert, zero-speed STOP and end of any activity in progress. */
    private fun liftDetected(reason: String, source: String) {
        val t = state.value.telemetry
        // Joystick stopped by the wheels without progress: often the bumper pushing against an object. Its 1014 comes with
        // the next error read: same grace window as a collision protection, so the contact is still logged on the map.
        val manualStall = source == "wheel_progress" && state.value.manualActive
        event("lift_detected", reason, mapOf("source" to source, "sensorStatus" to t.sensorStatus, "x" to t.x, "y" to t.y,
            "robotState" to t.robotState, "selfCheck" to t.selfCheck))
        liftGuard.reset()
        alerts.alert(AlertKind.LIFTED, Severity.CRITICAL, reason)
        requestStop(false, reason)
        if (manualStall) {
            manualContactUntil = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
            if (state.value.ready && !closing) send(commands.errorQuery(), "MANUAL_STALL_ERRORS")
        }
    }
    internal fun requestStop(close: Boolean, reason: String, intentional: Boolean = false) {
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
        taskHistory.taskEvent(TaskEventType.STOP, reason, mapOf("intentional" to intentional))
        if (intentional) alerts.intentionalAlertStop()
        // Stop on a terrain difficulty (blockage, wheel slip, lift, deviation…): recorded on the map.
        if (!intentional) DifficultyClassifier.fromReason(reason)?.let { difficulties.recordDifficulty(it, reason) }
        val external = state.value.robotRoute.running
        if (!alertLinkFault) alertCommunicationExpected = false
        if(!intentional && (external || state.value.navTest.running || state.value.manualActive)) {
            alerts.alertRouteFailure(reason)
            alerts.alertMotionFailure(reason)
            alerts.alert(AlertKind.UNEXPECTED_STOP, Severity.CRITICAL, reason)
        }
        // Manual mode held for bumper detection (obstacle test, navigation, mowing): leave it on STOP.
        val bumperManualMode = (state.value.teach.running && obstacleManualMode) || navBumperMode || state.value.telemetry.robotState == 20
        // Captured before the route, tests and height adjustment are cancelled: their target if one owned the deck.
        val stopHeightMm = holdHeightMm()
        navBumperMode = false
        confirmedHeading = null
        routes.cancelRobotRoute(reason)
        recovery.backOffJob?.cancel()
        preparationJob?.cancel(); preparationJob = null
        stopManual(reason, sendStop = false)
        abortSilenceTest(reason)
        val wasReady = state.value.ready
        stopReason = reason
        bladeJob?.cancel(); bladeJob = null
        update { it.copy(bladeTestActive = false) }
        if (bladeTestId.isNotBlank()) blades.recordBladeResult("INTERRUPTED_OFF_UNCONFIRMED", reason)
        stopHeightSequence(reason)
        blades.abortBladeHeight(reason)
        val nativeMission = !external && (state.value.mowing.running || state.value.telemetry.robotState in setOf(13, 14))
        nativeMowing.interruptMowing(reason)
        navigation.abandonNav(reason, userInitiated = intentional)
        safetyTests.finishObstacle("ABORTED", reason, sendStop = false)
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
        if (bumperManualMode || bladeMayBeOn || (ModelTrial.testAllowed(state.value.selected?.name, SystemClock.elapsedRealtime()) && state.value.telemetry.sensorStatus?.let { ((it shr 9) and 7) != 0 } == true)) {
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
        val result = MotorWatchdogResult.of(id, Clock.now(), state.value.telemetry.firmware, state.value.silenceTest.linearRaw,
            probe.abort(tr("$reason: inconclusive test", "$reason : essai non concluant")))
        val kept = safetyTests.recordWatchdog(result)
        update { it.copy(motorWatchdog = kept, silenceTest = it.silenceTest.copy(running = false, phase = "", status = tr("Inconclusive — ${result.detail}", "Non concluant — ${result.detail}"))) }
        event("silence_test_result", tr("Inconclusive", "Non concluant"), mapOf("outcome" to result.outcome, "detail" to result.detail))
    }

    private fun release() {
        // Link lost while a mowing is recharging: resume on its own once the link is back ([DockResume]).
        val dockResume = unexpectedLoss && (state.value.viaGateway || routes.dockResumeAnyLink) &&
            DockResume.wanted(routes.rechargeCycle, state.value.robotRoute.running, routes.routeCheckpoint != null)
        val resumeEngine = routes.checkpointEngine
        abortSilenceTest(tr("Link closed", "Liaison fermée"))
        routes.cancelRobotRoute(tr("Link closed", "Liaison fermée"))
        if (dockResume && resumeEngine != null) routes.planDockResume(resumeEngine, tr("Link closed", "Liaison fermée"))
        preparationJob?.cancel(); preparationJob = null
        stopManual(tr("Link closed", "Liaison fermée"), sendStop = false)
        mapReader.cancel()
        periodic?.cancel(); handshake?.cancel(); operationTimeout?.cancel()
        periodic = null; handshake = null; operationTimeout = null
        queue.clear(); current = null; stopPending = false
        val old = gatt; gatt = null
        try { old?.close() } catch (_: Exception) { /* previous link already closed */ }
        closing = false
        update { it.copy(connection = ConnectionState.Disconnected, ready = false, viaGateway = false, rssi = null, telemetry = it.telemetry.copy(positionRate = 0.0)) }
        bladeJob?.cancel(); bladeJob = null
        update { it.copy(bladeTestActive = false) }
        if (bladeTestId.isNotBlank()) blades.recordBladeResult("LINK_CLOSED_OFF_UNCONFIRMED", tr("Link closed; physical stop required if in doubt", "Liaison fermée ; arrêt physique requis si doute"))
        stopHeightSequence(tr("Link closed", "Liaison fermée"))
        blades.abortBladeHeight(tr("Link closed", "Liaison fermée"))
        nativeMowing.interruptMowing(tr("Link closed", "Liaison fermée"))
        navigation.abandonNav(tr("Link closed", "Liaison fermée"))
        safetyTests.finishObstacle("ABORTED", tr("Link closed", "Liaison fermée"), sendStop = false)
        borderFrontal.finishBorder("ABORTED", tr("Link closed", "Liaison fermée"), sendStop = false)
        bladeMayBeOn = false; bladeOffPending = false; bladeStopVerified = false
        event("disconnect", tr("GATT closed; no command resumed after a failure", "GATT fermé ; aucune commande reprise après panne"), mapOf("reason" to stopReason))
        val lost = unexpectedLoss; unexpectedLoss = false
        if (lost && wantLink && !state.value.campaign.running && expectedFailure == null && scheduleReconnect()) return
        wantLink = false
        RobotLinkService.stop(getApplication())?.let { failure ->
            event("link_service_failed", tr("Background link service not stopped: ${failure.message}", "Service de liaison en arrière-plan non arrêté : ${failure.message}"))
        }
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

    internal fun purgeQueuedCommands() {
        val continuation = (current as? Operation.Write)?.let { active -> queue.filterIsInstance<Operation.Write>().filter { it.batch == active.batch } }.orEmpty()
        queue.clear(); queue.addAll(continuation)
    }

    internal var silenceJob: Job? = null
    internal var silenceProbe: CommandSilenceProbe? = null

    internal class NavAbort(val motif: String) : Exception(motif)

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

    fun setTrajectorySpeed(percent: Int) {
        require(percent in 10..100)
        update { it.copy(trajectorySpeedPercent = percent) }
        speedPreferences.edit { putInt("percent", percent) }
        event("trajectory_speed", tr("Speed changed without automatic resume", "Vitesse modifiée sans reprise automatique"), mapOf("percent" to percent, "linearRaw" to PathFollower.linearFor(percent), "angularLimitRaw" to PathFollower.angularLimit(percent)))
    }

    // ───────────── Frontal border test (Service → Obstacle tests → Border) ─────────────

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
        update { it.copy(driven = DrivenTrace(kind, Clock.now())) }
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
    val mapSync: MapSyncRunner by lazy { MapSyncRunner(MapSyncLink(), viewModelScope) }

    /** Current differences between the robot map and the corrected map, traces checked (computed off the main thread). */
    suspend fun mapSyncDifferences(): List<SyncItem> = MapSyncLink().differences()

    /** Reason why the sync cannot start now, null if it can. */
    fun mapSyncRefusal(): String? = MapSyncLink().refusal()

    /** Last reply of the robot to a map writing command (logged, shown by the sync page). */
    private fun mapSyncReply(nav: MctrlNavProto.MctlNav) {
        if (!mapSync.state.value.running) return
        if (nav.hasToappGetCommondataAck() && !mapReader.reading) {
            val ack = nav.toappGetCommondataAck
            if (ack.action in setOf(0, 1, 6, 7)) event("map_sync_reply", tr("Robot reply to a map command", "Réponse du robot à une commande de carte"),
                mapOf("action" to ack.action, "type" to ack.type, "result" to ack.result, "hash" to ack.hash, "robotState" to state.value.telemetry.robotState))
        }
        if (nav.hasToappBstate()) event("map_sync_border_state", tr("Recording state reported by the robot", "État d'enregistrement signalé par le robot"),
            mapOf("bdstate" to nav.toappBstate.bdstate, "robotState" to state.value.telemetry.robotState))
    }

    private inner class MapSyncLink : MapSyncPort {
        override fun refusal(): String? {
            val ui = state.value
            return when {
                !ModelSupport.motionValidated(ui.selected?.name) -> tr("Writing to the robot map is reserved for a validated LUBA 2 (Luba-VS, Luba-VP)",
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

        private fun base(map: RobotMap): MapBase? = runCatching { BaseStore(getApplication(), map).resolve(map) }.getOrNull()

        private fun station(map: RobotMap): Point? = base(map)?.point ?: map.station?.point

        /** Station kept out of the outlines: its safety zone (the moves neither enter nor start there) plus a margin. */
        private fun stationClearance(map: RobotMap): Double = base(map)?.takeIf { it.entryHeadingDegrees != null }?.let {
            maxOf(MapSyncPlanner.STATION_CLEARANCE_M, TrajectoryPlanner.baseSafetyRadius(it.approachDistanceM) + MapSyncPlanner.STATION_MARGIN_M)
        } ?: MapSyncPlanner.STATION_CLEARANCE_M

        private fun accesses(map: RobotMap): List<List<Point>> = runCatching { BaseStore(getApplication(), map).accesses() }.getOrDefault(emptyList())

        private fun check(map: RobotMap, obstacles: List<LocalObstacle>, accesses: List<List<Point>>): (List<Point>) -> String? = { trace ->
            try { PointPlanner.tracePlan(map, trace, obstacles, accesses); null }
            catch (e: IllegalArgumentException) { e.message ?: e.javaClass.simpleName }
            catch (e: IllegalStateException) { e.message ?: e.javaClass.simpleName }
        }

        override suspend fun differences(): List<SyncItem> {
            val ui = state.value
            val station = station(ui.robotMap)
            val clearance = stationClearance(ui.robotMap)
            val accesses = accesses(ui.robotMap)
            return withContext(Dispatchers.Default) {
                MapSyncPlanner.differences(ui.mammotionMap, ui.correctedMap, ui.mapCorrections, station = station,
                    check = check(ui.robotMap, ui.obstacles, accesses), stationClearanceM = clearance)
            }
        }

        override fun recreate(shape: MapShape): SyncItem? {
            val ui = state.value
            return MapSyncPlanner.recreate(shape, station = station(ui.robotMap), check = check(ui.robotMap, ui.obstacles, accesses(ui.robotMap)),
                stationClearanceM = stationClearance(ui.robotMap))
        }

        private fun config(drawState: Int?) = NavConfig(pointDestination = true, linearSpeed = PathFollower.linearFor(state.value.trajectorySpeedPercent)
            .let { if (drawState != null) minOf(it, MapSyncPlanner.TRACE_LINEAR) else it },
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
                val reason = drive(null) { navigation.startMapNavigation(config(null), target) } ?: return null
                // Interrupted after it started: no other attempt. Refused before moving: next point.
                if (started) return reason
                event("map_sync_approach_refused", reason, mapOf("x" to target.x, "y" to target.y))
                refusal = reason
            }
            return refusal ?: tr("no point to drive to", "aucun point à rejoindre")
        }

        override suspend fun startRecording(kind: SyncKind): String? {
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
            return drive(null) { navigation.startMapNavigation(config(null), preset = plan.copy(alignAtStart = true)) }
        }

        override suspend fun driveTrace(trace: List<Point>, kind: SyncKind, progress: (Double) -> Unit): String? {
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
            return drive(progress) { navigation.startMapNavigation(config(kind.drawState), preset = plan.copy(alignAtStart = true)) }
        }

        override suspend fun endRecording(kind: SyncKind): String? {
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

        override suspend fun deleteElement(kind: SyncKind, hash: Long): String? {
            if (!state.value.ready || closing) return tr("robot disconnected", "robot déconnecté")
            send(commands.deleteMapElement(kind.type, hash), "MAP_SYNC_DELETE type=${kind.type}")
            event("map_sync_delete", tr("Deletion of a map element requested", "Suppression d'un élément de carte demandée"), mapOf("type" to kind.type, "hash" to hash))
            delay(2000)
            return null
        }

        override suspend fun readMap(): RobotMap? {
            delay(1500)
            mapReader.refusal()?.let { event("map_sync_read_refused", it); return null }
            fetchMap()
            val deadline = SystemClock.elapsedRealtime() + RobotMapReader.MAX_READ_MS + 15_000
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
        difficulties.publishDifficulties(state.value.difficulties.events, persist = false)
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
        val hidden = removed?.let { PresumedObstacles.hiddenContacts(events, it, kept, Clock.now()) }.orEmpty()
            .map { it.id }.toSet()
        obstacleStore.save(kept)
        update { it.copy(obstacles = kept) }
        if (hidden.isEmpty()) difficulties.publishDifficulties(events, persist = false)
        else difficulties.publishDifficulties(events.map { if (it.id in hidden) it.copy(dismissed = true) else it }, persist = true)
        event("obstacle_deleted", tr("Local obstacle deleted", "Obstacle local supprimé"), mapOf("id" to id, "contactsDismissed" to hidden.size))
    }

    /** Presumed obstacle dismissed (object removed, false contacts): all its contacts are dismissed at once. */
    fun dismissPresumedObstacle(id: String) {
        val events = state.value.difficulties.events
        val contacts = PresumedObstacles.contactsOf(events, id, Clock.now()).map { it.id }.toSet()
        if (contacts.isEmpty()) return
        difficulties.publishDifficulties(events.map { if (it.id in contacts) it.copy(dismissed = true) else it }, persist = true)
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
        val proposal = TeachSafety.pointedProposal(TeachContact(x, y, heading, Clock.now()), ObstacleKind.OTHER.defaultRadiusM)
        update { it.copy(teach = it.teach.copy(proposal = proposal, status = tr("Obstacle placed in front of the robot: adjust it, then save", "Obstacle placé devant le robot : ajustez-le puis enregistrez"))) }
        event("obstacle_pointed", tr("Obstacle pointed at without contact", "Obstacle désigné sans contact"), mapOf("x" to proposal.estimated.x, "y" to proposal.estimated.y, "heading" to heading))
    }

    override fun onCleared() {
        // Continuous map: latest measurements (under 30 s) written before the end; the background writer stops with the model.
        signalSurveys.saveOnClose()
        PhoneWifi.unlisten(phoneWifiListener)
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
