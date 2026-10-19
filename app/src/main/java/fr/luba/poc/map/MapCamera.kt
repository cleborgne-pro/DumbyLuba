package fr.luba.poc.map

import kotlin.math.*

/** Pixel transformation, independent of the map's metric coordinates. */
data class MapCamera(val zoom: Double = 1.0, val rotation: Double = 0.0, val offset: Point = Point(0.0,0.0)) {
    private fun rotate(p: Point, degrees: Double): Point {
        val a = Math.toRadians(degrees)
        return Point(cos(a)*p.x-sin(a)*p.y,sin(a)*p.x+cos(a)*p.y)
    }
    fun inverse(p: Point, center: Point): Point {
        val q=rotate(Point(p.x-center.x-offset.x,p.y-center.y-offset.y),-rotation)
        return Point(center.x+q.x/zoom,center.y+q.y/zoom)
    }
    fun transform(centroid: Point, center: Point, pan: Point, factor: Double, degrees: Double): MapCamera {
        val nextZoom=(zoom*factor).coerceIn(0.5,20.0)
        val relative=rotate(Point((centroid.x-center.x-offset.x)*nextZoom/zoom,(centroid.y-center.y-offset.y)*nextZoom/zoom),degrees)
        return MapCamera(nextZoom,(rotation+degrees)%360,Point(centroid.x-center.x+pan.x-relative.x,centroid.y-center.y+pan.y-relative.y))
    }
    /** Rotates the view around the screen center: the point shown at the center does not move. */
    fun rotatedBy(degrees: Double): MapCamera = copy(rotation=signedDegrees(rotation+degrees), offset=rotate(offset,degrees))
    /**
     * Robot following: offset that brings the point [canvas] (layer frame, before zoom and rotation around [center])
     * to [target] on screen. The chosen zoom and rotation are kept.
     */
    fun following(canvas: Point, center: Point, target: Point): MapCamera {
        val turned = rotate(Point((canvas.x-center.x)*zoom,(canvas.y-center.y)*zoom),rotation)
        return copy(offset=Point(target.x-center.x-turned.x,target.y-center.y-turned.y))
    }
    /** Rotation that brings north back to the top by the shortest way. */
    fun northTurn(): Double = -signedDegrees(rotation)

    companion object {
        /** Angle brought into [-180, 180). */
        fun signedDegrees(degrees: Double): Double = ((degrees%360)+540)%360-180
    }
}
