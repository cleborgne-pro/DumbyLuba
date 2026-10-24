package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.ui.Format
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Border strategy per border type ([BorderType]): margin between the side of the chassis and the recorded outline for
 * the border lap and the pass ends.
 *
 * - **Open lawn**: [OPEN_OFFSET_M] (a slight overrun is harmless: the lap cuts as close as possible);
 * - **Curb** and untyped: the set outer-loop margin;
 * - **Wall**: at least [WALL_OFFSET_M] (the chassis does not rub the wall);
 * - **Danger**: at least [DANGER_MARGIN_M] plus the overhang tolerated in manoeuvres
 *   ([RecordedBoundary.PHYSICAL_TOLERANCE_M]): no footprint of any pass, pivots included, comes closer than
 *   [DANGER_MARGIN_M] to the portion.
 *
 * Applied by moving the outlines given to the planner ([effectiveMap]): zone outline pulled in (or pushed out for an
 * open lawn) and no-go outline pushed out (or pulled in) by the difference with the set margin along each typed
 * portion. The robot map, the navigation and the safety checks keep the recorded outlines.
 */
object BorderStrategy {
    const val OPEN_OFFSET_M = 0.02
    const val WALL_OFFSET_M = 0.12
    /** Minimum distance between the chassis footprint and a Danger portion for the cutting passes, manoeuvres included. */
    const val DANGER_MARGIN_M = 0.4

    /** Chassis side to outline on the border lap along a portion of [type], for the set outer-loop margin [setting]. */
    fun sideOffsetM(type: BorderType?, setting: Double): Double = when (type) {
        BorderType.OPEN -> min(setting, OPEN_OFFSET_M)
        BorderType.WALL -> max(setting, WALL_OFFSET_M)
        BorderType.DANGER -> max(setting, DANGER_MARGIN_M + RecordedBoundary.PHYSICAL_TOLERANCE_M)
        BorderType.CURB, null -> setting
    }

    /** Strategy applied along a portion of [type], as shown in the route preview. */
    fun label(type: BorderType, setting: Double): String {
        val cm = Format.num(sideOffsetM(type, setting) * 100, 0)
        return when (type) {
            BorderType.OPEN -> tr("lap at $cm cm, as close as possible", "tour à $cm cm, au plus près")
            BorderType.CURB -> tr("lap at $cm cm (set margin)", "tour à $cm cm (marge réglée)")
            BorderType.WALL -> tr("lap at $cm cm, chassis off the wall", "tour à $cm cm, châssis écarté du mur")
            BorderType.DANGER -> tr("lap at $cm cm, passes ≥ ${Format.num(DANGER_MARGIN_M * 100, 0)} cm away, transits ≥ ${Format.num(fr.luba.poc.simulation.TrajectoryPlanner.DANGER_TRANSIT_MARGIN_M * 100, 0)} cm",
                "tour à $cm cm, passes à ${Format.num(DANGER_MARGIN_M * 100, 0)} cm au moins, transits à ${Format.num(fr.luba.poc.simulation.TrajectoryPlanner.DANGER_TRANSIT_MARGIN_M * 100, 0)} cm")
        }
    }

    /** One line per type present among [portions] of the boundaries [shapeIds]: "Danger · 12 m · lap at 55 cm…". */
    fun summary(portions: List<BorderPortion>, shapeIds: Set<Long>, setting: Double): List<String> =
        portions.filter { it.shapeId in shapeIds }.groupBy { it.type }.toSortedMap().map { (type, list) ->
            "${type.label} · ${Format.num(list.sumOf { it.lengthM }, 0)} m · ${label(type, setting)}"
        }

    private val factory = GeometryFactory()
    private fun polygon(points: List<Point>): Polygon =
        factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private fun band(points: List<Point>, width: Double) =
        factory.createLineString(points.map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(width, 8)

    /**
     * [map] with its zone and no-go outlines moved along the typed [portions] ([sideOffsetM] minus [setting]); shapes
     * without a moved portion are returned as is. A shape whose moved outline would be degenerate keeps its outline.
     */
    fun effectiveMap(map: RobotMap, portions: List<BorderPortion>, setting: Double): RobotMap {
        val byShape = portions.filter { it.points.size >= 2 && abs(sideOffsetM(it.type, setting) - setting) > 1e-6 }.groupBy { it.shapeId }
        if (byShape.isEmpty()) return map
        return map.copy(shapes = map.shapes.map { shape ->
            val list = byShape[shape.id] ?: return@map shape
            if (shape.points.size < 3 || (shape.kind != ShapeKind.ZONE && shape.kind != ShapeKind.NO_GO)) return@map shape
            moved(shape, list, setting)?.let { shape.copy(points = it) } ?: shape
        })
    }

    private fun moved(shape: MapShape, portions: List<BorderPortion>, setting: Double): List<Point>? = runCatching {
        val zone = shape.kind == ShapeKind.ZONE
        var g: org.locationtech.jts.geom.Geometry = polygon(shape.points)
        val original = g.area
        for (p in portions) {
            val delta = sideOffsetM(p.type, setting) - setting
            val strip = band(p.points, abs(delta))
            // Zone: a larger margin pulls the outline in; no-go: it pushes the outline out.
            g = if ((delta > 0) == zone) g.difference(strip) else g.union(strip)
        }
        val largest = (0 until g.numGeometries).map { g.getGeometryN(it) }.filterIsInstance<Polygon>().maxByOrNull { it.area }
            ?: return@runCatching null
        if (!largest.isValid || largest.area < 0.5 * original) return@runCatching null
        largest.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
    }.getOrNull()
}
