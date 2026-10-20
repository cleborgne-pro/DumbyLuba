package fr.luba.poc.map

import fr.luba.poc.camera.LiveCameraWindow
import fr.luba.poc.i18n.tr
import fr.luba.poc.simulation.*
import fr.luba.poc.planning.*
import fr.luba.poc.weather.WeatherPillSegment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.rounded.Grass
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Info
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.verticalScroll
import fr.luba.poc.ui.components.KeyValueRow
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.Role
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ui.components.LubaCard
import fr.luba.poc.ui.components.SettingRow
import fr.luba.poc.ui.components.StopAction
import fr.luba.poc.ui.components.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.LegendSwatch
import fr.luba.poc.ui.components.LubaCheckRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.SliderRow
import fr.luba.poc.ui.components.LubaSection
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen map: touch gestures (zoom, rotation, panning), zone selection and local route preparation, simulation
 * and supervision of the real BLE tracking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    state: RobotUi,
    simulation: SimulationSession,
    onFetchMap: () -> Unit,
    onOpenRobot: () -> Unit,
    onOpenAccount: () -> Unit,
    openMowing: Boolean = false,
    onMowingOpened: () -> Unit = {},
    /** Dedicated survey route requested from Service → Signal surveys: zone choice in survey mode. */
    openSurvey: Boolean = false,
    onSurveyOpened: () -> Unit = {},
    cloud: Boolean = false,
    apiPosition: fr.luba.poc.protocol.Telemetry? = null,
    apiKnown: LastKnownPosition? = null,
    onPreview: (Preview) -> Unit = {},
    onSimulationPreparation: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    onStartRobotRoute: (MowingSimulation, Boolean, Int) -> Boolean = { _, _, _ -> false },
    onBaseAction: (MowingSimulation, Boolean) -> Boolean = { _, _ -> false },
    onResumeRobotRoute: (MowingSimulation, MissionResume?) -> Boolean = { _, _ -> false },
    onBackOffBeforeResume: (MowingSimulation) -> Unit = {},
    onStopBackOff: () -> Unit = {},
    onDiscardMissionCheckpoint: () -> Unit = {},
    onStopMammotionTask: () -> Unit = {},
    /** Native return through the Mammotion account (null if the account does not follow this robot). */
    cloudReturn: fr.luba.poc.auth.CloudReturnUi? = null,
    onCloudReturn: () -> Unit = {},
    onCancelCloudReturn: () -> Unit = {},
    cloudRobotName: String? = null,
    onTrajectorySpeed: (Int) -> Unit = {},
    onStartPointNavigation: (Point) -> Unit = {},
    onStopPointNavigation: () -> Unit = {},
    onDiscardPointResume: () -> Unit = {},
    onUnlockRobot: () -> Unit = {},
    onStop: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenManual: () -> Unit = {},
    /** Detail of a history task (replay of the real route) and full history (Service). */
    onOpenTask: (String) -> Unit = {},
    onOpenAllTasks: () -> Unit = {},
    /** Saves the full list of local map corrections; false if refused (route in progress…). */
    onSaveCorrections: (List<MapCorrection>) -> Boolean = { false },
    /** Opened from the first-launch assistant ("Explore without a robot"): the demo map is shown at once. */
    startDemo: Boolean = false,
) {
    val colors = MammotionColors
    var demo by remember { mutableStateOf(startDemo) }
    val sim by simulation.ui.collectAsStateWithLifecycle()
    // Wheels of the simulated robot animated by its movement.
    val simOdometer = remember { WheelOdometer() }
    val simWheels = simOdometer.advance(sim.state?.let { DisplayPose(it.position, it.heading) })
    val showingRobotRoute = state.robotRoute.started && !sim.playing
    val map = if (state.robotRoute.running || (state.ready && !demo)) state.robotMap else sim.engine?.map ?: if (demo) Geometry.demo() else state.robotMap
    LaunchedEffect(state.ready,state.robotMap,demo) {
        if (state.ready && !demo && sim.engine?.map?.let { it!=state.robotMap }==true) simulation.clear()
    }
    val context = LocalContext.current
    val baseStore = remember(map) { BaseStore(context, map) }
    var mapBase by remember(map, demo) { mutableStateOf(run {
        val saved = baseStore.read()
        if (demo) saved ?: MapBase(Point(1.0, 1.0), "Démonstration")
        else baseStore.resolve(map, saved)
    }) }
    var accesses by remember(map) { mutableStateOf(baseStore.accesses()) }
    var placingAccess by remember { mutableStateOf(false) }
    var draftAccess by remember { mutableStateOf(emptyList<Point>()) }
    val zoneSelectionAllowed = !placingAccess
    var simulationDialog by remember { mutableStateOf(false) }
    var signalPanel by rememberSaveable { mutableStateOf(false) }
    var taskHistory by rememberSaveable { mutableStateOf(false) }
    var simulationSpeedMenu by remember { mutableStateOf(false) }
    var initialProgress by rememberSaveable { mutableStateOf(0f) }
    var initialBattery by rememberSaveable { mutableStateOf(100f) }
    var autonomy by rememberSaveable { mutableStateOf(60f) }
    var chargeTime by rememberSaveable { mutableStateOf(30f) }
    // Last mowing task settings, kept between two tasks and two launches ([MowingTaskStore]).
    val mowingTaskStore = remember { MowingTaskStore(context) }
    val savedTask = remember { mowingTaskStore.read() }
    var chargeLow by rememberSaveable { mutableStateOf(savedTask.chargeLow) }
    var chargeHigh by rememberSaveable { mutableStateOf(savedTask.chargeHigh) }
    var simBuilding by remember { mutableStateOf(false) }
    var simulationPreparationStage by remember { mutableStateOf(tr("Checking the links", "Vérification des liaisons")) }
    val telemetry = state.telemetry
    val known = state.lastKnown
    var positionClock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            positionClock = System.currentTimeMillis()
        }
    }
    // Last BLE position seen: a gateway reconnection does not send the robot back to an old FIX (the base).
    var lastBle by remember(state.selected?.address) { mutableStateOf<DisplayedRobot?>(null) }
    val marker = RobotPositionDisplay.resolve(if(cloud) fr.luba.poc.protocol.Telemetry() else telemetry,known,apiPosition,apiKnown,positionClock,
        recentBle = lastBle.takeIf { !cloud })
    if (!cloud && marker?.source == "BLE" && marker != lastBle) androidx.compose.runtime.SideEffect { lastBle = marker }
    val recoveryPoints = if (!demo && sim.engine == null) known?.points.orEmpty() else emptyList()
    var baseSupervised by remember { mutableStateOf(false) }
    var baseBusy by remember { mutableStateOf(false) }
    var pointMode by remember { mutableStateOf(false) }
    var destination by remember(map) { mutableStateOf<Point?>(null) }
    var pointPlan by remember(map) { mutableStateOf<fr.luba.poc.diagnostics.MapNavPlan?>(null) }
    var pointError by remember(map) { mutableStateOf("") }
    var pointComputing by remember { mutableStateOf(false) }
    var pointConfirmed by remember { mutableStateOf(false) }
    var pointAttempted by remember { mutableStateOf(false) }
    val pointIdle = state.ready && !demo && !cloud && !state.robotRoute.running && !state.mowing.active &&
        !state.manualActive && !state.campaign.running && !state.bladeTestActive && !state.bladeHeightPending &&
        !state.teach.running && !state.mapLoading && sim.engine == null
    androidx.compose.runtime.DisposableEffect(pointMode) {
        onDispose { if (pointMode) onStopPointNavigation() }
    }
    LaunchedEffect(destination, map, state.obstacles, pointMode, mapBase, state.telemetry.chargeState in setOf(1,2)) {
        pointPlan = null; pointError = ""; pointComputing = false
        val target = destination
        if (pointMode && target != null && !state.navTest.running) {
            pointComputing = true
            try {
                val start = Point(requireNotNull(state.telemetry.x) { tr("Robot position unavailable", "Position robot indisponible") },
                    requireNotNull(state.telemetry.y) { tr("Robot position unavailable", "Position robot indisponible") })
                pointPlan = withContext(Dispatchers.Default) {
                    RoutePlanning.point(map,start,target,state.obstacles,offMap=true,base=mapBase,
                        charging=state.telemetry.chargeState in setOf(1,2),accesses=accesses)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { pointError = e.message ?: tr("No safe path", "Aucun chemin sûr") }
            finally { pointComputing = false }
        }
    }
    var selected by remember(map.originalShapes) { mutableStateOf(setOf<Long>()) }
    var preview by remember(map.shapes, selected) { mutableStateOf<Preview?>(null) }
    // Start point: preview choice, otherwise the saved start of the first zone, otherwise nearest.
    val zoneStartStore = remember { ZoneStartStore(context) }
    var zoneStarts by remember { mutableStateOf(zoneStartStore.read()) }
    var startOverride by remember(map.shapes, selected) { mutableStateOf<StartOverride?>(null) }
    var previewStart by remember(map.shapes, selected) { mutableStateOf<StartRequest?>(null) }
    var pickingStart by remember { mutableStateOf(false) }
    var spacing by rememberSaveable { mutableStateOf(savedTask.spacingM) }
    val minimumCoverage=if(demo) 0.65f else 0.95f
    var planningReport by remember { mutableStateOf<PlanningResult?>(null) }
    var preparationStartedNanos by remember { mutableStateOf(0L) }
    var preparationTimingInputs by remember { mutableStateOf("") }
    var mowingHeight by rememberSaveable { mutableStateOf(savedTask.heightMm) }
    // Tall grass: successive passes lower and lower down to the cutting height ([CutPasses]).
    var heightPasses by rememberSaveable { mutableStateOf(savedTask.heightPasses) }
    var firstPassHeight by rememberSaveable { mutableStateOf(savedTask.firstPassHeightMm) }
    val passHeights = CutPasses.heights(mowingHeight.roundToInt(), firstPassHeight.roundToInt(), heightPasses)
    var mowingSpeed by rememberSaveable { mutableStateOf(savedTask.speedMps) }
    var mowingStep by rememberSaveable { mutableStateOf(0) }
    var estimate by remember { mutableStateOf<MowingEstimate?>(null) }
    var estimateError by remember { mutableStateOf("") }
    var margin by remember { mutableStateOf(0.6f) }
    val signalHistoryStore = remember { SignalSurveyStore(context.filesDir) }
    var signalHistoryOpen by remember { mutableStateOf(false) }
    LaunchedEffect(simulation) { simulation.storeSurveys(signalHistoryStore) }
    // Layer choice (layer, heatmap hidden, bar folded, shading) kept until the next change ([MapLayerSettings]).
    val layerChoice = remember { MapLayerSettings.read(context) }
    var surveySignal by remember { mutableStateOf(layerChoice.signal) }
    var showSignalHeat by remember { mutableStateOf(layerChoice.heatVisible) }
    var simulatedSignalSource by remember { mutableStateOf(sim.engine != null && !state.robotRoute.started) }
    LaunchedEffect(sim.engine,sim.playing,state.robotRoute.running) {
        if (state.robotRoute.running || sim.engine == null) simulatedSignalSource=false
        else if (sim.playing) simulatedSignalSource=true
    }
    val simulatedSurvey = simulatedSignalSource && sim.engine?.preview?.mode?.isSurvey == true
    // Overlaid survey: chosen in the history, otherwise the continuous map (automatic survey while moving), also on the
    // Mammotion account map when it is in the same frame.
    val pinnedSurvey by SignalOverlay.pinned.collectAsStateWithLifecycle()
    val overlaySurvey = remember(map.shapes, state.signalSurvey, pinnedSurvey) { SignalOverlay.resolve(map, state.signalSurvey, pinnedSurvey) }
    val displayedSignalSamples = if (simulatedSurvey) sim.state?.signalSamples.orEmpty() else overlaySurvey?.samples.orEmpty()
    val surveyMatches = simulatedSurvey || (!demo && overlaySurvey != null)
    // Layers: bar open by default, folded or reopened by the "Layers" button of the side bar.
    var layersOpen by remember { mutableStateOf(layerChoice.layersOpen) }
    val revealLayers by SignalOverlay.reveal.collectAsStateWithLifecycle()
    LaunchedEffect(revealLayers) { if (revealLayers && SignalOverlay.consumeReveal()) { layersOpen = true; showSignalHeat = true } }
    var reliefMount by remember { mutableStateOf(ReliefSettings.mount(context)) }
    var tiltCalibration by remember { mutableStateOf(ReliefSettings.calibration(context)) }
    var calibrationOpen by remember { mutableStateOf(false) }
    var relief3dOpen by remember { mutableStateOf(false) }
    // Simulation: fictional terrain measured with a "top towards the front" phone, whatever the real setting.
    val terrainMount = if (simulatedSurvey) PhoneMount.TOP_FORWARD else reliefMount
    var reliefShading by remember { mutableStateOf(layerChoice.reliefShading) }
    LaunchedEffect(surveySignal, showSignalHeat, layersOpen, reliefShading) {
        MapLayerSettings.save(context, MapLayerChoice(surveySignal, showSignalHeat, layersOpen, reliefShading))
    }
    // Terrain computed for every color: it overlays the Wi-Fi (shading, 3D view) in the same view.
    // Automatic orientation: search over the whole survey, computed off the UI thread.
    // Simulation: fictional terrain without reference; real survey: corrected by the flat-ground reference.
    val terrainCalibration = if (simulatedSurvey) null else tiltCalibration
    val relief by androidx.compose.runtime.produceState<ReliefResult?>(null, displayedSignalSamples, terrainMount, terrainCalibration) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ReliefModel.result(displayedSignalSamples,terrainMount,terrainCalibration) }
    }
    val signalView = remember(displayedSignalSamples, surveySignal, relief, reliefShading) {
        signalReliefView(displayedSignalSamples,surveySignal,relief,reliefShading)
    }
    val signalCells = signalView.cells
    var mode by remember { mutableStateOf(savedTask.mode) }
    // Last chosen mowing mode: restored when leaving a survey route, and the only one remembered.
    var mowingMode by remember { mutableStateOf(savedTask.mode) }
    var signalGridSpacing by rememberSaveable { mutableStateOf(3f) }
    var borders by remember { mutableStateOf(true) }
    var cutWidth by remember { mutableStateOf(Geometry.CUT_WIDTH_M.toFloat()) }
    var borderPasses by rememberSaveable { mutableStateOf(savedTask.borderPasses) }
    var noGoPasses by rememberSaveable { mutableStateOf(savedTask.noGoPasses) }
    var bordersFirst by rememberSaveable { mutableStateOf(savedTask.bordersFirst) }
    var nearbyNoGo by rememberSaveable { mutableStateOf(savedTask.nearbyNoGo) }
    LaunchedEffect(mode) { if (!mode.isSurvey) mowingMode = mode }
    LaunchedEffect(mowingHeight, mowingSpeed, mowingMode, spacing, borderPasses, noGoPasses, bordersFirst, nearbyNoGo, chargeLow, chargeHigh,
        heightPasses, firstPassHeight) {
        mowingTaskStore.write(MowingTaskSettings(mowingHeight, mowingSpeed, mowingMode, spacing, borderPasses, noGoPasses,
            bordersFirst, nearbyNoGo, chargeLow, chargeHigh, heightPasses, firstPassHeight))
    }
    var camera by remember(map.originalShapes) { mutableStateOf(MapCamera()) }
    // Robot following: view centered on it, zoom and rotation kept; a one-finger drag stops it.
    val follow = rememberRobotFollow()
    var mapSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var computing by remember { mutableStateOf(false) }
    var mowingDialog by remember { mutableStateOf(false) }
    // Zone choice directly on the map (not in a dialog hiding it).
    var selectingZones by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var baseDialog by remember { mutableStateOf(false) }
    // Without Bluetooth, account signed in: "Charging station" offers the native return through the account.
    val cloudStation = !state.ready && !demo && cloudReturn != null
    // Prepared task: mowing (Mow) or dedicated survey route (opened from Service → Signal surveys).
    var surveyTask by rememberSaveable { mutableStateOf(false) }
    fun selectTask(survey: Boolean) {
        surveyTask = survey
        if (survey && !mode.isSurvey) mode = CoverageMode.TOUR
        if (!survey && mode.isSurvey) mode = mowingMode
        selectingZones = true
    }
    LaunchedEffect(openMowing) {
        if (openMowing) {
            selectTask(false)
            onMowingOpened()
        }
    }
    LaunchedEffect(openSurvey) {
        if (openSurvey) {
            if (!state.robotRoute.running && !state.mowing.active && sim.engine == null) selectTask(true)
            onSurveyOpened()
        }
    }
    var mapActions by remember { mutableStateOf(false) }
    // Local corrections editor (layer laid on the Mammotion map), opened from the Map sheet.
    var correcting by remember { mutableStateOf(false) }
    var statusPanel by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf(Triple(0, 1, tr("Preparing", "Préparation"))) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Local obstacles learned by the robot, plus presumed obstacles (repeated contacts) if the option is on.
    val localObstacles = state.planningObstacles
    // Difficult zones (heatmap) avoided by travel with blades stopped.
    val transitAvoid = state.difficulties.transitAvoidance
    val planningMap = map
    val currentPlanningMap by androidx.compose.runtime.rememberUpdatedState(planningMap)
    val currentPlanningSelection by androidx.compose.runtime.rememberUpdatedState(selected.toList())
    val currentPlanningObstacles by androidx.compose.runtime.rememberUpdatedState(localObstacles)
    val outerOffset by OuterOffsetSetting.flow.collectAsStateWithLifecycle()
    val surveyOffset by SurveyOffsetSetting.flow.collectAsStateWithLifecycle()
    val planningInputs=listOf(planningMap,localObstacles,selected.toList(),mode,
        spacing,margin,cutWidth,minimumCoverage,mowingSpeed,borders,borderPasses,noGoPasses,
        bordersFirst,nearbyNoGo,signalGridSpacing,mapBase,accesses,outerOffset,surveyOffset,
        // Height passes: route prepared with all the passes, recomputed when they change.
        passHeights.takeIf { !mode.isSurvey })
    val currentPlanningInputs by androidx.compose.runtime.rememberUpdatedState(planningInputs)
    LaunchedEffect(planningInputs) { preview=null; planningReport=null; estimate=null }

    fun simulationConfig(base: MapBase) = SimConfig(initialProgress.toDouble(), initialBattery.toDouble(), autonomy.toDouble(),
        speedMps=mowingSpeed.toDouble(),chargeMinutes=chargeTime.toDouble(),instantCharging=true,
        returnPercent=chargeLow.toDouble(),resumePercent=chargeHigh.toDouble(),
        baseEntryHeadingDegrees=base.entryHeadingDegrees,baseApproachMeters=base.approachDistanceM)

    // A preparation belongs exactly to this preview and its accesses/obstacles.
    var preparedSimulation by remember(preview,planningMap,mapBase,accesses,localObstacles,transitAvoid,demo) { mutableStateOf<MowingSimulation?>(null) }
    val simulateEnabled = preparedSimulation != null && mapBase != null &&
        (demo || mapBase?.entryHeadingDegrees != null) && !computing && !simBuilding && !state.robotRoute.running
    // Measure after applying the composition that actually enables the button.
    SideEffect {
        if (mowingDialog && mowingStep == 2 && simulateEnabled && preparationStartedNanos != 0L) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - preparationStartedNanos) / 1_000_000.0
            Log.i("PathPreparationTiming", JSONObject(preparationTimingInputs)
                .put("event", "SIMULATE_ENABLED").put("elapsedMs", elapsedMs)
                .put("planHash", planningReport?.selected?.planHash)
                .put("snapshotHash", planningReport?.snapshotHash).toString())
            preparationStartedNanos = 0L
        }
    }
    fun buildRoutes(base: MapBase, routeMap: RobotMap = planningMap, obstacles: List<LocalObstacle> = localObstacles,
                    avoidAreas: List<List<Point>> = transitAvoid): TrajectoryPlanner =
        RoutePlanning.mission(routeMap, obstacles, accesses, base, recordedBoundary = !demo, avoidAreas = avoidAreas)
    fun buildSimulation(plan: Preview, base: MapBase, progress:(String)->Unit = {}): MowingSimulation =
        MowingSimulation(buildRoutes(base),base.point,plan,planningMap,simulationConfig(base),onPreparationProgress=progress)
    fun baseAction(leave: Boolean) {
        val base=mapBase ?: return
        baseBusy=true
        scope.launch {
            try {
                val start=Point(requireNotNull(state.telemetry.x),requireNotNull(state.telemetry.y))
                val engine=withContext(Dispatchers.Default) {
                    RoutePlanning.station(buildRoutes(base,map),map,base,start,simulationConfig(base))
                }
                if(onBaseAction(engine,leave)) mapActions=false
            } catch(cancel: CancellationException) { throw cancel }
            catch(e: Exception) { error=tr("Base: ${e.message}", "Base : ${e.message}") }
            finally { baseBusy=false }
        }
    }
    LaunchedEffect(preview,planningMap,mapBase,accesses,localObstacles,transitAvoid,demo,computing) {
        val plan=preview; val base=mapBase
        if(computing || plan==null || base==null || preparedSimulation!=null || (plan.localOnly && planningReport?.selected?.valid != true)) return@LaunchedEffect
        simBuilding=true
        val diagnostic = mapOf("selectedZoneIds" to plan.zoneIds,
            "transitZoneIds" to map.shapes.filter { it.kind==ShapeKind.ZONE }.map { it.id },
            "baseX" to base.point.x,"baseY" to base.point.y,"baseHeading" to base.entryHeadingDegrees,
            "passagePointCounts" to map.shapes.filter { it.kind==ShapeKind.PASSAGE }.map { it.points.size })
        try {
            preparedSimulation=withContext(Dispatchers.Default) {
                val context=currentCoroutineContext()
                buildSimulation(plan,base) { stage ->
                    context.ensureActive()
                    scope.launch { simulationPreparationStage=stage }
                }
            }
            onSimulationPreparation(tr("Route preparation succeeded", "Préparation du trajet réussie"),diagnostic + ("result" to "READY"))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            error=e.message ?: tr("Route preparation failed", "Préparation du trajet impossible")
            onSimulationPreparation(error,diagnostic + mapOf("result" to "FAILED","exception" to e.stackTraceToString()))
        }
        finally { simBuilding=false }
    }
    LaunchedEffect(preparedSimulation,mowingSpeed,chargeLow,chargeHigh,initialBattery,autonomy,chargeTime) {
        val prepared = preparedSimulation ?: return@LaunchedEffect
        val base = mapBase ?: return@LaunchedEffect
        if (!prepared.preview.mode.isSurvey) return@LaunchedEffect
        try {
            estimate = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                MowingEstimate.fromSimulation(prepared.withConfig(simulationConfig(base).copy(initialProgress=0.0))) { context.ensureActive() }
            }
            estimateError = tr("Simulated estimate: links, pivots, returns to base and recharges included.", "Estimation simulée : raccords, pivots, retours à la base et recharges inclus.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { estimate=null; estimateError=tr("Estimate unavailable: ${e.message}", "Estimation indisponible : ${e.message}") }
    }
    fun startMowingSimulation() {
        val prepared=preparedSimulation; val base=mapBase
        if(prepared==null || base==null || state.robotRoute.running || simBuilding || computing) return
        // The click triggers no geometric computation: only the scenario and its position.
        try {
            val engine=prepared.withConfig(simulationConfig(base))
            simulation.start(engine,engine.start())
            error=""; mowingDialog=false; simulationDialog=false
        } catch (e: Exception) {
            error=e.message ?: tr("Could not start the simulation", "Démarrage de simulation impossible")
        }
    }
    // Resume of an interrupted mowing: same preview prepared again on the current map, base, accesses and obstacles.
    var resumeBusy by remember { mutableStateOf(false) }
    var resumeError by remember { mutableStateOf("") }
    var discardConfirm by remember { mutableStateOf(false) }
    val currentRouteStatus by androidx.compose.runtime.rememberUpdatedState(state.robotRoute.status)
    // Resume route prepared again once, shared by "Resume", "Resume further on" (preview on the map) and "Unblock";
    // redone if the checkpoint, the map, the base, the accesses or the obstacles change.
    val resumeKey = state.missionCheckpoint?.let { listOf(it.robotId, it.startedAt, it.planFingerprint) }
    var resumeEngine by remember(resumeKey, mapBase, planningMap, state.obstacles, accesses) { mutableStateOf<MowingSimulation?>(null) }
    var skipMode by remember(resumeKey) { mutableStateOf(false) }
    var skipMeters by remember(resumeKey) { mutableStateOf(0f) }
    suspend fun resumeEngineFor(checkpoint: MissionCheckpoint, base: MapBase): MowingSimulation = resumeEngine ?: run {
        // Presumed obstacles and avoided zones of the start: a contact since then (often the one that interrupted the
        // mowing) is bypassed during tracking, it does not change the resumed plan.
        // Task started from the tests (border, painted zone): same continuous-blade zones as at its start.
        withContext(Dispatchers.Default) {
            fr.luba.poc.planning.RoutePlanning.resume(checkpoint, planningMap, base, accesses, state.obstacles, state.difficulties,
                simulationConfig(base), recordedBoundary = !demo)
        }.also { resumeEngine = it }
    }
    /** Preparation again then [action]; errors shown on the panel and logged. */
    fun withResumeEngine(action: (MissionCheckpoint, MowingSimulation) -> Unit) {
        val checkpoint = state.missionCheckpoint ?: return
        val base = mapBase
        if (base == null) { resumeError = tr("Unknown base: place the base before resuming", "Base inconnue : placez la base avant de reprendre"); return }
        if (state.robotRoute.running || resumeBusy) return
        resumeBusy = true; resumeError = ""
        scope.launch {
            try { action(checkpoint, resumeEngineFor(checkpoint, base)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                resumeError = tr("Resume failed: ${e.message ?: "route preparation"}", "Reprise impossible : ${e.message ?: "préparation du trajet"}")
                onSimulationPreparation(resumeError, mapOf("result" to "RESUME_FAILED", "edge" to checkpoint.edge,
                    "savedAvoidance" to (checkpoint.avoidance != null), "exception" to e.stackTraceToString()))
            }
            finally { resumeBusy = false }
        }
    }
    fun resumeMission() = withResumeEngine { checkpoint, engine ->
        // "Resume further on": point chosen on the map, without overlap; otherwise resume at the stop point.
        val skipTo = if (skipMode && skipMeters > 0f) ResumeSkipView.of(engine.edges, checkpoint, skipMeters.toDouble())?.resume
            ?: error(tr("resume point not found on the remaining stripes", "point de reprise introuvable sur les bandes restantes")) else null
        simulation.pause()
        if (onResumeRobotRoute(engine, skipTo)) { skipMode = false; skipMeters = 0f } else resumeError = currentRouteStatus
    }
    fun backOffBeforeResume() = withResumeEngine { _, engine -> onBackOffBeforeResume(engine) }
    fun openResumeSkip() { skipMode = true; if (resumeEngine == null) withResumeEngine { _, _ -> } }
    val resumeSkip = state.missionCheckpoint?.takeIf { skipMode }?.let { cp -> resumeEngine?.let { ResumeSkipView.of(it.edges, cp, skipMeters.toDouble()) } }
    // Map tap in "Resume further on" mode: resume at the nearest point of the remaining stripes.
    val resumeSkipTap by androidx.compose.runtime.rememberUpdatedState<(Point) -> Boolean> { p ->
        val cp = state.missionCheckpoint; val engine = resumeEngine
        if (!skipMode || cp == null || engine == null) false
        else { MissionProgress.skipToward(engine.edges, cp.edge, cp.point, p)?.let { skip ->
            skipMeters = skip.coerceIn(0.0, ResumeSkipView.maxMeters(engine.edges, cp)).toFloat() }; true }
    }
    fun calculateMowingPreview() {
        if (mode.isSurvey) initialProgress = 0f
        val calculationInputs=planningInputs
        val calculationMap=planningMap
        val calculationObstacles=localObstacles
        val calculationNavigation=PlanningNavigation(accesses.map { it.toList() },mapBase)
        val zones = selected.mapNotNull { id -> calculationMap.shapes.firstOrNull { it.kind == ShapeKind.ZONE && it.id == id } }
        if (zones.isEmpty()) return
        val startRequest = ZoneStart.requested(calculationMap, zones.map { it.id }, startOverride, zoneStarts)
        previewStart = startRequest
        pickingStart = false
        preparationStartedNanos = SystemClock.elapsedRealtimeNanos()
        preparationTimingInputs = JSONObject().put("startedNanos", preparationStartedNanos)
            .put("zones", JSONArray(zones.map { it.name.trim() }))
            .put("zoneIds", JSONArray(zones.map { it.id }))
            .put("spacingM", spacing.toDouble()).put("marginM", margin.toDouble())
            .put("cutWidthM", cutWidth.toDouble()).put("minimumCoverage", minimumCoverage.toDouble())
            .put("speedMps", mowingSpeed.toDouble()).put("mode", mode.name)
            .put("borderPasses", borderPasses).put("noGoPasses", noGoPasses)
            .put("borders", borders).put("bordersFirst", bordersFirst).put("nearbyNoGo", nearbyNoGo)
            .put("demo", demo).put("baseX", mapBase?.point?.x).put("baseY", mapBase?.point?.y)
            .put("baseHeading", mapBase?.entryHeadingDegrees).put("startSource", startRequest.source.name).toString()
        Log.i("PathPreparationTiming", JSONObject(preparationTimingInputs).put("event", "BEGIN").toString())
        val planSpacing = if (mode == CoverageMode.SURVEY_GRID) signalGridSpacing.toDouble() else spacing.toDouble()
        val planMargin = margin.toDouble()
        val planMode = mode
        val planBorders = borders
        val planBorderPasses = borderPasses
        val planNoGoPasses = noGoPasses
        val planBase = mapBase?.point
        val planMapBase = mapBase
        // Start nearest to the robot (fresh BLE position); base if it is charging or unknown.
        val planRobot = if (demo || cloud) null else marker?.takeIf { it.fresh && it.source == "BLE" }?.point
        val planDocked = state.telemetry.chargeState in setOf(1, 2)
        val planRoutingObstacles = localObstacles
        val planAccesses = accesses.map { it.toList() }
        val planBordersFirst = bordersFirst
        val planNearbyNoGo = nearbyNoGo
        val planHeights = if (mode.isSurvey) emptyList() else passHeights
        progress = Triple(0, zones.size, tr("Preparing the zones", "Préparation des zones"))
        computing = true
        error = ""
        preview = null
        estimate = null; estimateError = ""; planningReport=null
        val requestConfig=PlanningConfig(spacingM=planSpacing.coerceAtLeast(0.05),marginM=planMargin,cutWidthM=cutWidth.toDouble(),
            minimumCoverage=minimumCoverage.toDouble(),speedMps=mowingSpeed.toDouble(),borders=planBorders,
            borderPasses=planBorderPasses,noGoPasses=planNoGoPasses,bordersFirst=planBordersFirst,nearbyNoGo=planNearbyNoGo,
            boundary=if(demo) BoundaryMeaning.PHYSICAL else BoundaryMeaning.RECORDED_TRACE,
            outerOffsetM=if(planMode.isSurvey) SurveyOffsetSetting.meters else OuterOffsetSetting.meters)
        mowingStep = 2

        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val coroutine=currentCoroutineContext()
                    val startRoutes=planMapBase?.let { b -> RoutePlanning.mission(calculationMap,planRoutingObstacles,planAccesses,b,recordedBoundary=!demo,avoidAreas=transitAvoid) }
                    // Chosen or saved start: nearest free point where the robot can pivot.
                    val robotStart=startRequest.point?.let { requested ->
                        if(startRoutes==null) requested
                        else requireNotNull(RoutePlanning.nearestUsable(startRoutes,requested)) {
                            tr("Start point unreachable: choose a point further inside the zone", "Point de départ inaccessible : choisissez un point plus à l'intérieur de la zone") }
                    } ?: planMapBase?.let { b -> RoutePlanning.missionStart(startRoutes!!,planRobot,planDocked,b.point) }
                        ?: planRobot.takeIf { !planDocked }
                    val start=robotStart ?: planBase ?: org.locationtech.jts.geom.GeometryFactory().createPolygon(
                        (zones.first().points+zones.first().points.first()).map { org.locationtech.jts.geom.Coordinate(it.x,it.y) }.toTypedArray())
                        .interiorPoint.let { Point(it.x,it.y) }
                    // Mission ends at the base: the order does not come back towards the robot's start position.
                    val planned=RoutePlanning.coverage(PlanningRequest(calculationMap,zones.map { it.id },start,config=requestConfig,
                        obstacles=fr.luba.poc.planning.DockClearance.planning(calculationObstacles,planMapBase),
                        navigation=calculationNavigation,end=planBase?.takeIf { it!=start }),
                        planMode,checkpoint={ coroutine.ensureActive() },
                        progress={ done,total,name -> scope.launch { progress=Triple(done,total,name) } })
                    withContext(Dispatchers.Main) {
                        require(currentPlanningInputs==calculationInputs && currentPlanningMap==calculationMap && currentPlanningObstacles==calculationObstacles && currentPlanningSelection==zones.map { it.id }) { tr("Map changed: recomputation required", "Carte modifiée : recalcul requis") }
                        planningReport=planned.report
                    }
                    // Height passes: same route repeated, every other pass in reverse.
                    CutPasses.expand(planned.preview, planHeights)
                }
                require(currentPlanningInputs==calculationInputs && currentPlanningMap==calculationMap && currentPlanningObstacles==calculationObstacles && currentPlanningSelection==zones.map { it.id }) { tr("Map or obstacles changed during the computation: recomputation required", "Carte ou obstacles modifiés pendant le calcul : recalcul requis") }
                preview = result
                onPreview(result)
                estimate = realEstimate(state, demo, result.meters, mowingSpeed, autonomy, chargeTime, initialBattery, chargeLow, chargeHigh)
                estimateError = fr.luba.poc.history.MowingForecaster.note(estimateCalibration(state, demo))
                mowingStep = 2
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("Computation failed", "Calcul impossible")
            } finally {
                computing = false

            }
        }
    }

    val density = LocalDensity.current
    val viewportTop = with(density) { 80.dp.toPx() }
    // Zone choice keeps the action bar framing: the map does not jump when "Mow" is tapped.
    val viewportBottom = with(density) { (if (placingAccess || pointMode) 280.dp else if (sim.engine != null) 220.dp else 150.dp).toPx() }
    val framingPoints = listOfNotNull(sim.engine?.base ?: mapBase?.point, marker?.point.takeIf { !demo }) + accesses.flatten() + draftAccess
    val currentViewportMap = if (framingPoints.isNotEmpty()) map.copy(shapes=map.shapes+MapShape(-1,2,framingPoints)) else map
    val viewportMap = if (recoveryPoints.isNotEmpty()) currentViewportMap.copy(shapes = currentViewportMap.shapes + MapShape(-2, 2, recoveryPoints.map { Point(it.x, it.y) })) else currentViewportMap

    // Values read by the gestures in their latest version, without restarting the detector: the robot position is part
    // of the framing and changes on every reading; as a pointerInput key, it interrupted the pinch in progress.
    val gestureViewport by androidx.compose.runtime.rememberUpdatedState(viewportMap)
    // A single full-screen box: the map takes the whole height, no bar scrolls below it.
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { mapSize = it }
                .clipToBounds()
                .background(colors.MapBackground)
                .mapDotGrid(colors.TextTertiary.copy(alpha = 0.16f))
                .pointerInput(map, placingAccess, pointMode, selectingZones, state.navTest.running, pointIdle, sim.engine, viewportTop, viewportBottom) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var handledByControl = down.isConsumed
                        var transformed = false
                        var travel = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            handledByControl = handledByControl || event.changes.any { it.isConsumed }
                            val pan = event.calculatePan()
                            travel += pan
                            if (event.changes.count { it.pressed } > 1 || travel.getDistance() > viewConfiguration.touchSlop) transformed = true
                            if (!handledByControl && transformed && event.changes.any { it.pressed }) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (centroid != Offset.Unspecified) {
                                    // One-finger drag while following: the view stays where it is, following stopped.
                                    if (follow.enabled && event.changes.count { it.pressed } == 1) { camera = follow.view(camera); follow.stop() }
                                    camera = follow.view(camera).transform(
                                        Point(centroid.x.toDouble(), centroid.y.toDouble()),
                                        Point(size.width / 2.0, size.height / 2.0),
                                        Point(pan.x.toDouble(), pan.y.toDouble()),
                                        event.calculateZoom().toDouble(),
                                        event.calculateRotation().toDouble(),
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        // Single tap: selects or deselects the zone under the finger.
                        if (!transformed && !handledByControl && !computing && sim.engine == null) {
                            val base = follow.view(camera).inverse(
                                Point(down.position.x.toDouble(), down.position.y.toDouble()),
                                Point(size.width / 2.0, size.height / 2.0),
                            )
                            val tappedViewport = gestureViewport
                            val availableHeight = (size.height - viewportTop - viewportBottom).coerceAtLeast(120f)
                            val scale = min((size.width - 40) / mapExtentX(tappedViewport), availableHeight / mapExtentY(tappedViewport))
                            val ox = (size.width - mapExtentX(tappedViewport) * scale) / 2
                            val oy = viewportBottom + (availableHeight - mapExtentY(tappedViewport) * scale) / 2
                            val p = Point(
                                (base.x - ox) / scale + mapMinX(tappedViewport),
                                (size.height - base.y - oy) / scale + mapMinY(tappedViewport),
                            )
                            if (resumeSkipTap(p)) Unit
                            else if (pointMode) {
                                if (pointIdle && !state.navTest.running) { destination = p; pointConfirmed = false; pointAttempted = false }
                            } else if (placingAccess) {
                                draftAccess = draftAccess + p
                            } else if (zoneSelectionAllowed && selectingZones) map.shapes.firstOrNull { it.kind == ShapeKind.ZONE && Geometry.inside(p, it.points) }?.id?.let { id ->
                                selected = if (id in selected) selected - id else selected + id
                            } else if (!selectingZones) MapExtraLayers.tap(p, scale * camera.zoom)
                        }
                    }
                },
        ) {
            // Content compiled outside MapScreen (non-inline lambda): see IsolatedBox.
            IsolatedBox {
            val extraLayers by MapExtraLayers.flow.collectAsStateWithLifecycle()
            val bounds = remember(viewportMap) { MapBounds.of(viewportMap.shapes.flatMap { it.points }) }
            // Preview paths (thousands of passes) kept from one frame to the next: a zoom only changes the widths.
            val routeGeometry = remember { LiveGeometry() }
            // Zone names (interior point computed once per map) and display settings ([MapDisplaySettings]).
            val zoneNameLabels = remember(map) { zoneLabels(map) }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .mapAppear(map.shapes)
                    .graphicsLayer {
                        scaleX = camera.zoom.toFloat()
                        scaleY = camera.zoom.toFloat()
                        rotationZ = camera.rotation.toFloat()
                        val shown = follow.view(camera)
                        translationX = shown.offset.x.toFloat()
                        translationY = shown.offset.y.toFloat()
                    },
            ) {
                val w0 = bounds.width
                val h0 = bounds.height
                val minX = bounds.minX
                val minY = bounds.minY
                val availableHeight = (size.height - viewportTop - viewportBottom).coerceAtLeast(120f)
                val scale = min((size.width - 40) / w0, availableHeight / h0)
                val ox = (size.width - w0 * scale) / 2
                val oy = viewportBottom + (availableHeight - h0 * scale) / 2

                fun screen(p: Point) = Offset(
                    (ox + (p.x - minX) * scale).toFloat(),
                    (size.height - oy - (p.y - minY) * scale).toFloat(),
                )

                fun path(points: List<Point>, closed: Boolean): Path {
                    val out = Path()
                    // Trace break ([MissionTrail.BREAK]): the next piece starts again without a connecting line.
                    var start = true
                    points.forEach { p ->
                        if (fr.luba.poc.simulation.MissionTrail.isBreak(p)) { start = true; return@forEach }
                        val o = screen(p)
                        if (start) out.moveTo(o.x, o.y) else out.lineTo(o.x, o.y)
                        start = false
                    }
                    if (closed) out.close()
                    return out
                }

                // The Canvas is enlarged by graphicsLayer: compensate the zoom to keep thin lines on screen, independently
                // of the metric cutting areas.
                val screenUnit = 1.dp.toPx() / camera.zoom.toFloat()
                fun screenStroke(width: Float) = Stroke(
                    width = width * screenUnit, cap = StrokeCap.Round, join = StrokeJoin.Round,
                )
                val completedColor = colors.Mowed
                val display = MapDisplaySettings.current

                // "Grid" background: metric grid tied to the map, under the lawn.
                if (display.backdrop == MapBackdrop.GRID)
                    drawMetricGrid(MapProjection(minX, minY, scale, ox, oy, size.height), follow.view(camera), screenUnit, colors)

                // Lawn in relief: shadow, gradient, hatched no-go zones (with no zone chosen, all stay bright).
                drawLawn(map, { selected.isEmpty() || it.id in selected }, ::path, screenUnit, colors)
                // Base safety zone (grey perimeter of the Mammotion app): disc excluded from routes, under the planned route,
                // only when the docking axis is known (otherwise the planner does not set it).
                val stationPoint = sim.engine?.base ?: mapBase?.point
                val stationHeading = sim.engine?.dockEntryHeading ?: mapBase?.entryHeadingDegrees
                val safetyRadius = sim.engine?.routes?.baseSafety?.third
                    ?: stationHeading?.let { TrajectoryPlanner.baseSafetyRadius(mapBase?.approachDistanceM ?: 1.5) }
                if (stationPoint != null && safetyRadius != null)
                    drawBaseSafetyZone(screen(stationPoint), (safetyRadius * scale).toFloat(), screenUnit, colors.BaseSafety)

                // Stripes to the scale of the cutting width: each pass is drawn separately, so overlaps add up in opacity
                // and become visible.
                val cutPx = (cutWidth * scale).toFloat().coerceAtLeast(1f)
                val bandStroke = Stroke(width = cutPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                routeGeometry.begin(MapProjection(minX, minY, scale, ox, oy, size.height))
                val routePath = routeGeometry::path
                (sim.engine?.preview ?: preview)?.passes?.forEach { drawPath(routePath(it, false), colors.BandPass, style = bandStroke) }
                // Border loops lighter than the passes: the margin held along the boundaries and around the no-go zones
                // reads as an edging, as in the Mammotion app.
                (sim.engine?.preview ?: preview)?.borders?.forEach { drawPath(routePath(it, true), colors.BandBorder, style = bandStroke) }

                // Thin center lines of constant width: exact path at the center of the stripes.
                val centerStroke = screenStroke(0.75f)
                (sim.engine?.preview ?: preview)?.runs?.forEach { drawPath(routePath(it, false), colors.LinePass, style = centerStroke) }
                (sim.engine?.preview ?: preview)?.borders?.forEach { drawPath(routePath(it, true), colors.LineBorder, style = centerStroke) }
                routeGeometry.end()

                // Local point obstacles: physical circle, safety margin, turning envelope.
                // Saved obstacles; presumed obstacles are drawn by the Obstacles layer (dashed).
                drawLocalObstacles(state.obstacles, ::screen, scale, colors, unit = screenUnit)
                // Encountered obstacles (photo) and difficulties heatmap, according to the displayed layers.
                drawDifficultyLayers(state.difficulties, extraLayers, state.obstaclePhoto.photos, ::screen, scale, colors, unit = screenUnit)

                if (showSignalHeat && surveyMatches) signalCells.forEach { cell ->
                    val color = signalView.color(cell)
                    val corner = screen(Point(cell.point.x-0.5,cell.point.y+0.5))
                    drawRect(color,corner,androidx.compose.ui.geometry.Size(scale.toFloat(),scale.toFloat()))
                }
                // Manual accesses: travel with blades stopped (blue dashes).
                accesses.forEach { drawTransitPath(path(it, false), screenUnit, colors, 1.75f) }
                if (draftAccess.size >= 2) drawTransitPath(path(draftAccess, false), screenUnit, colors, 2.5f)
                // Station sent by the robot, without manual editing; lightning badge when it is too small on screen.
                stationPoint?.let { p ->
                    val o = screen(p)
                    drawStation(o, scale, stationHeading,
                        null, colors.Ink, mapBase?.approachDistanceM ?: 1.5, (1.5/camera.zoom).toFloat())
                    if (stationNeedsBadge(scale, screenUnit)) drawStationBadge(o, screenUnit, colors.Ink, colors.OnInk)
                }
                sim.state?.takeIf { !showingRobotRoute }?.let { s ->
                    // Already mowed: dark green stripes at the cutting width, under the robot trace.
                    sim.engine?.edges?.take(s.edge)?.filter { it.cutting }?.forEach { e ->
                        drawPath(path(listOf(e.a, e.b), false), completedColor.copy(alpha = 0.32f), style = bandStroke)
                    }
                    sim.engine?.edges?.getOrNull(s.edge)?.takeIf { it.cutting && s.offset > 0 }?.let { e ->
                        val fraction = s.offset / e.meters
                        val end = Point(e.a.x + (e.b.x - e.a.x) * fraction, e.a.y + (e.b.y - e.a.y) * fraction)
                        drawPath(path(listOf(e.a, end), false), completedColor.copy(alpha = 0.32f), style = bandStroke)
                    }
                    drawPath(path(s.trail, false), completedColor.copy(alpha = 0.22f), style = screenStroke(6f))
                    drawPath(path(s.trail, false), completedColor, style = screenStroke(1.75f))
                    if (s.phase in setOf(SimPhase.LEAVING_DOCK, SimPhase.RETURNING, SimPhase.DEPARTING, SimPhase.ALIGNING))
                        drawTransitPath(path(s.transit, false), screenUnit, colors)
                    val o = screen(s.position)
                    drawLuba(o, scale, s.heading, simWheels)
                }
                if (showingRobotRoute) {
                    // To do: planned route in white (like the preview); done: green robot trace.
                    drawPath(path(state.robotRoute.planned, false), colors.LinePass.copy(alpha = 0.9f), style = screenStroke(1.25f))
                    // Real trace: soft halo under a sharp line, readable on the bright lawn.
                    drawPath(path(state.robotRoute.trail, false), completedColor.copy(alpha = 0.25f), style = screenStroke(7f))
                    drawPath(path(state.robotRoute.trail, false), completedColor, style = screenStroke(2f))
                }
                (sim.engine?.preview ?: preview)?.let { route ->
                    route.cuttingPaths.firstOrNull { it.size >= 2 }?.first()?.let {
                        drawRouteStart(screen(it), camera.zoom.toFloat(), camera.rotation.toFloat())
                    }
                }
                // "Resume further on": remaining stripes, skipped stripes (not mowed), stop and resume point.
                resumeSkip?.let { v ->
                    fun segments(list: List<Pair<Point, Point>>) = Path().also { out ->
                        list.forEach { (a, b) -> val oa = screen(a); val ob = screen(b); out.moveTo(oa.x, oa.y); out.lineTo(ob.x, ob.y) } }
                    drawPath(segments(v.ahead), colors.LinePass, style = screenStroke(1.25f))
                    if (v.skipped.isNotEmpty()) {
                        val skipped = segments(v.skipped)
                        drawPath(skipped, colors.Warning.copy(alpha = 0.35f), style = bandStroke)
                        drawPath(skipped, colors.Warning, style = screenStroke(2f))
                    }
                    drawCircle(colors.Danger, 5 * screenUnit, screen(v.stop))
                    drawCircle(Color.White, 5 * screenUnit, screen(v.stop), style = screenStroke(1.5f))
                    drawRouteStart(screen(v.target), camera.zoom.toFloat(), camera.rotation.toFloat(), label = tr("Resume", "Reprise"))
                }
                // Gateway fallen off the robot: where to pick up the phone.
                state.gatewayDrop?.takeIf { !demo }?.let { drop ->
                    drawRouteStart(screen(Point(drop.x, drop.y)), camera.zoom.toFloat(), camera.rotation.toFloat(),
                        label = tr("Gateway", "Passerelle"), purple = colors.Danger)
                }
                if (pointMode) {
                    val points = if (state.navTest.running) state.navTest.planned else pointPlan?.points.orEmpty()
                    if (points.size > 1) drawTransitPath(path(points,false), screenUnit, colors, 3f)
                    // Straight exit from the base to the link point, before the path.
                    val exitLine = if (state.navTest.running) state.navTest.plannedExit else pointPlan?.departure?.let { listOf(it.from, it.approach) }.orEmpty()
                    if (exitLine.size > 1) drawTransitPath(path(exitLine,false), screenUnit, colors, 3f)
                    // Sections beyond the mapped zones: orange dots over the route.
                    (if (state.navTest.running) state.navTest.plannedOffMap else pointPlan?.offMap.orEmpty())
                        .forEach { if (it.size > 1) drawOffMapPath(path(it,false), screenUnit, colors) }
                    destination?.let { drawMapTarget(screen(it), screenUnit, colors) }
                    val trail = state.navTest.trail.map { Point(it.x,it.y) }
                    if (trail.size>1) drawPath(path(trail,false),colors.RobotMarker,style=screenStroke(2f))
                }
                // Real position distinct from the simulation.
                if (!demo) {
                    recoveryPoints.zipWithNext().filter { (a, b) -> b.at - a.at <= 5000 }.forEach { (a, b) ->
                        drawPath(path(listOf(Point(a.x, a.y), Point(b.x, b.y)), false),
                            colors.RobotMarker.copy(alpha = 0.6f), style = screenStroke(2f))
                    }
                }
                drawZoneOrder(map, selected.toList(), ::screen, screenUnit, camera.rotation.toFloat())
                if (display.zoneNames) drawZoneNames(zoneNameLabels, selected.toList(), ::screen, scale, screenUnit, camera.rotation.toFloat(), colors)
            }
            // Animated effects (separate layer, the only one redrawn on each frame): robot glided between two readings,
            // halo, comet on the planned route, wave at the start, flash of the chosen zone.
            val fxRoute = (sim.engine?.preview ?: preview)?.takeIf { sim.state == null && !showingRobotRoute }?.cuttingPaths.orEmpty()
            // Point published 8 cm behind the rotation center: silhouette centered on that center (pivot without apparent skid).
            val fxRobot = marker?.takeIf { !demo }?.let { m -> DisplayPose.published(m.point, m.heading).let { it.copy(heading = it.heading ?: 0.0) } }
            MapFxLayer({ camera }, { mapScreenProjection(bounds, it, viewportTop, viewportBottom) }, Modifier.fillMaxSize().mapAppear(map.shapes),
                robot = fxRobot, robotFresh = marker?.fresh == true,
                route = fxRoute.takeIf { MapDisplaySettings.current.routeAnimation }.orEmpty(), routeStart = fxRoute.firstOrNull { it.size >= 2 }?.first(), map = map, selected = selected,
                followOffset = { follow.offset?.takeIf { follow.enabled } })
            RobotFollowDriver(follow, camera, fxRobot, mapSize, viewportTop, viewportBottom) { mapScreenProjection(bounds, it, viewportTop, viewportBottom) }
            // Robot camera: button always present at the top right, including with the robot stopped (stream on demand);
            // the live view (moving or requested) shows on its left, can be hidden.
            // Outside the right bar: added to that centered column, it pushed the last button under the panels.
            // Thumbnail that can be moved (drag) and resized (bottom left corner, pinch), position saved.
            if (!demo) LiveCameraWindow(Modifier.padding(end = Dimens.Md + 44.dp + Dimens.Sm, top = 72.dp))
            fr.luba.poc.camera.LiveCameraButton(Modifier.align(Alignment.TopEnd).padding(end = Dimens.Md, top = 72.dp), enabled = !demo)

            // Scale: round length in meters, at the top left under the back button, recomputed on zoom.
            if (MapDisplaySettings.current.scaleBar && mapSize.width > 0) MapScaleBar(
                mapScreenProjection(bounds, androidx.compose.ui.geometry.Size(mapSize.width.toFloat(), mapSize.height.toFloat()),
                    viewportTop, viewportBottom).scale * camera.zoom,
                Modifier.align(Alignment.TopStart).padding(start = Dimens.Md, top = 72.dp))

            // Mammotion header: back, status pill (Bluetooth or gateway link, positioning, batteries, weather).
            val fresh = state.connection == ConnectionState.Receiving && (state.ageMs ?: Long.MAX_VALUE) < 5000
            val shownTelemetry = if (cloud) apiPosition ?: fr.luba.poc.protocol.Telemetry() else telemetry
            // Charge: Mammotion account (API), or active BLE if it is more recent.
            val charge = if (demo) null else fr.luba.poc.ui.components.rememberChargeStatus(apiPosition, telemetry.takeIf { state.ready })
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(Dimens.Md),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) MapSideButton(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back", "Retour"), onBack)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    DeviceStatusPill(bluetooth = state.ready && !demo, battery = if (demo) null else charge?.percent ?: shownTelemetry.battery ?: (if (cloud) apiKnown?.battery else known?.battery),
                        position = positionQuality(shownTelemetry.rtkStatus), cloud = cloud,
                        charging = charge != null, gateway = !demo && !cloud, viaGateway = state.viaGateway && !demo && !cloud,
                        stale = state.ready && !fresh && !demo && !cloud,
                        threshold = if (demo) null else fr.luba.poc.ui.components.batteryThreshold(charge != null, state.robotRoute,
                            chargeLow.roundToInt(), chargeHigh.roundToInt()),
                        onClick = { statusPanel = true }) {
                        // Garden weather: detail on tap (separate composable, outside the MapScreen registers).
                        WeatherPillSegment(state.mowing.running || state.robotRoute.running, telemetry)
                    }
                }
                if (onBack != null) Spacer(Modifier.size(44.dp))
            }

            // Right vertical bar: compass, station, recentering, following, move to a point (layers: bottom bar, Map menu).
            // Set under the camera button (not centered): centered, its last button went under the panels.
            // In the same layout as the bottom panels: the bar stays on top, never hidden by the protection alert, the
            // layers or the other panels, which are narrowed to its left when they rise up to it.
            MapOverlayLayout(sideTop = 72.dp + 44.dp + Dimens.Md, sideEnd = Dimens.Md, horizontal = Dimens.Md, bottom = Dimens.Sm,
                gap = Dimens.Sm, modifier = Modifier.fillMaxSize(), side = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Sm),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // Compass: follows the map rotation, a tap brings north back to the top.
                MapCompass(camera.rotation, { degrees -> camera = camera.rotatedBy(degrees) })
                MapSideButton(Icons.Rounded.EvStation, tr("Charging station", "Station de charge"), { baseDialog = true },
                    enabled = !demo && (cloudStation || (mapBase != null && !cloud)), tint = colors.Green)
                MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter the map", "Recentrer la carte"), {
                    val from = follow.view(camera); follow.stop()
                    scope.launch { animateCamera(from, MapCamera()) { camera = it } }
                })
                // Robot following: map centered on it, zoom kept; a second tap stops it.
                MapSideButton(if (follow.enabled) Icons.Rounded.GpsFixed else Icons.Rounded.MyLocation,
                    if (follow.enabled) tr("Stop following the robot", "Arrêter le suivi du robot") else tr("Follow the robot", "Suivre le robot"), {
                        if (follow.enabled) { camera = follow.view(camera); follow.stop() } else follow.start()
                    }, enabled = !demo && marker != null, tint = if (follow.enabled) colors.Green else colors.TextPrimary)
                MapSideButton(Icons.Rounded.NearMe, tr("Move to a point", "Déplacer à un point"), {
                    pointMode = true; destination = null; pointConfirmed = false; pointAttempted = false; selectingZones = false
                }, enabled = pointIdle && !state.navTest.running && map.complete && !placingAccess && !pointMode)
                // Layers: show the signal surveys above the map (bottom bar), or fold them.
                MapSideButton(Icons.Rounded.Layers, if (layersOpen) tr("Hide the layers", "Masquer les calques") else tr("Show the signal layers", "Afficher les calques des signaux"), {
                    layersOpen = !layersOpen; showSignalHeat = layersOpen
                }, enabled = !demo || simulatedSurvey, tint = if (layersOpen && showSignalHeat && displayedSignalSamples.isNotEmpty()) colors.Green else colors.TextPrimary)
            }
            }, panels = {
                // Robot STOP button pressed (self-check 11) or another protection (25 on 2026-10-14): no movement possible
                // before unlocking; the attempt stays without movement.
                val selfCheck = state.telemetry.selfCheck
                val stopLock = fr.luba.poc.protocol.StopUnlock.locked(selfCheck)
                // Robot stuck and mowing interrupted: a single panel (2026-10-15, 17:53: two stacked technical panels
                // covered the whole map). The resume shows once the robot is unlocked.
                val blockedCheckpoint = state.missionCheckpoint?.takeIf { !state.robotRoute.running && sim.engine == null && !pointMode && !placingAccess }
                // Self-check 25 beyond the mapped zones, bumper clear: not a lock for the blade-OFF moves (2026-10-20,
                // [fr.luba.poc.protocol.OffMapLatch]); a note replaces the "Robot stuck" panel.
                val offMapLatch = state.ready && !demo && !cloud && !state.mowing.active && !state.robotRoute.running &&
                    fr.luba.poc.protocol.OffMapLatch.ignored(selfCheck, state.telemetry.sensorStatus,
                        fr.luba.poc.simulation.PointPlanner.beyondMapped(state.robotMap, state.telemetry.x?.let { x -> state.telemetry.y?.let { y -> Point(x, y) } }))
                var offMapHidden by remember(selfCheck) { mutableStateOf(false) }
                val robotBlocked = state.ready && !demo && !cloud && ((fr.luba.poc.protocol.StopUnlock.blocked(selfCheck) && !offMapLatch) || state.unlockPending)
                // Forbidden time range of the Mammotion app (self-check 23): nothing to unlock, a message is enough (can be
                // hidden until the next state change).
                var forbiddenHidden by remember(selfCheck) { mutableStateOf(false) }
                if (robotBlocked && fr.luba.poc.protocol.SelfCheck.forbiddenTime(selfCheck) && !state.unlockPending) {
                    if (!forbiddenHidden) MapFloatingNote(tr("Outside the working hours set in the Mammotion app: the robot refuses to work " +
                        "until the end of this range. Nothing to unlock. To stop being blocked, disable this range in the Mammotion " +
                        "app and use the DumbyLuba one (Task settings): clean stop, return to base " +
                        "and resume at the interrupted point.",
                        "Hors de la plage horaire réglée dans l'app Mammotion : le robot refuse de travailler " +
                        "jusqu'à la fin de cette plage. Rien à déverrouiller. Pour ne plus être bloqué, désactiver cette plage dans l'app " +
                        "Mammotion et utiliser celle de DumbyLuba (Paramètres de la tâche) : arrêt propre, retour à la base " +
                        "et reprise au point interrompu."), NoteTone.WARNING) { forbiddenHidden = true }
                }
                else if (offMapLatch && !robotBlocked && !offMapHidden && !state.navTest.running && !state.manualActive)
                    MapFloatingNote(tr("Beyond the mapped zones (self-check 25): ignored, joystick and move to a point stay available, blades stopped.",
                        "Hors des zones cartographiées (self-check 25) : ignoré, joystick et déplacement au point restent possibles, lames arrêtées."),
                        NoteTone.INFO) { offMapHidden = true }
                else if (robotBlocked) {
                    val collision = fr.luba.poc.protocol.CollisionLatch.latched(selfCheck)
                    var details by remember { mutableStateOf(false) }
                    MapPanel(when { stopLock -> tr("Robot locked after STOP", "Robot verrouillé après STOP"); collision -> tr("Robot stuck against an obstacle", "Robot bloqué contre un obstacle"); else -> tr("Robot protection active", "Protection du robot active") },
                        subtitle = blockedCheckpoint?.let { tr("${it.task.title} at ${(it.progress * 100).toInt()} % · resume possible once the robot is cleared", "${it.task.title} à ${(it.progress * 100).toInt()} % · reprise possible une fois le robot dégagé") }) {
                        Text(state.unlockStatus.ifBlank { when {
                            stopLock -> tr("The robot's STOP button was used: it refuses any movement.", "Le bouton STOP du robot a été utilisé : il refuse tout déplacement.")
                            collision -> tr("It touched an object and refuses any movement while it stays against it. \"Clear\" backs it off by 30 cm, blades stopped.", "Il a touché un objet et refuse tout déplacement tant qu'il reste contre lui. « Dégager » le fait reculer de 30 cm, lames arrêtées.")
                            else -> tr("It refuses any movement while its protection stays active.", "Il refuse tout déplacement tant que sa protection reste active.")
                        } }, style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
                        if (details) InfoNote((if (collision) tr("30 cm straight back-off, blades stopped, then wait for the lock to be released (self-check $selfCheck).", "Recul de 30 cm en ligne droite, lames arrêtées, puis attente de la levée du verrou (self-check $selfCheck).")
                            else tr("Unlock without movement: stop, cancel any Mammotion task, read the errors again (self-check $selfCheck).", "Déverrouillage sans mouvement : arrêt, annulation de la tâche Mammotion éventuelle, relecture des erreurs (self-check $selfCheck).")) +
                            tr(" If the robot refuses: ${fr.luba.poc.protocol.StopUnlock.physical(selfCheck)}.", " Si le robot le refuse : ${fr.luba.poc.protocol.StopUnlock.physical(selfCheck)}.") +
                            (blockedCheckpoint?.interruption?.let { tr("\nStop: ${InterruptionSummary.of(it).detail ?: it}", "\nArrêt : ${InterruptionSummary.of(it).detail ?: it}") } ?: ""))
                        TextAction(if (details) tr("Hide the details", "Masquer les détails") else tr("Details", "Détails"), { details = !details })
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            if (blockedCheckpoint != null) SecondaryPillButton(tr("Discard", "Abandonner"), { discardConfirm = true }, Modifier.weight(1f),
                                enabled = !state.unlockPending)
                            PrimaryPillButton(if (state.unlockPending) tr("Unlocking…", "Déverrouillage…") else if (collision) tr("Clear", "Dégager") else tr("Unlock", "Déverrouiller"), onUnlockRobot,
                                Modifier.weight(1f), enabled = !state.unlockPending && !state.navTest.running && !state.robotRoute.running && !state.manualActive)
                        }
                    }
                }
                else if (state.unlockStatus.startsWith(tr("Robot unlocked", "Robot déverrouillé")) && !pointMode)
                    MapFloatingNote(state.unlockStatus, NoteTone.SUCCESS)
                // Travel speed during mowing: set in the details ("⋯" of the mowing bar), not on the map.
                if (pointMode) PointMovePanel(state, destination, pointPlan, pointComputing, pointError, pointAttempted, pointConfirmed,
                    { pointConfirmed = it }, pointIdle, onTrajectorySpeed, onStopPointNavigation,
                    onStart = { pointAttempted = true; destination?.let(onStartPointNavigation) },
                    onClose = { onStopPointNavigation(); pointMode = false; destination = null })
                if (placingAccess) MapPanel(tr("Manual access · ${draftAccess.size} points", "Accès manuel · ${draftAccess.size} points"),
                    subtitle = tr("Draw a free passage into the zones. Assumed width: 0.8 m + margin.", "Tracez un passage libre jusqu'à l'intérieur des zones. Largeur supposée : 0,8 m + marge."),
                    onClose = { placingAccess = false; draftAccess = emptyList() }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                        TextAction(tr("Undo the point", "Annuler le point"), { draftAccess = draftAccess.dropLast(1) }, enabled = draftAccess.isNotEmpty())
                        Spacer(Modifier.weight(1f))
                        PrimaryPillButton(tr("Validate", "Valider"), { accesses = accesses + listOf(draftAccess); baseStore.saveAccesses(accesses); placingAccess = false; draftAccess = emptyList() },
                            enabled = draftAccess.size >= 2)
                    }
                }
                val checkpoint = state.missionCheckpoint
                val interruptedShown = checkpoint != null && !robotBlocked && !state.robotRoute.running && !demo && !cloud && !pointMode && !placingAccess && sim.engine == null
                // Incident panel (robot stuck, task interrupted): it carries the progress and the actions; the stopped mowing
                // bar and the layer strip are hidden to keep the map visible (small Fold screen, 2026-10-16).
                val problemShown = interruptedShown || (robotBlocked && !fr.luba.poc.protocol.SelfCheck.forbiddenTime(selfCheck))
                if (checkpoint != null && interruptedShown)
                    InterruptedMowingPanel(checkpoint, map, state, resumeBusy, resumeError,
                        resumeEnabled = state.ready && mapBase != null && map.complete && !resumeBusy && !computing && !simBuilding && !state.campaign.running &&
                            !state.navTest.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading && !state.manualActive && !state.silenceTest.running &&
                            !state.backOffPending && !state.unlockPending,
                        skip = if (skipMode) resumeSkip else null, skipOpen = skipMode, skipMeters = skipMeters,
                        onSkipMeters = { skipMeters = it }, onOpenSkip = ::openResumeSkip, onCloseSkip = { skipMode = false; skipMeters = 0f },
                        onBackOff = ::backOffBeforeResume, onStopBackOff = onStopBackOff,
                        onDiscard = { discardConfirm = true }, onResume = ::resumeMission)
                // Interrupted move to a point (stop, link, restart): resume towards the same destination.
                else if (!state.robotRoute.running && !state.navTest.running && !demo && !cloud && !pointMode && !placingAccess && sim.engine == null)
                    state.pointResume?.takeIf { it.robotId == state.selected?.address }?.let { resume ->
                        InterruptedPointPanel(resume, state, enabled = pointIdle && !state.navTest.running && map.complete,
                            onResume = { onStartPointNavigation(resume.target) }, onDiscard = onDiscardPointResume)
                    }
                // Heatmap layers: one button per measured layer, settings and legend behind the right icon.
                // Without a survey on this map, the bar says so and opens the history.
                val heat = surveyMatches && displayedSignalSamples.isNotEmpty()
                val layerBar = layersOpen && (heat || (!demo && sim.engine == null && !pointMode && !placingAccess && !selectingZones))
                if (layerBar && !problemShown) MapLayerBar(if (heat) availableLayers(displayedSignalSamples, signalView.relief != null, surveySignal) else emptyList(), surveySignal,
                    visible = showSignalHeat, simulated = simulatedSurvey, onSelect = { surveySignal = it; showSignalHeat = true },
                    onToggleVisible = { showSignalHeat = !showSignalHeat },
                    onSettings = { if (heat) signalPanel = true else { simulation.pause(); signalHistoryOpen = true } },
                    source = pinnedSurvey?.takeIf { !simulatedSurvey && it === overlaySurvey }?.let { surveyShortLabel(it) },
                    onClearSource = { SignalOverlay.clear() })
                if (state.robotRoute.showSupervision && (state.robotRoute.running || !problemShown)) MowingLiveBar(state, onStop) { signalPanel = true }
                else if (sim.engine != null && !demo && !cloud && !heat) {
                    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { signalPanel = true }.padding(vertical = 8.dp)) {
                                Text(tr("Simulated route", "Trajet simulé"), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tr("Run this route on the robot", "Exécuter ce trajet sur le robot"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { signalPanel = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Route and local mowing details", "Détails du trajet et de la tonte locale")) }
                        }
                    }
                }
                sim.state?.takeIf { !showingRobotRoute }?.let { s ->
                    val total = sim.engine?.total ?: return@let
                    val coverage = (s.covered / total).toFloat().coerceIn(0f, 1f)
                    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { simulationDialog = true }.padding(vertical = 6.dp)) {
                                    Text("Simulation · ${s.phase.label}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
                                        Text(tr("Mowed ${(coverage * 100).toInt()} %", "Tondu ${(coverage * 100).toInt()} %"), style = MaterialTheme.typography.labelMedium, color = colors.Green)
                                        Text(tr("Battery ${s.battery.toInt()} %", "Batterie ${s.battery.toInt()} %"), style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                                        Text("${(s.elapsed / 60).toInt()} min", style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                                    }
                                }
                                if (s.phase !in setOf(SimPhase.COMPLETE, SimPhase.BLOCKED)) IconButton(
                                    onClick = simulation::toggle, enabled = !state.robotRoute.running
                                ) { Icon(if (sim.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (sim.playing) tr("Pause the simulation", "Mettre la simulation en pause") else tr("Resume the simulation", "Reprendre la simulation")) }
                                Box {
                                    TextAction("×${sim.speed}", { simulationSpeedMenu = true })
                                    DropdownMenu(expanded = simulationSpeedMenu, onDismissRequest = { simulationSpeedMenu = false }) {
                                        listOf(1, 5, 20, 60, 120, 300).forEach { speed ->
                                            DropdownMenuItem(text = { Text("×$speed${if (sim.speed == speed) tr(" · selected", " · sélectionné") else ""}") },
                                                onClick = { simulation.speed(speed); simulationSpeedMenu = false })
                                        }
                                    }
                                }
                                IconButton(onClick = { simulationDialog = true }) { Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Simulation details and speed", "Détails et vitesse de simulation")) }
                            }
                            LubaProgress(coverage, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp))
                            if (s.phase == SimPhase.BLOCKED) StatusNote(s.log.lastOrNull().orEmpty(), NoteTone.DANGER,
                                Modifier.padding(horizontal = Dimens.Md, vertical = Dimens.Xs))
                        }
                    }
                }
                if (computing && !mowingDialog) MapPanel(tr("Computing the route", "Calcul du trajet"), subtitle = progress.third) {
                    LubaProgress(progress.first.toFloat() / progress.second.coerceAtLeast(1), Modifier.fillMaxWidth())
                }
                if (error.isNotBlank() && !mowingDialog) MapFloatingNote(error, NoteTone.DANGER, onDismiss = { error = "" })
                // Zone choice on the map, in mowing order (numbers on the map).
                if (selectingZones && !pointMode && !placingAccess && sim.engine == null) MapPanel(if (surveyTask) tr("Survey zones", "Zones du relevé") else tr("Mowing zones", "Zones de tonte"),
                    subtitle = if (!map.complete) tr("Load a complete map first (Map button).", "Chargez d'abord une carte complète (bouton Carte).")
                        else if (surveyTask) tr("Tap the zones to drive through, blades stopped: they will be surveyed in this order.", "Touchez les zones à parcourir, lames arrêtées : elles seront relevées dans cet ordre.")
                        else tr("Tap the zones in mowing order: they will be mowed in this order.", "Touchez les zones dans l'ordre de tonte : elles seront tondues dans cet ordre."),
                    onClose = { selectingZones = false }, closeDescription = tr("Close the zone choice", "Fermer le choix des zones")) {
                    ZoneOrderChips(map, selected.toList(), onRemove = { id -> selected = selected - id })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
                        TextAction(tr("Add all", "Tout ajouter"), { selected = selected + map.shapes.filter { it.kind == ShapeKind.ZONE }.map { it.id } }, enabled = map.complete)
                        TextAction(tr("Clear", "Effacer"), { selected = emptySet() }, enabled = selected.isNotEmpty())
                        Spacer(Modifier.weight(1f))
                        PrimaryPillButton(tr("Next", "Suivant"), { selectingZones = false; mowingStep = 1; mowingDialog = true },
                            enabled = selected.isNotEmpty() && map.complete && !computing)
                    }
                }
                val showActions = !pointMode && !placingAccess && !selectingZones && sim.engine == null && !state.robotRoute.running
                if (showActions) {
                    // Link, positioning and battery are in the top pill: only a Mammotion task in progress or the demo map
                    // are still flagged above the bar.
                    if (demo || state.mowing.active) MapFloatingNote(if (demo) tr("Demo map", "Carte de démonstration") else state.mowing.status, NoteTone.INFO)
                    // No map on this phone (new installation, cache cleared): reading triggered from here, over Bluetooth
                    // by the gateway when the pilot goes through it, instead of an empty map with the action hidden in the sheet.
                    if (!demo && !map.complete && !state.mowing.active) NoMapPanel(state, cloud, mapFetchAllowed(state, false),
                        onFetch = { demo = false; onFetchMap() }, onAccount = onOpenAccount)
                    ActionBar {
                        // Mow goes back to the preparation in progress (preview already computed); otherwise zone choice.
                        ActionBarItem(Icons.Rounded.PlayArrow, if (state.mowing.active) tr("Follow", "Suivre") else tr("Mow", "Tondre"), colors.ActionMow, {
                            when {
                                state.mowing.active -> mowingDialog = true
                                preview != null && !surveyTask -> { mowingStep = 2; mowingDialog = true }
                                else -> selectTask(false)
                            }
                        }, Modifier.weight(1f), highlighted = true)
                        ActionBarItem(Icons.Rounded.Gamepad, tr("Manual", "Manuel"), colors.ActionManual, onOpenManual, Modifier.weight(1f), enabled = state.ready && !demo && !cloud)
                        ActionBarItem(Icons.Rounded.History, tr("History", "Historique"), colors.ActionTasks, { taskHistory = true }, Modifier.weight(1f))
                        ActionBarItem(Icons.Rounded.Map, tr("Map", "Carte"), colors.ActionMap, { mapActions = true }, Modifier.weight(1f))
                    }
                }
            })
            }
        }
    }

    if (signalPanel) ModalBottomSheet(onDismissRequest = { signalPanel = false }, containerColor = colors.Background) {
        val heat = surveyMatches && displayedSignalSamples.isNotEmpty()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Lg).padding(bottom = Dimens.Lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Text(if (heat) tr("Signals and terrain", "Signaux et relief") else if (state.robotRoute.started) tr("Local mowing", "Tonte locale") else tr("Simulated route", "Trajet simulé"), style = MaterialTheme.typography.headlineSmall)
            if (heat) SettingsGroup("${if (simulatedSurvey) tr("Simulated heatmap", "Heatmap simulée") else tr("Measured heatmap", "Heatmap mesurée")} · ${tr("${displayedSignalSamples.size} readings", "${displayedSignalSamples.size} relevés")}") {
                SignalReliefControls(signalView, displayedSignalSamples, onSignal = { surveySignal = it; showSignalHeat = true },
                    onShading = { reliefShading = it }, mount = if (simulatedSurvey) null else reliefMount,
                    onMount = { reliefMount = it; ReliefSettings.saveMount(context,it) }, on3d = { relief3dOpen = true },
                    hidden = !showSignalHeat, onHide = { showSignalHeat = false },
                    calibration = tiltCalibration, onCalibrate = if (simulatedSurvey) null else ({ signalPanel = false; calibrationOpen = true }))
                InfoNote(if (simulatedSurvey) tr("Fictional signals and terrain generated during the simulation.", "Signaux et terrain fictifs générés pendant la simulation.")
                    else tr("Robot readings on the driven cells. Relative terrain (no absolute altitude), deduced from the slopes of the phone mounted on the robot; positions less accurate without Fix.", "Mesures du robot sur les cellules parcourues. Relief relatif (sans altitude absolue), déduit des pentes du téléphone posé sur le robot ; positions moins précises sans Fix."))
                if (simulatedSurvey && sim.error.startsWith(tr("Heatmap save", "Sauvegarde heatmap"))) StatusNote(sim.error, NoteTone.DANGER)
                if (!simulatedSurvey && state.surveySaveError) StatusNote(tr("Could not save the survey", "Sauvegarde du relevé impossible"), NoteTone.DANGER)
                NavRow(tr("Saved heatmaps", "Heatmaps enregistrées"), icon = Icons.Rounded.WifiTethering, onClick = { simulation.pause(); signalPanel = false; signalHistoryOpen = true })
            }
            if (state.robotRoute.started) SettingsGroup(tr("Local mowing", "Tonte locale")) {
                KeyValueRow(tr("State", "État"), state.robotRoute.status)
                KeyValueRow(tr("Height", "Hauteur"), "${state.robotRoute.heightMm} mm")
                KeyValueRow(tr("Recharge", "Recharge"), tr("return ${state.robotRoute.returnPercent} % · resume ${state.robotRoute.resumePercent} %", "retour ${state.robotRoute.returnPercent} % · reprise ${state.robotRoute.resumePercent} %"))
                KeyValueRow(tr("Recharges", "Recharges"), rechargeLine(state))
                if (state.robotRoute.running) SliderRow(tr("Travel speed", "Vitesse de déplacement"), state.trajectorySpeedPercent.toFloat(), { onTrajectorySpeed(it.toInt()) },
                    valueRange = 10f..100f, valueLabel = "${state.trajectorySpeedPercent} %")
                if (state.robotRoute.running) DangerPillButton(tr("Stop", "Arrêter"), onStop, Modifier.fillMaxWidth())
            }
            // The simulated route can be handed to the real robot (same route, same checks).
            if (sim.engine != null && sim.engine?.preview?.localOnly != true && !demo && !cloud && !state.robotRoute.running) SettingsGroup(tr("Run this route", "Exécuter ce trajet")) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    SecondaryPillButton(tr("Follow, blades stopped", "Suivre, lames arrêtées"), { simulation.pause(); onStartRobotRoute(sim.engine!!, false, sim.engine!!.preview.cutHeights.firstOrNull() ?: mowingHeight.toInt()) }, Modifier.weight(1f), enabled = state.ready)
                    if (sim.engine?.preview?.mode?.isSurvey != true)
                        PrimaryPillButton(tr("Mow", "Tondre"), { simulation.pause(); onStartRobotRoute(sim.engine!!, true, sim.engine!!.preview.cutHeights.firstOrNull() ?: mowingHeight.toInt()) }, Modifier.weight(1f), enabled = state.ready, icon = Icons.Rounded.PlayArrow)
                }
                if (!state.ready) InfoNote(tr("Connect the robot to run this route.", "Connectez le robot pour exécuter ce trajet."))
            }
        }
    }

    // Map sheets and dialogs: separate method (register limit, see IsolatedBox).
    Isolated {
        EncounterSheet(state.difficulties, state.obstaclePhoto.photos, state.obstacles)
        if (taskHistory) fr.luba.poc.history.TaskHistorySheet(state.mowingHistory, state.mowingRecord,
            onOpen = { taskHistory = false; onOpenTask(it) }, onOpenAll = { taskHistory = false; onOpenAllTasks() }, onDismiss = { taskHistory = false })
        if (statusPanel) DeviceStatusSheet(state, if (cloud) apiPosition ?: fr.luba.poc.protocol.Telemetry() else telemetry,
            context = when {
                showingRobotRoute -> tr("Local mowing · ${state.robotRoute.index}/${state.robotRoute.count}", "Tonte locale · ${state.robotRoute.index}/${state.robotRoute.count}")
                sim.engine != null -> MapSource.SIMULATION.label
                demo -> MapSource.DEMO.label
                cloud -> MapSource.ACCOUNT.label
                else -> MapSource.ROBOT.label
            },
            marker = marker.takeIf { !demo }, known = known.takeIf { !demo && sim.engine == null },
            onOpenRobot = { statusPanel = false; onOpenRobot() }, onDismiss = { statusPanel = false })

        if (calibrationOpen) TiltCalibrationDialog(
            // Current orientation: that of the displayed real survey if known, otherwise the manual choice.
            orientation = relief?.mount?.takeIf { !simulatedSurvey && it.source != MountSource.DEFAULT }
                ?: reliefMount.angle?.let { MountEstimate(it, MountSource.MANUAL) }
                ?: tiltCalibration?.angle?.let { MountEstimate(it, MountSource.CALIBRATED) },
            calibration = tiltCalibration,
            onSaved = { c -> ReliefSettings.saveCalibration(context,c); tiltCalibration = c
                if (c.angle != null) { reliefMount = PhoneMount.CALIBRATED; ReliefSettings.saveMount(context,PhoneMount.CALIBRATED) } },
            onCleared = { ReliefSettings.clearCalibration(context); tiltCalibration = null
                if (reliefMount == PhoneMount.CALIBRATED) { reliefMount = PhoneMount.AUTO; ReliefSettings.saveMount(context,PhoneMount.AUTO) } },
            onDismiss = { calibrationOpen = false })
        if (signalHistoryOpen) SignalHistoryDialog(signalHistoryStore,map,reliefMount,tiltCalibration,onMount={ reliefMount=it; ReliefSettings.saveMount(context,it) },onDismiss={ signalHistoryOpen=false },
            onShowOnMap={ survey -> SignalOverlay.show(survey.takeIf { it.sessionId!=state.signalSurvey.sessionId }); signalHistoryOpen=false })
        if (relief3dOpen) Relief3DDialog(signalView,if (simulatedSurvey) sim.engine?.map ?: map else map,
            signalReliefLegend(signalView,displayedSignalSamples),onDismiss={ relief3dOpen=false })
        if (mapActions) MapSettingsSheet(
            source = when { sim.engine != null -> MapSource.SIMULATION; demo -> MapSource.DEMO; cloud -> MapSource.ACCOUNT; else -> MapSource.ROBOT },
            summary = mapSummary(map),
            heat = MapHeatChoice(if (surveyMatches && displayedSignalSamples.isNotEmpty())
                availableLayers(displayedSignalSamples, signalView.relief != null, surveySignal) else emptyList(),
                surveySignal, showSignalHeat, layersOpen, simulatedSurvey),
            mapLoading = state.mapLoading,
            fetchEnabled = mapFetchAllowed(state, sim.engine != null),
            accountEnabled = sim.engine == null,
            demoEnabled = !computing && !state.robotRoute.running && !state.mowing.active && sim.engine == null,
            onSignal = { surveySignal = it; showSignalHeat = true },
            onHeatVisible = { showSignalHeat = it },
            onLayerBar = { layersOpen = it },
            onFetch = { demo = false; mapActions = false; onFetchMap() },
            onAccount = { mapActions = false; onOpenAccount() },
            onToggleDemo = { demo = !demo; preview = null; mapActions = false },
            onHistory = { mapActions = false; signalHistoryOpen = true },
            onCalibration = { mapActions = false; calibrationOpen = true },
            onDismiss = { mapActions = false },
            corrections = state.mapCorrections.count { it.enabled },
            correctionsEnabled = !demo && sim.engine == null && !state.robotRoute.running && !state.mowing.active && state.mammotionMap.complete,
            onCorrections = { mapActions = false; correcting = true },
            recordEnabled = state.ready && !demo && !cloud && sim.engine == null && !state.robotRoute.running && !state.mowing.active && state.mammotionMap.complete,
            onRecord = { mapActions = false; onOpenManual() })
        if (correcting) androidx.compose.ui.window.Dialog(onDismissRequest = { correcting = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                dismissOnBackPress = false, dismissOnClickOutside = false)) {
            MapCorrectionEditor(state.mammotionMap, state.mapCorrections, onSaveCorrections, onClose = { correcting = false })
        }

        if (simulationDialog) SimulationDialog(
            ui = sim, preview = preview.takeIf { preparedSimulation != null }, base = mapBase, map = map, selected = selected, manualBase = demo,
            onSelection = { if (zoneSelectionAllowed) selected = it }, zoneSelectionAllowed = zoneSelectionAllowed,
            initialProgress = initialProgress, onProgress = { initialProgress = it },
            battery = initialBattery, onBattery = { initialBattery = it }, autonomy = autonomy, onAutonomy = { autonomy = it },
            chargeTime = chargeTime, onChargeTime = { chargeTime = it }, building = simBuilding,
            onSpeed = simulation::speed, onToggle = { if (!state.robotRoute.running) simulation.toggle() },
            onClose = { simulationDialog = false }, onClear = { if (!state.robotRoute.running) { simulation.clear(); simulationDialog = false; selectingZones = true } },
            onPreview = { simulationDialog = false; mowingStep = 1; mowingDialog = true },
            onStart = ::startMowingSimulation, chargeLow = chargeLow, chargeHigh = chargeHigh,
        )

    }
    // Task pages: separate method (register limit, see IsolatedBox).
    Isolated {
        if (mowingDialog) {
            val back = { if (!computing && !simBuilding) { if (mowingStep == 2 && !state.robotRoute.running && !state.mowing.active) { mowingStep = 1; estimate = null } else mowingDialog = false } }
            androidx.activity.compose.BackHandler(onBack = back)
            val zoneLine = selectedZoneLine(map, (preview?.zoneIds?.takeIf { it.isNotEmpty() } ?: selected.toList()))
            when {
                state.robotRoute.running || state.mowing.active -> PushedPage(if (state.robotRoute.running) tr("Mowing in progress", "Tonte en cours") else tr("Mammotion task active", "Tâche Mammotion active"), onBack = { mowingDialog = false },
                    bottomBar = { DangerPillButton("STOP", onStop, Modifier.fillMaxWidth()) }) {
                    if (state.robotRoute.running) {
                        SettingsGroup(tr("Progress", "Avancement")) {
                            val live = fr.luba.poc.ui.MowingLiveStats.of(state.telemetry, state.robotRoute)
                            Text(state.robotRoute.status, style = MaterialTheme.typography.titleMedium)
                            live.fraction?.let { done ->
                                Text(live.progress.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = colors.GreenDeep)
                                LubaProgress(done, Modifier.fillMaxWidth())
                            }
                            if (state.robotRoute.cutProgress != null) InfoNote(tr("Share of cutting stripes mowed, excluding travel with blades stopped.", "Part des bandes de coupe tondues, sans les déplacements lames arrêtées."))
                            KeyValueRow(tr("Blades", "Lames"), live.blades)
                            live.height?.let { KeyValueRow(tr("Reported height", "Hauteur rapportée"), it.removePrefix(tr("Height ", "Hauteur "))) }
                        }
                        SettingsGroup(tr("Settings", "Réglages")) {
                            KeyValueRow(tr("Cutting height", "Hauteur de coupe"), CutPasses.progress(state.robotRoute.cutHeights, state.robotRoute.heightMm))
                            KeyValueRow(tr("Recharge", "Recharge"), tr("return at ${state.robotRoute.returnPercent} % · resume at ${state.robotRoute.resumePercent} %", "retour à ${state.robotRoute.returnPercent} % · reprise à ${state.robotRoute.resumePercent} %"))
                            KeyValueRow(tr("Battery", "Batterie"), state.telemetry.battery?.let { "$it %" } ?: tr("unknown", "inconnue"))
                            KeyValueRow(tr("Recharges", "Recharges"), rechargeLine(state))
                            state.mowingRecord?.let { r ->
                                KeyValueRow(tr("Duration", "Durée"), tr("${fr.luba.poc.history.MowingText.duration(r.totals.totalSeconds)} / ≈ ${fr.luba.poc.history.MowingText.duration(r.forecast.totalSeconds)} estimated", "${fr.luba.poc.history.MowingText.duration(r.totals.totalSeconds)} / ≈ ${fr.luba.poc.history.MowingText.duration(r.forecast.totalSeconds)} estimée"))
                            }
                            SliderRow(tr("Travel speed", "Vitesse de déplacement"), state.trajectorySpeedPercent.toFloat(), { onTrajectorySpeed(it.toInt()) },
                                valueRange = 10f..100f, valueLabel = "${state.trajectorySpeedPercent} %")
                        }
                    } else StatusNote(tr("A robot activity is still reported. Stop it before preparing a new task.", "Une activité robot est encore signalée. Arrêtez-la avant de préparer une nouvelle tâche."), NoteTone.WARNING)
                }
                mowingStep < 2 -> PushedPage(if (surveyTask) tr("Survey route", "Parcours de relevé") else tr("Task settings", "Paramètres de la tâche"), onBack = back,
                    bottomBar = {
                        val area = CoveragePlanner.selectedArea(map, selected)
                        val rough = if (area > 0 && !mode.isSurvey) realEstimate(state, demo, area / spacing.coerceAtLeast(0.05f) * passHeights.size,
                            mowingSpeed, autonomy, chargeTime, initialBattery, chargeLow, chargeHigh) else null
                        Text("${Format.num(area, 0)} m²${rough?.let { tr(" · ≈ ${durationText(it.totalSeconds)} (estimate before computation)", " · ≈ ${durationText(it.totalSeconds)} (estimation avant calcul)") } ?: ""}",
                            style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        PrimaryPillButton(if (computing) tr("Computing…", "Calcul…") else tr("Route preview", "Aperçu du trajet"), { calculateMowingPreview(); mowingStep = 2 },
                            Modifier.fillMaxWidth(), enabled = selected.isNotEmpty() && map.complete && !computing, icon = Icons.Rounded.Route)
                    }) {
                    ZoneOrderGroup(selected.size, zoneLine, onEdit = { mowingDialog = false; selectingZones = true }, editEnabled = !computing)
                    // Mowing beyond the Mammotion boundaries (DumbyLuba zones, enlargements): recalled before every task.
                    val beyond = remember(map, selected) { MapCorrections.beyondMammotion(map, selected) }
                    if (beyond >= 0.05) StatusNote(tr("Includes ${MapCorrections.squareMeters(beyond)} beyond the Mammotion boundaries (DumbyLuba zones, enlargements; hatched on the map): this surface only exists on this phone and is mowed under your responsibility.",
                        "Dont ${MapCorrections.squareMeters(beyond)} hors des contours Mammotion (zones DumbyLuba, agrandissements ; hachurés sur la carte) : cette surface n'existe que sur ce téléphone et est tondue sous votre responsabilité."), NoteTone.WARNING)
                    // The signal survey is no longer a mowing mode: it is automatic while moving, and the dedicated route opens
                    // from Service → Signal surveys.
                    SettingsGroup(if (surveyTask) tr("Signal survey route", "Parcours de relevé des signaux") else tr("Route mode", "Mode de trajet")) {
                        if (!surveyTask) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            ChoiceTile(Icons.Rounded.Reorder, "Zigzag", mode == CoverageMode.LINES, { mode = CoverageMode.LINES }, Modifier.weight(1f), enabled = !computing)
                            ChoiceTile(Icons.Rounded.AutoAwesome, tr("Optimized", "Optimisé"), mode == CoverageMode.OPTIMIZED, { mode = CoverageMode.OPTIMIZED }, Modifier.weight(1f), enabled = !computing)
                            ChoiceTile(Icons.Rounded.GridOn, tr("Checkerboard", "Damier"), mode == CoverageMode.CHECKERBOARD, { mode = CoverageMode.CHECKERBOARD }, Modifier.weight(1f), enabled = !computing)
                        }
                        if (mode == CoverageMode.OPTIMIZED)
                            InfoNote(tr("Zigzag fitted to the shape: each part takes the direction needing the fewest U-turns.", "Zigzag adapté à la forme : chaque partie prend la direction qui demande le moins de demi-tours."))
                        if (mode.isSurvey) {
                            Segmented(listOf(CoverageMode.TOUR to tr("Full loop", "Grand tour"), CoverageMode.SURVEY_GRID to tr("Grid", "Quadrillage")), mode, { mode = it }, enabled = !computing)
                            InfoNote(if (mode == CoverageMode.TOUR) tr("Blades stopped. Adjacent zones merged: only the shared outer boundary is driven.", "Lames arrêtées. Zones adjacentes fusionnées : seul le contour extérieur commun est parcouru.")
                                else tr("Blades stopped. Merged boundary and crossed grid: the inside of the zones is measured too.", "Lames arrêtées. Contour fusionné et quadrillage croisé : l'intérieur des zones est aussi mesuré."))
                            if (mode == CoverageMode.SURVEY_GRID) SliderRow(tr("Grid spacing", "Maille du quadrillage"), signalGridSpacing, { signalGridSpacing = it.roundToInt().toFloat() }, 1f..10f,
                                valueLabel = "${signalGridSpacing.toInt()} m", steps = 8, enabled = !computing)
                            SurveyOffsetSetting.Slider(!computing && !demo)
                            SliderRow(tr("Survey speed", "Vitesse du relevé"), mowingSpeed, { mowingSpeed = it }, 0.2f..0.6f,
                                valueLabel = "${Format.num(mowingSpeed.toDouble(), 2)} m/s", enabled = !computing)
                        }
                    }
                    if (!mode.isSurvey) {
                        SettingsGroup(tr("Mowing", "Tonte")) {
                            SliderRow(tr("Cutting height", "Hauteur de coupe"), mowingHeight, { mowingHeight = it }, 25f..70f,
                                valueLabel = "${mowingHeight.toInt()} mm", steps = 44, enabled = !computing)
                            // Tall grass: several passes lower and lower, the last one at the cutting height.
                            val maxPasses = CutPasses.maxPasses(mowingHeight.roundToInt())
                            SliderRow(tr("Cutting passes", "Passes de hauteur"), passHeights.size.toFloat(), { heightPasses = it.roundToInt() }, 1f..CutPasses.MAX_PASSES.toFloat(),
                                valueLabel = if (passHeights.size == 1) tr("1 pass", "1 passe") else "${passHeights.size} passes", steps = CutPasses.MAX_PASSES - 2,
                                enabled = !computing && maxPasses > 1)
                            if (passHeights.size > 1) {
                                val firstRange = CutPasses.firstRange(mowingHeight.roundToInt(), passHeights.size)
                                if (firstRange.first < firstRange.last)
                                    SliderRow(tr("First pass height", "Hauteur de la première passe"), passHeights.first().toFloat(), { firstPassHeight = it.roundToInt().toFloat() },
                                        firstRange.first.toFloat()..firstRange.last.toFloat(), valueLabel = "${passHeights.first()} mm",
                                        steps = firstRange.last - firstRange.first - 1, enabled = !computing)
                                InfoNote(tr("Tall grass: ${CutPasses.label(passHeights)}. Every other pass is driven in reverse; " +
                                    "duration multiplied by ${passHeights.size}.",
                                    "Herbe haute : ${CutPasses.label(passHeights)}. Une passe sur deux est parcourue en sens inverse ; " +
                                    "durée multipliée par ${passHeights.size}."))
                            } else if (maxPasses == 1 && heightPasses > 1)
                                InfoNote(tr("Cutting height too high for several passes (at least 5 mm apart, 70 mm at most).", "Hauteur de coupe trop haute pour plusieurs passes (5 mm d'écart au moins, 70 mm au plus)."))
                            SliderRow(tr("Task speed", "Vitesse de la tâche"), mowingSpeed, { mowingSpeed = it }, 0.2f..0.6f,
                                valueLabel = "${Format.num(mowingSpeed.toDouble(), 2)} m/s", enabled = !computing)
                            // Perimeter offset (5 cm to 1 m) with the height and the speed; settings kept.
                            OuterOffsetSetting.Slider(!computing && borderPasses > 0)
                        }
                        WorkWindowGroup(enabled = !computing)
                        SettingsGroup {
                            Row(Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                                Text(tr("Advanced settings", "Réglages avancés"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Text(tr("${(spacing * 100).roundToInt()} cm · ${borderPasses} + ${noGoPasses} loops", "${(spacing * 100).roundToInt()} cm · ${borderPasses} + ${noGoPasses} tours"), style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                                Icon(if (advancedOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.TextSecondary)
                            }
                            if (advancedOpen) {
                                SliderRow(tr("Pass spacing", "Espacement des passes"), spacing, { spacing = (it * 100).roundToInt() / 100f }, 0.05f..0.35f,
                                    valueLabel = "${(spacing * 100).roundToInt()} cm", steps = 29, enabled = !computing)
                                SliderRow(tr("Perimeter loops", "Tours du périmètre"), borderPasses.toFloat(), { borderPasses = it.roundToInt() }, 0f..4f,
                                    valueLabel = "$borderPasses", steps = 3, enabled = !computing)
                                SliderRow(tr("Loops around no-go zones", "Tours autour des zones interdites"), noGoPasses.toFloat(), { noGoPasses = it.roundToInt() }, 0f..4f,
                                    valueLabel = "$noGoPasses", steps = 3, enabled = !computing)
                                ToggleRow(tr("Perimeter first", "Périmètre d'abord"), bordersFirst, { bordersFirst = it },
                                    subtitle = tr("Mow the borders before the inside of the zones.", "Tondre les bordures avant l'intérieur des zones."),
                                    enabled = !computing && borders && borderPasses + noGoPasses > 0)
                                ToggleRow(tr("No-go zones on the way", "Zones interdites au passage"), nearbyNoGo, { nearbyNoGo = it },
                                    subtitle = if (nearbyNoGo) tr("Their loop is done when the route passes nearby, then mowing resumes.", "Leur tour est fait quand le trajet passe à proximité, puis la tonte reprend.")
                                        else tr("All the no-go zone loops are grouped before the perimeter.", "Tous les tours de zones interdites sont regroupés avant le périmètre."),
                                    enabled = !computing && borders && noGoPasses > 0)
                                ChargeRangeRow(chargeLow, chargeHigh, enabled = !computing) { low, high -> chargeLow = low; chargeHigh = high }
                            }
                        }
                    }
                    if (error.isNotBlank()) StatusNote(error, NoteTone.DANGER)
                }
                else -> PushedPage(tr("Route preview", "Aperçu du trajet"), onBack = back,
                    bottomBar = {
                        PreviewFooterNotes(
                            warnings = listOfNotNull(
                                tr("Robot self-stop not demonstrated (Service → Command cutoff): supervision required.", "Arrêt seul du robot non démontré (Service → Coupure des commandes) : supervision requise.")
                                    .takeIf { state.ready && !demo && !cloud && state.motorWatchdog?.demonstrated != true },
                                state.missionCheckpoint
                                    ?.let { tr("Starting replaces the saved \"${it.task.title}\" (${(it.progress * 100).toInt()} % ${it.task.doneLabel}).", "Démarrer remplace la sauvegarde « ${it.task.title} » (${(it.progress * 100).toInt()} % ${it.task.doneLabel}).") }),
                            status = when {
                                computing -> tr("Computing the route · ${progress.third}", "Calcul du trajet · ${progress.third}")
                                simBuilding -> simulationPreparationStage.takeIf { it.startsWith(tr("Checking", "Vérification")) } ?: tr("Checking the links · $simulationPreparationStage", "Vérification des raccords · $simulationPreparationStage")
                                !state.ready -> tr("Start possible as soon as the robot is connected (gateway or direct Bluetooth).", "Démarrage possible dès que le robot est connecté (passerelle ou Bluetooth direct).")
                                cloud || demo -> tr("To mow, read the robot's Bluetooth map.", "Pour tondre, lisez la carte Bluetooth du robot.")
                                else -> null
                            },
                            busy = computing || simBuilding)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            SecondaryPillButton(tr("Simulate", "Simuler"), ::startMowingSimulation, Modifier.weight(1f), enabled = simulateEnabled, icon = Icons.Rounded.Animation)
                            PrimaryPillButton(if (preview?.mode?.isSurvey == true) tr("Start the survey", "Lancer le relevé") else tr("Start", "Démarrer"), {
                                try {
                                    val engine = requireNotNull(preparedSimulation).withConfig(simulationConfig(requireNotNull(mapBase)))
                                    simulation.pause()
                                    if (onStartRobotRoute(engine, !engine.preview.mode.isSurvey, engine.preview.cutHeights.firstOrNull() ?: mowingHeight.toInt())) {
                                        error = ""
                                        mowingDialog = false
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: tr("Could not start mowing", "Démarrage de tonte impossible")
                                }
                            }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow,
                                enabled = preview?.localOnly != true && preparedSimulation != null && mapBase != null && state.ready && !demo && !cloud && !computing && !simBuilding && !state.campaign.running &&
                                    !state.navTest.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading && !state.robotRoute.running)
                        }
                    }) {
                    // Link preparation: state shown only once, in the bottom bar.
                    MowingPreview(planningMap, preview, localObstacles, estimate, mowingSpeed,
                        computing = computing, validating = false, stage = if(simBuilding) simulationPreparationStage else progress.third,
                        zoneIds = preview?.zoneIds?.takeIf { it.isNotEmpty() }?.toSet() ?: selected,
                        autonomyMinutes = autonomy, chargeMinutes = chargeTime, initialBattery = initialBattery,
                        chargeLow = chargeLow, chargeHigh = chargeHigh, requestedStart = previewStart?.point,
                        onPickStart = if (pickingStart && !computing) { p ->
                            if (ZoneStart.zoneAt(planningMap, selected, p) == null) error = tr("Start: tap a point in a chosen zone, outside no-go zones", "Départ : touchez un point d'une zone choisie, hors zone interdite")
                            else { startOverride = StartOverride.Chosen(p); calculateMowingPreview() }
                        } else null)
                    StartPointGroup(planningMap, previewStart, selected.firstOrNull(), zoneStarts, pickingStart,
                        enabled = !computing && !simBuilding,
                        onPick = { pickingStart = !pickingStart; if (error.startsWith(tr("Start", "Départ"))) error = "" },
                        onNearest = { startOverride = StartOverride.Nearest; calculateMowingPreview() },
                        onSave = { previewStart?.let { r -> if (r.zoneId != null && r.point != null) zoneStarts = zoneStartStore.save(r.zoneId, r.point) } },
                        onForget = {
                            selected.firstOrNull()?.let { id -> zoneStarts = zoneStartStore.clear(id) }
                            if (previewStart?.source == StartSource.SAVED) calculateMowingPreview()
                        })
                    SettingsGroup(tr("Route", "Trajet")) {
                        KeyValueRow("Zones", zoneLine)
                        KeyValueRow(tr("Settings", "Réglages"), if (mode.isSurvey) "${mode.label}${if (mode == CoverageMode.SURVEY_GRID) tr(" · grid ${signalGridSpacing.toInt()} m", " · maille ${signalGridSpacing.toInt()} m") else ""} · ${tr("blades stopped", "lames arrêtées")} · ${Format.num(mowingSpeed.toDouble(), 2)} m/s"
                            else "${when (mode) { CoverageMode.CHECKERBOARD -> tr("Checkerboard", "Damier"); CoverageMode.OPTIMIZED -> tr("Optimized", "Optimisé"); else -> "Zigzag" }} · ${preview?.cutHeights?.takeIf { it.size > 1 }?.let { "${it.size} passes " + CutPasses.label(it) } ?: "${mowingHeight.toInt()} mm"} · ${(spacing * 100).roundToInt()} cm · ${Format.num(mowingSpeed.toDouble(), 2)} m/s")
                        if (estimateError.isNotBlank()) InfoNote(estimateError)
                    }
                    if (!mode.isSurvey) fr.luba.poc.weather.MowingRainWarning(estimate?.totalSeconds)
                    planningReport?.let { report ->
                        SettingsGroup(tr("Check", "Vérification")) {
                            report.selected.metrics?.let { metric ->
                                KeyValueRow(tr("Coverage", "Couverture"), "${Format.num(metric.coverage*100,1)} %")
                                KeyValueRow(tr("Unmowed area", "Surface non tondue"), "${Format.num(metric.uncoveredAreaM2,1)} m²")
                                KeyValueRow(tr("Total distance", "Distance totale"), tr("${Format.num(metric.distanceM,0)} m including ${Format.num(metric.transitM,0)} m without cutting", "${Format.num(metric.distanceM,0)} m dont ${Format.num(metric.transitM,0)} m hors coupe"))
                            }
                            if (report.selected.valid) StatusNote(tr("Route checked: same route for the simulation and real mowing.", "Trajet vérifié : même trajet pour la simulation et la tonte réelle."), NoteTone.SUCCESS)
                            else StatusNote(tr("Route not validated: ${report.selected.diagnostics.joinToString(" ; ")}", "Trajet non validé : ${report.selected.diagnostics.joinToString(" ; ")}"), NoteTone.DANGER)
                        }
                    }
                    if (state.status.startsWith(tr("Mowing refused", "Tonte refusée")) || state.status.startsWith(tr("Preparation:", "Préparation :"))) StatusNote(state.status, NoteTone.DANGER)
                    if (error.isNotBlank()) StatusNote(error, NoteTone.DANGER)
                }
            }
        }

    }
    // Mowing discard and station: separate method (register limit).
    Isolated {
        if (discardConfirm) LubaDialog(onDismissRequest = { discardConfirm = false }, title = tr("Discard the interrupted task?", "Abandonner la tâche interrompue ?"), content = {
            Text(tr("The progress is cleared: the next task will start from the beginning.", "L'avancement est effacé : la prochaine tâche repartira du début."), style = MaterialTheme.typography.bodyMedium)
        }, footer = {
            DangerPillButton(tr("Discard", "Abandonner"), { discardConfirm = false; resumeError = ""; onDiscardMissionCheckpoint() }, Modifier.fillMaxWidth())
        })

        if (baseDialog && cloudStation) CloudStationDialog(cloudReturn!!, cloudRobotName, onCloudReturn, onCancelCloudReturn) { baseDialog = false }
        if (baseDialog && !cloudStation) StationDialog(state, baseBusy, baseSupervised, { baseSupervised = it },
            error = error.takeIf { it.startsWith("Base") },
            leaveEnabled = baseSupervised && pointIdle && !state.navTest.running && !baseBusy && mapBase != null,
            rechargeEnabled = baseSupervised && pointIdle && !state.navTest.running && !baseBusy && mapBase != null,
            onLeave = { baseAction(true) }, onRecharge = { baseAction(false) },
            onStopMammotionTask = onStopMammotionTask, onDismiss = { baseDialog = false })
    }
}

/** Recharges of the task: current or done over the total planned at start ("1 / ≈ 2"), or just the number done. */
private fun rechargeLine(state: RobotUi): String {
    val record = state.mowingRecord
    val done = record?.recharges ?: state.robotRoute.rechargeCycles
    return record?.let { "$done / ≈ ${maxOf(done, it.forecast.recharges)}" } ?: "$done"
}

/** Line "1. Aire de jeux → 2. Piscine": actual mowing order. */
private fun selectedZoneLine(map: RobotMap, order: List<Long>): String =
    order.mapIndexedNotNull { index, id -> map.shapes.firstOrNull { it.id == id }?.let { "${index + 1}. ${MapCorrections.zoneTitle(it, "Zone ${it.id}")}" } }
        .joinToString(" → ").ifBlank { tr("none", "aucune") }

/** Calibration taken from the robot's real mowing history (none in demo). */
private fun estimateCalibration(state: RobotUi, demo: Boolean): fr.luba.poc.history.MowingCalibration? =
    if (demo) null else fr.luba.poc.history.MowingCalibration.from(state.mowingHistory, state.selected?.address)

/** Real mowing estimate: battery of the connected robot (setting otherwise), parameters adjusted by the history. */
private fun realEstimate(state: RobotUi, demo: Boolean, meters: Double, speed: Float, autonomy: Float, charge: Float,
                         battery: Float, low: Float, high: Float): MowingEstimate =
    fr.luba.poc.history.MowingForecaster.estimate(meters, speed.toDouble(), autonomy.toDouble(), charge.toDouble(),
        state.telemetry.battery?.takeIf { state.ready && !demo }?.toDouble() ?: battery.toDouble(), low.toDouble(), high.toDouble(),
        estimateCalibration(state, demo))

private fun durationText(seconds: Double): String {
    val minutes = ceil(seconds / 60).toInt()
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}

/** Chosen zones in mowing order: numbered badge, tap to remove. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ZoneOrderChips(map: RobotMap, order: List<Long>, onRemove: (Long) -> Unit) {
    val colors = MammotionColors
    if (order.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        order.forEachIndexed { index, id ->
            val name = map.shapes.firstOrNull { it.id == id }?.let { MapCorrections.zoneTitle(it, "Zone ${index + 1}") } ?: "Zone ${index + 1}"
            Surface(onClick = { onRemove(id) }, shape = CircleShape, color = colors.GreenSoft) {
                Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(24.dp).background(colors.Ink, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = colors.OnInk)
                    }
                    Text(name, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.Close, contentDescription = tr("Remove $name", "Retirer $name"), modifier = Modifier.size(16.dp), tint = colors.TextSecondary)
                }
            }
        }
    }
}

private fun mapMinX(map: RobotMap): Double = map.shapes.flatMap { it.points }.minOfOrNull { it.x } ?: 0.0
private fun mapMinY(map: RobotMap): Double = map.shapes.flatMap { it.points }.minOfOrNull { it.y } ?: 0.0

private fun mapExtentX(map: RobotMap): Double {
    val p = map.shapes.flatMap { it.points }
    return max(1.0, (p.maxOfOrNull { it.x } ?: 20.0) - (p.minOfOrNull { it.x } ?: 0.0))
}

private fun mapExtentY(map: RobotMap): Double {
    val p = map.shapes.flatMap { it.points }
    return max(1.0, (p.maxOfOrNull { it.y } ?: 15.0) - (p.minOfOrNull { it.y } ?: 0.0))
}

/**
 * Map content compiled in its own class (non-inline composable lambda).
 * MapScreen exceeded 256 Dalvik registers: D8 reused the Composer register there as an integer and ART rejected the
 * whole class (VerifyError when opening the map). Inline blocks (Box, Row, Column) copied all their content into the
 * method; this boundary keeps MapScreen far from the limit. Build check: tools/check-dex-registers.py.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.IsolatedBox(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) = content()

/**
 * Move to a point: path, section beyond the mapped zones (warning and its own acknowledgement), supervision, speed,
 * start and STOP. Separate function: keeps MapScreen away from the Dalvik register limit.
 */
@Composable
private fun PointMovePanel(
    state: RobotUi, destination: Point?, plan: fr.luba.poc.diagnostics.MapNavPlan?, computing: Boolean, error: String,
    attempted: Boolean, confirmed: Boolean, onConfirmed: (Boolean) -> Unit, idle: Boolean, onTrajectorySpeed: (Int) -> Unit,
    onStop: () -> Unit, onStart: () -> Unit, onClose: () -> Unit,
) {
    // Section beyond the mapped zones: acknowledged separately for each destination and each path.
    var offMapConfirmed by remember(destination, plan) { mutableStateOf(false) }
    val offMeters = plan?.offMapMeters ?: 0.0
    val offMap = offMeters > 0.0
    MapPanel(tr("Move to a point", "Déplacer à un point"),
        subtitle = if (state.navTest.running || attempted) state.navTest.status else if (computing) tr("Computing the path…", "Calcul du chemin…")
            else if (error.isNotBlank()) error
            else plan?.let {
                if (offMap) tr("Path: ${Format.num(it.meters,1)} m including ${Format.num(offMeters,1)} m beyond the map · blades stopped",
                    "Chemin : ${Format.num(it.meters,1)} m dont ${Format.num(offMeters,1)} m hors carte · lames arrêtées")
                else tr("Safe path: ${Format.num(it.meters,1)} m · blades stopped", "Chemin sûr : ${Format.num(it.meters,1)} m · lames arrêtées")
            } ?: tr("Tap the map to choose the destination, inside or beyond the zones.", "Touchez la carte pour choisir la destination, dans les zones ou au-delà."),
        onClose = onClose) {
        plan?.departure?.takeIf { !state.navTest.running }?.let { exit ->
            StatusNote(tr("Robot on its base: it first leaves in a straight line, slowly, to the link point (${Format.num(exit.meters,1)} m), then follows the path.",
                "Robot sur sa base : il sort d’abord en ligne droite, lentement, jusqu’au point de liaison (${Format.num(exit.meters,1)} m), puis suit le chemin."), NoteTone.INFO)
        }
        if (offMap && !state.navTest.running) StatusNote(tr("Beyond the mapped zones (orange dots): ${Format.num(offMeters,1)} m driven in a straight line without border check. Only the no-go zones and the saved obstacles are avoided: a wall, a pool or a slope are not known there.",
            "Au-delà des zones cartographiées (pointillés orange) : ${Format.num(offMeters,1)} m parcourus en ligne droite sans contrôle des bords. Seules les zones interdites et les obstacles enregistrés sont évités : un mur, une piscine ou une pente n’y sont pas connus."), NoteTone.WARNING)
        if (offMap && state.navTest.running) StatusNote(tr("Section beyond the map: stay next to the robot, STOP within reach.",
            "Tronçon hors carte : rester près du robot, STOP à portée de main."), NoteTone.WARNING)
        if (!state.navTest.running) LubaCheckRow(tr("I am supervising the move, area clear and stop within reach", "Je supervise le déplacement, zone dégagée et arrêt accessible"),
            confirmed, onConfirmed)
        if (offMap && !state.navTest.running) LubaCheckRow(tr("I have checked that the dotted section is clear and safe", "J’ai vérifié que le tronçon en pointillé est dégagé et sans danger"),
            offMapConfirmed, { offMapConfirmed = it })
        SliderRow(tr("Travel speed", "Vitesse de déplacement"), state.trajectorySpeedPercent.toFloat(), { onTrajectorySpeed(it.toInt()) },
            valueRange=10f..100f,valueLabel="${state.trajectorySpeedPercent} %")
        if (state.navTest.running) DangerPillButton("STOP", onStop, Modifier.fillMaxWidth())
        else PrimaryPillButton(tr("Go to the point", "Aller au point"), onStart, Modifier.fillMaxWidth(),
            enabled = idle && plan != null && !computing && confirmed && (!offMap || offMapConfirmed), icon = Icons.Rounded.NearMe)
    }
}

/**
 * Floating panel laid on the map (zone choice, move, access, resume): shaded white card, title, discreet subtitle and
 * optional close cross.
 */
@Composable
private fun MapPanel(
    title: String? = null,
    subtitle: String? = null,
    onClose: (() -> Unit)? = null,
    closeDescription: String = tr("Close", "Fermer"),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            if (title != null || onClose != null) Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = if (onClose != null) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                }
                if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.padding(start = Dimens.Xs).size(40.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = closeDescription, tint = colors.TextSecondary)
                }
            }
            content()
        }
    }
}

/** Status message laid on the map, with "OK" to clear it when it can be dismissed. */
@Composable
private fun MapFloatingNote(text: String, tone: NoteTone, onDismiss: (() -> Unit)? = null) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.medium, color = colors.Surface, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimens.Xs), verticalAlignment = Alignment.CenterVertically) {
            StatusNote(text, tone, Modifier.weight(1f))
            if (onDismiss != null) TextAction("OK", onDismiss)
        }
    }
}

/** BLE map reading possible now (robot linked directly or through the gateway, no mission, test or simulation). */
internal fun mapFetchAllowed(state: RobotUi, simulating: Boolean): Boolean =
    state.ready && !state.mapLoading && !state.campaign.running && !state.bladeTestActive && !state.navTest.running &&
        !state.teach.running && !state.robotRoute.running && !state.mowing.active && !state.manualActive && !simulating

/** Empty map: read the robot map (through the gateway when the pilot uses it) or open the Mammotion account map. */
@Composable
private fun NoMapPanel(state: RobotUi, cloud: Boolean, fetchEnabled: Boolean, onFetch: () -> Unit, onAccount: () -> Unit) {
    val gateway = state.viaGateway && !cloud
    val subtitle = when {
        state.mapLoading -> (if (gateway) tr("Reading the robot map over Bluetooth through the gateway…", "Lecture de la carte du robot en Bluetooth par la passerelle…")
            else tr("Reading the robot map over Bluetooth…", "Lecture de la carte du robot en Bluetooth…")) + " " + state.mammotionMap.source
        state.ready && gateway -> tr("Robot connected through the gateway: the gateway reads its map over Bluetooth.", "Robot connecté par la passerelle : la passerelle lit sa carte en Bluetooth.")
        state.ready -> tr("Robot connected over Bluetooth: read its map.", "Robot connecté en Bluetooth : lisez sa carte.")
        else -> tr("Connect the robot (Bluetooth or gateway) to read its map, or view the Mammotion account map.", "Connectez le robot (Bluetooth ou passerelle) pour lire sa carte, ou consultez la carte du compte Mammotion.")
    }
    MapPanel(tr("No map on this phone", "Aucune carte sur ce téléphone"), subtitle = subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
            SecondaryPillButton(tr("Account map", "Carte du compte"), onAccount, Modifier.weight(1f))
            PrimaryPillButton(if (state.mapLoading) tr("Reading…", "Lecture…") else if (gateway) tr("Read via the gateway", "Lire par la passerelle") else tr("Read the robot map", "Lire la carte du robot"),
                onFetch, Modifier.weight(1.3f), enabled = fetchEnabled && !cloud)
        }
    }
}

/** Interrupted move to a point: destination kept, resume from the robot's real position. */
@Composable
private fun InterruptedPointPanel(resume: PointResume, state: RobotUi, enabled: Boolean, onResume: () -> Unit, onDiscard: () -> Unit) {
    val summary = InterruptionSummary.of(resume.interruption)
    val t = state.telemetry
    val distance = if (t.x != null && t.y != null) Point(t.x, t.y).distance(resume.target) else null
    MapPanel(tr("Move to a point interrupted", "Déplacement au point interrompu"),
        subtitle = "${Format.time(resume.startedAt).substringBeforeLast(':').substringBefore('.')} · ${summary.label}") {
        Text(distance?.let { tr("Destination ${Format.num(it, 1)} m from the robot: route recomputed from its position.", "Destination à ${Format.num(it, 1)} m du robot : trajet recalculé depuis sa position.") }
            ?: tr("Destination kept: route recomputed from the robot's position.", "Destination conservée : trajet recalculé depuis la position du robot."),
            style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
        if (summary.detail != null) InfoNote(summary.detail)
        if (state.motorWatchdog?.demonstrated != true) StatusNote(tr("Stay nearby during the resume.", "Restez à proximité pendant la reprise."), NoteTone.WARNING)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Discard", "Abandonner"), onDiscard, Modifier.weight(1f))
            PrimaryPillButton(tr("Resume", "Reprendre"), onResume, Modifier.weight(1.3f), enabled = enabled)
        }
    }
}

/**
 * Resume of an interrupted mowing: progress, cause, supervision guard, errors and actions. Compact panel (Fold outer
 * screen, 2026-10-16: four big buttons, the progress bar duplicated with the mowing bar and the layer strip hid the
 * whole map): "Resume" as the only button, "Discard" as a link, "Unblock" and "Resume further on" in the "⋯" menu.
 * "Unblock" backs the robot off to a safe position slightly before the blockage; "Resume further on" skips a stripe
 * distance chosen with the slider or by tapping the map, the resume point being drawn live.
 */
@Composable
private fun InterruptedMowingPanel(
    checkpoint: MissionCheckpoint, map: RobotMap, state: RobotUi, busy: Boolean, resumeError: String,
    resumeEnabled: Boolean, skip: ResumeSkipView?, skipOpen: Boolean, skipMeters: Float,
    onSkipMeters: (Float) -> Unit, onOpenSkip: () -> Unit, onCloseSkip: () -> Unit,
    onBackOff: () -> Unit, onStopBackOff: () -> Unit, onDiscard: () -> Unit, onResume: () -> Unit,
) {
    val colors = MammotionColors
    // Cause in one sentence, technical message in "Details" (menu).
    val summary = InterruptionSummary.of(checkpoint.interruption)
    var details by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = Dimens.Lg, end = Dimens.Xs, top = Dimens.Sm, bottom = Dimens.Sm),
            verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${checkpoint.task.title} · ${(checkpoint.progress * 100).toInt()} %", style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Format.time(checkpoint.updatedAt).substringBeforeLast(':').substringBefore('.')} · ${summary.label}",
                        style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (skipOpen) IconButton(onClick = onCloseSkip, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = tr("Close \"Resume further on\"", "Fermer « Reprendre plus loin »"), tint = colors.TextSecondary)
                }
                else Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Other resume actions", "Autres actions de reprise"), tint = colors.TextSecondary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Column {
                            Text(tr("Unblock", "Débloquer"))
                            Text(tr("Back-off with blades stopped to a safe position", "Recul lames arrêtées vers une position sûre"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                        } }, onClick = { menu = false; onBackOff() }, enabled = resumeEnabled && !state.backOffPending)
                        DropdownMenuItem(text = { Column {
                            Text(tr("Resume further on", "Reprendre plus loin"))
                            Text(tr("Skip an obstacle or a difficult passage", "Sauter un obstacle ou un passage difficile"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                        } }, onClick = { menu = false; onOpenSkip() }, enabled = !busy && !state.backOffPending)
                        if (summary.detail != null) DropdownMenuItem(text = { Text(if (details) tr("Hide the details", "Masquer les détails") else tr("Stop details", "Détails de l'arrêt")) },
                            onClick = { menu = false; details = !details })
                    }
                }
            }
            Column(Modifier.padding(end = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
                Text("${CutPasses.progress(checkpoint.preview.cutHeights, checkpoint.heightMm)} · ${selectedZoneLine(map, checkpoint.preview.zoneIds)}" +
                    (if (checkpoint.rechargeCycles > 0) " · ${checkpoint.rechargeCycles} recharge(s)" else "") +
                    (if (checkpoint.resumes > 0) tr(" · ${checkpoint.resumes} resume(s)", " · ${checkpoint.resumes} reprise(s)") else "") +
                    (if (checkpoint.skippedMeters >= 0.05) tr(" · ${Format.num(checkpoint.skippedMeters, 1)} m skipped", " · ${Format.num(checkpoint.skippedMeters, 1)} m sautés") else ""),
                    style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (details && summary.detail != null) InfoNote(summary.detail)
                if (state.autoResumeStatus.isNotBlank()) StatusNote(state.autoResumeStatus, NoteTone.INFO)
                if (resumeError.isNotBlank()) StatusNote(resumeError, NoteTone.DANGER)
                if (state.checkpointSaveError) StatusNote(tr("Failed to write the progress on the phone.", "Échec d'écriture de l'avancement sur le téléphone."), NoteTone.DANGER)
                if (state.backOffStatus.isNotBlank()) StatusNote(state.backOffStatus, when {
                    state.backOffPending -> NoteTone.INFO
                    state.backOffStatus.startsWith(tr("Robot backed off", "Robot reculé")) -> NoteTone.SUCCESS
                    else -> NoteTone.DANGER })
                if (skipOpen) {
                    if (skip == null) InfoNote(if (busy) tr("Preparing the resume route…", "Préparation du trajet de reprise…") else tr("Preview unavailable: see the message above.", "Aperçu indisponible : voir le message ci-dessus."))
                    else if (skip.maxMeters < 0.1) InfoNote(tr("Less than one meter of stripe left: nothing to skip.", "Moins d'un mètre de bande restant : rien à sauter."))
                    else {
                        SliderRow(tr("Resume further on", "Reprendre plus loin"), skipMeters, { onSkipMeters((Math.round(it * 10) / 10f)) }, 0f..skip.maxMeters.toFloat(),
                            valueLabel = "+ ${Format.num(skip.skipMeters, 1)} m")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            TextAction("− 1 m", { onSkipMeters((skipMeters - 1f).coerceAtLeast(0f)) }, enabled = skipMeters > 0f)
                            TextAction("+ 1 m", { onSkipMeters((skipMeters + 1f).coerceAtMost(skip.maxMeters.toFloat())) }, enabled = skipMeters < skip.maxMeters)
                        }
                        Text(if (skip.skipMeters > 0.0) tr("Tap a stripe to place the resume: the ${Format.num(skip.skipMeters, 1)} m in orange will not be mowed.", "Touchez une bande pour placer la reprise : les ${Format.num(skip.skipMeters, 1)} m en orange ne seront pas tondus.")
                            else tr("Set the distance or tap a stripe: the purple dot shows the resume.", "Réglez la distance ou touchez une bande : le point violet montre la reprise."),
                            style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                    }
                }
                if (!state.ready) Text(tr("Connect the robot to resume (30 cm overlap at the interrupted point).", "Connectez le robot pour reprendre (30 cm de recouvrement au point interrompu)."),
                    style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                else if (state.motorWatchdog?.demonstrated != true)
                    Text(tr("Stay nearby during the resume.", "Restez à proximité pendant la reprise."), style = MaterialTheme.typography.labelSmall, color = colors.Warning)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    TextAction(tr("Discard", "Abandonner"), onDiscard, enabled = !busy && !state.backOffPending)
                    Spacer(Modifier.weight(1f))
                    if (state.backOffPending) DangerPillButton("STOP", onStopBackOff)
                    else PrimaryPillButton(if (busy) tr("Preparing…", "Préparation…") else if (skipOpen && skipMeters > 0f) tr("Resume at + ${Format.num(skipMeters.toDouble(), 1)} m", "Reprendre à + ${Format.num(skipMeters.toDouble(), 1)} m") else tr("Resume", "Reprendre"),
                        onResume, icon = Icons.Rounded.PlayArrow, enabled = resumeEnabled && (!skipOpen || skip != null))
                }
            }
        }
    }
}

/** "Device status" sheet opened from the status pill at the top of the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceStatusSheet(
    state: RobotUi, t: fr.luba.poc.protocol.Telemetry, context: String, marker: DisplayedRobot?, known: LastKnownPosition?,
    onOpenRobot: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = MammotionColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.Background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Lg).padding(bottom = Dimens.Lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Device status", "État de l'appareil"), style = MaterialTheme.typography.headlineSmall)
                Text(context, style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
            }
            SettingsGroup(tr("Connection", "Connexion")) {
                KeyValueRow("Bluetooth", state.connection.label)
                KeyValueRow(tr("Bluetooth signal", "Signal Bluetooth"), state.rssi?.let { "$it dBm" } ?: "—")
                KeyValueRow(tr("Last telemetry", "Dernière télémétrie"), state.telemetry.lastReportAt?.let(Format::time) ?: tr("never received", "jamais reçue"))
                NavRow(tr("Manage the connection", "Gérer la connexion"), icon = Icons.Rounded.Bluetooth, onClick = onOpenRobot)
            }
            SettingsGroup(tr("Positioning and battery", "Positionnement et batterie")) {
                val quality = positionQuality(t.rtkStatus)
                KeyValueRow(tr("Positioning", "Positionnement"), quality.label, valueColor = if (quality == PositionQuality.FIX) colors.GreenDeep else colors.Warning)
                KeyValueRow(tr("Last RTK state", "Dernier état RTK"), t.lastRtkAt?.let(Format::time) ?: "—")
                KeyValueRow(tr("Battery", "Batterie"), t.battery?.let { "$it %" } ?: "—")
                marker?.let { robot -> KeyValueRow(tr("Displayed position", "Position affichée"), "${robot.source} · ${if (robot.fresh) tr("recent", "récente") else tr("last known", "dernière connue")}") }
                InfoNote(if (quality == PositionQuality.FIX) tr("Accurate RTK position: automatic mowing is possible.", "Position RTK précise : la tonte automatique est possible.")
                    else tr("Only a Fix positioning allows accurate automatic mowing.", "Seul un positionnement Fix permet une tonte automatique précise."))
            }
            known?.let { k ->
                fun time(at: Long?) = at?.let { java.text.SimpleDateFormat(tr("MM-dd HH:mm:ss", "dd/MM HH:mm:ss"), fr.luba.poc.i18n.I18n.locale).format(java.util.Date(it)) } ?: "—"
                SettingsGroup(tr("Last known position", "Dernière position connue")) {
                    KeyValueRow("Robot", k.robotName)
                    KeyValueRow("Communication", time(k.communicationAt))
                    KeyValueRow(tr("Fix position", "Position Fix"), k.last?.let { p -> time(p.at) } ?: tr("none saved", "aucune enregistrée"))
                    if (state.positionSaveError) StatusNote(tr("Local save failed: position kept in memory", "Échec de sauvegarde locale : position conservée en mémoire"), NoteTone.DANGER)
                }
            }
            SettingsGroup(tr("Technical details", "Détails techniques")) {
                KeyValueRow(tr("Robot state (code)", "État robot (code)"), t.robotState?.toString() ?: "—")
                KeyValueRow(tr("RTK state / quality", "RTK état / qualité"), "${t.rtkStatus ?: "—"} / ${t.positioningLevel ?: "—"}")
                KeyValueRow(tr("Position type", "Type de position"), t.positionType?.toString() ?: "—")
                KeyValueRow(tr("Local position", "Position locale"), if (t.x != null && t.y != null) "${Format.num(t.x, 2)} / ${Format.num(t.y, 2)} m" else "—")
            }
        }
    }
}

/** Map summary for the Map sheet: zones, no-go zones and passages. */
private fun mapSummary(map: RobotMap): String {
    val zones = map.shapes.count { it.kind == ShapeKind.ZONE }
    val noGo = map.shapes.count { it.kind == ShapeKind.NO_GO }
    return listOfNotNull(Format.plural(zones, "zone", "zones"),
        noGo.takeIf { it > 0 }?.let { Format.plural(it, tr("no-go zone", "zone interdite"), tr("no-go zones", "zones interdites")) }).joinToString(" · ")
}

/** Chosen zones, in mowing order; a tap goes back to the choice on the map. */
@Composable
private fun ZoneOrderGroup(count: Int, zoneLine: String, onEdit: () -> Unit, editEnabled: Boolean) {
    SettingsGroup("Zones") {
        NavRow(Format.plural(count, "zone", "zones") + tr(" · mowing order", " · ordre de tonte"), subtitle = zoneLine, icon = Icons.Rounded.Grass,
            enabled = editEnabled, onClick = onEdit)
    }
}

/** Working hours ([fr.luba.poc.mowing.WorkWindowSetting]): setting shared by all tasks. */
@Composable
private fun WorkWindowGroup(enabled: Boolean) {
    val colors = MammotionColors
    val window by fr.luba.poc.mowing.WorkWindowSetting.flow.collectAsStateWithLifecycle()
    SettingsGroup(tr("Working hours", "Plage horaire de travail")) {
        ToggleRow(tr("Limit mowing hours", "Limiter les heures de tonte"), window.enabled, { fr.luba.poc.mowing.WorkWindowSetting.set(window.copy(enabled = it)) },
            subtitle = if (window.enabled) tr("Mowing allowed ${window.label}", "Tonte permise de ${window.label}") else tr("Mowing allowed at any time", "Tonte permise à toute heure"), enabled = enabled)
        if (window.enabled) {
            Row(Modifier.fillMaxWidth()) {
                Text(tr("Working hours", "Heures de travail"), style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary, modifier = Modifier.weight(1f))
                Text(window.label, style = MaterialTheme.typography.titleSmall, color = colors.Value)
            }
            val step = fr.luba.poc.mowing.WorkWindow.STEP
            LubaRangeSlider(value = window.startMinutes.toFloat()..window.endMinutes.toFloat(), onValueChange = { range ->
                val start = (range.start / step).roundToInt() * step
                val end = (range.endInclusive / step).roundToInt() * step
                if (end - start >= fr.luba.poc.mowing.WorkWindow.MIN_SPAN)
                    fr.luba.poc.mowing.WorkWindowSetting.set(window.copy(startMinutes = start, endMinutes = end))
            }, valueRange = 0f..fr.luba.poc.mowing.WorkWindow.DAY.toFloat(), steps = fr.luba.poc.mowing.WorkWindow.DAY / step - 1, enabled = enabled)
            InfoNote(tr("Outside: no mowing starts; a mowing in progress stops and the robot returns to base, blades stopped " +
                "(resume possible afterwards from the interrupted point); a robot left outside is brought back to base.",
                "En dehors : aucune tonte ne démarre ; une tonte en cours s'arrête et le robot rentre à la base, lames arrêtées " +
                "(reprise possible ensuite depuis le point interrompu) ; un robot resté dehors est ramené à la base."))
        }
        InfoNote(tr("Working hours set in the Mammotion app add to these: outside that range, the robot flatly refuses " +
            "to work (self-check 23), without return to base or resume. Better disable it and keep this one.",
            "Une plage horaire réglée dans l'app Mammotion s'ajoute à celle-ci : hors de cette plage, le robot refuse " +
            "net de travailler (autodiagnostic 23), sans retour à la base ni reprise. Mieux vaut la désactiver et garder celle-ci."))
    }
}

/** Recharge thresholds: return to base then resume, minimum gap of 5 %. */
@Composable
private fun ChargeRangeRow(low: Float, high: Float, enabled: Boolean, onChange: (Float, Float) -> Unit) {
    val colors = MammotionColors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
        Row(Modifier.fillMaxWidth()) {
            Text(tr("Battery: return / resume", "Batterie : retour / reprise"), style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary, modifier = Modifier.weight(1f))
            Text("${low.toInt()} % – ${high.toInt()} %", style = MaterialTheme.typography.titleSmall, color = colors.Value)
        }
        LubaRangeSlider(value = low..high, onValueChange = { range ->
            val l = range.start.roundToInt().coerceAtMost(80).toFloat()
            val h = range.endInclusive.roundToInt().toFloat()
            if (h - l >= 5f) onChange(l, h)
        }, valueRange = 10f..100f, steps = 89, enabled = enabled)
        InfoNote(tr("Mowing stops at ${low.toInt()} % to recharge, then resumes at the interrupted point at ${high.toInt()} %.", "La tonte s'arrête à ${low.toInt()} % pour recharger, puis reprend au point interrompu à ${high.toInt()} %."))
    }
}

/** Preview footer: grouped warnings, then a discreet status line above the actions. */
@Composable
private fun PreviewFooterNotes(warnings: List<String>, status: String?, busy: Boolean) {
    if (warnings.isNotEmpty()) StatusNote(warnings.joinToString("\n"), NoteTone.WARNING)
    if (status != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = MammotionColors.Green, strokeWidth = 2.dp)
        Text(status, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Charging station" dialog: supervision, states and actions. */
@Composable
private fun StationDialog(
    state: RobotUi, busy: Boolean,
    supervised: Boolean, onSupervised: (Boolean) -> Unit, error: String?,
    leaveEnabled: Boolean, rechargeEnabled: Boolean, onLeave: () -> Unit, onRecharge: () -> Unit,
    onStopMammotionTask: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = MammotionColors
    val nativeReturning = state.nativeReturnActive || state.telemetry.robotState == 14
    LubaDialog(onDismissRequest = onDismiss, title = tr("Charging station", "Station de charge"), content = {
        InfoNote(tr("Recharge: DumbyLuba drives the robot in front of the base along the verified local route (50 cm from the edges, blades " +
            "stopped), then the official Mammotion return handles the docking.",
            "Recharger : DumbyLuba conduit le robot devant la base par le trajet local vérifié (50 cm des bords, lames " +
            "arrêtées), puis le retour officiel Mammotion assure l'accostage."))
        SafetyNotice(tr("Stay nearby, stop within reach.", "Restez à proximité, arrêt accessible."))
        LubaCheckRow(tr("I am supervising the robot and the way to the station is clear", "Je supervise le robot et l'accès à la station est dégagé"), supervised, onSupervised)
        val lines = listOfNotNull(tr("Preparing the station route…", "Préparation du trajet de station…").takeIf { busy },
            state.robotRoute.status.takeIf { state.robotRoute.started },
            state.nativeReturnStatus.takeIf { it.isNotBlank() },
            state.taskStopStatus.takeIf { it.isNotBlank() })
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary) }
        if (error != null) StatusNote(error, NoteTone.DANGER)
    }, footer = {
        if (nativeReturning) DangerPillButton(tr("Stop the Mammotion return", "Arrêter le retour Mammotion"), onStopMammotionTask, Modifier.fillMaxWidth(),
            enabled = state.ready && !state.taskStopPending)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Leave the station", "Quitter la station"), onLeave, Modifier.weight(1f), enabled = leaveEnabled && !nativeReturning)
            PrimaryPillButton(tr("Recharge", "Recharger"), onRecharge, Modifier.weight(1f), icon = Icons.Rounded.EvStation,
                enabled = rechargeEnabled && !nativeReturning)
        }
    })
}

/** Content composed in its own method (non-inline lambda), like [IsolatedBox], outside the MapScreen registers. */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()
