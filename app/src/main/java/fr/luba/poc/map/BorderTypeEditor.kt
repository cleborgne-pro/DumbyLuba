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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.components.PrimaryPillButton
import fr.luba.poc.ui.components.SecondaryPillButton
import fr.luba.poc.ui.theme.Dimens
import fr.luba.poc.ui.theme.MammotionColors
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Colour of a border type on the map. */
val BorderType.color: Color get() = Color(argb)

/**
 * Typed boundary portions drawn over the outlines: wide stroke in the colour of the type on a light halo, so the type
 * reads over the zone green and the route. [highlightId]: annotation drawn wider (selected in the editor).
 */
fun DrawScope.drawBorderPortions(portions: List<BorderPortion>, path: (List<Point>, Boolean) -> Path, unit: Float, highlightId: Long? = null) {
    for (p in portions) {
        if (p.points.size < 2) continue
        val line = path(p.points, false)
        val wide = p.annotationId == highlightId
        drawPath(line, Color.White.copy(alpha = 0.75f), style = Stroke((if (wide) 9f else 6f) * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(line, p.type.color, style = Stroke((if (wide) 6.5f else 3.5f) * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Map (m) ↔ screen (px) mapping of the editor: framing of the map then camera (zoom, rotation, panning). */
private class BorderEditorView(val projection: MapProjection, val camera: MapCamera, val center: Point) {
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

/** Shows the border types editor when it is requested from the Map sheet ([BorderTypeRepository.editorOpen]). */
@Composable
fun BorderTypeEditorHost(map: RobotMap) {
    if (!BorderTypeRepository.editorOpen) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { BorderTypeRepository.editorOpen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
            dismissOnBackPress = false, dismissOnClickOutside = false)) {
        BorderTypeEditor(map, BorderTypeRepository.annotations, BorderTypeRepository::save, onClose = { BorderTypeRepository.editorOpen = false })
    }
}

private fun meters(m: Double): String = if (m < 10) String.format(Locale.ROOT, "%.1f m", m) else "${Math.round(m)} m"

private fun shapeName(map: RobotMap, shapeId: Long?): String {
    val shape = map.shapes.firstOrNull { it.id == shapeId } ?: return tr("Boundary not found", "Contour introuvable")
    val kind = if (shape.kind == ShapeKind.NO_GO) tr("No-go zone", "Zone interdite") else "Zone"
    return shape.name.ifBlank { kind }.let { if (shape.name.isBlank()) it else "$kind · $it" }
}

/**
 * Full-screen editor of the **border types**: tap a boundary (zone or no-go) to start a portion, tap its other end (the
 * shorter part is taken, "Other side" takes the other one) or choose the whole boundary, then a type. A coloured portion
 * tapped is selected: change its type or delete it. The list shows every annotation with its state on the current map.
 * [onSave] receives the full list and returns false if it could not be saved.
 */
@Composable
fun BorderTypeEditor(
    map: RobotMap,
    annotations: List<BorderAnnotation>,
    onSave: (List<BorderAnnotation>) -> Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MammotionColors
    val density = LocalDensity.current
    var camera by remember { mutableStateOf(MapCamera()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var startShape by remember { mutableStateOf<Long?>(null) }
    var startPoint by remember { mutableStateOf<Point?>(null) }
    var endPoint by remember { mutableStateOf<Point?>(null) }
    var whole by remember { mutableStateOf(false) }
    var otherSide by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf<BorderType?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editTap by remember { mutableStateOf<Point?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var undoList by remember { mutableStateOf<List<BorderAnnotation>?>(null) }

    val contours = remember(map) { BorderTypes.contours(map) }
    val resolved = remember(map, annotations) { BorderTypes.resolveAll(map, annotations) }
    val portions = remember(map, annotations) { BorderTypes.portions(map, annotations) }
    val shape = contours.firstOrNull { it.id == startShape }
    val selection: BorderSelection? = when {
        shape == null -> null
        whole -> BorderTypes.selectWhole(shape)
        startPoint != null && endPoint != null -> BorderTypes.select(shape, startPoint!!, endPoint!!, otherSide)
        else -> null
    }
    val editing = annotations.firstOrNull { it.id == editingId }
    val bounds = remember(map) { MapBounds.of(map.shapes.flatMap { it.points }.ifEmpty { listOf(Point(0.0, 0.0), Point(1.0, 1.0)) }) }
    val view = run {
        val w = size.width.toFloat().coerceAtLeast(1f)
        val h = size.height.toFloat().coerceAtLeast(1f)
        val top = with(density) { 96.dp.toPx() }
        BorderEditorView(mapScreenProjection(bounds, androidx.compose.ui.geometry.Size(w, h), top, h * 0.40f), camera, Point(w / 2.0, h / 2.0))
    }
    val reachPx = with(density) { 28.dp.toPx() }
    // Read by the gesture block and the callbacks (started once): always the framing of the last composition.
    val currentView by rememberUpdatedState(view)

    fun resetSelection() {
        startShape = null; startPoint = null; endPoint = null; whole = false; otherSide = false; type = null
        editingId = null; editTap = null
    }

    fun commit(list: List<BorderAnnotation>, message: String, undoable: Boolean = false) {
        val before = annotations
        if (onSave(list)) {
            undoList = if (undoable) before else null
            notice = message
            resetSelection()
        } else notice = tr("Could not save", "Enregistrement impossible")
    }

    fun nearestContour(m: Point, reachM: Double): Pair<MapShape, Point>? = contours
        .map { it to EdgeRedraw.project(it.points, m).point }
        .minByOrNull { it.second.distance(m) }
        ?.takeIf { it.second.distance(m) <= reachM }

    fun startAt(s: MapShape, p: Point) {
        resetSelection(); startShape = s.id; startPoint = p; listOpen = false
    }

    fun tap(o: Offset) {
        val v = currentView
        val m = v.map(o)
        val reachM = reachPx / v.pxPerMeter
        val near = nearestContour(m, reachM)
        when {
            editingId != null -> resetSelection()
            startPoint == null && !whole -> {
                val existing = BorderTypes.annotationNear(map, annotations, m, reachM)
                when {
                    existing != null -> { editingId = existing.id; editTap = near?.second; listOpen = false }
                    near != null -> startAt(near.first, near.second)
                    else -> notice = tr("Tap closer to a boundary", "Touchez plus près d'un contour")
                }
            }
            near == null -> Unit
            near.first.id == startShape -> { endPoint = near.second; whole = false }
            else -> startAt(near.first, near.second)
        }
    }

    fun focus(points: List<Point>) {
        val view = currentView
        if (points.isEmpty() || size.width == 0) return
        val c = Point((points.minOf { it.x } + points.maxOf { it.x }) / 2, (points.minOf { it.y } + points.maxOf { it.y }) / 2)
        val extent = max(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y }).coerceAtLeast(1.0)
        val zoom = (size.width * 0.6 / (extent * view.projection.scale)).coerceIn(0.5, 20.0)
        val layer = view.projection.screen(c)
        camera = MapCamera(zoom, camera.rotation).following(Point(layer.x.toDouble(), layer.y.toDouble()), view.center,
            Point(view.center.x, size.height * 0.30))
    }

    val onTap by rememberUpdatedState(::tap)

    BackHandler {
        when {
            listOpen -> listOpen = false
            startShape != null || editingId != null -> resetSelection()
            else -> onClose()
        }
    }

    Box(modifier.fillMaxSize().background(colors.MapBackground)) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.semantics { contentDescription = tr("Border types map", "Carte des types de bordure") }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var multi = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            multi = true
                            val centroid = event.calculateCentroid(useCurrent = false)
                            if (centroid != Offset.Unspecified) {
                                val pan = event.calculatePan()
                                camera = camera.transform(Point(centroid.x.toDouble(), centroid.y.toDouble()), currentView.center,
                                    Point(pan.x.toDouble(), pan.y.toDouble()), event.calculateZoom().toDouble(), event.calculateRotation().toDouble())
                            }
                        } else if (!multi) {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (moved) camera = camera.copy(offset = Point(camera.offset.x + change.positionChange().x, camera.offset.y + change.positionChange().y))
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (!moved && !multi) onTap(down.position)
                }
            }) {
            val unit = 1.dp.toPx()
            fun path(points: List<Point>, closed: Boolean) = Path().apply {
                points.forEachIndexed { i, p -> val s = view.screen(p); if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y) }
                if (closed) close()
            }
            drawLawnLayers(map, { it.id == startShape }, ::path, unit, colors, borders = false)
            drawBorderPortions(portions, ::path, unit, editingId)
            map.station?.let { s -> drawCircle(colors.Ink, 6 * unit, view.screen(s.point)); drawCircle(Color.White, 3 * unit, view.screen(s.point)) }
            selection?.let { sel ->
                val line = path(sel.points, false)
                drawPath(line, Color.White, style = Stroke(10f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, type?.color ?: colors.Ink, style = Stroke(5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10 * unit, 6 * unit))))
            }
            listOfNotNull(startPoint.takeIf { !whole }, endPoint.takeIf { !whole }).forEach { p ->
                drawCircle(Color.White, 9 * unit, view.screen(p)); drawCircle(colors.Ink, 9 * unit, view.screen(p), style = Stroke(2.5f * unit))
            }
        }

        BorderEditorHeader(annotations.size, onBack = { if (startShape != null || editingId != null) resetSelection() else onClose() },
            onList = { listOpen = !listOpen; if (listOpen) resetSelection() }, modifier = Modifier.align(Alignment.TopCenter))

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(Dimens.Md),
            verticalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
            notice?.let { text ->
                Surface(shape = MaterialTheme.shapes.medium, color = colors.Ink, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = Dimens.Lg, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text, color = colors.OnInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        undoList?.let { previous ->
                            TextButton(onClick = { if (onSave(previous)) { notice = tr("Restored", "Rétabli"); undoList = null } }) { Text(tr("Undo", "Annuler"), color = colors.Green) }
                        }
                        TextButton(onClick = { notice = null; undoList = null }) { Text("OK", color = colors.OnInk) }
                    }
                }
            }
            BorderEditorPanel {
                when {
                    listOpen -> BorderList(map, resolved, onShow = { r -> focus(r.points) }, onDelete = { a ->
                        commit(annotations.filter { it.id != a.id }, tr("Portion deleted", "Portion supprimée"), undoable = true); listOpen = true
                    })
                    editing != null -> {
                        val r = resolved.firstOrNull { it.annotation.id == editing.id }
                        Text("${editing.type.label} · ${meters(r?.lengthM ?: 0.0)}", style = MaterialTheme.typography.titleSmall)
                        Hint(listOfNotNull(shapeName(map, r?.shapeId), r?.status?.takeIf { it != BorderAnnotationStatus.EXACT }?.label).joinToString(" · "))
                        TypeChips(editing.type) { t ->
                            if (t != editing.type) commit(BorderTypes.add(annotations, editing.copy(type = t), map), tr("Type changed: ${t.label}", "Type modifié : ${t.label}"))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            SecondaryPillButton(tr("Delete", "Supprimer"), {
                                commit(annotations.filter { it.id != editing.id }, tr("Portion deleted", "Portion supprimée"), undoable = true)
                            }, Modifier.weight(1f))
                            val from = editTap
                            val s = contours.firstOrNull { it.id == r?.shapeId }
                            SecondaryPillButton(tr("New portion here", "Nouvelle portion ici"), { if (s != null && from != null) startAt(s, from) },
                                Modifier.weight(1f), enabled = s != null && from != null)
                        }
                    }
                    shape != null && selection == null -> {
                        Text(tr("Tap the other end on the same boundary", "Touchez l'autre extrémité sur le même contour"), style = MaterialTheme.typography.titleSmall)
                        Hint(shapeName(map, shape.id))
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm)) {
                            SecondaryPillButton(tr("Cancel", "Annuler"), { resetSelection() }, Modifier.weight(1f))
                            SecondaryPillButton(tr("Whole boundary", "Tout le contour"), { whole = true; endPoint = null }, Modifier.weight(1f))
                        }
                    }
                    shape != null && selection != null -> {
                        Text(tr("Portion of ${meters(selection.lengthM)}", "Portion de ${meters(selection.lengthM)}"), style = MaterialTheme.typography.titleSmall)
                        Hint(shapeName(map, shape.id) + if (selection.whole) tr(" · whole boundary", " · tout le contour") else "")
                        TypeChips(type) { type = it }
                        type?.let { Hint(it.detail) }
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Sm), verticalAlignment = Alignment.CenterVertically) {
                            if (!selection.whole) TextButton(onClick = { otherSide = !otherSide }) { Text(tr("Other side", "Autre côté")) }
                            SecondaryPillButton(tr("Cancel", "Annuler"), { resetSelection() }, Modifier.weight(1f))
                            PrimaryPillButton(tr("Validate", "Valider"), {
                                val t = type ?: return@PrimaryPillButton
                                val a = BorderTypes.annotate(shape, selection, t, (annotations.maxOfOrNull { it.id } ?: 0L) + 1, System.currentTimeMillis())
                                commit(BorderTypes.add(annotations, a, map), tr("Portion saved: ${t.label}", "Portion enregistrée : ${t.label}"))
                            }, Modifier.weight(1f), enabled = type != null)
                        }
                    }
                    else -> {
                        Text(tr("Tap a boundary to start a portion", "Touchez un contour pour commencer une portion"), style = MaterialTheme.typography.titleSmall)
                        Hint(tr("A coloured portion tapped can be retyped or deleted. One finger moves the map, two fingers zoom and rotate.",
                            "Une portion colorée touchée peut être retypée ou supprimée. Un doigt déplace la carte, deux doigts zooment et tournent."))
                        Legend()
                        Hint(tr("Kept on this phone; no effect on the route computation yet.", "Gardé sur ce téléphone ; sans effet sur le calcul du trajet pour l'instant."))
                    }
                }
            }
        }
    }

    LaunchedEffect(notice) { if (notice != null && undoList == null) { delay(4000); notice = null } }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MammotionColors.TextSecondary)

@Composable
private fun BorderEditorPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MammotionColors.Surface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.Lg, vertical = Dimens.Md), verticalArrangement = Arrangement.spacedBy(Dimens.Sm), content = content)
    }
}

@Composable
private fun BorderEditorHeader(count: Int, onBack: () -> Unit, onList: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MammotionColors
    Surface(modifier.fillMaxWidth().statusBarsPadding().padding(Dimens.Md), shape = MaterialTheme.shapes.large, color = colors.Surface, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back", "Retour")) }
            Column(Modifier.weight(1f)) {
                Text(tr("Border types", "Types de bordure"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Open lawn, curb, wall or danger, portion by portion", "Pelouse ouverte, bordure, mur ou danger, portion par portion"),
                    style = MaterialTheme.typography.bodySmall, color = colors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onList) {
                Box {
                    Icon(Icons.Rounded.FormatListBulleted, contentDescription = tr("Portions", "Portions"))
                    if (count > 0) Surface(shape = CircleShape, color = colors.Green, modifier = Modifier.align(Alignment.TopEnd).size(16.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("$count", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(selected: BorderType?, onType: (BorderType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BorderType.entries.forEach { t ->
            FilterChip(selected = t == selected, onClick = { onType(t) }, label = { Text(t.label) },
                leadingIcon = { Box(Modifier.size(12.dp).background(t.color, CircleShape)) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.Md)) {
        BorderType.entries.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(width = 16.dp, height = 4.dp).background(t.color, CircleShape))
                Text(t.label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun BorderList(map: RobotMap, resolved: List<ResolvedBorder>, onShow: (ResolvedBorder) -> Unit, onDelete: (BorderAnnotation) -> Unit) {
    val colors = MammotionColors
    Text(tr("Portions (${resolved.size})", "Portions (${resolved.size})"), style = MaterialTheme.typography.titleSmall)
    if (resolved.isEmpty()) { Hint(tr("No typed portion yet.", "Aucune portion typée pour l'instant.")); return }
    Hint(tr("The most recent portion wins where two overlap.", "Là où deux portions se chevauchent, la plus récente l'emporte."))
    resolved.asReversed().forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(r.annotation.type.color, CircleShape))
            Column(Modifier.weight(1f).padding(start = Dimens.Sm)) {
                Text("${r.annotation.type.label} · ${meters(r.lengthM)}", style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(shapeName(map, r.shapeId ?: r.annotation.shapeId), r.status.takeIf { it != BorderAnnotationStatus.EXACT }?.label).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = if (r.status.applied) colors.TextSecondary else colors.Warning)
            }
            TextButton(onClick = { onShow(r) }, enabled = r.status.applied) { Text(tr("Show", "Voir")) }
            TextButton(onClick = { onDelete(r.annotation) }) { Text(tr("Delete", "Supprimer"), color = colors.Danger) }
        }
    }
}
