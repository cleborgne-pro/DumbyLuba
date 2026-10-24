package fr.luba.poc.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import fr.luba.poc.map.RobotMap
import fr.luba.poc.planning.FinishingOffer
import fr.luba.poc.planning.FinishingPass
import androidx.compose.ui.Modifier
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.PushedPage
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.components.ToggleRow
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Report and history labels, in the interface language (dates and numbers formatted in that language too). */
object MowingText {
    /** Locale of dates: French on a French device, English otherwise. */
    internal val locale: Locale get() = if (I18n.french) Locale.FRANCE else Locale.ENGLISH
    fun duration(seconds: Double): String {
        val minutes = Math.round(seconds / 60).toInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
    }
    fun date(epochMs: Long): String = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", locale)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    fun hour(epochMs: Long): String = DateTimeFormatter.ofPattern("HH:mm", locale)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
    fun percent(value: Double) = "${Math.round(value)} %"
    fun minutes(value: Double) = "${Math.round(value)} min"
    fun factor(value: Double) = "×" + MowingForecaster.num(value, 2)
    fun zones(record: MowingRecord) = record.zoneNames.joinToString(", ").ifBlank { tr("Unnamed zones", "Zones sans nom") }
    /** Summary line of the history: actual duration, deviation from the estimate, recharges. */
    fun summary(record: MowingRecord): String {
        val c = MowingComparison.of(record)
        val delta = c.durationDelta?.let { d -> " (%s%d %%)".format(if (d >= 0) "+" else "−", Math.round(Math.abs(d) * 100)) } ?: ""
        val progress = if (record.outcome == MowingOutcome.INTERRUPTED) " · ${percent(record.progress * 100)} ${if (record.kind == TaskKind.MOW) tr("mowed", "tondus") else tr("covered", "parcourus")}" else ""
        return tr("${duration(c.actualSeconds)}$delta · ${record.recharges} recharge${if (record.recharges != 1) "s" else ""}$progress",
            "${duration(c.actualSeconds)}$delta · ${record.recharges} recharge${if (record.recharges > 1) "s" else ""}$progress")
    }
}

/** End-of-mowing report: actual figures compared with the estimate, battery measurements and effect on the next estimates. */
@Composable
fun MowingReportDialog(record: MowingRecord, history: List<MowingRecord>, onExcluded: (Boolean) -> Unit, onDismiss: () -> Unit,
                       loadTrack: (suspend (String) -> TaskTrack?)? = null, fallbackMap: RobotMap? = null,
                       onFinishing: ((FinishingOffer) -> Unit)? = null) {
    val comparison = remember(record) { MowingComparison.of(record) }
    val calibration = remember(history, record.robotId) { MowingCalibration.from(history, record.robotId) }
    val analysis = loadTrack?.let { rememberMowingAnalysis(record, it, fallbackMap) }
    val score = analysis?.score
    LubaDialog(onDismissRequest = onDismiss, title = tr("Mowing report", "Rapport de tonte"), content = {
        val extra = record.reason?.let { if (finishedReason(it, withRoute = false)) "" else " · ${reasonText(it)}" } ?: ""
        val cause = record.reason?.let(::reasonText) ?: tr("unknown cause", "cause inconnue")
        StatusNote(when (record.outcome) {
            MowingOutcome.COMPLETE -> tr("Mowing finished$extra", "Tonte terminée$extra")
            MowingOutcome.INTERRUPTED -> tr("Mowing interrupted at ${MowingText.percent(record.progress * 100)}: $cause",
                "Tonte interrompue à ${MowingText.percent(record.progress * 100)} : $cause")
            MowingOutcome.RUNNING -> tr("Mowing in progress", "Tonte en cours")
        }, when (record.outcome) { MowingOutcome.COMPLETE -> NoteTone.SUCCESS; MowingOutcome.INTERRUPTED -> NoteTone.WARNING; else -> NoteTone.INFO })
        analysis?.finishing?.let { FinishingOfferGroup(it, onFinishing) }
        MowingReportContent(record, comparison, calibration, score)
        SettingsGroup {
            ToggleRow(tr("Use for estimates", "Utiliser pour les estimations"), !record.excluded, { onExcluded(!it) },
                subtitle = tr("Turn off for a test or an abnormal mowing: it will not correct the next estimates.",
                    "Désactiver pour un essai ou une tonte anormale : elle ne corrigera pas les prochaines estimations."),
                enabled = record.finished)
        }
    }, footer = { SecondaryPillButton(tr("Close", "Fermer"), onDismiss, Modifier.fillMaxWidth()) })
}

@Composable
internal fun MowingReportContent(record: MowingRecord, c: MowingComparison, calibration: MowingCalibration, score: MowingScore? = null) {
    val partial = record.outcome != MowingOutcome.COMPLETE
    score?.let { ScoreGroup(it) }
    SettingsGroup(tr("Mowing", "Tonte")) {
        KeyValueRow("Zones", MowingText.zones(record))
        KeyValueRow(tr("Start", "Début"), MowingText.date(record.startedAt))
        record.endedAt?.let { KeyValueRow(tr("End", "Fin"), MowingText.date(it)) }
        KeyValueRow(tr("Area", "Surface"), "${MowingForecaster.num(record.areaM2, 0)} m²${if (partial) tr(" · ${MowingText.percent(record.progress * 100)} mowed", " · ${MowingText.percent(record.progress * 100)} tondus") else ""}")
        KeyValueRow(tr("Cutting covered", "Coupe parcourue"), "${MowingForecaster.num(record.doneMeters, 0)} / ${MowingForecaster.num(record.cuttingMeters, 0)} m")
        KeyValueRow(tr("Settings", "Réglages"), "${record.mode} · ${record.heightMm} mm · ${MowingForecaster.num(record.speedMps, 2)} m/s")
        if (record.obstacles > 0 || record.resumes > 0) KeyValueRow("Incidents",
            listOfNotNull(record.obstacles.takeIf { it > 0 }?.let { tr("$it obstacle${if (it > 1) "s" else ""} bypassed", "$it obstacle${if (it > 1) "s" else ""} contourné${if (it > 1) "s" else ""}") },
                record.resumes.takeIf { it > 0 }?.let { tr("$it resume${if (it > 1) "s" else ""}", "$it reprise${if (it > 1) "s" else ""}") }).joinToString(" · "))
    }
    SettingsGroup(if (partial) tr("Duration (part mowed)", "Durée (part tondue)") else tr("Duration", "Durée")) {
        KeyValueRow(tr("Estimated", "Estimée"), "≈ ${MowingText.duration(c.expectedSeconds)}${if (partial) tr(" of ≈ ${MowingText.duration(record.forecast.totalSeconds)}", " sur ≈ ${MowingText.duration(record.forecast.totalSeconds)}") else ""}")
        KeyValueRow(tr("Actual", "Réelle"), MowingText.duration(c.actualSeconds), valueColor = verdictColor(c.durationDelta))
        KeyValueRow(tr("of which mowing and travel", "dont tonte et trajets"), MowingText.duration(record.totals.activeSeconds))
        KeyValueRow(tr("of which recharging", "dont recharge"), MowingText.duration(record.totals.chargingSeconds))
        Text(c.durationVerdict, style = MaterialTheme.typography.labelLarge, color = verdictColor(c.durationDelta))
    }
    SettingsGroup("Recharges") {
        KeyValueRow(tr("Estimated", "Estimées"), "≈ ${c.expectedRecharges}${if (partial && record.forecast.recharges != c.expectedRecharges) tr(" of ≈ ${record.forecast.recharges}", " sur ≈ ${record.forecast.recharges}") else ""}")
        KeyValueRow(tr("Actual", "Réelles"), "${c.actualRecharges}", valueColor = verdictColor(if (c.rechargeDelta == 0) 0.0 else 1.0))
        Text(c.rechargeVerdict, style = MaterialTheme.typography.labelLarge, color = verdictColor(if (c.rechargeDelta == 0) 0.0 else 1.0))
    }
    SettingsGroup(tr("Battery and pace", "Batterie et rythme")) {
        KeyValueRow(tr("Battery", "Batterie"), "${record.batteryStart?.let { "$it %" } ?: "—"} → ${record.batteryEnd?.let { "$it %" } ?: "—"}")
        KeyValueRow(tr("Consumed · recharged", "Consommée · rechargée"), "${MowingText.percent(record.totals.batteryUsed)} · ${MowingText.percent(record.totals.batteryCharged)}")
        KeyValueRow(tr("Duration / theoretical run", "Durée / parcours théorique"), c.observedTimeFactor?.let { tr("${MowingText.factor(it)} (planned ${MowingText.factor(record.forecast.timeFactor)})", "${MowingText.factor(it)} (prévu ${MowingText.factor(record.forecast.timeFactor)})") } ?: tr("not enough data", "mesure insuffisante"))
        KeyValueRow(tr("Observed runtime", "Autonomie observée"), c.observedAutonomyMinutes?.let { tr("${MowingText.minutes(it)} (planned ${MowingText.minutes(record.forecast.autonomyMinutes)})", "${MowingText.minutes(it)} (prévu ${MowingText.minutes(record.forecast.autonomyMinutes)})") } ?: tr("not enough data", "mesure insuffisante"))
        KeyValueRow("Recharge 0 → 100 %", c.observedChargeMinutes?.let { tr("${MowingText.minutes(it)} (planned ${MowingText.minutes(record.forecast.chargeMinutes)})", "${MowingText.minutes(it)} (prévu ${MowingText.minutes(record.forecast.chargeMinutes)})") } ?: tr("not enough data", "mesure insuffisante"))
        InfoNote(tr("Duration / theoretical run: time off charge divided by the route distance at the set speed. It includes pivots, links, returns to base and slowdowns.",
            "Durée / parcours théorique : temps hors charge divisé par la distance du trajet à la vitesse réglée. Il inclut pivots, raccords, retours à la base et ralentissements."))
    }
    CalibrationGroup(calibration, if (record.excluded) tr("This mowing is excluded from estimates.", "Cette tonte est écartée des estimations.") else null)
}

/**
 * Mowing Score of a mowing: total out of 100 and the points taken off by each factor, with the value measured, so
 * that the score explains itself.
 */
@Composable
internal fun ScoreGroup(score: MowingScore) {
    SettingsGroup("Mowing Score") {
        KeyValueRow(tr("Score", "Score"), "${score.total} / 100", valueColor = scoreColor(score.total))
        score.contributions.forEach { c ->
            KeyValueRow(c.factor.label, ScoreText.contribution(c), valueColor = if (c.penalty >= 1.0) MammotionColors.Warning else MammotionColors.TextPrimary)
        }
        InfoNote(ScoreText.note(score))
    }
}

@Composable
private fun scoreColor(total: Int) = when {
    total >= 80 -> MammotionColors.GreenDeep
    total >= 60 -> MammotionColors.TextPrimary
    else -> MammotionColors.Warning
}

/** Labels of the Mowing Score. */
object ScoreText {
    fun points(p: Double): String = if (p < 0.5) "0" else "−${Math.round(p)}"

    fun contribution(c: ScoreContribution): String =
        if (!c.measured) c.value else "${c.value} · ${points(c.penalty)} / ${Math.round(c.factor.maxPenalty)}"

    fun note(score: MowingScore): String = tr(
        "100 minus the points taken off: coverage up to 40, overlaps beyond 15 % up to 15, pivots beyond the planned ones up to 15, " +
            "reversing beyond what leaving the base needs up to 10, 4 per incident up to 20." +
            if (score.realCoverage) "" else " Real coverage not measured: coverage estimated from the route covered, overlaps left out.",
        "100 moins les points retirés : couverture jusqu'à 40, chevauchements au-delà de 15 % jusqu'à 15, pivots au-delà des prévus jusqu'à 15, " +
            "marches arrière au-delà de la sortie de base jusqu'à 10, 4 par incident jusqu'à 20." +
            if (score.realCoverage) "" else " Couverture réelle non mesurée : couverture estimée sur le trajet parcouru, chevauchements non comptés.")
}

/** Score of a mowing and finishing offered on its gaps ([FinishingPass.offer]), from the same real coverage. */
internal data class MowingAnalysis(val score: MowingScore, val finishing: FinishingOffer?)

/** Track, real coverage, score and finishing offer of a mowing, computed in the background (null while loading or for a survey). */
@Composable
internal fun rememberMowingAnalysis(record: MowingRecord, loadTrack: suspend (String) -> TaskTrack?, fallbackMap: RobotMap?): MowingAnalysis? {
    var analysis by remember(record.id) { mutableStateOf<MowingAnalysis?>(null) }
    LaunchedEffect(record.id, record.outcome, record.endedAt) {
        if (record.kind != TaskKind.MOW) return@LaunchedEffect
        // Loaded from the calling thread (the positions not yet written live on the main thread), computed aside.
        val track = runCatching { loadTrack(record.id) }.getOrNull()
        analysis = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val coverage = track?.let { runCatching { CoverageMap.of(it, fallbackMap) }.getOrNull() }
            val finishing = coverage?.let { c -> runCatching { FinishingPass.offer(record.id, c, record.outcome == MowingOutcome.COMPLETE) }.getOrNull() }
            MowingAnalysis(MowingScore.of(record, track, coverage), finishing)
        }
    }
    return analysis
}

/** Track, real coverage and score of a mowing, computed in the background (null while loading or for a survey). */
@Composable
internal fun rememberMowingScore(record: MowingRecord, loadTrack: suspend (String) -> TaskTrack?, fallbackMap: RobotMap?): MowingScore? =
    rememberMowingAnalysis(record, loadTrack, fallbackMap)?.score

/**
 * Finishing offered at the end of a mowing whose real coverage left gaps worth a short dedicated route
 * ([FinishingPass]); [onFinishing] null: shown without the button (other robot, map not loaded).
 */
@Composable
internal fun FinishingOfferGroup(offer: FinishingOffer, onFinishing: ((FinishingOffer) -> Unit)?) {
    SettingsGroup(tr("Finishing", "Finition")) {
        KeyValueRow(tr("Left unmowed", "Reste à tondre"), tr("${CoverageText.area(offer.areaM2)} in ${offer.count} gap${if (offer.count > 1) "s" else ""}",
            "${CoverageText.area(offer.areaM2)} en ${offer.count} manque${if (offer.count > 1) "s" else ""}"))
        InfoNote(tr("A short route mows only these gaps, with a lap on their edges, instead of mowing the zones again. Prepared and started from Service → Trial mowing, with the same checks as a mowing.",
            "Un trajet court ne tond que ces manques, avec un tour sur leurs bords, au lieu de retondre les zones. Préparé et lancé depuis Service → Tonte d'essai, avec les mêmes contrôles qu'une tonte."))
        if (onFinishing != null) PrimaryPillButton(tr("Prepare the finishing", "Préparer la finition"), { onFinishing(offer) }, Modifier.fillMaxWidth())
        else InfoNote(tr("Connect the robot of this mowing with its complete map to prepare the finishing.",
            "Connectez le robot de cette tonte avec sa carte complète pour préparer la finition."))
    }
}

/** Two mowings side by side: settings, duration, recharges, coverage and score, factor by factor. */
@Composable
fun MowingComparePage(a: MowingRecord, b: MowingRecord, loadTrack: suspend (String) -> TaskTrack?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val (first, second) = if (a.startedAt <= b.startedAt) a to b else b to a
    val left = rememberMowingScore(first, loadTrack, null)
    val right = rememberMowingScore(second, loadTrack, null)
    PushedPage(tr("Compare two mowings", "Comparer deux tontes"), onBack, modifier) {
        SettingsGroup {
            CompareRow("", MowingText.date(first.startedAt), MowingText.date(second.startedAt), header = true)
            CompareRow("Zones", MowingText.zones(first), MowingText.zones(second))
            CompareRow(tr("Outcome", "Issue"), TaskText.outcome(first), TaskText.outcome(second))
            CompareRow(tr("Settings", "Réglages"), "${first.mode} · ${first.heightMm} mm", "${second.mode} · ${second.heightMm} mm")
            CompareRow(tr("Duration", "Durée"), MowingText.duration(first.totals.totalSeconds), MowingText.duration(second.totals.totalSeconds))
            CompareRow("Recharges", "${first.recharges}", "${second.recharges}")
            CompareRow(tr("Battery used", "Batterie consommée"), MowingText.percent(first.totals.batteryUsed), MowingText.percent(second.totals.batteryUsed))
        }
        SettingsGroup("Mowing Score") {
            CompareRow(tr("Score", "Score"), left?.let { "${it.total} / 100" } ?: "…", right?.let { "${it.total} / 100" } ?: "…", header = true)
            ScoreFactor.entries.forEach { f ->
                CompareRow(f.label, left?.of(f)?.let(ScoreText::contribution) ?: "…", right?.of(f)?.let(ScoreText::contribution) ?: "…")
            }
            if (left != null && right != null) InfoNote(ScoreText.note(if (left.realCoverage) right else left))
        }
    }
}

@Composable
private fun CompareRow(label: String, left: String, right: String, header: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium, color = MammotionColors.TextSecondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Md), verticalAlignment = Alignment.Top) {
            listOf(left, right).forEach {
                Text(it, Modifier.weight(1f), style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = MammotionColors.TextPrimary)
            }
        }
    }
}

/** Parameters used for the next estimates, derived from recent mowings. */
@Composable
fun CalibrationGroup(calibration: MowingCalibration, note: String? = null) {
    SettingsGroup(tr("Next estimates", "Prochaines estimations")) {
        if (calibration.empty) InfoNote(tr("Not enough measurements yet: estimates use the settings (runtime, recharge duration, speed). " +
            "Each real mowing of at least ${(MowingCalibration.MIN_ACTIVE_S / 60).toInt()} min adjusts them.",
            "Pas encore assez de mesures : les estimations utilisent les réglages (autonomie, durée de recharge, vitesse). " +
            "Chaque tonte réelle d'au moins ${(MowingCalibration.MIN_ACTIVE_S / 60).toInt()} min les ajuste."))
        else {
            KeyValueRow(tr("Duration factor", "Facteur de durée"), calibration.timeFactor?.let(MowingText::factor) ?: tr("setting (×1.00)", "réglage (×1,00)"))
            KeyValueRow(tr("Runtime", "Autonomie"), calibration.autonomyMinutes?.let(MowingText::minutes) ?: tr("setting", "réglage"))
            KeyValueRow("Recharge 0 → 100 %", calibration.chargeMinutes?.let(MowingText::minutes) ?: tr("setting", "réglage"))
            InfoNote(tr("Adjusted on ${calibration.records} real mowing${if (calibration.records > 1) "s" else ""} " +
                "(${MowingCalibration.RECENT} most recent at most, weighted by duration).",
                "Ajusté sur ${calibration.records} tonte${if (calibration.records > 1) "s" else ""} réelle${if (calibration.records > 1) "s" else ""} " +
                "(${MowingCalibration.RECENT} plus récentes au plus, pondérées par leur durée)."))
        }
        note?.let { InfoNote(it) }
    }
}

@Composable
private fun verdictColor(delta: Double?) = when {
    delta == null -> MammotionColors.TextPrimary
    Math.abs(delta) <= MowingCalibration.TOLERANCE -> MammotionColors.GreenDeep
    else -> MammotionColors.Warning
}

/**
 * Service → Task history: mowings, surveys and blades-stopped runs, newest first, and estimate calibration (mowings
 * only). A tap opens the detail and the route replay.
 */
@Composable
fun MowingHistoryPage(history: List<MowingRecord>, current: MowingRecord?, robotId: String?, onBack: () -> Unit, onOpen: (String) -> Unit,
                      modifier: Modifier = Modifier, loadTrack: (suspend (String) -> TaskTrack?)? = null) {
    val calibration = remember(history, robotId) { MowingCalibration.from(history, robotId ?: history.firstOrNull()?.robotId) }
    // Comparison: choose two finished mowings, then both side by side.
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }
    val mowings = history.filter { it.finished && it.kind == TaskKind.MOW }
    val pair = picked.mapNotNull { id -> mowings.firstOrNull { it.id == id } }
    if (loadTrack != null && pair.size == 2) {
        MowingComparePage(pair[0], pair[1], loadTrack, onBack = { picked = emptyList() }, modifier = modifier)
        return
    }
    PushedPage(tr("Task history", "Historique des tâches"), onBack, modifier) {
        current?.let { r ->
            SettingsGroup(tr("In progress", "En cours")) {
                TaskRow(r, onOpen)
                KeyValueRow(tr("Duration", "Durée"), "${MowingText.duration(r.totals.totalSeconds)} / ≈ ${MowingText.duration(r.forecast.totalSeconds)}")
                KeyValueRow(if (r.kind == TaskKind.MOW) tr("Mowed · recharges", "Tondu · recharges") else tr("Covered · recharges", "Parcouru · recharges"),
                    "${MowingText.percent(r.progress * 100)} · ${r.recharges} / ≈ ${r.forecast.recharges}")
            }
        }
        CalibrationGroup(calibration)
        if (loadTrack != null && mowings.size >= 2) SettingsGroup {
            if (!picking) NavRow(tr("Compare two mowings", "Comparer deux tontes"), icon = Icons.Rounded.CompareArrows,
                subtitle = tr("Score, coverage, duration and recharges side by side", "Score, couverture, durée et recharges côte à côte")) { picking = true; picked = emptyList() }
            else {
                StatusNote(if (picked.isEmpty()) tr("Choose the first mowing", "Choisir la première tonte") else tr("Choose the second mowing", "Choisir la deuxième tonte"))
                mowings.filter { it.id !in picked }.forEach { r -> TaskRow(r) { id -> picked = picked + id; if (picked.size == 2) picking = false } }
                SecondaryPillButton(tr("Cancel", "Annuler"), { picking = false; picked = emptyList() }, Modifier.fillMaxWidth())
            }
        }
        val finished = history.filter { it.finished }
        SettingsGroup(if (finished.isEmpty()) tr("Tasks", "Tâches") else tr("Tasks · ${finished.size}", "Tâches · ${finished.size}")) {
            if (finished.isEmpty()) InfoNote(tr("No task recorded. Every mowing, signal survey or run with blades stopped appears here with its report and its actual route.",
                "Aucune tâche enregistrée. Chaque tonte, relevé des signaux ou parcours lames arrêtées apparaît ici avec son rapport et son trajet réel."))
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Xs)) { finished.forEach { TaskRow(it, onOpen) } }
        }
        InfoNote(tr("The last ${MowingHistoryStore.MAX_RECORDS} tasks are kept on the phone, with the actual route of the " +
            "${TaskTrackStore.MAX_TRACKS} most recent. Actual duration = mowing, travel and recharging time, excluding interruptions.",
            "Les ${MowingHistoryStore.MAX_RECORDS} dernières tâches sont conservées sur le téléphone, avec le trajet réel des " +
            "${TaskTrackStore.MAX_TRACKS} plus récentes. Durée réelle = temps de tonte, trajets et recharges, hors interruptions."))
    }
}

/** Entry of the Service hub. */
@Composable
fun MowingHistoryRow(history: List<MowingRecord>, onClick: () -> Unit) {
    val last = history.firstOrNull { it.finished }
    NavRow(tr("Task history", "Historique des tâches"), icon = Icons.Rounded.History,
        subtitle = last?.let { tr("Last: ${it.kind.label.lowercase()} · ${MowingText.date(it.startedAt)}", "Dernière : ${it.kind.label.lowercase()} · ${MowingText.date(it.startedAt)}") }
            ?: tr("Reports and actual routes", "Rapports et trajets réels"), onClick = onClick)
}
