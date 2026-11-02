package fr.luba.poc.map

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.max
import kotlin.math.min

/**
 * Gestures of the map view ([MapScreen]): one or two fingers pan, zoom and rotate the view (a one-finger drag stops the
 * robot following). A single tap not handled by a control of the map is passed to [onTap] in map meters, with the
 * fit scale of the view (pixels per meter at zoom 1), when [tapEnabled]. While [painter] gives an input (zone choice by
 * painting), one finger paints instead and a second finger drops the stroke and moves the view.
 */
internal suspend fun PointerInputScope.mapViewGestures(
    frame: MapFrameState, follow: RobotFollow, viewport: () -> RobotMap, viewportTop: Float, viewportBottom: Float,
    tapEnabled: () -> Boolean, painter: () -> MapPaintInput?, onTap: (Point, Double) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var handledByControl = down.isConsumed
        var transformed = false
        var travel = Offset.Zero
        val stroke = if (handledByControl) null else painter()?.let { PaintGesture(it) { o -> viewPoint(o, frame, follow, viewport(), viewportTop, viewportBottom).first } }
        do {
            val event = awaitPointerEvent()
            handledByControl = handledByControl || event.changes.any { it.isConsumed }
            if (stroke != null && !handledByControl && stroke.paint(event, down, viewConfiguration.touchSlop)) continue
            val pan = event.calculatePan()
            travel += pan
            if (event.changes.count { it.pressed } > 1 || travel.getDistance() > viewConfiguration.touchSlop) transformed = true
            if (!handledByControl && transformed && event.changes.any { it.pressed }) {
                val centroid = event.calculateCentroid(useCurrent = false)
                if (centroid != Offset.Unspecified) {
                    // One-finger drag while following: the view stays where it is, following stopped.
                    if (follow.enabled && event.changes.count { it.pressed } == 1) { frame.camera = follow.view(frame.camera); follow.stop() }
                    frame.camera = follow.view(frame.camera).transform(
                        Point(centroid.x.toDouble(), centroid.y.toDouble()),
                        Point(size.width / 2.0, size.height / 2.0),
                        Point(pan.x.toDouble(), pan.y.toDouble()),
                        event.calculateZoom().toDouble(),
                        event.calculateRotation().toDouble(),
                    )
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        stroke?.end()
        // Single tap: selects or deselects the zone under the finger.
        if (stroke == null && !transformed && !handledByControl && tapEnabled()) {
            val (p, scale) = viewPoint(down.position, frame, follow, viewport(), viewportTop, viewportBottom)
            onTap(p, scale)
        }
    }
}

/** Map point (meters) under [position] on the view, and the fit scale of the view (pixels per meter at zoom 1). */
private fun PointerInputScope.viewPoint(position: Offset, frame: MapFrameState, follow: RobotFollow, viewport: RobotMap,
                                        viewportTop: Float, viewportBottom: Float): Pair<Point, Double> {
    val base = follow.view(frame.camera).inverse(
        Point(position.x.toDouble(), position.y.toDouble()),
        Point(size.width / 2.0, size.height / 2.0),
    )
    val availableHeight = (size.height - viewportTop - viewportBottom).coerceAtLeast(120f)
    val scale = min((size.width - 40) / mapExtentX(viewport), availableHeight / mapExtentY(viewport))
    val ox = (size.width - mapExtentX(viewport) * scale) / 2
    val oy = viewportBottom + (availableHeight - mapExtentY(viewport) * scale) / 2
    return Point(
        (base.x - ox) / scale + mapMinX(viewport),
        (size.height - base.y - oy) / scale + mapMinY(viewport),
    ) to scale
}

/**
 * One-finger stroke of a painting gesture. It only starts once the gesture is recognized as a single finger (dragged
 * beyond the touch slop, or tap released, which paints a dot): the first finger of a pinch does not paint. A second
 * finger drops the stroke; the view then moves and that gesture paints no more.
 */
private class PaintGesture(private val input: MapPaintInput, private val toMap: (Offset) -> Point) {
    private var pending = true
    private var painting = false

    /** Handles [event] as painting (consumed); false once a second finger made it a view gesture. */
    fun paint(event: PointerEvent, down: PointerInputChange, touchSlop: Float): Boolean {
        if (event.changes.count { it.pressed } > 1) {
            pending = false
            if (painting) { input.finish(false); painting = false }
            return false
        }
        if (!pending && !painting) return false
        val change = event.changes.firstOrNull { it.id == down.id }
        if (pending && change != null && (!change.pressed || (change.position - down.position).getDistance() > touchSlop)) {
            pending = false
            painting = true
            input.begin(toMap(down.position))
        }
        if (painting && change != null && change.pressed) input.extend(toMap(change.position))
        event.changes.forEach { it.consume() }
        return true
    }

    /** Finger lifted: the stroke is kept. */
    fun end() { if (painting) input.finish(true) }
}

private fun mapMinX(map: RobotMap): Double = map.shapes.flatMap { it.points }.minOfOrNull { it.x } ?: 0.0
private fun mapMinY(map: RobotMap): Double = map.shapes.flatMap { it.points }.minOfOrNull { it.y } ?: 0.0

private fun mapExtentX(map: RobotMap): Double {
    val p = map.shapes.flatMap { it.points }
    return max(1.0, (p.maxOfOrNull { it.x } ?: 20.0) - (p.minOfOrNull { it.x } ?: 0.0))
}

private fun mapExtentY(map: RobotMap): Double {
    val p = map.shapes.flatMap { it.points }
    return max(1.0, (p.maxOfOrNull { it.y } ?: 15.0) - (p.minOfOrNull { it.y } ?: 0.0))
}
