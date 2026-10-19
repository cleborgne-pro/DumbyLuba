package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.planning.MissionLeg
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory

/**
 * Continuous blades within a zone: a link between two passes of the same zone (border → zigzag, pass → pass) keeps
 * the blades running if it stays entirely inside that zone. The blades only stop to change zone, reach the first pass
 * or return to base.
 */
object BladeContinuity {
    /** Tolerance for assigning a pass to its zone (recorded outline, roundings). */
    const val TOLERANCE_M = 0.05
    private val factory = GeometryFactory()

    /** Blades running per leg: passes, and links internal to a zone. */
    fun legs(mission: List<MissionLeg>, zones: List<List<Point>>): List<Boolean> {
        val areas = zones.filter { it.size >= 3 }.mapNotNull { points ->
            runCatching {
                val ring = (points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray()
                val polygon = factory.createPolygon(ring).let { if (it.isValid) it else it.buffer(0.0) }
                PreparedGeometryFactory.prepare(polygon.buffer(TOLERANCE_M, 8))
            }.getOrNull()
        }
        fun line(path: List<Point>) = if (path.size == 1) factory.createPoint(Coordinate(path[0].x, path[0].y))
            else factory.createLineString(path.map { Coordinate(it.x, it.y) }.toTypedArray())
        val zoneOf = mission.map { leg -> if (!leg.cutting || leg.path.isEmpty()) -1 else line(leg.path).let { g -> areas.indexOfFirst { it.covers(g) } } }
        return mission.mapIndexed { i, leg ->
            if (leg.cutting) return@mapIndexed true
            val before = zoneOf.getOrNull(i - 1)?.takeIf { mission[i - 1].cutting } ?: -1
            val after = zoneOf.getOrNull(i + 1)?.takeIf { mission[i + 1].cutting } ?: -1
            before >= 0 && before == after && leg.path.isNotEmpty() && areas[before].covers(line(leg.path))
        }
    }
}
