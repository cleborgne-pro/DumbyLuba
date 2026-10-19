package fr.luba.poc.map

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import fr.luba.poc.i18n.tr

/** Purple dot and explicit label, at a constant on-screen size even when zooming. */
fun DrawScope.drawRouteStart(at: Offset, zoom: Float = 1f, mapRotation: Float = 0f, label: String = tr("Start", "Départ"),
                              purple: Color = Color(0xFF7253C7)) {
    val unit = 1.dp.toPx() / zoom.coerceAtLeast(0.1f)
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
