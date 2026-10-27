package fr.luba.poc.map

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry as JtsGeometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Guide lines of the manual direction of the passes ([OrientationMode.MANUAL]), drawn on the map while the angle is
 * chosen: straight lines at the planner angle (counter-clockwise from the map's x axis, like [Preview.angle]) clipped
 * to the chosen zones minus their no-go zones. Visual only, with a readable spacing rather than the real one: no
 * coverage is computed. Pure and quick (a few dozen lines), recomputed on every angle step.
 */
object OrientationGuide {
    /** Number of lines across the largest extent of the chosen zones. */
    const val LINES_ACROSS = 18

    private val factory = GeometryFactory()

    /** Mowable area of [zoneIds] on [map] (no-go zones removed), null when no chosen zone has an area. */
    fun area(map: RobotMap, zoneIds: Collection<Long>): JtsGeometry? {
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE && it.id in zoneIds && it.points.size >= 3 }
        if (zones.isEmpty()) return null
        var area: JtsGeometry = factory.createGeometryCollection(zones.map(::polygon).toTypedArray()).union()
        map.shapes.filter { it.excludes }.forEach { area = area.difference(polygon(it)) }
        return area.takeIf { !it.isEmpty && it.area > 1e-6 }
    }

    /**
     * Segments at [angleDeg] across [area], [spacingM] apart and centred on its envelope; [spacingM] null = the extent
     * divided by [LINES_ACROSS]. Every segment lies inside [area].
     */
    fun lines(area: JtsGeometry, angleDeg: Int, spacingM: Double? = null): List<List<Point>> {
        val env = area.envelopeInternal
        val spacing = spacingM ?: (max(env.width, env.height) / LINES_ACROSS).coerceAtLeast(0.05)
        val a = Math.toRadians(angleDeg.toDouble())
        val d = Point(cos(a), sin(a)); val n = Point(-sin(a), cos(a))
        val c = Point(env.centre().x, env.centre().y)
        // Half-diagonal: lines long enough and offsets wide enough to cross the whole envelope at any angle.
        val half = hypot(env.width, env.height) / 2 + spacing
        val count = ceil(half / spacing).toInt()
        val out = mutableListOf<List<Point>>()
        for (k in -count..count) {
            val o = Point(c.x + n.x * k * spacing, c.y + n.y * k * spacing)
            val line = factory.createLineString(arrayOf(Coordinate(o.x - d.x * half, o.y - d.y * half), Coordinate(o.x + d.x * half, o.y + d.y * half)))
            val cut = runCatching { area.intersection(line) }.getOrNull() ?: continue
            for (i in 0 until cut.numGeometries) {
                val piece = cut.getGeometryN(i) as? LineString ?: continue
                if (piece.length > 1e-3) out.add(piece.coordinates.map { Point(it.x, it.y) })
            }
        }
        return out
    }

    /** Lines of the chosen direction: one family, two (a and a + 90) for the checkerboard. */
    fun lines(map: RobotMap, zoneIds: Collection<Long>, angleDeg: Int, mode: CoverageMode): List<List<List<Point>>> {
        val area = area(map, zoneIds) ?: return emptyList()
        val families = if (mode == CoverageMode.CHECKERBOARD) listOf(angleDeg, angleDeg + 90) else listOf(angleDeg)
        return families.map { lines(area, it) }
    }

    /**
     * Manual angle of a finger drawn from [from] to [to] in map coordinates: direction of the line, in steps of
     * [OrientationMode.MANUAL_STEP_DEG] from 0 to [OrientationMode.MANUAL_MAX_DEG] (180 is 0: same passes).
     */
    fun angleOf(from: Point, to: Point): Int {
        val raw = Math.toDegrees(atan2(to.y - from.y, to.x - from.x))
        val step = OrientationMode.MANUAL_STEP_DEG
        return foldAngle((foldAngle(raw.roundToInt()) / step.toDouble()).roundToInt() * step)
    }

    /** Smallest drag, in meters, read as a direction on the guide map of [area]. */
    fun minimumDrag(area: JtsGeometry): Double = max(area.envelopeInternal.width, area.envelopeInternal.height) / 25

    private fun polygon(shape: MapShape) =
        factory.createPolygon((shape.points + shape.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(0.0)
}
