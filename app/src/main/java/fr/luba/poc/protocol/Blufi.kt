package fr.luba.poc.protocol

import fr.luba.poc.i18n.tr
import java.io.ByteArrayOutputStream

/** BluFi custom data, transport-independent port of the framing described by PyMammotion. */
class Blufi {
    private var tx = -1
    private var rx: Int? = null
    private val pending = ByteArrayOutputStream()
    private var expectedRemaining: Int? = null

    fun encode(payload: ByteArray, mtu: Int): List<ByteArray> = fragment(payload, mtu).map(::forWrite)

    /** Gateway: the next frame sent follows sequence [seq], already written by another sender. */
    fun continueAfter(seq: Int) { tx = seq and 255 }

    /** True while an incomplete fragmented message is being received. */
    val assembling: Boolean get() = pending.size() > 0 || expectedRemaining != null

    /** Assign the sequence only at the actual write, after any STOP purge. */
    fun forWrite(template: ByteArray): ByteArray {
        tx = (tx + 1) and 255
        return template.copyOf().also { it[2] = tx.toByte() }
    }

    fun fragment(payload: ByteArray, mtu: Int): List<ByteArray> {
        require(payload.isNotEmpty() && payload.size <= 65535)
        val capacity = minOf(255, mtu - 7) // ATT 3 + BluFi 4
        require(capacity >= 3)
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val fragmented = remaining > capacity
            val size = if (fragmented) capacity - 2 else remaining
            val prefix = if (fragmented) byteArrayOf(remaining.toByte(), (remaining shr 8).toByte()) else byteArrayOf()
            val data = prefix + payload.copyOfRange(offset, offset + size)
            frames += byteArrayOf(0x4d, if (fragmented) 0x10 else 0, 0, data.size.toByte()) + data
            offset += size
        }
        return frames
    }

    /** Rejects any invalid frame; it must never refresh the watchdog. */
    fun receive(frame: ByteArray): ByteArray? {
        try {
            require(frame.size >= 4) { tr("Truncated BluFi header", "En-tête BluFi tronqué") }
            val type = frame[0].toInt() and 255
            val ctrl = frame[1].toInt() and 255
            val seq = frame[2].toInt() and 255
            val size = frame[3].toInt() and 255
            require(ctrl and 1 == 0) { tr("Encrypted BLE not supported: firmware to examine", "BLE chiffré non pris en charge : firmware à examiner") }
            val checksum = ctrl and 2 != 0
            require(frame.size == 4 + size + if (checksum) 2 else 0) { tr("Invalid BluFi length", "Longueur BluFi invalide") }
            val data = frame.copyOfRange(4, 4 + size)
            if (checksum) {
                val crc = crc(crc(0, byteArrayOf(seq.toByte(), size.toByte())), data)
                val received = ((frame[frame.lastIndex].toInt() and 255) shl 8) or (frame[frame.lastIndex - 1].toInt() and 255)
                require(crc == received) { tr("Invalid BluFi CRC", "CRC BluFi invalide") }
            }
            if (seq == rx) return null
            rx?.let { require(seq == ((it + 1) and 255)) { tr("BluFi sequence lost", "Perte de séquence BluFi") } }
            rx = seq
            if (type != 0x4d) {
                require(pending.size() == 0) { tr("BluFi type changed during fragmentation", "Type BluFi modifié pendant fragmentation") }
                return null
            }
            val fragmented = ctrl and 16 != 0
            if (fragmented) {
                require(data.size >= 3)
                val remaining = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
                expectedRemaining?.let { require(it == remaining) { tr("Inconsistent BluFi fragment", "Fragment BluFi incohérent") } }
                require(remaining > data.size - 2)
                expectedRemaining = remaining - (data.size - 2)
                pending.write(data, 2, data.size - 2)
                require(pending.size() <= 65535)
                return null
            }
            expectedRemaining?.let { require(it == data.size) { tr("Incomplete last fragment", "Dernier fragment incomplet") } }
            pending.write(data)
            val result = pending.toByteArray()
            pending.reset()
            expectedRemaining = null
            return result
        } catch (error: Exception) {
            pending.reset()
            expectedRemaining = null
            throw error
        }
    }

    companion object {
        fun crc(initial: Int, bytes: ByteArray): Int {
            var value = initial.inv() and 65535
            for (byte in bytes) {
                value = value xor ((byte.toInt() and 255) shl 8)
                repeat(8) { value = if (value and 0x8000 != 0) (value shl 1) xor 0x1021 else value shl 1 }
                value = value and 65535
            }
            return value.inv() and 65535
        }
    }
}
