package fr.luba.poc.map

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.Clock
import fr.luba.poc.auth.CloudReturnUi
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.camera.LiveCameraButton
import fr.luba.poc.camera.LiveCameraWindow
import fr.luba.poc.history.MowingCalibration
import fr.luba.poc.history.MowingForecaster
import fr.luba.poc.history.realCoverageLayer
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.planning.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    apiPosition: Telemetry? = null,
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
    cloudReturn: CloudReturnUi? = null,
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
    /** Dedicated "record by driving" page (zone, passage or no-go driven with the joysticks). */
    onOpenDrivenRecord: () -> Unit = onOpenManual,
    /** Detail of a history task (replay of the real route) and full history (Service). */
    onOpenTask: (String) -> Unit = {},
    onOpenAllTasks: () -> Unit = {},
    /** Saves the full list of local map corrections; false if refused (route in progress…). */
    onSaveCorrections: (List<MapCorrection>) -> Boolean = { false },
    /** Opened from the first-launch assistant ("Explore without a robot"): the demo map is shown at once. */
    startDemo: Boolean = false,
) {
    // One parameter object for the body: see [MapScreenArgs] (Dalvik register limit).
    MapScreenContent(state, simulation, MapScreenArgs(
        onFetchMap, onOpenRobot, onOpenAccount, openMowing, onMowingOpened, openSurvey, onSurveyOpened, cloud,
        apiPosition, apiKnown, onPreview, onSimulationPreparation, onStartRobotRoute, onBaseAction, onResumeRobotRoute,
        onBackOffBeforeResume, onStopBackOff, onDiscardMissionCheckpoint, onStopMammotionTask, cloudReturn,
        onCloudReturn, onCancelCloudReturn, cloudRobotName, onTrajectorySpeed, onStartPointNavigation,
        onStopPointNavigation, onDiscardPointResume, onUnlockRobot, onStop, onBack, onOpenManual, onOpenDrivenRecord,
        onOpenTask, onOpenAllTasks, onSaveCorrections, startDemo))
}

/** Map screen body: a single parameter object ([MapScreenArgs]) keeps its Dalvik registers low (see [MapScreenArgs]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreenContent(state: RobotUi, simulation: SimulationSession, args: MapScreenArgs) {
    val colors = MammotionColors
    // Demo map and map view size; view and zone selection of this map; "Resume further on" ([MapScreenState.kt]).
    val mapView = remember { MapViewState(args.startDemo) }
    val sim by simulation.ui.collectAsStateWithLifecycle()
    // Wheels of the simulated robot animated by its movement.
    val simOdometer = remember { WheelOdometer() }
    val simWheels = simOdometer.advance(sim.state?.let { DisplayPose(it.position, it.heading) })
    val showingRobotRoute = state.robotRoute.started && !sim.playing
    val map = if (state.robotRoute.running || (state.ready && !mapView.demo)) state.robotMap else sim.engine?.map ?: if (mapView.demo) Geometry.demo() else state.robotMap
    LaunchedEffect(state.ready,state.robotMap,mapView.demo) {
        if (state.ready && !mapView.demo && sim.engine?.map?.let { it!=state.robotMap }==true) simulation.clear()
    }
    val context = LocalContext.current
    val baseStore = remember(map) { BaseStore(context, map) }
    var mapBase by remember(map, mapView.demo) { mutableStateOf(run {
        val saved = baseStore.read()
        if (mapView.demo) saved ?: MapBase(Point(1.0, 1.0), "Démonstration")
        else baseStore.resolve(map, saved)
    }) }
    var accesses by remember(map) { mutableStateOf(baseStore.accesses()) }
    val mowingTaskStore = remember { MowingTaskStore(context) }
    // Last mowing task settings, kept between two tasks and two launches ([MowingTaskStore]); sheets and dialogs.
    val form = rememberMowingTaskForm(remember { mowingTaskStore.read() })
    val sheets = rememberMapSheets()
    val pointMove = remember { PointMoveState() }
    val pointTarget = remember(map) { PointTarget() }
    val prep = remember { RoutePreparationState() }
    val actions = remember { MapActionState() }
    val zoneSelectionAllowed = !actions.placingAccess
    // Layer choice (layer, heatmap hidden, bar folded, shading) kept until the next change ([MapLayerSettings]).
    val layers = remember { MapLayersState(MapLayerSettings.read(context), ReliefSettings.mount(context), ReliefSettings.calibration(context),
        sim.engine != null && !state.robotRoute.started) }
    val telemetry = state.telemetry
    val known = state.lastKnown
    var positionClock by remember { mutableLongStateOf(Clock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            positionClock = Clock.now()
        }
    }
    // Last BLE position seen: a gateway reconnection does not send the robot back to an old FIX (the base).
    var lastBle by remember(state.selected?.address) { mutableStateOf<DisplayedRobot?>(null) }
    val marker = RobotPositionDisplay.resolve(if(args.cloud) Telemetry.EMPTY else telemetry,known,args.apiPosition,args.apiKnown,positionClock,
        recentBle = lastBle.takeIf { !args.cloud })
    if (!args.cloud && marker?.source == "BLE" && marker != lastBle) SideEffect { lastBle = marker }
    val recoveryPoints = if (!mapView.demo && sim.engine == null) known?.points.orEmpty() else emptyList()
    val pointIdle = state.ready && !mapView.demo && !args.cloud && !state.robotRoute.running && !state.mowing.active &&
        !state.manualActive && !state.campaign.running && !state.bladeTestActive && !state.bladeHeightPending &&
        !state.teach.running && !state.mapLoading && sim.engine == null
    DisposableEffect(pointMove.pointMode) {
        onDispose { if (pointMove.pointMode) args.onStopPointNavigation() }
    }
    PointPlanEffect(pointTarget, pointMove, state, map, mapBase, accesses)
    val frame = remember(map.originalShapes) { MapFrameState() }
    val paint = rememberZonePaint(map, frame, marker?.point ?: mapBase?.point)
    var preview by remember(map.shapes, frame.selected) { mutableStateOf<Preview?>(null) }
    // Start point: preview choice, otherwise the saved start of the first zone, otherwise nearest.
    val zoneStarts = remember { ZoneStartsState(ZoneStartStore(context)) }
    val start = remember(map.shapes, frame.selected) { PreviewStartState() }
    val minimumCoverage=if(mapView.demo) 0.65f else if(paint.painting) PaintedZone.MIN_COVERAGE.toFloat() else 0.95f
    // Cutting heights of the connected robot (higher on the H versions). The saved height is kept as chosen and
    // brought into the range where it is used: the model code may arrive a few seconds after the connection.
    val cutHeights = state.cutHeights
    val passHeights = CutPasses.heights(form.mowingHeight.roundToInt(), form.firstPassHeight.roundToInt(), form.heightPasses, cutHeights)
    val cuttingHeight = passHeights.last()
    val signalHistoryStore = remember { SignalSurveyStore(context.filesDir) }
    LaunchedEffect(simulation) { simulation.storeSurveys(signalHistoryStore) }
    // Signal survey shown on the map: simulated or measured readings, terrain and colored cells ([MapSignals]).
    val signals = rememberMapSignals(map, state, sim, layers, mapView.demo)
    SaveMapLayers(context, layers.choice())
    LaunchedEffect(form.mode) { if (!form.mode.isSurvey) form.mowingMode = form.mode }
    // Saved in its own composable: a 12-key effect here counted against the MapScreen Dalvik register limit.
    SaveMowingTask(mowingTaskStore, form.settings())
    // Robot following: view centered on it, zoom and rotation kept; a one-finger drag stops it.
    val follow = rememberRobotFollow()
    // Without Bluetooth, account signed in: "Charging station" offers the native return through the account.
    val cloudStation = !state.ready && !mapView.demo && args.cloudReturn != null
    fun selectTask(survey: Boolean) {
        sheets.surveyTask = survey
        if (survey && !form.mode.isSurvey) form.mode = CoverageMode.TOUR
        if (!survey && form.mode.isSurvey) form.mode = form.mowingMode
        sheets.selectingZones = true
    }
    LaunchedEffect(args.openMowing) {
        if (args.openMowing) {
            selectTask(false)
            args.onMowingOpened()
        }
    }
    LaunchedEffect(args.openSurvey) {
        if (args.openSurvey) {
            if (!state.robotRoute.running && !state.mowing.active && sim.engine == null) selectTask(true)
            args.onSurveyOpened()
        }
    }
    val scope = rememberCoroutineScope()

    // Local obstacles learned by the robot, plus presumed obstacles (repeated contacts) if the option is on.
    val localObstacles = state.planningObstacles
    // Difficult zones (heatmap) avoided by travel with blades stopped.
    val transitAvoid = state.difficulties.transitAvoidance
    val planningMap = paint.planningMap(map)
    val currentPlanningMap by rememberUpdatedState(planningMap)
    val currentPlanningSelection by rememberUpdatedState(frame.selected.toList())
    val currentPlanningObstacles by rememberUpdatedState(localObstacles)
    val outerOffset by OuterOffsetSetting.flow.collectAsStateWithLifecycle()
    val surveyOffset by SurveyOffsetSetting.flow.collectAsStateWithLifecycle()
    val planningInputs=listOf(planningMap,localObstacles,frame.selected.toList(),form.mode,
        form.spacing,form.margin,form.cutWidth,minimumCoverage,form.mowingSpeed,form.borders,form.borderPasses,form.noGoPasses,
        form.bordersFirst,form.nearbyNoGo,form.turnStyle,form.signalGridSpacing,mapBase,accesses,outerOffset,surveyOffset,
        // Height passes: route prepared with all the passes, recomputed when they change.
        passHeights.takeIf { !form.mode.isSurvey },form.orientation,form.manualAngle,
        // Spiral direction: only the Spiral mode recomputes when it changes.
        form.spiralDirection.takeIf { form.mode == CoverageMode.SPIRAL }, form.zamboniBlocks.takeIf { form.mode == CoverageMode.ZAMBONI },
        // Change every session: a mowing that ends updates the directions to move away from.
        state.zoneOrientations.takeIf { form.orientation == OrientationMode.EACH_SESSION && form.mode.usesOrientation })
    val currentPlanningInputs by rememberUpdatedState(planningInputs)
    LaunchedEffect(planningInputs) { preview=null; prep.planningReport=null; prep.estimate=null }

    fun simulationConfig(base: MapBase) = SimConfig(form.initialProgress.toDouble(), form.initialBattery.toDouble(), form.autonomy.toDouble(),
        speedMps=form.mowingSpeed.toDouble(),chargeMinutes=form.chargeTime.toDouble(),instantCharging=true,
        returnPercent=form.chargeLow.toDouble(),resumePercent=form.chargeHigh.toDouble(),returnReservePercent=form.returnReserve.toDouble(),
        baseEntryHeadingDegrees=base.entryHeadingDegrees,baseApproachMeters=base.approachDistanceM)

    // A preparation belongs exactly to this preview and its accesses/obstacles.
    var preparedSimulation by remember(preview,planningMap,mapBase,accesses,localObstacles,transitAvoid,mapView.demo) { mutableStateOf<MowingSimulation?>(null) }
    val simulateEnabled = preparedSimulation != null && mapBase != null &&
        (mapView.demo || mapBase?.entryHeadingDegrees != null) && !prep.computing && !prep.simBuilding && !state.robotRoute.running
    // Measure after applying the composition that actually enables the button.
    SideEffect {
        if (sheets.mowingDialog && form.mowingStep == 2 && simulateEnabled && prep.preparationStartedNanos != 0L) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - prep.preparationStartedNanos) / 1_000_000.0
            Log.i("PathPreparationTiming", JSONObject(prep.preparationTimingInputs)
                .put("event", "SIMULATE_ENABLED").put("elapsedMs", elapsedMs)
                .put("planHash", prep.planningReport?.selected?.planHash)
                .put("snapshotHash", prep.planningReport?.snapshotHash).toString())
            prep.preparationStartedNanos = 0L
        }
    }
    fun buildRoutes(base: MapBase): TrajectoryPlanner = RoutePlanning.mission(map, localObstacles, accesses, base, recordedBoundary = !mapView.demo, avoidAreas = transitAvoid)
    fun buildSimulation(plan: Preview, base: MapBase, progress:(String)->Unit = {}): MowingSimulation =
        MowingSimulation(buildRoutes(base),base.point,plan,map,simulationConfig(base),PaintedZone.bladeZones(planningMap,plan.zoneIds),onPreparationProgress=progress)
    fun baseAction(leave: Boolean) {
        val base=mapBase ?: return
        actions.baseBusy=true
        scope.launch {
            try {
                val start=Point(requireNotNull(state.telemetry.x),requireNotNull(state.telemetry.y))
                val engine=withContext(Dispatchers.Default) {
                    RoutePlanning.station(buildRoutes(base),map,base,start,simulationConfig(base))
                }
                if(args.onBaseAction(engine,leave)) sheets.mapActions=false
            } catch(cancel: CancellationException) { throw cancel }
            catch(e: Exception) { prep.error=tr("Base: ${e.message}", "Base : ${e.message}") }
            finally { actions.baseBusy=false }
        }
    }
    LaunchedEffect(preview,planningMap,mapBase,accesses,localObstacles,transitAvoid,mapView.demo,prep.computing) {
        val plan=preview; val base=mapBase
        if(prep.computing || plan==null || base==null || preparedSimulation!=null || (plan.localOnly && prep.planningReport?.selected?.valid != true)) return@LaunchedEffect
        prep.simBuilding=true
        val diagnostic = mapOf("selectedZoneIds" to plan.zoneIds,
            "transitZoneIds" to map.shapes.filter { it.kind==ShapeKind.ZONE }.map { it.id },
            "baseX" to base.point.x,"baseY" to base.point.y,"baseHeading" to base.entryHeadingDegrees,
            "passagePointCounts" to map.shapes.filter { it.kind==ShapeKind.PASSAGE }.map { it.points.size })
        try {
            preparedSimulation=withContext(Dispatchers.Default) {
                val context=currentCoroutineContext()
                buildSimulation(plan,base) { stage ->
                    context.ensureActive()
                    scope.launch { prep.simulationPreparationStage=stage }
                }.also { prep.slowed = AdaptiveSpeed.slowedMeters(it.edges, it.speedCaps) }
            }
            val preparationMs = prep.preparationStartedNanos.takeIf { it > 0 }?.let { (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000.0 }
            args.onSimulationPreparation(tr("Route preparation succeeded", "Préparation du trajet réussie"),diagnostic + mapOf("result" to "READY", "elapsedMs" to preparationMs))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            prep.error=e.message ?: tr("Route preparation failed", "Préparation du trajet impossible")
            args.onSimulationPreparation(prep.error,diagnostic + mapOf("result" to "FAILED","exception" to e.stackTraceToString()))
        }
        finally { prep.simBuilding=false }
    }
    LaunchedEffect(preparedSimulation,form.mowingSpeed,form.chargeLow,form.chargeHigh,form.returnReserve,form.initialBattery,form.autonomy,form.chargeTime) {
        val prepared = preparedSimulation ?: return@LaunchedEffect
        val base = mapBase ?: return@LaunchedEffect
        if (!prepared.preview.mode.isSurvey) return@LaunchedEffect
        try {
            prep.estimate = withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                MowingEstimate.fromSimulation(prepared.withConfig(simulationConfig(base).copy(initialProgress=0.0))) { context.ensureActive() }
            }
            prep.estimateError = tr("Simulated estimate: links, pivots, returns to base and recharges included.", "Estimation simulée : raccords, pivots, retours à la base et recharges inclus.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { prep.estimate=null; prep.estimateError=tr("Estimate unavailable: ${e.message}", "Estimation indisponible : ${e.message}") }
    }
    fun startMowingSimulation() {
        val prepared=preparedSimulation; val base=mapBase
        if(prepared==null || base==null || state.robotRoute.running || prep.simBuilding || prep.computing) return
        // The click triggers no geometric computation: only the scenario and its position.
        try {
            val engine=prepared.withConfig(simulationConfig(base))
            simulation.start(engine,engine.start())
            prep.error=""; sheets.mowingDialog=false; sheets.simulationDialog=false
        } catch (e: Exception) {
            prep.error=e.message ?: tr("Could not start the simulation", "Démarrage de simulation impossible")
        }
    }
    /** Start of the prepared route on the robot ("Start" of the route preview). */
    fun startPreparedRoute() {
        try {
            val engine = requireNotNull(preparedSimulation).withConfig(simulationConfig(requireNotNull(mapBase)))
            simulation.pause()
            if (args.onStartRobotRoute(engine, !engine.preview.mode.isSurvey, engine.preview.cutHeights.firstOrNull() ?: cuttingHeight)) {
                prep.error = ""
                sheets.mowingDialog = false
            }
        } catch (e: Exception) {
            prep.error = e.message ?: tr("Could not start mowing", "Démarrage de tonte impossible")
        }
    }
    // Resume of an interrupted mowing: same preview prepared again on the current map, base, accesses and obstacles.
    val currentRouteStatus by rememberUpdatedState(state.robotRoute.status)
    // Resume route prepared again once, shared by "Resume", "Resume further on" (preview on the map) and "Unblock";
    // redone if the checkpoint, the map, the base, the accesses or the obstacles change.
    val resumeKey = state.missionCheckpoint?.let { listOf(it.robotId, it.startedAt, it.planFingerprint) }
    var resumeEngine by remember(resumeKey, mapBase, map, state.obstacles, accesses) { mutableStateOf<MowingSimulation?>(null) }
    val skipState = remember(resumeKey) { ResumeSkipState() }
    suspend fun resumeEngineFor(checkpoint: MissionCheckpoint, base: MapBase): MowingSimulation = resumeEngine ?: run {
        // Presumed obstacles and avoided zones of the start: a contact since then (often the one that interrupted the
        // mowing) is bypassed during tracking, it does not change the resumed plan.
        // Task started from the tests (border, painted zone): same continuous-blade zones as at its start.
        withContext(Dispatchers.Default) {
            RoutePlanning.resume(checkpoint, map, base, accesses, state.obstacles, state.difficulties,
                simulationConfig(base), recordedBoundary = !mapView.demo)
        }.also { resumeEngine = it }
    }
    /** Preparation again then [action]; errors shown on the panel and logged. */
    fun withResumeEngine(action: (MissionCheckpoint, MowingSimulation) -> Unit) {
        val checkpoint = state.missionCheckpoint ?: return
        val base = mapBase
        if (base == null) { actions.resumeError = tr("Unknown base: place the base before resuming", "Base inconnue : placez la base avant de reprendre"); return }
        if (state.robotRoute.running || actions.resumeBusy) return
        actions.resumeBusy = true; actions.resumeError = ""
        scope.launch {
            try { action(checkpoint, resumeEngineFor(checkpoint, base)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                actions.resumeError = tr("Resume failed: ${e.message ?: "route preparation"}", "Reprise impossible : ${e.message ?: "préparation du trajet"}")
                args.onSimulationPreparation(actions.resumeError, mapOf("result" to "RESUME_FAILED", "edge" to checkpoint.edge,
                    "savedAvoidance" to (checkpoint.avoidance != null), "exception" to e.stackTraceToString()))
            }
            finally { actions.resumeBusy = false }
        }
    }
    fun resumeMission() = withResumeEngine { checkpoint, engine ->
        // "Resume further on": point chosen on the map, without overlap; otherwise resume at the stop point.
        val skipTo = if (skipState.skipMode && skipState.skipMeters > 0f) ResumeSkipView.of(engine.edges, checkpoint, skipState.skipMeters.toDouble())?.resume
            ?: error(tr("resume point not found on the remaining stripes", "point de reprise introuvable sur les bandes restantes")) else null
        simulation.pause()
        if (args.onResumeRobotRoute(engine, skipTo)) { skipState.skipMode = false; skipState.skipMeters = 0f } else actions.resumeError = currentRouteStatus
    }
    fun backOffBeforeResume() = withResumeEngine { _, engine -> args.onBackOffBeforeResume(engine) }
    fun openResumeSkip() { skipState.skipMode = true; if (resumeEngine == null) withResumeEngine { _, _ -> } }
    val resumeSkip = state.missionCheckpoint?.takeIf { skipState.skipMode }?.let { cp -> resumeEngine?.let { ResumeSkipView.of(it.edges, cp, skipState.skipMeters.toDouble()) } }
    // Map tap in "Resume further on" mode: resume at the nearest point of the remaining stripes.
    val resumeSkipTap by rememberUpdatedState<(Point) -> Boolean> { p ->
        val cp = state.missionCheckpoint; val engine = resumeEngine
        if (!skipState.skipMode || cp == null || engine == null) false
        else { MissionProgress.skipToward(engine.edges, cp.edge, cp.point, p)?.let { skip ->
            skipState.skipMeters = skip.coerceIn(0.0, ResumeSkipView.maxMeters(engine.edges, cp)).toFloat() }; true }
    }
    fun calculateMowingPreview() {
        if (form.mode.isSurvey) form.initialProgress = 0f
        val calculationInputs=planningInputs
        val calculationMap=planningMap
        val calculationObstacles=localObstacles
        val calculationNavigation=PlanningNavigation(accesses.map { it.toList() },mapBase)
        val zones = frame.selected.mapNotNull { id -> calculationMap.shapes.firstOrNull { it.kind == ShapeKind.ZONE && it.id == id } }
        if (zones.isEmpty()) return
        val startRequest = ZoneStart.requested(calculationMap, zones.map { it.id }, start.startOverride, zoneStarts.starts)
        start.previewStart = startRequest
        sheets.pickingStart = false
        prep.preparationStartedNanos = SystemClock.elapsedRealtimeNanos()
        prep.preparationTimingInputs = JSONObject().put("startedNanos", prep.preparationStartedNanos)
            .put("zones", JSONArray(zones.map { it.name.trim() }))
            .put("zoneIds", JSONArray(zones.map { it.id }))
            .put("spacingM", form.spacing.toDouble()).put("marginM", form.margin.toDouble())
            .put("cutWidthM", form.cutWidth.toDouble()).put("minimumCoverage", minimumCoverage.toDouble())
            .put("speedMps", form.mowingSpeed.toDouble()).put("mode", form.mode.name)
            .put("borderPasses", form.borderPasses).put("noGoPasses", form.noGoPasses)
            .put("borders", form.borders).put("bordersFirst", form.bordersFirst).put("nearbyNoGo", form.nearbyNoGo).put("turnStyle", form.turnStyle.name)
            .put("orientation", form.orientation.name).put("manualAngleDeg", form.manualAngle).put("spiralDirection", form.spiralDirection.name).put("zamboniBlocks", form.zamboniBlocks.name)
            .put("demo", mapView.demo).put("baseX", mapBase?.point?.x).put("baseY", mapBase?.point?.y)
            .put("baseHeading", mapBase?.entryHeadingDegrees).put("startSource", startRequest.source.name).toString()
        Log.i("PathPreparationTiming", JSONObject(prep.preparationTimingInputs).put("event", "BEGIN").toString())
        val planSpacing = if (form.mode == CoverageMode.SURVEY_GRID) form.signalGridSpacing.toDouble() else form.spacing.toDouble()
        val planMargin = form.margin.toDouble()
        val planMode = form.mode
        val planBorders = form.borders
        val planBorderPasses = form.borderPasses
        val planNoGoPasses = form.noGoPasses
        val planBase = mapBase?.point
        val planMapBase = mapBase
        // Start nearest to the robot (fresh BLE position); base if it is charging or unknown.
        val planRobot = if (mapView.demo || args.cloud) null else marker?.takeIf { it.fresh && it.source == "BLE" }?.point
        val planDocked = state.telemetry.chargeState in setOf(1, 2)
        val planRoutingObstacles = localObstacles
        val planAccesses = accesses.map { it.toList() }
        val planBordersFirst = form.bordersFirst
        val planNearbyNoGo = form.nearbyNoGo
        val planHeights = if (form.mode.isSurvey) emptyList() else passHeights
        val planOrientationMemory = if (form.orientation == OrientationMode.EACH_SESSION && planMode.usesOrientation) state.zoneOrientations else emptyMap()
        prep.progress = Triple(0, zones.size, tr("Preparing the zones", "Préparation des zones"))
        prep.error = ""
        preview = null
        prep.estimate = null; prep.estimateError = ""; prep.planningReport=null
        val requestConfig=PlanningConfig(spacingM=planSpacing.coerceAtLeast(0.05),marginM=planMargin,cutWidthM=form.cutWidth.toDouble(),
            minimumCoverage=minimumCoverage.toDouble(),speedMps=form.mowingSpeed.toDouble(),borders=planBorders,
            borderPasses=planBorderPasses,noGoPasses=planNoGoPasses,bordersFirst=planBordersFirst,nearbyNoGo=planNearbyNoGo,
            boundary=if(mapView.demo) BoundaryMeaning.PHYSICAL else BoundaryMeaning.RECORDED_TRACE,
            outerOffsetM=if(planMode.isSurvey) SurveyOffsetSetting.meters else OuterOffsetSetting.meters,
            // Zamboni always weighs duration and wear, Spiral has no U-turns between its rings (their toggle is disabled):
            // the configuration says so too (the zigzag of the Spiral pockets and fallback zones takes the fast U-turns).
            turnStyle=if(planMode.isSurvey || planMode==CoverageMode.SPIRAL) TurnStyle.FAST else if(planMode==CoverageMode.ZAMBONI) TurnStyle.LAWN else form.turnStyle,
            orientation=if(planMode.usesOrientation) form.orientation else OrientationMode.AUTOMATIC,manualAngleDeg=foldAngle(form.manualAngle),
            spiralDirection=if(planMode==CoverageMode.SPIRAL) form.spiralDirection else SpiralDirection.FROM_EDGE,zamboniBlocks=if(planMode==CoverageMode.ZAMBONI) form.zamboniBlocks else ZamboniBlocks.LARGE)
        prep.begin(calculationInputs) { scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val coroutine=currentCoroutineContext()
                    val startRoutes=planMapBase?.let { b -> RoutePlanning.mission(map,planRoutingObstacles,planAccesses,b,recordedBoundary=!mapView.demo,avoidAreas=transitAvoid) }
                    // Chosen or saved start: nearest free point where the robot can pivot.
                    val robotStart=startRequest.point?.let { requested ->
                        if(startRoutes==null) requested
                        else requireNotNull(RoutePlanning.nearestUsable(startRoutes,requested)) {
                            tr("Start point unreachable: choose a point further inside the zone", "Point de départ inaccessible : choisissez un point plus à l'intérieur de la zone") }
                    } ?: startRoutes?.let { routes -> planMapBase?.let { b -> RoutePlanning.missionStart(routes,planRobot,planDocked,b.point) } }
                        ?: planRobot.takeIf { !planDocked }
                    val start=robotStart ?: planBase ?: org.locationtech.jts.geom.GeometryFactory().createPolygon(
                        (zones.first().points+zones.first().points.first()).map { org.locationtech.jts.geom.Coordinate(it.x,it.y) }.toTypedArray())
                        .interiorPoint.let { Point(it.x,it.y) }
                    // Mission ends at the base: the order does not come back towards the robot's start position.
                    val planned=RoutePlanning.coverage(PlanningRequest(calculationMap,zones.map { it.id },start,config=requestConfig,
                        obstacles=DockClearance.planning(calculationObstacles,planMapBase),
                        navigation=calculationNavigation,end=planBase?.takeIf { it!=start },orientationMemory=planOrientationMemory),
                        planMode,checkpoint={ coroutine.ensureActive() },
                        progress={ done,total,name -> scope.launch { prep.progress=Triple(done,total,name) } })
                    withContext(Dispatchers.Main) {
                        // Inputs changed meanwhile: abandoned without a message, the route of the new ones is computed by itself.
                        if (currentPlanningInputs!=calculationInputs || currentPlanningMap!=calculationMap || currentPlanningObstacles!=calculationObstacles || currentPlanningSelection!=zones.map { it.id }) throw CancellationException("planning inputs changed")
                        prep.planningReport=planned.report
                    }
                    // Height passes: same route repeated, every other pass in reverse.
                    CutPasses.expand(planned.preview, planHeights)
                }
                if (currentPlanningInputs!=calculationInputs || currentPlanningMap!=calculationMap || currentPlanningObstacles!=calculationObstacles || currentPlanningSelection!=zones.map { it.id }) throw CancellationException("planning inputs changed")
                preview = result
                args.onPreview(result)
                prep.estimate = realEstimate(state, mapView.demo, result.meters, form.mowingSpeed, form.autonomy, form.chargeTime, form.initialBattery, form.chargeLow, form.chargeHigh)
                prep.estimateError = MowingForecaster.note(estimateCalibration(state, mapView.demo))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                prep.error = e.message ?: tr("Computation failed", "Calcul impossible")
            } finally {
                prep.end(coroutineContext[Job])
            }
        } }
    }
    AutoRouteComputation(prep, sheets.mowingDialog && !state.robotRoute.running && !state.mowing.active && map.complete && frame.selected.isNotEmpty(),
        planningInputs, latestAction(::calculateMowingPreview))

    val density = LocalDensity.current
    val viewportTop = with(density) { 80.dp.toPx() }
    // Zone choice keeps the action bar framing: the map does not jump when "Mow" is tapped.
    val viewportBottom = with(density) { (if (actions.placingAccess || pointMove.pointMode) 280.dp else if (sim.engine != null) 220.dp else 150.dp).toPx() }
    val framingPoints = listOfNotNull(sim.engine?.base ?: mapBase?.point, marker?.point.takeIf { !mapView.demo }) + accesses.flatten() + actions.draftAccess
    val currentViewportMap = if (framingPoints.isNotEmpty()) map.copy(shapes=map.shapes+MapShape(-1,2,framingPoints)) else map
    val viewportMap = if (recoveryPoints.isNotEmpty()) currentViewportMap.copy(shapes = currentViewportMap.shapes + MapShape(-2, 2, recoveryPoints.map { Point(it.x, it.y) })) else currentViewportMap

    // Values read by the gestures in their latest version, without restarting the detector: the robot position is part
    // of the framing and changes on every reading; as a pointerInput key, it interrupted the pinch in progress.
    val gestureViewport by rememberUpdatedState(viewportMap)
    // A single full-screen box: the map takes the whole height, no bar scrolls below it.
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { mapView.mapSize = it }
                .clipToBounds()
                .background(colors.MapBackground)
                .mapDotGrid(colors.TextTertiary.copy(alpha = 0.16f))
                .pointerInput(map, actions.placingAccess, pointMove.pointMode, sheets.selectingZones, state.navTest.running, pointIdle, sim.engine, viewportTop, viewportBottom) {
                    mapViewGestures(frame, follow, { gestureViewport }, viewportTop, viewportBottom, tapEnabled = { !prep.computing && sim.engine == null }, painter = { paint.input(map).takeIf { sheets.selectingZones && !pointMove.pointMode && !actions.placingAccess && sim.engine == null } }) { p, scale ->
                        if (resumeSkipTap(p)) Unit
                        else if (pointMove.pointMode) {
                            if (pointIdle && !state.navTest.running) { pointTarget.destination = p; pointMove.pointConfirmed = false; pointMove.pointAttempted = false }
                        } else if (actions.placingAccess) {
                            actions.draftAccess = actions.draftAccess + p
                        } else if (zoneSelectionAllowed && sheets.selectingZones && !paint.active) map.shapes.firstOrNull { it.kind == ShapeKind.ZONE && Geometry.inside(p, it.points) }?.id?.let { id ->
                            frame.selected = if (id in frame.selected) frame.selected - id else frame.selected + id
                        } else if (!sheets.selectingZones) MapExtraLayers.tap(p, scale * frame.camera.zoom)
                    }
                },
        ) {
            // Content compiled outside MapScreen (non-inline lambda): see IsolatedBox.
            IsolatedBox {
            val extraLayers by MapExtraLayers.flow.collectAsStateWithLifecycle()
            // Real coverage of the current or last mowing, when it belongs to this map ([fr.luba.poc.history.RealCoverage]).
            val coverageLayer = realCoverageLayer(map, MapExtraLayer.COVERAGE in extraLayers)
            val bounds = remember(viewportMap) { MapBounds.of(viewportMap.shapes.flatMap { it.points }) }
            // Preview paths (thousands of passes) kept from one frame to the next: a zoom only changes the widths.
            val routeGeometry = remember { LiveGeometry() }
            // Zone names (interior point computed once per map) and display settings ([MapDisplaySettings]).
            val zoneNameLabels = remember(map) { zoneLabels(map) }
            MapRouteCanvas(map, frame.selected, { frame.camera }, follow, bounds, viewportTop, viewportBottom, state, sim, preview, mapBase,
                form.cutWidth, extraLayers, layers.showSignalHeat && signals.surveyMatches, signals.signalView, accesses, actions.draftAccess,
                showingRobotRoute, simWheels, resumeSkip, pointMove, pointTarget, mapView.demo, recoveryPoints, zoneNameLabels, routeGeometry,
                coverageLayer, paint.takeIf { sheets.selectingZones && it.active })
            // Animated effects (separate layer, the only one redrawn on each frame): robot glided between two readings,
            // halo, comet on the planned route, wave at the start, flash of the chosen zone.
            val fxRoute = (sim.engine?.preview ?: preview)?.takeIf { sim.state == null && !showingRobotRoute }?.cuttingPaths.orEmpty()
            // Point published 8 cm behind the rotation center: silhouette centered on that center (pivot without apparent skid).
            val fxRobot = marker?.takeIf { !mapView.demo }?.let { m -> DisplayPose.published(m.point, m.heading).let { it.copy(heading = it.heading ?: 0.0) } }
            MapFxLayer({ frame.camera }, { mapScreenProjection(bounds, it, viewportTop, viewportBottom) }, Modifier.fillMaxSize().mapAppear(map.shapes),
                robot = fxRobot, robotFresh = marker?.fresh == true,
                route = fxRoute.takeIf { MapDisplaySettings.current.routeAnimation }.orEmpty(), routeStart = fxRoute.firstOrNull { it.size >= 2 }?.first(), map = map, selected = frame.selected,
                followOffset = { follow.offset?.takeIf { follow.enabled } })
            RobotFollowDriver(follow, frame.camera, fxRobot, mapView.mapSize, viewportTop, viewportBottom) { mapScreenProjection(bounds, it, viewportTop, viewportBottom) }
            // Robot camera: button always present at the top right, including with the robot stopped (stream on demand);
            // the live view (moving or requested) shows on its left, can be hidden.
            // Outside the right bar: added to that centered column, it pushed the last button under the panels.
            // Thumbnail that can be moved (drag) and resized (bottom left corner, pinch), position saved.
            if (!mapView.demo) LiveCameraWindow(Modifier.padding(end = Dimens.Md + 44.dp + Dimens.Sm, top = 72.dp))
            LiveCameraButton(Modifier.align(Alignment.TopEnd).padding(end = Dimens.Md, top = 72.dp), enabled = !mapView.demo)

            // Scale: round length in meters, at the top left under the back button, recomputed on zoom.
            if (MapDisplaySettings.current.scaleBar && mapView.mapSize.width > 0) MapScaleBar(
                mapScreenProjection(bounds, Size(mapView.mapSize.width.toFloat(), mapView.mapSize.height.toFloat()),
                    viewportTop, viewportBottom).scale * frame.camera.zoom,
                Modifier.align(Alignment.TopStart).padding(start = Dimens.Md, top = 72.dp))

            // Mammotion header: back, status pill (Bluetooth or gateway link, positioning, batteries, weather).
            MapHeader(state, telemetry, mapView.demo, args.cloud, args.apiPosition, args.apiKnown, known, form, sheets, args.onBack,
                Modifier.align(Alignment.TopCenter))

            // Right vertical bar: compass, station, recentering, following, move to a point (layers: bottom bar, Map menu).
            // Set under the camera button (not centered): centered, its last button went under the panels.
            // In the same layout as the bottom panels: the bar stays on top, never hidden by the protection alert, the
            // layers or the other panels, which are narrowed to its left when they rise up to it.
            MapOverlayLayout(sideTop = 72.dp + 44.dp + Dimens.Md, sideEnd = Dimens.Md, horizontal = Dimens.Md, bottom = Dimens.Sm,
                gap = Dimens.Sm, modifier = Modifier.fillMaxSize(), side = {
                MapSideBar({ frame.camera }, { frame.camera = it }, follow, scope, sheets, layers, actions, pointMove, pointTarget,
                    stationEnabled = !mapView.demo && (cloudStation || (mapBase != null && !args.cloud)), followEnabled = !mapView.demo && marker != null,
                    moveEnabled = pointIdle && !state.navTest.running && map.complete, layersEnabled = !mapView.demo || signals.simulatedSurvey,
                    layersShown = signals.displayedSignalSamples.isNotEmpty())
            }, panels = {
                // Robot STOP button, collision, forbidden time range or beyond the mapped zones ([RobotProtectionPanel]).
                val protection = RobotProtection(state, mapView.demo, args.cloud)
                val robotBlocked = protection.robotBlocked
                RobotProtectionPanel(protection, state,
                    blockedCheckpoint = state.missionCheckpoint?.takeIf { !state.robotRoute.running && sim.engine == null && !pointMove.pointMode && !actions.placingAccess },
                    pointMode = pointMove.pointMode, onDiscard = { sheets.discardConfirm = true }, onUnlockRobot = args.onUnlockRobot)
                // Travel speed during mowing: set in the details ("⋯" of the mowing bar), not on the map.
                if (pointMove.pointMode) PointMovePanel(state, pointTarget.destination, pointTarget.pointPlan, pointMove.pointComputing, pointTarget.pointError, pointMove.pointAttempted, pointMove.pointConfirmed,
                    { pointMove.pointConfirmed = it }, pointIdle, args.onTrajectorySpeed, args.onStopPointNavigation,
                    onStart = { pointMove.pointAttempted = true; pointTarget.destination?.let(args.onStartPointNavigation) },
                    onClose = { args.onStopPointNavigation(); pointMove.pointMode = false; pointTarget.destination = null })
                ManualAccessPanel(actions) { draft -> accesses = accesses + listOf(draft); baseStore.saveAccesses(accesses) }
                val checkpoint = state.missionCheckpoint
                val interruptedShown = checkpoint != null && !robotBlocked && !state.robotRoute.running && !mapView.demo && !args.cloud && !pointMove.pointMode && !actions.placingAccess && sim.engine == null
                // Incident panel (robot stuck, task interrupted): it carries the progress and the actions; the stopped mowing
                // bar and the layer strip are hidden to keep the map visible (small Fold screen, 2026-10-16).
                val problemShown = interruptedShown || (robotBlocked && !protection.noteOnly)
                if (checkpoint != null && interruptedShown)
                    InterruptedMowingPanel(checkpoint, map, state, actions.resumeBusy, actions.resumeError,
                        resumeEnabled = state.ready && mapBase != null && map.complete && !actions.resumeBusy && !prep.computing && !prep.simBuilding && !state.campaign.running &&
                            !state.navTest.running && !state.bladeTestActive && !state.teach.running && !state.mapLoading && !state.manualActive && !state.silenceTest.running &&
                            !state.backOffPending && !state.unlockPending,
                        skip = if (skipState.skipMode) resumeSkip else null, skipOpen = skipState.skipMode, skipMeters = skipState.skipMeters,
                        onSkipMeters = { skipState.skipMeters = it }, onOpenSkip = latestAction(::openResumeSkip), onCloseSkip = { skipState.skipMode = false; skipState.skipMeters = 0f },
                        onBackOff = latestAction(::backOffBeforeResume), onStopBackOff = args.onStopBackOff,
                        onDiscard = { sheets.discardConfirm = true }, onResume = latestAction(::resumeMission))
                // Interrupted move to a point (stop, link, restart): resume towards the same destination.
                else if (!state.robotRoute.running && !state.navTest.running && !mapView.demo && !args.cloud && !pointMove.pointMode && !actions.placingAccess && sim.engine == null)
                    state.pointResume?.takeIf { it.robotId == state.selected?.address }?.let { resume ->
                        InterruptedPointPanel(resume, state, enabled = pointIdle && !state.navTest.running && map.complete,
                            onResume = { args.onStartPointNavigation(resume.target) }, onDiscard = args.onDiscardPointResume)
                    }
                // Heatmap layers: one button per measured layer, settings and legend behind the right icon.
                // Without a survey on this map, the bar says so and opens the history.
                val heat = signals.surveyMatches && signals.displayedSignalSamples.isNotEmpty()
                val layerBar = layers.layersOpen && (heat || (!mapView.demo && sim.engine == null && !pointMove.pointMode && !actions.placingAccess && !sheets.selectingZones))
                if (layerBar && !problemShown) MapLayerBar(if (heat) availableLayers(signals.displayedSignalSamples, signals.signalView.relief != null, layers.surveySignal) else emptyList(), layers.surveySignal,
                    visible = layers.showSignalHeat, simulated = signals.simulatedSurvey, onSelect = { layers.surveySignal = it; layers.showSignalHeat = true },
                    onToggleVisible = { layers.showSignalHeat = !layers.showSignalHeat },
                    onSettings = { if (heat) sheets.signalPanel = true else { simulation.pause(); sheets.signalHistoryOpen = true } },
                    source = signals.pinnedSurvey?.takeIf { !signals.simulatedSurvey && it === signals.overlaySurvey }?.let { surveyShortLabel(it) },
                    onClearSource = { SignalOverlay.clear() })
                if (state.robotRoute.showSupervision && (state.robotRoute.running || !problemShown)) MowingLiveBar(state, args.onStop) { sheets.signalPanel = true }
                else if (sim.engine != null && !mapView.demo && !args.cloud && !heat) SimulatedRouteBar(sheets)
                sim.state?.takeIf { !showingRobotRoute }?.let { s ->
                    val total = sim.engine?.total ?: return@let
                    SimulationBar(s, total, sim, simulation, sheets, state.robotRoute.running)
                }
                RouteComputationPanels(prep, sheets)
                // Zone choice on the map, in mowing order (numbers on the map).
                if (sheets.selectingZones && !pointMove.pointMode && !actions.placingAccess && sim.engine == null)
                    ZoneChoicePanel(sheets, form, prep, map, frame.selected, paint) { frame.selected = it }
                val showActions = !pointMove.pointMode && !actions.placingAccess && !sheets.selectingZones && sim.engine == null && !state.robotRoute.running
                if (showActions) MapActions(state, mapView.demo, args.cloud, map, preview != null, sheets, form, onChooseZones = { selectTask(false) },
                    onFetchMap = { mapView.demo = false; args.onFetchMap() }, onOpenAccount = args.onOpenAccount, onOpenManual = args.onOpenManual)
            })
            }
        }
    }

    // Sheets and dialogs: named composables reading the state holders (MapSheetsHost.kt).
    MapSignalsSheet(sheets, layers, state, sim, simulation, signals.signalView, signals.displayedSignalSamples, signals.simulatedSurvey, signals.surveyMatches,
        mapView.demo, args.cloud, cuttingHeight, args.onStartRobotRoute, args.onTrajectorySpeed, args.onStop)
    val mapSource = when { sim.engine != null -> MapSource.SIMULATION; mapView.demo -> MapSource.DEMO; args.cloud -> MapSource.ACCOUNT; else -> MapSource.ROBOT }
    MapInfoSheets(sheets, state, if (args.cloud) args.apiPosition ?: Telemetry.EMPTY else telemetry, mapSource, showingRobotRoute,
        marker.takeIf { !mapView.demo }, known.takeIf { !mapView.demo && sim.engine == null }, args.onOpenTask, args.onOpenAllTasks, args.onOpenRobot)
    MapTerrainDialogs(sheets, layers, signals.relief, signals.simulatedSurvey, signalHistoryStore, map, if (signals.simulatedSurvey) sim.engine?.map ?: map else map,
        signals.signalView, signals.displayedSignalSamples, state.signalSurvey.sessionId)
    MapSettingsDialogs(sheets, layers, state, map, mapSource, signals.signalView, signals.displayedSignalSamples, signals.surveyMatches, signals.simulatedSurvey,
        simulating = sim.engine != null, demo = mapView.demo, cloud = args.cloud, prep = prep,
        onToggleDemo = { mapView.demo = !mapView.demo; preview = null }, onFetchMap = { mapView.demo = false; args.onFetchMap() }, onOpenAccount = args.onOpenAccount,
        onOpenDrivenRecord = args.onOpenDrivenRecord, onSaveCorrections = args.onSaveCorrections)
    MapSimulationDialog(sheets, form, sim, simulation, preview.takeIf { preparedSimulation != null }, mapBase, planningMap, frame.selected, { frame.selected = it },
        mapView.demo, zoneSelectionAllowed, prep, state.robotRoute.running, latestAction(::startMowingSimulation))
    // Task pages: named composable reading the state holders (MowingTaskPages.kt). Local functions passed through
    // latestAction: a bare reference ran on the settings of an old composition (LatestAction.kt).
    MowingTaskPages(sheets, form, prep, state, map, planningMap, preview, frame.selected, localObstacles, mapView.demo, args.cloud, passHeights, cuttingHeight,
        start, zoneStarts, prepared = preparedSimulation != null && mapBase != null, simulateEnabled = simulateEnabled,
        onCompute = latestAction(::calculateMowingPreview), onSimulate = latestAction(::startMowingSimulation), onStartRobot = latestAction(::startPreparedRoute),
        onStop = args.onStop, onTrajectorySpeed = args.onTrajectorySpeed)
    MapStationDialogs(sheets, actions, prep, state, cloudStation, args.cloudReturn, args.cloudRobotName, args.onCloudReturn, args.onCancelCloudReturn,
        stationEnabled = pointIdle && !state.navTest.running && mapBase != null, onBaseAction = latestAction1(::baseAction),
        onStopMammotionTask = args.onStopMammotionTask, onDiscardMissionCheckpoint = args.onDiscardMissionCheckpoint)
}

/** Mowing task settings written whenever one of them changes (kept from one task to the next, [MowingTaskStore]). */
@Composable
private fun SaveMowingTask(store: MowingTaskStore, settings: MowingTaskSettings) {
    LaunchedEffect(settings) { store.write(settings) }
}

/** Layer choice written whenever it changes (kept until the next change, [MapLayerSettings]). */
@Composable
private fun SaveMapLayers(context: Context, choice: MapLayerChoice) {
    LaunchedEffect(choice) { MapLayerSettings.save(context, choice) }
}

/** Recharges of the task: current or done over the total planned at start ("1 / ≈ 2"), or just the number done. */
internal fun rechargeLine(state: RobotUi): String {
    val record = state.mowingRecord
    val done = record?.recharges ?: state.robotRoute.rechargeCycles
    return record?.let { "$done / ≈ ${maxOf(done, it.forecast.recharges)}" } ?: "$done"
}

/** Line "1. Aire de jeux → 2. Piscine": actual mowing order. */
internal fun selectedZoneLine(map: RobotMap, order: List<Long>): String =
    order.mapIndexedNotNull { index, id -> map.shapes.firstOrNull { it.id == id }?.let { "${index + 1}. ${MapCorrections.zoneTitle(it, "Zone ${it.id}")}" } }
        .joinToString(" → ").ifBlank { tr("none", "aucune") }

/** Calibration taken from the robot's real mowing history (none in demo). */
private fun estimateCalibration(state: RobotUi, demo: Boolean): MowingCalibration? =
    if (demo) null else MowingCalibration.from(state.mowingHistory, state.selected?.address)

/** Real mowing estimate: battery of the connected robot (setting otherwise), parameters adjusted by the history. */
internal fun realEstimate(state: RobotUi, demo: Boolean, meters: Double, speed: Float, autonomy: Float, charge: Float,
                         battery: Float, low: Float, high: Float): MowingEstimate =
    MowingForecaster.estimate(meters, speed.toDouble(), autonomy.toDouble(), charge.toDouble(),
        state.telemetry.battery?.takeIf { state.ready && !demo }?.toDouble() ?: battery.toDouble(), low.toDouble(), high.toDouble(),
        estimateCalibration(state, demo))

/**
 * Map content compiled in its own class (non-inline composable lambda).
 * MapScreen exceeded 256 Dalvik registers: D8 reused the Composer register there as an integer and ART rejected the
 * whole class (VerifyError when opening the map). Inline blocks (Box, Row, Column) copied all their content into the
 * method; this boundary keeps MapScreenContent far from the limit (136 registers on 2026-10-21, threshold 224 of
 * checkFoldDexRegisters). New states go into the holders of MapScreenState.kt, new panels and sheets into named
 * composables taking them. Build check: tools/check-dex-registers.py.
 */
@Composable
private fun BoxScope.IsolatedBox(content: @Composable BoxScope.() -> Unit) = content()
