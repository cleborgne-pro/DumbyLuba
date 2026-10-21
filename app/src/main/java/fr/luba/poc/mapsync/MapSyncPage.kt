package fr.luba.poc.mapsync

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.drawLuba
import fr.luba.poc.map.kind
import fr.luba.poc.simulation.PathFollower
import fr.luba.poc.ui.components.LubaCard
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Page state kept while the app runs: opened from the Map sheet, elements left out of the sync, element shown. */
object MapSyncScreen {
    var open by mutableStateOf(false)
    var excluded by mutableStateOf(emptySet<String>())
    var focused by mutableStateOf<String?>(null)
}

/** Full-screen sync page, opened from the Map sheet (kept outside MapScreen, at its register limit). */
@Composable
fun MapSyncHost(robot: RobotBle, state: RobotUi) {
    if (!MapSyncScreen.open) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { MapSyncScreen.open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = false, dismissOnClickOutside = false)) {
        MapSyncPage(robot, state, onClose = { MapSyncScreen.open = false })
    }
}

/**
 * **Sync with the Mammotion app**: lists the differences between the corrected map (Mammotion map + local
 * corrections) and the Mammotion map held by the robot, lets the user leave some out, warns that the robot will drive
 * every outline on its own, then follows the sync live (map, phase, outline progress, element states, STOP).
 */
@Composable
fun MapSyncPage(robot: RobotBle, state: RobotUi, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val run by robot.mapSync.state.collectAsState()
    var items by remember { mutableStateOf<List<SyncItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Differences recomputed on every map or correction change, not during a sync (its own list is shown then).
    LaunchedEffect(state.mammotionMap, state.mapCorrections, run.running) {
        if (run.running) return@LaunchedEffect
        error = null
        items = try { robot.mapSyncDifferences() } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; emptyList() }
    }
    val robotPoint = state.telemetry.x?.let { x -> state.telemetry.y?.let { y -> Point(x, y) } }?.takeIf { it.x.isFinite() && it.y.isFinite() }
    MapSyncContent(state.mammotionMap, items, error, run, robot.mapSyncRefusal(),
        minOf(PathFollower.linearFor(state.trajectorySpeedPercent), MapSyncPlanner.TRACE_LINEAR) * MM_PER_UNIT / 1000.0, robotPoint, state.telemetry.heading,
        state.navTest.trail.map { Point(it.x, it.y) }.takeIf { run.phase == SyncPhase.RECORDING }.orEmpty(),
        onClose, onStart = { robot.mapSync.start(it) }, onStop = { robot.mapSync.stop() }, onReset = { robot.mapSync.reset() })
}

/**
 * Content of the sync page, without the robot link (tests and screenshots): [items] null while the comparison runs,
 * [refusal] why the sync cannot start, [speedMps] speed of the moves for the estimates, [trail] positions recorded
 * live while an outline is driven.
 */
@Composable
fun MapSyncContent(map: RobotMap, items: List<SyncItem>?, error: String?, run: MapSyncRun, refusal: String?, speedMps: Double,
                   robotPoint: Point?, heading: Double?, trail: List<Point>, onClose: () -> Unit, onStart: (Set<String>) -> Unit,
                   onStop: () -> Unit, onReset: () -> Unit) {
    val colors = MammotionColors
    var confirm by remember { mutableStateOf(false) }
    val shown: List<SyncItem> = if (run.items.isNotEmpty()) run.items.map { it.item } else items.orEmpty()
    val chosen = items.orEmpty().filter { it.possible && it.key !in MapSyncScreen.excluded }

    Column(Modifier.fillMaxSize().background(colors.Background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", "Retour")) }
            Column(Modifier.weight(1f)) {
                Text(tr("Sync with the Mammotion app", "Synchroniser avec l'app Mammotion"), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("Phone corrections → map stored in the robot", "Corrections du téléphone → carte enregistrée dans le robot"),
                    style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SyncMap(map, shown, run, MapSyncScreen.focused, robotPoint, heading, trail, Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp).widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (run.phase != SyncPhase.IDLE) RunPanel(run, onStop = onStop, onReset = onReset)
            else MoveWarning()
            when {
                items == null && run.items.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(tr("Comparing the two maps…", "Comparaison des deux cartes…"), style = MaterialTheme.typography.bodyMedium)
                }
                error != null -> StatusNote(tr("Comparison impossible: $error", "Comparaison impossible : $error"), NoteTone.DANGER)
                shown.isEmpty() -> StatusNote(if (map.complete) tr("No difference: the Mammotion map already holds your corrections.",
                    "Aucune différence : la carte Mammotion contient déjà vos corrections.")
                    else tr("Read the robot map first (Map sheet → Read the robot map).", "Lisez d'abord la carte du robot (feuille Carte → Lire la carte du robot)."), NoteTone.INFO)
                else -> DifferenceList(shown, run, speedMps)
            }
            if (run.phase == SyncPhase.IDLE && items.orEmpty().isNotEmpty()) {
                val meters = chosen.sumOf { it.traceM }
                val minutes = ceil(chosen.sumOf { MapSyncPlanner.durationS(it, speedMps) } / 60.0).toInt()
                refusal?.let { StatusNote(it, NoteTone.WARNING) }
                chosen.firstOrNull { MapSyncPlanner.durationS(it, speedMps) > MapSyncPlanner.MAX_DRIVE_S }?.let {
                    StatusNote(tr("${it.title}: outline too long for one drive at this speed, raise the route speed (Map → mowing settings)",
                        "${it.title} : contour trop long pour un seul parcours à cette vitesse, augmentez la vitesse des trajets (Carte → réglages de tonte)"), NoteTone.WARNING)
                }
                PrimaryPillButton(if (chosen.isEmpty()) tr("Nothing selected", "Rien de sélectionné")
                    else tr("Start the sync · ${chosen.size} element(s) · ${MapCorrections.meters(meters)} m · ≈ $minutes min",
                        "Lancer la synchronisation · ${chosen.size} élément(s) · ${MapCorrections.meters(meters)} m · ≈ $minutes min"),
                    { confirm = true }, Modifier.fillMaxWidth().testTag("sync-start"), enabled = chosen.isNotEmpty() && refusal == null, icon = Icons.Rounded.Sync)
            }
        }
    }

    if (confirm) LubaDialog(onDismissRequest = { confirm = false }, title = tr("The robot will move on its own", "Le robot va se déplacer seul"), content = {
        Text(tr("${chosen.size} outline(s), ${MapCorrections.meters(chosen.sumOf { it.traceM })} m driven blades stopped, plus the moves between them. Stay next to the robot with STOP within reach, nobody in the area.",
            "${chosen.size} contour(s), ${MapCorrections.meters(chosen.sumOf { it.traceM })} m parcourus lames arrêtées, plus les trajets entre eux. Restez près du robot, STOP à portée, personne dans la zone."),
            style = MaterialTheme.typography.bodyMedium)
        Text(tr("Back up the map in the Mammotion app first: a replaced zone gets a new identifier (its Mammotion schedules must be set again) and nothing written to the robot can be undone from here.",
            "Sauvegardez d'abord la carte dans l'app Mammotion : une zone remplacée reçoit un nouvel identifiant (ses plannings Mammotion sont à refaire) et rien de ce qui est écrit dans le robot ne s'annule d'ici."),
            style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
    }, footer = {
        PrimaryPillButton(tr("Start", "Démarrer"), { confirm = false; onStart(chosen.map { it.key }.toSet()) }, Modifier.fillMaxWidth().testTag("sync-confirm"))
        TextAction(tr("Cancel", "Annuler"), { confirm = false }, Modifier.fillMaxWidth())
    })
}

/** What the sync does to the robot, shown before it starts. */
@Composable
private fun MoveWarning() {
    val colors = MammotionColors
    LubaCard(containerColor = colors.WarningSoft, border = false) {
        Text(tr("The robot has to move", "Le robot va devoir se déplacer"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(tr("The robot only stores what it records while it drives. For each element below, it drives on its own, blades stopped, to the outline, then along it while it records it, exactly like a recording made in the Mammotion app. A modified zone is recorded again in full, then the former one is deleted.",
            "Le robot n'enregistre que ce qu'il parcourt. Pour chaque élément ci-dessous, il se rend seul, lames arrêtées, jusqu'au contour puis le longe en l'enregistrant, comme un enregistrement fait dans l'app Mammotion. Une zone modifiée est réenregistrée en entier, puis l'ancienne est supprimée."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Text(tr("Untick what must not be sent to the robot. Border types, real coverage and encountered obstacles have no place in the Mammotion map and stay on the phone.",
            "Décochez ce qui ne doit pas être envoyé au robot. Les types de bordure, la couverture réelle et les obstacles rencontrés n'ont pas d'équivalent dans la carte Mammotion et restent sur le téléphone."),
            style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Phase, message, outline progress and STOP while the sync runs; result and back to the list once it ends. */
@Composable
private fun RunPanel(run: MapSyncRun, onStop: () -> Unit, onReset: () -> Unit) {
    val colors = MammotionColors
    val tone = when (run.phase) {
        SyncPhase.DONE -> colors.GreenSoft
        SyncPhase.FAILED, SyncPhase.STOPPED -> colors.DangerSoft
        else -> colors.InfoSoft
    }
    LubaCard(containerColor = tone, border = false, modifier = Modifier.testTag("sync-run")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (run.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(run.phase.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                run.current?.takeIf { run.running }?.let { Text(it.item.title, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary) }
            }
            Text("${run.done}/${run.items.count { it.status != ItemStatus.SKIPPED || it.note == null }.coerceAtLeast(run.done)}",
                style = MaterialTheme.typography.labelLarge, color = colors.TextSecondary)
        }
        if (run.message.isNotBlank() && run.message != run.phase.label) Text(run.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        run.progress?.let { p ->
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth(), color = colors.Danger)
                Text(tr("Outline recorded ${(p * 100).toInt()} %", "Contour enregistré à ${(p * 100).toInt()} %"), style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
            }
        }
        Column(Modifier.padding(top = 10.dp)) {
            if (run.running) PrimaryPillButton(tr("STOP", "STOP"), onStop, Modifier.fillMaxWidth().testTag("sync-stop"), icon = Icons.Rounded.Stop)
            else SecondaryPillButton(tr("Back to the differences", "Revenir aux différences"), onReset, Modifier.fillMaxWidth())
        }
    }
}

/** Differences with their tick (before the sync) or their state (during and after it). */
@Composable
private fun DifferenceList(items: List<SyncItem>, run: MapSyncRun, speedMps: Double) {
    val colors = MammotionColors
    val states = run.items.associateBy { it.item.key }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("${items.size} difference(s) with the Mammotion map", "${items.size} différence(s) avec la carte Mammotion"),
            style = MaterialTheme.typography.titleSmall)
        items.forEach { item ->
            val runItem = states[item.key]
            val focused = MapSyncScreen.focused == item.key
            Surface(shape = RoundedCornerShape(14.dp), color = if (focused) colors.SurfaceMuted else colors.Surface,
                modifier = Modifier.fillMaxWidth().clickable { MapSyncScreen.focused = if (focused) null else item.key }.testTag("sync-item-${item.key}")) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    if (runItem == null) Checkbox(checked = item.possible && item.key !in MapSyncScreen.excluded, enabled = item.possible && !run.running,
                        onCheckedChange = { on -> MapSyncScreen.excluded = if (on) MapSyncScreen.excluded - item.key else MapSyncScreen.excluded + item.key })
                    else StatusIcon(runItem.status, Modifier.padding(12.dp))
                    Column(Modifier.weight(1f).padding(top = 6.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(kindIcon(item.kind), null, Modifier.size(16.dp), tint = kindColor(item.kind))
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Chip(runItem?.status?.label ?: item.action.label, runItem?.status)
                        }
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                        if (item.possible) Text(tr("Driven: ${MapCorrections.meters(item.traceM)} m · ≈ ${ceil(MapSyncPlanner.durationS(item, speedMps) / 60.0).toInt()} min",
                            "Parcours : ${MapCorrections.meters(item.traceM)} m · ≈ ${ceil(MapSyncPlanner.durationS(item, speedMps) / 60.0).toInt()} min"),
                            style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary)
                        (runItem?.note ?: item.refusal)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (runItem?.status == ItemStatus.DONE) colors.GreenDeep else colors.Danger) }
                        if (focused) item.warnings.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = colors.TextSecondary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, status: ItemStatus?) {
    val colors = MammotionColors
    val (bg, fg) = when (status) {
        ItemStatus.DONE -> colors.GreenSoft to colors.GreenDeep
        ItemStatus.FAILED -> colors.DangerSoft to colors.Danger
        ItemStatus.ACTIVE -> colors.InfoSoft to colors.TextPrimary
        else -> colors.SurfaceMuted to colors.TextSecondary
    }
    Surface(shape = RoundedCornerShape(10.dp), color = bg) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun StatusIcon(status: ItemStatus, modifier: Modifier) {
    val colors = MammotionColors
    when (status) {
        ItemStatus.ACTIVE -> CircularProgressIndicator(modifier.size(20.dp), strokeWidth = 2.dp)
        ItemStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, modifier.size(22.dp), tint = colors.Green)
        ItemStatus.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, modifier.size(22.dp), tint = colors.Danger)
        else -> Box(modifier.size(22.dp))
    }
}

private fun kindIcon(kind: SyncKind): ImageVector = when (kind) {
    SyncKind.ZONE -> Icons.Rounded.CropSquare
    SyncKind.NO_GO -> Icons.Rounded.DoNotDisturbOn
    SyncKind.PASSAGE -> Icons.Rounded.Timeline
}

@Composable
private fun kindColor(kind: SyncKind): Color = when (kind) {
    SyncKind.ZONE -> MammotionColors.Green
    SyncKind.NO_GO -> MammotionColors.Danger
    SyncKind.PASSAGE -> MammotionColors.Info
}

/**
 * Map of the sync: Mammotion map as stored in the robot, wanted outlines (created in green, replaced in orange over the
 * former one dashed), trace of the element shown or in progress, trail recorded live, robot.
 */
@Composable
private fun SyncMap(map: RobotMap, items: List<SyncItem>, run: MapSyncRun, focused: String?, robot: Point?, heading: Double?,
                    trail: List<Point>, modifier: Modifier) {
    val colors = MammotionColors
    val excluded = MapSyncScreen.excluded
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = colors.MapBackground) {
        Canvas(Modifier.fillMaxSize().testTag("sync-map")) {
            val all = map.shapes.flatMap { it.points } + items.flatMap { it.outline } + listOfNotNull(robot)
            if (all.isEmpty()) return@Canvas
            val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
            val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
            val unit = min(size.width / max(maxX - minX, 1.0), size.height / max(maxY - minY, 1.0)).toFloat() * 0.9f
            val cx = ((minX + maxX) / 2); val cy = ((minY + maxY) / 2)
            fun at(p: Point) = Offset(size.width / 2 + ((p.x - cx) * unit).toFloat(), size.height / 2 - ((p.y - cy) * unit).toFloat())
            fun path(points: List<Point>, closed: Boolean) = Path().apply {
                points.forEachIndexed { i, p -> val o = at(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                if (closed) close()
            }
            val thin = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            map.shapes.filter { it.points.size >= 2 }.sortedBy { it.type }.forEach { shape -> drawShape(shape, ::path, thin, colors) }
            val active = run.currentKey ?: focused
            items.forEach { item ->
                val faded = item.key in excluded && run.items.isEmpty()
                val color = (if (item.action == SyncAction.CREATE) colors.Green else colors.Warning).copy(alpha = if (faded) 0.3f else 1f)
                item.previous?.let { drawPath(path(it.points, true), colors.TextSecondary, style = Stroke(1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))) }
                drawPath(path(item.outline, item.kind.closed), color, style = Stroke((if (item.key == active) 3.5 else 2.5).dp.toPx(),
                    cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (item.key == active && item.trace.size >= 2) drawPath(path(item.trace, false), colors.Info,
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
            }
            if (trail.size >= 2) drawPath(path(trail, false), colors.Danger, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            robot?.let { drawLuba(at(it), unit.toDouble(), heading ?: 90.0) }
        }
    }
}

private fun DrawScope.drawShape(shape: MapShape, path: (List<Point>, Boolean) -> Path, stroke: Stroke, colors: fr.luba.poc.ui.theme.LubaColors) {
    when (shape.kind) {
        ShapeKind.ZONE -> { val p = path(shape.points, true); drawPath(p, colors.ZoneFill); drawPath(p, colors.ZoneStroke, style = stroke) }
        ShapeKind.NO_GO, ShapeKind.VISION_ZONE -> { val p = path(shape.points, true); drawPath(p, colors.NoGoFill); drawPath(p, colors.NoGoStroke, style = stroke) }
        ShapeKind.PASSAGE -> drawPath(path(shape.points, false), colors.TextTertiary, style = stroke)
        ShapeKind.OBSTACLE -> drawPath(path(shape.points, false), colors.ObstacleStroke, style = stroke)
    }
}

/** Travel per speed unit of the moves: 500 ≈ 0.48 m/s measured on 2026-10-10. */
private const val MM_PER_UNIT = 0.96
