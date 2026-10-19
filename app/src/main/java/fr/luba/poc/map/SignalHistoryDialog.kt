package fr.luba.poc.map

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.LubaProgress
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.TextAction
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Short label of a survey shown on the map: "Survey of Oct 12", "Continuous map of Oct 12". */
fun surveyShortLabel(s: SignalSurvey): String {
    val day = if (s.startedAt > 0) tr(" of " + SimpleDateFormat("MMM d", Locale.ENGLISH).format(Date(s.startedAt)),
        " du " + SimpleDateFormat("dd/MM", fr.luba.poc.i18n.I18n.locale).format(Date(s.startedAt))) else ""
    return (if (s.live) tr("Continuous map", "Carte continue") else if (s.simulated) tr("Simulated survey", "Relevé simulé")
        else tr("Survey", "Relevé")) + day
}

/** [onShowOnMap]: overlays the viewed survey on the main map ("Show on map" button). */
@Composable
fun SignalHistoryDialog(store: SignalSurveyStore, currentMap: RobotMap, mount: PhoneMount, calibration: TiltCalibration?,
                        onMount: (PhoneMount) -> Unit, onDismiss: () -> Unit, onShowOnMap: ((SignalSurvey) -> Unit)? = null) {
    var history by remember { mutableStateOf<List<SignalSurvey>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<SignalSurvey?>(null) }
    var signal by remember { mutableStateOf(SurveySignal.WIFI) }
    var filter by remember { mutableStateOf(0) }
    var view3d by remember { mutableStateOf(false) }
    var shading by remember { mutableStateOf(true) }
    var exaggeration by remember { mutableStateOf(5f) }
    LaunchedEffect(store) { history=withContext(Dispatchers.IO) { store.history() }; loading=false }
    val date=remember { SimpleDateFormat(tr("yyyy-MM-dd HH:mm:ss", "dd/MM/yyyy à HH:mm:ss"),fr.luba.poc.i18n.I18n.locale) }
    fun label(s: SignalSurvey) = if(s.startedAt>0) date.format(Date(s.startedAt)) else tr("Unknown date", "Date inconnue")
    fun modeLabel(s: SignalSurvey) = if(s.live) tr("Continuous map (today's version)", "Carte continue (version du jour)") else s.mode.label
    LubaDialog(onDismissRequest=onDismiss,title=tr("Heatmap history", "Historique des heatmaps"),content={
        if(loading) LubaProgress(Modifier.fillMaxWidth())
        val survey=selected
        if(survey==null) {
            Segmented(listOf(0 to tr("All", "Tous"),1 to tr("Real", "Réels"),2 to tr("Simulated", "Simulés"),3 to tr("Continuous", "Continue")),filter,{ filter=it })
            val visible=history.filter { when(filter) { 1 -> !it.simulated; 2 -> it.simulated; 3 -> it.live; else -> true } }
            if(!loading && visible.isEmpty()) InfoNote(tr("No heatmap saved for this filter.", "Aucune heatmap enregistrée pour ce filtre."))
            else if(visible.isNotEmpty()) SettingsGroup {
                visible.forEach { s -> NavRow(label(s),
                    subtitle="${if(s.simulated) "Simulation" else "Robot"} · ${modeLabel(s)} · ${tr("${s.samples.size} readings", "${s.samples.size} relevés")}",onClick={ selected=s }) }
            }
        } else {
            TextAction(tr("Back to the list", "Retour à la liste"),{ selected=null })
            if(onShowOnMap!=null) PrimaryPillButton(tr("Show on map", "Afficher sur la carte"),{ onShowOnMap(survey) },Modifier.fillMaxWidth())
            val surveyMount=if(survey.simulated) PhoneMount.TOP_FORWARD else mount
            val surveyCalibration=if(survey.simulated) null else calibration
            val relief by produceState<ReliefResult?>(null,survey,surveyMount,surveyCalibration) {
                value=withContext(Dispatchers.Default) { ReliefModel.result(survey.samples,surveyMount,surveyCalibration) } }
            val view=remember(survey,signal,relief,shading) { signalReliefView(survey.samples,signal,relief,shading) }
            val cells=view.cells
            val snapshot=survey.mapSnapshot ?: currentMap.takeIf { SignalCoverage.mapKey(it)==survey.mapKey } ?: RobotMap()
            SettingsGroup {
                KeyValueRow(tr("Survey", "Relevé"),label(survey))
                KeyValueRow("Source",if(survey.simulated) tr("Fictional signals — simulation", "Signaux fictifs — simulation") else "Robot ${survey.robotId}")
                KeyValueRow("Mode",modeLabel(survey))
                KeyValueRow(tr("Readings", "Mesures"),tr("${survey.samples.size} readings · ${cells.size} cells", "${survey.samples.size} relevés · ${cells.size} cellules"))
            }
            if(snapshot.shapes.isEmpty()) InfoNote(tr("Old survey: the boundaries of this map were not kept.", "Ancien relevé : les contours de cette carte n’ont pas été conservés."))
            // A single view: the chosen color (Wi-Fi by default) and the terrain, as shading on the map or as a 3D shape.
            if(relief!=null) Segmented(listOf(false to tr("Map", "Carte"),true to "3D"),view3d,{ view3d=it })
            if(relief!=null && view3d) Relief3DContent(view,snapshot,"",exaggeration) { exaggeration=it }
            else MapLive(snapshot,modifier=Modifier.fillMaxWidth().height(400.dp).clip(MaterialTheme.shapes.medium).background(MammotionColors.MapBackground),
                interactive=true,cameraReset=survey.sessionId.hashCode(),signalCells=cells,signal=signal,signalColor=view::color)
            SettingsGroup(tr("Display", "Affichage")) {
                SignalReliefControls(view,survey.samples,onSignal={ signal=it },onShading={ shading=it },
                    mount=if(survey.simulated) null else mount,onMount=onMount,on3d=null,calibration=calibration)
            }
        }
    })
}
