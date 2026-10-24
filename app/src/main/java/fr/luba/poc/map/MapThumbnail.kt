package fr.luba.poc.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.dp
import fr.luba.poc.ui.theme.MammotionColors
import kotlin.math.max
import kotlin.math.min

/**
 * Static map preview, without interaction: used on Home and in lists.
 * Uses the color codes of the main preview (mowed zones in green, no-go zones in red).
 */
@Composable
fun MapThumbnail(map: RobotMap, modifier: Modifier = Modifier, empty: Color = MammotionColors.SurfaceMuted) {
    val colors = MammotionColors
    Canvas(modifier) {
        val pts = map.shapes.flatMap { it.points }
        if (pts.size < 2) {
            drawRect(empty)
            return@Canvas
        }
        val minX = pts.minOf { it.x }; val minY = pts.minOf { it.y }
        val w = max(1.0, pts.maxOf { it.x } - minX); val h = max(1.0, pts.maxOf { it.y } - minY)
        val pad = 16f
        val scale = min((size.width - pad) / w, (size.height - pad) / h)
        val ox = (size.width - w * scale) / 2; val oy = (size.height - h * scale) / 2
        fun screen(p: Point) = Offset((ox + (p.x - minX) * scale).toFloat(), (size.height - oy - (p.y - minY) * scale).toFloat())
        fun path(points: List<Point>, closed: Boolean): Path {
            val out = Path()
            points.forEachIndexed { i, p -> val o = screen(p); if (i == 0) out.moveTo(o.x, o.y) else out.lineTo(o.x, o.y) }
            if (closed) out.close()
            return out
        }
        for (shape in map.shapes.sortedBy { it.type }) {
            if (shape.points.size < 2) continue
            val fill = when (shape.kind) { ShapeKind.ZONE -> colors.ZoneFillSelected; ShapeKind.NO_GO -> colors.NoGoFill; ShapeKind.PASSAGE -> colors.Ink
                        ShapeKind.OBSTACLE, ShapeKind.VISION_ZONE -> colors.ObstacleFill; ShapeKind.NO_STOP_ZONE -> colors.Info }
            val stroke = when (shape.kind) { ShapeKind.NO_GO -> colors.NoGoStroke; ShapeKind.PASSAGE -> colors.Ink; ShapeKind.NO_STOP_ZONE -> colors.Info; else -> colors.ZoneStroke }
            if (shape.kind.isContour) drawPath(path(shape.points, true), fill)
            drawPath(path(shape.points, shape.kind.isContour), stroke, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
        }
    }
}
