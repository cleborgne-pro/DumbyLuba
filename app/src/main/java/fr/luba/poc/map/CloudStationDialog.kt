package fr.luba.poc.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import fr.luba.poc.auth.CloudReturnPhase
import fr.luba.poc.auth.CloudReturnUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.MammotionColors

/**
 * "Charging station" without a Bluetooth link, Mammotion account signed in: only the native Mammotion return is
 * possible. The request goes through the account, the firmware computes the route and the docking; the state is
 * followed until charging contact and the return can be cancelled.
 */
@Composable
fun CloudStationDialog(ret: CloudReturnUi, robotName: String?, onReturn: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val colors = MammotionColors
    var supervised by remember { mutableStateOf(false) }
    LubaDialog(onDismissRequest = onDismiss, title = tr("Charging station", "Station de charge"), content = {
        Text(tr("Native Mammotion return through the account", "Retour natif Mammotion par le compte"), style = MaterialTheme.typography.labelLarge)
        Text(tr("Bluetooth not connected: the return request is sent through the Mammotion account${robotName?.let { " to $it" } ?: ""}. " +
            "The Mammotion firmware computes the route and handles docking, as in the Mammotion app. " +
            "Stay nearby: stopping goes through the cloud (a few seconds of latency) or the robot's STOP button.",
            "Bluetooth non connecté : la demande de retour est envoyée par le compte Mammotion${robotName?.let { " à $it" } ?: ""}. " +
            "Le firmware Mammotion calcule le trajet et assure l’accostage, comme dans l’app Mammotion. " +
            "Restez à proximité : l’arrêt passe par le cloud (quelques secondes de latence) ou par le bouton STOP du robot."),
            style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
        // Robot already on its base (fresh account report): the native return has nothing to do.
        val onBase = ret.presence == StationPresence.ON_BASE && !ret.active
        if (onBase) InfoNote(tr("Robot on its base: no return needed.", "Robot sur sa base : aucun retour nécessaire."))
        else LubaCheckRow(tr("I am supervising the robot and the way to the station is clear", "Je supervise le robot et l’accès à la station est dégagé"), supervised, { supervised = it }, enabled = !ret.active)
        if (ret.active) LubaProgress(Modifier.fillMaxWidth())
        if (ret.status.isNotBlank()) Text(ret.status, style = MaterialTheme.typography.bodySmall,
            color = when (ret.phase) {
                CloudReturnPhase.FAILED -> colors.DangerDeep
                CloudReturnPhase.DOCKED -> colors.GreenDeep
                else -> colors.TextSecondary
            })
    }, footer = {
        if (ret.cancellable) DangerPillButton(tr("Stop the Mammotion return", "Arrêter le retour Mammotion"), onCancel, Modifier.fillMaxWidth())
        if (ret.presence.canRecharge || ret.active) PrimaryPillButton(tr("Recharge (native Mammotion)", "Recharger (Mammotion natif)"),
            { supervised = false; onReturn() }, Modifier.fillMaxWidth(), enabled = supervised && !ret.active, icon = Icons.Rounded.EvStation)
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(tr("Close", "Fermer")) }
    })
}
