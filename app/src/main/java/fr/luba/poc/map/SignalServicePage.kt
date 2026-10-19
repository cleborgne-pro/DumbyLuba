package fr.luba.poc.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.PushedPage
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service → Signal surveys. The survey is automatic: every robot movement feeds the continuous map ([SignalLiveMap]).
 * The page shows its state and a preview, opens the history, overlays a survey on the map and, optionally, prepares a
 * dedicated survey route (full loop or grid, blades stopped) on the map.
 */
@Composable
fun SignalSurveyPage(state: RobotUi, onBack: () -> Unit, onShowOnMap: () -> Unit, onDedicatedRoute: () -> Unit) {
    val context = LocalContext.current
    val live = state.signalSurvey.takeIf { it.live && it.samples.isNotEmpty() }
    val store = remember { SignalSurveyStore(context.filesDir) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var signal by rememberSaveable { mutableStateOf(SurveySignal.WIFI) }
    var mount by remember { mutableStateOf(ReliefSettings.mount(context)) }
    val calibration = remember { ReliefSettings.calibration(context) }
    val samples = live?.samples.orEmpty()
    val relief by produceState<ReliefResult?>(null, samples, mount, calibration) {
        value = withContext(Dispatchers.Default) { ReliefModel.result(samples, mount, calibration) }
    }
    val view = remember(samples, signal, relief) { signalReliefView(samples, signal, relief, true) }
    val layers = remember(samples, relief) { availableLayers(samples, relief != null, signal) }
    val mapForPreview = live?.mapSnapshot ?: state.robotMap
    val sameMap = live != null && SignalOverlay.resolve(state.robotMap, live, null) != null
    PushedPage(tr("Signal surveys", "Relevés de signaux"), onBack, bottomBar = {
        PrimaryPillButton(tr("Show on map", "Afficher sur la carte"), { SignalOverlay.show(null); onShowOnMap() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Layers)
    }) {
        InfoNote(tr("Automatic survey: during every robot movement (mowing, move to a point, return to base, " +
            "manual control), the app records the Wi-Fi, RTK, Bluetooth, mobile network, satellites and, " +
            "with the gateway phone mounted on the robot, its tilt for the terrain. At most one reading per second and " +
            "every 30 cm, without any command sent to the robot.",
            "Relevé automatique : pendant chaque déplacement du robot (tonte, déplacement au point, retour à la base, " +
            "pilotage manuel), l'application enregistre le Wi-Fi, le RTK, le Bluetooth, le réseau mobile, les satellites et, " +
            "avec le téléphone passerelle posé sur le robot, son inclinaison pour le relief. Une mesure par seconde au plus et " +
            "tous les 30 cm, sans aucune commande envoyée au robot."))
        SettingsGroup(tr("Continuous map", "Carte continue")) {
            if (live == null) InfoNote(tr("No readings yet: they are added as soon as the robot moves on a complete map.", "Aucune mesure pour l'instant : elles s'ajoutent dès que le robot se déplace sur une carte complète."))
            else {
                KeyValueRow("Version", surveyDate(live.startedAt))
                val cells = remember(samples) { samples.map { kotlin.math.floor(it.point.x) to kotlin.math.floor(it.point.y) }.distinct().size }
                KeyValueRow(tr("Readings", "Mesures"), tr("${Format.plural(samples.size, "reading", "readings")} · 1 m ${Format.plural(cells, "cell", "cells")}", "${Format.plural(samples.size, "mesure", "mesures")} · ${Format.plural(cells, "cellule", "cellules")} de 1 m"))
                KeyValueRow(tr("Layers", "Calques"), layers.joinToString(" · ") { it.shortLabel() })
                samples.maxOfOrNull { it.at }?.let { KeyValueRow(tr("Last reading", "Dernière mesure"), surveyDate(it)) }
                KeyValueRow(tr("Map", "Carte"), if (sameMap) tr("current robot map", "carte actuelle du robot") else tr("other map (preview on its original map)", "autre carte (aperçu sur sa carte d'origine)"))
            }
            if (state.surveySaveError) StatusNote(tr("Could not save the survey", "Sauvegarde du relevé impossible"), NoteTone.DANGER)
        }
        if (live != null) SettingsGroup(tr("Preview", "Aperçu")) {
            MapLive(mapForPreview, modifier = Modifier.fillMaxWidth().height(320.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground),
                interactive = true, cameraReset = live.sessionId.hashCode(), signalCells = view.cells, signal = signal, signalColor = view::color)
            SignalReliefControls(view, samples, onSignal = { signal = it }, onShading = {}, mount = null, onMount = {}, on3d = null)
        }
        SettingsGroup(tr("Browse", "Consulter")) {
            NavRow(tr("Heatmap history", "Historique des heatmaps"), icon = Icons.Rounded.History,
                subtitle = tr("Continuous map versions and dedicated routes, to show on the map", "Versions de la carte continue et parcours dédiés, à afficher sur la carte")) { historyOpen = true }
        }
        SettingsGroup(tr("Dedicated route (optional)", "Parcours dédié (facultatif)")) {
            InfoNote(tr("To measure the whole garden at once: full loop of the boundary or crossed grid (better terrain), " +
                "blades stopped. Its readings also feed the continuous map.",
                "Pour mesurer tout le jardin en une fois : grand tour du contour ou quadrillage croisé (meilleur relief), " +
                "lames arrêtées. Ses mesures alimentent aussi la carte continue."))
            NavRow(tr("Prepare a survey route", "Préparer un parcours de relevé"), icon = Icons.Rounded.Route, enabled = state.robotMap.complete,
                subtitle = if (state.robotMap.complete) tr("Zone choice on the map, preview, simulation", "Choix des zones sur la carte, aperçu, simulation") else tr("Load the robot map first", "Chargez d'abord la carte du robot"),
                onClick = onDedicatedRoute)
        }
    }
    if (historyOpen) SignalHistoryDialog(store, state.robotMap, mount, calibration,
        onMount = { mount = it; ReliefSettings.saveMount(context, it) }, onDismiss = { historyOpen = false },
        onShowOnMap = { survey ->
            SignalOverlay.show(survey.takeIf { it.sessionId != state.signalSurvey.sessionId })
            historyOpen = false; onShowOnMap()
        })
}

private fun surveyDate(epochMs: Long): String =
    if (epochMs > 0) SimpleDateFormat(tr("yyyy-MM-dd HH:mm", "dd/MM/yyyy à HH:mm"), fr.luba.poc.i18n.I18n.locale).format(Date(epochMs)) else tr("unknown date", "date inconnue")
