package fr.luba.poc.weather

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Reflectivity of a radar frame around the garden. `dbz` is indexed row by row; [NO_ECHO] marks the absence of
 * echo. The garden is at pixel ([gardenX], [gardenY]).
 */
class RadarGrid(
    val timeSec: Long,
    val width: Int,
    val height: Int,
    val dbz: ByteArray,
    val gardenX: Double,
    val gardenY: Double,
    val metersPerPixel: Double,
) {
    init { require(dbz.size == width * height) }
    fun at(x: Int, y: Int): Int? = dbz[y * width + x].toInt().takeIf { it != NO_ECHO.toInt() }

    companion object {
        const val NO_ECHO: Byte = Byte.MIN_VALUE
        fun encode(dbz: Int?): Byte = dbz?.coerceIn(-100, 127)?.toByte() ?: NO_ECHO
    }
}

/** Summary of a frame: rain on the garden and nearest rain cell. */
data class FrameStats(
    val timeSec: Long,
    /** Maximum echo within the local radius (radar and position uncertainty), null if dry. */
    val localDbz: Int?,
    /** Distance of the nearest rainy pixel (≥ threshold), null if none within the search radius. */
    val nearestKm: Double?,
    val nearestDbz: Int?,
    /** Share of rainy pixels within 10 km: tells an isolated shower from a wide front. */
    val coverage10Km: Double,
)

/** Apparent movement of the nearest rain over the latest frames (estimate, not a forecast). */
data class RainApproach(val distanceKm: Double, val speedKmH: Double, val etaMin: Int?, val frames: Int) {
    val approaching: Boolean get() = speedKmH > 0.0
}

object RadarAnalysis {
    const val LOCAL_RADIUS_M = 1_500.0
    const val SEARCH_RADIUS_M = 60_000.0
    /** Threshold of a rain that really wets the lawn (≈ 0.6 mm/h). */
    const val RAIN_DBZ = 20

    fun stats(grid: RadarGrid, localRadiusM: Double = LOCAL_RADIUS_M, searchRadiusM: Double = SEARCH_RADIUS_M,
              rainDbz: Int = RAIN_DBZ): FrameStats {
        val mpp = grid.metersPerPixel
        var local: Int? = null
        var nearest = Double.MAX_VALUE; var nearestDbz: Int? = null
        var near10 = 0; var rain10 = 0
        val r = (searchRadiusM / mpp).toInt() + 1
        val x0 = (grid.gardenX - r).toInt().coerceAtLeast(0); val x1 = (grid.gardenX + r).toInt().coerceAtMost(grid.width - 1)
        val y0 = (grid.gardenY - r).toInt().coerceAtLeast(0); val y1 = (grid.gardenY + r).toInt().coerceAtMost(grid.height - 1)
        for (y in y0..y1) for (x in x0..x1) {
            // Distance to the pixel centre.
            val d = hypot(x + 0.5 - grid.gardenX, y + 0.5 - grid.gardenY) * mpp
            if (d > searchRadiusM) continue
            val v = grid.at(x, y)
            if (d <= 10_000.0) { near10++; if (v != null && v >= rainDbz) rain10++ }
            if (v == null) continue
            // The pixel containing the garden always counts, even if the local radius is smaller than it.
            if ((d <= localRadiusM || d <= mpp * 0.75) && v >= RadarPalette.PRECIP_DBZ) local = maxOf(local ?: v, v)
            if (v >= rainDbz && d < nearest) { nearest = d; nearestDbz = v }
        }
        return FrameStats(grid.timeSec, local, nearestDbz?.let { nearest / 1000.0 }, nearestDbz,
            if (near10 == 0) 0.0 else rain10.toDouble() / near10)
    }

    /**
     * Approach speed from a linear regression of the distance over the recent frames (≤ 40 min).
     * A decreasing distance may also come from a cell forming: the result remains an estimate.
     */
    fun approach(frames: List<FrameStats>, windowSec: Long = 40 * 60): RainApproach? {
        val last = frames.maxByOrNull { it.timeSec } ?: return null
        val lastKm = last.nearestKm ?: return null
        val pts = frames.filter { it.nearestKm != null && last.timeSec - it.timeSec in 0..windowSec }.sortedBy { it.timeSec }
        if (pts.size < 3) return RainApproach(lastKm, 0.0, null, pts.size)
        val t0 = pts.first().timeSec
        val xs = pts.map { (it.timeSec - t0) / 3600.0 }; val ys = pts.map { it.nearestKm!! }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0.0) return RainApproach(lastKm, 0.0, null, pts.size)
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        val speed = -slope
        // Beyond 120 km/h, the jump comes from a new cell, not a movement.
        if (speed > 120.0) return RainApproach(lastKm, 0.0, null, pts.size)
        val eta = if (speed >= 3.0 && lastKm > 0.0) (lastKm / speed * 60.0).roundToInt().takeIf { it <= 120 } else null
        return RainApproach(lastKm, speed, eta, pts.size)
    }
}
