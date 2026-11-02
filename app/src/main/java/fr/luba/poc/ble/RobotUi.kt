package fr.luba.poc.ble

import fr.luba.poc.camera.CameraRobotState
import fr.luba.poc.camera.ObstaclePhotoUi
import fr.luba.poc.diagnostics.CampaignUi
import fr.luba.poc.diagnostics.MotorWatchdogResult
import fr.luba.poc.diagnostics.NavSample
import fr.luba.poc.diagnostics.PerceptionMapFrame
import fr.luba.poc.diagnostics.SensorProbeSnapshot
import fr.luba.poc.diagnostics.TestResult
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.history.MowingRecord
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.CorrectedMap
import fr.luba.poc.map.DrivenTrace
import fr.luba.poc.map.LastKnownPosition
import fr.luba.poc.map.MapCorrection
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.SignalSurvey
import fr.luba.poc.mowing.MowUi
import fr.luba.poc.obstacles.BorderFrontalUi
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.TeachProposal
import fr.luba.poc.planning.ZoneOrientation
import fr.luba.poc.protocol.CutHeightRange
import fr.luba.poc.protocol.RobotErrorReport
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.MissionCheckpoint
import fr.luba.poc.simulation.PointResume
import fr.luba.poc.simulation.RobotRouteUi

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
fun RobotUi.withMammotionMap(raw: RobotMap, corrections: List<MapCorrection> = mapCorrections): RobotUi =
    withCorrectedMap(raw, MapCorrections.apply(raw, corrections), corrections)

/**
 * Same as [withMammotionMap] with the corrections already applied ([corrected] = `MapCorrections.apply(raw,
 * corrections)`): a screen keeps that result across state emissions instead of rebuilding the map every second.
 */
fun RobotUi.withCorrectedMap(raw: RobotMap, corrected: CorrectedMap, corrections: List<MapCorrection> = mapCorrections): RobotUi =
    copy(mammotionMap = raw, robotMap = corrected.map, mapCorrections = corrections, correctedMap = corrected)

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
    val perceptionMap: PerceptionMapFrame? = null,
    /** Video state read from telemetry (fpv_info, network used, vi_switch acknowledgement). */
    val camera: CameraRobotState = CameraRobotState(),
    /** Obstacle photo after a bumper contact (pending request, setting, saved photos). */
    val obstaclePhoto: ObstaclePhotoUi = ObstaclePhotoUi(),
    /** Progress of the last unfinished local mowing (resume possible), for the selected robot. */
    val missionCheckpoint: MissionCheckpoint? = null, val checkpointSaveError: Boolean = false,
    /** Move to a point in progress or interrupted (resume possible), for the selected robot. */
    val pointResume: PointResume? = null,
    val silenceTest: SilenceTestUi = SilenceTestUi(),
    /** Last command cutoff test of the selected robot. */
    val motorWatchdog: MotorWatchdogResult? = null,
    /** History of real mowings (most recent first), mowing currently being measured and report to show. */
    val mowingHistory: List<MowingRecord> = emptyList(),
    /** Directions last mowed per zone ("Change every session", [fr.luba.poc.planning.ZoneOrientationStore]). */
    val zoneOrientations: Map<Long, ZoneOrientation> = emptyMap(),
    val mowingRecord: MowingRecord? = null,
    val mowingReport: MowingRecord? = null,
    /** History task opened in detail (track replay), by identifier. */
    val taskDetail: String? = null,
    /** Difficulties encountered (contacts, blockages, wheel slip, lifts…), presumed obstacles and settings. */
    val difficulties: DifficultyUi = DifficultyUi(),
    /** Gateway phone fallen off the robot, detected by the gateway from its Bluetooth signal: where to pick it up. */
    val gatewayDrop: GatewayDrop? = null,
) {
    /** Cutting heights of the connected robot, from its model code (H versions: 55–100 mm, [fr.luba.poc.protocol.CutHeightRange]). */
    val cutHeights: CutHeightRange get() =
        CutHeightRange.forRobot(selected?.name ?: lastKnown?.robotName, telemetry.productType ?: productType, telemetry.bladeHeightMm)

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
