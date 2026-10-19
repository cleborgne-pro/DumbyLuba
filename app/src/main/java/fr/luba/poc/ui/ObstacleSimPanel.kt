package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.simulation.*
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Service → Obstacle simulation: an object (square, round, triangle) placed with a finger on the map, unknown to the
 * planner, is encountered during the simulated mowing of a zone. Contact, back-off and repaired route are computed
 * by the same functions as real mowing ([ObstacleBypass]). No command is sent to the robot.
 */
@Composable
internal fun ObstacleSimPage(state: RobotUi, back: () -> Unit) {
    val map = state.robotMap
    val context = LocalContext.current
    val store = remember(map) { BaseStore(context, map) }
    val base = remember(map) { store.resolve(map) }
    val accesses = remember(map) { store.accesses() }
    val zones = remember(map) { map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 } }
    val t = state.telemetry
    var zoneId by rememberSaveable(map) {
        mutableLongStateOf((zones.firstOrNull { z -> t.x != null && t.y != null && Geometry.inside(Point(t.x, t.y), z.points) } ?: zones.firstOrNull())?.id ?: -1L)
    }
    var shape by rememberSaveable { mutableStateOf(ProbeShape.SQUARE) }
    var size by rememberSaveable { mutableFloatStateOf(0.6f) }
    var rotation by rememberSaveable { mutableFloatStateOf(0f) }
    var center by remember(map) { mutableStateOf<Point?>(null) }
    var speed by rememberSaveable { mutableFloatStateOf(10f) }
    var nearStart by rememberSaveable { mutableStateOf(true) }
    var route by remember(map) { mutableStateOf<EncounterRoute?>(null) }
    var routeZone by remember(map) { mutableLongStateOf(-1L) }
    var sim by remember { mutableStateOf<ObstacleEncounterSimulation?>(null) }
    var frame by remember { mutableStateOf<EncounterFrame?>(null) }
    var playing by remember { mutableStateOf(false) }
    var computing by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var cameraReset by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // A single simulation advance at a time (animated playback or playback to the end).
    val lock = remember { Mutex() }
    val probe = center?.let { ProbeObject(shape, it, size.toDouble(), rotation.toDouble()) }

    // Object or zone changed: the current simulation no longer matches.
    LaunchedEffect(probe, zoneId) { playing = false; sim = null; frame = null }

    fun prepare() {
        val b = base ?: return
        val zone = zones.firstOrNull { it.id == zoneId } ?: return
        computing = true; error = ""; stage = tr("Preparation", "Préparation")
        scope.launch {
            try {
                route = withContext(Dispatchers.Default) {
                    val coroutine = currentCoroutineContext()
                    ObstacleEncounterSimulation.route(map, zone, b, accesses, state.obstacles,
                        checkpoint = { coroutine.ensureActive() }, progress = { scope.launch { stage = it } })
                }
                routeZone = zone.id
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: tr("Computation impossible", "Calcul impossible"); route = null }
            finally { computing = false }
        }
    }
    fun restart() {
        val r = route ?: return
        val p = probe ?: return
        val created = ObstacleEncounterSimulation(r.engine.routes, r.physical, r.edges, p, r.zones,
            if (nearStart) ObstacleEncounterSimulation.startNear(r.edges, p) else 0,
            speedPercent = state.trajectorySpeedPercent, baseApproach = r.engine.baseApproach)
        sim = created; frame = created.frame(); playing = true
    }
    fun finish() {
        val s = sim ?: return
        playing = false; computing = true; stage = tr("Playing to the end", "Lecture jusqu'à la fin")
        scope.launch {
            try { frame = lock.withLock { withContext(Dispatchers.Default) { s.finish(); s.frame() } } }
            finally { computing = false }
        }
    }

    // Animated playback: simulated time = 50 ms × speed; computation (repairs included) off the UI thread.
    LaunchedEffect(playing, sim) {
        val s = sim ?: return@LaunchedEffect
        while (playing && s.running) {
            val step = 0.05 * speed
            frame = lock.withLock { withContext(Dispatchers.Default) { s.advance(step); s.frame() } }
            delay(50)
        }
        if (!s.running) playing = false
    }

    val painter = remember(map) {
        MapPaintInput(begin = { p -> center = p }, extend = { p -> center = p }, finish = { })
    }
    val routeReady = route != null && routeZone == zoneId
    PushedPage(ServicePage.OBSTACLE_SIM.title, back, bottomBar = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            if (!routeReady) PrimaryPillButton(if (computing) tr("Computing…", "Calcul…") else tr("Prepare route", "Préparer le trajet"), ::prepare, Modifier.weight(1f),
                enabled = base != null && zoneId >= 0 && !computing && map.complete, icon = Icons.Rounded.Route)
            else {
                SecondaryPillButton(if (sim == null) tr("Start", "Lancer") else tr("Restart", "Recommencer"), ::restart, Modifier.weight(1f),
                    enabled = probe != null && !computing, icon = Icons.Rounded.Replay)
                PrimaryPillButton(if (playing) "Pause" else tr("Play", "Lecture"), { playing = !playing }, Modifier.weight(1f),
                    enabled = sim?.running == true && !computing, icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow)
                SecondaryPillButton(tr("End", "Fin"), ::finish, Modifier.weight(0.7f), enabled = sim?.running == true && !computing, icon = Icons.Rounded.SkipNext)
            }
        }
    }) {
        InfoNote(tr("Simulation only, no command sent to the robot. The object, unknown to the planner, is only discovered on contact, as in real mowing.", "Simulation seule, aucune commande envoyée au robot. L'objet, inconnu du planificateur, n'est découvert qu'au contact, comme en tonte réelle."))
        ObstacleSimMap(state, route?.takeIf { routeReady }, probe, frame, painter, cameraReset) { cameraReset++ }
        frame?.let { f -> ObstacleSimStatus(f) }
        SettingsGroup(tr("Object", "Objet")) {
            Segmented(ProbeShape.entries.map { it to it.label }, shape, { shape = it })
            SliderRow(if (shape == ProbeShape.CIRCLE) tr("Diameter", "Diamètre") else tr("Side", "Côté"), size, { size = (it * 10).let(Math::round) / 10f },
                ProbeObject.MIN_SIZE_M.toFloat()..ProbeObject.MAX_SIZE_M.toFloat(), valueLabel = "${Format.num(size.toDouble(), 1)} m")
            if (shape != ProbeShape.CIRCLE) SliderRow("Orientation", rotation, { rotation = Math.round(it / 5f) * 5f }, 0f..180f, valueLabel = "${rotation.toInt()}°")
            center.let { c ->
                if (c == null) InfoNote(tr("Tap the map to place the object; two fingers pan and zoom the view.", "Touchez la carte pour placer l'objet ; deux doigts déplacent et zooment la vue."))
                else KeyValueRow(tr("Position (tap or drag to move)", "Position (touchez ou glissez pour déplacer)"), "${Format.num(c.x, 1)} ; ${Format.num(c.y, 1)} m")
            }
        }
        SettingsGroup(tr("Simulated route", "Trajet simulé")) {
            if (zones.isEmpty()) InfoNote(tr("No zone: read the robot map.", "Aucune zone : lisez la carte du robot."))
            else ChipChoice(zones.map { it.id to it.name.ifBlank { "Zone ${it.id}" } }, zoneId, { zoneId = it }, enabled = !computing)
            if (base == null) InfoNote(tr("Base unknown or without a docking axis: leave the robot charging so it can be observed, or read the map again.", "Base inconnue ou sans axe d'accostage : laissez le robot en charge pour l'observer, ou relisez la carte."))
            SliderRow(tr("Playback speed", "Vitesse de lecture"), speed, { speed = Math.round(it).toFloat() }, 1f..40f, valueLabel = "× ${speed.toInt()}")
            ToggleRow(tr("Start near the object", "Départ près de l'objet"), nearStart, { nearStart = it }, subtitle = tr("A few meters before the first pass near the object", "Quelques mètres avant le premier passage près de l'objet"))
            if (computing) ProgressNote(stage)
            if (error.isNotBlank()) StatusNote(error, NoteTone.DANGER)
            if (routeReady) route?.let { KeyValueRow(tr("Prepared route", "Trajet préparé"), "${Format.num(it.edges.sumOf { e -> e.meters }, 0)} m · ${it.edges.size} segments") }
        }
    }
}

@Composable
private fun ObstacleSimMap(state: RobotUi, route: EncounterRoute?, probe: ProbeObject?, frame: EncounterFrame?, painter: MapPaintInput,
                           cameraReset: Int, recenter: () -> Unit) {
    val colors = MammotionColors
    val overlays = buildList {
        // Original route (light dotted), repaired remaining route (orange), object (red), contacts (dots).
        (frame?.original ?: route?.edges?.let { e -> listOf(e.first().a) + e.map { it.b } })?.let {
            add(MapOverlay(it, colors.TextSecondary.copy(alpha = 0.45f), 1.2f)) }
        frame?.remaining?.takeIf { it.size >= 2 }?.let { add(MapOverlay(it, colors.Warning, 2.2f)) }
        probe?.let { add(MapOverlay(it.outline(), colors.Danger, 1.5f, closed = true, fill = colors.Danger.copy(alpha = 0.55f))) }
        frame?.contacts?.takeIf { it.isNotEmpty() }?.let { c -> add(MapOverlay(c.map { it.pose.position }, colors.Danger, 2f, dots = true)) }
    }
    SettingsGroup(tr("Map", "Carte")) {
        Box(Modifier.fillMaxWidth().height(420.dp).clip(MaterialTheme.shapes.medium).background(colors.MapBackground)) {
            MapLive(map = state.robotMap, x = frame?.pose?.position?.x, y = frame?.pose?.position?.y, heading = frame?.pose?.headingDegrees, publishedPosition = false,
                trail = frame?.trail.orEmpty(), obstacles = state.obstacles + frame?.discovered.orEmpty(),
                interactive = true, cameraReset = cameraReset, onPaint = painter, overlays = overlays, modifier = Modifier.fillMaxSize())
            MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), recenter, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        InfoNote(tr("Gray: prepared route · orange: route followed (repaired after contact) · red: object and contacts · circles: estimated obstacles.", "Gris : trajet préparé · orange : trajet suivi (adapté après contact) · rouge : objet et contacts · cercles : obstacles estimés."))
    }
}

@Composable
private fun ObstacleSimStatus(frame: EncounterFrame) {
    SettingsGroup(tr("Tracking", "Suivi")) {
        StatusNote(frame.message, when (frame.phase) {
            EncounterPhase.STOPPED -> NoteTone.DANGER
            EncounterPhase.BACKING_OFF -> NoteTone.WARNING
            else -> NoteTone.INFO
        })
        KeyValueRow(tr("State", "État"), frame.phase.label)
        KeyValueRow("Contacts", "${frame.contacts.size}")
        KeyValueRow(tr("Simulated time · distance", "Temps simulé · distance"), "${Format.num(frame.elapsedS / 60, 1)} min · ${Format.num(frame.meters, 0)} m")
        frame.contacts.lastOrNull()?.repair?.let { r ->
            KeyValueRow(tr("Last repair", "Dernière adaptation"), tr("back-off ${Format.num((r.backOff?.distance(frame.contacts.last().pose.position) ?: 0.0) * 100, 0)} cm · " +
                "${r.bypasses} link(s) · ${Format.num(r.skippedCuttingMeters, 1)} m of cut left", "recul ${Format.num((r.backOff?.distance(frame.contacts.last().pose.position) ?: 0.0) * 100, 0)} cm · " +
                "${r.bypasses} raccord(s) · ${Format.num(r.skippedCuttingMeters, 1)} m de coupe laissés"))
        }
        LogLines(frame.log, title = tr("Log", "Journal"))
    }
}
