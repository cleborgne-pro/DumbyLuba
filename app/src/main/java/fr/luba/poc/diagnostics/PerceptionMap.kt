package fr.luba.poc.diagnostics

import fr.luba.poc.proto.LubaMsgProto.LubaMsg

/**
 * Local perception map pushed by the robot (`MctlPept.perception_local_map_visualization.map`, sender
 * `DEV_PERCEPTION`). Observed on the LUBA 2 AWD (firmware 1.30.31.19) on 2026-10-20, 1.4 s after the official
 * return to base was requested: a 40-byte lossless WebP image of 61 × 31 pixels, entirely black. Perception
 * therefore runs while the firmware navigates by itself; the meaning of the pixels (grid around the robot, cell
 * size, colours) is still unknown. Kept outside the sensor capture: the message arrives without any subscription.
 */
class PerceptionMapFrame(
    val image: ByteArray, val info: PerceptionImage.Info, val receivedAt: Long, val count: Long,
    /** Robot state (`sys_status`) known when the map arrived: 14 = return to base, 13 = task. */
    val robotState: Int?,
) {
    override fun equals(other: Any?) = other is PerceptionMapFrame && other.count == count && other.receivedAt == receivedAt
    override fun hashCode() = (count * 31 + receivedAt).hashCode()
}

/** Format and size read from the image header, without decoding it (JVM-testable; the display decodes it). */
object PerceptionImage {
    data class Info(val format: String, val width: Int?, val height: Int?, val bytes: Int) {
        val label: String get() = if (width != null && height != null) "$format ${width}×$height · $bytes B" else "$format · $bytes B"
    }

    fun info(b: ByteArray): Info {
        fun u(i: Int) = b[i].toInt() and 0xFF
        fun le(i: Int, n: Int): Int { var v = 0; for (k in n - 1 downTo 0) v = (v shl 8) or u(i + k); return v }
        fun be(i: Int): Int = (0..3).fold(0) { v, k -> (v shl 8) or u(i + k) }
        fun ascii(i: Int, n: Int) = if (b.size >= i + n) String(b, i, n, Charsets.US_ASCII) else ""
        if (ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") {
            when (ascii(12, 4)) {
                // Lossless: signature 0x2f, then width-1 and height-1 on 14 bits each.
                "VP8L" -> if (b.size >= 25 && u(20) == 0x2f) {
                    val v = le(21, 4)
                    return Info("WebP lossless", (v and 0x3FFF) + 1, ((v shr 14) and 0x3FFF) + 1, b.size)
                }
                // Lossy: 3-byte frame tag, start code 9d 01 2a, then 14-bit width and height.
                "VP8 " -> if (b.size >= 30 && u(23) == 0x9d && u(24) == 0x01 && u(25) == 0x2a)
                    return Info("WebP", le(26, 2) and 0x3FFF, le(28, 2) and 0x3FFF, b.size)
                // Extended: canvas width-1 and height-1 on 24 bits.
                "VP8X" -> if (b.size >= 30) return Info("WebP", le(24, 3) + 1, le(27, 3) + 1, b.size)
            }
            return Info("WebP", null, null, b.size)
        }
        if (b.size >= 24 && u(0) == 0x89 && ascii(1, 3) == "PNG") return Info("PNG", be(16), be(20), b.size)
        return Info(if (b.isEmpty()) "empty" else "unknown", null, null, b.size)
    }
}

class PerceptionMapObserver {
    var last: PerceptionMapFrame? = null
        private set
    private var count = 0L

    /** New frame for a perception map message, null otherwise. Images above [MAX_BYTES] are counted but not kept. */
    fun accept(message: LubaMsg, now: Long, robotState: Int?): PerceptionMapFrame? {
        if (!message.hasPept() || !message.pept.hasPerceptionLocalMapVisualization()) return null
        val bytes = message.pept.perceptionLocalMapVisualization.map
        count++
        val kept = bytes.size() <= MAX_BYTES
        val image = if (kept) bytes.toByteArray() else ByteArray(0)
        val info = if (kept) PerceptionImage.info(image) else PerceptionImage.Info("too large", null, null, bytes.size())
        return PerceptionMapFrame(image, info, now, count, robotState).also { last = it }
    }

    companion object {
        const val MAX_BYTES = 1 shl 20
        /** Images up to this size are written to the log in base64 (`perception_map` event). */
        const val LOG_BYTES = 64 * 1024
    }
}
