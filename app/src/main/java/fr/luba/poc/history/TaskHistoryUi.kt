package fr.luba.poc.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.MapLive
import fr.luba.poc.map.MapOverlay
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PushedPage
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.ToggleRow
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Labels specific to history tasks (mowings, surveys, runs with blades stopped). */
object TaskText {
    fun title(r: MowingRecord) = "${r.kind.label} · ${MowingText.zones(r)}"
    /** Task outcome; in French it agrees in gender: "Terminée" for a mowing, "Terminé" for a survey or a run. */
    fun outcome(r: MowingRecord): String {
        val e = if (r.kind == TaskKind.MOW) "e" else ""
        return when (r.outcome) {
            MowingOutcome.COMPLETE -> tr("Finished", "Terminé$e")
            MowingOutcome.INTERRUPTED -> tr("Interrupted", "Interrompu$e")
            MowingOutcome.RUNNING -> tr("In progress", "En cours")
        }
    }
    fun subtitle(r: MowingRecord) = "${MowingText.date(r.startedAt)} · ${outcome(r)} · ${MowingText.summary(r)}${if (r.excluded) tr(" · excluded", " · écartée") else ""}"
    fun clock(epochMs: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss", fr.luba.poc.i18n.I18n.locale)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 3600) "%d min %02d s".format(s / 60, s % 60) else "%d h %02d min".format(s / 3600, (s % 3600) / 60)
    }
    fun icon(r: MowingRecord): ImageVector = when {
        r.outcome == MowingOutcome.RUNNING -> Icons.Rounded.PlayCircle
        r.kind == TaskKind.SURVEY -> Icons.Rounded.WifiTethering
        r.kind == TaskKind.FOLLOW -> Icons.Rounded.Route
        r.outcome == MowingOutcome.COMPLETE -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.PauseCircle
    }
}

/** Task row: type, zones, date, outcome and duration; a tap opens its detail. */
@Composable
fun TaskRow(record: MowingRecord, onOpen: (String) -> Unit) {
    NavRow(TaskText.title(record), icon = TaskText.icon(record), subtitle = TaskText.subtitle(record)) { onOpen(record.id) }
}

/**
 * History button of the map: latest completed tasks (mowings, surveys, runs with blades stopped), the running task
 * first. A tap opens the detail and the route replay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHistorySheet(history: List<MowingRecord>, current: MowingRecord?, onOpen: (String) -> Unit, onOpenAll: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MammotionColors.Background) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Lg).padding(bottom = Dimens.Lg),
            verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
            Text(tr("Latest tasks", "Dernières tâches"), style = MaterialTheme.typography.headlineSmall)
            current?.let { r -> SettingsGroup(tr("In progress", "En cours")) { TaskRow(r, onOpen) } }
            val finished = history.filter { it.finished }.take(RECENT)
            SettingsGroup(if (finished.isEmpty()) tr("Completed", "Réalisées") else tr("Completed · ${finished.size} most recent", "Réalisées · ${finished.size} plus récentes")) {
                if (finished.isEmpty()) InfoNote(tr("No completed task. Every mowing, signal survey or run with blades stopped appears here, with its actual route to replay on the map.", "Aucune tâche réalisée. Chaque tonte, relevé des signaux ou parcours lames arrêtées apparaît ici, avec son trajet réel à revoir sur la carte."))
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) { finished.forEach { TaskRow(it, onOpen) } }
            }
            NavRow(tr("Full history", "Tout l'historique"), icon = Icons.Rounded.History, subtitle = tr("All tasks and adjusted estimates", "Toutes les tâches et estimations ajustées"), onClick = onOpenAll)
        }
    }
}

private const val RECENT = 12

/** Trace colour by robot activity. */
@Composable
private fun phaseColor(phase: TrackPhase): Color = when (phase) {
    TrackPhase.CUTTING -> MammotionColors.GreenDeep
    TrackPhase.TRAVEL -> MammotionColors.Info
    TrackPhase.RETURN, TrackPhase.DOCKING -> MammotionColors.Warning
    TrackPhase.CHARGING -> MammotionColors.Warning
    TrackPhase.OTHER -> MammotionColors.TextSecondary
}

/** Size-limited trace chunk: the portion being replayed is redrawn quickly on each frame. */
private class Chunk(val phase: TrackPhase, val from: Int, val to: Int, val points: List<Point>)

private fun chunks(pieces: List<TaskTrackPieces.Piece>, size: Int = 240): List<Chunk> = pieces.flatMap { p ->
    if (p.points.size <= size) listOf(Chunk(p.phase, p.from, p.to, p.points))
    else (0 until p.points.size - 1 step size - 1).map { start ->
        val end = minOf(start + size - 1, p.points.size - 1)
        Chunk(p.phase, p.from + start, p.from + end, p.points.subList(start, end + 1))
    }
}

/**
 * Detail of a history task: task map with the prepared route and the actual recorded positions, animated replay
 * (play, speed, time slider), then the report.
 */
@Composable
fun TaskDetailPage(record: MowingRecord, history: List<MowingRecord>, fallbackMap: RobotMap?, loadTrack: suspend (String) -> TaskTrack?,
                   onExcluded: (Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    var track by remember(record.id) { mutableStateOf<TaskTrack?>(null) }
    var loading by remember(record.id) { mutableStateOf(true) }
    LaunchedEffect(record.id, record.outcome, record.endedAt) {
        track = runCatching { loadTrack(record.id) }.getOrNull()
        loading = false
    }
    PushedPage(tr("${record.kind.label} of ${MowingText.date(record.startedAt)}", "${record.kind.label} du ${MowingText.date(record.startedAt)}"), onBack, modifier) {
        StatusNote(when (record.outcome) {
            MowingOutcome.COMPLETE -> "${record.kind.label} ${TaskText.outcome(record).lowercase()}${record.reason?.let { if (finishedReason(it, withRoute = true)) "" else " · ${reasonText(it)}" } ?: ""}"
            MowingOutcome.INTERRUPTED -> tr("${record.kind.label} ${TaskText.outcome(record).lowercase()} at ${MowingText.percent(record.progress * 100)}: ${record.reason?.let(::reasonText) ?: "unknown cause"}",
                "${record.kind.label} ${TaskText.outcome(record).lowercase()} à ${MowingText.percent(record.progress * 100)} : ${record.reason?.let(::reasonText) ?: "cause inconnue"}")
            MowingOutcome.RUNNING -> tr("${record.kind.label} in progress", "${record.kind.label} en cours")
        }, when (record.outcome) { MowingOutcome.COMPLETE -> NoteTone.SUCCESS; MowingOutcome.INTERRUPTED -> NoteTone.WARNING; else -> NoteTone.INFO })
        TaskReplay(record, track, loading, fallbackMap)
        if (record.kind == TaskKind.MOW) {
            val comparison = remember(record) { MowingComparison.of(record) }
            val calibration = remember(history, record.robotId) { MowingCalibration.from(history, record.robotId) }
            MowingReportContent(record, comparison, calibration)
            SettingsGroup {
                ToggleRow(tr("Use for estimates", "Utiliser pour les estimations"), !record.excluded, { onExcluded(!it) },
                    subtitle = tr("Turn off for a test or an abnormal mowing: it will not correct the next estimates.",
                        "Désactiver pour un essai ou une tonte anormale : elle ne corrigera pas les prochaines estimations."),
                    enabled = record.finished)
            }
        } else SettingsGroup(record.kind.label) {
            KeyValueRow("Zones", MowingText.zones(record))
            KeyValueRow(tr("Start", "Début"), MowingText.date(record.startedAt))
            record.endedAt?.let { KeyValueRow(tr("End", "Fin"), MowingText.date(it)) }
            KeyValueRow(tr("Duration", "Durée"), MowingText.duration(record.totals.totalSeconds))
            KeyValueRow(tr("Route", "Trajet"), "${MowingForecaster.num(record.doneMeters, 0)} / ${MowingForecaster.num(record.cuttingMeters, 0)} m · ${record.mode}")
            KeyValueRow(tr("Battery", "Batterie"), "${record.batteryStart?.let { "$it %" } ?: "—"} → ${record.batteryEnd?.let { "$it %" } ?: "—"}")
            if (record.obstacles > 0) KeyValueRow(tr("Obstacles bypassed", "Obstacles contournés"), "${record.obstacles}")
            InfoNote(tr("Route with blades stopped: it does not adjust the mowing duration estimates.", "Trajet lames arrêtées : il n'ajuste pas les estimations de durée des tontes."))
        }
    }
}

/** Map and replay controls for the actual positions. */
@Composable
private fun TaskReplay(record: MowingRecord, track: TaskTrack?, loading: Boolean, fallbackMap: RobotMap?) {
    val colors = MammotionColors
    val samples = track?.samples.orEmpty()
    val map = track?.meta?.map ?: fallbackMap ?: RobotMap()
    val pieces = remember(track) { chunks(TaskTrackPieces.of(samples)) }
    val start = track?.startMs ?: 0L
    val end = track?.endMs ?: 0L
    var cursor by remember(track) { mutableLongStateOf(end) }
    var playing by remember(track) { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(60) }
    var showPlanned by remember { mutableStateOf(true) }
    LaunchedEffect(playing, speed, track) {
        if (!playing || samples.size < 2) return@LaunchedEffect
        if (cursor >= end) cursor = start
        var last = withFrameMillis { it }
        while (cursor < end) {
            val now = withFrameMillis { it }
            // Recharges and long stationary periods play 20 times faster so the route stays readable.
            val here = samples.getOrNull(track!!.indexAt(cursor))
            val boost = if (here?.phase == TrackPhase.CHARGING) 20 else 1
            cursor = minOf(end, cursor + (now - last) * speed * boost)
            last = now
        }
        playing = false
    }
    val index = track?.indexAt(cursor) ?: -1
    val pose = if (samples.isEmpty()) null else track?.poseAt(cursor)
    val palette = TrackPhase.entries.associateWith { phaseColor(it) }
    val faint = remember(pieces, palette) { pieces.map { MapOverlay(it.points, palette.getValue(it.phase).copy(alpha = 0.22f), widthDp = 3f) } }
    val done = remember(pieces, palette) { pieces.map { MapOverlay(it.points, palette.getValue(it.phase), widthDp = 2.5f) } }
    val border = colors.Warning // planned route in orange (map borders are white)
    val transit = remember(track, border) { track?.meta?.transit.orEmpty().map { MapOverlay(it, border, widthDp = 1f) } }
    val overlays = buildList {
        if (showPlanned) addAll(transit)
        addAll(faint)
        pieces.forEachIndexed { i, c ->
            when {
                c.to <= index -> add(done[i])
                c.from <= index -> {
                    val partial = c.points.subList(0, index - c.from + 1) + listOfNotNull(pose?.point)
                    add(MapOverlay(partial, palette.getValue(c.phase), widthDp = 2.5f))
                }
            }
        }
    }
    SettingsGroup(tr("Actual route", "Trajet réel")) {
        Box(Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(16.dp)).background(colors.SurfaceMuted)) {
            MapLive(map, Modifier.fillMaxWidth().height(400.dp), x = pose?.x, y = pose?.y, heading = pose?.heading?.takeUnless { it.isNaN() },
                trail = if (map.shapes.isEmpty()) samples.map { it.point } else emptyList(),
                plannedBands = if (showPlanned) track?.meta?.cutting.orEmpty() else emptyList(),
                interactive = true, cameraReset = record.id.hashCode(), overlays = overlays, publishedPosition = false)
        }
        when {
            loading -> InfoNote(tr("Loading the track…", "Chargement de la piste…"))
            samples.isEmpty() -> InfoNote(if (track?.meta != null) tr("No position received during this task.", "Aucune position reçue pendant cette tâche.")
                else tr("Positions not recorded: replay covers tasks started since version 0.53.0.", "Positions non enregistrées : la relecture concerne les tâches lancées depuis la version 0.53.0."))
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Xs)) {
                    IconButton(onClick = { playing = !playing }) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (playing) tr("Pause the replay", "Mettre la relecture en pause") else tr("Play the route", "Lire le trajet"))
                    }
                    IconButton(onClick = { playing = false; cursor = start }) { Icon(Icons.Rounded.Replay, contentDescription = tr("Back to start", "Revenir au début")) }
                    Column(Modifier.weight(1f)) {
                        Text("${TaskText.clock(cursor)} · +${TaskText.elapsed(cursor - start)}", style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(samples.getOrNull(index.coerceAtLeast(0))?.phase?.label,
                            samples.getOrNull(index.coerceAtLeast(0))?.battery?.takeIf { it >= 0 }?.let { tr("battery $it %", "batterie $it %") }).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium, color = colors.TextSecondary)
                    }
                }
                Slider(value = if (end > start) ((cursor - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 1f,
                    onValueChange = { playing = false; cursor = start + ((end - start) * it).toLong() })
                Segmented(listOf(10 to "×10", 60 to "×60", 300 to "×300", 1000 to "×1000"), speed, { speed = it })
                Legend(samples)
                ToggleRow(tr("Prepared route", "Trajet préparé"), showPlanned, { showPlanned = it },
                    subtitle = tr("Cutting stripes and links computed at start, under the actual positions.", "Bandes de coupe et raccords calculés au lancement, sous les positions réelles."), enabled = track?.meta != null)
                KeyValueRow(tr("Recorded positions", "Positions relevées"), tr("${samples.size} · ${MowingForecaster.num(distance(samples), 0)} m traveled", "${samples.size} · ${MowingForecaster.num(distance(samples), 0)} m parcourus"))
                if (track?.meta == null) InfoNote(tr("Current robot map shown: the task map was not recorded.", "Carte actuelle du robot affichée : la carte de la tâche n'a pas été enregistrée."))
            }
        }
    }
}

@Composable
private fun Legend(samples: List<TrackSample>) {
    val phases = remember(samples) { samples.map { it.phase }.toSet() }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Md), verticalAlignment = Alignment.CenterVertically) {
        listOf(TrackPhase.CUTTING to tr("Mowing", "Tonte"), TrackPhase.TRAVEL to tr("Blades stopped", "Lames arrêtées"), TrackPhase.RETURN to tr("Return, recharge", "Retour, recharge"))
            .filter { (p, _) -> p in phases || (p == TrackPhase.RETURN && (TrackPhase.CHARGING in phases || TrackPhase.DOCKING in phases)) }
            .forEach { (phase, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(10.dp).clip(CircleShape).background(phaseColor(phase)))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
                }
            }
    }
}

/** Distance travelled, not counting jumps between unjoined pieces. */
internal fun distance(samples: List<TrackSample>): Double =
    samples.zipWithNext().sumOf { (a, b) -> if (TaskTrackPieces.broken(a, b)) 0.0 else a.point.distance(b.point) }
