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
        if(map.drawings.isNotEmpty()) json.put("drawings",JSONArray().apply { map.drawings.forEach { put(drawingJson(it)) } })
        map.station?.let { station -> json.put("station", JSONObject().put("x",station.point.x).put("y",station.point.y)
            .put("headingFrame",2).put("heading",station.entryHeadingDegrees ?: JSONObject.NULL).put("origin",station.origin)) }
        val f=file(id);val tmp=File(f.path+".tmp");tmp.outputStream().use{it.write(json.toString().toByteArray());it.fd.sync()};check(tmp.renameTo(f))
    }
    fun read(id:String):RobotMap? = try {
        val f=file(id);if(!f.exists() || f.length()>16*1024*1024)null else {
            val json=JSONObject(f.readText());val shapes=json.getJSONArray("shapes");require(shapes.length()<=1024)
            val items=(0 until shapes.length()).map {val s=shapes.getJSONObject(it);val pts=s.getJSONArray("points");require(pts.length()<=100000);MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until pts.length()).map{ i ->val p=pts.getJSONArray(i);Point(p.getDouble(0),p.getDouble(1))},s.optString("name"))}
            // Any element type is kept (ShapeKind.UNKNOWN for the unknown ones): only the coordinates are checked.
            require(items.all { it.points.all{p->p.x.isFinite()&&p.y.isFinite()} })
            val station=json.optJSONObject("station")?.let { s ->
                val x=s.getDouble("x"); val y=s.getDouble("y")
                val heading=if(s.isNull("heading")) null else s.getDouble("heading")
                require(x.isFinite() && y.isFinite() && (heading==null || heading.isFinite() && heading in 0.0..360.0))
                MapStation(Point(x,y),heading?.let { if(s.optInt("headingFrame",1)<2) MapHeading.migrateDockEntry(it) else it },s.getString("origin"))
            }
            RobotMap(items,true,tr("Local cache · ${java.time.Instant.ofEpochMilli(json.getLong("savedAt"))} · ${json.getString("source")}", "Cache local · ${java.time.Instant.ofEpochMilli(json.getLong("savedAt"))} · ${json.getString("source")}"),station,
                drawings=drawingsOf(json.optJSONArray("drawings")))
        }
    } catch(_:Exception){null}

    companion object {
        /** Drawing ([MapDrawing]) as cached: hashes as strings like the shape ids. */
        internal fun drawingJson(d:MapDrawing):JSONObject = JSONObject().put("hash",d.hash.toString()).put("zone",d.zoneHash.toString())
            .put("x",d.centerX).put("y",d.centerY).put("width",d.widthM).put("height",d.heightM).put("rotation",d.rotationRad)
            .put("hidden",d.hidden).put("name",d.fileName).put("svg",d.svg).put("widthPix",d.basePixelWidth).put("heightPix",d.basePixelHeight)

        /**
         * Drawings of a cache; a cache written before them has none. A drawing with a non-finite or unusable placement,
         * or an SVG over [SvgOutline.MAX_SVG_CHARS], is left out alone: the map is read anyway.
         */
        internal fun drawingsOf(array:JSONArray?):List<MapDrawing> {
            if(array==null) return emptyList()
            return (0 until minOf(array.length(),MapTransfer.MAX_DRAWINGS)).mapNotNull { i -> try {
                val o=array.getJSONObject(i)
                val svg=o.optString("svg")
                require(svg.length<=SvgOutline.MAX_SVG_CHARS)
                MapDrawing(o.getString("hash").toLong(),o.getString("zone").toLong(),o.getDouble("x"),o.getDouble("y"),o.getDouble("width"),
                    o.getDouble("height"),o.getDouble("rotation"),o.optBoolean("hidden"),o.optString("name"),svg,o.optInt("widthPix"),o.optInt("heightPix"))
                    .takeIf { it.valid }
            } catch(_:Exception){null} }
        }
    }
}
