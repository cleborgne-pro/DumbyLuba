package fr.luba.poc.map

import fr.luba.poc.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One archive per route; updates only replace that same route. */
class SignalSurveyStore(private val directory: File) {
    private val archives = File(directory,"signal-surveys")
    private fun latest(id: String) = File(directory,"signal-tour-${id.replace(Regex("[^a-zA-Z0-9]"),"_")}.json")
    private fun archive(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]+"))) { tr("Invalid survey identifier", "Identifiant de relevé invalide") }
        return File(archives,"$id.json")
    }
    private fun effectiveId(s: SignalSurvey) = s.sessionId.ifBlank { "legacy-${s.robotId.hashCode()}-${s.samples.firstOrNull()?.at ?: s.startedAt}" }
    private fun encode(s: SignalSurvey): JSONObject = JSONObject().put("id",effectiveId(s)).put("robot",s.robotId)
        .put("startedAt",s.startedAt.takeIf { it>0 } ?: s.samples.firstOrNull()?.at ?: 0)
        .put("simulated",s.simulated).put("live",s.live).put("mode",s.mode.name).put("map",s.mapKey).put("zones",JSONArray(s.zoneIds))
        .put("samples",JSONArray(s.samples.map { v -> JSONObject().put("x",v.point.x).put("y",v.point.y)
            .put("at",v.at).put("wifi",v.wifi ?: JSONObject.NULL).put("ble",v.ble ?: JSONObject.NULL)
            .put("mobile",v.mobile ?: JSONObject.NULL).put("rtk",v.rtkLevel ?: JSONObject.NULL).put("gps",v.satellites ?: JSONObject.NULL)
            .apply { v.heading?.let { put("heading",it) }; v.gravity?.let { put("gravity",JSONArray(listOf(it.x,it.y,it.z))) } } }))
        .apply { s.mapSnapshot?.let { map -> put("shapes",JSONArray(map.shapes.map { shape ->
            JSONObject().put("id",shape.id).put("type",shape.type).put("name",shape.name)
                .put("points",JSONArray(shape.points.map { JSONArray(listOf(it.x,it.y)) })) })) } }
    private fun decode(data: JSONObject, fallbackId: String = ""): SignalSurvey {
        fun JSONObject.number(key: String): Int? = if (isNull(key)) null else getInt(key)
        val values=data.getJSONArray("samples"); val zones=data.getJSONArray("zones")
        val samples=(0 until values.length()).map { i -> val s=values.getJSONObject(i)
            val gravity=s.optJSONArray("gravity")?.takeIf { it.length()==3 }?.let { PhoneGravity(it.getDouble(0),it.getDouble(1),it.getDouble(2)) }
            SignalSample(Point(s.getDouble("x"),s.getDouble("y")),s.getLong("at"),s.number("wifi"),s.number("ble"),s.number("mobile"),s.number("rtk"),s.number("gps"),
                if (s.has("heading") && !s.isNull("heading")) s.getDouble("heading") else null,gravity) }
        val snapshot=data.optJSONArray("shapes")?.let { shapes -> RobotMap((0 until shapes.length()).map { i ->
            val shape=shapes.getJSONObject(i);val points=shape.getJSONArray("points")
            MapShape(shape.getLong("id"),shape.getInt("type"),(0 until points.length()).map { j ->
                val p=points.getJSONArray(j);Point(p.getDouble(0),p.getDouble(1)) },shape.optString("name")) },true,tr("Map of the archived survey", "Carte du relevé archivé")) }
        val survey=SignalSurvey(data.optString("robot",fallbackId),data.getString("map"),(0 until zones.length()).map { zones.getLong(it) },samples,
            data.optString("id"),data.optLong("startedAt",samples.firstOrNull()?.at ?: 0),data.optBoolean("simulated"),
            runCatching { CoverageMode.valueOf(data.optString("mode","TOUR")) }.getOrDefault(CoverageMode.TOUR),snapshot,
            live=data.optBoolean("live"))
        return survey.copy(sessionId=effectiveId(survey))
    }
    private fun readFile(file: File, fallback: String = ""): SignalSurvey? = runCatching { decode(JSONObject(file.readText()),fallback) }.getOrNull()
    @Synchronized fun read(id: String): SignalSurvey? = readFile(latest(id),id) ?: readFile(File(latest(id).path+".bak"),id)
    @Synchronized fun load(id: String): SignalSurvey? = readFile(archive(id)) ?: history().firstOrNull { it.sessionId==id }
    @Synchronized fun history(): List<SignalSurvey> {
        val saved=archives.listFiles().orEmpty().filter { it.extension=="json" }.mapNotNull { readFile(it) }
        // Compatibility with the last survey saved by previous versions.
        val legacy=directory.listFiles().orEmpty().filter { it.name.startsWith("signal-tour-") && it.extension=="json" }
            .mapNotNull { readFile(it,it.name.removePrefix("signal-tour-").removeSuffix(".json").replace('_',':')) }
        return (saved+legacy).filter { it.samples.isNotEmpty() }.distinctBy { it.sessionId }.sortedByDescending { it.startedAt }
    }
    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val pending=File.createTempFile("signal-", ".tmp",file.parentFile)
        try { pending.writeText(text); Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        finally { pending.delete() }
    }
    /** [latest]: survey shown at the next start (continuous map); false: archive only (dedicated survey). */
    @Synchronized fun save(survey: SignalSurvey, latest: Boolean = true) {
        if (latest) read(survey.robotId)?.takeIf { it.sessionId!=effectiveId(survey) && it.samples.isNotEmpty() }?.let { previous ->
            atomicWrite(archive(effectiveId(previous)),encode(previous).toString())
        }
        val text=encode(survey).toString()
        atomicWrite(archive(effectiveId(survey)),text)
        if (latest) atomicWrite(latest(survey.robotId),text)
    }
    /** Keeps only the [keep] most recent versions of the continuous map; dedicated surveys stay. */
    @Synchronized fun pruneLive(keep: Int = SignalLiveMap.KEEP_VERSIONS): Int {
        val files=archives.listFiles().orEmpty().filter { it.extension=="json" }
        val expired=SignalLiveMap.expired(files.map { it.nameWithoutExtension },keep).toSet()
        return files.count { it.nameWithoutExtension in expired && it.delete() }
    }
}
