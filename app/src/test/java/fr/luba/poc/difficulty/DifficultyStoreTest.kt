package fr.luba.poc.difficulty

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Difficulty log in `difficulties.json`: reload after a restart, missing, corrupted or old file. */
class DifficultyStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_760_000_000_000L
    private fun event(id: String, at: Long = now, kind: DifficultyKind = DifficultyKind.CONTACT) =
        DifficultyEvent(id, at, kind, 2.0, 3.0, robotId = "AA:BB", code = 1014, note = "contact", headingDeg = 45.0,
            obstacleX = 2.4, obstacleY = 3.2, contactAtMs = at - 500, source = "tonte", side = 1, inner = false)

    @Test fun `log reloaded identically after a restart, without temporary file`() {
        val dir = folder.newFolder()
        assertEquals(emptyList<DifficultyEvent>(), DifficultyStore(dir).load())
        val events = listOf(event("a"), event("b", now + 1000, DifficultyKind.SLIP).copy(obstacleX = null, obstacleY = null, dismissed = true))
        DifficultyStore(dir).save(events)
        assertEquals(events, DifficultyStore(dir).load())
        assertEquals(listOf("difficulties.json"), dir.list()!!.toList())
        // New save: replaces the previous one.
        DifficultyStore(dir).save(events.take(1))
        assertEquals(events.take(1), DifficultyStore(dir).load())
    }

    @Test fun `corrupted file, unknown version or too large, empty log without exception`() {
        val dir = folder.newFolder()
        val file = File(dir, "difficulties.json")
        for (text in listOf("", "{", "[]", "{\"schemaVersion\":2,\"events\":[]}", "{\"schemaVersion\":1}")) {
            file.writeText(text)
            assertEquals(text, emptyList<DifficultyEvent>(), DifficultyStore(dir).load())
        }
        file.writeText(DifficultyJson.encode(listOf(event("a"))) + " ".repeat(8 * 1024 * 1024))
        assertEquals(emptyList<DifficultyEvent>(), DifficultyStore(dir).load())
    }

    @Test fun `invalid entries skipped one by one, missing optional fields tolerated`() {
        val valid = JSONObject().put("id", "v").put("atMs", now).put("kind", "lift").put("x", 1.0).put("y", 2.0)
        val text = JSONObject().put("schemaVersion", 1).put("events", JSONArray()
            .put(valid)
            .put(JSONObject(valid.toString()).put("id", "sans-x").apply { remove("x") })
            .put(JSONObject(valid.toString()).put("id", "inconnu").put("kind", "tempete"))
            .put(JSONObject(valid.toString()).put("id", "")) // empty id
            .put(JSONObject(valid.toString()).put("id", "demi").put("obstacleX", 1.0)) // incomplete obstacle centre
            .put(JSONObject(valid.toString()).put("id", "note").put("note", "n".repeat(400)).put("atMs", now - 1))
            .put("pas un objet")).toString()
        val decoded = DifficultyJson.decode(text)
        assertEquals(listOf("note", "v"), decoded.map { it.id })
        assertEquals(300, decoded.first().note.length)
        assertEquals(DifficultyEvent("v", now, DifficultyKind.LIFT, 1.0, 2.0), decoded.last())
    }

    @Test fun `log bounded to the most recent events`() {
        val dir = folder.newFolder()
        val events = (0 until DifficultyLog.MAX_EVENTS + 5).map { event("e$it", now + it) }
        DifficultyStore(dir).save(events)
        val loaded = DifficultyStore(dir).load()
        assertEquals(DifficultyLog.MAX_EVENTS, loaded.size)
        assertEquals("e5", loaded.first().id)
    }
}
