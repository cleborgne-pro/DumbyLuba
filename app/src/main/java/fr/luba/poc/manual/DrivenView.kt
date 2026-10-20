package fr.luba.poc.manual

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.luba.poc.map.Point
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * View of the "record by driving" map: zoom (dp per metre), robot following, heading up or north up, and the focus
 * kept when the user pans the map (following suspended until [recenter]).
 */
@Stable
class DrivenMapView {
    var dpPerMeter by mutableFloatStateOf(DrivenView.DEFAULT_DP_PER_M)
    var follow by mutableStateOf(true)
    var headingUp by mutableStateOf(false)
    /** Map point shown at the centre of the visible area while following is suspended. */
    var focus by mutableStateOf<Point?>(null)
    /** Map rotation kept while following is suspended in heading-up mode. */
    var pannedRotation by mutableFloatStateOf(0f)
    /** Last focus and rotation drawn, read by the gestures; not observed. */
    internal var drawnFocus: Point? = null
    internal var drawnRotation: Float = 0f

    fun zoom(factor: Float) { dpPerMeter = DrivenView.zoomed(dpPerMeter, factor) }
    fun recenter() { follow = true; focus = null }
}

/** Geometry of the follow map, without Compose: tested on the JVM. */
object DrivenView {
    /** Default zoom: about 11 m across a folded Fold screen. */
    const val DEFAULT_DP_PER_M = 36f
    const val MIN_DP_PER_M = 6f
    const val MAX_DP_PER_M = 240f
    private val SCALE_STEPS = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0)

    fun zoomed(dpPerMeter: Float, factor: Float): Float =
        if (!factor.isFinite() || factor <= 0f) dpPerMeter else (dpPerMeter * factor).coerceIn(MIN_DP_PER_M, MAX_DP_PER_M)

    /**
     * Screen rotation of the map (degrees, clockwise): heading up brings the robot's nose to the top of the screen
     * (local heading 90° = north, see [fr.luba.poc.map.drawLuba]); north up otherwise or without a heading.
     */
    fun rotation(headingUp: Boolean, heading: Double?): Float =
        if (headingUp && heading != null && heading.isFinite()) (heading - 90.0).toFloat() else 0f

    /** Screen position (before the map rotation around the anchor) of map point [p] when [focus] is at the anchor. */
    fun screen(p: Point, focus: Point, anchorX: Float, anchorY: Float, pxPerMeter: Float): Pair<Float, Float> =
        (anchorX + (p.x - focus.x) * pxPerMeter).toFloat() to (anchorY - (p.y - focus.y) * pxPerMeter).toFloat()

    /** Focus after dragging the rotated map by ([dragX], [dragY]) pixels: the map follows the finger. */
    fun panned(focus: Point, dragX: Float, dragY: Float, pxPerMeter: Float, rotationDeg: Float): Point {
        val a = Math.toRadians(-rotationDeg.toDouble())
        val dx = dragX * cos(a) - dragY * sin(a)
        val dy = dragX * sin(a) + dragY * cos(a)
        return Point(focus.x - dx / pxPerMeter, focus.y + dy / pxPerMeter)
    }

    /** Longest round length (0.5, 1, 2, 5… m) whose bar fits in [maxPx]. */
    fun scaleBarMeters(pxPerMeter: Float, maxPx: Float): Double =
        SCALE_STEPS.lastOrNull { it * pxPerMeter <= maxPx } ?: SCALE_STEPS.first()

    /**
     * Centre and scale (px per metre) showing [points] in a visible area of [width] × [height] px with a margin, at
     * least [minSpanM] across. Null without points.
     */
    fun fit(points: List<Point>, width: Float, height: Float, minSpanM: Double = 6.0): Pair<Point, Float>? {
        val shown = points.filter { it.x.isFinite() && it.y.isFinite() }
        if (shown.isEmpty() || width <= 0f || height <= 0f) return null
        val minX = shown.minOf { it.x }; val maxX = shown.maxOf { it.x }
        val minY = shown.minOf { it.y }; val maxY = shown.maxOf { it.y }
        val spanX = max(minSpanM, (maxX - minX) * 1.25); val spanY = max(minSpanM, (maxY - minY) * 1.25)
        val scale = min(width / spanX, height / spanY).toFloat()
        return Point((minX + maxX) / 2, (minY + maxY) / 2) to scale
    }
}
