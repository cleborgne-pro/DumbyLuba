package fr.luba.poc.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.EncounterSheet
import fr.luba.poc.map.MapExtraLayer
import fr.luba.poc.map.MapExtraLayers
import fr.luba.poc.ui.components.*
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors

/**
 * Service → Obstacles: encountered obstacles (contacts with photo), presumed obstacles (repeated contacts) and the
 * difficulty heatmap, with how they are used in later routes.
 */
@Composable
internal fun DifficultiesGroup(robot: RobotBle, state: RobotUi) {
    val ui = state.difficulties
    val layers by MapExtraLayers.flow.collectAsState()
    var clearing by remember { mutableStateOf(false) }
    val active = ui.events.filter { !it.dismissed }
    SettingsGroup(tr("Encountered obstacles and difficulties", "Obstacles rencontrés et difficultés")) {
        if (active.isEmpty()) InfoNote(tr("No difficulty recorded: contacts, blockages, wheel slip, lifts and deviations " +
            "during autonomous moves are recorded here and on the map.", "Aucune difficulté consignée : contacts, blocages, patinage, soulèvements et écarts " +
            "pendant les déplacements autonomes s'inscrivent ici et sur la carte."))
        DifficultyKind.entries.forEach { kind ->
            val count = active.count { it.kind == kind }
            if (count > 0) KeyValueRow(kind.label, "$count")
        }
        if (ui.presumed.isNotEmpty()) {
            LubaDivider()
            Text(tr("Presumed obstacles (${ui.presumed.size})", "Obstacles présumés (${ui.presumed.size})"), style = MaterialTheme.typography.titleSmall)
            ui.presumed.forEach { o ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    Column(Modifier.weight(1f)) {
                        Text(o.label, style = MaterialTheme.typography.bodyMedium)
                        Text(tr("${Format.num(o.x, 1)} / ${Format.num(o.y, 1)} m · radius ${Format.num(o.radiusM, 2)} m", "${Format.num(o.x, 1)} / ${Format.num(o.y, 1)} m · rayon ${Format.num(o.radiusM, 2)} m"),
                            style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                    }
                    SecondaryPillButton(tr("Save", "Enregistrer"), {
                        robot.saveObstacle(o.copy(id = java.util.UUID.randomUUID().toString(), label = tr("Encountered obstacle (${o.label.substringAfter("(").removeSuffix(")")})", "Obstacle rencontré (${o.label.substringAfter("(").removeSuffix(")")})")))
                    })
                }
            }
        }
        val encounters = ui.encounters
        if (encounters.isNotEmpty()) {
            LubaDivider()
            Text(tr("Encountered obstacles (${encounters.size})", "Obstacles rencontrés (${encounters.size})"), style = MaterialTheme.typography.titleSmall)
            encounters.take(15).forEach { e ->
                val photo = MapExtraLayers.photoFor(e, state.obstaclePhoto.photos)
                Row(Modifier.fillMaxWidth().clickable { MapExtraLayers.open(e) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                    if (photo != null) PhotoImage(photo, Modifier.width(80.dp).aspectRatio(4f / 3f), maxSide = 320)
                    Column(Modifier.weight(1f)) {
                        Text(java.text.SimpleDateFormat("dd/MM HH:mm", fr.luba.poc.i18n.I18n.locale).format(java.util.Date(e.atMs)),
                            style = MaterialTheme.typography.titleSmall)
                        val side = e.side?.takeIf { it != 0 }?.let { if (it > 0) tr(" · bypassed on the left", " · contourné par la gauche") else tr(" · bypassed on the right", " · contourné par la droite") }.orEmpty()
                        Text("${e.source.ifBlank { "contact" }} · ${Format.num(e.point.x, 1)} / ${Format.num(e.point.y, 1)} m$side" +
                            (if (e.inner == true && side.isNotEmpty()) tr(", inner side", ", côté intérieur") else ""),
                            style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
                    }
                }
            }
        }
        if (ui.saveError) InfoNote(tr("Difficulty log not saved: it will be written again at the next difficulty.", "Journal des difficultés non enregistré : il sera réécrit à la prochaine difficulté."))
        if (ui.events.isNotEmpty()) SecondaryPillButton(tr("Clear the difficulty log", "Effacer le journal des difficultés"), { clearing = true }, Modifier.fillMaxWidth())
    }
    // Settings kept apart from the data: use in routes, then display on the map.
    SettingsGroup(tr("Use in routes", "Prise en compte dans les trajets")) {
        ToggleRow(tr("Avoid obstacles hit several times", "Éviter les obstacles rencontrés plusieurs fois"), ui.avoidPresumed, robot::setAvoidPresumedObstacles,
            subtitle = tr("Two or more contacts at the same place (less than 75 cm apart, separate moves): presumed obstacle, " +
                "avoided by routes prepared afterwards like a saved obstacle (radius 0.2 m at least, margin 0.3 m).",
                "Deux contacts ou plus au même endroit (à moins de 75 cm, déplacements distincts) : obstacle présumé, " +
                "évité par les trajets préparés ensuite comme un obstacle enregistré (rayon 0,2 m au moins, marge 0,3 m)."))
        ToggleRow(tr("Bypass difficult areas when moving", "Contourner les zones difficiles en déplacement"), ui.avoidHot, robot::setAvoidDifficultAreas,
            subtitle = tr("With blades stopped (departure, return to base, recharge), the route avoids the red areas of the heatmap " +
                "if the detour stays reasonable (×1.5 + 4 m at most). Obstacle bypasses also avoid known difficulties.",
                "Lames arrêtées (départ, retour à la base, recharge), le trajet évite les zones rouges de la heatmap " +
                "si le détour reste raisonnable (×1,5 + 4 m au plus). Les contournements d'obstacle évitent aussi les difficultés connues."))
    }
    SettingsGroup(tr("Map layers", "Calques de la carte")) {
        MapExtraLayer.entries.forEach { layer ->
            ToggleRow(tr("“${layer.label}” layer", "Calque « ${layer.label} »"), layer in layers, { MapExtraLayers.toggle(layer) }, subtitle = layer.description)
        }
    }
    EncounterSheet(ui, state.obstaclePhoto.photos, state.obstacles)
    if (clearing) LubaDialog(onDismissRequest = { clearing = false }, title = tr("Clear the log?", "Effacer le journal ?"), content = {
        Text(tr("The ${ui.events.size} recorded difficulties, the heatmap and the presumed obstacles will be deleted. " +
            "Saved obstacles and photos are kept.", "Les ${ui.events.size} difficultés consignées, la heatmap et les obstacles présumés seront supprimés. " +
            "Les obstacles enregistrés et les photos sont conservés."), style = MaterialTheme.typography.bodyMedium)
    }, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryPillButton(tr("Cancel", "Annuler"), { clearing = false }, Modifier.weight(1f))
            DangerPillButton(tr("Clear", "Effacer"), { robot.clearDifficulties(); clearing = false }, Modifier.weight(1f))
        }
    })
}
