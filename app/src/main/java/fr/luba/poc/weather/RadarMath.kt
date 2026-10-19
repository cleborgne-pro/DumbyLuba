package fr.luba.poc.weather

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import fr.luba.poc.i18n.tr

/**
 * "Universal Blue" palette (scheme 2) of the RainViewer tiles, the only one available for free since 2026.
 * Official table: https://www.rainviewer.com/files/rainviewer_api_colors_table.csv (Universal Blue column, rain,
 * -10 to 75 dBZ). Tiles are requested without smoothing or snow: each pixel uses an exact colour.
 */
object RadarPalette {
    const val MIN_DBZ = -10
    /** Below: very weak echoes (grey), often noise or drizzle that does not reach the ground. */
    const val PRECIP_DBZ = 15

    private val RGB = intArrayOf(
        0x636159, 0x66635a, 0x69665c, 0x6c685d, 0x6f6b5f, 0x726e61, 0x757062, 0x787364, 0x7c7565, 0x7f7867,
        0x827b69, 0x857d6a, 0x88806c, 0x8b826d, 0x8e856f, 0x928871, 0x9e9375, 0xaa9e79, 0xb6a97e, 0xc2b482,
        0xcec087, 0xd2c48b, 0xd6c88f, 0xdacc93, 0xded097, 0x88ddee, 0x6cd1eb, 0x51c5e8, 0x36bae5, 0x1baee2,
        0x00a3e0, 0x009ad5, 0x0091ca, 0x0088bf, 0x007fb4, 0x0077aa, 0x0070a3, 0x00699c, 0x006295, 0x005b8e,
        0x005588, 0x005180, 0x004e78, 0x004a70, 0x004768, 0xffee00, 0xffe000, 0xffd200, 0xffc500, 0xffb700,
        0xffaa00, 0xff9f00, 0xff9500, 0xff8b00, 0xff8100, 0xff4400, 0xf23600, 0xe62800, 0xd91b00, 0xcd0d00,
        0xc10000, 0xa80000, 0x8f0000, 0x760000, 0x5d0000, 0xffaaff, 0xff9fff, 0xff95ff, 0xff8bff, 0xff81ff,
        0xff77ff, 0xff6cff, 0xff62ff, 0xff58ff, 0xff4eff, 0xffffff,
    )
    private val exact = HashMap<Int, Int>().apply { RGB.forEachIndexed { i, c -> putIfAbsent(c, MIN_DBZ + i) } }

    /** Palette ARGB colour (non-premultiplied alpha) for a given dBZ, for the legend and the tint. */
    fun color(dbz: Int): Int {
        val i = (dbz - MIN_DBZ).coerceIn(0, RGB.lastIndex)
        return (0xff shl 24) or RGB[i]
    }

    /** dBZ of a non-premultiplied ARGB pixel; null if transparent or outside the palette (background, decoding artefact). */
    fun dbz(argb: Int): Int? {
        if ((argb ushr 24) < 8) return null
        val rgb = argb and 0xffffff
        exact[rgb]?.let { return it }
        // Possible decoder rounding on barely opaque pixels: nearest colour, tight tolerance.
        var best = -1; var bestD = Int.MAX_VALUE
        val r = rgb shr 16 and 0xff; val g = rgb shr 8 and 0xff; val b = rgb and 0xff
        for (i in RGB.indices) {
            val c = RGB[i]
            val dr = (c shr 16 and 0xff) - r; val dg = (c shr 8 and 0xff) - g; val db = (c and 0xff) - b
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD <= 48) MIN_DBZ + best else null
    }
}

/** Reflectivity conversions, Marshall-Palmer relation (Z = 200·R^1.6): order of magnitude, not a measurement. */
object Reflectivity {
    fun rainRateMmH(dbz: Double): Double = (10.0.pow(dbz / 10.0) / 200.0).pow(1.0 / 1.6)
    fun dbzFor(rateMmH: Double): Double = 10.0 * kotlin.math.log10(200.0 * rateMmH.pow(1.6))

    fun label(dbz: Int?): String = when {
        dbz == null || dbz < RadarPalette.PRECIP_DBZ -> tr("no rain", "aucune pluie")
        dbz < 23 -> tr("very light rain", "pluie très faible")
        dbz < 30 -> tr("light rain", "pluie faible")
        dbz < 40 -> tr("moderate rain", "pluie modérée")
        dbz < 50 -> tr("heavy rain", "pluie forte")
        else -> tr("thunderstorm", "orage")
    }
}

/** Web Mercator projection (XYZ tiles) shared by the base map and the radar. */
object WebMercator {
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    fun worldX(lon: Double, zoom: Int, tile: Int = 256): Double = (lon + 180.0) / 360.0 * tile * (1 shl zoom)

    fun worldY(lat: Double, zoom: Int, tile: Int = 256): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * tile * (1 shl zoom)
    }

    fun lon(worldX: Double, zoom: Int, tile: Int = 256): Double = worldX / (tile * (1 shl zoom)) * 360.0 - 180.0

    fun lat(worldY: Double, zoom: Int, tile: Int = 256): Double {
        val n = PI - 2.0 * PI * worldY / (tile * (1 shl zoom))
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    fun metersPerPixel(lat: Double, zoom: Int, tile: Int = 256): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / (tile * (1 shl zoom))

    /** Index of the tile containing a world pixel. */
    fun tileIndex(worldPx: Double, tile: Int): Int = floor(worldPx / tile).toInt()
}
