package fr.luba.poc.map

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr

/**
 * Point [aheadM] metres along the initial direction of [path] (its first non-degenerate edge), or null: where the
 * start arrow of [drawRouteStart] points.
 */
fun routeStartAhead(path: List<Point>?, aheadM: Double = 1.0): Point? {
    val a = path?.firstOrNull() ?: return null
    val b = path.drop(1).firstOrNull { it.distance(a) > 1e-6 } ?: return null
    val d = a.distance(b)
    return Point(a.x + (b.x - a.x) / d * aheadM, a.y + (b.y - a.y) / d * aheadM)
}

/**
 * Purple dot and explicit label, at a constant on-screen size even when zooming. [toward]: screen point along the
 * initial direction of the route ([routeStartAhead]); an arrow then shows the direction the robot sets off in.
 */
fun DrawScope.drawRouteStart(at: Offset, zoom: Float = 1f, mapRotation: Float = 0f, label: String = tr("Start", "Départ"),
                              purple: Color = Color(0xFF7253C7), toward: Offset? = null) {
    val unit = 1.dp.toPx() / zoom.coerceAtLeast(0.1f)
    // Initial direction, in map coordinates (turns with the map), 30 dp long from the dot.
    toward?.minus(at)?.takeIf { it.getDistance() > 1e-3f }?.let { v ->
        val d = v / v.getDistance()
        val n = Offset(-d.y, d.x)
        val tip = at + d * (30 * unit)
        val tail = at + d * (7 * unit)
        val neck = tip - d * (9 * unit)
        val head = Path().apply {
            moveTo(tip.x, tip.y)
            (neck + n * (6 * unit)).let { lineTo(it.x, it.y) }
            (neck - n * (6 * unit)).let { lineTo(it.x, it.y) }
            close()
        }
        drawLine(Color.White, tail, neck, 5 * unit, StrokeCap.Round)
        drawPath(head, Color.White, style = Stroke(3 * unit))
        drawLine(purple, tail, neck, 2.5f * unit, StrokeCap.Round)
        drawPath(head, purple)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 13 * unit
        typeface = Typeface.DEFAULT_BOLD
    }
    val width = paint.measureText(label) + 16 * unit
    // Cancel the map rotation around the geographic start point.
    rotate(-mapRotation, at) {
        val topLeft = at + Offset(10 * unit, -12 * unit)
        drawRoundRect(purple, topLeft, Size(width,24 * unit), CornerRadius(12 * unit))
        drawRoundRect(Color.White, topLeft, Size(width,24 * unit), CornerRadius(12 * unit),
            style = Stroke(1.5f * unit))
        drawCircle(purple, 6 * unit, at)
        drawCircle(Color.White, 6 * unit, at, style = Stroke(2 * unit))
        drawCircle(Color.White, 1.5f * unit, at)
        drawIntoCanvas { canvas ->
            val baseline = at.y - (paint.ascent() + paint.descent()) / 2
            canvas.nativeCanvas.drawText(label,topLeft.x + 8 * unit,baseline,paint)
        }
    }
}
