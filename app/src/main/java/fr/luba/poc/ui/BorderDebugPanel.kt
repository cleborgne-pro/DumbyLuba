package fr.luba.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import fr.luba.poc.map.BorderChoice
import fr.luba.poc.map.BorderFollow
import fr.luba.poc.map.BorderMission
import fr.luba.poc.map.BorderValidation
import fr.luba.poc.map.BorderValidationEntry
import fr.luba.poc.map.BorderValidationStatus
import fr.luba.poc.map.BorderValidationStore
import fr.luba.poc.map.RecordedBoundary
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.MapOverlay
import fr.luba.poc.map.MapPaintInput
import fr.luba.poc.map.OuterOffsetSetting
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.BaseStore
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Measurement during the loop: deviation from the recorded outline and from the planned route, in metres. */
private data class BorderSample(val toBorderM: Double, val toPlannedM: Double)

/**
 * Border, offset and last started loop kept for the session: the measurement continues
 * when leaving the page and coming back during the loop.
 */
private object BorderDraft {
    var shapeId by mutableStateOf<Long?>(null)
    /** Starts from the mowing offset (kept between two launches). */
    var offsetM by mutableFloatStateOf(OuterOffsetSetting.meters.toFloat())
    /** Blades during the loop: stopped by default (plain border following). */
    var blades by mutableStateOf(false)
    var followed by mutableStateOf<BorderMission?>(null)
    val samples = mutableStateListOf<BorderSample>()
}

/** Another debug mowing starts: the measurement of the last border loop does not apply to it. */
internal fun forgetBorderFollow() { BorderDraft.followed = null; BorderDraft.samples.clear() }

private fun cm(meters: Double) = "${(meters * 100).roundToInt()} cm"

/** Distance from a point to the planned loops (closed polylines). */
private fun distanceToLoops(p: Point, loops: List<List<Point>>): Double =
    loops.minOfOrNull { loop -> loop.zipWithNext().minOfOrNull { (a, b) -> Geometry.distance(p, a, b) } ?: Double.MAX_VALUE } ?: Double.MAX_VALUE

/**
 * Service → Border following: a single outline (zone boundary or no-go perimeter) mowed at a chosen
 * offset from 0 to 100 cm, to check on the ground the distance the robot actually keeps. Blades stopped by
 * default (plain following) or running on the loop; link from the robot and return to base always with the
 * blades stopped, like a mowing.
 */
@Composable
internal fun BorderDebugPage(robot: RobotBle, state: RobotUi, back: () -> Unit, header: @Composable ColumnScope.() -> Unit) {
    val map = state.robotMap
    val context = LocalContext.current
    val store = remember(map) { BaseStore(context, map) }
    val base = remember(map) { store.resolve(map) }
    val accesses = remember(map) { store.accesses() }
    val choices = remember(map) { BorderFollow.choices(map) }
    val selected = choices.firstOrNull { it.shape.id == BorderDraft.shapeId }
    val validationStore = remember { BorderValidationStore(context) }
    var validations by remember { mutableStateOf(validationStore.read()) }
    // Choosing a validated border restores the offset saved with its validation.
    fun select(choice: BorderChoice) {
        BorderDraft.shapeId = choice.shape.id
        BorderValidation.validFor(choice.shape, validations)?.let { BorderDraft.offsetM = it.offsetM.toFloat() }
    }
    fun validate(m: BorderMission) {
        val border = BorderDraft.samples.map { it.toBorderM }.sorted()
        validations = validationStore.save(BorderValidationEntry(m.choice.shape.id, m.requestedOffsetM, m.appliedOffsetM,
            System.currentTimeMillis(), BorderValidation.fingerprint(m.choice.shape), border.firstOrNull(), border.getOrNull(border.size / 2)))
    }
    val offset = BorderDraft.offsetM
    var height by rememberSaveable { mutableIntStateOf(state.telemetry.bladeHeightMm ?: state.cutHeights.defaultMm) }
    // Range of the connected model (higher on the H versions): a height chosen for another robot is brought back into it.
    LaunchedEffect(state.cutHeights) { height = state.cutHeights.clamp(height) }
    var supervised by rememberSaveable { mutableStateOf(false) }
    var mission by remember { mutableStateOf<BorderMission?>(null) }
    var computing by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var pendingTap by remember { mutableStateOf<Point?>(null) }
    val scope = rememberCoroutineScope()
    val route = state.robotRoute
    val routeStarted = route.started && route.running
    val t = state.telemetry

    LaunchedEffect(map, selected?.shape?.id, offset, state.obstacles, base) { mission = null; error = "" }

    // Field measurement: only on the loop (within 35 cm of the planned route), not during links.
    LaunchedEffect(t.x, t.y, route.running) {
        val followed = BorderDraft.followed ?: return@LaunchedEffect
        val x = t.x ?: return@LaunchedEffect
        val y = t.y ?: return@LaunchedEffect
        if (!route.running) return@LaunchedEffect
        val p = Point(x, y)
        val toPlanned = distanceToLoops(p, followed.loops)
        if (toPlanned <= 0.35) BorderDraft.samples.add(BorderSample(BorderFollow.clearance(followed.choice, p), toPlanned))
    }

    val idle = state.ready && !route.running && !state.manualActive && !state.mowing.active && !state.navTest.running &&
        !state.teach.running && !state.campaign.running && !state.bladeTestActive && !state.mapLoading && !state.bladeHeightPending &&
        !state.heightSequence.running

    fun compute() {
        val b = base ?: return
        val choice = selected ?: return
        // Start closest to the robot; from the base if it is charging or its position is unknown.
        val start = if (t.x != null && t.y != null && t.chargeState !in setOf(1, 2)) Point(t.x, t.y) else b.point
        val inputs = choice.shape.id to offset
        computing = true; error = ""; mission = null; stage = tr("Preparing", "Préparation")
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.Default) {
                    val coroutine = currentCoroutineContext()
                    BorderFollow.prepare(map, choice.shape.id, offset.toDouble(), start, b, accesses, state.obstacles, 0.3,
                        checkpoint = { coroutine.ensureActive() }, progress = { scope.launch { stage = it } })
                }
                if (inputs == (BorderDraft.shapeId to BorderDraft.offsetM) && map == robot.state.value.robotMap) {
                    mission = prepared
                    robot.recordMapPreview(prepared.preview)
                } else error = tr("Border or offset changed during the computation: compute again", "Bordure ou retrait modifiés pendant le calcul : recalculez")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: tr("Computation impossible", "Calcul impossible") }
            finally { computing = false }
        }
    }

    // One finger: tap to pick the nearest border; two fingers: pan and zoom.
    val picker = remember(map) {
        MapPaintInput(begin = { pendingTap = it }, extend = {}, finish = { committed ->
            pendingTap?.takeIf { committed }?.let { p -> BorderFollow.nearest(map, p)?.let { select(it) } }
            pendingTap = null
        })
    }
    val locked = routeStarted || computing

    PushedPage(ServicePage.BORDER.title, back, bottomBar = {
        MowDebugActions(robot, state, mission?.engine, height, supervised, idle, computing, base != null && selected != null && idle, ::compute,
            onStart = { BorderDraft.followed = mission; BorderDraft.samples.clear() }, blades = BorderDraft.blades)
    }) {
        header()
        SafetyNotice(if (BorderDraft.blades) tr("Real mowing, blades running, along the chosen border. Clear area (toys, cables, stones), no person or animal, physical stop within reach.",
                "Vraie tonte, lames en marche, le long de la bordure choisie. Zone dégagée (jouets, câbles, cailloux), personne ni animal, arrêt physique accessible.")
            else tr("The robot follows the chosen border, blades stopped. Clear area, no person or animal, physical stop within reach.",
                "Le robot suit la bordure choisie, lames arrêtées. Zone dégagée, personne ni animal, arrêt physique accessible."))
        BorderDebugMap(state, choices, validations, selected, mission, BorderDraft.followed.takeIf { route.started }, picker.takeIf { !locked })
        BorderChoiceGroup(choices, validations, selected, locked, ::select,
            onReuse = { BorderDraft.offsetM = it.offsetM.toFloat() },
            onForget = { validations = validationStore.clear(it) })
        // Nothing to track: no empty card.
        if (base == null || computing || error.isNotBlank() || mission != null || BorderDraft.followed != null || route.started) SettingsGroup(tr("Tracking", "Suivi")) {
            if (base == null) StatusNote(tr("Base unknown or without a docking axis: leave the robot charging so it can be observed, or read the map again.",
                "Base inconnue ou sans axe d'accostage : laissez le robot en charge pour l'observer, ou relisez la carte."), NoteTone.WARNING)
            if (computing) ProgressNote(tr("Computing the loop · $stage", "Calcul du tour · $stage"))
            if (error.isNotBlank()) StatusNote(error, NoteTone.DANGER)
            mission?.let { BorderMissionSummary(it) }
            BorderMeasure(BorderDraft.followed, BorderDraft.samples, route.running)
            BorderDraft.followed?.takeIf { !route.running }?.let { followed ->
                val current = choices.firstOrNull { it.shape.id == followed.choice.shape.id }
                val entry = current?.let { BorderValidation.validFor(it.shape, validations) }
                val already = entry != null && kotlin.math.abs(entry.offsetM - followed.requestedOffsetM) < 0.005
                if (current == null) StatusNote(tr("Border missing from the current map: validation impossible.", "Bordure absente de la carte actuelle : validation impossible."), NoteTone.WARNING)
                else if (BorderValidation.fingerprint(current.shape) != BorderValidation.fingerprint(followed.choice.shape))
                    StatusNote(tr("Outline changed since this loop: run the loop again before validating it.", "Contour modifié depuis ce tour : refaites le tour avant de le valider."), NoteTone.WARNING)
                else if (already) StatusNote(tr("Border validated at ${cm(followed.requestedOffsetM)}.", "Bordure validée à ${cm(followed.requestedOffsetM)}."), NoteTone.SUCCESS)
                else SecondaryPillButton(tr("Validate this border at ${cm(followed.requestedOffsetM)}", "Valider cette bordure à ${cm(followed.requestedOffsetM)}"), { validate(followed) },
                    Modifier.fillMaxWidth(), icon = Icons.Rounded.Verified)
            }
            MowDebugRouteStatus(state)
        }
        BorderDebugSettings(robot, state, selected, height, { height = it }, supervised, { supervised = it }, locked)
    }
}

@Composable
private fun BorderDebugMap(state: RobotUi, choices: List<BorderChoice>, validations: Map<Long, BorderValidationEntry>,
                           selected: BorderChoice?, mission: BorderMission?, followed: BorderMission?, picker: MapPaintInput?) {
    val t = state.telemetry
    val route = state.robotRoute
    var cameraReset by remember { mutableIntStateOf(0) }
    val shown = followed ?: mission
    val colors = MammotionColors
    // Chosen border: wide orange halo under its status line; then each border in green (validated) or red (to validate).
    val overlays = listOfNotNull(selected?.let { MapOverlay(it.shape.points, colors.Warning, widthDp = 8f, closed = true) }) +
        choices.map { choice ->
            val validated = BorderValidation.status(choice.shape, validations) == BorderValidationStatus.VALIDATED
            MapOverlay(choice.shape.points, if (validated) colors.Mowed else colors.Danger, widthDp = if (validated) 3f else 2f, closed = true)
        }
    SettingsGroup(tr("Map", "Carte")) {
        Box(Modifier.fillMaxWidth().height(420.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground)) {
            MapLive(map = state.robotMap, x = t.x, y = t.y, heading = t.heading,
                trail = if (route.started) route.trail else emptyList(),
                planned = if (route.started) route.planned else mission?.engine?.edges?.filterNot { it.cutting }?.let { e -> e.map { it.a } + listOfNotNull(e.lastOrNull()?.b) }.orEmpty(),
                cuttingPasses = shown?.preview?.cuttingPaths.orEmpty(),
                obstacles = state.obstacles, interactive = true, cameraReset = cameraReset,
                onPaint = picker,
                overlays = overlays,
                modifier = Modifier.fillMaxSize())
            MapSideButton(Icons.Rounded.CenterFocusStrong, tr("Recenter", "Recentrer"), { cameraReset++ }, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        BorderLegend()
        InfoNote(tr("Tap a zone boundary or a no-go zone to choose it; two fingers pan and zoom.", "Touchez une limite de zone ou un no-go pour le choisir ; deux doigts déplacent et zooment."))
        if (t.x == null || t.y == null) InfoNote(tr("Robot position unavailable: the route will start from the base.", "Position du robot indisponible : le trajet partira de la base."))
        else if (t.chargeState in setOf(1, 2)) InfoNote(tr("Robot charging: straight exit from the base, then a link; recharges first if the battery is too low.",
            "Robot en charge : sortie de base en ligne droite, puis raccord ; recharge d'abord si la batterie est trop basse."))
        if (!state.robotMap.complete) InfoNote(tr("Incomplete map: read the robot map before choosing a border.", "Carte incomplète : lisez la carte du robot avant de choisir une bordure."))
    }
}

@Composable
private fun BorderLegend() {
    val colors = MammotionColors
    @Composable
    fun item(color: androidx.compose.ui.graphics.Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 16.dp, height = 4.dp).clip(MaterialTheme.shapes.extraSmall).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        item(colors.Mowed, tr("Validated", "Validée"))
        item(colors.Danger, tr("To validate", "À valider"))
        item(colors.Warning, tr("Chosen", "Choisie"))
    }
}

/** Short label of the validation state, for the list and the summary. */
private fun validationText(status: BorderValidationStatus, entry: BorderValidationEntry?): String = when (status) {
    BorderValidationStatus.VALIDATED -> tr("Validated · ${cm(entry!!.offsetM)}", "Validée · ${cm(entry!!.offsetM)}")
    BorderValidationStatus.CHANGED -> tr("Outline changed · to validate again", "Contour modifié · à revalider")
    BorderValidationStatus.NOT_VALIDATED -> tr("To validate", "À valider")
}

@Composable
private fun BorderChoiceGroup(choices: List<BorderChoice>, validations: Map<Long, BorderValidationEntry>, selected: BorderChoice?, locked: Boolean,
                              onSelect: (BorderChoice) -> Unit, onReuse: (BorderValidationEntry) -> Unit, onForget: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val colors = MammotionColors
    SettingsGroup(tr("Followed border", "Bordure suivie")) {
        KeyValueRow(tr("Validated borders", "Bordures validées"), "${BorderValidation.validatedCount(choices, validations)} / ${choices.size}")
        KeyValueRow(tr("Choice", "Choix"), selected?.label ?: "—")
        selected?.let { choice ->
            val status = BorderValidation.status(choice.shape, validations)
            val entry = validations[choice.shape.id]
            KeyValueRow("Validation", validationText(status, entry), valueColor = when (status) {
                BorderValidationStatus.VALIDATED -> colors.Mowed
                BorderValidationStatus.CHANGED -> colors.Warning
                BorderValidationStatus.NOT_VALIDATED -> colors.Danger
            })
            if (entry != null) {
                KeyValueRow(tr("Validated on", "Validée le"), Format.dateTime(entry.validatedAtMs))
                KeyValueRow(tr("Chosen · applied offset", "Écart choisi · appliqué"), "${cm(entry.offsetM)} · ${cm(entry.appliedOffsetM)}")
                if (entry.measuredMinM != null && entry.measuredMedianM != null)
                    KeyValueRow(tr("Measured offset · min · median", "Écart mesuré · min · médian"), "${cm(entry.measuredMinM)} · ${cm(entry.measuredMedianM)}")
                if (status == BorderValidationStatus.VALIDATED && kotlin.math.abs(BorderDraft.offsetM - entry.offsetM) > 0.005)
                    SecondaryPillButton(tr("Reuse the validated offset (${cm(entry.offsetM)})", "Reprendre l'écart validé (${cm(entry.offsetM)})"), { onReuse(entry) }, Modifier.fillMaxWidth(),
                        enabled = !locked, icon = Icons.Rounded.Restore)
                SecondaryPillButton(tr("Remove the validation", "Retirer la validation"), { onForget(choice.shape.id) }, Modifier.fillMaxWidth(),
                    enabled = !locked, icon = Icons.Rounded.Close)
            }
        }
        Box {
            SecondaryPillButton(if (selected == null) tr("Choose from the list", "Choisir dans la liste") else tr("Change border", "Changer de bordure"), { open = true }, Modifier.fillMaxWidth(),
                enabled = !locked && choices.isNotEmpty(), icon = Icons.Rounded.Route)
            DropdownMenu(open, { open = false }) {
                choices.forEach { choice ->
                    val status = BorderValidation.status(choice.shape, validations)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(choice.label)
                                Text(validationText(status, validations[choice.shape.id]), style = MaterialTheme.typography.bodySmall,
                                    color = if (status == BorderValidationStatus.VALIDATED) colors.Mowed else colors.TextSecondary)
                            }
                        },
                        onClick = { onSelect(choice); open = false },
                        leadingIcon = { Icon(if (choice.noGo) Icons.Rounded.Block else Icons.Rounded.CropFree, contentDescription = null) },
                        trailingIcon = if (status == BorderValidationStatus.VALIDATED) {
                            { Icon(Icons.Rounded.Verified, contentDescription = tr("Validated", "Validée"), tint = colors.Mowed) }
                        } else null)
                }
            }
        }
        InfoNote(tr("Zone: inner loop at the chosen offset. No-go zone: outer loop. Other no-go zones, learned obstacles and the base surroundings are bypassed.",
            "Zone : tour intérieur au retrait choisi. No-go : tour extérieur. Autres no-go, obstacles appris et abords de la base contournés."))
        InfoNote(tr("After a conclusive loop, \"Validate\" keeps the border and its offset (kept between two launches); choosing a validated border restores that offset. A redrawn outline must be validated again.",
            "Après un tour concluant, « Valider » retient la bordure et son écart (conservés entre deux lancements) ; choisir une bordure validée reprend cet écart. Un contour retracé est à revalider."))
    }
}

@Composable
private fun BorderDebugSettings(robot: RobotBle, state: RobotUi, selected: BorderChoice?, height: Int, onHeight: (Int) -> Unit,
                                supervised: Boolean, onSupervised: (Boolean) -> Unit, locked: Boolean) {
    val offset = BorderDraft.offsetM
    val outer by OuterOffsetSetting.flow.collectAsState()
    SettingsGroup(tr("Loop settings", "Réglages du tour")) {
        SliderRow(tr("Offset between the robot and the outline", "Écart entre le robot et le contour"), offset, { BorderDraft.offsetM = (it * 100).roundToInt() / 100f },
            0f..BorderFollow.MAX_OFFSET_M.toFloat(), valueLabel = cm(offset.toDouble()),
            steps = (BorderFollow.MAX_OFFSET_M * 100).toInt() - 1, enabled = !locked)
        if (offset < BorderFollow.MIN_APPLIED_M) InfoNote(tr("0 cm: the side of the robot passes ${cm(BorderFollow.MIN_APPLIED_M)} from the outline to stay inside the zone.",
            "0 cm : le côté du robot passe à ${cm(BorderFollow.MIN_APPLIED_M)} du contour pour rester dans la zone."))
        ToggleRow(tr("Blades running", "Lames en marche"), BorderDraft.blades, { BorderDraft.blades = it }, enabled = !locked,
            subtitle = if (BorderDraft.blades) tr("The loop mows the border; links and return with the blades stopped", "Le tour tond la bordure ; raccords et retour lames arrêtées")
                else tr("Following only, blades stopped along the whole route", "Suivi seul, lames arrêtées sur tout le trajet"))
        if (BorderDraft.blades) SliderRow(tr("Cutting height", "Hauteur de coupe"), height.toFloat(), { onHeight(it.toInt()) }, state.cutHeights.floatRange(),
            valueLabel = "$height mm", steps = state.cutHeights.sliderSteps, enabled = !locked)
        SliderRow(tr("Travel speed", "Vitesse d'avance"), state.trajectorySpeedPercent.toFloat(), { robot.setTrajectorySpeed(it.toInt()) }, 10f..100f,
            valueLabel = "${state.trajectorySpeedPercent} %", enabled = !locked)
        LubaCheckRow(tr("I am supervising on site, area cleared", "Je supervise sur place, zone dégagée"), supervised, onSupervised, enabled = !state.robotRoute.running)
    }
    SettingsGroup(tr("Outer loop of the mowing", "Tour extérieur de la tonte")) {
        KeyValueRow(tr("Current offset", "Écart actuel"), cm(outer))
        if (selected != null && !selected.noGo) SecondaryPillButton(tr("Use ${cm(offset.toDouble())} for mowing", "Utiliser ${cm(offset.toDouble())} pour la tonte"),
            { OuterOffsetSetting.set(offset.toDouble()) }, Modifier.fillMaxWidth(),
            enabled = !locked && offset >= OuterOffsetSetting.MIN_M && kotlin.math.abs(outer - offset) > 0.005, icon = Icons.Rounded.Check)
        InfoNote(tr("Mowing uses the offset chosen here (5 cm minimum).", "La tonte reprend l'écart choisi ici (5 cm minimum)."))
    }
    InfoNote(tr("The recorded outline is the outer side of the robot: the offset is measured from the side of the chassis, its center passes " +
        "${cm(RecordedBoundary.centerInsetM(fr.luba.poc.obstacles.RobotEnvelope.LUBA2))} further in. Corner too tight to pivot: " +
        "rounded corners, then the offset is increased in 2 cm steps (at most +${cm(BorderFollow.MAX_EXTRA_M)}).",
        "Le contour enregistré est le côté extérieur du robot : l'écart se mesure depuis le côté du châssis, son centre passe " +
        "${cm(RecordedBoundary.centerInsetM(fr.luba.poc.obstacles.RobotEnvelope.LUBA2))} plus loin. Angle trop serré pour pivoter : " +
        "coins arrondis puis retrait augmenté par pas de 2 cm (au plus +${cm(BorderFollow.MAX_EXTRA_M)})."))
}

@Composable
private fun BorderMissionSummary(m: BorderMission) {
    KeyValueRow(tr("Border", "Bordure"), m.choice.label)
    KeyValueRow(tr("Requested · applied offset", "Retrait demandé · appliqué"), "${cm(m.requestedOffsetM)} · ${cm(m.appliedOffsetM)}",
        valueColor = if (m.appliedOffsetM > m.requestedOffsetM.coerceAtLeast(BorderFollow.MIN_APPLIED_M) + 0.005) MammotionColors.Warning else MammotionColors.TextPrimary)
    if (m.roundingM > 0) KeyValueRow(tr("Rounded corners", "Coins arrondis"), tr("radius ${cm(m.roundingM)}", "rayon ${cm(m.roundingM)}"))
    KeyValueRow(tr("Planned offset from the outline", "Écart prévu au contour"), tr("min ${cm(m.minDistanceM)} · median ${cm(m.medianDistanceM)}", "min ${cm(m.minDistanceM)} · médian ${cm(m.medianDistanceM)}"))
    KeyValueRow(tr("Loop · links", "Tour · liaisons"), "${Format.num(m.meters, 0)} m · ${Format.num(m.engine.edges.filterNot { it.cutting }.sumOf { it.meters }, 0)} m" +
        if (m.loops.size > 1) tr(" · ${m.loops.size} loops", " · ${m.loops.size} boucles") else "")
}

/** Offset actually kept by the robot on the loop, from its measured position. */
@Composable
private fun BorderMeasure(followed: BorderMission?, samples: List<BorderSample>, running: Boolean) {
    if (followed == null) return
    Text(tr("Field measurement · ${followed.choice.label} · planned ${cm(followed.appliedOffsetM)}", "Mesure terrain · ${followed.choice.label} · prévu ${cm(followed.appliedOffsetM)}"), style = MaterialTheme.typography.titleSmall)
    if (samples.isEmpty()) {
        InfoNote(if (running) tr("Waiting for the robot on the loop…", "En attente du robot sur le tour…") else tr("No position measured on the loop.", "Aucune position mesurée sur le tour."))
        return
    }
    val border = samples.map { it.toBorderM }.sorted()
    val planned = samples.map { it.toPlannedM }.sorted()
    KeyValueRow(tr("Offset from the outline · current", "Écart au contour · actuel"), cm(samples.last().toBorderM))
    KeyValueRow(tr("Offset from the outline · min · median", "Écart au contour · min · médian"), "${cm(border.first())} · ${cm(border[border.size / 2])}",
        valueColor = if (border.first() < followed.appliedOffsetM - 0.1) MammotionColors.Danger else MammotionColors.TextPrimary)
    KeyValueRow(tr("Deviation from the planned route · median · max", "Écart au trajet prévu · médian · max"), "${cm(planned[planned.size / 2])} · ${cm(planned.last())}")
    KeyValueRow(tr("Measurements", "Mesures"), "${samples.size}")
}
