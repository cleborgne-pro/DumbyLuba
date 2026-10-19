package fr.luba.poc.diagnostics

import fr.luba.poc.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal ZMTP 3.0 client (NULL mechanism, no dependency) to listen to the robot's DDS → ZMQ debug bridge
 * (`todev_set_dds2zmq`, port 5555). The robot's socket type is undocumented: the handshake announces
 * [socketType] and returns the peer's (`Socket-Type` of its READY), to reconnect with the compatible type.
 */
object Zmtp {
    /** Local socket type compatible with the one announced by the peer. */
    fun compatible(peer: String): String = when (peer.uppercase()) {
        "PUB", "XPUB" -> "SUB"
        "PUSH" -> "PULL"
        "PAIR" -> "PAIR"
        "ROUTER" -> "DEALER"
        "DEALER", "REP" -> "DEALER"
        "STREAM" -> "STREAM"
        else -> "SUB"
    }

    /** 64-byte greeting: signature, version 3.0, NULL mechanism, client. */
    fun greeting(): ByteArray = ByteArray(64).also {
        it[0] = 0xFF.toByte(); it[9] = 0x7F; it[10] = 3; it[11] = 0
        "NULL".toByteArray().copyInto(it, 12)
    }

    fun readyCommand(socketType: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(5); body.write("READY".toByteArray())
        property(body, "Socket-Type", socketType.toByteArray())
        if (socketType == "DEALER" || socketType == "REQ") property(body, "Identity", ByteArray(0))
        return frame(body.toByteArray(), command = true)
    }

    private fun property(out: ByteArrayOutputStream, name: String, value: ByteArray) {
        out.write(name.length); out.write(name.toByteArray())
        out.write(value.size ushr 24); out.write(value.size ushr 16); out.write(value.size ushr 8); out.write(value.size)
        out.write(value)
    }

    /** Subscription to all topics (ZMTP 3.0: message whose first byte is 1). */
    fun subscribeAll(): ByteArray = frame(byteArrayOf(1))

    fun frame(body: ByteArray, command: Boolean = false, more: Boolean = false): ByteArray {
        val long = body.size > 255
        val out = ByteArrayOutputStream()
        out.write((if (more) 1 else 0) or (if (long) 2 else 0) or (if (command) 4 else 0))
        if (long) for (shift in 56 downTo 0 step 8) out.write(((body.size.toLong() ushr shift) and 0xFF).toInt())
        else out.write(body.size)
        out.write(body)
        return out.toByteArray()
    }

    class Frame(val more: Boolean, val command: Boolean, val body: ByteArray)

    /** Reads a frame; [maxSize] guards against an absurd size (stream that is not ZMTP). */
    fun readFrame(input: DataInputStream, maxSize: Long = 16L shl 20): Frame {
        val flags = input.readUnsignedByte()
        if (flags and 0xF8 != 0) throw IOException(tr("Invalid ZMTP frame (flags 0x%02x)", "Trame ZMTP invalide (drapeaux 0x%02x)").format(flags))
        val size = if (flags and 2 != 0) input.readLong() else input.readUnsignedByte().toLong()
        if (size < 0 || size > maxSize) throw IOException(tr("ZMTP frame too large ($size bytes)", "Trame ZMTP trop grande ($size octets)"))
        val body = ByteArray(size.toInt()).also { input.readFully(it) }
        return Frame(flags and 1 != 0, flags and 4 != 0, body)
    }

    /** Properties of a READY (name → text value). */
    fun readyProperties(body: ByteArray): Map<String, String> {
        val nameLength = body[0].toInt() and 0xFF
        if (String(body, 1, nameLength) != "READY") return emptyMap()
        val properties = LinkedHashMap<String, String>()
        var i = 1 + nameLength
        while (i < body.size) {
            val n = body[i].toInt() and 0xFF
            val name = String(body, i + 1, n); i += 1 + n
            val size = ((body[i].toInt() and 0xFF) shl 24) or ((body[i + 1].toInt() and 0xFF) shl 16) or
                ((body[i + 2].toInt() and 0xFF) shl 8) or (body[i + 3].toInt() and 0xFF)
            i += 4
            properties[name] = String(body, i, size); i += size
        }
        return properties
    }

    /** Handshake result: peer version, mechanism and properties of its READY (or ERROR command). */
    data class Handshake(val version: String, val mechanism: String, val properties: Map<String, String>, val error: String? = null) {
        val peerSocketType: String get() = properties["Socket-Type"].orEmpty()
    }

    fun handshake(input: InputStream, output: OutputStream, socketType: String): Handshake {
        val data = input as? DataInputStream ?: DataInputStream(input)
        output.write(greeting()); output.flush()
        val peer = ByteArray(64)
        data.readFully(peer, 0, 10)
        if (peer[0] != 0xFF.toByte() || peer[9] != 0x7F.toByte()) throw IOException(tr("Not a ZMTP greeting: ", "Pas une salutation ZMTP : ") + hex(peer, 10))
        data.readFully(peer, 10, 54)
        val mechanism = String(peer, 12, 20).trimEnd('\u0000')
        val version = "${peer[10]}.${peer[11]}"
        if (mechanism != "NULL") return Handshake(version, mechanism, emptyMap(), tr("mechanism $mechanism not supported", "mécanisme $mechanism non pris en charge"))
        output.write(readyCommand(socketType)); output.flush()
        val ready = readFrame(data)
        if (!ready.command) throw IOException(tr("READY expected, message received", "READY attendu, message reçu"))
        val nameLength = ready.body[0].toInt() and 0xFF
        val name = String(ready.body, 1, nameLength)
        if (name == "ERROR") return Handshake(version, mechanism, emptyMap(),
            String(ready.body, 2 + nameLength, (ready.body[1 + nameLength].toInt() and 0xFF)))
        val properties = readyProperties(ready.body)
        if (socketType == "SUB") { output.write(subscribeAll()); output.flush() }
        return Handshake(version, mechanism, properties)
    }

    /** Reads a complete message (all its parts), skipping commands (PING, SUBSCRIBE…). */
    fun readMessage(input: DataInputStream): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        while (true) {
            val frame = readFrame(input)
            if (frame.command) { if (parts.isEmpty()) continue else throw IOException(tr("Command in the middle of a message", "Commande au milieu d'un message")) }
            parts += frame.body
            if (!frame.more) return parts
        }
    }

    fun hex(bytes: ByteArray, limit: Int = bytes.size): String =
        bytes.take(limit).joinToString("") { "%02x".format(it) }

    /** Readable preview of a part: printable text, otherwise hexadecimal. */
    fun preview(bytes: ByteArray, limit: Int = 48): String {
        val printable = bytes.isNotEmpty() && bytes.take(limit).all { it in 0x20..0x7E || it == '\n'.code.toByte() || it == '\t'.code.toByte() }
        return if (printable) "\"" + String(bytes, 0, minOf(limit, bytes.size)) + "\"" + if (bytes.size > limit) "…" else ""
        else hex(bytes, limit) + if (bytes.size > limit) "…" else ""
    }

    /** Normal end of stream (connection closed by the robot). */
    fun isEnd(error: Throwable) = error is EOFException
}
