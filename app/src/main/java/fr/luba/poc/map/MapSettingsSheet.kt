package fr.luba.poc.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.InfoNote
import fr.luba.poc.ui.components.NavRow
import fr.luba.poc.ui.components.SettingsGroup
import fr.luba.poc.ui.components.ToggleRow
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.EyebrowStyle
import fr.luba.poc.ui.theme.LubaColors
import fr.luba.poc.ui.theme.MammotionColors

/** Source of the displayed map, recalled at the top of the **Map** sheet. */
enum class MapSource(private val en: String, private val fr: String) {
    ROBOT("Robot Bluetooth map", "Carte Bluetooth du robot"),
    ACCOUNT("Mammotion account map · read only", "Carte du compte Mammotion · lecture seule"),
    DEMO("Demo map", "Carte de démonstration"),
    SIMULATION("Local simulation · no real movement", "Simulation locale · aucun mouvement réel");
    val label: String get() = tr(en, fr)
}

/** Signal heatmap as shown on the map: measured layers, chosen layer, visibility, bar. */
data class MapHeatChoice(
    val layers: List<SurveySignal>,
    val signal: SurveySignal,
    val visible: Boolean,
    val barOpen: Boolean,
    val simulated: Boolean = false,
)

/**
 * **Map** sheet (Map button of the bottom bar): all the map's own settings in one place — background, displayed items,
 * signal heatmap, map source and an illustrated legend with the same symbols as the map. Each setting applies at once
 * (the map stays visible above the sheet) and is kept between two launches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSettingsSheet(
    source: MapSource,
    summary: String,
    heat: MapHeatChoice,
    mapLoading: Boolean,
    fetchEnabled: Boolean,
    accountEnabled: Boolean,
    demoEnabled: Boolean,
    onSignal: (SurveySignal) -> Unit,
    onHeatVisible: (Boolean) -> Unit,
    onLayerBar: (Boolean) -> Unit,
    onFetch: () -> Unit,
    onAccount: () -> Unit,
    onToggleDemo: () -> Unit,
    onHistory: () -> Unit,
    onCalibration: () -> Unit,
    onDismiss: () -> Unit,
    corrections: Int = 0,
    correctionsEnabled: Boolean = false,
    onCorrections: () -> Unit = {},
    recordEnabled: Boolean = false,
    onRecord: () -> Unit = {},
) {
    val colors = MammotionColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.Background, scrimColor = Color.Black.copy(alpha = 0.25f)) {
        MapSettingsContent(source, summary, heat, mapLoading, fetchEnabled, accountEnabled, demoEnabled, onSignal, onHeatVisible,
            onLayerBar, onFetch, onAccount, onToggleDemo, onHistory, onCalibration, corrections, correctionsEnabled, onCorrections,
            recordEnabled, onRecord)
    }
}

/** Content of the **Map** sheet, kept apart for screenshots and tests. */
@Composable
fun MapSettingsContent(
    source: MapSource,
    summary: String,
    heat: MapHeatChoice,
    mapLoading: Boolean,
    fetchEnabled: Boolean,
    accountEnabled: Boolean,
    demoEnabled: Boolean,
    onSignal: (SurveySignal) -> Unit,
    onHeatVisible: (Boolean) -> Unit,
    onLayerBar: (Boolean) -> Unit,
    onFetch: () -> Unit,
    onAccount: () -> Unit,
    onToggleDemo: () -> Unit,
    onHistory: () -> Unit,
    onCalibration: () -> Unit,
    corrections: Int = 0,
    correctionsEnabled: Boolean = false,
    onCorrections: () -> Unit = {},
    recordEnabled: Boolean = false,
    onRecord: () -> Unit = {},
) {
    val colors = MammotionColors
    val display = MapDisplaySettings.current
    val extras by MapExtraLayers.flow.collectAsState()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.Lg).padding(bottom = Dimens.Lg),
        verticalArrangement = Arrangement.spacedBy(Dimens.Lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr("Map", "Carte"), style = MaterialTheme.typography.headlineSmall)
            Text("${source.label} · $summary", style = MaterialTheme.typography.bodyMedium, color = colors.TextSecondary)
        }

        SheetSection(tr("Map background", "Fond de carte")) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                MapBackdrop.entries.forEach { backdrop ->
                    BackdropTile(backdrop, display.backdrop == backdrop, { MapDisplaySettings.update { it.copy(backdrop = backdrop) } },
                        Modifier.weight(1f))
                }
            }
            Text(display.backdrop.detail, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary,
                modifier = Modifier.padding(start = 6.dp))
        }

        SettingsGroup(tr("On the map", "Sur la carte")) {
            ToggleRow(tr("Zone names", "Noms des zones"), display.zoneNames, { on -> MapDisplaySettings.update { it.copy(zoneNames = on) } },
                subtitle = tr("Hidden for a zone too small on screen", "Masqués pour une zone trop petite à l'écran"), icon = Icons.Rounded.TextFields)
            ToggleRow(tr("Scale", "Échelle"), display.scaleBar, { on -> MapDisplaySettings.update { it.copy(scaleBar = on) } },
                subtitle = tr("Reference distance at the top left, follows the zoom", "Distance de référence en haut à gauche, suit le zoom"), icon = Icons.Rounded.Straighten)
            ToggleRow(tr("Planned route animation", "Animation du trajet prévu"), display.routeAnimation, { on -> MapDisplaySettings.update { it.copy(routeAnimation = on) } },
                subtitle = tr("Comet running along the route in mowing order", "Comète qui parcourt le trajet dans l'ordre de tonte"), icon = Icons.Rounded.Animation)
            ToggleRow(tr("Encountered obstacles", "Obstacles rencontrés"), MapExtraLayer.ENCOUNTERS in extras, { MapExtraLayers.toggle(MapExtraLayer.ENCOUNTERS) },
                subtitle = tr("Bumper contacts and presumed obstacles; a tap opens their sheet", "Contacts du pare-chocs et obstacles présumés ; un toucher ouvre leur fiche"), icon = Icons.Rounded.WarningAmber)
            ToggleRow(tr("Difficulties", "Difficultés"), MapExtraLayer.DIFFICULTIES in extras, { MapExtraLayers.toggle(MapExtraLayer.DIFFICULTIES) },
                subtitle = tr("Blockages, slipping, lifts and route deviations", "Blocages, patinages, soulèvements et écarts de trajectoire"), icon = Icons.Rounded.Whatshot)
        }

        SettingsGroup(tr("Signal heatmap", "Heatmap des signaux")) {
            if (heat.layers.isEmpty()) InfoNote(tr("No signal survey on this map: open the history to show one.", "Aucun relevé de signaux sur cette carte : ouvrez l'historique pour en afficher un."))
            else {
                ToggleRow(if (heat.simulated) tr("Show the simulated heatmap", "Afficher la heatmap simulée") else tr("Show the heatmap", "Afficher la heatmap"), heat.visible, onHeatVisible,
                    icon = Icons.Rounded.WifiTethering)
                SignalChips(heat, onSignal)
            }
            ToggleRow(tr("Layer bar", "Barre des calques"), heat.barOpen, onLayerBar,
                subtitle = tr("Obstacles, Difficulties and signal shortcuts above the actions", "Raccourcis Obstacles, Difficultés et signaux au-dessus des actions"), icon = Icons.Rounded.Layers)
            NavRow(tr("Heatmap history", "Historique des heatmaps"), icon = Icons.Rounded.WifiTethering, onClick = onHistory)
            NavRow(tr("Flat-ground reference", "Référence sur sol plat"), icon = Icons.Rounded.Landscape,
                subtitle = tr("Tilt and orientation of the gateway phone, for the terrain", "Inclinaison et orientation du téléphone passerelle, pour le relief"), onClick = onCalibration)
        }

        SettingsGroup(tr("Local corrections", "Corrections locales")) {
            NavRow(tr("Correct the map", "Corriger la carte"), icon = Icons.Rounded.Edit,
                subtitle = tr("Zones, no-go zones and obstacles corrected on the phone, without touching the Mammotion map", "Zones, no-go et obstacles corrigés sur le téléphone, sans toucher à la carte Mammotion"),
                value = if (corrections > 0) tr("$corrections active", "$corrections active${if (corrections > 1) "s" else ""}") else null,
                enabled = correctionsEnabled, onClick = onCorrections)
            NavRow(tr("Record by driving", "Enregistrer en pilotant"), icon = Icons.Rounded.Gamepad,
                subtitle = tr("New DumbyLuba zone, passage or no-go zone, driven with the joystick like in the Mammotion app; kept on this phone only",
                    "Nouvelle zone DumbyLuba, passage ou no-go, parcourus au joystick comme dans l'app Mammotion ; gardés sur ce téléphone uniquement"),
                enabled = recordEnabled, onClick = onRecord)
        }

        SettingsGroup(tr("Map source", "Source de la carte")) {
            NavRow(if (mapLoading) tr("Reading…", "Lecture en cours…") else tr("Read the robot map", "Lire la carte du robot"), icon = Icons.Rounded.Bluetooth,
                subtitle = tr("Bluetooth or gateway", "Bluetooth ou passerelle"), value = tr("Shown", "Affichée").takeIf { source == MapSource.ROBOT },
                enabled = fetchEnabled, onClick = onFetch)
            NavRow(tr("Mammotion account map", "Carte du compte Mammotion"), icon = Icons.Rounded.Cloud, subtitle = tr("Viewing and simulation, no mowing", "Consultation et simulation, sans tonte"),
                value = tr("Shown", "Affichée").takeIf { source == MapSource.ACCOUNT }, enabled = accountEnabled, onClick = onAccount)
            NavRow(if (source == MapSource.DEMO) tr("Back to the real map", "Revenir à la carte réelle") else tr("Try the demo", "Essayer la démonstration"), icon = Icons.Rounded.Map,
                subtitle = tr("Built-in fictional map", "Carte fictive embarquée"), enabled = demoEnabled, onClick = onToggleDemo)
        }

        MapLegend()
    }
}

/** Spaced capital eyebrow followed by its content (no white card: the tiles have their own). */
@Composable
private fun SheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = EyebrowStyle, color = MammotionColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
        content()
    }
}

/** Background tile: drawn preview (background, pattern, lawn) and label; green tick on the chosen background. */
@Composable
private fun BackdropTile(backdrop: MapBackdrop, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    Surface(onClick = onClick, modifier = modifier.semantics { role = Role.RadioButton; this.selected = selected },
        shape = MaterialTheme.shapes.medium, color = colors.Surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.Green else colors.Hairline)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Canvas(Modifier.fillMaxWidth().height(64.dp)) { drawBackdropPreview(backdrop, colors) }
                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.Green,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
            }
            Text(backdrop.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                color = if (selected) colors.TextPrimary else colors.TextSecondary, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun DrawScope.drawBackdropPreview(backdrop: MapBackdrop, colors: LubaColors) {
    val r = 8.dp.toPx()
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }
    clipPath(clip) {
        drawRect(colors.MapBackground)
        when (backdrop) {
            MapBackdrop.GRID -> {
                val step = 9.dp.toPx()
                var i = 0
                var x = step / 2
                while (x < size.width) {
                    drawLine(colors.MapGrid.copy(alpha = if (i % 5 == 0) 0.4f else 0.2f), Offset(x, 0f), Offset(x, size.height), 1f); x += step; i++
                }
                i = 0
                var y = step / 2
                while (y < size.height) {
                    drawLine(colors.MapGrid.copy(alpha = if (i % 5 == 0) 0.4f else 0.2f), Offset(0f, y), Offset(size.width, y), 1f); y += step; i++
                }
            }
            MapBackdrop.DOTS -> {
                val step = 9.dp.toPx()
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) { drawCircle(colors.TextTertiary.copy(alpha = 0.35f), 1.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
            MapBackdrop.PLAIN -> Unit
        }
        // Stylized lawn: same rendering as the map (pale green fill, green outline, ochre no-go).
        val lawn = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.30f)
            lineTo(size.width * 0.62f, size.height * 0.18f)
            lineTo(size.width * 0.86f, size.height * 0.55f)
            lineTo(size.width * 0.55f, size.height * 0.86f)
            lineTo(size.width * 0.22f, size.height * 0.74f)
            close()
        }
        drawPath(lawn, colors.ZoneFill)
        drawPath(lawn, colors.ZoneStroke, style = Stroke(1.dp.toPx()))
        drawCircle(colors.NoGoFill, 6.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.52f))
        drawCircle(colors.NoGoStroke, 6.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.52f), style = Stroke(1.dp.toPx()))
    }
}

/** Chips of the measured signal layers, on several lines if needed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalChips(heat: MapHeatChoice, onSignal: (SurveySignal) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        heat.layers.forEach { signal -> LayerChip(signal, active = heat.visible && signal == heat.signal, onClick = { onSignal(signal) }) }
    }
}

/** Legend entry: symbol drawn as on the map, label and optional detail. */
private class LegendEntry(val title: String, val detail: String? = null, val symbol: DrawScope.(LubaColors) -> Unit)

/** Illustrated map legend, by family: garden, routes, obstacles, robot. */
@Composable
fun MapLegend() {
    val groups = listOf(
        tr("Garden", "Terrain") to listOf(
            LegendEntry(tr("Mowing zone", "Zone de tonte"), tr("Brighter when chosen, number = mowing order", "Plus vive quand elle est choisie, numéro = ordre de tonte")) { c -> legendZone(c, true) },
            LegendEntry(tr("No-go zone", "Zone interdite"), tr("Never mowed or crossed", "Jamais tondue ni traversée")) { c -> legendNoGo(c) },
            LegendEntry(tr("Map obstacle", "Obstacle de la carte")) { c -> legendMapObstacle(c) },
            LegendEntry(tr("Passage", "Passage"), tr("Links two zones, never mowed", "Relie deux zones, jamais tondu")) { c -> legendPassage(c) },
            LegendEntry(tr("Charging station", "Station de charge"), tr("Lightning badge when the station is small on screen", "Pastille éclair quand la station est petite à l'écran")) { c -> legendStation(c) },
            LegendEntry(tr("Base safety zone", "Zone de sécurité de la base"), tr("No route goes through it; entry and exit through the front corridor", "Aucun trajet n'y passe ; entrée et sortie par le couloir avant")) { c -> legendBaseSafety(c) },
            LegendEntry(tr("DumbyLuba zone · DL", "Zone DumbyLuba · DL"), tr("Dashed outline: recorded by driving, exists only on this phone (not in the Mammotion app); mowed like the others",
                "Contour en tirets : enregistrée en pilotant, n'existe que sur ce téléphone (pas dans l'app Mammotion) ; tondue comme les autres")) { c -> legendDumbyLubaZone(c) },
            LegendEntry(tr("DumbyLuba passage", "Passage DumbyLuba"), tr("Green band: recorded by driving, links zones like a Mammotion passage",
                "Bande verte : enregistré en pilotant, relie les zones comme un passage Mammotion")) { c -> legendPassage(c, dumbyLuba = true) },
            LegendEntry(tr("Beyond the Mammotion boundaries", "Hors des contours Mammotion"), tr("Orange hatching: surface added on the phone (DumbyLuba zones, enlargements), mowed under your responsibility",
                "Hachures orange : surface ajoutée sur le téléphone (zones DumbyLuba, agrandissements), tondue sous votre responsabilité")) { c -> legendBeyond(c) },
            LegendEntry(tr("Corrected locally ✎", "Corrigé localement ✎"), tr("Boundary edited on the phone; the Mammotion map stays unchanged", "Contour retouché sur le téléphone ; la carte Mammotion reste inchangée")) { c -> legendCorrected(c) },
        ),
        tr("Routes", "Trajets") to listOf(
            LegendEntry(tr("Passes to mow", "Passes à tondre"), tr("Planned route, band = cutting width", "Trajet prévu, bande = largeur de coupe")) { c -> legendPasses(c) },
            LegendEntry(tr("Borders to mow", "Bordures à tondre"), tr("Loops along the boundaries and no-go zones, on a light edging", "Tours des limites et des zones interdites, sur un liseré clair")) { c -> legendBorder(c) },
            LegendEntry(tr("Already mowed", "Déjà tondu"), tr("Dark green stripes and robot trace", "Bandes vert foncé et trace du robot")) { c -> legendMowed(c) },
            LegendEntry(tr("Travel with blades stopped", "Déplacement lames arrêtées"), tr("Returns, links, accesses, move to a point", "Retours, raccords, accès, déplacement au point")) { c -> legendTransit(c) },
            LegendEntry(tr("Start and resume", "Départ et reprise")) { c -> legendStart(c) },
        ),
        tr("Obstacles", "Obstacles") to listOf(
            LegendEntry(tr("Saved obstacle", "Obstacle enregistré"), tr("Object, safety margin, turning envelope", "Objet, marge de sécurité, enveloppe de virage")) { c -> legendLocalObstacle(c) },
            LegendEntry(tr("Presumed obstacle", "Obstacle présumé"), tr("Repeated contacts at the same place", "Contacts répétés au même endroit")) { c -> legendPresumed(c) },
            LegendEntry(tr("Bumper contact", "Contact du pare-chocs"), tr("White dot: photo available", "Point blanc : photo disponible")) { c -> legendContact() },
            LegendEntry(tr("Difficulties", "Difficultés"), tr("From yellow (rare) to red (frequent)", "Du jaune (rare) au rouge (fréquent)")) { c -> legendDifficulty() },
        ),
        "Robot" to listOf(
            LegendEntry(tr("Live position", "Position en direct"), tr("Green halo: fresh position", "Halo vert : position fraîche")) { c -> legendRobot(c, true) },
            LegendEntry(tr("Last known position", "Dernière position connue"), tr("Gray ring: old position", "Anneau gris : position ancienne")) { c -> legendRobot(c, false) },
        ),
    )
    SettingsGroup(tr("Legend", "Légende")) {
        groups.forEachIndexed { index, (title, entries) ->
            Text(title, style = MaterialTheme.typography.labelLarge, color = MammotionColors.TextSecondary,
                modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp))
            entries.forEach { entry -> LegendRow(entry) }
        }
    }
}

@Composable
private fun LegendRow(entry: LegendEntry) {
    val colors = MammotionColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
        Canvas(Modifier.size(width = 44.dp, height = 28.dp)) {
            drawRoundRect(colors.MapBackground, cornerRadius = CornerRadius(6.dp.toPx()))
            entry.symbol(this, colors)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
            if (entry.detail != null) Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary)
        }
    }
}

// Legend symbols: drawn in a 44 × 28 dp thumbnail, with the map colors and stroke widths.
private fun DrawScope.u() = 1.dp.toPx()

private fun DrawScope.lawnRect(c: LubaColors, selected: Boolean) {
    val inset = 4 * u()
    drawRoundRect(if (selected) c.ZoneFillSelected else c.ZoneFill, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
        CornerRadius(5 * u()))
    drawRoundRect(c.ZoneStroke, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(5 * u()),
        style = Stroke(1.5f * u()))
}

private fun DrawScope.legendZone(c: LubaColors, selected: Boolean) {
    lawnRect(c, selected)
    val at = Offset(size.width * 0.5f, size.height * 0.5f)
    drawCircle(Color.White, 8 * u(), at)
    drawCircle(Color(0xFF212226), 6.5f * u(), at)
    drawLine(Color.White, at + Offset(0f, -3 * u()), at + Offset(0f, 3 * u()), 1.5f * u(), StrokeCap.Round)
}

/** Corrected boundary: filled zone, original Mammotion boundary as grey dashes. */
private fun DrawScope.legendCorrected(c: LubaColors) {
    lawnRect(c, false)
    val r = androidx.compose.ui.geometry.Rect(size.width * 0.18f, size.height * 0.25f, size.width * 0.70f, size.height * 0.75f)
    drawRect(c.TextSecondary, r.topLeft, r.size, style = Stroke(1.5f * u(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * u(), 3f * u()))))
}

private fun DrawScope.legendNoGo(c: LubaColors) {
    lawnRect(c, false)
    val r = androidx.compose.ui.geometry.Rect(size.width * 0.25f, size.height * 0.22f, size.width * 0.75f, size.height * 0.78f)
    val shape = Path().apply { addOval(r) }
    drawPath(shape, c.NoGoFill)
    drawPath(shape, c.NoGoStroke.copy(alpha = 0.7f), style = Stroke(1f * u()))
}

private fun DrawScope.legendMapObstacle(c: LubaColors) {
    lawnRect(c, false)
    val r = androidx.compose.ui.geometry.Rect(size.width * 0.32f, size.height * 0.28f, size.width * 0.68f, size.height * 0.72f)
    drawRoundRect(c.ObstacleFill, r.topLeft, r.size, CornerRadius(3 * u()))
    drawRoundRect(c.ObstacleStroke, r.topLeft, r.size, CornerRadius(3 * u()), style = Stroke(1.25f * u()))
}

/** DumbyLuba zone: filled zone with a dashed outline. */
private fun DrawScope.legendDumbyLubaZone(c: LubaColors) {
    val inset = 4 * u()
    drawRoundRect(c.ZoneFill, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(5 * u()))
    drawRoundRect(c.ZoneStroke, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(5 * u()),
        style = Stroke(2f * u(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * u(), 4f * u()))))
}

/** Beyond the Mammotion boundaries: orange hatching over the zone. */
private fun DrawScope.legendBeyond(c: LubaColors) {
    lawnRect(c, false)
    val ring = listOf(Point(size.width * 0.45, size.height * 0.2), Point(size.width * 0.92, size.height * 0.2),
        Point(size.width * 0.92, size.height * 0.8), Point(size.width * 0.45, size.height * 0.8))
    drawBeyondMammotion(BeyondArea(listOf(ring), 1.0), { pts, _ ->
        Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x.toFloat(), p.y.toFloat()) else lineTo(p.x.toFloat(), p.y.toFloat()) }; close() }
    }, u(), c)
}

private fun DrawScope.legendPassage(c: LubaColors, dumbyLuba: Boolean = false) {
    val y = size.height / 2
    val dark = c.Background.luminance() < 0.5f
    drawLine(if (dumbyLuba) c.ZoneStroke.copy(alpha = 0.45f) else (if (dark) Color.White else Color(0xFF6E767E)).copy(alpha = if (dark) 0.22f else 0.35f),
        Offset(6 * u(), y), Offset(size.width - 6 * u(), y), 6 * u(), StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.95f), Offset(6 * u(), y), Offset(size.width - 6 * u(), y), 1.5f * u(), StrokeCap.Round,
        PathEffect.dashPathEffect(floatArrayOf(4 * u(), 3 * u())))
}

private fun DrawScope.legendStation(c: LubaColors) {
    drawStationBadge(Offset(size.width / 2, size.height / 2), u(), c.Ink, c.OnInk)
}

/** Base safety zone: grey disc edged in white around the station. */
private fun DrawScope.legendBaseSafety(c: LubaColors) {
    lawnRect(c, false)
    val at = Offset(size.width / 2, size.height / 2)
    drawBaseSafetyZone(at, 10 * u(), u(), c.BaseSafety)
    drawCircle(c.Ink, 3 * u(), at)
}

private fun DrawScope.legendPasses(c: LubaColors) {
    lawnRect(c, true)
    var y = 8 * u()
    while (y < size.height - 6 * u()) {
        drawLine(c.BandPass, Offset(8 * u(), y), Offset(size.width - 8 * u(), y), 4 * u())
        drawLine(c.LinePass, Offset(8 * u(), y), Offset(size.width - 8 * u(), y), 0.9f * u())
        y += 5 * u()
    }
}

private fun DrawScope.legendBorder(c: LubaColors) {
    lawnRect(c, true)
    val inset = 8 * u()
    drawRoundRect(c.BandBorder, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(3 * u()),
        style = Stroke(4 * u()))
    drawRoundRect(c.LineBorder, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(3 * u()),
        style = Stroke(0.9f * u()))
}

private fun DrawScope.legendMowed(c: LubaColors) {
    lawnRect(c, true)
    var y = 8 * u()
    while (y < size.height - 6 * u()) {
        drawLine(c.Mowed.copy(alpha = 0.32f), Offset(8 * u(), y), Offset(size.width - 8 * u(), y), 4 * u())
        y += 5 * u()
    }
    drawLine(c.Mowed, Offset(8 * u(), size.height / 2), Offset(size.width - 8 * u(), size.height / 2), 2 * u(), StrokeCap.Round)
}

private fun DrawScope.legendTransit(c: LubaColors) {
    val path = Path().apply {
        moveTo(6 * u(), size.height * 0.7f)
        cubicTo(size.width * 0.35f, size.height * 0.1f, size.width * 0.6f, size.height * 0.95f, size.width - 6 * u(), size.height * 0.3f)
    }
    drawTransitPath(path, u(), c)
}

private fun DrawScope.legendStart(c: LubaColors) {
    val at = Offset(size.width / 2, size.height / 2)
    val purple = Color(0xFF7253C7)
    drawCircle(purple.copy(alpha = 0.25f), 11 * u(), at)
    drawCircle(purple, 6 * u(), at)
    drawCircle(Color.White, 6 * u(), at, style = Stroke(2 * u()))
    drawCircle(Color.White, 1.5f * u(), at)
}

private fun DrawScope.legendLocalObstacle(c: LubaColors) {
    val at = Offset(size.width / 2, size.height / 2)
    drawCircle(c.ObstacleLocalStroke.copy(alpha = 0.10f), 12 * u(), at)
    drawCircle(c.ObstacleKeepOut, 12 * u(), at, style = Stroke(1.5f * u(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * u(), 3 * u()))))
    drawCircle(c.ObstacleLocalStroke.copy(alpha = 0.18f), 8 * u(), at)
    drawCircle(c.ObstacleLocalStroke, 8 * u(), at, style = Stroke(1.25f * u()))
    drawCircle(c.ObstacleLocalStroke, 4 * u(), at)
    drawCircle(Color.White, 4 * u(), at, style = Stroke(1.5f * u()))
}

private fun DrawScope.legendPresumed(c: LubaColors) {
    val at = Offset(size.width / 2, size.height / 2)
    drawCircle(c.Danger.copy(alpha = 0.10f), 10 * u(), at)
    drawCircle(c.Danger, 10 * u(), at, style = Stroke(1.75f * u(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5 * u(), 3.5f * u()))))
}

private fun DrawScope.legendContact() {
    val at = Offset(size.width / 2, size.height / 2)
    drawCircle(Color.White, 8 * u(), at)
    drawCircle(MapExtraLayers.kindColor(DifficultyKind.CONTACT), 6 * u(), at)
    drawCircle(Color.White, 2.25f * u(), at)
}

private fun DrawScope.legendDifficulty() {
    val cell = 8 * u()
    val left = (size.width - 4 * cell) / 2
    listOf(0.2, 0.6, 1.0, 1.5).forEachIndexed { i, score ->
        drawRect(difficultyHeatColor(score), Offset(left + i * cell, size.height / 2 - cell / 2), Size(cell, cell))
    }
}

private fun DrawScope.legendRobot(c: LubaColors, fresh: Boolean) {
    val at = Offset(size.width / 2, size.height / 2)
    if (fresh) {
        drawCircle(c.Green.copy(alpha = 0.25f), 12 * u(), at)
        drawCircle(Color.White.copy(alpha = 0.9f), 8 * u(), at, style = Stroke(2 * u()))
    } else {
        drawCircle(c.TextTertiary.copy(alpha = 0.25f), 10 * u(), at)
        drawCircle(c.TextTertiary, 8 * u(), at, style = Stroke(1.5f * u()))
    }
    drawLuba(at, lubaDrawScale(0.0, 15 * u(), 1f), 90.0)
}
