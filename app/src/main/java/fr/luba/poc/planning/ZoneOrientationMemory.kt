package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

/**
 * What was actually mowed on a zone, for "Change every session" ([OrientationMode.EACH_SESSION]): pass directions
 * (one for Zigzag, two for Checkerboard: a and a + 90, each 0..179), row phase ([OrientationRule.PHASES] index), outline
 * of the zone when it was mowed ([shapeKey]) and date.
 */
data class ZoneOrientation(val directions: List<Int>, val phaseIndex: Int, val shapeKey: String, val mowedAtMillis: Long) {
    init { require(directions.isNotEmpty() && directions.all { it in 0..179 } && phaseIndex in OrientationRule.PHASES.indices) }

    /** Same outline as when it was mowed: an entry of a retraced or enlarged zone is ignored. */
    fun matches(zone: MapShape): Boolean = shapeKey == shapeKey(zone.points)

    companion object {
        /** Hash of the zone outline rounded to 1 cm. */
        fun shapeKey(points: List<Point>): String =
            stableHash(points.joinToString(";") { "${(it.x * 100).roundToLong()},${(it.y * 100).roundToLong()}" })
    }
}

/** Memory of the last mowed directions per zone id (`zone-orientations.json`); unreadable ⇒ empty, atomic write. */
class ZoneOrientationStore(directory: File) {
    private val file = File(directory, "zone-orientations.json")

    @Synchronized fun read(): Map<Long, ZoneOrientation> = runCatching {
        if (!file.exists()) return emptyMap()
        val zones = JSONObject(file.readText()).getJSONObject("zones")
        zones.keys().asSequence().mapNotNull { key ->
            runCatching {
                val o = zones.getJSONObject(key)
                val directions = o.getJSONArray("directions").let { a -> (0 until a.length()).map { a.getInt(it) } }
                key.toLong() to ZoneOrientation(directions, o.getInt("phaseIndex"), o.getString("shapeKey"), o.getLong("mowedAt"))
            }.getOrNull()
        }.toMap().toSortedMap()
    }.getOrDefault(emptyMap())

    /**
     * [memory] written over the file: the zones on file that it does not hold are kept (a mowing that ends before the
     * file was first read must not drop them; entries are only ever added or replaced, the newer ones win).
     */
    @Synchronized fun merge(memory: Map<Long, ZoneOrientation>) = save(read() + memory)

    @Synchronized fun save(all: Map<Long, ZoneOrientation>) {
        val zones = JSONObject()
        all.toSortedMap().forEach { (id, z) ->
            zones.put(id.toString(), JSONObject().put("directions", JSONArray(z.directions)).put("phaseIndex", z.phaseIndex)
                .put("shapeKey", z.shapeKey).put("mowedAt", z.mowedAtMillis))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { it.write(JSONObject().put("version", 1).put("zones", zones).toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(file)) { tr("Failed to save the pass directions", "Échec sauvegarde des directions de passes") }
    }
}

/** Share of each zone actually mowed by a mission, and the resulting memory. Pure, without Android. */
object ZoneProgress {
    /** Share of a zone's cutting metres that a mowing must have done for its direction to be remembered. */
    const val RECORD_SHARE = 0.5

    /**
     * Share (0..1) of the cutting metres of each zone of [preview] done after [doneMeters] metres along its cutting paths,
     * in order. [totalMeters]: cutting metres of the prepared mission when they differ from the preview (links trimmed),
     * [doneMeters] is then scaled to the preview. A path belongs to the zone it was planned for ([Preview.pathZoneIds]);
     * when that is unknown (older saves), to the zone holding most of its length.
     */
    fun shares(preview: Preview, map: RobotMap, doneMeters: Double, totalMeters: Double? = null): Map<Long, Double> {
        val zones = preview.zoneIds.mapNotNull { id -> map.shapes.firstOrNull { it.kind == ShapeKind.ZONE && it.id == id } }
        if (zones.isEmpty()) return emptyMap()
        val all = preview.cuttingPaths
        val known = preview.cuttingPathZones
        val paths = all.indices.filter { all[it].size >= 2 }
        val planned = paths.sumOf { length(all[it]) }
        val done = if (totalMeters != null && totalMeters > 1e-9) doneMeters * planned / totalMeters else doneMeters
        val total = linkedMapOf<Long, Double>(); val mowed = linkedMapOf<Long, Double>()
        var before = 0.0
        for (i in paths) {
            val path = all[i]
            val meters = length(path)
            val owner = known?.get(i) ?: owner(path, zones)
            total[owner] = (total[owner] ?: 0.0) + meters
            mowed[owner] = (mowed[owner] ?: 0.0) + (done - before).coerceIn(0.0, meters)
            before += meters
        }
        return total.mapValues { (id, meters) -> if (meters > 0) ((mowed[id] ?: 0.0) / meters).coerceIn(0.0, 1.0) else 0.0 }
    }

    /** Sampling step and outline tolerance of [owner]: a border loop on the recorded outline counts for its zone. */
    private const val SAMPLE_M = 0.25
    private const val OUTLINE_M = 0.05

    /**
     * Zone holding the most metres of [path] (inside or within [OUTLINE_M] of its outline), the first of [zones] on a tie;
     * outside every zone, the zone nearest to its first point.
     */
    private fun owner(path: List<Point>, zones: List<MapShape>): Long {
        val held = DoubleArray(zones.size)
        for ((a, b) in path.zipWithNext()) {
            val d = a.distance(b)
            if (d <= 0) continue
            val steps = kotlin.math.ceil(d / SAMPLE_M).toInt()
            for (k in 0 until steps) {
                val t = (k + 0.5) / steps
                val p = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                zones.forEachIndexed { z, zone -> if (Geometry.inside(p, zone.points) || outline(p, zone) <= OUTLINE_M) held[z] += d / steps }
            }
        }
        val best = zones.indices.maxBy { held[it] }
        if (held[best] > 0) return zones[zones.indices.first { held[it] >= held[best] - 1e-9 }].id
        return zones.minBy { outline(path.first(), it) }.id
    }

    private fun outline(p: Point, zone: MapShape): Double =
        zone.points.indices.minOf { i -> Geometry.distance(p, zone.points[i], zone.points[(i + 1) % zone.points.size]) }

    /**
     * [memory] after a mowing of [preview]: every zone mowed at [RECORD_SHARE] or more whose applied pattern uses an
     * orientation (Zigzag, Checkerboard, Zamboni) gets its directions, row phase and outline; the other zones keep their entry.
     */
    fun update(memory: Map<Long, ZoneOrientation>, preview: Preview, map: RobotMap, doneMeters: Double, now: Long,
               totalMeters: Double? = null): Map<Long, ZoneOrientation> {
        val shares = shares(preview, map, doneMeters, totalMeters)
        val result = memory.toSortedMap()
        preview.zoneIds.forEachIndexed { i, id ->
            val mode = preview.zoneModes.getOrNull(i) ?: preview.mode
            val zone = map.shapes.firstOrNull { it.kind == ShapeKind.ZONE && it.id == id }
            if (!mode.usesOrientation || zone == null || (shares[id] ?: 0.0) < RECORD_SHARE) return@forEachIndexed
            val angle = preview.zoneAngles.getOrNull(i) ?: preview.angle
            result[id] = ZoneOrientation(OrientationRule.directions(angle, mode.orientationPeriod),
                OrientationRule.phaseIndex(preview.zonePhases.getOrNull(i) ?: 0.5), ZoneOrientation.shapeKey(zone.points), now)
        }
        return result
    }
}
