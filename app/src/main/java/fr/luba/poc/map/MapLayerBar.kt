package fr.luba.poc.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.theme.MammotionColors

/** Short label of a layer in the map bar (the full label stays in the settings). */
fun SurveySignal.shortLabel(): String = when (this) {
    SurveySignal.WIFI -> "Wi-Fi"
    SurveySignal.RTK -> "RTK"
    SurveySignal.BLE -> "BLE"
    SurveySignal.MOBILE -> "Mobile"
    SurveySignal.GPS -> "Satellites"
    SurveySignal.SLOPE -> tr("Slope", "Pente")
    SurveySignal.RELIEF -> tr("Terrain", "Relief")
}

/**
 * Layers offered in the bar: signal families actually measured (zero RSSI = unknown), terrain layers only if the
 * terrain can be computed. The displayed layer is always offered.
 */
fun availableLayers(samples: List<SignalSample>, terrain: Boolean, current: SurveySignal): List<SurveySignal> =
    SurveySignal.entries.filter { signal ->
        signal == current || if (signal.terrain) terrain else samples.any { s ->
            val v = s.value(signal)
            v != null && (v != 0.0 || signal == SurveySignal.RTK || signal == SurveySignal.GPS)
        }
    }

/**
 * Heatmap layer bar, at the bottom of the map: eye (show / hide), one button per available layer (Wi-Fi, RTK, BLE…)
 * and access to the settings, legend and history. Tapping the displayed layer hides it. Without a layer (no survey on
 * this map), the bar says so and the right icon opens the history. [source]: survey chosen in the history,
 * [onClearSource] goes back to the continuous map.
 */
@Composable
fun MapLayerBar(
    layers: List<SurveySignal>,
    selected: SurveySignal,
    visible: Boolean,
    simulated: Boolean,
    onSelect: (SurveySignal) -> Unit,
    onToggleVisible: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    source: String? = null,
    onClearSource: (() -> Unit)? = null,
    /** Encountered obstacles and Difficulties layers (independent of the displayed signal). */
    showExtras: Boolean = true,
) {
    val colors = MammotionColors
    val extras by MapExtraLayers.flow.collectAsState()
    Surface(modifier.fillMaxWidth(), shape = CircleShape, color = colors.Overlay, contentColor = colors.TextPrimary, shadowElevation = 3.dp) {
        Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleVisible, modifier = Modifier.size(40.dp), enabled = layers.isNotEmpty()) {
                Icon(if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = if (visible) tr("Hide the heatmap", "Masquer la heatmap") else tr("Show the heatmap", "Afficher la heatmap"),
                    tint = if (visible && layers.isNotEmpty()) colors.TextPrimary else colors.TextTertiary, modifier = Modifier.size(20.dp))
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showExtras) MapExtraLayer.entries.forEach { layer ->
                    ExtraChip(layer, active = layer in extras, onClick = { MapExtraLayers.toggle(layer) })
                }
                if (layers.isEmpty()) Text(tr("No survey", "Aucun relevé"), style = MaterialTheme.typography.labelMedium,
                    color = colors.TextSecondary, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
                if (simulated) Text(tr("Simulated", "Simulé"), style = MaterialTheme.typography.labelSmall, color = colors.Warning,
                    modifier = Modifier.padding(horizontal = 4.dp))
                if (source != null) SourceChip(source, onClearSource)
                layers.forEach { signal -> LayerChip(signal, active = visible && signal == selected,
                    onClick = { if (visible && signal == selected) onToggleVisible() else onSelect(signal) }) }
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Tune, contentDescription = if (layers.isEmpty()) tr("Heatmap history", "Historique des heatmaps")
                    else tr("Signal settings, legend and history", "Réglages, légende et historique des signaux"), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
internal fun LayerChip(signal: SurveySignal, active: Boolean, onClick: () -> Unit) {
    val colors = MammotionColors
    Surface(onClick = onClick, shape = CircleShape, color = if (active) colors.Ink else Color.Transparent,
        contentColor = if (active) colors.OnInk else colors.TextSecondary,
        border = if (active) null else BorderStroke(1.dp, colors.Hairline),
        modifier = Modifier.semantics { role = Role.Tab; selected = active }) {
        Text(signal.shortLabel(), style = MaterialTheme.typography.labelLarge, maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Obstacles or Difficulties layer: independent toggle, colored dot when shown. */
@Composable
private fun ExtraChip(layer: MapExtraLayer, active: Boolean, onClick: () -> Unit) {
    val colors = MammotionColors
    val accent = if (layer == MapExtraLayer.ENCOUNTERS) MapExtraLayers.kindColor(fr.luba.poc.difficulty.DifficultyKind.CONTACT)
        else androidx.compose.ui.graphics.Color(0xFFF59E0B)
    Surface(onClick = onClick, shape = CircleShape, color = if (active) accent.copy(alpha = 0.16f) else Color.Transparent,
        contentColor = if (active) colors.TextPrimary else colors.TextSecondary,
        border = BorderStroke(1.dp, if (active) accent else colors.Hairline),
        modifier = Modifier.semantics { role = Role.Switch; selected = active; contentDescription = layer.description }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(if (active) accent else colors.TextTertiary) }
            Text(layer.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Survey from the history shown instead of the continuous map; the cross goes back to the continuous map. */
@Composable
private fun SourceChip(label: String, onClear: (() -> Unit)?) {
    val colors = MammotionColors
    Surface(shape = CircleShape, color = colors.SurfaceMuted, contentColor = colors.TextSecondary) {
        Row(Modifier.padding(start = 10.dp, end = if (onClear != null) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (onClear != null) IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = tr("Back to the continuous map", "Revenir à la carte continue"), modifier = Modifier.size(14.dp))
            }
        }
    }
}
