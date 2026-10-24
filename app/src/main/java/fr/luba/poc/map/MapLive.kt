package fr.luba.poc.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import fr.luba.poc.history.drawRealCoverage
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.ui.theme.MammotionColors
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/** Brush input: one finger paints (map coordinates, in meters), two fingers move the view. */
class MapPaintInput(val begin: (Point) -> Unit, val extend: (Point) -> Unit, val finish: (committed: Boolean) -> Unit)

/** Extra drawing in map coordinates (simulation, aids): polyline, filled polygon or points. */
data class MapOverlay(val points: List<Point>, val color: Color, val widthDp: Float = 2f, val closed: Boolean = false,
                      val fill: Color? = null, val dots: Boolean = false)

private fun liveFit(all: List<Point>, width: Float, height: Float): MapProjection = fitProjection(all, width, height)

/**
 * Read-only live map: boundaries, local obstacles, random walk trace, start point and robot position when telemetry
 * provides it ("if available"). Optional interactions: zoom, rotation and panning.
 */
@Composable
fun MapLive(
    map: RobotMap,
    modifier: Modifier = Modifier,
    x: Double? = null,
    y: Double? = null,
    heading: Double? = null,
    trail: List<Point> = emptyList(),
    start: Point? = null,
    routeStart: Point? = null,
    /** Point along the initial direction of the route ([routeStartAhead]): arrow at [routeStart]. */
    routeStartAhead: Point? = null,
    planned: List<Point> = emptyList(),
    plannedBands: List<List<Point>> = emptyList(),
    plannedBorders: List<List<Point>> = emptyList(),
    cuttingPasses: List<List<Point>> = emptyList(),
    cutWidthM: Double = Geometry.CUT_WIDTH_M,
    cuttingZoneIds: Set<Long> = emptySet(),
    zoneOrder: List<Long> = emptyList(),
    obstacles: List<LocalObstacle> = emptyList(),
    empty: Color = MammotionColors.SurfaceMuted,
    interactive: Boolean = false,
    cameraReset: Int = 0,
    /**
     * Camera provided by the calling screen (zoom buttons, recentering); otherwise the map's own camera.
     * Read only when drawing: zooming neither recomposes the map nor rebuilds its geometry.
     */
    cameraState: MutableState<MapCamera>? = null,
    signalCells: List<SignalCell> = emptyList(), signal: SurveySignal = SurveySignal.WIFI,
    signalColor: (SignalCell) -> Color = { heatColor(signal,it) },
    paint: List<PaintStroke> = emptyList(),
    paintPieces: List<List<Point>> = emptyList(),
    onPaint: MapPaintInput? = null,
    overlays: List<MapOverlay> = emptyList(),
    /** Single tap converted into map coordinates (meters); a drag does not trigger it. */
    onTap: ((Point) -> Unit)? = null,
    /** [x], [y]: point published by the robot (8 cm behind its rotation center); false for an already centered pose (simulation). */
    publishedPosition: Boolean = true,
    /** Real coverage layer and its image ([fr.luba.poc.history.CoverageLayerStyle.image]), over the planned stripes. */
    coverage: Pair<fr.luba.poc.history.CoverageMap, androidx.compose.ui.graphics.ImageBitmap>? = null,
) {
    val colors = MammotionColors
    val robot = if (x != null && y != null) Point(x, y) else null
    val ownCamera = remember(cameraReset) { mutableStateOf(MapCamera()) }
    val cameraHolder = cameraState ?: ownCamera
    var camera by cameraHolder
    val framePoints = remember(map, obstacles, signalCells) {
        map.shapes.flatMap { it.points } + obstacles.extentPoints() + signalCells.flatMap { listOf(Point(it.point.x-0.5,it.point.y-0.5),Point(it.point.x+0.5,it.point.y+0.5)) }
    }
    val painter by rememberUpdatedState(onPaint)
    val fitPoints = remember(framePoints, trail, planned, start, robot) { framePoints + trail + planned + listOfNotNull(start, robot) }
    val currentFrame by rememberUpdatedState(fitPoints)
    val geometry = remember { LiveGeometry() }
    val tapped by rememberUpdatedState(onTap)
    // Compass outside the gesture area: a tap on it neither draws nor moves the map.
    Box(modifier) {
    Box(Modifier.matchParentSize().clipToBounds().mapAppear(map.shapes).then(if (onPaint != null) Modifier.pointerInput(map, cameraHolder, "paint") {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val center = Point(size.width / 2.0, size.height / 2.0)
            fun toMap(o: Offset): Point? {
                val frame = currentFrame
                if (frame.size < 2) return null
                val layer = camera.inverse(Point(o.x.toDouble(), o.y.toDouble()), center)
                return liveFit(frame, size.width.toFloat(), size.height.toFloat()).map(Offset(layer.x.toFloat(), layer.y.toFloat()))
            }
            // The stroke only starts once the gesture is recognized as a single finger (dragged beyond the touch slop, or tap
            // released): the first finger of a pinch neither paints nor moves the obstacle simulation object (which used to
            // stop it and erase the robot on every zoom).
            var pending = true
            var painting = false
            fun beginAtDown(): Boolean = toMap(down.position)?.let { painter?.begin?.invoke(it); true } ?: false
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) {
                    // Second finger: the current stroke is dropped, the view moves.
                    pending = false
                    if (painting) { painter?.finish?.invoke(false); painting = false }
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid != Offset.Unspecified) {
                        val pan = event.calculatePan()
                        camera = camera.transform(Point(centroid.x.toDouble(), centroid.y.toDouble()), center,
                            Point(pan.x.toDouble(), pan.y.toDouble()), event.calculateZoom().toDouble(), event.calculateRotation().toDouble())
                    }
                } else {
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (pending && change != null && (!change.pressed ||
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop)) {
                        pending = false
                        painting = beginAtDown()
                    }
                    if (painting && change != null && change.pressed) toMap(change.position)?.let { painter?.extend?.invoke(it) }
                }
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
            if (painting) painter?.finish?.invoke(true)
        }
    } else if (interactive) Modifier.pointerInput(map, cameraHolder) {
        detectTransformGestures { centroid, pan, zoom, rotation ->
            camera = camera.transform(Point(centroid.x.toDouble(),centroid.y.toDouble()),
                Point(size.width/2.0,size.height/2.0),Point(pan.x.toDouble(),pan.y.toDouble()),zoom.toDouble(),rotation.toDouble())
        }
    } else Modifier).then(if (onTap != null) Modifier.pointerInput(map, cameraHolder, "tap") {
        detectTapGestures { o ->
            val frame = currentFrame
            if (frame.size >= 2) {
                val layer = camera.inverse(Point(o.x.toDouble(), o.y.toDouble()), Point(size.width / 2.0, size.height / 2.0))
                tapped?.invoke(liveFit(frame, size.width.toFloat(), size.height.toFloat()).map(Offset(layer.x.toFloat(), layer.y.toFloat())))
            }
        }
    } else Modifier)) {
    Canvas(Modifier.matchParentSize().graphicsLayer {
        scaleX=camera.zoom.toFloat(); scaleY=camera.zoom.toFloat()
        rotationZ=camera.rotation.toFloat()
        translationX=camera.offset.x.toFloat(); translationY=camera.offset.y.toFloat()
    }) {
        if (framePoints.size < 2 && trail.isEmpty() && robot == null) {
            drawRect(empty)
            return@Canvas
        }
        val fit = geometry.begin(fitPoints, size)
        // The layer is enlarged by the zoom: divide the stroke widths to keep thin lines on screen.
        // Only these widths change with zoom: paths, zone union and framing stay cached.
        val zoom = camera.zoom.toFloat().coerceAtLeast(0.01f)
        val lineScale = zoom
        val scale = fit.scale
        fun screen(p: Point) = fit.screen(p)
        val path = geometry::path
        drawLawn(map, { cuttingZoneIds.isEmpty() || it.id in cuttingZoneIds }, path, 1.dp.toPx() / lineScale, colors)
        if (paint.isNotEmpty()) {
            // Paint visible only inside the zones (no-go excluded): this is the kept area.
            clipPath(geometry.surface(map, null)) {
                drawIntoCanvas { canvas ->
                    // Separate layer: the eraser removes paint, and overlapping strokes keep the same tint.
                    canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), Paint().apply { alpha = 0.5f })
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
        paintPieces.forEach { piece ->
            if (piece.size >= 3) drawPath(path(piece, true), colors.Warning, style = Stroke(2.dp.toPx() / lineScale, join = StrokeJoin.Round))
        }
        drawLocalObstacles(obstacles, ::screen, scale, colors, unit = 1.dp.toPx() / lineScale)
        drawPath(path(planned,false), colors.LinePass, style = Stroke(2.dp.toPx() / lineScale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Clip the stripes to the mowable area, without hiding the map boundaries.
        clipPath(geometry.surface(map, cuttingZoneIds)) {
            // Metric width; constant center line on screen, without link spikes.
            val cuttingStroke = Stroke((cutWidthM * scale).toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            // Lighter border loops: margin along the boundaries and around no-go zones (Mammotion app).
            plannedBorders.forEach { drawPath(path(it,false), colors.BandBorder, style = cuttingStroke) }
            cuttingPasses.forEach { drawPath(path(it,false), colors.BandPass, style = cuttingStroke) }
            plannedBands.forEach { drawPath(path(it,false), colors.LinePass,
                style = Stroke(0.75.dp.toPx() / lineScale,cap = StrokeCap.Round,join = StrokeJoin.Round)) }
        }
        plannedBorders.forEach { drawPath(path(it,false), colors.LineBorder, style = Stroke(1.5.dp.toPx() / lineScale,cap = StrokeCap.Round,join = StrokeJoin.Round)) }
        coverage?.let { (c, image) -> drawRealCoverage(c, image, ::screen) }
        overlays.forEach { overlay ->
            if (overlay.points.isEmpty()) return@forEach
            val width = overlay.widthDp.dp.toPx() / lineScale
            if (overlay.fill != null && overlay.points.size >= 3) drawPath(path(overlay.points, true), overlay.fill)
            if (overlay.dots) overlay.points.forEach { drawCircle(overlay.color, width * 2, screen(it)) }
            else if (overlay.points.size >= 2) drawPath(path(overlay.points, overlay.closed), overlay.color,
                style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (trail.size >= 2) {
            drawPath(
                path(trail, false),
                colors.RobotMarker.copy(alpha = 0.45f),
                style = Stroke(3.dp.toPx() / lineScale, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        routeStart?.let { drawRouteStart(screen(it), lineScale, camera.rotation.toFloat(), toward = routeStartAhead?.let(::screen)) }
        start?.let { drawCircle(colors.RobotMarker.copy(alpha = 0.6f), 4.dp.toPx() / lineScale, screen(it)) }
        signalCells.forEach { cell ->
            drawRect(signalColor(cell),screen(Point(cell.point.x-0.5,cell.point.y+0.5)),
                androidx.compose.ui.geometry.Size(scale.toFloat(),scale.toFloat()))
        }
        drawZoneOrder(map,zoneOrder,::screen,1.dp.toPx() / lineScale,camera.rotation.toFloat())
        geometry.end()
    }
    // Animated effects in their own layer: smoothed robot and halo, comet on the route (except during a progressive trace).
    val fxRoute = remember(start == null, plannedBorders, plannedBands) { if (start == null) plannedBorders + plannedBands else emptyList() }
    MapFxLayer({ cameraHolder.value }, { geometry.projection(currentFrame, it) }, Modifier.matchParentSize(),
        robot = robot?.let { if (publishedPosition) DisplayPose.published(it, heading) else DisplayPose(it, heading) },
        route = fxRoute, selected = cuttingZoneIds)
    }
    // Only interactive maps rotate: compass at the top left ("Recenter" takes the right corner).
    if (interactive || onPaint != null) LiveCompass(cameraHolder, Modifier.align(Alignment.TopStart).padding(8.dp))
    }
}

/** Compass in its own recomposition scope: rotating the view does not recompose the map. */
@Composable
private fun LiveCompass(camera: MutableState<MapCamera>, modifier: Modifier) {
    MapCompass(camera.value.rotation, { degrees -> camera.value = camera.value.rotatedBy(degrees) }, modifier)
}

/**
 * Screen geometry of the live map, kept from one frame to the next: framing, paths (keyed by the identity of their
 * point list) and combined areas (zone union minus no-go zones). A zoom, a rotation or an animation frame only
 * redraws these paths with other widths, without rebuilding thousands of passes or redoing the boolean operations.
 */
internal class LiveGeometry {
    private class Entry(val path: Path, val size: Int, val last: Point?)

    private var fitPoints: List<Point>? = null
    private var fitSize = Size.Unspecified
    private var fit: MapProjection? = null
    private var open = IdentityHashMap<List<Point>, Entry>()
    private var closed = IdentityHashMap<List<Point>, Entry>()
    private var nextOpen = IdentityHashMap<List<Point>, Entry>()
    private var nextClosed = IdentityHashMap<List<Point>, Entry>()
    // Areas of the map [surfaceMap] (keyed by identity: no hashing of all its points on every frame).
    private var surfaceMap: RobotMap? = null
    private val surfaces = HashMap<Set<Long>?, Path>()

    /** Framing only (effects layer): recomputed when the points or the size change. */
    fun projection(points: List<Point>, size: Size): MapProjection {
        val current = fit
        if (current != null && points === fitPoints && size == fitSize) return current
        val next = liveFit(points, size.width, size.height)
        fitPoints = points; fitSize = size
        // Robot or trace tip within the extent: same framing, the cached paths stay valid.
        if (current != null && current.sameAs(next)) return current
        fit = next
        open.clear(); closed.clear(); surfaces.clear()
        return next
    }

    /** Start of a map layer frame. */
    fun begin(points: List<Point>, size: Size): MapProjection = projection(points, size)

    /** Start of a frame with a forced framing (full-screen map): caches kept as long as it does not change. */
    fun begin(projection: MapProjection) {
        val current = fit
        fitPoints = null
        if (current != null && current.sameAs(projection)) return
        fit = projection
        open.clear(); closed.clear(); surfaces.clear()
    }

    /** End of a frame: paths not drawn (progressive reveal, old trace) are forgotten. */
    fun end() {
        open = nextOpen.also { nextOpen = open }; nextOpen.clear()
        closed = nextClosed.also { nextClosed = closed }; nextClosed.clear()
    }

    fun path(points: List<Point>, close: Boolean): Path {
        val cache = if (close) closed else open
        val next = if (close) nextClosed else nextOpen
        val last = points.lastOrNull()
        // Already drawn in this frame (wide band then thin line) or in the previous one, unchanged list.
        val entry = (next[points] ?: cache[points])?.takeIf { it.size == points.size && it.last === last }
            ?: Entry(build(points, close), points.size, last)
        next[points] = entry
        return entry.path
    }

    /** Mowable area: chosen zones ([zones] empty = all, null = all, for painting) minus no-go zones. */
    fun surface(map: RobotMap, zones: Set<Long>?): Path {
        if (map !== surfaceMap) { surfaces.clear(); surfaceMap = map }
        return surfaces.getOrPut(zones) { combine(map, zones) }
    }

    private fun combine(map: RobotMap, zones: Set<Long>?): Path {
        var area = Path()
        map.shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 && (zones.isNullOrEmpty() || it.id in zones) }
            .forEach { area = combineOrKeep(PathOperation.Union, area, build(it.points, true)) }
        map.shapes.filter { it.kind == ShapeKind.NO_GO && it.points.size >= 3 }
            .forEach { area = combineOrKeep(PathOperation.Difference, area, build(it.points, true)) }
        if (surfaces.size > 8) surfaces.clear()
        return area
    }

    /**
     * Skia refuses some boolean operations (non-finite point, nearly coincident edges at a given framing) and Compose
     * then throws from the draw pass, which killed the app in the middle of a route (Fold, 2026-10-22). The clip is
     * only cosmetic: the failing shape is left out (union) or not subtracted (no-go), never a crash.
     */
    private fun combineOrKeep(operation: PathOperation, area: Path, shape: Path): Path {
        if (shape.isEmpty) return area
        val out = Path()
        return if (out.op(area, shape, operation)) out else if (operation == PathOperation.Union && area.isEmpty) shape else area
    }

    /** Projected path; a non-finite point (NaN/infinite) is skipped instead of reaching Skia. */
    private fun build(points: List<Point>, close: Boolean): Path {
        val projection = fit ?: return Path()
        val out = Path()
        var first = true
        points.forEach { p ->
            val o = projection.screen(p)
            if (!o.x.isFinite() || !o.y.isFinite()) return@forEach
            if (first) out.moveTo(o.x, o.y) else out.lineTo(o.x, o.y)
            first = false
        }
        if (close && !first) out.close()
        return out
    }
}
