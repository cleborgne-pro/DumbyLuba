package fr.luba.poc.map

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.luba.poc.ui.theme.LubaColors
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Visual dressing of the maps: flat pale-green zones with a green outline and ocher no-go zones (since 0.64.0), dotted
 * background and
 * animated effects layer (smoothed robot and halo, comet on the route, selection flash, appearance, recentering).
 * Purely visual: no route computation or command.
 *
 * The animated effects live in their own Canvas: only this light layer is redrawn on each frame, never the map and its
 * thousands of passes.
 */

/** Meter → pixel framing of the map layer, before the camera (zoom, rotation, panning). */
class MapProjection(val minX: Double, val minY: Double, val scale: Double, val ox: Double, val oy: Double, val height: Float) {
    fun screen(p: Point) = Offset((ox + (p.x - minX) * scale).toFloat(), (height - oy - (p.y - minY) * scale).toFloat())
    fun map(o: Offset) = Point((o.x - ox) / scale + minX, (height - oy - o.y) / scale + minY)
    /** Same meter → pixel mapping: already projected paths stay valid. */
    fun sameAs(other: MapProjection) = minX == other.minX && minY == other.minY && scale == other.scale &&
        ox == other.ox && oy == other.oy && height == other.height
}

/** Metric extent of a set of points, computed once. */
data class MapBounds(val minX: Double, val minY: Double, val width: Double, val height: Double) {
    companion object {
        fun of(points: List<Point>, defaultW: Double = 20.0, defaultH: Double = 15.0): MapBounds {
            if (points.isEmpty()) return MapBounds(0.0, 0.0, defaultW, defaultH)
            val minX = points.minOf { it.x }
            val minY = points.minOf { it.y }
            return MapBounds(minX, minY, max(1.0, points.maxOf { it.x } - minX), max(1.0, points.maxOf { it.y } - minY))
        }
    }
}

/** System animations disabled (accessibility, battery saving): frozen effects, no infinite loop. */
@Composable
fun rememberMapMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
}

private fun LubaColors.isDark() = Background.luminance() < 0.5f

/**
 * Lawn drawn like the Mammotion app (capture of 2026-10-17): pale green fills outlined in green, ochre no-go zone fills,
 * passages as a grey band dotted with white. Zone boundaries are drawn after all the fills: a boundary shared by two
 * zones stays a sharp line. [unit] = 1 dp on screen in the current layer.
 */
fun DrawScope.drawLawn(
    map: RobotMap,
    active: (MapShape) -> Boolean,
    path: (List<Point>, Boolean) -> Path,
    unit: Float,
    colors: LubaColors,
) {
    val dark = colors.isDark()
    val shapes = map.shapes.filter { it.points.size >= 2 }.sortedBy { it.type }
    val zones = shapes.filter { it.kind == ShapeKind.ZONE && it.points.size >= 3 }
    val outlines = zones.map { path(it.points, true) }
    zones.forEachIndexed { i, zone -> drawPath(outlines[i], if (active(zone)) colors.ZoneFillSelected else colors.ZoneFill) }
    // Surface beyond the Mammotion boundaries (DumbyLuba zones, enlargements): orange hatching, always visible.
    drawBeyondMammotion(MapCorrections.beyondMammotion(map), path, unit, colors)
    // DumbyLuba zone (phone only): dashed outline; Mammotion zone: solid outline.
    zones.forEachIndexed { i, zone ->
        if (MapCorrections.isDumbyLuba(zone)) drawPath(outlines[i], colors.ZoneStroke, style = Stroke(2f * unit, join = StrokeJoin.Round,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f * unit, 4f * unit))))
        else drawPath(outlines[i], colors.ZoneStroke, style = Stroke(1.5f * unit, join = StrokeJoin.Round))
    }
    for (shape in shapes) {
        when (shape.kind) {
            ShapeKind.ZONE -> Unit
            ShapeKind.NO_GO -> {
                val outline = path(shape.points, true)
                drawPath(outline, colors.NoGoFill)
                drawPath(outline, colors.NoGoStroke.copy(alpha = 0.7f), style = Stroke(1f * unit, join = StrokeJoin.Round))
            }
            // Passage: grey band dotted with white, like a path (never mowed).
            // DumbyLuba passage: same band, tinted with the zone green.
            ShapeKind.PASSAGE -> {
                val line = path(shape.points, false)
                drawPath(line, if (MapCorrections.isDumbyLuba(shape)) colors.ZoneStroke.copy(alpha = 0.45f)
                    else (if (dark) Color.White else Color(0xFF6E767E)).copy(alpha = if (dark) 0.22f else 0.35f),
                    style = Stroke(6f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(line, Color.White.copy(alpha = 0.95f), style = Stroke(1.5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * unit, 3f * unit))))
            }
            ShapeKind.OBSTACLE -> {
                val outline = path(shape.points, true)
                drawPath(outline, colors.ObstacleFill)
                drawPath(outline, colors.ObstacleStroke, style = Stroke(1.25f * unit, join = StrokeJoin.Round))
            }
        }
    }
}

/** Orange hatching (and thin outline) over the surface beyond the Mammotion boundaries. */
fun DrawScope.drawBeyondMammotion(beyond: BeyondArea, path: (List<Point>, Boolean) -> Path, unit: Float, colors: LubaColors) {
    if (beyond.rings.isEmpty()) return
    val area = Path().apply {
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        beyond.rings.filter { it.size >= 3 }.forEach { addPath(path(it, true)) }
    }
    val bounds = area.getBounds()
    if (bounds.isEmpty) return
    val tint = colors.Warning
    drawPath(area, tint.copy(alpha = 0.10f))
    clipPath(area) {
        val step = 7f * unit
        var x = bounds.left - bounds.height
        while (x < bounds.right) {
            drawLine(tint.copy(alpha = 0.55f), Offset(x, bounds.bottom), Offset(x + bounds.height, bounds.top), 1f * unit)
            x += step
        }
    }
    drawPath(area, tint.copy(alpha = 0.8f), style = Stroke(1f * unit, join = StrokeJoin.Round))
}

/**
 * Dotted map background, fixed on screen (does not follow the camera): discreet, modern grid. Drawn only for the
 * "Dots" background ([MapDisplaySettings]); the metric grid is drawn in the map layer.
 */
fun Modifier.mapDotGrid(color: Color): Modifier = drawBehind {
    if (MapDisplaySettings.current.backdrop != MapBackdrop.DOTS) return@drawBehind
    val step = 22.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) { drawCircle(color, r, Offset(x, y)); x += step }
        y += step
    }
}

/** Map appearance: slight fade and zoom from 96 to 100 %, only once per screen. */
@Composable
fun Modifier.mapAppear(key: Any?): Modifier {
    val motion = rememberMapMotion()
    val progress = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(key) { if (motion) progress.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        val t = progress.value
        alpha = t
        val s = 0.96f + 0.04f * t
        scaleX = s; scaleY = s
    }
}

/** Animated camera recentering: interpolation of the zoom, rotation (shortest way) and offset. */
suspend fun animateCamera(from: MapCamera, to: MapCamera, durationMs: Int = 420, set: (MapCamera) -> Unit) {
    val turn = MapCamera.signedDegrees(to.rotation - from.rotation)
    animate(0f, 1f, animationSpec = tween(durationMs, easing = FastOutSlowInEasing)) { t, _ ->
        set(MapCamera(
            zoom = from.zoom + (to.zoom - from.zoom) * t,
            rotation = from.rotation + turn * t,
            offset = Point(from.offset.x + (to.offset.x - from.offset.x) * t, from.offset.y + (to.offset.y - from.offset.y) * t),
        ))
    }
    set(to)
}

/** Displayed robot position, glided between two readings (1 Hz) instead of jumping. */
data class DisplayPose(val point: Point, val heading: Double?) {
    companion object {
        /**
         * Pose of the real robot from its published point: silhouette centered on the rotation center
         * ([fr.luba.poc.simulation.DeadReckoning.rotationCenter]), which stays still during a pivot.
         */
        fun published(point: Point, heading: Double?): DisplayPose =
            DisplayPose(fr.luba.poc.simulation.DeadReckoning.rotationCenter(point, heading), heading)
    }
}

/** Maximum glide between two poses: real robot positions published at 1 Hz. */
const val POSE_SMOOTHING_MS = 950L
/** Below this interval, the new pose is shown as is (no frame would fit in between). */
const val POSE_SNAP_MS = 16L

/**
 * Duration of the glide to a new pose: the interval observed since the previous one, bounded by [POSE_SMOOTHING_MS]. A
 * fixed 950 ms duration restarted on each simulation pose (one every 50 ms) left the icon ≈ 1 s behind the robot, i.e.
 * 10 to 40 s of route in fast playback: it cut the turns into straight lines and its heading lagged. Matched to the
 * interval, it goes through every received pose.
 */
fun poseSmoothingMs(sincePreviousMs: Long?): Long =
    (sincePreviousMs ?: POSE_SMOOTHING_MS).coerceIn(0L, POSE_SMOOTHING_MS)

@Composable
fun rememberSmoothedPose(target: DisplayPose?): DisplayPose? {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val t = remember { Animatable(1f) }
    val motion = rememberMapMotion()
    val previousAt = remember { longArrayOf(Long.MIN_VALUE) }
    LaunchedEffect(target) {
        if (target == null) { from = null; to = null; previousAt[0] = Long.MIN_VALUE; return@LaunchedEffect }
        val now = System.nanoTime() / 1_000_000
        val duration = poseSmoothingMs(previousAt[0].takeIf { it != Long.MIN_VALUE }?.let { now - it })
        previousAt[0] = now
        val shown = current(from, to, t.value)
        // Large jump (relocalization, other source): no glide across the map.
        val jump = shown == null || hypot(shown.point.x - target.point.x, shown.point.y - target.point.y) > 5.0
        val snap = jump || !motion || duration < POSE_SNAP_MS
        from = if (snap) target else shown
        to = target
        t.snapTo(0f)
        if (snap) t.snapTo(1f) else t.animateTo(1f, tween(duration.toInt(), easing = LinearEasing))
    }
    return current(from, to, t.value)
}

private fun current(from: DisplayPose?, to: DisplayPose?, t: Float): DisplayPose? {
    if (to == null) return null
    if (from == null || t >= 1f) return to
    val p = Point(from.point.x + (to.point.x - from.point.x) * t, from.point.y + (to.point.y - from.point.y) * t)
    val h = if (from.heading != null && to.heading != null) {
        val turn = ((to.heading - from.heading) % 360 + 540) % 360 - 180
        from.heading + turn * t
    } else to.heading
    return DisplayPose(p, h)
}

/** Route laid end to end in mowing order, with cumulative distances (comet). */
class RouteTrack(paths: List<List<Point>>) {
    val points: List<Point> = paths.filter { it.size >= 2 }.flatten()
    private val cumulative = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    val meters: Double = cumulative.lastOrNull() ?: 0.0

    private fun at(d: Double): Pair<Int, Point> {
        var lo = 0; var hi = points.size - 1
        while (lo < hi - 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= d) lo = mid else hi = mid }
        val span = cumulative[hi] - cumulative[lo]
        val f = if (span <= 0) 0.0 else (d - cumulative[lo]) / span
        val a = points[lo]; val b = points[hi]
        return lo to Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }

    /** Portion of the route between [start] and [end] meters. */
    fun slice(start: Double, end: Double): List<Point> {
        if (points.size < 2 || meters <= 0) return emptyList()
        val s = start.coerceIn(0.0, meters); val e = end.coerceIn(0.0, meters)
        if (e <= s) return emptyList()
        val (i0, p0) = at(s); val (i1, p1) = at(e)
        return buildList { add(p0); for (i in i0 + 1..i1) add(points[i]); add(p1) }
    }
}

/**
 * Effects layer above the map, with the same camera: pulsing robot halo (smoothed position), comet running along the
 * planned route in mowing order, start pulse and flash of the zone just chosen.
 */
@Composable
fun MapFxLayer(
    /** Camera read only when drawing: a zoom redraws this layer without recomposing it. */
    camera: () -> MapCamera,
    projection: (Size) -> MapProjection,
    modifier: Modifier = Modifier,
    robot: DisplayPose? = null,
    robotFresh: Boolean = true,
    route: List<List<Point>> = emptyList(),
    routeStart: Point? = null,
    map: RobotMap? = null,
    selected: Collection<Long> = emptyList(),
    /** Offset forced by robot following ([RobotFollow]), read only by the layer. */
    followOffset: () -> Point? = { null },
) {
    val colors = MammotionColors
    val motion = rememberMapMotion()
    val pose = rememberSmoothedPose(robot)
    // Wheels animated by the displayed motion (glided between two positions); still without animations.
    val odometer = remember { WheelOdometer() }
    val wheels = if (motion) odometer.advance(pose) else WheelTravel.ZERO
    val track = remember(route) { RouteTrack(route) }
    val loop = rememberInfiniteTransition(label = "map")
    val pulse by loop.animateFloat(0f, if (motion) 1f else 0f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "halo")
    // Comet: constant apparent speed, one route lap between 5 and 16 s.
    val cometMs = (track.meters * 6).toInt().coerceIn(5000, 16000)
    val comet by loop.animateFloat(0f, if (motion) 1f else 0f, infiniteRepeatable(tween(cometMs, easing = LinearEasing), RepeatMode.Restart), label = "comet")
    // Selection flash: the last added zone briefly lights up.
    var lastSelected by remember { mutableStateOf(selected.toSet()) }
    var flashZone by remember { mutableStateOf<Long?>(null) }
    val flash = remember { Animatable(1f) }
    LaunchedEffect(selected.toSet()) {
        val added = selected.toSet() - lastSelected
        lastSelected = selected.toSet()
        if (added.isNotEmpty() && motion) { flashZone = added.last(); flash.snapTo(0f); flash.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    }
    Canvas(modifier.graphicsLayer {
        val view = camera()
        scaleX = view.zoom.toFloat(); scaleY = view.zoom.toFloat()
        rotationZ = view.rotation.toFloat()
        val offset = followOffset() ?: view.offset
        translationX = offset.x.toFloat(); translationY = offset.y.toFloat()
    }) {
        val proj = projection(size)
        val zoom = camera().zoom.toFloat().coerceAtLeast(0.01f)
        // 1 dp on screen whatever the zoom: comet, halo and edgings keep their size when zooming.
        val unit = 1.dp.toPx() / zoom
        fun polyline(points: List<Point>): Path = Path().apply {
            points.forEachIndexed { i, p -> val o = proj.screen(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        }
        // Zone just chosen: white edging that widens and fades.
        val f = flash.value
        if (f < 1f && map != null) map.shapes.firstOrNull { it.id == flashZone && it.points.size >= 3 }?.let { zone ->
            val outline = polyline(zone.points).apply { close() }
            drawPath(outline, Color.White.copy(alpha = 0.35f * (1f - f)))
            drawPath(outline, Color.White.copy(alpha = 0.9f * (1f - f)), style = Stroke((2f + 7f * f) * unit, join = StrokeJoin.Round))
        }
        // Comet: bright head and gradient trail over a few meters of route.
        if (motion && track.meters > 1.0) {
            val head = comet * track.meters
            val tail = min(6.0, track.meters / 4)
            val steps = 6
            for (k in 0 until steps) {
                val a = head - tail * (k + 1) / steps
                val b = head - tail * k / steps
                val piece = track.slice(a, b)
                if (piece.size < 2) continue
                val fade = 1f - k.toFloat() / steps
                drawPath(polyline(piece), colors.Green.copy(alpha = 0.28f * fade),
                    style = Stroke(7f * unit * fade, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(polyline(piece), Color.White.copy(alpha = 0.95f * fade),
                    style = Stroke(2.2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            track.slice(head - 0.01, head).lastOrNull()?.let { drawCircle(Color.White, 3.2f * unit, proj.screen(it)) }
        }
        // Route start: purple wave around the point.
        routeStart?.let { start ->
            val o = proj.screen(start)
            val p = (pulse * 2f) % 1f
            drawCircle(Color(0xFF7253C7).copy(alpha = 0.45f * (1f - p)), (6f + 12f * p) * unit, o, style = Stroke(2f * unit))
        }
        // Robot: two green waves (fresh position) or grey ring (last FIX), then the silhouette.
        pose?.let { at ->
            val o = proj.screen(at.point)
            val base = (0.48 * proj.scale).toFloat().coerceAtLeast(10f * unit)
            if (robotFresh) {
                for (shift in listOf(0f, 0.5f)) {
                    val p = (pulse + shift) % 1f
                    drawCircle(colors.Green.copy(alpha = 0.40f * (1f - p)), base * (1f + 1.3f * p), o)
                }
                drawCircle(Color.White.copy(alpha = 0.9f), base, o, style = Stroke(2f * unit))
            } else {
                drawCircle(colors.TextTertiary.copy(alpha = 0.25f), base * 1.2f, o)
                drawCircle(colors.TextTertiary, base, o, style = Stroke(1.5f * unit))
            }
            drawLuba(o, lubaDrawScale(proj.scale, 12.dp.toPx(), zoom), at.heading ?: 90.0, wheels)
        }
    }
}

/** Full-screen map projection: extent centered between the header and the action bar. */
fun mapScreenProjection(bounds: MapBounds, size: Size, top: Float, bottom: Float): MapProjection {
    val available = (size.height - top - bottom).coerceAtLeast(120f)
    val scale = min((size.width - 40) / bounds.width, available / bounds.height)
    return MapProjection(bounds.minX, bounds.minY, scale, (size.width - bounds.width * scale) / 2,
        bottom + (available - bounds.height * scale) / 2, size.height)
}

/** Fitting a set of points into the canvas (embedded maps). */
fun fitProjection(all: List<Point>, width: Float, height: Float, pad: Float = 16f): MapProjection {
    val b = MapBounds.of(all)
    val scale = min((width - pad) / b.width, (height - pad) / b.height)
    return MapProjection(b.minX, b.minY, scale, (width - b.width * scale) / 2, (height - b.height * scale) / 2, height)
}


