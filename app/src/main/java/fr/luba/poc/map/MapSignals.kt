package fr.luba.poc.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.simulation.SimulationUi

/** Signal survey shown on the map ([MapScreen]): simulated or measured readings, terrain and colored cells. */
internal class MapSignals(
    /** Heatmap of the running simulation (survey route) rather than a measured one. */
    val simulatedSurvey: Boolean,
    /** Overlaid survey: chosen in the history ([pinnedSurvey]), otherwise the continuous map. */
    val pinnedSurvey: SignalSurvey?,
    val overlaySurvey: SignalSurvey?,
    val displayedSignalSamples: List<SignalSample>,
    /** The shown survey belongs to this map. */
    val surveyMatches: Boolean,
    val relief: ReliefResult?,
    val signalView: SignalReliefView,
)

/** Values of [MapSignals], recomputed with the survey, the simulation and the layer choice ([MapLayersState]). */
@Composable
internal fun rememberMapSignals(map: RobotMap, state: RobotUi, sim: SimulationUi, layers: MapLayersState, demo: Boolean): MapSignals {
    LaunchedEffect(sim.engine,sim.playing,state.robotRoute.running) {
        if (state.robotRoute.running || sim.engine == null) layers.simulatedSignalSource=false
        else if (sim.playing) layers.simulatedSignalSource=true
    }
    val simulatedSurvey = layers.simulatedSignalSource && sim.engine?.preview?.mode?.isSurvey == true
    // Overlaid survey: chosen in the history, otherwise the continuous map (automatic survey while moving), also on the
    // Mammotion account map when it is in the same frame.
    val pinnedSurvey by SignalOverlay.pinned.collectAsStateWithLifecycle()
    val overlaySurvey = remember(map.shapes, state.signalSurvey, pinnedSurvey) { SignalOverlay.resolve(map, state.signalSurvey, pinnedSurvey) }
    val displayedSignalSamples = if (simulatedSurvey) sim.state?.signalSamples.orEmpty() else overlaySurvey?.samples.orEmpty()
    val surveyMatches = simulatedSurvey || (!demo && overlaySurvey != null)
    val revealLayers by SignalOverlay.reveal.collectAsStateWithLifecycle()
    LaunchedEffect(revealLayers) { if (revealLayers && SignalOverlay.consumeReveal()) { layers.layersOpen = true; layers.showSignalHeat = true } }
    // Simulation: fictional terrain measured with a "top towards the front" phone, whatever the real setting.
    val terrainMount = if (simulatedSurvey) PhoneMount.TOP_FORWARD else layers.reliefMount
    // Terrain computed for every color: it overlays the Wi-Fi (shading, 3D view) in the same view.
    // Automatic orientation: search over the whole survey, computed off the UI thread.
    // Simulation: fictional terrain without reference; real survey: corrected by the flat-ground reference.
    val terrainCalibration = if (simulatedSurvey) null else layers.tiltCalibration
    val relief by androidx.compose.runtime.produceState<ReliefResult?>(null, displayedSignalSamples, terrainMount, terrainCalibration) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ReliefModel.result(displayedSignalSamples,terrainMount,terrainCalibration) }
    }
    val signalView = remember(displayedSignalSamples, layers.surveySignal, relief, layers.reliefShading) {
        signalReliefView(displayedSignalSamples,layers.surveySignal,relief,layers.reliefShading)
    }
    return MapSignals(simulatedSurvey, pinnedSurvey, overlaySurvey, displayedSignalSamples, surveyMatches, relief, signalView)
}
