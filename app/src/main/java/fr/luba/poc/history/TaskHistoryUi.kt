package fr.luba.poc.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import fr.luba.poc.ui.components.SecondaryPillButton
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
                   onExcluded: (Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
                   onFinishing: ((fr.luba.poc.planning.FinishingOffer) -> Unit)? = null) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    var track by remember(record.id) { mutableStateOf<TaskTrack?>(null) }
    var loading by remember(record.id) { mutableStateOf(true) }
    var coverage by remember(record.id) { mutableStateOf<CoverageMap?>(null) }
    // Replay cursor asked by the journal or a black box (a new value at each tap, even on the same moment).
    var jump by remember(record.id) { mutableStateOf<ReplayJump?>(null) }
    val jumpTo: (Long) -> Unit = { at -> jump = ReplayJump(at, (jump?.nonce ?: 0) + 1) }
    LaunchedEffect(record.id, record.outcome, record.endedAt) {
        track = runCatching { loadTrack(record.id) }.getOrNull()
        loading = false
        // Real coverage: mowings only (surveys and routes run with blades stopped).
        coverage = track?.takeIf { record.kind == TaskKind.MOW }?.let { t ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { CoverageMap.of(t, fallbackMap) }.getOrNull() } }
    }
    PushedPage(tr("${record.kind.label} of ${MowingText.date(record.startedAt)}", "${record.kind.label} du ${MowingText.date(record.startedAt)}"), onBack, modifier) {
        StatusNote(when (record.outcome) {
            MowingOutcome.COMPLETE -> "${record.kind.label} ${TaskText.outcome(record).lowercase()}${record.reason?.let { if (finishedReason(it, withRoute = true)) "" else " · ${reasonText(it)}" } ?: ""}"
            MowingOutcome.INTERRUPTED -> tr("${record.kind.label} ${TaskText.outcome(record).lowercase()} at ${MowingText.percent(record.progress * 100)}: ${record.reason?.let(::reasonText) ?: "unknown cause"}",
                "${record.kind.label} ${TaskText.outcome(record).lowercase()} à ${MowingText.percent(record.progress * 100)} : ${record.reason?.let(::reasonText) ?: "cause inconnue"}")
            MowingOutcome.RUNNING -> tr("${record.kind.label} in progress", "${record.kind.label} en cours")
        }, when (record.outcome) { MowingOutcome.COMPLETE -> NoteTone.SUCCESS; MowingOutcome.INTERRUPTED -> NoteTone.WARNING; else -> NoteTone.INFO })
        TaskReplay(record, track, loading, fallbackMap, coverage, jump)
        if (!loading) TrackingQuality(record, track)
        if (!loading) TaskJournal(track, jumpTo)
        if (!loading) BlackBoxSection(track, jumpTo)
        coverage?.let { RealCoverageSection(record, it) }
        coverage?.let { c ->
            val offer by androidx.compose.runtime.produceState<fr.luba.poc.planning.FinishingOffer?>(null, c, record.outcome) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { fr.luba.poc.planning.FinishingPass.offer(record.id, c, record.outcome == MowingOutcome.COMPLETE) }.getOrNull() }
            }
            offer?.let { FinishingOfferGroup(it, onFinishing) }
        }
        if (record.kind == TaskKind.MOW) {
            val comparison = remember(record) { MowingComparison.of(record) }
            val calibration = remember(history, record.robotId) { MowingCalibration.from(history, record.robotId) }
            val score = remember(record, track, coverage, loading) { if (loading) null else MowingScore.of(record, track, coverage) }
            MowingReportContent(record, comparison, calibration, score)
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
private fun TaskReplay(record: MowingRecord, track: TaskTrack?, loading: Boolean, fallbackMap: RobotMap?, coverage: CoverageMap? = null,
                       jump: ReplayJump? = null) {
    val colors = MammotionColors
    val samples = track?.samples.orEmpty()
    val map = track?.meta?.map ?: fallbackMap ?: RobotMap()
    val pieces = remember(track) { chunks(TaskTrackPieces.of(samples)) }
    val start = track?.startMs ?: 0L
    val end = track?.endMs ?: 0L
    var cursor by remember(track) { mutableLongStateOf(end) }
    var playing by remember(track) { mutableStateOf(false) }
    LaunchedEffect(jump, track) { jump?.let { playing = false; cursor = it.atMs.coerceIn(start, maxOf(start, end)) } }
    val incidents = remember(track) { track?.events.orEmpty().filter { it.type.incident } }
    var speed by remember { mutableIntStateOf(60) }
    var showPlanned by remember { mutableStateOf(true) }
    var showCoverage by remember { mutableStateOf(true) }
    val coverageImage = remember(coverage, record.finished) { coverage?.let { CoverageLayerStyle.image(it, gaps = record.finished) } }
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
                interactive = true, cameraReset = record.id.hashCode(), overlays = overlays, publishedPosition = false,
                coverage = if (showCoverage && coverage != null && coverageImage != null) coverage to coverageImage else null)
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
                if (incidents.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    val previous = incidents.lastOrNull { it.atMs < cursor - 500 }
                    val next = incidents.firstOrNull { it.atMs > cursor + 500 }
                    IconButton(onClick = { previous?.let { playing = false; cursor = it.atMs.coerceIn(start, end) } }, enabled = previous != null) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = tr("Previous incident", "Incident précédent"))
                    }
                    Text(ReplayText.incident(incidents, cursor), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
                    IconButton(onClick = { next?.let { playing = false; cursor = it.atMs.coerceIn(start, end) } }, enabled = next != null) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = tr("Next incident", "Incident suivant"))
                    }
                }
                Legend(samples)
                ToggleRow(tr("Prepared route", "Trajet préparé"), showPlanned, { showPlanned = it },
                    subtitle = tr("Cutting stripes and links computed at start, under the actual positions.", "Bandes de coupe et raccords calculés au lancement, sous les positions réelles."), enabled = track?.meta != null)
                if (coverage != null) ToggleRow(tr("Real coverage", "Couverture réelle"), showCoverage, { showCoverage = it },
                    subtitle = if (record.finished) tr("Mowed area in green, gaps left in orange.", "Surface tondue en vert, manques restants en orange.")
                        else tr("Mowed area so far, in green.", "Surface tondue jusqu'ici, en vert."))
                KeyValueRow(tr("Recorded positions", "Positions relevées"), tr("${samples.size} · ${MowingForecaster.num(distance(samples), 0)} m traveled", "${samples.size} · ${MowingForecaster.num(distance(samples), 0)} m parcourus"))
                if (track?.meta == null) InfoNote(tr("Current robot map shown: the task map was not recorded.", "Carte actuelle du robot affichée : la carte de la tâche n'a pas été enregistrée."))
            }
        }
    }
}

/**
 * Real coverage of a mowing: share of each zone actually mowed according to the track (cutting width around the
 * positions recorded with the blades running) and the gaps left, which a later pass can target.
 */
@Composable
private fun RealCoverageSection(record: MowingRecord, coverage: CoverageMap) {
    val gaps by androidx.compose.runtime.produceState<List<CoverageGap>?>(null, coverage) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { coverage.gaps() }.getOrDefault(emptyList()) }
    }
    SettingsGroup(tr("Real coverage", "Couverture réelle")) {
        if (coverage.coveredM2 <= 0.0) {
            InfoNote(tr("No position recorded with the blades running: coverage not measured.", "Aucune position relevée lames en marche : couverture non mesurée."))
            return@SettingsGroup
        }
        KeyValueRow(tr("Covered", "Couvert"), tr("${CoverageText.percent(coverage.fraction)} · ${CoverageText.area(coverage.coveredM2)} of ${CoverageText.area(coverage.areaM2)}",
            "${CoverageText.percent(coverage.fraction)} · ${CoverageText.area(coverage.coveredM2)} sur ${CoverageText.area(coverage.areaM2)}"))
        if (coverage.zones.size > 1) coverage.zones.forEach { z -> KeyValueRow(z.name.trim().ifBlank { tr("Unnamed zone", "Zone sans nom") }, CoverageText.zone(z)) }
        KeyValueRow(tr("Mowed twice or more", "Tondu plusieurs fois"), CoverageText.area(coverage.repeatedM2))
        if (record.finished) KeyValueRow(tr("Gaps of ${CoverageText.area(CoverageMap.MIN_GAP_M2)} or more", "Manques de ${CoverageText.area(CoverageMap.MIN_GAP_M2)} ou plus"), gaps?.let(CoverageText::gaps) ?: "…")
        InfoNote(tr("Cutting width (${MowingForecaster.num(coverage.cutWidthM * 100, 0)} cm) swept around the positions received with the blades running, on a ${MowingForecaster.num(coverage.cellM * 100, 0)} cm grid; no-go zones excluded. Strips narrower than about 30 cm along the edges are not counted as gaps.",
            "Largeur de coupe (${MowingForecaster.num(coverage.cutWidthM * 100, 0)} cm) balayée autour des positions reçues lames en marche, sur une grille de ${MowingForecaster.num(coverage.cellM * 100, 0)} cm ; zones interdites exclues. Les bandes de moins de 30 cm environ le long des bords ne comptent pas comme des manques."))
    }
}

/**
 * Route tracking: who tracked the route (pilot phone or gateway) and the deviation of the positions recorded while
 * mowing from the prepared stripes, to compare a mowing tracked by the gateway with one tracked by the pilot phone.
 */
@Composable
private fun TrackingQuality(record: MowingRecord, track: TaskTrack?) {
    val deviation = remember(track) { track?.let(RouteDeviation::of) }
    if (record.links.isEmpty() && deviation == null) return
    SettingsGroup(tr("Route tracking", "Suivi du trajet")) {
        TrackingText.links(record)?.let { KeyValueRow(tr("Tracked by", "Suivi par"), it) }
        if (deviation == null) {
            InfoNote(tr("No position recorded with the blades running: deviation not measured.", "Aucune position relevée lames en marche : écart non mesuré."))
            return@SettingsGroup
        }
        KeyValueRow(tr("Deviation from the stripes · median · 95 % · max", "Écart aux bandes · médian · 95 % · max"), TrackingText.deviation(deviation))
        KeyValueRow(tr("Positions beyond 30 cm", "Positions au-delà de 30 cm"), TrackingText.beyond(deviation))
        KeyValueRow(tr("Position holes over 15 s", "Trous de positions de plus de 15 s"), TrackingText.gaps(deviation))
        InfoNote(tr("Measured on the positions received while mowing, against the stripes prepared at start: a bypass or a repaired route counts as a deviation. A hole means positions not received (Wi-Fi or link lost).",
            "Mesuré sur les positions reçues pendant la tonte, par rapport aux bandes préparées au lancement : un contournement ou un trajet réparé compte comme un écart. Un trou signale des positions non reçues (Wi-Fi ou liaison perdus)."))
    }
}

/**
 * Typed journal of the task: start, recharges, obstacles, blockages, STOP, link losses… with the time and the
 * elapsed time since the start. Incidents are highlighted.
 */
@Composable
private fun TaskJournal(track: TaskTrack?, onJump: (Long) -> Unit) {
    val events = track?.events.orEmpty()
    if (events.isEmpty()) return
    var all by remember(track) { mutableStateOf(false) }
    val start = track?.startMs?.takeIf { it > 0 }?.let { minOf(it, events.first().atMs) } ?: events.first().atMs
    SettingsGroup(tr("Task journal", "Journal de la tâche")) {
        KeyValueRow(tr("Events", "Événements"), JournalText.summary(events))
        val shown = if (all || events.size <= JOURNAL_PREVIEW) events else events.take(JOURNAL_PREVIEW)
        shown.forEach { e ->
            // An incident opens the replay at its moment.
            Column(Modifier.fillMaxWidth().then(if (e.type.incident) Modifier.clickable { onJump(e.atMs) } else Modifier).padding(vertical = 2.dp)) {
                Text("${TaskText.clock(e.atMs)} · +${TaskText.elapsed(e.atMs - start)} · ${e.type.label}",
                    style = MaterialTheme.typography.titleSmall, color = if (e.type.incident) MammotionColors.Warning else MammotionColors.TextPrimary)
                JournalText.detail(e)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary) }
            }
        }
        if (events.size > JOURNAL_PREVIEW) ToggleRow(tr("Show all ${events.size} events", "Afficher les ${events.size} événements"), all, { all = it })
    }
}

private const val JOURNAL_PREVIEW = 30

/** Replay cursor asked from outside the replay; [nonce] changes at each request. */
data class ReplayJump(val atMs: Long, val nonce: Int)

/** Labels of the replay incident navigation. */
object ReplayText {
    /** "Incident 2 / 5 · Bumper contact" for the last incident before the cursor, or the count before the first one. */
    fun incident(incidents: List<TaskEvent>, cursor: Long): String {
        val i = incidents.indexOfLast { it.atMs <= cursor + 500 }
        return if (i < 0) tr("${incidents.size} incident${if (incidents.size > 1) "s" else ""}", "${incidents.size} incident${if (incidents.size > 1) "s" else ""}")
        else tr("Incident ${i + 1} / ${incidents.size} · ${incidents[i].type.label}", "Incident ${i + 1} / ${incidents.size} · ${incidents[i].type.label}")
    }
}

/**
 * Black boxes of the task: for each incident, what the robot and the phone did in the 45 s before it and the 3 s after
 * (positions, commands, telemetry, link, log events). A tap opens it; "Show in the replay" moves the replay to it.
 */
@Composable
private fun BlackBoxSection(track: TaskTrack?, onJump: (Long) -> Unit) {
    val boxes = track?.boxes.orEmpty()
    if (boxes.isEmpty()) return
    var open by remember(track) { mutableStateOf<Int?>(null) }
    SettingsGroup("Black Box") {
        boxes.forEachIndexed { i, b ->
            NavRow("${TaskText.clock(b.atMs)} · ${BlackBoxText.title(b)}", icon = Icons.Rounded.ReportProblem, subtitle = BlackBoxText.summary(b)) {
                open = if (open == i) null else i
            }
            if (open == i) BlackBoxDetail(b, onJump)
        }
        InfoNote(tr("Frozen at each incident (blockage, bumper contact, obstacle, lift, robot error, STOP, link lost): 45 s before it and 3 s after, exported with the logs.",
            "Figée à chaque incident (blocage, contact pare-chocs, obstacle, soulèvement, erreur robot, STOP, perte de liaison) : 45 s avant et 3 s après, exportée avec les journaux."))
    }
}

@Composable
private fun BlackBoxDetail(box: BlackBox, onJump: (Long) -> Unit) {
    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    Column(Modifier.fillMaxWidth().padding(start = Dimens.Md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SecondaryPillButton(tr("Show in the replay", "Voir dans le replay"), { onJump(box.atMs) }, Modifier.fillMaxWidth())
        if (box.detail.isNotBlank()) Text(box.detail, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
        Text(tr("Robot (every 2 s)", "Robot (toutes les 2 s)"), style = MaterialTheme.typography.titleSmall)
        BlackBoxText.frames(box).forEach { Text(it, style = mono, color = MammotionColors.TextPrimary) }
        if (box.commands.isNotEmpty()) {
            Text(tr("Commands (last ${BlackBoxText.COMMANDS})", "Commandes (${BlackBoxText.COMMANDS} dernières)"), style = MaterialTheme.typography.titleSmall)
            BlackBoxText.commands(box).forEach { Text(it, style = mono, color = MammotionColors.TextPrimary) }
        }
        if (box.notes.isNotEmpty()) {
            Text(tr("Log", "Journal"), style = MaterialTheme.typography.titleSmall)
            BlackBoxText.notes(box).forEach { Text(it, style = mono, color = MammotionColors.TextSecondary) }
        }
    }
}

/** Labels of the black boxes. */
object BlackBoxText {
    const val COMMANDS = 15
    const val NOTES = 40

    fun title(b: BlackBox): String = b.triggers.joinToString(" + ") { it.label }.ifBlank { "Incident" }

    private fun offset(b: BlackBox, at: Long): String {
        val s = (at - b.atMs) / 1000.0
        return (if (s < 0) "−" else "+") + MowingForecaster.num(Math.abs(s), 1) + " s"
    }

    private fun command(linear: Int?, angular: Int?) = if (linear == null || angular == null) "—" else "$linear/$angular"

    /** State at the incident: link, command, battery, RTK, then the size of the box. */
    fun summary(b: BlackBox): String {
        val f = b.frameAt(b.atMs)
        val state = f?.let { listOfNotNull(it.link.label, tr("command ${command(it.linear, it.angular)}", "commande ${command(it.linear, it.angular)}"),
            it.battery.takeIf { v -> v >= 0 }?.let { v -> tr("battery $v %", "batterie $v %") },
            it.rtkStatus.takeIf { v -> v >= 0 }?.let { v -> "RTK $v" + (it.satellites.takeIf { s -> s >= 0 }?.let { s -> " ($s sat.)" } ?: "") }) }.orEmpty()
        return (state + tr("${b.frames.size} states · ${b.commands.size} commands · ${b.notes.size} events", "${b.frames.size} états · ${b.commands.size} commandes · ${b.notes.size} événements"))
            .joinToString(" · ")
    }

    /** One line every 2 s: time from the incident, position, heading, command, speed, robot state, RTK, link. */
    fun frames(b: BlackBox): List<String> {
        val out = mutableListOf<BlackBoxFrame>()
        b.frames.forEach { f -> if (out.isEmpty() || f.atMs - out.last().atMs >= 2_000) out += f }
        return out.map { f ->
            listOf(offset(b, f.atMs),
                if (f.x.isFinite() && f.y.isFinite()) "(${MowingForecaster.num(f.x, 2)}, ${MowingForecaster.num(f.y, 2)})" else "(—)",
                if (f.heading.isFinite()) "${Math.round(f.heading)}°" else "—°",
                tr("cmd", "cmd") + " " + command(f.linear, f.angular),
                if (f.speedMps.isFinite()) "${MowingForecaster.num(f.speedMps, 2)} m/s" else "— m/s",
                tr("state", "état") + " " + (f.robotState.takeIf { it >= 0 }?.toString() ?: "—"),
                "RTK " + (f.rtkStatus.takeIf { it >= 0 }?.toString() ?: "—"),
                f.link.label).joinToString(" · ")
        }
    }

    fun commands(b: BlackBox): List<String> = b.commands.takeLast(COMMANDS).map { c ->
        "${offset(b, c.atMs)} · ${c.label}" + if (c.linear != null || c.angular != null) " ${command(c.linear, c.angular)}" else "" }

    fun notes(b: BlackBox): List<String> = b.notes.takeLast(NOTES).map { n -> "${offset(b, n.atMs)} · ${n.text}" }
}

/** Labels of the task journal. */
object JournalText {
    /** Count per type, incidents first: "2 Recharge · 1 Obstacle". */
    fun summary(events: List<TaskEvent>): String = events.groupingBy { it.type }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<TaskEventType, Int>> { it.key.incident }.thenBy { it.key.ordinal })
        .joinToString(" · ") { "${it.value} ${it.key.label}" }

    fun detail(e: TaskEvent): String? = listOfNotNull(e.detail.takeIf { it.isNotBlank() && it != e.type.label },
        e.battery.takeIf { it >= 0 }?.let { tr("battery $it %", "batterie $it %") }).joinToString(" · ").ifBlank { null }
}

/** Labels of the route tracking section. */
object TrackingText {
    private fun cm(m: Double) = "${MowingForecaster.num(m * 100, 0)} cm"

    fun links(r: MowingRecord): String? {
        if (r.links.isEmpty()) return null
        val names = TrackingLink.entries.filter { it in r.links }.joinToString(" + ") { it.label }
        return if (r.gatewayLegs > 0) tr("$names · ${r.gatewayLegs} run${if (r.gatewayLegs > 1) "s" else ""} handed to the gateway",
            "$names · ${r.gatewayLegs} suivi${if (r.gatewayLegs > 1) "s" else ""} confié${if (r.gatewayLegs > 1) "s" else ""} à la passerelle") else names
    }

    fun deviation(d: RouteDeviation): String =
        "${cm(d.medianM)} · ${cm(d.p95M)} · ${if (d.offRoute > 0) "≥ " else ""}${cm(d.maxM)}"

    fun beyond(d: RouteDeviation): String =
        tr("${d.over30} of ${d.samples} (${MowingText.percent(100.0 * d.over30 / d.samples)})", "${d.over30} sur ${d.samples} (${MowingText.percent(100.0 * d.over30 / d.samples)})")

    fun gaps(d: RouteDeviation): String =
        if (d.gaps == 0) tr("None", "Aucun") else tr("${d.gaps} · longest ${TaskText.elapsed(d.longestGapMs)}", "${d.gaps} · le plus long ${TaskText.elapsed(d.longestGapMs)}")
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
