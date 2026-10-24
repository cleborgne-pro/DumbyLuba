package fr.luba.poc.planning

import android.content.Context
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeKind
import fr.luba.poc.map.kind
import fr.luba.poc.map.excludes

/** Origin of the start point of a mowing or survey preview. */
enum class StartSource { NEAREST, SAVED, CHOSEN }

/**
 * Start point requested for the computation: [point] null = as close as possible to the robot (base if it is
 * charging, position unknown or robot inside the base safety zone). [zoneId]: zone containing the requested point.
 */
data class StartRequest(val point: Point?, val source: StartSource, val zoneId: Long? = null)

/** Start choice made in the preview, taking precedence over the zone's saved default. */
sealed interface StartOverride {
    /** Point tapped on the preview map. */
    data class Chosen(val point: Point) : StartOverride
    /** As close as possible to the robot, even if the first zone has a saved start. */
    data object Nearest : StartOverride
}

/**
 * Start of a mission: point chosen in the preview, otherwise the saved start of the first zone in the mowing order,
 * otherwise as close as possible to the robot.
 */
object ZoneStart {
    /** Zone among [zoneIds] containing [p], outside any no-go zone; null otherwise. */
    fun zoneAt(map: RobotMap, zoneIds: Collection<Long>, p: Point): Long? {
        if (!p.x.isFinite() || !p.y.isFinite()) return null
        if (map.shapes.any { it.excludes && it.points.size >= 3 && Geometry.inside(p, it.points) }) return null
        return zoneIds.firstOrNull { id ->
            map.shapes.any { it.kind == ShapeKind.ZONE && it.id == id && it.points.size >= 3 && Geometry.inside(p, it.points) }
        }
    }

    /** Saved default of [zoneId], only if it is still inside the zone (map read again or retraced). */
    fun savedFor(map: RobotMap, zoneId: Long, saved: Map<Long, Point>): Point? =
        saved[zoneId]?.takeIf { zoneAt(map, listOf(zoneId), it) == zoneId }

    /** [order]: zones in mowing order; the first one is mowed first. */
    fun requested(map: RobotMap, order: List<Long>, override: StartOverride?, saved: Map<Long, Point>): StartRequest {
        when (override) {
            StartOverride.Nearest -> return StartRequest(null, StartSource.NEAREST)
            is StartOverride.Chosen -> zoneAt(map, order, override.point)?.let { return StartRequest(override.point, StartSource.CHOSEN, it) }
            null -> Unit
        }
        val first = order.firstOrNull() ?: return StartRequest(null, StartSource.NEAREST)
        return savedFor(map, first, saved)?.let { StartRequest(it, StartSource.SAVED, first) }
            ?: StartRequest(null, StartSource.NEAREST)
    }

    /** Storage format: one line per zone, `id|x|y`. Unreadable lines are ignored. */
    fun encode(saved: Map<Long, Point>): String =
        saved.entries.sortedBy { it.key }.joinToString("\n") { (id, p) -> "$id|${p.x}|${p.y}" }

    fun decode(text: String?): Map<Long, Point> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 3) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val x = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val y = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        id to Point(x, y)
    }.toMap()
}

/** Starts saved per zone (zone id of the robot map), kept between two launches. */
class ZoneStartStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zone-starts", Context.MODE_PRIVATE)
    fun read(): Map<Long, Point> = ZoneStart.decode(prefs.getString(KEY, null))
    fun save(zoneId: Long, point: Point): Map<Long, Point> = write(read() + (zoneId to point))
    fun clear(zoneId: Long): Map<Long, Point> = write(read() - zoneId)
    private fun write(saved: Map<Long, Point>): Map<Long, Point> {
        prefs.edit().putString(KEY, ZoneStart.encode(saved)).apply()
        return saved
    }
    private companion object { const val KEY = "starts" }
}
