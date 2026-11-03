package fr.luba.poc.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import fr.luba.poc.auth.CloudReturnUi
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.diagnostics.MapNavPlan
import fr.luba.poc.i18n.tr
import fr.luba.poc.planning.PlanningResult
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.planning.StartOverride
import fr.luba.poc.planning.StartRequest
import fr.luba.poc.planning.ZoneStartStore
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MissionResume
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SpeedReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * State holders of the map screen. MapScreen is a single Compose method: every `var x by remember { mutableStateOf() }`
 * stays live in one Dalvik register for the whole method, and every lambda capturing it copies it into a contiguous
 * register range. Grouping the states in a few objects keeps MapScreen well under the register limit (see
 * docs/ROBOT_ROUTE_BLE.md, "Map crash"; check: tools/check-dex-registers.py). Same states, same saved subset as before.
 */

/** Sheets, dialogs and pages opened from the map, and the zone choice in progress. */
@Stable
internal class MapSheets {
    // Kept across a configuration change (Fold folded or unfolded): saved by [saver].
    var signalPanel by mutableStateOf(false)
    var taskHistory by mutableStateOf(false)
    /** Zone choice directly on the map (not in a dialog hiding it). */
    var selectingZones by mutableStateOf(false)
    var advancedOpen by mutableStateOf(false)
    var statusPanel by mutableStateOf(false)
    /** Prepared task: mowing (Mow) or dedicated survey route (opened from Service → Signal surveys). */
    var surveyTask by mutableStateOf(false)

    // Closed again after a configuration change, as before.
    var simulationDialog by mutableStateOf(false)
    var simulationSpeedMenu by mutableStateOf(false)
    var signalHistoryOpen by mutableStateOf(false)
    var calibrationOpen by mutableStateOf(false)
    var relief3dOpen by mutableStateOf(false)
    var mowingDialog by mutableStateOf(false)
    var baseDialog by mutableStateOf(false)
    var mapActions by mutableStateOf(false)
    /** Local corrections editor (layer laid on the Mammotion map), opened from the Map sheet. */
    var correcting by mutableStateOf(false)
    var discardConfirm by mutableStateOf(false)
    /** Start point being picked on the route preview. */
    var pickingStart by mutableStateOf(false)

    companion object {
        val saver: Saver<MapSheets, List<Boolean>> = Saver(
            save = { listOf(it.signalPanel, it.taskHistory, it.selectingZones, it.advancedOpen, it.statusPanel, it.surveyTask) },
            restore = { saved ->
                MapSheets().apply {
                    signalPanel = saved[0]; taskHistory = saved[1]; selectingZones = saved[2]
                    advancedOpen = saved[3]; statusPanel = saved[4]; surveyTask = saved[5]
                }
            },
        )
    }
}

@Composable
internal fun rememberMapSheets(): MapSheets = rememberSaveable(saver = MapSheets.saver) { MapSheets() }

/**
 * Settings of the mowing or survey task being prepared, and of its simulation. Starts from the last saved task
 * ([MowingTaskStore]); the same subset as before is kept across a configuration change.
 */
@Stable
internal class MowingTaskForm(saved: MowingTaskSettings) {
    // Simulation settings.
    var initialProgress by mutableFloatStateOf(0f)
    var initialBattery by mutableFloatStateOf(100f)
    var autonomy by mutableFloatStateOf(60f)
    var chargeTime by mutableFloatStateOf(30f)
    // Task settings.
    var chargeLow by mutableFloatStateOf(saved.chargeLow)
    var chargeHigh by mutableFloatStateOf(saved.chargeHigh)
    var returnReserve by mutableFloatStateOf(saved.returnReserve)
    var spacing by mutableFloatStateOf(saved.spacingM)
    var mowingHeight by mutableFloatStateOf(saved.heightMm)
    /** Tall grass: successive passes lower and lower down to the cutting height ([CutPasses]). */
    var heightPasses by mutableIntStateOf(saved.heightPasses)
    var firstPassHeight by mutableFloatStateOf(saved.firstPassHeightMm)
    var mowingSpeed by mutableFloatStateOf(saved.speedMps)
    /** Task pages: 1 settings, 2 route preview. */
    var mowingStep by mutableIntStateOf(0)
    var signalGridSpacing by mutableFloatStateOf(3f)
    var borderPasses by mutableIntStateOf(saved.borderPasses)
    var noGoPasses by mutableIntStateOf(saved.noGoPasses)
    var bordersFirst by mutableStateOf(saved.bordersFirst)
    var nearbyNoGo by mutableStateOf(saved.nearbyNoGo)
    var turnStyle by mutableStateOf(saved.turnStyle)
    /** Direction of the passes and angle of the manual direction ([OrientationMode]). */
    var orientation by mutableStateOf(saved.orientation)
    var manualAngle by mutableIntStateOf(saved.manualAngleDeg)
    /** Spiral mode: from the edge or from the centre ([SpiralDirection]). */
    var spiralDirection by mutableStateOf(saved.spiralDirection)
    /** Zamboni mode: blocks ordered in Zamboni ([ZamboniBlocks]). */
    var zamboniBlocks by mutableStateOf(saved.zamboniBlocks)

    // Not saved across a configuration change (as before).
    var margin by mutableFloatStateOf(0.6f)
    var mode by mutableStateOf(saved.mode)
    /** Last chosen mowing mode: restored when leaving a survey route, and the only one remembered. */
    var mowingMode by mutableStateOf(saved.mode)
    var borders by mutableStateOf(true)
    var cutWidth by mutableFloatStateOf(Geometry.CUT_WIDTH_M.toFloat())

    /** Settings written for the next task ([MowingTaskStore]). */
    fun settings(): MowingTaskSettings = MowingTaskSettings(heightMm = mowingHeight, speedMps = mowingSpeed, mode = mowingMode,
        spacingM = spacing, borderPasses = borderPasses, noGoPasses = noGoPasses, bordersFirst = bordersFirst, nearbyNoGo = nearbyNoGo,
        chargeLow = chargeLow, chargeHigh = chargeHigh, heightPasses = heightPasses, firstPassHeightMm = firstPassHeight,
        returnReserve = returnReserve, turnStyle = turnStyle, orientation = orientation, manualAngleDeg = manualAngle,
        spiralDirection = spiralDirection, zamboniBlocks = zamboniBlocks)

    companion object {
        fun saver(saved: MowingTaskSettings): Saver<MowingTaskForm, List<Any>> = Saver(
            save = {
                listOf(it.initialProgress, it.initialBattery, it.autonomy, it.chargeTime, it.chargeLow, it.chargeHigh, it.spacing,
                    it.mowingHeight, it.heightPasses, it.firstPassHeight, it.mowingSpeed, it.mowingStep, it.signalGridSpacing,
                    it.borderPasses, it.noGoPasses, it.bordersFirst, it.nearbyNoGo, it.returnReserve, it.turnStyle.name,
                    it.orientation.name, it.manualAngle, it.spiralDirection.name, it.zamboniBlocks.name)
            },
            restore = { s ->
                MowingTaskForm(saved).apply {
                    initialProgress = s[0] as Float; initialBattery = s[1] as Float; autonomy = s[2] as Float
                    chargeTime = s[3] as Float; chargeLow = s[4] as Float; chargeHigh = s[5] as Float; spacing = s[6] as Float
                    mowingHeight = s[7] as Float; heightPasses = s[8] as Int; firstPassHeight = s[9] as Float
                    mowingSpeed = s[10] as Float; mowingStep = s[11] as Int; signalGridSpacing = s[12] as Float
                    borderPasses = s[13] as Int; noGoPasses = s[14] as Int; bordersFirst = s[15] as Boolean; nearbyNoGo = s[16] as Boolean
                    returnReserve = s[17] as Float; turnStyle = TurnStyle.valueOf(s[18] as String)
                    (s.getOrNull(19) as? String)?.let { name -> runCatching { OrientationMode.valueOf(name) }.getOrNull() }?.let { orientation = it }
                    (s.getOrNull(20) as? Int)?.let { manualAngle = foldAngle(it) }
                    (s.getOrNull(21) as? String)?.let { name -> runCatching { SpiralDirection.valueOf(name) }.getOrNull() }?.let { spiralDirection = it }
                    (s.getOrNull(22) as? String)?.let { name -> runCatching { ZamboniBlocks.valueOf(name) }.getOrNull() }?.let { zamboniBlocks = it }
                }
            },
        )
    }
}

@Composable
internal fun rememberMowingTaskForm(saved: MowingTaskSettings): MowingTaskForm =
    rememberSaveable(saver = MowingTaskForm.saver(saved)) { MowingTaskForm(saved) }

/** Move to a point: mode, computation and supervision ticks. */
@Stable
internal class PointMoveState {
    var pointMode by mutableStateOf(false)
    var pointComputing by mutableStateOf(false)
    var pointConfirmed by mutableStateOf(false)
    var pointAttempted by mutableStateOf(false)
}

/** Destination of the move to a point and its path: cleared when the map changes (remembered with the map as key). */
@Stable
internal class PointTarget {
    var destination by mutableStateOf<Point?>(null)
    var pointPlan by mutableStateOf<MapNavPlan?>(null)
    var pointError by mutableStateOf("")
}

/**
 * Path of the move to a point, computed again when the destination, the map, the obstacles, the mode, the base or the
 * charging state change.
 */
@Composable
internal fun PointPlanEffect(pointTarget: PointTarget, pointMove: PointMoveState, state: RobotUi, map: RobotMap, mapBase: MapBase?,
                             accesses: List<List<Point>>) {
    LaunchedEffect(pointTarget.destination, map, state.obstacles, pointMove.pointMode, mapBase, state.telemetry.chargeState in setOf(1,2)) {
        pointTarget.pointPlan = null; pointTarget.pointError = ""; pointMove.pointComputing = false
        val target = pointTarget.destination
        if (pointMove.pointMode && target != null && !state.navTest.running) {
            pointMove.pointComputing = true
            try {
                val start = Point(requireNotNull(state.telemetry.x) { tr("Robot position unavailable", "Position robot indisponible") },
                    requireNotNull(state.telemetry.y) { tr("Robot position unavailable", "Position robot indisponible") })
                pointTarget.pointPlan = withContext(Dispatchers.Default) {
                    RoutePlanning.point(map,start,target,state.obstacles,offMap=true,base=mapBase,
                        charging=state.telemetry.chargeState in setOf(1,2),accesses=accesses)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { pointTarget.pointError = e.message ?: tr("No safe path", "Aucun chemin sûr") }
            finally { pointMove.pointComputing = false }
        }
    }
}

/** Signal and terrain layers: chosen signal, heatmap shown, layer bar, shading, phone mount and tilt reference. */
@Stable
internal class MapLayersState(choice: MapLayerChoice, mount: PhoneMount, calibration: TiltCalibration?, simulatedSource: Boolean) {
    var surveySignal by mutableStateOf(choice.signal)
    var showSignalHeat by mutableStateOf(choice.heatVisible)
    /** Layers: bar open by default, folded or reopened by the "Layers" button of the side bar. */
    var layersOpen by mutableStateOf(choice.layersOpen)
    var reliefShading by mutableStateOf(choice.reliefShading)
    /** Heatmap of the simulation rather than the measured one. */
    var simulatedSignalSource by mutableStateOf(simulatedSource)
    var reliefMount by mutableStateOf(mount)
    var tiltCalibration by mutableStateOf(calibration)

    /** Choice kept until the next change ([MapLayerSettings]). */
    fun choice(): MapLayerChoice = MapLayerChoice(surveySignal, showSignalHeat, layersOpen, reliefShading)
}

/** Route computation and preparation of the simulation: progress, stages, check report, estimate, errors. */
@Stable
internal class RoutePreparationState {
    var computing by mutableStateOf(false); private set
    /** Planning inputs of the route computed or being computed ([AutoRouteComputation]); null once abandoned. */
    var computedFor: Any? = null; private set
    private var job: Job? = null
    var progress by mutableStateOf(Triple(0, 1, tr("Preparing", "Préparation")))
    var error by mutableStateOf("")
    var simBuilding by mutableStateOf(false)
    var simulationPreparationStage by mutableStateOf(tr("Checking the links", "Vérification des liaisons"))
    var planningReport by mutableStateOf<PlanningResult?>(null)
    var preparationStartedNanos by mutableLongStateOf(0L)
    var preparationTimingInputs by mutableStateOf("")
    var estimate by mutableStateOf<MowingEstimate?>(null)
    var estimateError by mutableStateOf("")
    /** Metres of the prepared route driven slower ([AdaptiveSpeed]), by reason; shown while a route is prepared. */
    var slowed by mutableStateOf<Map<SpeedReason, Double>>(emptyMap())

    /** Computation of the route of [inputs] started by [launch]; the one in progress is abandoned. */
    fun begin(inputs: Any?, launch: () -> Job) {
        job?.cancel()
        computing = true; computedFor = inputs
        job = launch()
    }

    /** End of the computation [ended]: an abandoned one finishing late leaves the state of the current one alone. */
    fun end(ended: Job?) { if (ended === job) { computing = false; job = null } }

    /** Computation in progress abandoned (inputs changed, task pages closed): computed again when needed. */
    fun cancel() {
        if (!computing) return
        job?.cancel(); job = null
        computing = false; computedFor = null
    }
}

/** Station, manual accesses and mowing resume: supervision tick, station route being prepared, access being drawn. */
@Stable
internal class MapActionState {
    var baseSupervised by mutableStateOf(false)
    var baseBusy by mutableStateOf(false)
    var placingAccess by mutableStateOf(false)
    var draftAccess by mutableStateOf(emptyList<Point>())
    /** Resume of an interrupted mowing: preparation in progress and its error. */
    var resumeBusy by mutableStateOf(false)
    var resumeError by mutableStateOf("")
}

/** Start of the previewed route: choice made in the preview and request used (remembered with the zones and the selection as keys). */
@Stable
internal class PreviewStartState {
    var startOverride by mutableStateOf<StartOverride?>(null)
    var previewStart by mutableStateOf<StartRequest?>(null)
}

/** Saved start point of each zone ([fr.luba.poc.planning.ZoneStartStore]). */
@Stable
internal class ZoneStartsState(private val store: ZoneStartStore) {
    var starts by mutableStateOf(store.read())
        private set
    fun save(zoneId: Long, point: Point) { starts = store.save(zoneId, point) }
    fun clear(zoneId: Long) { starts = store.clear(zoneId) }
}

/** Demo map shown and size of the map view. */
@Stable
internal class MapViewState(demo: Boolean) {
    var demo by mutableStateOf(demo)
    var mapSize by mutableStateOf(IntSize.Zero)
}

/** View (zoom, rotation, offset) and zone selection of a map: reset when its zones change (remembered with them as key). */
@Stable
internal class MapFrameState {
    var camera by mutableStateOf(MapCamera())
    var selected by mutableStateOf(setOf<Long>())
}

/** "Resume further on" of the interrupted task: open and skipped distance (remembered with the checkpoint as key). */
@Stable
internal class ResumeSkipState {
    var skipMode by mutableStateOf(false)
    var skipMeters by mutableFloatStateOf(0f)
}

/**
 * Parameters of [MapScreen] passed to its body as one object: as separate parameters, all of them stayed live in Dalvik
 * registers for the whole method (the restart lambda captures them), about 50 of the ~220 registers measured on 2026-10-20.
 */
internal class MapScreenArgs(
    val onFetchMap: () -> Unit,
    val onOpenRobot: () -> Unit,
    val onOpenAccount: () -> Unit,
    val openMowing: Boolean,
    val onMowingOpened: () -> Unit,
    /** Dedicated survey route requested from Service → Signal surveys: zone choice in survey mode. */
    val openSurvey: Boolean,
    val onSurveyOpened: () -> Unit,
    val cloud: Boolean,
    val apiPosition: Telemetry?,
    val apiKnown: LastKnownPosition?,
    val onPreview: (Preview) -> Unit,
    val onSimulationPreparation: (String, Map<String, Any?>) -> Unit,
    val onStartRobotRoute: (MowingSimulation, Boolean, Int) -> Boolean,
    val onBaseAction: (MowingSimulation, Boolean) -> Boolean,
    val onResumeRobotRoute: (MowingSimulation, MissionResume?) -> Boolean,
    val onBackOffBeforeResume: (MowingSimulation) -> Unit,
    val onStopBackOff: () -> Unit,
    val onDiscardMissionCheckpoint: () -> Unit,
    val onStopMammotionTask: () -> Unit,
    /** Native return through the Mammotion account (null if the account does not follow this robot). */
    val cloudReturn: CloudReturnUi?,
    val onCloudReturn: () -> Unit,
    val onCancelCloudReturn: () -> Unit,
    val cloudRobotName: String?,
    val onTrajectorySpeed: (Int) -> Unit,
    val onStartPointNavigation: (Point) -> Unit,
    val onStopPointNavigation: () -> Unit,
    val onDiscardPointResume: () -> Unit,
    val onUnlockRobot: () -> Unit,
    val onStop: () -> Unit,
    val onBack: (() -> Unit)?,
    val onOpenManual: () -> Unit,
    /** Dedicated "record by driving" page (zone, passage or no-go driven with the joysticks). */
    val onOpenDrivenRecord: () -> Unit,
    /** Detail of a history task (replay of the real route) and full history (Service). */
    val onOpenTask: (String) -> Unit,
    val onOpenAllTasks: () -> Unit,
    /** Saves the full list of local map corrections; false if refused (route in progress…). */
    val onSaveCorrections: (List<MapCorrection>) -> Boolean,
    /** Opened from the first-launch assistant ("Explore without a robot"): the demo map is shown at once. */
    val startDemo: Boolean,
)
