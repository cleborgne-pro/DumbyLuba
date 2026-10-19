package fr.luba.poc.obstacles

import fr.luba.poc.i18n.tr
import org.json.JSONArray
import org.json.JSONObject

/** JSON encoding of local obstacles: app-private file, never a Mammotion no-go zone. */
object ObstacleJson {
    const val SCHEMA_VERSION = 1
    const val MAX_OBSTACLES = 512

    fun encode(obstacles: List<LocalObstacle>): String {
        require(obstacles.size <= MAX_OBSTACLES) { tr("Too many local obstacles", "Trop d'obstacles locaux") }
        val array = JSONArray()
        obstacles.forEach { array.put(encode(it)) }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("obstacles", array).toString()
    }

    fun decode(text: String): List<LocalObstacle> {
        val json = JSONObject(text)
        require(json.getInt("schemaVersion") == SCHEMA_VERSION) { tr("Unknown obstacle storage version", "Version de stockage obstacles inconnue") }
        val array = json.getJSONArray("obstacles")
        require(array.length() <= MAX_OBSTACLES) { tr("Too many local obstacles", "Trop d'obstacles locaux") }
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }

    private fun encode(obstacle: LocalObstacle): JSONObject = JSONObject()
        .put("id", obstacle.id)
        .put("kind", obstacle.kind.name)
        .put("x", obstacle.x)
        .put("y", obstacle.y)
        .put("radiusM", obstacle.radiusM)
        .put("marginM", obstacle.marginM)
        .put("createdAtMs", obstacle.createdAtMs)
        .put("updatedAtMs", obstacle.updatedAtMs)
        .put("label", obstacle.label)
        .put("incidents", JSONArray().also { array ->
            obstacle.incidents.forEach { incident ->
                array.put(
                    JSONObject()
                        .put("atMs", incident.atMs)
                        .put("kind", incident.kind)
                        .put("note", incident.note)
                        .put("x", incident.x ?: JSONObject.NULL)
                        .put("y", incident.y ?: JSONObject.NULL),
                )
            }
        })

    private fun decode(json: JSONObject): LocalObstacle {
        val items = json.optJSONArray("incidents") ?: JSONArray()
        require(items.length() <= LocalObstacle.MAX_INCIDENTS) { tr("Too many incidents for one obstacle", "Trop d'incidents pour un obstacle") }
        val incidents = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            ObstacleIncident(
                atMs = item.getLong("atMs"),
                kind = item.getString("kind"),
                note = item.optString("note"),
                x = if (item.isNull("x")) null else item.getDouble("x"),
                y = if (item.isNull("y")) null else item.getDouble("y"),
            )
        }
        return LocalObstacle(
            id = json.getString("id"),
            kind = ObstacleKind.from(json.getString("kind")),
            x = json.getDouble("x"),
            y = json.getDouble("y"),
            radiusM = json.getDouble("radiusM"),
            marginM = json.getDouble("marginM"),
            incidents = incidents,
            createdAtMs = json.optLong("createdAtMs"),
            updatedAtMs = json.optLong("updatedAtMs"),
            label = json.optString("label"),
        )
    }
}
