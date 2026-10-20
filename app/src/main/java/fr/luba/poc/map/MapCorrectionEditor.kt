package fr.luba.poc.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.ui.components.LubaDialog
import fr.luba.poc.ui.components.LubaSlider
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.components.Segmented
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.LubaColors
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Edit tool of the selected shape. */
internal enum class EditTool(private val en: String, private val fr: String) {
    POINTS("Points", "Points"), EDGE("Edge", "Bord"), SHAPE("Shape", "Forme"), OFFSET("Offset", "Décalage");
    val label: String get() = tr(en, fr)
}

/** Addition in progress: drawn no-go zone, simple-shape no-go zone, round obstacle. */
internal enum class Creating { TRACE, SHAPE, OBSTACLE }

/** Handle grabbed at the start of a drag. */
private sealed interface Grab {
    data class Vertex(val index: Int) : Grab
    data class Insert(val after: Int) : Grab
    data class Center(val dx: Double, val dy: Double) : Grab
    data object Resize : Grab
    data object Rotate : Grab
}

/** Preview computed off the UI thread: candidate correction, resulting map, added / removed areas. */
private class EditorPreview(
    val candidate: MapCorrection?,
    val result: CorrectedMap,
    val added: List<List<Point>>,
    val removed: List<List<Point>>,
    val beforeArea: Double?,
    val afterArea: Double?,
    val outsideM2: Double,
    /** Refused draft (self-crossing, too small): reason displayed, nothing to validate. */
    val refusal: String? = null,
    /** Editor state that produced this preview: a preview of an earlier state cannot be validated. */
    val key: List<Any?> = emptyList(),
)

/** Gesture functions of the last composition (the gesture block, started once, reads them again on each event). */
private class GestureHandlers(
    val view: EditorView,
    val hit: (Offset) -> Grab?,
    val dragStart: (Grab) -> Grab,
    val drag: (Grab, Point) -> Unit,
    val dragEnd: () -> Unit,
    val tap: (Offset) -> Unit,
)

/** Map (m) ↔ screen (px) mapping of the editor: framing of the Mammotion map then camera (zoom, rotation, panning). */
private class EditorView(val projection: MapProjection, val camera: MapCamera, val center: Point) {
    val pxPerMeter: Double get() = projection.scale * camera.zoom
    fun screen(p: Point): Offset {
        val l = projection.screen(p)
        val a = Math.toRadians(camera.rotation)
        val dx = (l.x - center.x) * camera.zoom
        val dy = (l.y - center.y) * camera.zoom
        return Offset((center.x + camera.offset.x + dx * cos(a) - dy * sin(a)).toFloat(),
            (center.y + camera.offset.y + dx * sin(a) + dy * cos(a)).toFloat())
    }
    fun map(o: Offset): Point {
        val l = camera.inverse(Point(o.x.toDouble(), o.y.toDouble()), center)
        return projection.map(Offset(l.x.toFloat(), l.y.toFloat()))
    }
}

/**
 * Full-screen editor of the map's **local corrections**: edit of a zone or a no-go zone (points, redrawn edge, shape
 * added or removed, offset), new no-go zones (drawing, shape, obstacle) and list of corrections (disable, show, edit,
 * delete, restore all). The Mammotion map [raw] is never modified: [onSave] receives the full list of corrections and
 * returns false if it could not be saved.
 */
@Composable
fun MapCorrectionEditor(
    raw: RobotMap,
    corrections: List<MapCorrection>,
    onSave: (List<MapCorrection>) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MammotionColors
    val density = LocalDensity.current
    val corrected = remember(raw, corrections) { MapCorrections.apply(raw, corrections) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var tool by remember { mutableStateOf(EditTool.POINTS) }
    var creating by remember { mutableStateOf<Creating?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    // Boundary being edited (Points, Edge, no-go drawing), its starting point and the history for ↶.
    var draft by remember { mutableStateOf<List<Point>?>(null) }
    var draftBase by remember { mutableStateOf<List<Point>?>(null) }
    var history by remember { mutableStateOf(listOf<List<Point>>()) }
    var usedPoints by remember { mutableStateOf(false) }
    var usedEdge by remember { mutableStateOf(false) }
    var edgeLine by remember { mutableStateOf(listOf<Point>()) }
    var otherSide by remember { mutableStateOf(false) }
    var selectedVertex by remember { mutableStateOf<Int?>(null) }
    var primitive by remember { mutableStateOf<PrimitiveShape?>(null) }
    var removing by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(0f) }
    var obstacleKind by remember { mutableStateOf(ObstacleKind.TREE) }
    var listOpen by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var undoList by remember { mutableStateOf<List<MapCorrection>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(MapCamera()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }

    val target: MapShape? = selectedId?.let { id -> corrected.map.shapes.firstOrNull { it.id == id } }
    val editing: MapCorrection? = editingId?.let { id -> corrections.firstOrNull { it.id == id } }
    val bounds = remember(raw) { MapBounds.of(raw.shapes.flatMap { it.points }) }
    val view = run {
        val w = size.width.toFloat().coerceAtLeast(1f)
        val h = size.height.toFloat().coerceAtLeast(1f)
        val top = with(density) { 96.dp.toPx() }
        val proj = mapScreenProjection(bounds, androidx.compose.ui.geometry.Size(w, h), top, h * 0.40f)
        EditorView(proj, camera, Point(w / 2.0, h / 2.0))
    }

    fun resetEdit() {
        selectedId = null; tool = EditTool.POINTS; creating = null; editingId = null
        draft = null; draftBase = null; history = emptyList(); usedPoints = false; usedEdge = false
        edgeLine = emptyList(); otherSide = false; selectedVertex = null; primitive = null; removing = false; offset = 0f
    }

    fun commit(list: List<MapCorrection>, message: String, undoable: Boolean = false) {
        val before = corrections
        if (onSave(list)) {
            undoList = if (undoable) before else null
            notice = message
            resetEdit()
        } else notice = tr("Could not save", "Enregistrement impossible")
    }

    /** Primitive placed at the center of the screen, at a readable size. */
    fun newPrimitive(kind: PrimitiveKind, sizeM: Double): PrimitiveShape {
        val c = view.map(Offset(view.center.x.toFloat(), (size.height * 0.30f)))
        return PrimitiveShape(kind, c, sizeM, sizeM)
    }

    fun startTool(t: EditTool, shape: MapShape? = target) {
        if (shape == null) return
        val pointTools = setOf(EditTool.POINTS, EditTool.EDGE)
        if (t !in pointTools && tool in pointTools && draft != null && draft != draftBase) {
            notice = tr("Validate or discard the current edit before changing tools", "Validez ou abandonnez la retouche en cours avant de changer d'outil"); return
        }
        if (t != EditTool.SHAPE) primitive = null
        if (t != EditTool.OFFSET) offset = 0f
        if (t !in pointTools) { draft = null; draftBase = null; history = emptyList() }
        tool = t; selectedVertex = null; edgeLine = emptyList()
        if ((t == EditTool.POINTS || t == EditTool.EDGE) && draft == null) { draft = shape.points; draftBase = shape.points }
        if (t == EditTool.SHAPE && primitive == null) primitive = newPrimitive(PrimitiveKind.SQUARE, 1.0)
    }

    fun pushHistory() { draft?.let { history = history + listOf(it) } }

    fun reopen(c: MapCorrection) {
        resetEdit(); listOpen = false; editingId = c.id
        when (c) {
            is ShapeOffset -> { selectedId = c.target; tool = EditTool.OFFSET; offset = c.distanceM.toFloat() }
            is ShapePatch -> { selectedId = c.target; tool = EditTool.SHAPE; primitive = c.primitive; removing = c.removing }
            is AddedNoGo -> when (c.origin) {
                NoGoOrigin.TRACE -> { creating = Creating.TRACE; draft = c.points }
                NoGoOrigin.SHAPE -> { creating = Creating.SHAPE; primitive = c.primitive }
                NoGoOrigin.OBSTACLE -> {
                    creating = Creating.OBSTACLE; primitive = c.primitive
                    obstacleKind = ObstacleKind.entries.firstOrNull { it.matches(c.label) } ?: ObstacleKind.OTHER
                }
            }
            // DumbyLuba zone or passage: not reopened (its outline is edited by tapping it on the map, as a new correction).
            is AddedZone, is AddedPassage -> Unit
        }
    }

    // Candidate correction and resulting map, recomputed after a short pause (smooth dragging).
    val previewKey = listOf<Any?>(corrections, raw, selectedId, tool, creating, editingId, draft, draftBase, primitive, removing, offset, obstacleKind)
    val preview by produceState<EditorPreview?>(null, corrections, raw, selectedId, tool, creating, editingId, draft, draftBase,
        primitive, removing, offset, obstacleKind, dragging) {
        if (dragging) return@produceState
        val key = previewKey
        delay(40)
        value = withContext(Dispatchers.Default) {
            runCatching {
                val draftKind = if (creating == Creating.TRACE) ShapeKind.NO_GO else target?.kind ?: ShapeKind.ZONE
                val drafted = creating == Creating.TRACE || (creating == null && target != null && (tool == EditTool.POINTS || tool == EditTool.EDGE))
                val refusal = draft?.takeIf { drafted && it.size >= 3 }?.let { (MapCorrections.check(it, draftKind) as? ShapeResult.Refused)?.reason }
                val candidate = if (refusal != null) null else candidate(corrections, raw, target, editing, editingId, tool, creating, draft, draftBase,
                    usedPoints, usedEdge, primitive, removing, offset.toDouble(), obstacleKind)
                val list = when {
                    candidate == null -> corrections
                    editingId != null -> corrections.map { if (it.id == candidate.id) candidate else it }
                    else -> corrections + candidate
                }
                val result = if (candidate == null) corrected else MapCorrections.apply(raw, list)
                val shapeId = candidate?.let(MapCorrections::shapeOf)
                val after = shapeId?.let { id -> result.map.shapes.firstOrNull { it.id == id } }
                val original = shapeId?.let { id -> raw.shapes.firstOrNull { it.id == id } }
                val before = shapeId?.let { id -> corrected.map.shapes.firstOrNull { it.id == id } }
                val (add, cut) = if (after != null && original != null) MapCorrections.diff(original.points, after.points) else "" to ""
                EditorPreview(candidate, result, MapCorrections.rings(add), MapCorrections.rings(cut),
                    before?.points?.let(MapCorrections::polygonArea), after?.points?.let(MapCorrections::polygonArea),
                    if (after != null && original != null && after.kind == ShapeKind.ZONE) MapCorrections.outside(after.points, original.points) else 0.0,
                    refusal, key)
            }.getOrNull()
        }
    }

    val freshPreview = preview?.takeIf { it.key == previewKey }
    val drafting = creating == Creating.TRACE || (target != null && (tool == EditTool.POINTS || tool == EditTool.EDGE))
    val draftedId = when {
        creating == Creating.TRACE -> editingId?.let(MapCorrections::shapeId)
        drafting -> selectedId
        else -> null
    }
    val shownMap = when {
        showOriginal -> raw
        drafting -> corrected.map.copy(shapes = corrected.map.shapes.filter { it.id != draftedId })
        else -> preview?.result?.map ?: corrected.map
    }
    val primitiveShown = primitive != null && (creating == Creating.SHAPE || creating == Creating.OBSTACLE || (target != null && tool == EditTool.SHAPE))
    val handlePx = with(density) { 22.dp.toPx() }
    val spacingPx = with(density) { 26.dp.toPx() }
    val rotateExtraPx = with(density) { 36.dp.toPx() }

    fun vertexHandles(points: List<Point>): List<Int> =
        PointEditing.handles(points, spacingPx.toDouble()) { p -> view.screen(p).let { Point(it.x.toDouble(), it.y.toDouble()) } }

    fun insertMarks(points: List<Point>, handles: List<Int>): List<Pair<Int, Point>> {
        val n = points.size
        return handles.mapNotNull { i ->
            val j = (i + 1) % n
            if (j !in handles) return@mapNotNull null
            val a = view.screen(points[i]); val b = view.screen(points[j])
            if ((a - b).getDistance() < spacingPx * 2.2f) null else i to Point((points[i].x + points[j].x) / 2, (points[i].y + points[j].y) / 2)
        }
    }

    fun rotateHandle(p: PrimitiveShape): Point = p.toMap(Point(0.0, p.heightM / 2 + rotateExtraPx / view.pxPerMeter))
    fun resizeHandle(p: PrimitiveShape): Point = if (p.kind == PrimitiveKind.CIRCLE) p.toMap(Point(p.widthM / 2, 0.0)) else p.corner()

    fun hit(o: Offset): Grab? {
        val reach = handlePx * 1.2f
        primitive?.takeIf { primitiveShown }?.let { p ->
            if (p.kind != PrimitiveKind.CIRCLE && (view.screen(rotateHandle(p)) - o).getDistance() <= reach) return Grab.Rotate
            if ((view.screen(resizeHandle(p)) - o).getDistance() <= reach) return Grab.Resize
            val m = view.map(o)
            if (Geometry.inside(m, p.polygon()) || (view.screen(p.center) - o).getDistance() <= reach) return Grab.Center(m.x - p.center.x, m.y - p.center.y)
        }
        val points = draft
        if (points != null && (creating == Creating.TRACE || (target != null && tool == EditTool.POINTS))) {
            val handles = if (creating == Creating.TRACE) points.indices.toList() else vertexHandles(points)
            handles.filter { (view.screen(points[it]) - o).getDistance() <= reach }
                .minByOrNull { (view.screen(points[it]) - o).getDistance() }?.let { return Grab.Vertex(it) }
            if (points.size >= 3) insertMarks(points, handles).firstOrNull { (view.screen(it.second) - o).getDistance() <= reach }
                ?.let { return Grab.Insert(it.first) }
        }
        return null
    }

    fun dragStart(g: Grab): Grab {
        dragging = true
        return when (g) {
            is Grab.Insert -> { pushHistory(); draft = PointEditing.insertAfter(requireNotNull(draft), g.after); usedPoints = true; Grab.Vertex(g.after + 1) }
            is Grab.Vertex -> { pushHistory(); usedPoints = true; g }
            else -> g
        }
    }

    fun drag(g: Grab, m: Point) {
        val p = primitive
        when (g) {
            is Grab.Vertex -> draft = draft?.let { PointEditing.move(it, g.index, m) }
            is Grab.Center -> if (p != null) primitive = p.copy(center = Point(m.x - g.dx, m.y - g.dy))
            Grab.Resize -> if (p != null) {
                val local = p.toLocal(m)
                val minS = PrimitiveShape.MIN_SIZE_M
                val maxS = PrimitiveShape.MAX_SIZE_M
                primitive = if (p.kind == PrimitiveKind.CIRCLE) {
                    val d = (2 * m.distance(p.center)).coerceIn(minS, maxS)
                    p.copy(widthM = d, heightM = d)
                } else p.copy(widthM = (2 * kotlin.math.abs(local.x)).coerceIn(minS, maxS), heightM = (2 * kotlin.math.abs(local.y)).coerceIn(minS, maxS))
            }
            Grab.Rotate -> if (p != null) {
                val deg = Math.toDegrees(atan2(m.y - p.center.y, m.x - p.center.x)) - 90.0
                primitive = p.copy(rotationDeg = MapCamera.signedDegrees(deg))
            }
            is Grab.Insert -> Unit
        }
    }

    fun tap(o: Offset) {
        val m = view.map(o)
        notice = null
        when {
            creating == Creating.TRACE -> { pushHistory(); draft = (draft ?: emptyList()) + m }
            creating == Creating.SHAPE || creating == Creating.OBSTACLE -> primitive = primitive?.copy(center = m)
            target != null && tool == EditTool.EDGE -> edgeLine = edgeLine + m
            target != null && tool == EditTool.SHAPE -> primitive = primitive?.copy(center = m)
            target != null && tool == EditTool.POINTS -> {
                val g = hit(o)
                selectedVertex = when (g) {
                    is Grab.Vertex -> g.index
                    is Grab.Insert -> { pushHistory(); draft = PointEditing.insertAfter(requireNotNull(draft), g.after); usedPoints = true; g.after + 1 }
                    else -> null
                }
            }
            target != null -> Unit
            else -> {
                // Selection: a (smaller) no-go zone before the zone containing it.
                val shapes = corrected.map.shapes
                val pick = shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 && Geometry.inside(m, it.points) }
                    .minByOrNull { MapCorrections.polygonArea(it.points) }
                    ?: shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 && Geometry.inside(m, it.points) }
                        .minByOrNull { MapCorrections.polygonArea(it.points) }
                if (pick != null) { selectedId = pick.id; listOpen = false; tool = EditTool.POINTS; startTool(EditTool.POINTS, pick) }
            }
        }
    }

    fun focus(points: List<Point>) {
        if (points.isEmpty() || size.width == 0) return
        val c = Point((points.minOf { it.x } + points.maxOf { it.x }) / 2, (points.minOf { it.y } + points.maxOf { it.y }) / 2)
        val extent = max(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1.0)
        val zoom = (size.width * 0.6 / (extent * view.projection.scale)).coerceIn(0.5, 20.0)
        val layer = view.projection.screen(c)
        val screenTarget = Point(view.center.x, size.height * 0.30)
        camera = MapCamera(zoom, camera.rotation).following(Point(layer.x.toDouble(), layer.y.toDouble()), view.center, screenTarget)
    }

    val handlers by rememberUpdatedState(GestureHandlers(view, ::hit, ::dragStart, ::drag, { dragging = false }, ::tap))

    BackHandler {
        when {
            listOpen -> listOpen = false
            target != null || creating != null -> resetEdit()
            else -> onClose()
        }
    }

    Box(modifier.fillMaxSize().background(colors.MapBackground)) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val grabbed = handlers.hit(down.position)
                var active: Grab? = null
                var moved = false
                var multi = false
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) {
                        multi = true
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (centroid != Offset.Unspecified) {
                            val pan = event.calculatePan()
                            camera = camera.transform(Point(centroid.x.toDouble(), centroid.y.toDouble()), handlers.view.center,
                                Point(pan.x.toDouble(), pan.y.toDouble()), event.calculateZoom().toDouble(), event.calculateRotation().toDouble())
                        }
                    } else if (!multi) {
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && change.pressed) {
                            if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                if (grabbed != null) active = handlers.dragStart(grabbed)
                            }
                            if (moved) {
                                val a = active
                                if (a != null) handlers.drag(a, handlers.view.map(change.position))
                                else camera = camera.copy(offset = Point(camera.offset.x + change.positionChange().x, camera.offset.y + change.positionChange().y))
                            }
                        }
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                if (active != null) handlers.dragEnd()
                if (!moved && !multi) handlers.tap(down.position)
            }
        }) {
            val unit = 1.dp.toPx()
            fun path(points: List<Point>, closed: Boolean) = Path().apply {
                points.forEachIndexed { i, p -> val s = view.screen(p); if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y) }
                if (closed) close()
            }
            drawLawn(shownMap, { it.id == selectedId }, ::path, unit, colors)
            if (!showOriginal) drawCorrectionMarks(raw, corrected, selectedId, ::path, unit, colors)
            preview?.takeIf { !showOriginal && !drafting }?.let { drawAreas(it.added, it.removed, ::path, unit, colors) }
            raw.station?.let { s -> drawCircle(colors.Ink, 6 * unit, view.screen(s.point)); drawCircle(Color.White, 3 * unit, view.screen(s.point)) }
            if (showOriginal) return@Canvas
            // Boundary being edited: filled according to its kind, green edging, handles.
            val points = draft
            if (drafting && points != null) {
                val noGo = creating == Creating.TRACE || target?.kind == ShapeKind.NO_GO
                if (points.size >= 3) drawPath(path(points, true), (if (noGo) colors.NoGoFill else colors.ZoneFillSelected).copy(alpha = 0.85f))
                if (points.size >= 2) drawPath(path(points, points.size >= 3), colors.Green, style = Stroke(2.5f * unit))
                if (tool == EditTool.EDGE && creating == null) drawEdgeLine(points, edgeLine, otherSide, ::path, view::screen, unit, colors)
                else {
                    val handles = if (creating == Creating.TRACE) points.indices.toList() else vertexHandles(points)
                    if (points.size >= 3) insertMarks(points, handles).forEach { (_, p) ->
                        drawCircle(Color.White.copy(alpha = 0.9f), 8 * unit, view.screen(p))
                        drawCircle(colors.Green, 8 * unit, view.screen(p), style = Stroke(1.5f * unit))
                        val s = view.screen(p)
                        drawLine(colors.Green, s - Offset(4 * unit, 0f), s + Offset(4 * unit, 0f), 1.5f * unit)
                        drawLine(colors.Green, s - Offset(0f, 4 * unit), s + Offset(0f, 4 * unit), 1.5f * unit)
                    }
                    handles.forEach { i ->
                        val s = view.screen(points[i])
                        val chosen = i == selectedVertex
                        drawCircle(Color.White, (if (chosen) 11f else 8f) * unit, s)
                        drawCircle(if (chosen) colors.Warning else colors.Green, (if (chosen) 11f else 8f) * unit, s, style = Stroke(2.5f * unit))
                    }
                }
            }
            primitive?.takeIf { primitiveShown }?.let { p ->
                val tone = if (removing && creating == null) colors.Danger else colors.Green
                drawPath(path(p.polygon(), true), tone.copy(alpha = 0.18f))
                drawPath(path(p.polygon(), true), tone, style = Stroke(2.5f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * unit, 5 * unit))))
                drawCircle(Color.White, 9 * unit, view.screen(p.center)); drawCircle(tone, 9 * unit, view.screen(p.center), style = Stroke(2.5f * unit))
                val r = view.screen(resizeHandle(p))
                drawRect(Color.White, r - Offset(8 * unit, 8 * unit), androidx.compose.ui.geometry.Size(16 * unit, 16 * unit))
                drawRect(tone, r - Offset(8 * unit, 8 * unit), androidx.compose.ui.geometry.Size(16 * unit, 16 * unit), style = Stroke(2.5f * unit))
                if (p.kind != PrimitiveKind.CIRCLE) {
                    val top = view.screen(p.toMap(Point(0.0, p.heightM / 2)))
                    val rot = view.screen(rotateHandle(p))
                    drawLine(tone, top, rot, 1.5f * unit)
                    drawCircle(Color.White, 9 * unit, rot); drawCircle(tone, 9 * unit, rot, style = Stroke(2.5f * unit))
                    drawArc(tone, -150f, 120f, false, rot - Offset(5 * unit, 5 * unit), androidx.compose.ui.geometry.Size(10 * unit, 10 * unit), style = Stroke(1.5f * unit))
                }
            }
        }

        EditorHeader(corrections.count { it.enabled }, onBack = { if (target != null || creating != null) resetEdit() else onClose() },
            onOriginal = { showOriginal = it }, onList = { listOpen = !listOpen; if (listOpen) resetEdit() },
            modifier = Modifier.align(Alignment.TopCenter))

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(Dimens.Md),
            verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            notice?.let { text ->
                NoticeBar(text, undoList?.let { previous -> { if (onSave(previous)) { notice = tr("Correction restored", "Correction rétablie"); undoList = null } } },
                    onDismiss = { notice = null; undoList = null })
            }
            EditorPanel {
                when {
                    listOpen -> CorrectionList(corrections, corrected, onToggle = { c, on ->
                        commit(corrections.map { if (it.id == c.id) it.withEnabled(on) else it }, if (on) tr("Correction enabled again", "Correction réactivée") else tr("Correction disabled", "Correction désactivée"))
                        listOpen = true
                    }, onShow = { c ->
                        val id = MapCorrections.shapeOf(c)
                        corrected.map.shapes.firstOrNull { it.id == id }?.let { focus(it.points) }
                    }, onEdit = { reopen(it) }, onDelete = { c ->
                        val n = MapCorrections.dependents(corrections, c.id)
                        commit(MapCorrections.remove(corrections, c.id), if (n > 1) tr("Correction and ${n - 1} linked edit(s) deleted", "Correction et ${n - 1} retouche(s) liée(s) supprimées") else tr("Correction deleted", "Correction supprimée"), undoable = true)
                        listOpen = true
                    }, onResetAll = { confirmReset = true })
                    creating != null -> CreatePanel(creating!!, editing != null, draft, primitive, obstacleKind, freshPreview,
                        onKind = { k -> primitive = primitive?.let { PrimitiveShape(k, it.center, it.widthM, it.heightM, it.rotationDeg) } },
                        onObstacle = { k -> obstacleKind = k; primitive = primitive?.let { it.copy(widthM = k.defaultRadiusM * 2 + 0.2, heightM = k.defaultRadiusM * 2 + 0.2) } },
                        onRadius = { r -> primitive = primitive?.copy(widthM = r * 2, heightM = r * 2) },
                        onUndo = { history.lastOrNull()?.let { draft = it; history = history.dropLast(1) } ?: run { draft = draft?.dropLast(1) } },
                        onCancel = { resetEdit() },
                        onValidate = { c -> commit(if (editingId != null) corrections.map { if (it.id == c.id) c else it } else corrections + c,
                            tr("Correction saved: ${c.label}", "Correction enregistrée : ${c.label}") + stationWarning(raw, c)) })
                    target != null -> ShapePanel(target, tool, editing != null, draft, draftBase, edgeLine, otherSide, selectedVertex, primitive,
                        removing, offset, freshPreview, history.isNotEmpty(),
                        onTool = { startTool(it) },
                        onEdgeApply = {
                            val replaced = draft?.let { EdgeRedraw.replace(it, edgeLine, otherSide) }
                            if (replaced != null) { pushHistory(); draft = replaced; usedEdge = true; edgeLine = emptyList(); otherSide = false }
                            else notice = tr("Edge not possible: ends too close", "Bord impossible : extrémités trop proches")
                        },
                        onEdgeUndo = { edgeLine = edgeLine.dropLast(1) },
                        onOtherSide = { otherSide = !otherSide },
                        onDeleteVertex = { i -> draft?.let { PointEditing.remove(it, i) }?.let { pushHistory(); draft = it; usedPoints = true; selectedVertex = null } },
                        onUndo = { history.lastOrNull()?.let { draft = it; history = history.dropLast(1); selectedVertex = null } },
                        onKind = { k -> primitive = primitive?.let { PrimitiveShape(k, it.center, it.widthM, it.heightM, it.rotationDeg) } },
                        onRemoving = { removing = it },
                        onOffset = { offset = it },
                        onCancel = { resetEdit() },
                        onValidate = { c -> commit(if (editingId != null) corrections.map { if (it.id == c.id) c else it } else corrections + c,
                            tr("Correction saved: ${c.label}", "Correction enregistrée : ${c.label}") + stationWarning(raw, c)) })
                    else -> BrowsePanel(corrections.size,
                        onNoGo = { creating = Creating.TRACE; draft = emptyList(); history = emptyList() },
                        onShape = { creating = Creating.SHAPE; primitive = newPrimitive(PrimitiveKind.SQUARE, 1.0) },
                        onObstacle = { creating = Creating.OBSTACLE; obstacleKind = ObstacleKind.TREE
                            primitive = newPrimitive(PrimitiveKind.CIRCLE, ObstacleKind.TREE.defaultRadiusM * 2 + 0.2) },
                        onList = { listOpen = true })
                }
            }
        }
    }

    if (confirmReset) LubaDialog(onDismissRequest = { confirmReset = false }, title = tr("Restore everything?", "Tout rétablir ?"), content = {
        Text(tr("The ${corrections.size} local corrections are deleted: the map becomes the Mammotion one again. " +
            "\"Undo\" in the next message restores them.",
            "Les ${corrections.size} corrections locales sont supprimées : la carte redevient celle de Mammotion. " +
            "« Annuler » dans le message suivant les rétablit."), style = MaterialTheme.typography.bodyMedium)
    }, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            SecondaryPillButton(tr("Keep", "Garder"), { confirmReset = false }, Modifier.weight(1f))
            PrimaryPillButton(tr("Restore everything", "Tout rétablir"), { confirmReset = false; commit(emptyList(), tr("Mammotion map restored", "Carte Mammotion rétablie"), undoable = true); listOpen = true }, Modifier.weight(1f))
        }
    })

    LaunchedEffect(notice) { if (notice != null && undoList == null) { delay(4000); notice = null } }
}

/** Warning if a correction brings a no-go zone close to the station (docking hindered). */
private fun stationWarning(raw: RobotMap, c: MapCorrection): String {
    val station = raw.station?.point ?: return ""
    val map = MapCorrections.apply(raw, listOf(c)).map
    val shapeId = MapCorrections.shapeOf(c) ?: return ""
    val shape = map.shapes.firstOrNull { it.id == shapeId && it.kind == ShapeKind.NO_GO } ?: return ""
    val near = Geometry.inside(station, shape.points) ||
        shape.points.indices.any { i -> Geometry.distance(station, shape.points[i], shape.points[(i + 1) % shape.points.size]) < 1.0 }
    return if (near) tr(" · warning: no-go zone less than 1 m from the station", " · attention : no-go à moins de 1 m de la station") else ""
}

/** Builds the correction described by the editor state; null as long as nothing has changed. */
private fun candidate(
    corrections: List<MapCorrection>, raw: RobotMap, target: MapShape?, editing: MapCorrection?, editingId: Long?,
    tool: EditTool, creating: Creating?, draft: List<Point>?, draftBase: List<Point>?, usedPoints: Boolean, usedEdge: Boolean,
    primitive: PrimitiveShape?, removing: Boolean, offset: Double, obstacleKind: ObstacleKind,
): MapCorrection? {
    val id = editingId ?: MapCorrections.nextId(corrections)
    val created = editing?.createdAtMs ?: System.currentTimeMillis()
    val enabled = editing?.enabled ?: true
    if (creating != null) return when (creating) {
        Creating.TRACE -> draft?.takeIf { it.size >= 3 }?.let {
            AddedNoGo(id, created, tr("Drawn no-go · ${MapCorrections.squareMeters(MapCorrections.polygonArea(it))}", "No-go tracée · ${MapCorrections.squareMeters(MapCorrections.polygonArea(it))}"), it, NoGoOrigin.TRACE, enabled = enabled)
        }
        Creating.SHAPE -> primitive?.let { AddedNoGo(id, created, tr("${it.kind.label} no-go · ${it.sizeText()}", "No-go ${it.kind.label.lowercase()} · ${it.sizeText()}"), it.polygon(), NoGoOrigin.SHAPE, it, enabled) }
        Creating.OBSTACLE -> primitive?.let { AddedNoGo(id, created, "${obstacleKind.label} · ${it.sizeText()}", it.polygon(), NoGoOrigin.OBSTACLE, it, enabled) }
    }
    val shape = target ?: return null
    val name = MapCorrections.shapeName(shape)
    val fingerprint = raw.shapes.firstOrNull { it.id == shape.id }?.let(MapCorrections::fingerprint)
    return when (tool) {
        EditTool.SHAPE -> primitive?.let { p ->
            val wkt = MapCorrections.primitiveWkt(p)
            ShapePatch(id, created, if (removing) tr("$name · ${p.kind.label.lowercase()} removed ${p.sizeText()}", "$name · ${p.kind.label.lowercase()} retiré ${p.sizeText()}")
                else tr("$name · ${p.kind.label.lowercase()} added ${p.sizeText()}", "$name · ${p.kind.label.lowercase()} ajouté ${p.sizeText()}"), shape.id, fingerprint,
                if (removing) "" else wkt, if (removing) wkt else "", CorrectionTool.SHAPE, p, removing, enabled)
        }
        EditTool.OFFSET -> offset.takeIf { kotlin.math.abs(it) >= 0.01 }?.let {
            ShapeOffset(id, created, tr("$name · offset ${MapCorrections.centimeters(it)}", "$name · décalage ${MapCorrections.centimeters(it)}"), shape.id, fingerprint, it, enabled)
        }
        EditTool.POINTS, EditTool.EDGE -> {
            val before = draftBase ?: return null
            val after = draft ?: return null
            if (after == before) return null
            // Self-crossing boundary: refusal shown by the preview, no correction offered.
            if (MapCorrections.check(after, shape.kind) is ShapeResult.Refused) return null
            val (add, cut) = MapCorrections.diff(before, after)
            if (add.isEmpty() && cut.isEmpty()) return null
            val what = when {
                usedEdge && usedPoints -> tr("boundary edited", "contour retouché")
                usedEdge -> tr("edge redrawn", "bord redessiné")
                else -> tr("points moved", "points modifiés")
            }
            ShapePatch(id, created, "$name · $what (${MapCorrections.balance(add, cut)})", shape.id, fingerprint, add, cut,
                if (usedEdge && !usedPoints) CorrectionTool.EDGE else CorrectionTool.POINTS, enabled = enabled)
        }
    }
}

// ------------------------------------------------------------------------------------------------ drawing

private fun DrawScope.drawCorrectionMarks(raw: RobotMap, corrected: CorrectedMap, selectedId: Long?, path: (List<Point>, Boolean) -> Path, unit: Float,
                                          colors: LubaColors) {
    // Original Mammotion boundary as grey dashes under each corrected shape.
    corrected.edited.keys.forEach { id ->
        raw.shapes.firstOrNull { it.id == id }?.let { original ->
            drawPath(path(original.points, true), colors.TextSecondary, style = Stroke(1.5f * unit,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * unit, 4 * unit))))
        }
    }
    selectedId?.let { id -> corrected.map.shapes.firstOrNull { it.id == id } }?.let { shape ->
        drawPath(path(shape.points, true), colors.Green, style = Stroke(3f * unit))
    }
}

private fun DrawScope.drawAreas(added: List<List<Point>>, removed: List<List<Point>>, path: (List<Point>, Boolean) -> Path, unit: Float,
                                colors: LubaColors) {
    fun fill(rings: List<List<Point>>, tone: Color) {
        if (rings.isEmpty()) return
        val p = Path().apply { fillType = PathFillType.EvenOdd; rings.filter { it.size >= 3 }.forEach { addPath(path(it, true)) } }
        drawPath(p, tone.copy(alpha = 0.30f))
        drawPath(p, tone, style = Stroke(1.5f * unit))
    }
    fill(added, colors.Green)
    fill(removed, colors.Danger)
}

private fun DrawScope.drawEdgeLine(ring: List<Point>, line: List<Point>, otherSide: Boolean, path: (List<Point>, Boolean) -> Path,
                                   screen: (Point) -> Offset, unit: Float, colors: LubaColors) {
    if (line.isEmpty()) return
    val snapped = line.toMutableList()
    if (ring.size >= 3) {
        snapped[0] = EdgeRedraw.project(ring, line.first()).point
        if (line.size >= 2) snapped[snapped.lastIndex] = EdgeRedraw.project(ring, line.last()).point
    }
    if (line.size >= 2) EdgeRedraw.replace(ring, line, otherSide)?.let { result ->
        drawPath(path(result, true), colors.Warning, style = Stroke(2f * unit, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * unit, 4 * unit))))
    }
    drawPath(path(snapped, false), colors.Ink, style = Stroke(2.5f * unit))
    snapped.forEach { drawCircle(Color.White, 7 * unit, screen(it)); drawCircle(colors.Ink, 7 * unit, screen(it), style = Stroke(2f * unit)) }
}

// ------------------------------------------------------------------------------------------------ panels

@Composable
private fun EditorHeader(active: Int, onBack: () -> Unit, onOriginal: (Boolean) -> Unit, onList: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    Surface(modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.Md), shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", "Retour")) }
            Column(Modifier.weight(1f)) {
                Text(tr("Correct the map", "Corriger la carte"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Local correction · the Mammotion map is not modified", "Correction locale · la carte Mammotion n'est pas modifiée"), style = MaterialTheme.typography.bodySmall,
                    color = colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // Press and hold: the original Mammotion map, to compare.
            Surface(shape = CircleShape, color = colors.SurfaceMuted, modifier = Modifier.size(40.dp).pointerInput(Unit) {
                detectTapGestures(onPress = { onOriginal(true); tryAwaitRelease(); onOriginal(false) })
            }) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Visibility, contentDescription = tr("Hold to see the original", "Maintenir pour voir l'original")) } }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onList) {
                Box {
                    Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Corrections")
                    if (active > 0) Surface(shape = CircleShape, color = colors.Green, modifier = Modifier.align(Alignment.TopEnd).size(16.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("$active", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MammotionColors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Sm), content = content)
    }
}

@Composable
private fun NoticeBar(text: String, onUndo: (() -> Unit)?, onDismiss: () -> Unit) {
    val colors = MammotionColors
    Surface(shape = MaterialTheme.shapes.medium, color = colors.Ink, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Dimens.Lg, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = colors.OnInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onUndo != null) TextButton(onClick = onUndo) { Text(tr("Undo", "Annuler"), color = colors.Green) }
            TextButton(onClick = onDismiss) { Text("OK", color = colors.OnInk) }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)

@Composable
private fun BrowsePanel(count: Int, onNoGo: () -> Unit, onShape: () -> Unit, onObstacle: () -> Unit, onList: () -> Unit) {
    Text(tr("Tap a zone or a no-go zone to correct", "Touchez une zone ou une no-go à corriger"), style = MaterialTheme.typography.titleSmall)
    Hint(tr("One finger moves the map, two fingers zoom and rotate. Or add:", "Un doigt déplace la carte, deux doigts zooment et tournent. Ou ajoutez :"))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        SecondaryPillButton(tr("Drawn no-go", "No-go tracée"), onNoGo, Modifier.weight(1f))
        SecondaryPillButton(tr("Shape no-go", "No-go forme"), onShape, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
        SecondaryPillButton("Obstacle", onObstacle, Modifier.weight(1f))
        SecondaryPillButton("Corrections ($count)", onList, Modifier.weight(1f))
    }
}

/** Preview summary: area before → after, part outside the Mammotion boundary, or the reason for the refusal. */
@Composable
private fun PreviewLine(preview: EditorPreview?, emptyHint: String) {
    val colors = MammotionColors
    preview?.refusal?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.Danger); return }
    val c = preview?.candidate
    if (c == null) { Hint(emptyHint); return }
    val status = preview.result.status[c.id]
    if (status != null && !status.applied) {
        Text(preview.result.reasons[c.id] ?: status.label, style = MaterialTheme.typography.bodyMedium, color = colors.Danger)
        return
    }
    val after = preview.afterArea
    val before = preview.beforeArea
    val text = when {
        after == null -> c.label
        before == null || c is AddedNoGo && before == after -> tr("Area: ${MapCorrections.squareMeters(after)}", "Surface : ${MapCorrections.squareMeters(after)}")
        else -> tr("Area: ${MapCorrections.squareMeters(before)} → ${MapCorrections.squareMeters(after)}", "Surface : ${MapCorrections.squareMeters(before)} → ${MapCorrections.squareMeters(after)}")
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    if (preview.outsideM2 >= 0.05) Text(tr("Including ${MapCorrections.squareMeters(preview.outsideM2)} outside the Mammotion boundary: mowed under your responsibility", "Dont ${MapCorrections.squareMeters(preview.outsideM2)} hors du contour Mammotion : tondu sous votre responsabilité"),
        style = MaterialTheme.typography.bodySmall, color = colors.Warning)
}

private fun validCandidate(preview: EditorPreview?): MapCorrection? {
    val c = preview?.candidate ?: return null
    return c.takeIf { preview.result.status[it.id]?.applied == true }
}

@Composable
private fun ValidateRow(preview: EditorPreview?, onCancel: () -> Unit, onValidate: (MapCorrection) -> Unit, undo: (() -> Unit)? = null, undoEnabled: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
        if (undo != null) IconButton(onClick = undo, enabled = undoEnabled) { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = tr("Undo the last gesture", "Annuler le dernier geste")) }
        SecondaryPillButton(tr("Discard", "Abandonner"), onCancel, Modifier.weight(1f))
        val c = validCandidate(preview)
        PrimaryPillButton(tr("Validate", "Valider"), { c?.let(onValidate) }, Modifier.weight(1f), enabled = c != null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(kinds: List<PrimitiveKind>, selected: PrimitiveKind?, onKind: (PrimitiveKind) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kinds.forEach { k -> FilterChip(selected = k == selected, onClick = { onKind(k) }, label = { Text(k.label) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreatePanel(
    creating: Creating, reopened: Boolean, draft: List<Point>?, primitive: PrimitiveShape?, obstacleKind: ObstacleKind, preview: EditorPreview?,
    onKind: (PrimitiveKind) -> Unit, onObstacle: (ObstacleKind) -> Unit, onRadius: (Double) -> Unit,
    onUndo: () -> Unit, onCancel: () -> Unit, onValidate: (MapCorrection) -> Unit,
) {
    when (creating) {
        Creating.TRACE -> {
            Text(if (reopened) tr("Edit the no-go zone", "Modifier la no-go") else tr("New drawn no-go zone", "Nouvelle no-go tracée"), style = MaterialTheme.typography.titleSmall)
            Hint(tr("Tap the map to place each point; drag a point to move it, \"+\" adds one between two.", "Touchez la carte pour poser chaque point ; glissez un point pour le déplacer, « + » en ajoute un entre deux."))
            PreviewLine(preview, tr("${draft?.size ?: 0} point(s): at least 3", "${draft?.size ?: 0} point(s) : 3 au moins"))
            ValidateRow(preview, onCancel, onValidate, onUndo, (draft?.size ?: 0) > 0)
        }
        Creating.SHAPE -> {
            Text(if (reopened) tr("Edit the no-go zone", "Modifier la no-go") else tr("New no-go zone", "Nouvelle no-go"), style = MaterialTheme.typography.titleSmall)
            KindChips(PrimitiveKind.entries, primitive?.kind, onKind)
            Hint(tr("Drag the shape to move it, the white square to resize it, the top circle to rotate it.", "Glissez la forme pour la déplacer, le carré blanc pour la redimensionner, le rond du haut pour la tourner.") +
                (primitive?.let { " ${it.sizeText()}" } ?: ""))
            PreviewLine(preview, tr("Place the shape", "Placez la forme"))
            ValidateRow(preview, onCancel, onValidate)
        }
        Creating.OBSTACLE -> {
            Text(if (reopened) tr("Edit the obstacle", "Modifier l'obstacle") else tr("New obstacle", "Nouvel obstacle"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ObstacleKind.entries.forEach { k -> FilterChip(selected = k == obstacleKind, onClick = { onObstacle(k) }, label = { Text(k.label) }) }
            }
            val radius = (primitive?.widthM ?: 0.5) / 2
            Text(tr("Radius: ${MapCorrections.meters(radius)} m · tap the map to place it", "Rayon : ${MapCorrections.meters(radius)} m · touchez la carte pour le placer"), style = MaterialTheme.typography.bodyMedium)
            LubaSlider(radius.toFloat(), { onRadius(((it * 20).toInt() / 20.0).coerceAtLeast(0.1)) }, valueRange = 0.1f..2f)
            PreviewLine(preview, tr("Place the obstacle", "Placez l'obstacle"))
            ValidateRow(preview, onCancel, onValidate)
        }
    }
}

@Composable
private fun ShapePanel(
    target: MapShape, tool: EditTool, reopened: Boolean, draft: List<Point>?, draftBase: List<Point>?, edgeLine: List<Point>, otherSide: Boolean,
    selectedVertex: Int?, primitive: PrimitiveShape?, removing: Boolean, offset: Float, preview: EditorPreview?, canUndo: Boolean,
    onTool: (EditTool) -> Unit, onEdgeApply: () -> Unit, onEdgeUndo: () -> Unit, onOtherSide: () -> Unit, onDeleteVertex: (Int) -> Unit,
    onUndo: () -> Unit, onKind: (PrimitiveKind) -> Unit, onRemoving: (Boolean) -> Unit, onOffset: (Float) -> Unit,
    onCancel: () -> Unit, onValidate: (MapCorrection) -> Unit,
) {
    val name = MapCorrections.shapeName(target)
    Text(if (target.kind == ShapeKind.NO_GO) "No-go · $name".takeIf { name != "No-go" } ?: "No-go" else name, style = MaterialTheme.typography.titleSmall)
    if (!reopened) Segmented(EditTool.entries.map { it to it.label }, tool, onTool)
    when (tool) {
        EditTool.POINTS -> {
            Hint(tr("Drag a point to move it; \"+\" adds a point; zoom in to see all the points.", "Glissez un point pour le déplacer ; « + » ajoute un point ; zoomez pour voir tous les points."))
            if (selectedVertex != null) SecondaryPillButton(tr("Delete this point", "Supprimer ce point"), { onDeleteVertex(selectedVertex) }, Modifier.fillMaxWidth(),
                enabled = (draft?.size ?: 0) > 3, icon = Icons.Rounded.Delete)
            PreviewLine(preview, tr("No change", "Aucun changement"))
            ValidateRow(preview, onCancel, onValidate, onUndo, canUndo)
        }
        EditTool.EDGE -> {
            Hint(tr("Tap the boundary at the start of the edge, the points of the new edge, then the boundary at the end: the part in between is replaced.", "Touchez le contour au début du bord, les points du nouveau bord, puis le contour à la fin : la partie entre les deux est remplacée."))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                SecondaryPillButton(tr("Previous point", "Point précédent"), onEdgeUndo, Modifier.weight(1f), enabled = edgeLine.isNotEmpty())
                SecondaryPillButton(if (otherSide) tr("Side: other", "Côté : autre") else tr("Side: short", "Côté : court"), onOtherSide, Modifier.weight(1f), enabled = edgeLine.size >= 2)
            }
            PrimaryPillButton(tr("Apply this edge", "Appliquer ce bord"), onEdgeApply, Modifier.fillMaxWidth(), enabled = edgeLine.size >= 2)
            PreviewLine(preview, if (draft == draftBase) tr("No edge applied", "Aucun bord appliqué") else tr("Edge applied", "Bord appliqué"))
            ValidateRow(preview, onCancel, onValidate, onUndo, canUndo)
        }
        EditTool.SHAPE -> {
            Segmented(listOf(false to tr("Add", "Ajouter"), true to tr("Remove", "Retirer")), removing, onRemoving)
            KindChips(PrimitiveKind.entries, primitive?.kind, onKind)
            Hint(tr("Drag the shape, the white square for the size, the top circle for the rotation.", "Glissez la forme, le carré blanc pour la taille, le rond du haut pour la rotation.") + (primitive?.let { " ${it.sizeText()}" } ?: ""))
            PreviewLine(preview, tr("Place the shape", "Placez la forme"))
            ValidateRow(preview, onCancel, onValidate)
        }
        EditTool.OFFSET -> {
            Text(tr("Offset: ${MapCorrections.centimeters(offset.toDouble())}", "Décalage : ${MapCorrections.centimeters(offset.toDouble())}") +
                " (${if (offset >= 0) tr("outwards", "vers l'extérieur") else tr("inwards", "vers l'intérieur")})",
                style = MaterialTheme.typography.bodyMedium)
            LubaSlider(offset, { onOffset((Math.round(it * 20) / 20f)) }, valueRange = -1f..1f)
            PreviewLine(preview, tr("Zero offset", "Décalage nul"))
            ValidateRow(preview, onCancel, onValidate)
        }
    }
}

@Composable
private fun CorrectionList(
    corrections: List<MapCorrection>, corrected: CorrectedMap,
    onToggle: (MapCorrection, Boolean) -> Unit, onShow: (MapCorrection) -> Unit, onEdit: (MapCorrection) -> Unit,
    onDelete: (MapCorrection) -> Unit, onResetAll: () -> Unit,
) {
    val colors = MammotionColors
    val format = remember { SimpleDateFormat("d MMM HH:mm", if (fr.luba.poc.i18n.I18n.french) Locale.FRANCE else Locale.ENGLISH) }
    Text("Corrections (${corrections.size})", style = MaterialTheme.typography.titleSmall)
    if (corrections.isEmpty()) { Hint(tr("No correction: the map is the Mammotion one.", "Aucune correction : la carte est celle de Mammotion.")); return }
    Hint(tr("Applied in order; each one can be disabled or deleted on its own.", "Appliquées dans l'ordre ; chacune peut être désactivée ou supprimée seule."))
    corrections.asReversed().forEach { c ->
        val status = corrected.status[c.id]
        val reason = corrected.reasons[c.id]
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(c.createdAtMs.takeIf { it > 0 }?.let { format.format(Date(it)) }, status?.label, reason).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == null || status.applied && status != CorrectionStatus.CHANGED) colors.TextSecondary else colors.Warning)
                }
                Switch(checked = c.enabled, onCheckedChange = { onToggle(c, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.Green))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onShow(c) }, enabled = status?.applied == true) { Text(tr("Show", "Voir")) }
                val editable = c is ShapeOffset || c is AddedNoGo || (c is ShapePatch && c.primitive != null)
                if (editable) TextButton(onClick = { onEdit(c) }) { Text(tr("Edit", "Modifier")) }
                TextButton(onClick = { onDelete(c) }) { Text(tr("Delete", "Supprimer"), color = colors.Danger) }
            }
        }
    }
    SecondaryPillButton(tr("Restore everything", "Tout rétablir"), onResetAll, Modifier.fillMaxWidth())
}
