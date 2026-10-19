package fr.luba.poc.map

import android.content.Context
import java.util.zip.CRC32
import kotlin.math.roundToLong

/**
 * Border validated in the garden by border following: chosen offset ([offsetM], side of the robot → boundary), offset
 * actually applied by the computation, date and, if the loop was measured, the offsets held (min, median).
 * [fingerprint]: boundary fingerprint at validation time; a redrawn boundary is no longer validated.
 */
data class BorderValidationEntry(
    val shapeId: Long,
    val offsetM: Double,
    val appliedOffsetM: Double,
    val validatedAtMs: Long,
    val fingerprint: Long,
    val measuredMinM: Double? = null,
    val measuredMedianM: Double? = null,
)

/** State of a map border with respect to the saved validations. */
enum class BorderValidationStatus { VALIDATED, CHANGED, NOT_VALIDATED }

/**
 * Border following validations, one per boundary (zone boundary or no-go), with their offset setting.
 * Pure logic (encoding, status); storage is done by [BorderValidationStore].
 */
object BorderValidation {
    /** Fingerprint of the boundary rounded to the centimeter: stable from one map reading to the next, changes if redrawn. */
    fun fingerprint(shape: MapShape): Long {
        val crc = CRC32()
        val text = shape.points.joinToString(";") { "${(it.x * 100).roundToLong()},${(it.y * 100).roundToLong()}" }
        crc.update(text.toByteArray())
        return crc.value
    }

    fun status(shape: MapShape, saved: Map<Long, BorderValidationEntry>): BorderValidationStatus {
        val entry = saved[shape.id] ?: return BorderValidationStatus.NOT_VALIDATED
        return if (entry.fingerprint == fingerprint(shape)) BorderValidationStatus.VALIDATED else BorderValidationStatus.CHANGED
    }

    /** Validation still valid for [shape] (unchanged boundary), otherwise null. */
    fun validFor(shape: MapShape, saved: Map<Long, BorderValidationEntry>): BorderValidationEntry? =
        saved[shape.id]?.takeIf { it.fingerprint == fingerprint(shape) }

    /** Number of validated borders among [choices] (unchanged boundary). */
    fun validatedCount(choices: List<BorderChoice>, saved: Map<Long, BorderValidationEntry>): Int =
        choices.count { status(it.shape, saved) == BorderValidationStatus.VALIDATED }

    /**
     * Storage format: one line per border, `id|offset|applied|date|fingerprint|min|median` (min and median empty
     * without a measurement). Unreadable lines are ignored.
     */
    fun encode(saved: Map<Long, BorderValidationEntry>): String = saved.values.sortedBy { it.shapeId }.joinToString("\n") { e ->
        listOf(e.shapeId, e.offsetM, e.appliedOffsetM, e.validatedAtMs, e.fingerprint, e.measuredMinM ?: "", e.measuredMedianM ?: "")
            .joinToString("|")
    }

    fun decode(text: String?): Map<Long, BorderValidationEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 7) return@mapNotNull null
        fun finite(s: String) = s.toDoubleOrNull()?.takeIf { it.isFinite() }
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val offset = finite(parts[1])?.takeIf { it in 0.0..CoveragePlanner.MAX_OUTER_OFFSET_M } ?: return@mapNotNull null
        val applied = finite(parts[2]) ?: return@mapNotNull null
        val at = parts[3].toLongOrNull() ?: return@mapNotNull null
        val fingerprint = parts[4].toLongOrNull() ?: return@mapNotNull null
        id to BorderValidationEntry(id, offset, applied, at, fingerprint, finite(parts[5]), finite(parts[6]))
    }.toMap()
}

/** Border following validations (boundary identifier of the robot map), kept between two launches. */
class BorderValidationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("border-validations", Context.MODE_PRIVATE)
    fun read(): Map<Long, BorderValidationEntry> = BorderValidation.decode(prefs.getString(KEY, null))
    fun save(entry: BorderValidationEntry): Map<Long, BorderValidationEntry> = write(read() + (entry.shapeId to entry))
    fun clear(shapeId: Long): Map<Long, BorderValidationEntry> = write(read() - shapeId)
    private fun write(saved: Map<Long, BorderValidationEntry>): Map<Long, BorderValidationEntry> {
        prefs.edit().putString(KEY, BorderValidation.encode(saved)).apply()
        return saved
    }
    private companion object { const val KEY = "validations" }
}
