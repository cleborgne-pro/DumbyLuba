package fr.luba.poc.manual

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.DisplayPose
import fr.luba.poc.map.DrivenKind
import fr.luba.poc.map.DrivenOutcome
import fr.luba.poc.map.DrivenRecording
import fr.luba.poc.map.DrivenTrace
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.drawLawn
import fr.luba.poc.map.drawLuba
import fr.luba.poc.map.rememberSmoothedPose
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.LubaTextField
import fr.luba.poc.ui.components.MapSideButton
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.hypot

/** Choices of the page that are not part of the recording itself (kept by [RobotBle.driven]). */
@Stable
private class DrivenRecordChoices {
    var kind by mutableStateOf(DrivenKind.ZONE)
    var outcome by mutableStateOf<DrivenOutcome?>(null)
    var enlarge by mutableStateOf(false)
    var name by mutableStateOf("")
    var saved by mutableStateOf<String?>(null)
    var computing by mutableStateOf(false)
    var confirmCancel by mutableStateOf(false)
}

/**
 * "Record by driving" page: the map fills the screen, zoomed in and centred on the robot (active following, heading up
 * on demand), the live camera at the top and the joysticks at the bottom, so that a zone, a passage or a no-go zone is
 * driven like in the Mammotion app. Laid out for a folded Fold (412 × 915 dp). The result is a local correction:
 * nothing is written to the robot. Leaving the page stops the robot but keeps the recording in progress.
 */
@Composable
fun DrivenRecordPage(robot: RobotBle, state: RobotUi, back: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = back)
    val colors = MammotionColors
    val density = LocalDensity.current
    val drive = rememberManualDrive(robot, state)
    val choices = remember { DrivenRecordChoices() }
    val view = remember { DrivenMapView() }
    val trace = state.driven
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    // A new trace (or the end of the recording) closes the result being reviewed.
    LaunchedEffect(trace?.startedAtMs) { if (trace == null) choices.outcome = null }
    LaunchedEffect(choices.saved) { if (choices.saved != null) { delay(8000); choices.saved = null } }
    val reviewed = choices.outcome.takeIf { trace != null }
    val robotPoint = state.telemetry.x?.let { x -> state.telemetry.y?.let { y -> Point(x, y) } }?.takeIf { it.x.isFinite() && it.y.isFinite() }
    val pose = robotPoint?.let { DisplayPose.published(it, state.telemetry.heading?.takeIf(Double::isFinite)) }

    Box(modifier.fillMaxSize().background(colors.MapBackground)) {
        DrivenFollowMap(state.robotMap, trace, reviewed, choices.enlarge, pose, view, topPx.toFloat(), bottomPx.toFloat(), Modifier.fillMaxSize())

        // Visible part of the map, between the camera and the joysticks: notes, map buttons, scale.
        val topDp = with(density) { topPx.toDp() }
        val bottomDp = with(density) { bottomPx.toDp() }
        Box(Modifier.fillMaxSize().padding(top = topDp + 8.dp, bottom = bottomDp + 8.dp, start = 8.dp, end = 8.dp)) {
            Column(Modifier.align(Alignment.TopStart).padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mapNotes(state, trace, choices, robotPoint != null).forEach { (text, tone) -> MapNote(text, tone) }
            }
            Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MapSideButton(Icons.Rounded.MyLocation, if (view.follow) tr("Following the robot", "Suivi du robot") else tr("Follow the robot", "Suivre le robot"),
                    { view.recenter() }, tint = if (view.follow) colors.Green else colors.TextPrimary)
                MapSideButton(if (view.headingUp) Icons.Rounded.Navigation else Icons.Rounded.Explore,
                    if (view.headingUp) tr("Heading up: show north up", "Cap en haut : afficher le nord en haut") else tr("North up: show heading up", "Nord en haut : afficher le cap en haut"),
                    { view.headingUp = !view.headingUp }, tint = if (view.headingUp) colors.Green else colors.TextPrimary)
                ZoomPill(view)
            }
            Column(Modifier.align(Alignment.BottomStart).padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (trace == null && reviewed == null) MapNote(kindHint(choices.kind), NoteTone.INFO, compact = false)
                ScaleBar(view.dpPerMeter.takeIf { reviewed == null })
            }
        }

        // Top: header and live camera.
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }.testTag("driven-top"),
            color = colors.Background, shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), shadowElevation = 4.dp) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", "Retour")) }
                    Column(Modifier.weight(1f)) {
                        Text(tr("Record by driving", "Enregistrer en pilotant"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("Kept on this phone only · blades stopped", "Gardé sur ce téléphone uniquement · lames arrêtées"),
                            style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (trace != null) RecordingBadge(trace)
                    if (trace != null) IconButton(onClick = { choices.confirmCancel = true }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = tr("Cancel the recording", "Annuler l'enregistrement"), tint = colors.Danger)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.TopCenter) {
                    fr.luba.poc.camera.LiveCameraView(Modifier.widthIn(max = 560.dp), large = true)
                }
            }
        }

        // Bottom: recording bar and joysticks, or the result to review.
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }.testTag("driven-deck"),
            color = colors.Surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 8.dp) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (reviewed != null && trace != null) ReviewPanel(robot, state, reviewed, choices)
                else {
                    RecordBar(robot, state, trace, choices)
                    Box {
                        JoystickDeck(drive, state.manualActive, padHeight = 150.dp)
                        if (!drive.confirmed) SupervisionGate(Modifier.matchParentSize()) { drive.confirmed = true }
                    }
                }
            }
        }
    }

    if (choices.confirmCancel) LubaDialog(onDismissRequest = { choices.confirmCancel = false }, title = tr("Cancel the recording?", "Annuler l'enregistrement ?"), content = {
        Text(tr("The trace driven so far is deleted; nothing has been saved.", "La trace parcourue jusqu'ici est supprimée ; rien n'a été enregistré."),
            style = MaterialTheme.typography.bodyMedium)
    }, footer = {
        PrimaryPillButton(tr("Cancel the recording", "Annuler l'enregistrement"), {
            choices.confirmCancel = false; choices.outcome = null; robot.cancelDrivenRecording()
        }, Modifier.fillMaxWidth())
        TextAction(tr("Keep recording", "Continuer l'enregistrement"), { choices.confirmCancel = false }, Modifier.fillMaxWidth())
    })
}

/** Reason why a recording cannot start, null if it can. */
private fun startRefusal(state: RobotUi): String? = when {
    !state.ready -> tr("Connect the robot first.", "Connectez d'abord le robot.")
    !state.mammotionMap.complete -> tr("Read the robot map first: the new shape is placed on it.", "Lisez d'abord la carte du robot : la nouvelle forme s'y place.")
    state.robotRoute.running || state.mowing.active -> tr("Stop the current route first.", "Arrêtez d'abord le trajet en cours.")
    else -> null
}

private fun kindHint(kind: DrivenKind): String = when (kind) {
    DrivenKind.ZONE -> tr("Drive the chassis edge along the outline of the new zone, then back to the start. It becomes a DumbyLuba zone, mowed like the others.",
        "Longez le contour de la nouvelle zone avec le bord du châssis, puis revenez au départ. Elle devient une zone DumbyLuba, tondue comme les autres.")
    DrivenKind.PASSAGE -> tr("Drive from one zone to the other along the path to use (blades stopped). Start and finish inside the zones it connects.",
        "Conduisez d'une zone à l'autre par le chemin à emprunter (lames arrêtées). Commencez et terminez dans les zones qu'il relie.")
    DrivenKind.NO_GO -> tr("Drive around the obstacle without touching it, then back to the start: the enclosed area, minus half a chassis width, becomes a no-go zone.",
        "Faites le tour de l'obstacle sans le toucher, puis revenez au départ : la surface entourée, moins une demi-largeur de châssis, devient une no-go.")
}

/** Short notes shown at the top of the map: refusal, saved result, RTK, pause, closing of the outline, control refusal. */
private fun mapNotes(state: RobotUi, trace: DrivenTrace?, choices: DrivenRecordChoices, robotKnown: Boolean): List<Pair<String, NoteTone>> = buildList {
    choices.saved?.let { add(it to NoteTone.SUCCESS) }
    if (trace == null) startRefusal(state)?.let { add(it to NoteTone.WARNING) }
    if (!robotKnown && state.ready) add(tr("Robot position unknown", "Position du robot inconnue") to NoteTone.WARNING)
    if (trace != null && choices.outcome == null) when {
        !state.telemetry.rtkFix -> add(tr("No RTK FIX: positions are not recorded until it comes back (straight join).",
            "Pas de RTK FIX : les positions ne sont pas enregistrées tant qu'il ne revient pas (raccord en ligne droite).") to NoteTone.WARNING)
        trace.paused -> add(tr("Paused: move the robot freely, the trace resumes in a straight line from its last point.",
            "En pause : déplacez librement le robot, la trace reprendra en ligne droite depuis son dernier point.") to NoteTone.INFO)
        trace.closable -> add(tr("Outline closed: you can finish.", "Contour fermé : vous pouvez terminer.") to NoteTone.SUCCESS)
        trace.kind.closed -> add(DrivenRecording.closeHint(trace) to NoteTone.INFO)
    }
    val refused = tr("Control refused", "Pilotage refusé")
    if (!state.manualActive && state.manualStatus.startsWith(refused)) add(state.manualStatus to NoteTone.DANGER)
}

/** Note floating over the map: coloured pill, readable on the lawn. */
@Composable
private fun MapNote(text: String, tone: NoteTone, compact: Boolean = true) {
    val colors = MammotionColors
    val (bg, fg) = when (tone) {
        NoteTone.INFO -> colors.Surface.copy(alpha = 0.94f) to colors.TextPrimary
        NoteTone.SUCCESS -> colors.GreenSoft to colors.GreenDeep
        NoteTone.WARNING -> colors.WarningSoft to colors.TextPrimary
        NoteTone.DANGER -> colors.DangerSoft to colors.Danger
    }
    Surface(shape = RoundedCornerShape(12.dp), color = bg, shadowElevation = 2.dp) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = fg,
            maxLines = if (compact) 3 else 4, overflow = TextOverflow.Ellipsis)
    }
}

/** Red "REC" badge (grey when paused) with the recorded length. */
@Composable
private fun RecordingBadge(trace: DrivenTrace) {
    val colors = MammotionColors
    val tint = if (trace.paused) colors.TextSecondary else colors.Danger
    Surface(shape = CircleShape, color = if (trace.paused) colors.SurfaceMuted else colors.DangerSoft) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(if (trace.paused) Icons.Rounded.Pause else Icons.Rounded.FiberManualRecord, null, Modifier.size(12.dp), tint = tint)
            Text("${MapCorrections.meters(trace.lengthM)} m", style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Zoom buttons of the map, in one vertical pill. */
@Composable
private fun ZoomPill(view: DrivenMapView) {
    val colors = MammotionColors
    Surface(shape = RoundedCornerShape(22.dp), color = colors.Overlay, shadowElevation = 3.dp, modifier = Modifier.width(44.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { view.zoom(1.5f) }, enabled = view.dpPerMeter < DrivenView.MAX_DP_PER_M) { Icon(Icons.Rounded.Add, tr("Zoom in", "Zoomer")) }
            IconButton(onClick = { view.zoom(1f / 1.5f) }, enabled = view.dpPerMeter > DrivenView.MIN_DP_PER_M) { Icon(Icons.Rounded.Remove, tr("Zoom out", "Dézoomer")) }
        }
    }
}

/** Scale bar of the map (hidden when the result is framed). */
@Composable
private fun ScaleBar(dpPerMeter: Float?) {
    dpPerMeter ?: return
    val colors = MammotionColors
    val meters = DrivenView.scaleBarMeters(dpPerMeter, 96f)
    val label = if (meters < 1) "${(meters * 100).toInt()} cm" else "${meters.toInt()} m"
    Surface(shape = RoundedCornerShape(8.dp), color = colors.Surface.copy(alpha = 0.85f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.TextPrimary)
            Box(Modifier.width((meters * dpPerMeter).dp).height(3.dp).background(colors.TextPrimary, RoundedCornerShape(2.dp)))
        }
    }
}

/** Before recording: kind and start; while recording: summary, pause, erase and finish. */
@Composable
private fun RecordBar(robot: RobotBle, state: RobotUi, trace: DrivenTrace?, choices: DrivenRecordChoices) {
    val colors = MammotionColors
    val scope = rememberCoroutineScope()
    if (trace == null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(DrivenKind.entries.map { it to it.label }, choices.kind, { choices.kind = it }, Modifier.weight(1f))
            PrimaryPillButton(tr("Start", "Démarrer"), {
                choices.saved = null; choices.outcome = null; choices.enlarge = false; choices.name = ""
                robot.startDrivenRecording(choices.kind)
            }, enabled = startRefusal(state) == null, icon = Icons.Rounded.FiberManualRecord)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("${trace.kind.label} · ${trace.points.size} points", "${trace.kind.label} · ${trace.points.size} points"),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val closing = trace.closingM
            Text(when {
                trace.paused -> tr("Paused", "En pause")
                trace.kind.closed && closing != null -> tr("Back to the start: ${MapCorrections.meters(closing)} m", "Retour au départ : ${MapCorrections.meters(closing)} m")
                else -> tr("Recording", "Enregistrement")
            }, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                color = if (trace.closable) colors.GreenDeep else colors.TextSecondary)
        }
        RoundAction(if (trace.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            if (trace.paused) tr("Resume", "Reprendre") else tr("Pause", "Pause")) { robot.pauseDrivenRecording(!trace.paused) }
        RoundAction(Icons.AutoMirrored.Rounded.Undo, tr("Erase 1 m", "Effacer 1 m"), enabled = trace.points.isNotEmpty()) { robot.eraseDrivenRecording(1.0) }
        // Geometry computed off the UI thread (up to 20,000 points).
        PrimaryPillButton(if (choices.computing) tr("Computing…", "Calcul…") else tr("Finish", "Terminer"), {
            choices.computing = true
            scope.launch {
                val result = try { withContext(Dispatchers.Default) { robot.drivenOutcome() } } finally { choices.computing = false }
                choices.outcome = result
                choices.enlarge = result is DrivenOutcome.Zone && result.separate == null
            }
        }, enabled = trace.finishable && !choices.computing)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MammotionColors
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = colors.SurfaceMuted,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.Outline), modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp), tint = if (enabled) colors.TextPrimary else colors.TextTertiary)
        }
    }
}

/** Joysticks locked until the user confirms supervising the robot. */
@Composable
private fun SupervisionGate(modifier: Modifier, onConfirm: () -> Unit) {
    val colors = MammotionColors
    Box(modifier.background(colors.Surface.copy(alpha = 0.86f), RoundedCornerShape(16.dp)).clickable(onClick = onConfirm), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = colors.WarningSoft, shadowElevation = 2.dp, modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Lock, null, tint = colors.Warning)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(tr("I am supervising the robot", "Je supervise le robot"), style = MaterialTheme.typography.titleSmall)
                    Text(tr("Blades stopped, area clear, physical stop within reach. Tap to unlock the joysticks.",
                        "Lames arrêtées, zone dégagée, arrêt physique accessible. Touchez pour déverrouiller les joysticks."),
                        style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                }
            }
        }
    }
}

/** Result of a finished recording: choices, figures, warnings, then save or go back. */
@Composable
private fun ReviewPanel(robot: RobotBle, state: RobotUi, reviewed: DrivenOutcome, choices: DrivenRecordChoices) {
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        Text(tr("Result · ${state.driven?.kind?.label ?: ""}", "Résultat · ${state.driven?.kind?.label ?: ""}"), style = MaterialTheme.typography.titleSmall)
        when (reviewed) {
            is DrivenOutcome.Refused -> StatusNote(reviewed.reason, NoteTone.DANGER)
            is DrivenOutcome.Zone -> {
                val options = buildList {
                    if (reviewed.separate != null) add(false to tr("New DumbyLuba zone", "Nouvelle zone DumbyLuba"))
                    reviewed.enlargement?.let { add(true to tr("Enlarge ${it.targetName}", "Agrandir ${it.targetName}")) }
                }
                if (options.size > 1) Segmented(options, choices.enlarge, { choices.enlarge = it })
                if (!choices.enlarge) {
                    LubaTextField(choices.name, { choices.name = it.take(40) }, tr("Zone name", "Nom de la zone"), placeholder = DrivenRecording.defaultZoneName(state.robotMap))
                    KeyValueRow(tr("Area", "Surface"), MapCorrections.squareMeters(reviewed.separateAreaM2))
                    reviewed.separateRefusal?.let { StatusNote(it) }
                    if (!reviewed.connected) StatusNote(tr("This zone is not connected to the others: record a passage from an existing zone, otherwise the robot cannot reach it.",
                        "Cette zone n'est reliée à aucune autre : enregistrez un passage depuis une zone existante, sinon le robot ne pourra pas l'atteindre."), NoteTone.WARNING)
                } else reviewed.enlargement?.let { e ->
                    KeyValueRow(tr("Area added", "Surface ajoutée"), MapCorrections.squareMeters(e.addedM2))
                    reviewed.separateRefusal?.let { StatusNote(it) }
                }
                if (reviewed.beyondM2 >= 0.05) StatusNote(tr("${MapCorrections.squareMeters(reviewed.beyondM2)} beyond the Mammotion boundaries: this surface only exists on this phone (hatched on the map), the robot and the Mammotion app do not know it. Mowed under your responsibility.",
                    "${MapCorrections.squareMeters(reviewed.beyondM2)} hors des contours Mammotion : cette surface n'existe que sur ce téléphone (hachurée sur la carte), le robot et l'app Mammotion ne la connaissent pas. Tondue sous votre responsabilité."), NoteTone.WARNING)
            }
            is DrivenOutcome.Passage -> {
                KeyValueRow(tr("Length", "Longueur"), "${MapCorrections.meters(reviewed.lengthM)} m")
                KeyValueRow(tr("Ends inside a zone", "Extrémités dans une zone"), "${reviewed.endsInZones} / 2")
            }
            is DrivenOutcome.NoGo -> KeyValueRow(tr("Area", "Surface"), MapCorrections.squareMeters(reviewed.areaM2))
        }
        reviewed.warnings.forEach { StatusNote(it, NoteTone.WARNING) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        SecondaryPillButton(tr("Back", "Revenir"), { choices.outcome = null }, Modifier.weight(1f))
        if (reviewed !is DrivenOutcome.Refused) PrimaryPillButton(tr("Save", "Enregistrer"), {
            val correction = DrivenRecording.correction(reviewed, choices.enlarge, choices.name, state.mapCorrections, state.mammotionMap, state.robotMap,
                System.currentTimeMillis())
            if (correction != null && robot.saveDrivenRecording(correction)) {
                choices.saved = tr("Saved: ${correction.label}. It is on the map and in Map → Correct the map → Corrections (disable, delete).",
                    "Enregistré : ${correction.label}. Visible sur la carte et dans Carte → Corriger la carte → Corrections (désactiver, supprimer).")
                choices.outcome = null
            }
        }, Modifier.weight(1f))
    }
}

/**
 * Map in the background of the page: lawn, trace being recorded, start point with its closing circle, reviewed result
 * and robot to scale. The robot stays at the centre of the visible area ([top] and [bottom] covered by the camera and the
 * joysticks), glided between two positions; pinch or the buttons zoom, dragging suspends the following. While a result
 * is reviewed, the view frames it.
 */
@Composable
private fun DrivenFollowMap(map: RobotMap, trace: DrivenTrace?, outcome: DrivenOutcome?, enlarge: Boolean, robot: DisplayPose?,
                            view: DrivenMapView, top: Float, bottom: Float, modifier: Modifier) {
    val colors = MammotionColors
    val pose = rememberSmoothedPose(robot)
    val result: Pair<List<Point>, Boolean>? = when (outcome) {
        is DrivenOutcome.Zone -> (if (enlarge) outcome.enlargement?.after else outcome.separate ?: outcome.outline)?.let { it to true }
        is DrivenOutcome.NoGo -> outcome.points to true
        is DrivenOutcome.Passage -> outcome.points to false
        else -> null
    }
    val framed = outcome != null
    Canvas(modifier.pointerInput(framed) {
        if (framed) return@pointerInput
        detectTransformGestures { _, pan, zoom, _ ->
            view.zoom(zoom)
            if (hypot(pan.x, pan.y) > 0.5f) {
                val from = view.focus ?: view.drawnFocus ?: return@detectTransformGestures
                // The map keeps the orientation it had when the following was suspended.
                if (view.follow) view.pannedRotation = view.drawnRotation
                view.focus = DrivenView.panned(from, pan.x, pan.y, view.dpPerMeter * density, view.drawnRotation)
                view.follow = false
            }
        }
    }) {
        val visibleTop = top.coerceIn(0f, size.height)
        val visibleBottom = (size.height - bottom).coerceIn(visibleTop, size.height)
        val anchor = Offset(size.width / 2f, (visibleTop + visibleBottom) / 2f)
        val fit = if (framed) DrivenView.fit((result?.first.orEmpty()) + trace?.points.orEmpty(), size.width, visibleBottom - visibleTop) else null
        val pxPerM = fit?.second ?: (view.dpPerMeter * density)
        val focus = fit?.first ?: (if (view.follow) pose?.point else null) ?: view.focus ?: pose?.point ?: trace?.points?.lastOrNull()
            ?: map.shapes.flatMap { it.points }.let { all -> if (all.isEmpty()) null else Point(all.sumOf { it.x } / all.size, all.sumOf { it.y } / all.size) }
            ?: return@Canvas
        view.drawnFocus = focus
        // Heading up follows the robot; a panned view keeps the orientation it had (it does not spin under the finger).
        val rotation = when {
            fit != null || !view.headingUp -> 0f
            view.follow -> DrivenView.rotation(true, pose?.heading)
            else -> view.pannedRotation
        }
        view.drawnRotation = rotation
        fun screen(p: Point): Offset = DrivenView.screen(p, focus, anchor.x, anchor.y, pxPerM).let { (x, y) -> Offset(x, y) }
        fun path(points: List<Point>, closed: Boolean) = Path().apply {
            points.forEachIndexed { i, p -> val o = screen(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            if (closed) close()
        }
        val unit = 1.dp.toPx()
        rotate(rotation, anchor) {
            // Metric grid: 1 m when zoomed in, 5 m otherwise, over the whole rotated screen.
            val step = if (pxPerM >= 18f * unit) 1.0 else 5.0
            val half = hypot(size.width, size.height) / pxPerM
            var gx = floor((focus.x - half) / step) * step
            while (gx <= focus.x + half) {
                drawLine(colors.MapGrid.copy(alpha = colors.MapGrid.alpha * 0.45f), screen(Point(gx, focus.y - half)), screen(Point(gx, focus.y + half)), unit * 0.6f); gx += step
            }
            var gy = floor((focus.y - half) / step) * step
            while (gy <= focus.y + half) {
                drawLine(colors.MapGrid.copy(alpha = colors.MapGrid.alpha * 0.45f), screen(Point(focus.x - half, gy)), screen(Point(focus.x + half, gy)), unit * 0.6f); gy += step
            }
            drawLawn(map, { false }, ::path, unit, colors)
            result?.let { (points, closed) ->
                val p = path(points, closed)
                if (closed) drawPath(p, if (outcome is DrivenOutcome.NoGo) colors.NoGoFill else colors.ZoneFillSelected)
                drawPath(p, if (outcome is DrivenOutcome.NoGo) colors.NoGoStroke else colors.GreenDeep, style = Stroke(2.5f * unit,
                    cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit))))
            }
            if (trace != null) {
                trace.points.firstOrNull()?.let { start ->
                    if (trace.kind.closed) {
                        val radius = (DrivenRecording.CLOSE_M * pxPerM).toFloat()
                        drawCircle(colors.Info.copy(alpha = if (trace.closable) 0.30f else 0.16f), radius, screen(start))
                        drawCircle(colors.Info.copy(alpha = 0.7f), radius, screen(start), style = Stroke(1.5f * unit,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * unit, 3f * unit))))
                    }
                }
                if (trace.points.size >= 2) {
                    val line = path(trace.points, false)
                    drawPath(line, Color.White.copy(alpha = 0.85f), style = Stroke(5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(line, colors.Info, style = Stroke(3f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // Live join from the last point to the robot, dashed (not yet recorded).
                val last = trace.points.lastOrNull()
                if (last != null && pose != null && !trace.paused && outcome == null)
                    drawLine(colors.Info.copy(alpha = 0.6f), screen(last), screen(pose.point), 2f * unit,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * unit, 4f * unit)))
                trace.points.firstOrNull()?.let { start ->
                    drawCircle(Color.White, 6f * unit, screen(start))
                    drawCircle(colors.Info, 4.5f * unit, screen(start))
                }
            }
            if (pose != null) {
                val at = screen(pose.point)
                val heading = pose.heading
                if (heading != null) drawLuba(at, pxPerM.toDouble().coerceAtLeast(26.0 * unit), heading)
                else {
                    drawCircle(colors.RobotHalo, 11f * unit, at)
                    drawCircle(colors.RobotMarker, 6f * unit, at)
                }
            }
        }
    }
}
