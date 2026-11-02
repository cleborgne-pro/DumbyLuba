package fr.luba.poc.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.Format
import fr.luba.poc.ui.components.NoteTone
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.components.SliderRow
import fr.luba.poc.ui.components.StatusNote
import fr.luba.poc.ui.theme.LubaColors
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Map tool of the painted zone: brush, eraser or plain view panning. */
internal enum class PaintTool(private val labelEn: String, private val labelFr: String) {
    PAINT("Paint", "Peindre"), ERASE("Erase", "Gommer"), MOVE("Move", "Déplacer");
    val label: String get() = tr(labelEn, labelFr)
}

/** Painted strokes kept for the session (the zone choice can be closed and reopened), for a single map. */
internal object PaintDraft {
    private var draft by mutableStateOf<Pair<RobotMap, List<PaintStroke>>?>(null)
    fun strokes(map: RobotMap): List<PaintStroke> = draft?.takeIf { it.first == map }?.second.orEmpty()
    fun update(map: RobotMap, change: (List<PaintStroke>) -> List<PaintStroke>) { draft = map to change(strokes(map)) }
}

/**
 * Zone choice by painting ([ZoneChoicePanel]): a zone painted with a finger replaces the tapped zones. Its retained area
 * ([PaintedZone.area]) becomes temporary zones added to the planning map only, selected in mowing order: the task
 * (settings, preview, simulation, real mowing, resume) then runs as for recorded zones, its route prepared and checked
 * on the unchanged robot map with the temporary zones as continuous-blade zones ([PaintedZone.bladeZones]).
 */
@Stable
internal class ZonePaintState {
    /** Painting chosen instead of tapping zones (mowing only, not the survey routes). */
    var active by mutableStateOf(false)
    var tool by mutableStateOf(PaintTool.PAINT)
    var brushM by mutableFloatStateOf(DEFAULT_BRUSH_M)
    /** Stroke being drawn, added to [PaintDraft] when the finger lifts. */
    var drawing by mutableStateOf<PaintStroke?>(null)
        private set
    var area by mutableStateOf(PaintedArea.EMPTY)
        private set
    /** Why the painting could not be turned into zones (too fragmented…); empty otherwise. */
    var error by mutableStateOf("")
        private set
    private var planning: Triple<RobotMap, List<MapShape>, RobotMap>? = null

    /** A painted target is chosen: the task plans its temporary zones. */
    val painting: Boolean get() = active && area.zones.isNotEmpty()
    val zoneIds: Set<Long> get() = area.zones.map { it.id }.toSet()

    /** Map the task is planned on: the robot map, plus the temporary zones while painting (same instance while unchanged). */
    fun planningMap(map: RobotMap): RobotMap {
        if (!painting) return map
        planning?.takeIf { it.first === map && it.second === area.zones }?.let { return it.third }
        return PaintedZone.withZones(map, area.zones).also { planning = Triple(map, area.zones, it) }
    }

    /** Finger input on the map while painting with the brush or the eraser; null with the move tool. */
    fun input(map: RobotMap): MapPaintInput? = if (!active || tool == PaintTool.MOVE) null else MapPaintInput(
        begin = { p -> drawing = PaintStroke(listOf(p), brushM.toDouble(), tool == PaintTool.ERASE) },
        extend = { p -> drawing?.let { s -> if (s.points.last().distance(p) >= s.radiusM / 4) drawing = s.copy(points = s.points + p) } },
        finish = { committed ->
            drawing?.let { s -> if (committed) PaintDraft.update(map) { it + s } }
            drawing = null
        },
    )

    internal fun retain(retained: PaintedArea, failure: String) { area = retained; error = failure }

    companion object {
        const val DEFAULT_BRUSH_M = 0.6f
        /** Pause after a stroke before computing the retained area again: a quick series of strokes computes it once. */
        const val RECOMPUTE_DELAY_MS = 150L
    }
}

/**
 * Painting state of the map screen, its retained area computed again after each stroke (temporary zones ordered from
 * [origin], the robot or the base, read at computation time); while painting, they are the zone selection of [frame].
 */
@Composable
internal fun rememberZonePaint(map: RobotMap, frame: MapFrameState, origin: Point?): ZonePaintState {
    val paint = remember { ZonePaintState() }
    val strokes = PaintDraft.strokes(map)
    val start by rememberUpdatedState(origin)
    LaunchedEffect(map, strokes, frame) {
        delay(ZonePaintState.RECOMPUTE_DELAY_MS)
        try {
            paint.retain(withContext(Dispatchers.Default) { PaintedZone.area(map, strokes, start) }, "")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { paint.retain(PaintedArea.EMPTY, e.message ?: tr("Painted zone unusable", "Zone peinte inutilisable")) }
        if (paint.active) frame.selected = paint.zoneIds
    }
    return paint
}

/** Painting tools of the zone choice: tool, brush size, retained area and why the painting is refused. */
@Composable
internal fun ZonePaintTools(paint: ZonePaintState, map: RobotMap, enabled: Boolean) {
    Segmented(PaintTool.entries.map { it to it.label }, paint.tool, { paint.tool = it }, enabled = enabled)
    SliderRow(tr("Brush size", "Taille du pinceau"), paint.brushM, { paint.brushM = it }, 0.3f..2f,
        valueLabel = "Ø ${Format.num(2.0 * paint.brushM, 1)} m", enabled = enabled)
    val zones = paint.area.zones.size
    Text(if (zones == 0) tr("Paint inside the zones: only that part is mowed.", "Peignez dans les zones : seule cette partie est tondue.")
        else tr("${Format.num(paint.area.areaM2, 1)} m² outside no-go zones", "${Format.num(paint.area.areaM2, 1)} m² hors no-go") +
            " · ${Format.plural(zones, tr("painted zone", "zone peinte"), tr("painted zones", "zones peintes"))}",
        style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)
    if (paint.error.isNotBlank()) StatusNote(paint.error, tone = NoteTone.DANGER)
    if (PaintDraft.strokes(map).isNotEmpty() && zones == 0 && paint.error.isBlank())
        StatusNote(tr("No painted area inside the zones (pieces under ${Format.num(PaintedZone.MIN_PIECE_M2, 1)} m² ignored).",
            "Aucune surface peinte à l'intérieur des zones (morceaux de moins de ${Format.num(PaintedZone.MIN_PIECE_M2, 1)} m² ignorés)."), tone = NoteTone.WARNING)
}

/** Painted strokes at half opacity, only inside [clip] (zones, no-go excluded); the eraser removes paint. */
internal fun DrawScope.drawPaintStrokes(paint: List<PaintStroke>, clip: Path, path: (List<Point>, Boolean) -> Path, screen: (Point) -> Offset, scale: Double, colors: LubaColors) {
    // Paint visible only inside the zones (no-go excluded): this is the kept area.
    clipPath(clip) {
        drawIntoCanvas { canvas ->
            // Separate layer: the eraser removes paint, and overlapping strokes keep the same tint.
            canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = 0.5f })
            paint.forEach { stroke ->
                val width = (2 * stroke.radiusM * scale).toFloat()
                val color = colors.Warning
                val blend = if (stroke.erase) BlendMode.Clear else BlendMode.SrcOver
                if (stroke.points.size == 1) drawCircle(color, width / 2, screen(stroke.points[0]), blendMode = blend)
                else drawPath(path(stroke.points, false), color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
            }
            canvas.restore()
        }
    }
}

/** Zone being painted on the map view: strokes (the one being drawn included) and orange outline of the retained area. */
internal fun DrawScope.drawZonePaint(paint: ZonePaintState, map: RobotMap, clip: Path, path: (List<Point>, Boolean) -> Path,
                                     screen: (Point) -> Offset, scale: Double, unit: Float, colors: LubaColors) {
    val strokes = PaintDraft.strokes(map) + listOfNotNull(paint.drawing)
    if (strokes.isNotEmpty()) drawPaintStrokes(strokes, clip, path, screen, scale, colors)
    paint.area.outlines.forEach { drawPath(path(it, true), colors.Warning, style = Stroke(2 * unit, join = StrokeJoin.Round)) }
}
