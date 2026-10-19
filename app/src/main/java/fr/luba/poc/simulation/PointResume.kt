package fr.luba.poc.simulation

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Move to a point in progress or interrupted, kept on disk to resume it after a stop, a link loss or an app restart
 * (2026-10-16): the destination is enough, the route is recomputed from the actual robot position with the usual
 * checks. [interruption] null during the move: if the app is closed before the end, the save stays without a reason
 * ("app closed").
 */
data class PointResume(val robotId: String, val target: Point, val startedAt: Long, val interruption: String? = null) {
    init { require(robotId.isNotBlank() && target.x.isFinite() && target.y.isFinite()) }

    companion object {
        /**
         * Save after the end of a move: deleted if it succeeded ([outcome] PASS), otherwise kept with its reason, user
         * STOP included (like a stopped mowing, the user resumes or abandons it).
         */
        fun after(current: PointResume?, outcome: String, reason: String): PointResume? =
            if (current == null || outcome == "PASS") null else current.copy(interruption = reason)
    }
}

/** One file per robot, atomic write; unreadable ⇒ ignored. */
class PointResumeStore(private val directory: File) {
    private fun file(id: String) = File(directory, "point-resume-" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")

    @Synchronized fun save(value: PointResume) {
        val json = JSONObject().put("version", 1).put("robotId", value.robotId).put("x", value.target.x).put("y", value.target.y)
            .put("startedAt", value.startedAt).put("interruption", value.interruption ?: JSONObject.NULL)
        val target = file(value.robotId)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(target)) { tr("Failed to save the move to a point", "Échec sauvegarde du déplacement au point") }
    }

    fun read(id: String): PointResume? = runCatching {
        val f = file(id); require(f.length() in 1..100_000)
        val j = JSONObject(f.readText())
        require(j.getInt("version") == 1 && j.getString("robotId") == id)
        PointResume(id, Point(j.getDouble("x"), j.getDouble("y")), j.getLong("startedAt"),
            if (j.isNull("interruption")) null else j.getString("interruption"))
    }.getOrNull()

    @Synchronized fun delete(id: String) { file(id).delete() }
}
