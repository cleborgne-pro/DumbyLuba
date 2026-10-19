package fr.luba.poc.camera

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import fr.luba.poc.i18n.tr

/**
 * Geometry of a floating camera window (map thumbnail, thumbnail of the other screens): pure computations, in dp,
 * tested on the JVM ([CameraWindowTest]).
 *
 * The window keeps its original anchor (top-right corner on the map, bottom-left elsewhere): the saved position is an
 * offset from that anchor, the size a width (fixed 16/9 ratio). The anchor corner stays fixed during a resize; the
 * handle is at the opposite corner.
 */
object CameraWindowGeometry {
    const val ASPECT = 16f / 9f
    val MIN_WIDTH = 112.dp
    /** Margin kept between the window and the edges of the screen hosting it. */
    val MARGIN = 4.dp

    /** Maximum width: the window fits in the screen (width and height, 16/9 ratio), margins included. */
    fun maxWidth(parentWidth: Float, parentHeight: Float, margin: Float = MARGIN.value, minWidth: Float = MIN_WIDTH.value): Float =
        minOf(parentWidth - 2 * margin, (parentHeight - 2 * margin) * ASPECT).coerceAtLeast(minWidth)

    fun clampWidth(width: Float, maxWidth: Float, minWidth: Float = MIN_WIDTH.value): Float = width.coerceIn(minWidth, maxOf(minWidth, maxWidth))

    /**
     * Offset kept within the screen: the window placed at [base] (position without offset) and of size [width] × [height]
     * stays entirely within [parentWidth] × [parentHeight], margins included. Screen too small: stuck to the top / left edge.
     */
    fun clampOffset(dx: Float, dy: Float, baseX: Float, baseY: Float, width: Float, height: Float,
                    parentWidth: Float, parentHeight: Float, margin: Float = MARGIN.value): Pair<Float, Float> {
        fun axis(d: Float, base: Float, size: Float, parent: Float): Float {
            val low = margin - base
            val high = parent - margin - size - base
            return if (high < low) low else d.coerceIn(low, high)
        }
        return axis(dx, baseX, width, parentWidth) to axis(dy, baseY, height, parentHeight)
    }

    /**
     * New width when the handle (corner opposite the anchor) is pulled by ([dx], [dy]). [growX] / [growY]: direction
     * that enlarges (−1: towards the left / the top). The dominant gesture decides (width directly, or height × 16/9).
     */
    fun resize(width: Float, dx: Float, dy: Float, growX: Int, growY: Int): Float {
        val byWidth = dx * growX
        val byHeight = dy * growY * ASPECT
        return width + if (abs(byWidth) >= abs(byHeight)) byWidth else byHeight
    }

    /** Handle corner: opposite the anchor corner; returns the enlarging direction (x, y). */
    fun growDirection(anchor: Alignment): Pair<Int, Int> = when (anchor) {
        Alignment.TopEnd -> -1 to 1
        Alignment.TopStart -> 1 to 1
        Alignment.BottomEnd -> -1 to -1
        else -> 1 to -1 // BottomStart
    }

    /** Touch in the handle area ([handle] dp wide) at the corner opposite the anchor. */
    fun onHandle(x: Float, y: Float, width: Float, height: Float, anchor: Alignment, handle: Float): Boolean {
        val (gx, gy) = growDirection(anchor)
        val inX = if (gx < 0) x <= handle else x >= width - handle
        val inY = if (gy > 0) y >= height - handle else y <= handle
        return inX && inY
    }
}

/** Position and size of a camera window, saved per screen ([key]): offset from the anchor and width, in dp. */
@Stable
class CameraWindowState internal constructor(private val key: String, val defaultWidth: Dp) {
    var dx by mutableFloatStateOf(0f); internal set
    var dy by mutableFloatStateOf(0f); internal set
    var width by mutableFloatStateOf(defaultWidth.value); internal set
    /** Gesture in progress (move or resize): outline highlighted. */
    var active by mutableStateOf(false); internal set

    internal fun load(context: Context) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        dx = prefs.getFloat("$key.dx", 0f); dy = prefs.getFloat("$key.dy", 0f)
        width = prefs.getFloat("$key.width", defaultWidth.value)
    }

    internal fun save(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("$key.dx", dx).putFloat("$key.dy", dy).putFloat("$key.width", width).apply()
    }

    /** Original position and size (double-tap on the handle). */
    internal fun reset(context: Context) {
        dx = 0f; dy = 0f; width = defaultWidth.value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove("$key.dx").remove("$key.dy").remove("$key.width").apply()
    }

    private companion object { const val FILE = "camera_window" }
}

@Composable
fun rememberCameraWindowState(key: String, defaultWidth: Dp): CameraWindowState {
    val context = LocalContext.current
    return remember(key) { CameraWindowState(key, defaultWidth).also { it.load(context) } }
}

/** Placement of a window without offset, in dp: anchor corner, host screen size, height / width. */
@Immutable
internal data class CameraWindowPlacement(val cornerX: Float, val cornerY: Float, val parentWidth: Float, val parentHeight: Float, val heightRatio: Float)

/**
 * Floating camera window, placed in the [BoxScope] of the hosting screen at anchor [anchor] (and [modifier], original
 * margins included): dragging the window moves it, pulling the handle at the opposite corner (or pinching with two
 * fingers) resizes it, a double-tap on the handle puts it back to its original place and size. Position and size are
 * kept within the screen (including after folding or rotating) and saved.
 *
 * Gestures read before the content (`Initial` pass): the player (WebView) and the retry of a failed stream keep simple
 * taps, a drag beyond the threshold is taken away from them. The map under the window no longer receives gestures
 * made on the window.
 */
@Composable
fun BoxScope.CameraWindow(state: CameraWindowState, anchor: Alignment, modifier: Modifier = Modifier,
                          content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val (growX, growY) = CameraWindowGeometry.growDirection(anchor)
    // Last known placement; updated only if it changes (a single extra placement pass).
    var placement by remember { mutableStateOf<CameraWindowPlacement?>(null) }
    val handleDp = 32f

    fun maxWidth(): Float = placement?.let { CameraWindowGeometry.maxWidth(it.parentWidth, it.parentHeight) } ?: Float.MAX_VALUE
    /** Bounded offset for a given width (fixed anchor corner: the position without offset depends on the size). */
    fun clamped(width: Float): Pair<Float, Float> {
        val p = placement ?: return state.dx to state.dy
        val height = width * p.heightRatio
        val baseX = if (growX < 0) p.cornerX - width else p.cornerX
        val baseY = if (growY < 0) p.cornerY - height else p.cornerY
        return CameraWindowGeometry.clampOffset(state.dx, state.dy, baseX, baseY, width, height, p.parentWidth, p.parentHeight)
    }

    val shownWidth = CameraWindowGeometry.clampWidth(state.width, maxWidth())
    val applied = remember { floatArrayOf(0f, 0f) }
    Box(modifier.align(anchor)
        .offset {
            val (x, y) = clamped(shownWidth)
            applied[0] = x; applied[1] = y
            with(density) { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) }
        }
        .onPlaced { coordinates ->
            val parent = coordinates.parentLayoutCoordinates ?: return@onPlaced
            val position = coordinates.positionInParent()
            with(density) {
                val w = coordinates.size.width.toDp().value
                val h = coordinates.size.height.toDp().value
                if (w <= 0f) return@onPlaced
                val left = position.x.toDp().value - applied[0]
                val top = position.y.toDp().value - applied[1]
                val next = CameraWindowPlacement(if (growX < 0) left + w else left, if (growY < 0) top + h else top,
                    parent.size.width.toDp().value, parent.size.height.toDp().value, h / w)
                if (next != placement) placement = next
            }
        }
        .width(shownWidth.dp)
        .semantics { contentDescription = tr("Camera window: drag to move, pull the corner to resize", "Fenêtre caméra : glisser pour déplacer, tirer le coin pour redimensionner") }
        .pointerInput(state, anchor) {
            var lastHandleTap = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val resizing = CameraWindowGeometry.onHandle(down.position.x.toDp().value, down.position.y.toDp().value,
                    size.width.toDp().value, size.height.toDp().value, anchor, handleDp)
                var moved = false
                var travel = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        // Pinch: width multiplied by the finger spread (fixed anchor corner).
                        val (a, b) = pressed[0] to pressed[1]
                        val before = (a.previousPosition - b.previousPosition).getDistance()
                        val after = (a.position - b.position).getDistance()
                        if (before > 0f && after > 0f) state.width = CameraWindowGeometry.clampWidth(state.width * after / before, maxWidth())
                        moved = true; state.active = true
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = pressed.first()
                    val delta = change.positionChange()
                    val stepPx = if (moved) delta else {
                        travel += delta
                        if (travel.getDistance() < viewConfiguration.touchSlop) continue
                        moved = true; state.active = true
                        travel
                    }
                    val stepX = stepPx.x.toDp().value
                    val stepY = stepPx.y.toDp().value
                    if (resizing) {
                        state.width = CameraWindowGeometry.clampWidth(CameraWindowGeometry.resize(state.width, stepX, stepY, growX, growY), maxWidth())
                    } else {
                        // Offset bounded at each step: the window does not "stick" to the edge when the finger comes back.
                        state.dx += stepX; state.dy += stepY
                    }
                    clamped(CameraWindowGeometry.clampWidth(state.width, maxWidth())).let { (x, y) -> state.dx = x; state.dy = y }
                    change.consume()
                }
                if (moved) {
                    state.width = CameraWindowGeometry.clampWidth(state.width, maxWidth())
                    clamped(state.width).let { (x, y) -> state.dx = x; state.dy = y }
                    state.active = false
                    state.save(context)
                } else if (resizing) {
                    val now = System.currentTimeMillis()
                    if (now - lastHandleTap < viewConfiguration.doubleTapTimeoutMillis) { state.reset(context); lastHandleTap = 0L }
                    else lastHandleTap = now
                }
            }
        }) {
        content()
        ResizeHandle(Modifier.align(handleAlignment(anchor)).size(handleDp.dp), growX, growY, state.active)
    }
}

private fun handleAlignment(anchor: Alignment): Alignment = when (anchor) {
    Alignment.TopEnd -> Alignment.BottomStart
    Alignment.TopStart -> Alignment.BottomEnd
    Alignment.BottomEnd -> Alignment.TopStart
    else -> Alignment.TopEnd
}

/** Resize handle: two slanted strokes in the corner, on a dark disc. */
@Composable
private fun ResizeHandle(modifier: Modifier, growX: Int, growY: Int, active: Boolean) {
    val corner = when {
        growX < 0 && growY > 0 -> RoundedCornerShape(bottomStart = 10.dp, topEnd = 8.dp)
        growX > 0 && growY > 0 -> RoundedCornerShape(bottomEnd = 10.dp, topStart = 8.dp)
        growX < 0 -> RoundedCornerShape(topStart = 10.dp, bottomEnd = 8.dp)
        else -> RoundedCornerShape(topEnd = 10.dp, bottomStart = 8.dp)
    }
    Canvas(modifier.padding(4.dp).background(Color.Black.copy(alpha = if (active) 0.6f else 0.35f), corner)) {
        val color = Color.White.copy(alpha = if (active) 1f else 0.85f)
        val stroke = 1.6.dp.toPx()
        // Outer corner of the handle (side towards which the window grows): two strokes parallel to the diagonal.
        val cx = if (growX < 0) 0f else size.width
        val cy = if (growY > 0) size.height else 0f
        val sx = if (growX < 0) 1f else -1f
        val sy = if (growY > 0) -1f else 1f
        val inset = size.minDimension * 0.2f
        for (k in listOf(0.3f, 0.6f)) {
            val d = size.minDimension * k
            drawLine(color, Offset(cx + sx * inset, cy + sy * (inset + d)), Offset(cx + sx * (inset + d), cy + sy * inset), stroke, StrokeCap.Round)
        }
    }
}
