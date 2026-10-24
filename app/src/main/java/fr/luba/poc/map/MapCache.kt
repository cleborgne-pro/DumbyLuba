package fr.luba.poc.map
import android.content.Context
import fr.luba.poc.i18n.tr
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
class MapCache(private val context:Context) {
    private fun file(id:String):File {val name=MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)};return File(context.filesDir,"map-$name.json")}
    fun save(id:String,map:RobotMap) {
        if(!map.complete)return
        val shapes=JSONArray();map.shapes.forEach { s -> val pts=JSONArray();s.points.forEach{pts.put(JSONArray().put(it.x).put(it.y))};shapes.put(JSONObject().put("id",s.id.toString()).put("type",s.type).put("name",s.name).put("points",pts)) }
        val json=JSONObject().put("savedAt",System.currentTimeMillis()).put("source",map.source).put("shapes",shapes)
        map.station?.let { station -> json.put("station", JSONObject().put("x",station.point.x).put("y",station.point.y)
            .put("headingFrame",2).put("heading",station.entryHeadingDegrees ?: JSONObject.NULL).put("origin",station.origin)) }
        val f=file(id);val tmp=File(f.path+".tmp");tmp.outputStream().use{it.write(json.toString().toByteArray());it.fd.sync()};check(tmp.renameTo(f))
    }
    fun read(id:String):RobotMap? = try {
        val f=file(id);if(!f.exists() || f.length()>16*1024*1024)null else {
            val json=JSONObject(f.readText());val shapes=json.getJSONArray("shapes");require(shapes.length()<=1024)
            val items=(0 until shapes.length()).map {val s=shapes.getJSONObject(it);val pts=s.getJSONArray("points");require(pts.length()<=100000);MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until pts.length()).map{ i ->val p=pts.getJSONArray(i);Point(p.getDouble(0),p.getDouble(1))},s.optString("name"))}
            require(items.all { it.type in ShapeKind.SUPPORTED_TYPES && it.points.all{p->p.x.isFinite()&&p.y.isFinite()} })
            val station=json.optJSONObject("station")?.let { s ->
                val x=s.getDouble("x"); val y=s.getDouble("y")
                val heading=if(s.isNull("heading")) null else s.getDouble("heading")
                require(x.isFinite() && y.isFinite() && (heading==null || heading.isFinite() && heading in 0.0..360.0))
                MapStation(Point(x,y),heading?.let { if(s.optInt("headingFrame",1)<2) MapHeading.migrateDockEntry(it) else it },s.getString("origin"))
            }
            RobotMap(items,true,tr("Local cache · ${java.time.Instant.ofEpochMilli(json.getLong("savedAt"))} · ${json.getString("source")}", "Cache local · ${java.time.Instant.ofEpochMilli(json.getLong("savedAt"))} · ${json.getString("source")}"),station)
        }
    } catch(_:Exception){null}
}
