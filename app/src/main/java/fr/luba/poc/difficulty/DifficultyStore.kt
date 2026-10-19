package fr.luba.poc.difficulty

import fr.luba.poc.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON encoding of the difficulty log (app-private file). */
object DifficultyJson {
    const val SCHEMA_VERSION = 1

    fun encode(events: List<DifficultyEvent>): String {
        val array = JSONArray()
        events.takeLast(DifficultyLog.MAX_EVENTS).forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id); put("atMs", e.atMs); put("kind", e.kind.code); put("x", e.x); put("y", e.y)
                put("robotId", e.robotId); put("note", e.note); put("source", e.source); put("dismissed", e.dismissed)
                e.code?.let { put("code", it) }
                e.headingDeg?.let { put("headingDeg", it) }
                e.obstacleX?.let { put("obstacleX", it) }
                e.obstacleY?.let { put("obstacleY", it) }
                e.contactAtMs?.let { put("contactAtMs", it) }
                e.side?.let { put("side", it) }
                e.inner?.let { put("inner", it) }
            })
        }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("events", array).toString()
    }

    /** Readable events; an invalid entry or one of an unknown type is skipped, not the whole file. */
    fun decode(text: String): List<DifficultyEvent> {
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == SCHEMA_VERSION) { tr("Unknown difficulty log version", "Version du journal des difficultés inconnue") }
        val array = json.getJSONArray("events")
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                fun dbl(key: String) = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
                DifficultyEvent(
                    id = o.getString("id"), atMs = o.getLong("atMs"),
                    kind = requireNotNull(DifficultyKind.of(o.getString("kind"))),
                    x = o.getDouble("x"), y = o.getDouble("y"),
                    robotId = o.optString("robotId", ""),
                    code = if (o.has("code")) o.getInt("code") else null,
                    note = o.optString("note", "").take(300),
                    headingDeg = dbl("headingDeg"), obstacleX = dbl("obstacleX"), obstacleY = dbl("obstacleY"),
                    contactAtMs = if (o.has("contactAtMs")) o.getLong("contactAtMs") else null,
                    source = o.optString("source", ""),
                    side = if (o.has("side")) o.getInt("side") else null,
                    inner = if (o.has("inner")) o.getBoolean("inner") else null,
                    dismissed = o.optBoolean("dismissed", false),
                )
            }.getOrNull()
        }.sortedBy { it.atMs }.takeLast(DifficultyLog.MAX_EVENTS)
    }
}

/** Difficulty log in `filesDir/difficulties.json` (atomic write). */
class DifficultyStore(private val filesDir: File) {
    private fun file(): File = File(filesDir, "difficulties.json")

    fun load(): List<DifficultyEvent> = try {
        val stored = file()
        if (!stored.exists() || stored.length() > 8L * 1024 * 1024) emptyList() else DifficultyJson.decode(stored.readText())
    } catch (_: Exception) {
        emptyList()
    }

    fun save(events: List<DifficultyEvent>) {
        val stored = file()
        val temp = File(stored.path + ".tmp")
        temp.outputStream().use { stream ->
            stream.write(DifficultyJson.encode(events).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temp.renameTo(stored)) { tr("Difficulty log not saved", "Journal des difficultés non enregistré") }
    }
}
