package fr.luba.poc.map

import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/** Last layer: the badges stay readable above the routes and the heatmap. */
fun DrawScope.drawZoneOrder(map: RobotMap, order: List<Long>, screen: (Point) -> Offset, unit: Float, mapRotation: Float = 0f) {
    if (order.isEmpty()) return
    order.forEachIndexed { index,id ->
        val zone=map.shapes.firstOrNull { it.id==id && it.kind==ShapeKind.ZONE && it.points.size>=3 } ?: return@forEachIndexed
        val polygon=GeometryFactory().createPolygon((zone.points+zone.points.first()).map { Coordinate(it.x,it.y) }.toTypedArray())
        val center=polygon.centroid.takeIf { polygon.covers(it) } ?: polygon.interiorPoint
        val at=screen(Point(center.x,center.y))
        // "Ink" badge circled in white: distinct from the yellow no-go zones.
        drawCircle(Color.White,19f*unit,at)
        drawCircle(Color(0xFF212226),16.5f*unit,at)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.WHITE
            textSize=17f*unit
            typeface=Typeface.DEFAULT_BOLD
            textAlign=android.graphics.Paint.Align.CENTER
        }
        rotate(-mapRotation, at) {
            drawContext.canvas.nativeCanvas.drawText((index+1).toString(),at.x,at.y-(paint.ascent()+paint.descent())/2,paint)
        }
    }
}
