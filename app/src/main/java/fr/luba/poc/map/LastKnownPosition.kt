package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import fr.luba.poc.protocol.Telemetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A point is only kept with a recent FIX solution ([Telemetry.rtkFix]). */
data class KnownPoint(val x: Double, val y: Double, val heading: Double?, val at: Long,
    val battery: Int?, val robotState: Int?, val positionType: Int?, val rtkStatus: Int?, val quality: Int)
data class LastKnownPosition(val robotId: String, val robotName: String, val points: List<KnownPoint> = emptyList(),
    val communicationAt: Long? = null, val battery: Int? = null, val robotState: Int? = null) {
    val last get() = points.lastOrNull()
    fun accept(t: Telemetry): LastKnownPosition {
        val at = t.lastPositionAt
        val valid = at != null && at > (last?.at ?: 0L) && t.x?.isFinite() == true && t.y?.isFinite() == true &&
            t.heading?.isFinite() == true && t.rtkFix && t.lastRtkAt?.let { at - it in 0..5000 } == true
        val next = if (valid) {
            val point = KnownPoint(t.x!!, t.y!!, t.heading, at!!, t.battery, t.robotState, t.positionType, t.rtkStatus, 0)
            (points.filter { at - it.at <= 5 * 60_000 }.let { if (it.isNotEmpty() && at / 1000 == it.last().at / 1000) it.dropLast(1) else it } + point).takeLast(301)
        } else points
        return copy(points = next, communicationAt = t.lastReportAt ?: communicationAt,
            battery = t.battery ?: battery, robotState = t.robotState ?: robotState)
    }
}

/** Atomic write and fsync; one file per robot, no network dependency. */
class LastKnownPositionStore(private val directory: File) {
    private fun file(id: String) = File(directory, "last-position-" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } + ".json")
    @Synchronized fun save(value: LastKnownPosition) {
        val json = JSONObject().put("id", value.robotId).put("name", value.robotName)
            .put("communication", value.communicationAt).put("battery", value.battery).put("state", value.robotState)
        val points = JSONArray()
        value.points.forEach { p -> points.put(JSONObject().put("x", p.x).put("y", p.y).put("heading", p.heading)
            .put("at", p.at).put("battery", p.battery).put("state", p.robotState).put("type", p.positionType)
            .put("rtk", p.rtkStatus).put("quality", p.quality)) }
        json.put("points", points)
        val target = file(value.robotId)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
        check(tmp.renameTo(target)) { tr("Failed to save the last position", "Échec sauvegarde dernière position") }
    }
    fun read(id: String): LastKnownPosition? = runCatching {
        val f = file(id); require(f.length() in 1..512_000)
        val j = JSONObject(f.readText()); require(j.getString("id") == id)
        fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
        val a = j.getJSONArray("points"); require(a.length() <= 301)
        val points = (0 until a.length()).map { i -> val p = a.getJSONObject(i)
            KnownPoint(p.getDouble("x"), p.getDouble("y"), if(p.has("heading")) p.getDouble("heading") else null,
                p.getLong("at"), p.int("battery"), p.int("state"), p.int("type"), p.int("rtk"), p.getInt("quality")) }
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.heading?.isFinite() != false && it.at > 0 && it.quality == 0 })
        require(points.zipWithNext().all { (a,b) -> a.at < b.at })
        LastKnownPosition(id,j.getString("name"),points,if(j.has("communication")) j.getLong("communication") else null,j.int("battery"),j.int("state"))
    }.getOrNull()
}
