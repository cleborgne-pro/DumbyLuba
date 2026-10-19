package fr.luba.poc.autonomy

import fr.luba.poc.map.SignalSample
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/** Autonomous route tracking frames between the pilot and the gateway (types and payloads; the TCP transport is the gateway's). */
object LegFrames {
    // Pilot → gateway
    const val BLOB_BEGIN = 0x80
    const val BLOB_CHUNK = 0x81
    const val BLOB_END = 0x82
    const val LEG_START = 0x83
    const val LEG_ABORT = 0x84
    const val LEG_OBSTACLES = 0x85
    const val LEG_SYNC = 0x86
    const val LEG_ACK = 0x87
    /** The pilot announces that it understands the autonomous tracking frames: the gateway sends none before that (older pilot). */
    const val LEG_HELLO = 0x88

    // Gateway → pilot
    const val BLOB_OK = 0x90
    const val BLOB_REJECTED = 0x91
    const val LEG_REFUSED = 0x92
    const val LEG_PROGRESS = 0x93
    const val LEG_RESULT = 0x94
    const val LEG_SAMPLE = 0x95
    const val LEG_STATE = 0x96
    const val LEG_EVENT = 0x97

    val PILOT_TO_GATEWAY = setOf(BLOB_BEGIN, BLOB_CHUNK, BLOB_END, LEG_START, LEG_ABORT, LEG_OBSTACLES, LEG_SYNC, LEG_ACK, LEG_HELLO)
    val GATEWAY_TO_PILOT = setOf(BLOB_OK, BLOB_REJECTED, LEG_REFUSED, LEG_PROGRESS, LEG_RESULT, LEG_SAMPLE, LEG_STATE, LEG_EVENT)

    /** Data bytes per BLOB_CHUNK frame (8-byte header included, well below the maximum frame payload). */
    const val CHUNK_BYTES = 3800
    /** Maximum blob size (route of 200,000 edges ≈ 6.6 MB): refused beyond. */
    const val MAX_BLOB_BYTES = 8 * 1024 * 1024

    /** Maximum frame payload: the same as [fr.luba.poc.gateway.GatewayProtocol.MAX_PAYLOAD] (checked by a test). */
    const val MAX_PAYLOAD = 16 * 1024

    /** Value of [LegState.state]. */
    const val STATE_NONE = 0
    const val STATE_RUNNING = 1
    /** Route tracking finished, outcome not yet acknowledged by the pilot. */
    const val STATE_FINISHED = 2

    private fun ints(vararg values: Int): ByteArray = ByteArray(values.size * 4).also { out ->
        values.forEachIndexed { index, value -> for (k in 0..3) out[index * 4 + k] = (value shr (24 - 8 * k)).toByte() }
    }
    fun int(bytes: ByteArray, index: Int = 0): Int = ((bytes[index * 4].toInt() and 255) shl 24) or ((bytes[index * 4 + 1].toInt() and 255) shl 16) or
        ((bytes[index * 4 + 2].toInt() and 255) shl 8) or (bytes[index * 4 + 3].toInt() and 255)

    fun blobBegin(id: Int, kind: Int, size: Int) = ints(id, kind, size)
    class BlobBegin(val id: Int, val kind: Int, val size: Int)
    fun parseBlobBegin(payload: ByteArray) = BlobBegin(int(payload, 0), int(payload, 1), int(payload, 2))

    fun blobChunk(id: Int, offset: Int, data: ByteArray, from: Int, length: Int) = ints(id, offset) + data.copyOfRange(from, from + length)
    class BlobChunk(val id: Int, val offset: Int, val data: ByteArray)
    fun parseBlobChunk(payload: ByteArray) = BlobChunk(int(payload, 0), int(payload, 1), payload.copyOfRange(8, payload.size))

    fun blobEnd(id: Int, data: ByteArray) = ints(id, crc(data))
    fun parseBlobEnd(payload: ByteArray) = int(payload, 0) to int(payload, 1)
    fun crc(data: ByteArray): Int = CRC32().also { it.update(data) }.value.toInt()

    fun blobOk(id: Int) = ints(id)
    fun blobRejected(id: Int, reason: String) = ints(id) + reason.toByteArray(Charsets.UTF_8)
    fun legRefused(legId: Int, reason: String) = ints(legId) + reason.toByteArray(Charsets.UTF_8)
    fun text(payload: ByteArray) = String(payload, 4, payload.size - 4, Charsets.UTF_8)
    fun legAbort(legId: Int) = ints(legId)
    fun legAck(legId: Int) = ints(legId)
    fun legSync(legId: Int, samplesHave: Int) = ints(legId, samplesHave)

    fun obstacles(legId: Int, list: List<LegObstacle>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(legId); out.writeShort(list.size)
            list.forEach { o -> out.writeUTF(o.id); out.writeDouble(o.x); out.writeDouble(o.y); out.writeDouble(o.radiusM); out.writeDouble(o.exclusionRadiusM); out.writeBoolean(o.planned) }
        }
    }.toByteArray()
    fun parseObstacles(payload: ByteArray): Pair<Int, List<LegObstacle>> = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val legId = input.readInt()
        legId to (0 until input.readUnsignedShort()).map { LegObstacle(input.readUTF(), input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(), input.readBoolean()) }
    }

    /** Route tracking state, handed to the pilot when it connects: [state] see STATE_*, [samples] signal survey samples already taken. */
    class LegState(val legId: Int, val state: Int, val samples: Int)
    fun legState(legId: Int, state: Int, samples: Int) = ints(legId, state, samples)
    fun parseLegState(payload: ByteArray) = LegState(int(payload, 0), int(payload, 1), int(payload, 2))

    /** Separator of the fields of a [LEG_EVENT] (after the text). */
    private const val FIELDS_MARK = "\n\u001E"
    /** Fields beyond this size: not sent (the text remains). */
    private const val MAX_FIELDS_BYTES = 8000

    /**
     * Route tracking event: name, text (1000 bytes at most) and fields as JSON. The fields (pose, predicted heading,
     * deviation, position age…) used not to be sent: on 2026-10-14, the gateway's "Écart au trajet >30 cm" stops only had
     * a text in the pilot's log, with no way to analyse them.
     */
    fun legEvent(kind: String, text: String, fields: Map<String, Any?> = emptyMap()): ByteArray {
        val head = "$kind\n$text".toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 1000)) }
        if (fields.isEmpty()) return head
        val json = runCatching { org.json.JSONObject().apply { fields.forEach { (k, v) -> put(k, org.json.JSONObject.wrap(v) ?: org.json.JSONObject.NULL) } }.toString() }
            .getOrNull()?.toByteArray(Charsets.UTF_8)?.takeIf { it.size <= MAX_FIELDS_BYTES } ?: return head
        // Text cut in the middle of a character: decoding replaces it, the separator is still recognised.
        return head + FIELDS_MARK.toByteArray(Charsets.UTF_8) + json
    }

    class LegEvent(val kind: String, val text: String, val fields: Map<String, Any?>)

    fun parseLegEvent(payload: ByteArray): LegEvent {
        val all = payload.toString(Charsets.UTF_8)
        val mark = all.lastIndexOf(FIELDS_MARK)
        val head = if (mark >= 0) all.substring(0, mark) else all
        val fields: Map<String, Any?> = if (mark < 0) emptyMap() else runCatching {
            val json = org.json.JSONObject(all.substring(mark + FIELDS_MARK.length))
            json.keys().asSequence().associateWith { key -> json.get(key).let { if (it == org.json.JSONObject.NULL) null else it } }
        }.getOrDefault(emptyMap())
        val parts = head.split('\n', limit = 2)
        return LegEvent(parts[0], parts.getOrElse(1) { "" }, fields)
    }

    private const val ABSENT = Int.MIN_VALUE
    fun sample(legId: Int, seq: Int, s: SignalSample): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(legId); out.writeInt(seq)
            out.writeDouble(s.point.x); out.writeDouble(s.point.y); out.writeLong(s.at)
            listOf(s.wifi, s.ble, s.mobile, s.rtkLevel, s.satellites).forEach { out.writeInt(it ?: ABSENT) }
            // Heading and tilt of the phone placed on the robot (Slope and Terrain layers).
            out.writeBoolean(s.heading != null); out.writeDouble(s.heading ?: 0.0)
            out.writeBoolean(s.gravity != null); out.writeDouble(s.gravity?.x ?: 0.0); out.writeDouble(s.gravity?.y ?: 0.0); out.writeDouble(s.gravity?.z ?: 0.0)
        }
    }.toByteArray()
    class SampleFrame(val legId: Int, val seq: Int, val sample: SignalSample)
    fun parseSample(payload: ByteArray): SampleFrame = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val legId = input.readInt(); val seq = input.readInt()
        val point = fr.luba.poc.map.Point(input.readDouble(), input.readDouble()); val at = input.readLong()
        fun value() = input.readInt().takeIf { it != ABSENT }
        val wifi = value(); val ble = value(); val mobile = value(); val rtk = value(); val stars = value()
        val hasHeading = input.readBoolean(); val heading = input.readDouble()
        val hasGravity = input.readBoolean(); val gx = input.readDouble(); val gy = input.readDouble(); val gz = input.readDouble()
        SampleFrame(legId, seq, SignalSample(point, at, wifi, ble, mobile, rtk, stars,
            heading.takeIf { hasHeading }, if (hasGravity) fr.luba.poc.map.PhoneGravity(gx, gy, gz) else null))
    }
}
