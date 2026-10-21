package fr.luba.poc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.maintenance.BladeLimitSource
import fr.luba.poc.maintenance.BladeMaintenanceSettings
import fr.luba.poc.maintenance.BladeWear
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Service → Maintenance: counters read over Bluetooth (`rpt_maintain`, no Mammotion account needed) and blade
 * reminder. Read only towards the robot: the blade change is noted on the phone, the robot counter is not reset.
 */
@Composable
internal fun MaintenancePage(state: RobotUi, back: () -> Unit, modifier: Modifier = Modifier) {
    val settings by BladeMaintenanceSettings.flow.collectAsState()
    val robotId = state.selected?.address
    val m = state.telemetry.maintenance
    var confirm by remember { mutableStateOf(false) }
    PushedPage(ServicePage.MAINTENANCE.title, back, modifier, bottomBar = {
        PrimaryPillButton(tr("Blades replaced", "Lames changées"), { confirm = true }, Modifier.fillMaxWidth(), enabled = m != null && robotId != null)
    }) {
        if (m == null) InfoNote(if (state.ready) tr("Waiting for the robot's maintenance counters (sent about every second).", "En attente des compteurs d'entretien du robot (envoyés environ chaque seconde).")
            else tr("Connect the robot (gateway or direct Bluetooth) to read its maintenance counters.", "Connectez le robot (passerelle ou Bluetooth direct) pour lire ses compteurs d'entretien."))
        m?.let { counters ->
            val status = BladeWear.status(counters, settings.changes[robotId], settings.intervalHours)
            SettingsGroup(tr("Blades", "Lames")) {
                val ratio = status.ratio
                KeyValueRow(tr("Used since the change", "Utilisées depuis le changement").takeIf { status.source == BladeLimitSource.LOCAL } ?: tr("Robot blade counter", "Compteur lames du robot"),
                    "${Format.num(status.usedHours, 1)} h" + (status.limitSeconds?.let { " / ${(it / 3600.0).roundToInt()} h" } ?: ""),
                    valueColor = if (status.due) MammotionColors.Danger else MammotionColors.TextPrimary)
                ratio?.let { LubaProgress(it.toFloat().coerceIn(0f, 1f), Modifier.fillMaxWidth()) }
                when (status.source) {
                    BladeLimitSource.LOCAL -> KeyValueRow(tr("Last change", "Dernier changement"),
                        SimpleDateFormat("dd/MM/yyyy", fr.luba.poc.i18n.I18n.locale).format(Date(requireNotNull(status.changedAt))))
                    BladeLimitSource.ROBOT -> InfoNote(tr("Threshold set in the Mammotion app. Tap Blades replaced after a change to follow your own interval.",
                        "Seuil réglé dans l'app Mammotion. Appuyez sur Lames changées après un changement pour suivre votre propre intervalle."))
                    BladeLimitSource.NONE -> InfoNote(tr("Date of the last change unknown: tap Blades replaced when you replace them; a reminder is then sent after the interval below.",
                        "Date du dernier changement inconnue : appuyez sur Lames changées quand vous les remplacez ; un rappel est ensuite envoyé après l'intervalle ci-dessous."))
                }
                if (status.due) SafetyNotice(status.detail + tr(": replace them (robot off, gloves).", " : changez-les (robot éteint, gants)."))
                SliderRow(tr("Change every", "Changer toutes les"), settings.intervalHours.toFloat(), { BladeMaintenanceSettings.setInterval((it / 10).roundToInt() * 10) },
                    BladeMaintenanceSettings.HOURS.first.toFloat()..BladeMaintenanceSettings.HOURS.last.toFloat(),
                    valueLabel = tr("${settings.intervalHours} h of rotation", "${settings.intervalHours} h de rotation"),
                    steps = (BladeMaintenanceSettings.HOURS.last - BladeMaintenanceSettings.HOURS.first) / 10 - 1)
            }
            SettingsGroup(tr("Robot counters", "Compteurs du robot")) {
                KeyValueRow(tr("Distance traveled", "Distance parcourue"), "${Format.num(counters.mileageMeters / 1000.0, 1)} km")
                KeyValueRow(tr("Working time", "Temps de travail"), "${Format.num(counters.workSeconds / 3600.0, 1)} h")
                KeyValueRow(tr("Battery cycles", "Cycles de batterie"), "${counters.batteryCycles}")
                KeyValueRow(tr("Blade counter (robot)", "Compteur lames (robot)"), "${Format.num(counters.bladeUsedSeconds / 3600.0, 1)} h" +
                    if (counters.bladeWarnSeconds > 0) tr(" / alert at ${(counters.bladeWarnSeconds / 3600.0).roundToInt()} h", " / alerte à ${(counters.bladeWarnSeconds / 3600.0).roundToInt()} h") else "")
                state.telemetry.lastMaintenanceAt?.let { KeyValueRow(tr("Read at", "Lu à"), SimpleDateFormat("HH:mm:ss", fr.luba.poc.i18n.I18n.locale).format(Date(it))) }
            }
        }
    }
    if (confirm && m != null && robotId != null) LubaDialog({ confirm = false }, title = tr("Blades replaced?", "Lames changées ?"),
        content = {
            Text(tr("The robot's blade counter (${Format.num(m.bladeUsedSeconds / 3600.0, 1)} h) becomes the reference: the reminder comes back after ${settings.intervalHours} h of rotation. Nothing is sent to the robot.",
                "Le compteur lames du robot (${Format.num(m.bladeUsedSeconds / 3600.0, 1)} h) devient la référence : le rappel revient après ${settings.intervalHours} h de rotation. Rien n'est envoyé au robot."),
                style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextPrimary)
        },
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(tr("Cancel", "Annuler"), { confirm = false }, Modifier.weight(1f))
                PrimaryPillButton(tr("Confirm", "Confirmer"), {
                    BladeMaintenanceSettings.markChanged(robotId, m.bladeUsedSeconds, System.currentTimeMillis()); confirm = false
                }, Modifier.weight(1f))
            }
        })
}
