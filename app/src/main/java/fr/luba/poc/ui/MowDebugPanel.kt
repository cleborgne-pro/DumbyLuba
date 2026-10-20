package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.MapPaintInput
import fr.luba.poc.map.PaintStroke
import fr.luba.poc.map.PaintedMission
import fr.luba.poc.map.PaintedZone
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.simulation.BaseStore
import fr.luba.poc.simulation.MissionAvoidance
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Map tool: brush, eraser or plain view panning. */
private enum class PaintTool(private val labelEn: String, private val labelFr: String) {
    PAINT("Paint", "Peindre"), ERASE("Erase", "Gommer"), MOVE("Move", "Déplacer");
    val label: String get() = tr(labelEn, labelFr)
}

/** Painted zone kept for the session (to look at the map and come back), for a single map. */
private object PaintDraft {
    private var draft by mutableStateOf<Pair<RobotMap, List<PaintStroke>>?>(null)
    fun strokes(map: RobotMap): List<PaintStroke> = draft?.takeIf { it.first == map }?.second.orEmpty()
    fun update(map: RobotMap, change: (List<PaintStroke>) -> List<PaintStroke>) { draft = map to change(strokes(map)) }
}

/** Service → Trial mowing: the painted zone (border following has its own page, see [BorderDebugPage]). */
@Composable
internal fun MowDebugPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) =
    PaintDebugPage(robot, state, back, header)

/**
 * Service → Trial mowing (painted zone): real mowing, blades running, limited to a zone painted with a finger on the map.
 * Paint is only kept inside existing zones; the route is computed, checked and followed by the same building
 * blocks as mowing started from the map (planner, preparation, BLE control, return to base, robot protections).
 */
@Composable
private fun PaintDebugPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    val map = state.robotMap
    val context = LocalContext.current
    val store = remember(map) { BaseStore(context, map) }
    val base = remember(map) { store.resolve(map) }
    val accesses = remember(map) { store.accesses() }
    var tool by rememberSaveable { mutableStateOf(PaintTool.PAINT) }
    var brush by rememberSaveable { mutableFloatStateOf(0.6f) }
    var height by rememberSaveable { mutableIntStateOf(state.telemetry.bladeHeightMm ?: state.cutHeights.defaultMm) }
    // Range of the connected model (higher on the H versions): a height chosen for another robot is brought back into it.
    LaunchedEffect(state.cutHeights) { height = state.cutHeights.clamp(height) }
    var spacing by rememberSaveable { mutableFloatStateOf(0.25f) }
    var borders by rememberSaveable { mutableStateOf(true) }
    var supervised by rememberSaveable { mutableStateOf(false) }
    var drawing by remember { mutableStateOf<PaintStroke?>(null) }
    // Displayed retained area: outlines outside no-go zones and area actually mowed.
    var retained by remember { mutableStateOf(emptyList<List<Point>>() to 0.0) }
    var pieceCount by remember { mutableIntStateOf(0) }
    var mission by remember { mutableStateOf<PaintedMission?>(null) }
    var computing by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val strokes = PaintDraft.strokes(map)
    val route = state.robotRoute
    val routeStarted = route.started && route.running

    // Retained area recomputed after each stroke; any change invalidates the prepared route.
    LaunchedEffect(map, strokes) {
        delay(150)
        val pieces = withContext(Dispatchers.Default) { runCatching { PaintedZone.pieces(map, strokes) }.getOrDefault(emptyList()) }
        retained = withContext(Dispatchers.Default) {
            runCatching { PaintedZone.mowable(map, pieces).let { PaintedZone.outlines(it) to it.area } }.getOrDefault(emptyList<List<Point>>() to 0.0)
        }
        pieceCount = pieces.size
    }
    LaunchedEffect(map, strokes, spacing, borders, state.obstacles, base) { mission = null; error = "" }

    val idle = state.ready && !route.running && !state.manualActive && !state.mowing.active && !state.navTest.running &&
        !state.teach.running && !state.campaign.running && !state.bladeTestActive && !state.mapLoading && !state.bladeHeightPending &&
        !state.heightSequence.running

    fun compute() {
        val b = base ?: return
        val t = state.telemetry
        // Start as close as possible to the robot; the base if it is charging or its position is unknown.
        val start = if (t.x != null && t.y != null && t.chargeState !in setOf(1, 2)) Point(t.x, t.y) else b.point
        val inputs = Triple(strokes, spacing, borders)
        computing = true; error = ""; mission = null; stage = tr("Preparation", "Préparation")
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.Default) {
                    val coroutine = currentCoroutineContext()
                    PaintedZone.prepare(map, strokes, start, b, accesses, state.obstacles, spacing.toDouble(), borders, 0.3,
                        checkpoint = { coroutine.ensureActive() }, progress = { scope.launch { stage = it } })
                }
                if (inputs == Triple(PaintDraft.strokes(map), spacing, borders) && map == robot.state.value.robotMap) {
                    mission = prepared
                    robot.recordMapPreview(prepared.preview)
                } else error = tr("Zone or settings changed during computation: compute again", "Zone ou réglages modifiés pendant le calcul : recalculez")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: tr("Computation impossible", "Calcul impossible") }
            finally { computing = false }
        }
    }

    val painter = remember(map) {
        MapPaintInput(
            begin = { p -> drawing = PaintStroke(listOf(p), brush.toDouble(), tool == PaintTool.ERASE) },
            extend = { p ->
                drawing?.let { s -> if (s.points.last().distance(p) >= s.radiusM / 4) drawing = s.copy(points = s.points + p) }
            },
            finish = { committed ->
                drawing?.let { s -> if (committed) PaintDraft.update(map) { it + s } }
                drawing = null
            },
        )
    }

    PushedPage(ServicePage.MOW_DEBUG.title, back, bottomBar = {
        MowDebugActions(robot, state, mission?.engine, height, supervised, idle, computing, base != null && strokes.isNotEmpty() && idle, ::compute,
            onStart = ::forgetBorderFollow)
    }) {
        header()
        SafetyNotice(tr("Real mowing, blades running, limited to the painted zone. Area cleared (toys, cables, stones), no people or animals, physical stop within reach.", "Vraie tonte, lames en marche, limitée à la zone peinte. Zone dégagée (jouets, câbles, cailloux), personne ni animal, arrêt physique accessible."))
        MowDebugMap(state, strokes + listOfNotNull(drawing), retained.first, mission, painter.takeIf { tool != PaintTool.MOVE && !routeStarted })
        SettingsGroup(tr("Painted zone", "Zone peinte")) {
            Segmented(PaintTool.entries.map { it to it.label }, tool, { tool = it }, enabled = !routeStarted)
            SliderRow(tr("Brush size", "Taille du pinceau"), brush, { brush = it }, 0.3f..2f, valueLabel = "Ø ${Format.num(2.0 * brush, 1)} m", enabled = !routeStarted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(tr("Undo", "Annuler"), { PaintDraft.update(map) { it.dropLast(1) } }, Modifier.weight(1f),
                    enabled = strokes.isNotEmpty() && !routeStarted && !computing, icon = Icons.AutoMirrored.Rounded.Undo)
                SecondaryPillButton(tr("Clear all", "Tout effacer"), { PaintDraft.update(map) { emptyList() } }, Modifier.weight(1f),
                    enabled = strokes.isNotEmpty() && !routeStarted && !computing, icon = Icons.Rounded.Delete)
            }
            KeyValueRow(tr("Retained area", "Surface retenue"), if (pieceCount == 0) "—" else tr("${Format.num(retained.second, 1)} m² outside no-go zones", "${Format.num(retained.second, 1)} m² hors no-go") + " · ${Format.plural(pieceCount, tr("temporary zone", "zone temporaire"), tr("temporary zones", "zones temporaires"))}")
            InfoNote(tr("One finger paints, two fingers pan and zoom. Only paint inside existing zones is kept (orange outline): " +
                "no-go zones bypassed, learned obstacles and base surroundings excluded, pieces under ${Format.num(PaintedZone.MIN_PIECE_M2, 1)} m² ignored.",
                "Un doigt peint, deux doigts déplacent et zooment. Seule la peinture dans les zones existantes est retenue (contour orange) : " +
                "no-go contournés, obstacles appris et abords de la base exclus, morceaux de moins de ${Format.num(PaintedZone.MIN_PIECE_M2, 1)} m² ignorés."))
        }
        MowDebugStatus(state, mission, computing, stage, error, base != null)
        MowDebugSettings(robot, state, height, { height = it }, spacing, { spacing = it }, borders, { borders = it }, supervised, { supervised = it }, routeStarted || computing)
    }
}

@Composable
private fun MowDebugMap(state: RobotUi, paint: List<PaintStroke>, pieces: List<List<Point>>, mission: PaintedMission?, painter: MapPaintInput?) {
    val t = state.telemetry
    val route = state.robotRoute
    var cameraReset by remember { mutableIntStateOf(0) }
    SettingsGroup(tr("Map", "Carte")) {
        Box(Modifier.fillMaxWidth().height(420.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
            MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading,
                trail = if (route.started) route.trail else emptyList(),
                planned = if (route.started) route.planned else mission?.engine?.edges?.filterNot { it.cutting }?.let { e -> e.map { it.a } + listOfNotNull(e.lastOrNull()?.b) }.orEmpty(),
                cuttingPasses = mission?.preview?.cuttingPaths.orEmpty(),
                obstacles = state.obstacles, interactive = true, cameraReset = cameraReset,
                paint = paint, paintPieces = pieces, onPaint = painter, modifier = Modifier.fillMaxSize())
            MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        if (t.x == null || t.y == null) InfoNote(tr("Robot position unavailable: the route will start from the base.", "Position du robot indisponible : le trajet partira de la base."))
        else if (t.chargeState in setOf(1, 2)) InfoNote(tr("Robot charging: straight exit from the base, then a link; recharges first if the battery is too low.", "Robot en charge : sortie de base en ligne droite, puis raccord ; recharge d'abord si la batterie est trop basse."))
        if (!state.robotMap.complete) InfoNote(tr("Incomplete map: read the robot map before painting.", "Carte incomplète : lisez la carte du robot avant de peindre."))
    }
}

@Composable
private fun MowDebugSettings(robot: RobotBle, state: RobotUi, height: Int, onHeight: (Int) -> Unit, spacing: Float, onSpacing: (Float) -> Unit,
                             borders: Boolean, onBorders: (Boolean) -> Unit, supervised: Boolean, onSupervised: (Boolean) -> Unit, locked: Boolean) {
    SettingsGroup(tr("Mowing settings", "Réglages de la tonte")) {
        SliderRow(tr("Cutting height", "Hauteur de coupe"), height.toFloat(), { onHeight(it.toInt()) }, state.cutHeights.floatRange(),
            valueLabel = "$height mm", steps = state.cutHeights.sliderSteps, enabled = !locked)
        SliderRow(tr("Pass spacing", "Espacement des passes"), spacing, { onSpacing((it * 20).let(Math::round) / 20f) }, 0.15f..0.35f,
            valueLabel = "${(spacing * 100).toInt()} cm", steps = 3, enabled = !locked)
        SliderRow(tr("Forward speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
            valueLabel = "${state.trajectorySpeedPercent} %", enabled = !locked)
        ToggleRow(tr("Border of the painted zone", "Bordure de la zone peinte"), borders, onBorders, enabled = !locked, subtitle = tr("One lap of the retained outline before the passes", "Un tour du contour retenu avant les passes"))
        LubaCheckRow(tr("I am supervising on site, area cleared", "Je supervise sur place, zone dégagée"), supervised, onSupervised, enabled = !state.robotRoute.running)
    }
    InfoNote(tr("Like mowing from the map: link from the robot, passes with blades running, links with blades stopped, return to base. " +
        "Obstacle, contact, deviation, unexpected state or STOP: robot and blades stop. " +
        "Required coverage: ${(PaintedZone.MIN_COVERAGE * 100).toInt()} % of the retained area.",
        "Comme une tonte depuis la carte : raccord depuis le robot, passes lames en marche, liaisons lames arrêtées, retour à la base. " +
        "Obstacle, contact, écart, état inattendu ou STOP : arrêt du robot et des lames. " +
        "Couverture exigée : ${(PaintedZone.MIN_COVERAGE * 100).toInt()} % de la surface retenue."))
}

@Composable
private fun MowDebugStatus(state: RobotUi, mission: PaintedMission?, computing: Boolean, stage: String, error: String, baseKnown: Boolean) {
    // Nothing to track: no empty card.
    if (baseKnown && !computing && error.isBlank() && mission == null && !state.robotRoute.started) return
    SettingsGroup(tr("Tracking", "Suivi")) {
        if (!baseKnown) StatusNote(tr("Base unknown or without a docking axis: leave the robot charging so it can be observed, or read the map again.", "Base inconnue ou sans axe d'accostage : laissez le robot en charge pour l'observer, ou relisez la carte."), NoteTone.WARNING)
        if (computing) ProgressNote(tr("Computing the route · $stage", "Calcul du trajet · $stage"))
        if (error.isNotBlank()) StatusNote(error, NoteTone.DANGER)
        mission?.let { m ->
            val metrics = m.report?.selected?.metrics
            KeyValueRow(tr("Target area", "Surface ciblée"), tr("${Format.num(m.areaM2, 1)} m² outside no-go zones", "${Format.num(m.areaM2, 1)} m² hors no-go"))
            if (m.preview.skippedBorderLoops > 0) StatusNote(tr("${m.preview.skippedBorderLoops} border lap(s) impassable for the chassis " +
                "(no-go zone or obstacle too close): not driven, zigzags with a reserve around them.", "${m.preview.skippedBorderLoops} tour(s) de bordure impraticable(s) pour le châssis " +
                "(no-go ou obstacle trop près) : non parcouru(s), zigzags avec réserve autour."), NoteTone.WARNING)
            KeyValueRow(tr("Planned coverage", "Couverture prévue"), metrics?.let { "${(it.coverage * 100).toInt()} %" } ?: "—")
            KeyValueRow(tr("Cutting · links", "Coupe · liaisons"), "${Format.num(m.engine.total, 0)} m · ${Format.num(m.engine.edges.filterNot { it.cutting }.sumOf { it.meters }, 0)} m")
        }
        MowDebugRouteStatus(state)
    }
}

/** Progress of the current or finished BLE route, shared by both modes. */
@Composable
internal fun MowDebugRouteStatus(state: RobotUi) {
    val route = state.robotRoute
    if (!route.started) return
    StatusNote(route.status, if (route.running) NoteTone.WARNING else NoteTone.INFO)
    if (route.count > 0) {
        val done = route.index.toFloat() / route.count
        LubaProgress(done, Modifier.fillMaxWidth())
        KeyValueRow("Segment", "${route.index}/${route.count}")
    }
    KeyValueRow(tr("Cutting height", "Hauteur de coupe"), "${route.heightMm} mm")
    KeyValueRow(tr("Battery", "Batterie"), state.telemetry.battery?.let { "$it %" } ?: "—")
    if (route.running) InfoNote(tr("Mowing continues if you leave this page; STOP remains available here and on the map.", "La tonte continue si vous quittez cette page ; STOP reste disponible ici et sur la carte."))
}

@Composable
internal fun ColumnScope.MowDebugActions(robot: RobotBle, state: RobotUi, engine: MowingSimulation?, height: Int, supervised: Boolean, idle: Boolean,
                                         computing: Boolean, canCompute: Boolean, compute: () -> Unit, onStart: () -> Unit = {},
                                         blades: Boolean = true) {
    if (state.robotRoute.running) {
        DangerPillButton(if (blades) tr("STOP · stop the robot and the blades", "STOP · arrêter le robot et les lames") else tr("STOP · stop the robot", "STOP · arrêter le robot"), { robot.stopBladeTest(tr("STOP debug mowing", "STOP tonte debug")) }, Modifier.fillMaxWidth())
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        SecondaryPillButton(if (computing) tr("Computing…", "Calcul…") else tr("Compute", "Calculer"), compute, Modifier.weight(1f),
            enabled = canCompute && !computing, icon = Icons.Rounded.Route)
        // Prepared with saved obstacles only (neither presumed obstacles nor avoided areas): the resume prepares the same way.
        PrimaryPillButton(if (blades) tr("Mow · $height mm", "Tondre · $height mm") else tr("Follow · blades OFF", "Suivre · lames OFF"),
            { engine?.let { if (robot.startRobotRoute(it, blades, height, MissionAvoidance(emptyList(), emptyList()))) onStart() } }, Modifier.weight(1.3f),
            enabled = engine != null && supervised && idle && !computing && state.robotMap == engine.map, icon = Icons.Rounded.PlayArrow)
    }
    // A single saved task per robot: the new one replaces the one waiting to be resumed on the map.
    if (engine != null) state.missionCheckpoint?.let {
        InfoNote(tr("Starting replaces the saved task “${it.task.title}” (${(it.progress * 100).toInt()} % ${it.task.doneLabel}), resumed from the map.", "Démarrer remplace la sauvegarde « ${it.task.title} » (${(it.progress * 100).toInt()} % ${it.task.doneLabel}), reprise depuis la carte."))
    }
    InfoNote(tr("Interrupted (STOP, link, app closed): resume from the point reached, from the map.", "Interrompu (STOP, liaison, fermeture de l'application) : reprise au point atteint depuis la carte."))
}
