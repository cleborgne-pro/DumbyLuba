package fr.luba.poc.relay

import fr.luba.poc.i18n.tr

import java.io.File

/** A bounded durable latest snapshot: newer data supersedes older data while offline. */
class RelayOutbox(private val file: File) {
    fun write(payload: String) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { it.write(payload.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(tmp.renameTo(file)) { tr("Could not save the snapshot", "Sauvegarde du snapshot impossible") }
    }
    fun read(): String? = if (file.isFile) file.readText() else null
    fun acknowledge() { check(!file.exists() || file.delete()) { tr("Could not delete the snapshot", "Suppression snapshot impossible") } }
}
