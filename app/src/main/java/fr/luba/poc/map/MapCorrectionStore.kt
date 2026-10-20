package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Format of the local corrections (`map-corrections.json`): `{schemaVersion: 1, corrections: [...]}` in application
 * order. An unreadable entry is ignored without losing the others. Types: `patch`, `offset`, `nogo`, `zone` and
 * `passage` (DumbyLuba zone and passage, 1.4.0; an older version ignores them).
 */
object MapCorrectionJson {
    const val SCHEMA_VERSION = 1
    const val MAX_CORRECTIONS = 500

    fun encode(corrections: List<MapCorrection>): String {
        val items = JSONArray()
        corrections.forEach { items.put(entry(it)) }
        return JSONObject().put("schemaVersion", SCHEMA_VERSION).put("corrections", items).toString()
    }

    fun decode(text: String?): List<MapCorrection> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        if (root.optInt("schemaVersion", 0) > SCHEMA_VERSION) return emptyList()
        val items = root.optJSONArray("corrections") ?: return emptyList()
        val out = (0 until minOf(items.length(), MAX_CORRECTIONS)).mapNotNull { i ->
            runCatching { correction(items.getJSONObject(i)) }.getOrNull()
        }
        // Unique identifiers: a duplicated entry is discarded.
        return out.distinctBy { it.id }
    }

    private fun entry(c: MapCorrection): JSONObject {
        val o = JSONObject().put("id", c.id).put("createdAtMs", c.createdAtMs).put("enabled", c.enabled).put("label", c.label)
        when (c) {
            is ShapePatch -> {
                o.put("type", "patch").put("target", c.target.toString()).put("add", c.add).put("cut", c.cut)
                    .put("tool", c.tool.name).put("removing", c.removing)
                c.baseFingerprint?.let { o.put("baseFingerprint", it) }
                c.primitive?.let { o.put("primitive", primitive(it)) }
            }
            is ShapeOffset -> {
                o.put("type", "offset").put("target", c.target.toString()).put("distanceM", c.distanceM)
                c.baseFingerprint?.let { o.put("baseFingerprint", it) }
            }
            is AddedNoGo -> {
                o.put("type", "nogo").put("origin", c.origin.name).put("points", points(c.points))
                c.primitive?.let { o.put("primitive", primitive(it)) }
            }
            is AddedZone -> o.put("type", "zone").put("name", c.name).put("points", points(c.points))
            is AddedPassage -> o.put("type", "passage").put("points", points(c.points))
        }
        return o
    }

    private fun correction(o: JSONObject): MapCorrection {
        val id = o.getLong("id")
        require(id > 0)
        val created = o.optLong("createdAtMs", 0L)
        val enabled = o.optBoolean("enabled", true)
        val label = o.optString("label", "Correction")
        val fingerprint = if (o.has("baseFingerprint")) o.getLong("baseFingerprint") else null
        return when (o.getString("type")) {
            "patch" -> ShapePatch(id, created, label, o.getString("target").toLong(), fingerprint, o.optString("add", ""),
                o.optString("cut", ""), CorrectionTool.valueOf(o.getString("tool")),
                o.optJSONObject("primitive")?.let(::primitive), o.optBoolean("removing", false), enabled).also {
                // Readable geometries required as soon as they are read.
                MapCorrections.area(it.add); MapCorrections.area(it.cut)
            }
            "offset" -> ShapeOffset(id, created, label, o.getString("target").toLong(), fingerprint, o.getDouble("distanceM"), enabled)
            "nogo" -> AddedNoGo(id, created, label, points(o.getJSONArray("points")), NoGoOrigin.valueOf(o.getString("origin")),
                o.optJSONObject("primitive")?.let(::primitive), enabled).also { require(it.points.size >= 3) }
            "zone" -> AddedZone(id, created, label, o.optString("name", label), points(o.getJSONArray("points")), enabled)
                .also { require(it.points.size >= 3) }
            "passage" -> AddedPassage(id, created, label, points(o.getJSONArray("points")), enabled).also { require(it.points.size >= 2) }
            else -> error(tr("Unknown type", "Type inconnu"))
        }
    }

    private fun points(list: List<Point>) = JSONArray().also { a -> list.forEach { a.put(JSONArray().put(it.x).put(it.y)) } }

    private fun points(a: JSONArray): List<Point> = (0 until a.length()).map { i ->
        val p = a.getJSONArray(i)
        Point(p.getDouble(0), p.getDouble(1)).also { require(it.x.isFinite() && it.y.isFinite()) }
    }

    private fun primitive(p: PrimitiveShape) = JSONObject().put("kind", p.kind.name).put("x", p.center.x).put("y", p.center.y)
        .put("width", p.widthM).put("height", p.heightM).put("rotation", p.rotationDeg)

    private fun primitive(o: JSONObject) = PrimitiveShape(PrimitiveKind.valueOf(o.getString("kind")),
        Point(o.getDouble("x"), o.getDouble("y")), o.getDouble("width"), o.getDouble("height"), o.optDouble("rotation", 0.0))
}

/**
 * Local corrections saved on the phone (`filesDir/map-corrections.json`), atomic write. They are not attached to a
 * robot: each one targets a shape by its Mammotion identifier, the same over Bluetooth and through the account, and has
 * no effect on a map that does not contain it.
 */
class MapCorrectionStore(private val dir: File) {
    private val file get() = File(dir, "map-corrections.json")

    fun load(): List<MapCorrection> = runCatching {
        val f = file
        if (!f.exists() || f.length() > 8 * 1024 * 1024) emptyList() else MapCorrectionJson.decode(f.readText())
    }.getOrDefault(emptyList())

    fun save(corrections: List<MapCorrection>) {
        val f = file
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { it.write(MapCorrectionJson.encode(corrections).toByteArray()); it.fd.sync() }
        check(tmp.renameTo(f)) { tr("Could not write the corrections", "Écriture des corrections impossible") }
    }
}
