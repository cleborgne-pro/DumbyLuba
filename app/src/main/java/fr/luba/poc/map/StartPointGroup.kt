package fr.luba.poc.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.luba.poc.i18n.tr
import fr.luba.poc.planning.StartRequest
import fr.luba.poc.planning.StartSource
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.theme.Dimens

/**
 * Start point of the preview: nearest to the robot by default, the saved start of the first zone if there is one,
 * or a point tapped on the preview map. The route is recomputed on each choice.
 */
@Composable
fun StartPointGroup(map: RobotMap, request: StartRequest?, firstZoneId: Long?, saved: Map<Long, Point>,
                    picking: Boolean, enabled: Boolean, onPick: () -> Unit, onNearest: () -> Unit,
                    onSave: () -> Unit, onForget: () -> Unit) {
    fun zoneName(id: Long?) = map.shapes.firstOrNull { it.id == id }?.name?.trim()?.ifBlank { null } ?: tr("the zone", "la zone")
    val chosenSaved = request?.source == StartSource.CHOSEN && request.zoneId != null && saved[request.zoneId] == request.point
    SettingsGroup(tr("Start point", "Point de départ")) {
        KeyValueRow(tr("Start", "Départ"), when (request?.source) {
            StartSource.SAVED -> tr("Saved for ${zoneName(request.zoneId)}", "Enregistré pour ${zoneName(request.zoneId)}")
            StartSource.CHOSEN -> tr("Chosen on the map · ${zoneName(request.zoneId)}", "Choisi sur la carte · ${zoneName(request.zoneId)}") +
                if (chosenSaved) tr(" (saved)", " (enregistré)") else ""
            else -> tr("Nearest to the robot", "Au plus près du robot")
        })
        if (picking) InfoNote(tr("Tap the preview map inside a chosen zone: the route is recomputed from that point.", "Touchez la carte de l'aperçu dans une zone choisie : le trajet est recalculé depuis ce point."))
        else if (request?.source == StartSource.NEAREST || request == null)
            InfoNote(tr("With no saved start for the first zone, mowing starts nearest to the robot (from the base if it is charging or its position is unknown).", "Sans départ enregistré pour la première zone, la tonte commence au plus près du robot (depuis la base s'il est en charge ou si sa position est inconnue)."))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(if (picking) tr("Cancel", "Annuler") else tr("Change", "Changer"), onPick, Modifier.weight(1f), enabled = enabled, icon = Icons.Rounded.TouchApp)
            if (request != null && request.source != StartSource.NEAREST)
                SecondaryPillButton(tr("Nearest", "Au plus près"), onNearest, Modifier.weight(1f), enabled = enabled && !picking, icon = Icons.Rounded.MyLocation)
        }
        if (request?.source == StartSource.CHOSEN && !chosenSaved)
            SecondaryPillButton(tr("Save for ${zoneName(request.zoneId)}", "Enregistrer pour ${zoneName(request.zoneId)}"), onSave, Modifier.fillMaxWidth(),
                enabled = enabled && !picking, icon = Icons.Rounded.BookmarkAdd)
        if (firstZoneId != null && saved.containsKey(firstZoneId))
            TextButton(onForget, enabled = enabled && !picking) { Text(tr("Forget the saved start of ${zoneName(firstZoneId)}", "Oublier le départ enregistré de ${zoneName(firstZoneId)}")) }
    }
}
