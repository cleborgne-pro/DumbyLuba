package fr.luba.poc.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import fr.luba.poc.i18n.tr

/**
 * Firmware update through the account: offered version, start confirmed by the user and live progress
 * (percentage, stage, final state). "Stop following" does not interrupt the update.
 */
@Composable
fun FirmwareUpdateRows(state: CloudUi, onStart: () -> Unit, onFollow: () -> Unit, onStopFollow: () -> Unit, now: Long = System.currentTimeMillis()) {
    val colors = MammotionColors
    val f = state.firmwareCheck
    val u = state.firmwareUpdate
    var confirm by remember { mutableStateOf(false) }
    var supervised by remember { mutableStateOf(false) }

    if (u.target != null && u.phase != FirmwarePhase.IDLE) {
        KeyValueRow(tr("Update", "Mise à jour"), tr("to ${u.target}", "vers ${u.target}"), valueColor = when (u.phase) {
            FirmwarePhase.DONE -> colors.Green
            FirmwarePhase.FAILED, FirmwarePhase.LOST -> colors.Danger
            else -> colors.Warning
        })
        if (u.active) {
            val p = u.progress
            if (p != null) LubaProgress(p / 100f, Modifier.fillMaxWidth()) else LubaProgress(Modifier.fillMaxWidth())
            KeyValueRow(tr("Progress", "Avancement"), p?.let { "$it %" } ?: tr("waiting for the robot", "en attente du robot"))
            if (u.stage.isNotBlank()) KeyValueRow(tr("Stage", "Étape"), u.stage)
            u.startedAt?.let { KeyValueRow(tr("Started", "Démarrée"), tr("${((now - it) / 60_000).coerceAtLeast(0)} min ago", "il y a ${((now - it) / 60_000).coerceAtLeast(0)} min")) }
        }
        Text(u.status, style = MaterialTheme.typography.bodySmall,
            color = if (u.phase == FirmwarePhase.FAILED || u.phase == FirmwarePhase.LOST) colors.DangerDeep else colors.TextSecondary)
        if (u.active) {
            InfoNote(tr("The robot downloads then installs the firmware and restarts: do not use or move it until it is finished.", "Le robot télécharge puis installe le firmware et redémarre : ne pas l’utiliser ni le déplacer avant la fin."))
            SecondaryPillButton(tr("Stop following", "Arrêter le suivi"), onStopFollow, Modifier.fillMaxWidth())
        }
    }
    if (!u.active) {
        val refusal = FirmwareUpdatePolicy.refusal(f, state.details)
        when {
            f == null -> {}
            f.upgrading -> {
                KeyValueRow(tr("Update", "Mise à jour"), tr("in progress", "en cours") + (f.progress?.takeIf { it > 0 }?.let { " · $it %" } ?: ""), valueColor = colors.Warning)
                SecondaryPillButton(tr("Follow progress", "Suivre l’avancement"), onFollow, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            f.upgradeable && f.latest != null -> {
                if (u.phase != FirmwarePhase.DONE) KeyValueRow(tr("Update available", "Mise à jour disponible"), f.latest, valueColor = colors.Green)
                PrimaryPillButton(tr("Update to ${f.latest}", "Mettre à jour vers ${f.latest}"), { supervised = false; confirm = true }, Modifier.fillMaxWidth(),
                    enabled = refusal == null && !state.busy && !state.nativeReturn.active, icon = Icons.Rounded.SystemUpdate)
                if (refusal != null) Text(refusal, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
            }
            else -> KeyValueRow(tr("Update", "Mise à jour"), tr("up to date", "à jour"))
        }
        f?.cause?.takeIf { f.upgradeable.not() && !f.upgrading && it.isNotBlank() }?.let {
            Text(tr("Cloud: $it", "Cloud : $it"), style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        }
        if (u.phase == FirmwarePhase.IDLE && u.status.isNotBlank())
            Text(u.status, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
    }

    if (confirm && f?.latest != null) LubaDialog(onDismissRequest = { confirm = false }, title = tr("Update the robot?", "Mettre à jour le robot ?"), content = {
        KeyValueRow(tr("Installed version", "Version installée"), f.current ?: state.details.firmware ?: "—")
        KeyValueRow(tr("New version", "Nouvelle version"), f.latest, valueColor = colors.Green)
        f.releaseNote?.let { Text(it.take(600), style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary) }
        Text(tr("The update is started by the Mammotion account: the robot downloads the firmware, installs it, then restarts. " +
            "It stays still and unavailable during the whole operation. Progress is shown here.",
            "La mise à jour est lancée par le compte Mammotion : le robot télécharge le firmware, l’installe puis redémarre. " +
            "Il reste immobile et indisponible pendant toute l’opération. L’avancement s’affiche ici."),
            style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
        LubaCheckRow(tr("The robot is stopped (ideally on its base), the battery is sufficient, and I will not use it during the update", "Le robot est à l’arrêt (idéalement sur sa base), batterie suffisante, et je ne l’utiliserai pas pendant la mise à jour"),
            supervised, { supervised = it })
    }, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Cancel", "Annuler"), { confirm = false }, Modifier.weight(1f))
            PrimaryPillButton(tr("Update", "Mettre à jour"), { confirm = false; onStart() }, Modifier.weight(1f), enabled = supervised)
        }
    })
}
