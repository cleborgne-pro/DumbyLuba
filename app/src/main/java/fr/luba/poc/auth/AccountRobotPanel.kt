package fr.luba.poc.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import fr.luba.poc.i18n.tr

/**
 * Everything the Mammotion account can read about the robot, without any command: state,
 * current task, positioning, network, maintenance, versions and mowing history.
 */
@Composable
fun AccountRobotPanel(state: CloudUi, onRefresh: () -> Unit, now: Long = System.currentTimeMillis(),
                      onStartFirmwareUpdate: () -> Unit = {}, onFollowFirmwareUpdate: () -> Unit = {}, onStopFirmwareFollow: () -> Unit = {}) {
    val d = state.details
    val colors = MammotionColors
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Md)) {
        SettingsGroup(tr("Robot state (account)", "État du robot (compte)")) {
            KeyValueRow(tr("State", "État"), RobotStates.label(d.sysStatus, d.chargeState))
            KeyValueRow(tr("Battery", "Batterie"), d.battery?.let { "$it %" + if (d.chargeState in setOf(1, 2)) tr(" · charging", " · en charge") else "" } ?: "—")
            if (d.lockState != null && d.lockState != 0) KeyValueRow(tr("Lock", "Verrouillage"), tr("locked (${d.lockState})", "verrouillé (${d.lockState})"), valueColor = colors.Warning)
            if (d.batteryHeat == true) KeyValueRow(tr("Battery", "Batterie"), tr("heating on", "chauffage actif"))
            d.cutterRpm?.let { KeyValueRow(tr("Blades", "Lames"), tr("$it rpm", "$it tr/min")) }
            KeyValueRow(tr("Last read", "Dernière lecture"), d.updatedAt?.let { ago(now - it) } ?: tr("none", "aucune"))
            SecondaryPillButton(tr("Refresh from the account", "Actualiser depuis le compte"), onRefresh, Modifier.fillMaxWidth(), enabled = !state.busy, icon = Icons.Rounded.Refresh)
        }

        d.task?.takeIf { it.active }?.let { t ->
            SettingsGroup(tr("Current task", "Tâche en cours")) {
                KeyValueRow(tr("Progress", "Avancement"), "${t.percent} %")
                LubaProgress(t.percent / 100f, Modifier.fillMaxWidth())
                KeyValueRow(tr("Task area", "Surface de la tâche"), "${t.areaM2} m²")
                if (t.totalMinutes > 0) KeyValueRow(tr("Time", "Temps"), tr("${t.elapsedMinutes} min elapsed · ${t.leftMinutes} min left", "${t.elapsedMinutes} min écoulées · ${t.leftMinutes} min restantes"))
                t.knifeHeightMm?.let { KeyValueRow(tr("Cutting height", "Hauteur de coupe"), "$it mm") }
                t.speedMps?.let { KeyValueRow(tr("Speed", "Vitesse"), "${Format.num(it, 2)} m/s") }
            }
        }

        if (d.rtk != null || d.network != null) SettingsGroup(tr("Positioning and network", "Positionnement et réseau")) {
            d.rtk?.let { k ->
                KeyValueRow(tr("RTK solution", "Solution RTK"), RobotStates.rtk(k.status))
                KeyValueRow("Satellites", tr("L1 ${k.l1} · L2 ${k.l2} · common view ${k.coViewL1}/${k.coViewL2}", "L1 ${k.l1} · L2 ${k.l2} · en vue commune ${k.coViewL1}/${k.coViewL2}"))
                KeyValueRow(tr("Correction age", "Âge des corrections"), "${k.ageSeconds} s" + if (k.rtcmReady) tr(" · RTCM ready", " · RTCM prêt") else "")
                if (k.roverScore != null) KeyValueRow("Score", "robot ${k.roverScore} · base ${k.baseScore ?: "—"}")
                if (k.baseMoved == true) KeyValueRow(tr("RTK base", "Base RTK"), tr("moved", "déplacée"), valueColor = colors.Warning)
            }
            d.network?.let { n ->
                KeyValueRow(tr("Robot connection", "Connexion du robot"), RobotStates.net(n.usedNet))
                n.wifiRssi?.let { KeyValueRow("Wi-Fi", "$it dBm") }
                n.mobileRssi?.let { KeyValueRow("4G", "$it dBm") }
            }
        }

        d.maintenance?.let { m ->
            SettingsGroup(tr("Maintenance", "Entretien")) {
                KeyValueRow(tr("Distance traveled", "Distance parcourue"), "${Format.num(m.mileageMeters / 1000.0, 1)} km")
                KeyValueRow(tr("Working time", "Temps de travail"), "${Format.num(m.workSeconds / 3600.0, 1)} h")
                KeyValueRow(tr("Battery cycles", "Cycles de batterie"), "${m.batteryCycles}")
                KeyValueRow(tr("Blades", "Lames"), "${Format.num(m.bladeUsedSeconds / 3600.0, 1)} h" +
                    if (m.bladeWarnSeconds > 0) tr(" / alert at ${Format.num(m.bladeWarnSeconds / 3600.0, 0)} h", " / alerte à ${Format.num(m.bladeWarnSeconds / 3600.0, 0)} h") else "",
                    valueColor = if ((m.bladeWear ?: 0.0) >= 1.0) colors.Danger else colors.TextPrimary)
                m.bladeWear?.let { LubaProgress(it.toFloat().coerceIn(0f, 1f), Modifier.fillMaxWidth()) }
            }
        }

        if (d.firmware != null || state.firmwareCheck != null || d.base != null || state.firmwareUpdate.active) SettingsGroup(tr("Software", "Logiciel")) {
            val f = state.firmwareCheck
            KeyValueRow("Firmware", f?.current ?: d.firmware ?: "—")
            // Update: confirmed start and live progress (FirmwareUpdateUi.kt).
            FirmwareUpdateRows(state, onStartFirmwareUpdate, onFollowFirmwareUpdate, onStopFirmwareFollow, now)
            f?.releaseNote?.takeIf { f.upgradeable }?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary) }
            d.base?.let { KeyValueRow(tr("RTK base", "Base RTK"), "version ${it.version}") }
            d.modules.take(12).forEach { KeyValueRow(it.identify.ifBlank { "module ${it.type}" }, it.version) }
        }

        if (state.workRecords.isNotEmpty()) SettingsGroup(tr("Latest mowings", "Dernières tontes")) {
            state.workRecords.take(10).forEach { w ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${w.start?.let(::date) ?: tr("unknown date", "date inconnue")} · ${w.typeLabel} · ${w.resultLabel}",
                        style = MaterialTheme.typography.bodyMedium, color = colors.TextPrimary)
                    Text(listOfNotNull(w.areaM2?.let { "${Format.num(it, 0)} m²" }, w.progress?.let { "$it %" },
                        w.minutes?.let { "${Format.num(it, 0)} min" }, w.name.takeIf { it != tr("Mowing", "Tonte") }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
                }
            }
        }

        // Failed account reads: reported, the other data stay displayed.
        if (state.accountReadErrors.isNotEmpty()) StatusNote(state.accountReadErrors.joinToString("\n"), NoteTone.WARNING)
    }
}

private fun ago(ms: Long): String = when {
    ms < 0 -> tr("just now", "à l'instant")
    ms < 60_000 -> tr("${ms / 1000} s ago", "il y a ${ms / 1000} s")
    ms < 3_600_000 -> tr("${ms / 60_000} min ago", "il y a ${ms / 60_000} min")
    else -> tr("${ms / 3_600_000} h ago", "il y a ${ms / 3_600_000} h")
}

private fun date(ms: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag(tr("en-GB", "fr-FR"))).format(Date(ms))
