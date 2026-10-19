package fr.luba.poc.map

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import fr.luba.poc.camera.ObstaclePhoto
import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.KeyValueRow
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.theme.LubaColors
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Map layers independent of the signal surveys. Labels are evaluated at display time (interface language). */
enum class MapExtraLayer(private val labelEn: String, private val labelFr: String, private val descriptionEn: String,
                         private val descriptionFr: String) {
    ENCOUNTERS("Obstacles", "Obstacles", "Encountered obstacles (bumper contacts, photo) and presumed obstacles",
        "Obstacles rencontrés (contacts pare-chocs, photo) et obstacles présumés"),
    DIFFICULTIES("Difficulties", "Difficultés", "Difficulties heatmap: contacts, blockages, slipping, lifts, deviations",
        "Heatmap des difficultés : contacts, blocages, patinage, soulèvements, écarts");
    val label: String get() = tr(labelEn, labelFr)
    val description: String get() = tr(descriptionEn, descriptionFr)
}

/** Action requested from the sheet of an encountered obstacle. */
sealed interface DifficultyAction {
    data class Dismiss(val id: String) : DifficultyAction
    data class Register(val obstacle: LocalObstacle) : DifficultyAction
    /** Saved obstacle deleted from its sheet on the map. */
    data class Delete(val id: String) : DifficultyAction
}

/**
 * Encountered obstacles and Difficulties layers: visibility kept between two launches, sheet opened when an
 * encountered obstacle is tapped. State kept outside the map screen (MapScreen register limit).
 */
object MapExtraLayers {
    private var prefs: android.content.SharedPreferences? = null
    private val visible = MutableStateFlow(setOf(MapExtraLayer.ENCOUNTERS))
    val flow: StateFlow<Set<MapExtraLayer>> get() = visible
    private val opened = MutableStateFlow<DifficultyEvent?>(null)
    val openedEncounter: StateFlow<DifficultyEvent?> get() = opened
    private val openedObstacleId = MutableStateFlow<String?>(null)
    /** Saved obstacle whose sheet (exclusion size) is open. */
    val openedObstacle: StateFlow<String?> get() = openedObstacleId
    /** Displayed saved obstacles: the map tap reads them without recomposing. */
    @Volatile var latestObstacles: List<LocalObstacle> = emptyList()
    /** Last displayed state: the map gesture reads it without recomposing. */
    @Volatile var latest: DifficultyUi = DifficultyUi()
    /** Sheet actions (save, dismiss), wired by the activity to the robot driver. */
    var actions: ((DifficultyAction) -> Unit)? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("map_layers", Context.MODE_PRIVATE).also { p ->
            visible.value = MapExtraLayer.entries.filter { p.getBoolean(it.name, it == MapExtraLayer.ENCOUNTERS) }.toSet()
        }
    }

    fun toggle(layer: MapExtraLayer) {
        val next = if (layer in visible.value) visible.value - layer else visible.value + layer
        visible.value = next
        prefs?.edit()?.putBoolean(layer.name, layer in next)?.apply()
    }

    /**
     * Map tap at [p] (meters): opens the sheet of the nearest item close enough — bumper contact or presumed obstacle
     * (Obstacles layer), or saved obstacle (always drawn).
     */
    fun tap(p: Point, scale: Double): Boolean {
        val ui = latest
        return when (val hit = ObstacleSelection.pick(p, scale, ui.encounters, ui.presumed, latestObstacles, MapExtraLayer.ENCOUNTERS in visible.value)) {
            is MapPick.Encounter -> { openedObstacleId.value = null; opened.value = hit.event; true }
            is MapPick.Obstacle -> { opened.value = null; openedObstacleId.value = hit.obstacle.id; true }
            null -> false
        }
    }

    fun open(event: DifficultyEvent) { openedObstacleId.value = null; opened.value = event }
    fun openObstacle(id: String) { opened.value = null; openedObstacleId.value = id }
    fun close() { opened.value = null; openedObstacleId.value = null }

    /** Photo of a contact: same contact time, otherwise the nearest within 90 s. */
    fun photoFor(event: DifficultyEvent, photos: List<ObstaclePhoto>): ObstaclePhoto? {
        val at = event.contactAtMs ?: event.atMs
        return photos.firstOrNull { it.contactAt == at }
            ?: photos.filter { abs(it.contactAt - at) <= 90_000 }.minByOrNull { abs(it.contactAt - at) }
    }

    fun kindColor(kind: DifficultyKind): Color = when (kind) {
        DifficultyKind.CONTACT -> Color(0xFFDC2626)
        DifficultyKind.BLOCKED -> Color(0xFF7C3AED)
        DifficultyKind.SLIP -> Color(0xFFF59E0B)
        DifficultyKind.LIFT -> Color(0xFFDB2777)
        DifficultyKind.NO_PROGRESS -> Color(0xFF2563EB)
        DifficultyKind.DEVIATION -> Color(0xFF0D9488)
    }
}

/** Display text of a stored [DifficultyEvent.source] (stored values stay in French). */
fun difficultySourceLabel(source: String): String = when (source) {
    "" -> "—"
    "passerelle" -> tr("gateway", "passerelle")
    "trajet" -> tr("route", "trajet")
    "navigation" -> "navigation"
    "tonte native" -> tr("native mowing", "tonte native")
    else -> source
}

/** Difficulties heatmap color: yellow (low) → red (high), translucent. */
fun difficultyHeatColor(score: Double): Color {
    val s = (score / 1.5).coerceIn(0.0, 1.0).toFloat()
    return Color(0.95f, 0.85f * (1f - s) + 0.1f, 0.1f, 0.25f + 0.4f * s)
}

/**
 * Drawing of the visible layers: difficulties heatmap (1 m cells) and presumed obstacles (dashed circle) for
 * Difficulties; bumper contacts (red dot, circled in white when there is a photo) and presumed obstacles for Obstacles.
 */
fun DrawScope.drawDifficultyLayers(ui: DifficultyUi, visible: Set<MapExtraLayer>, photos: List<ObstaclePhoto>,
                                   screen: (Point) -> Offset, scale: Double, colors: LubaColors, unit: Float = 1f) {
    if (MapExtraLayer.DIFFICULTIES in visible && ui.events.isNotEmpty()) {
        ui.cells.forEach { cell ->
            val corner = screen(Point(cell.point.x - 0.5, cell.point.y + 0.5))
            drawRect(difficultyHeatColor(cell.score), corner, Size(scale.toFloat(), scale.toFloat()))
        }
        ui.events.filter { !it.dismissed && it.kind != DifficultyKind.CONTACT }.forEach { e ->
            val c = screen(e.point)
            drawCircle(Color.White, 4.5f * unit, c)
            drawCircle(MapExtraLayers.kindColor(e.kind), 3.25f * unit, c)
        }
    }
    // Presumed obstacle (repeated contacts): light red area, dashed outline.
    if (MapExtraLayer.ENCOUNTERS in visible || MapExtraLayer.DIFFICULTIES in visible) ui.presumed.forEach { o ->
        val r = ((o.exclusionRadiusM) * scale).toFloat().coerceAtLeast(6f * unit)
        val c = screen(Point(o.x, o.y))
        drawCircle(colors.Danger.copy(alpha = 0.10f), r, c)
        drawCircle(colors.Danger, r, c, style = Stroke(1.75f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit))))
    }
    // Bumper contact: red dot circled in white; white center dot when a photo exists.
    if (MapExtraLayer.ENCOUNTERS in visible) ui.encounters.forEach { e ->
        val c = screen(e.point)
        val photo = MapExtraLayers.photoFor(e, photos) != null
        drawCircle(Color.Black.copy(alpha = 0.18f), 8.5f * unit, c + Offset(0f, 1f * unit))
        drawCircle(Color.White, 8f * unit, c)
        drawCircle(MapExtraLayers.kindColor(DifficultyKind.CONTACT), 6f * unit, c)
        if (photo) drawCircle(Color.White, 2.25f * unit, c)
    }
}

/**
 * Sheet of an encountered obstacle: photo, date, bypass side, dismiss, or save as an obstacle with the chosen exclusion
 * size (type, object radius, margin around it). Sheet of a saved obstacle: [ObstacleEditSheet].
 */
@Composable
fun EncounterSheet(ui: DifficultyUi, photos: List<ObstaclePhoto>, obstacles: List<LocalObstacle> = emptyList()) {
    SideEffect { MapExtraLayers.latest = ui; MapExtraLayers.latestObstacles = obstacles }
    ObstacleEditSheet(obstacles)
    val opened by MapExtraLayers.openedEncounter.collectAsState()
    val event = opened ?: return
    var kind by remember(event.id) { mutableStateOf(ObstacleKind.OTHER) }
    var radius by remember(event.id) { mutableFloatStateOf(ObstacleSelection.defaultRadius(event, ui.presumed).toFloat()) }
    var margin by remember(event.id) { mutableFloatStateOf(fr.luba.poc.simulation.ObstacleBypass.CONTACT_MARGIN_M.toFloat()) }
    val photo = MapExtraLayers.photoFor(event, photos)
    val date = java.text.SimpleDateFormat(tr("yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm"), fr.luba.poc.i18n.I18n.locale).format(java.util.Date(event.atMs))
    LubaDialog(onDismissRequest = { MapExtraLayers.close() }, title = tr("Obstacle encountered", "Obstacle rencontré"), content = {
        if (photo != null) fr.luba.poc.ui.PhotoImage(photo, Modifier.fillMaxWidth().aspectRatio(4f / 3f), 1280)
        else Text(tr("No photo for this contact (option off, account not signed in or stream unavailable).", "Pas de photo pour ce contact (option désactivée, compte non connecté ou flux indisponible)."),
            style = MaterialTheme.typography.bodyMedium, color = MammotionColors.TextSecondary)
        KeyValueRow("Contact", date)
        KeyValueRow(tr("Origin", "Origine"), difficultySourceLabel(event.source))
        event.code?.let { KeyValueRow("Code", "$it") }
        KeyValueRow(tr("Estimated obstacle", "Obstacle estimé"), "${Format.num(event.point.x, 2)} / ${Format.num(event.point.y, 2)} m")
        event.side?.takeIf { it != 0 }?.let { side ->
            KeyValueRow(tr("Bypassed", "Contourné"), (if (side > 0) tr("on the left", "par la gauche") else tr("on the right", "par la droite")) + if (event.inner == true) tr(", inner side", ", côté intérieur") else "")
        }
        val around = ui.encounters.count { it.point.distance(event.point) <= fr.luba.poc.difficulty.PresumedObstacles.CLUSTER_M }
        if (around > 1) KeyValueRow(tr("Contacts at this place", "Contacts à cet endroit"), "$around")
        if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        Text(tr("Save the obstacle: size of the zone that routes will avoid around it (pole hit: choose Pole and a wider margin).", "Enregistrer l'obstacle : taille de la zone que les trajets éviteront autour de lui (poteau touché : choisir Poteau et une marge plus large)."),
            style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
        ObstacleSizeEditor(kind, { kind = it }, radius, { radius = it }, margin, { margin = it })
    }, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryPillButton(tr("Dismiss", "Écarter"), {
                MapExtraLayers.actions?.invoke(DifficultyAction.Dismiss(event.id)); MapExtraLayers.close()
            }, Modifier.weight(1f))
            PrimaryPillButton(tr("Save the obstacle", "Enregistrer l'obstacle"), {
                val now = System.currentTimeMillis()
                MapExtraLayers.actions?.invoke(DifficultyAction.Register(ObstacleSelection.fromEncounter(event, ui.presumed, kind,
                    radius.toDouble(), margin.toDouble(), java.util.UUID.randomUUID().toString(), now, date)))
                MapExtraLayers.close()
            }, Modifier.weight(1f))
        }
    })
}
