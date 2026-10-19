package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Robot slide measured during an in-place pivot: average speed of the rotation centre ([vx], [vy], m/s) during
 * [seconds] seconds of pure rotation, around ([x], [y]).
 */
data class SlideSample(val x: Double, val y: Double, val vx: Double, val vy: Double, val seconds: Double, val atMs: Long)

/** [SlideField] cell: average slide speed and the measured pivot duration supporting it. */
data class SlideCell(val i: Int, val j: Int, val vx: Double, val vy: Double, val weightS: Double, val updatedAtMs: Long)

/**
 * Map of pivot slides, learnt during mowing without an extra sensor: the robot pivots in place, so any movement of its
 * rotation centre between two positions without commanded advance is a slide. Mowing of 2026-10-15 in the Jardin
 * d'Eden: 3.5 cm/s at the bottom of the zone versus 0.9 cm/s at the top, always downhill whatever the direction of
 * rotation (about 14 cm per 4 s pivot).
 *
 * Cells of [CELL_M]; each measurement is a duration-weighted average, the cumulative duration being capped at
 * [MAX_WEIGHT_S] so that recent mowings (dry or wet grass) take precedence over older ones. The expected speed at a
 * point combines the cell and its neighbours ([velocity]); it is only given with at least [MIN_WEIGHT_S] of
 * measurements and stays bounded to [MAX_SPEED_MPS].
 */
class SlideField(cells: Collection<SlideCell> = emptyList()) {
    companion object {
        const val CELL_M = 1.0
        const val MAX_WEIGHT_S = 40.0
        const val MIN_WEIGHT_S = 3.0
        /** Maximum slide speed kept (2026-10-15: 3.5 cm/s on average, 7 cm/s on the worst pivots). */
        const val MAX_SPEED_MPS = 0.06
        /** Measurement discarded beyond this: robot pushed, hit or lifted rather than sliding. */
        const val OUTLIER_MPS = 0.15
        /** Minimum pure rotation of a measurement. */
        const val MIN_SAMPLE_S = 0.8
        /** Weight of the eight neighbouring cells in [velocity]. */
        const val NEIGHBOR_WEIGHT = 0.5
        const val SCHEMA_VERSION = 1
        /** Maximum number of cells sent to the gateway. */
        const val MAX_CELLS = 4000

        fun cellOf(v: Double) = floor(v / CELL_M).toInt()

        fun decodeJson(text: String): SlideField {
            val json = JSONObject(text)
            require(json.getInt("schemaVersion") == SCHEMA_VERSION) { tr("Unknown slide map version", "Version de la carte des glissements inconnue") }
            val array = json.getJSONArray("cells")
            return SlideField((0 until array.length()).mapNotNull { k ->
                runCatching {
                    val o = array.getJSONObject(k)
                    SlideCell(o.getInt("i"), o.getInt("j"), o.getDouble("vx"), o.getDouble("vy"), o.getDouble("w"), o.optLong("at", 0L))
                }.getOrNull()?.takeIf { it.vx.isFinite() && it.vy.isFinite() && it.weightS.isFinite() && it.weightS > 0 }
            })
        }

        /** Reads the binary encoding ([write]); bounded cell count. */
        fun read(input: DataInputStream): SlideField {
            val count = input.readUnsignedShort()
            require(count <= MAX_CELLS) { tr("Slide map of $count cells refused", "Carte des glissements de $count cellules refusée") }
            return SlideField(List(count) {
                SlideCell(input.readInt(), input.readInt(), input.readFloat().toDouble(), input.readFloat().toDouble(),
                    input.readFloat().toDouble(), 0L)
            }.filter { it.vx.isFinite() && it.vy.isFinite() && it.weightS.isFinite() && it.weightS > 0 })
        }
    }

    private val cells: Map<Pair<Int, Int>, SlideCell> = cells.associateBy { it.i to it.j }

    val isEmpty get() = cells.isEmpty()
    val size get() = cells.size
    fun cells(): List<SlideCell> = cells.values.sortedWith(compareBy({ it.i }, { it.j }))

    /** Map enriched with [sample]; too short, non-finite or outlier measurement ignored. */
    fun learned(sample: SlideSample): SlideField {
        if (!(sample.seconds >= MIN_SAMPLE_S && sample.x.isFinite() && sample.y.isFinite() && sample.vx.isFinite() && sample.vy.isFinite())) return this
        if (hypot(sample.vx, sample.vy) > OUTLIER_MPS) return this
        val key = cellOf(sample.x) to cellOf(sample.y)
        val old = cells[key]
        val w = old?.weightS ?: 0.0
        val total = w + sample.seconds
        val vx = ((old?.vx ?: 0.0) * w + sample.vx * sample.seconds) / total
        val vy = ((old?.vy ?: 0.0) * w + sample.vy * sample.seconds) / total
        return SlideField(cells.values.filter { (it.i to it.j) != key } +
            SlideCell(key.first, key.second, vx, vy, minOf(total, MAX_WEIGHT_S), maxOf(old?.updatedAtMs ?: 0L, sample.atMs)))
    }

    /** Expected slide speed during a pivot at [p] (m/s), or null without enough measurements. */
    fun velocity(p: Point): Point? {
        if (cells.isEmpty() || !p.x.isFinite() || !p.y.isFinite()) return null
        val ci = cellOf(p.x); val cj = cellOf(p.y)
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (di in -1..1) for (dj in -1..1) {
            val c = cells[(ci + di) to (cj + dj)] ?: continue
            val w = c.weightS * if (di == 0 && dj == 0) 1.0 else NEIGHBOR_WEIGHT
            sx += c.vx * w; sy += c.vy * w; sw += w
        }
        if (sw < MIN_WEIGHT_S) return null
        val vx = sx / sw; val vy = sy / sw
        val speed = hypot(vx, vy)
        val scale = if (speed > MAX_SPEED_MPS) MAX_SPEED_MPS / speed else 1.0
        return Point(vx * scale, vy * scale)
    }

    /** Cells within [marginM] of [points] (route of a tracking handed to the gateway), the [maxCells] best measured. */
    fun around(points: List<Point>, marginM: Double = 2.0, maxCells: Int = MAX_CELLS): SlideField {
        if (cells.isEmpty() || points.isEmpty()) return SlideField()
        val keep = HashSet<Pair<Int, Int>>()
        val reach = kotlin.math.ceil(marginM / CELL_M).toInt()
        points.forEach { p -> val ci = cellOf(p.x); val cj = cellOf(p.y)
            for (di in -reach..reach) for (dj in -reach..reach) keep += (ci + di) to (cj + dj) }
        return SlideField(cells.filterKeys { it in keep }.values.sortedByDescending { it.weightS }.take(minOf(maxCells, MAX_CELLS)))
    }

    /** Map with speeds multiplied by [factor] (wet grass: [fr.luba.poc.weather.GrassWetness.slideFactor]). */
    fun scaled(factor: Double): SlideField =
        if (factor == 1.0 || !factor.isFinite()) this else SlideField(cells.values.map { it.copy(vx = it.vx * factor, vy = it.vy * factor) })

    fun toJson(): String = JSONObject().put("schemaVersion", SCHEMA_VERSION).put("cells", JSONArray().apply {
        cells().forEach { c -> put(JSONObject().put("i", c.i).put("j", c.j).put("vx", c.vx).put("vy", c.vy).put("w", c.weightS).put("at", c.updatedAtMs)) }
    }).toString()

    /** Compact binary encoding (tracking handed to the gateway). */
    fun write(out: DataOutputStream) {
        val list = cells().take(MAX_CELLS)
        out.writeShort(list.size)
        list.forEach { c -> out.writeInt(c.i); out.writeInt(c.j); out.writeFloat(c.vx.toFloat()); out.writeFloat(c.vy.toFloat()); out.writeFloat(c.weightS.toFloat()) }
    }

    override fun equals(other: Any?) = other is SlideField && other.cells == cells
    override fun hashCode() = cells.hashCode()
}

/**
 * Groups the pure-rotation intervals ([DeadReckoning.rotationSlide]) of the same pivot into one [SlideSample]
 * measurement: total centre movement divided by the rotation duration, average position.
 */
class PivotSlideMeter {
    private var dx = 0.0; private var dy = 0.0; private var seconds = 0.0
    private var sumX = 0.0; private var sumY = 0.0; private var count = 0
    private var lastAt = 0L

    /**
     * New position: [interval] = (centre movement, duration) if the elapsed interval was a pure rotation, otherwise
     * null. Returns the measurement of the pivot that just ended, if any.
     */
    fun update(center: Point, at: Long, interval: Pair<Point, Double>?): SlideSample? {
        if (interval != null) {
            dx += interval.first.x; dy += interval.first.y; seconds += interval.second
            sumX += center.x; sumY += center.y; count++; lastAt = at
            return null
        }
        return flush()
    }

    /** Current pivot ended (end of tracking): its measurement, if any. */
    fun flush(): SlideSample? {
        val sample = if (count > 0 && seconds >= SlideField.MIN_SAMPLE_S)
            SlideSample(sumX / count, sumY / count, dx / seconds, dy / seconds, seconds, lastAt) else null
        dx = 0.0; dy = 0.0; seconds = 0.0; sumX = 0.0; sumY = 0.0; count = 0
        return sample
    }
}

/** Component along the axis [headingDegrees] of a movement [v] (m). */
internal fun alongAxis(v: Point, headingDegrees: Double): Double {
    val r = Math.toRadians(headingDegrees)
    return v.x * kotlin.math.cos(r) + v.y * kotlin.math.sin(r)
}

/** Significant non-zero speed (at least 2 mm/s). */
internal fun Point.significant() = abs(x) + abs(y) >= 0.002

/** Slide map in `filesDir/slide-field.json` (atomic write). */
class SlideFieldStore(private val filesDir: java.io.File) {
    private fun file() = java.io.File(filesDir, "slide-field.json")

    fun load(): SlideField = try {
        val stored = file()
        if (!stored.exists() || stored.length() > 4L * 1024 * 1024) SlideField() else SlideField.decodeJson(stored.readText())
    } catch (_: Exception) {
        SlideField()
    }

    fun save(field: SlideField) {
        val stored = file()
        val temp = java.io.File(stored.path + ".tmp")
        temp.outputStream().use { stream -> stream.write(field.toJson().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        check(temp.renameTo(stored)) { tr("Slide map not saved", "Carte des glissements non enregistrée") }
    }
}
