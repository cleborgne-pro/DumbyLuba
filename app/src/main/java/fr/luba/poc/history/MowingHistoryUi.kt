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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import fr.luba.poc.i18n.I18n
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.NoteTone
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
fun MowingReportDialog(record: MowingRecord, history: List<MowingRecord>, onExcluded: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val comparison = remember(record) { MowingComparison.of(record) }
    val calibration = remember(history, record.robotId) { MowingCalibration.from(history, record.robotId) }
    LubaDialog(onDismissRequest = onDismiss, title = tr("Mowing report", "Rapport de tonte"), content = {
        val extra = record.reason?.let { if (finishedReason(it, withRoute = false)) "" else " · ${reasonText(it)}" } ?: ""
        val cause = record.reason?.let(::reasonText) ?: tr("unknown cause", "cause inconnue")
        StatusNote(when (record.outcome) {
            MowingOutcome.COMPLETE -> tr("Mowing finished$extra", "Tonte terminée$extra")
            MowingOutcome.INTERRUPTED -> tr("Mowing interrupted at ${MowingText.percent(record.progress * 100)}: $cause",
                "Tonte interrompue à ${MowingText.percent(record.progress * 100)} : $cause")
            MowingOutcome.RUNNING -> tr("Mowing in progress", "Tonte en cours")
        }, when (record.outcome) { MowingOutcome.COMPLETE -> NoteTone.SUCCESS; MowingOutcome.INTERRUPTED -> NoteTone.WARNING; else -> NoteTone.INFO })
        MowingReportContent(record, comparison, calibration)
        SettingsGroup {
            ToggleRow(tr("Use for estimates", "Utiliser pour les estimations"), !record.excluded, { onExcluded(!it) },
                subtitle = tr("Turn off for a test or an abnormal mowing: it will not correct the next estimates.",
                    "Désactiver pour un essai ou une tonte anormale : elle ne corrigera pas les prochaines estimations."),
                enabled = record.finished)
        }
    }, footer = { SecondaryPillButton(tr("Close", "Fermer"), onDismiss, Modifier.fillMaxWidth()) })
}

@Composable
internal fun MowingReportContent(record: MowingRecord, c: MowingComparison, calibration: MowingCalibration) {
    val partial = record.outcome != MowingOutcome.COMPLETE
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
                      modifier: Modifier = Modifier) {
    val calibration = remember(history, robotId) { MowingCalibration.from(history, robotId ?: history.firstOrNull()?.robotId) }
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
