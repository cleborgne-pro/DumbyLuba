package fr.luba.poc.manual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.DrivenKind
import fr.luba.poc.map.DrivenOutcome
import fr.luba.poc.map.DrivenRecording
import fr.luba.poc.map.DrivenTrace
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.drawLawn
import fr.luba.poc.ui.components.DangerPillButton
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaTextField
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * "Record by driving" group of the manual control page: the robot is driven with the joysticks (and the camera
 * through the gateway) along a new zone, passage or no-go zone, like in the Mammotion app. The result is a local
 * correction (DumbyLuba zone, passage or no-go zone): nothing is written to the robot.
 */
@Composable
fun DrivenRecordGroup(robot: RobotBle, state: RobotUi) {
    val trace = state.driven
    var kind by remember { mutableStateOf(DrivenKind.ZONE) }
    var outcome by remember { mutableStateOf<DrivenOutcome?>(null) }
    var enlarge by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    var computing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A new trace (or the end of the recording) closes the result being reviewed.
    LaunchedEffect(trace?.startedAtMs) { if (trace == null) outcome = null }
    SettingsGroup(tr("Record by driving", "Enregistrer en pilotant")) {
        if (trace == null) {
            saved?.let { StatusNote(it, NoteTone.SUCCESS) }
            Segmented(DrivenKind.entries.map { it to it.label }, kind, { kind = it })
            InfoNote(when (kind) {
                DrivenKind.ZONE -> tr("Drive the chassis edge along the outline of the new zone, then come back to the starting point. The zone is added to the map on this phone (DumbyLuba zone) and mowed like the Mammotion zones; the robot and the Mammotion app do not know it.",
                    "Longez le contour de la nouvelle zone avec le bord du châssis, puis revenez au point de départ. La zone est ajoutée à la carte sur ce téléphone (zone DumbyLuba) et tondue comme les zones Mammotion ; le robot et l'app Mammotion ne la connaissent pas.")
                DrivenKind.PASSAGE -> tr("Drive from one zone to the other along the path to use: the robot will travel along it, blades stopped, like on a Mammotion passage. Start and finish inside the zones it connects.",
                    "Conduisez d'une zone à l'autre par le chemin à emprunter : le robot y circulera lames arrêtées, comme sur un passage Mammotion. Commencez et terminez dans les zones qu'il relie.")
                DrivenKind.NO_GO -> tr("Drive around the obstacle without touching it, then come back to the starting point: the enclosed area, minus half the chassis width, becomes a no-go zone.",
                    "Faites le tour de l'obstacle sans le toucher, puis revenez au point de départ : la surface entourée, moins une demi-largeur de châssis, devient une no-go.")
            })
            val refusal = when {
                !state.ready -> tr("Connect the robot first.", "Connectez d'abord le robot.")
                !state.mammotionMap.complete -> tr("Read the robot map first: the new shape is placed on it.", "Lisez d'abord la carte du robot : la nouvelle forme s'y place.")
                state.robotRoute.running || state.mowing.active -> tr("Stop the current route first.", "Arrêtez d'abord le trajet en cours.")
                else -> null
            }
            refusal?.let { StatusNote(it, NoteTone.WARNING) }
            PrimaryPillButton(tr("Start recording", "Démarrer l'enregistrement"), {
                saved = null; outcome = null; enlarge = false; name = ""
                robot.startDrivenRecording(kind)
            }, Modifier.fillMaxWidth(), enabled = refusal == null)
            return@SettingsGroup
        }
        DrivenMap(state.robotMap, trace, outcome, enlarge, Point(state.telemetry.x ?: Double.NaN, state.telemetry.y ?: Double.NaN),
            Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)).background(MammotionColors.MapBackground))
        val reviewed = outcome
        if (reviewed == null) {
            Text(tr("${trace.kind.label} · recording${if (trace.paused) " paused" else ""}", "${trace.kind.label} · enregistrement${if (trace.paused) " en pause" else ""}"),
                style = MaterialTheme.typography.titleSmall)
            KeyValueRow(tr("Recorded", "Enregistré"), tr("${trace.points.size} points · ${MapCorrections.meters(trace.lengthM)} m",
                "${trace.points.size} points · ${MapCorrections.meters(trace.lengthM)} m"))
            if (trace.kind.closed) KeyValueRow(tr("Back to the start", "Retour au départ"), trace.closingM?.let { "${MapCorrections.meters(it)} m" } ?: "—",
                valueColor = if (trace.closable) MammotionColors.GreenDeep else MammotionColors.TextPrimary)
            when {
                !state.telemetry.rtkFix -> StatusNote(tr("No RTK FIX: positions are not recorded until it comes back (straight join).",
                    "Pas de RTK FIX : les positions ne sont pas enregistrées tant qu'il ne revient pas (raccord en ligne droite)."), NoteTone.WARNING)
                trace.paused -> StatusNote(tr("Paused: move the robot freely, the trace resumes in a straight line from its last point.",
                    "En pause : déplacez librement le robot, la trace reprendra en ligne droite depuis son dernier point."))
                trace.kind.closed && !trace.closable -> InfoNote(DrivenRecording.closeHint(trace))
                trace.closable -> StatusNote(tr("Outline closed: you can finish.", "Contour fermé : vous pouvez terminer."), NoteTone.SUCCESS)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(if (trace.paused) tr("Resume", "Reprendre") else tr("Pause", "Pause"), { robot.pauseDrivenRecording(!trace.paused) }, Modifier.weight(1f))
                SecondaryPillButton(tr("Erase 1 m", "Effacer 1 m"), { robot.eraseDrivenRecording(1.0) }, Modifier.weight(1f), enabled = trace.points.isNotEmpty())
            }
            // Geometry computed off the UI thread (up to 20,000 points).
            PrimaryPillButton(if (computing) tr("Computing…", "Calcul…") else tr("Finish", "Terminer"), {
                computing = true
                scope.launch {
                    val result = try { withContext(Dispatchers.Default) { robot.drivenOutcome() } } finally { computing = false }
                    outcome = result
                    enlarge = result is DrivenOutcome.Zone && result.separate == null
                }
            }, Modifier.fillMaxWidth(), enabled = trace.finishable && !computing)
            TextAction(tr("Cancel the recording", "Annuler l'enregistrement"), { robot.cancelDrivenRecording(); outcome = null }, danger = true)
            return@SettingsGroup
        }
        when (reviewed) {
            is DrivenOutcome.Refused -> StatusNote(reviewed.reason, NoteTone.DANGER)
            is DrivenOutcome.Zone -> {
                val options = buildList {
                    if (reviewed.separate != null) add(false to tr("New DumbyLuba zone", "Nouvelle zone DumbyLuba"))
                    reviewed.enlargement?.let { add(true to tr("Enlarge ${it.targetName}", "Agrandir ${it.targetName}")) }
                }
                if (options.size > 1) Segmented(options, enlarge, { enlarge = it })
                if (!enlarge) {
                    LubaTextField(name, { name = it.take(40) }, tr("Zone name", "Nom de la zone"), placeholder = DrivenRecording.defaultZoneName(state.robotMap))
                    KeyValueRow(tr("Area", "Surface"), MapCorrections.squareMeters(reviewed.separateAreaM2))
                    reviewed.separateRefusal?.let { InfoNote(it) }
                    if (!reviewed.connected) StatusNote(tr("This zone is not connected to the others: record a passage from an existing zone, otherwise the robot cannot reach it.",
                        "Cette zone n'est reliée à aucune autre : enregistrez un passage depuis une zone existante, sinon le robot ne pourra pas l'atteindre."), NoteTone.WARNING)
                } else reviewed.enlargement?.let { e ->
                    KeyValueRow(tr("Area added", "Surface ajoutée"), MapCorrections.squareMeters(e.addedM2))
                    reviewed.separateRefusal?.let { InfoNote(it) }
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
        if (reviewed !is DrivenOutcome.Refused) PrimaryPillButton(tr("Save", "Enregistrer"), {
            val correction = DrivenRecording.correction(reviewed, enlarge, name, state.mapCorrections, state.mammotionMap, state.robotMap,
                System.currentTimeMillis())
            if (correction != null && robot.saveDrivenRecording(correction)) {
                saved = tr("Saved: ${correction.label}. It is on the map and in Map → Correct the map → Corrections (disable, delete).",
                    "Enregistré : ${correction.label}. Visible sur la carte et dans Carte → Corriger la carte → Corrections (désactiver, supprimer).")
                outcome = null
            }
        }, Modifier.fillMaxWidth())
        SecondaryPillButton(tr("Back to the recording", "Revenir à l'enregistrement"), { outcome = null }, Modifier.fillMaxWidth())
        TextAction(tr("Cancel the recording", "Annuler l'enregistrement"), { robot.cancelDrivenRecording(); outcome = null }, danger = true)
    }
}

/** Map around the trace and the robot: lawn, trace being recorded, result reviewed (outline or passage). */
@Composable
private fun DrivenMap(map: RobotMap, trace: DrivenTrace, outcome: DrivenOutcome?, enlarge: Boolean, robot: Point, modifier: Modifier) {
    val colors = MammotionColors
    Canvas(modifier) {
        val robotKnown = robot.x.isFinite() && robot.y.isFinite()
        val focus = trace.points + listOfNotNull(robot.takeIf { robotKnown })
        if (focus.isEmpty() && map.shapes.isEmpty()) return@Canvas
        val frame = focus.ifEmpty { map.shapes.flatMap { it.points } }
        // At least 12 m around the trace, so that the neighbouring zones are visible.
        val cx = (frame.minOf { it.x } + frame.maxOf { it.x }) / 2
        val cy = (frame.minOf { it.y } + frame.maxOf { it.y }) / 2
        val spanM = max(12.0, max(frame.maxOf { it.x } - frame.minOf { it.x }, frame.maxOf { it.y } - frame.minOf { it.y }) * 1.2)
        val scale = min(size.width, size.height) / spanM
        fun screen(p: Point) = Offset((size.width / 2 + (p.x - cx) * scale).toFloat(), (size.height / 2 - (p.y - cy) * scale).toFloat())
        fun path(points: List<Point>, closed: Boolean) = Path().apply {
            points.forEachIndexed { i, p -> val o = screen(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            if (closed) close()
        }
        val unit = 1.dp.toPx()
        drawLawn(map, { false }, ::path, unit, colors)
        val result: Pair<List<Point>, Boolean>? = when (outcome) {
            is DrivenOutcome.Zone -> (if (enlarge) outcome.enlargement?.after else outcome.separate ?: outcome.outline)?.let { it to true }
            is DrivenOutcome.NoGo -> outcome.points to true
            is DrivenOutcome.Passage -> outcome.points to false
            else -> null
        }
        result?.let { (points, closed) ->
            val p = path(points, closed)
            if (closed) drawPath(p, (if (outcome is DrivenOutcome.NoGo) colors.NoGoFill else colors.ZoneFillSelected))
            drawPath(p, if (outcome is DrivenOutcome.NoGo) colors.NoGoStroke else colors.GreenDeep, style = Stroke(2.5f * unit,
                cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit))))
        }
        if (trace.points.size >= 2) drawPath(path(trace.points, false), colors.Info, style = Stroke(2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        trace.points.firstOrNull()?.let { start ->
            if (trace.kind.closed) drawCircle(colors.Info.copy(alpha = 0.18f), (DrivenRecording.CLOSE_M * scale).toFloat(), screen(start))
            drawCircle(colors.Info, 4f * unit, screen(start))
        }
        if (robotKnown) {
            drawCircle(colors.RobotHalo, 9f * unit, screen(robot))
            drawCircle(colors.RobotMarker, 5f * unit, screen(robot))
        }
    }
}
