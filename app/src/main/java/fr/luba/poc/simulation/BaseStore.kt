package fr.luba.poc.simulation

import android.content.Context
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import java.security.MessageDigest

data class MapBase(val point:Point,val origin:String,val influenceRadiusM:Double?=DEFAULT_INFLUENCE_RADIUS_M,val entryHeadingDegrees:Double?=null,val approachDistanceM:Double=1.5) {
    companion object { const val DEFAULT_INFLUENCE_RADIUS_M = 1.5 }
    init {
        require(approachDistanceM.isFinite() && approachDistanceM in 1.0..1.5)
        require(influenceRadiusM == null || (influenceRadiusM.isFinite() && influenceRadiusM > 0))
        require(entryHeadingDegrees == null || (entryHeadingDegrees.isFinite() && entryHeadingDegrees in 0.0..360.0))
    }
}
/**
 * Display label of a base origin. Origins are persisted and compared in French ("Transmise par le robot",
 * "Observée en recharge…", "Placée manuellement"…): the stored value is kept and only translated here.
 */
fun baseOriginLabel(origin: String): String = when {
    origin == "Transmise par le robot" -> tr("Sent by the robot", origin)
    origin == "Position et axe observés en recharge" -> tr("Position and axis observed while charging", origin)
    origin == "Placée manuellement" -> tr("Placed manually", origin)
    origin == "Démonstration" -> tr("Demo", origin)
    origin.startsWith("Observée en recharge") -> tr("Observed while charging" + origin.removePrefix("Observée en recharge"), origin)
    else -> origin
}

/** Frame tied to the exact geometry, not shared between maps/robots. */
class BaseStore(context:Context,map:RobotMap) {
    private val prefs=context.getSharedPreferences("map-bases",Context.MODE_PRIVATE)
    private val key=MessageDigest.getInstance("SHA-256").digest(map.originalShapes.joinToString { "${it.id}:${it.type}:${it.points}" }.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun read():MapBase? {
        val value=prefs.getString(key,null)?.split('|') ?: return null
        return try { MapBase(Point(value[0].toDouble(),value[1].toDouble()),value[2],value.getOrNull(3)?.toDoubleOrNull() ?: MapBase.DEFAULT_INFLUENCE_RADIUS_M,value.getOrNull(4)?.toDoubleOrNull()?.let { heading ->
            if(value.getOrNull(6)=="2" || value[2]=="Placée manuellement" || value[2]=="Démonstration") heading
            else if(value[2].contains("recharge")) fr.luba.poc.map.MapHeading.migrateDockEntry(heading)
            else null
        },value.getOrNull(5)?.toDoubleOrNull() ?: 1.5) } catch(_:Exception) { null }
    }
    /** Base of a real map: station sent by the robot, otherwise the base observed while charging. */
    fun resolve(map:RobotMap,saved:MapBase?=read()):MapBase? = map.station?.let { station ->
        MapBase(station.point, station.origin, saved?.influenceRadiusM ?: 1.5,
            station.entryHeadingDegrees, saved?.approachDistanceM ?: 1.5)
    } ?: saved?.takeIf { it.origin == "Transmise par le robot" || it.origin.startsWith("Observée en recharge") ||
        it.origin == "Position et axe observés en recharge" }
    fun accesses():List<List<Point>> = try {
        val array=org.json.JSONArray(prefs.getString("$key-accesses", "[]"))
        (0 until array.length()).map { index ->
            val path=array.getJSONArray(index)
            (0 until path.length()).map { i -> val p=path.getJSONArray(i); Point(p.getDouble(0),p.getDouble(1)) }
        }
    } catch(_:Exception) { emptyList() }
    fun saveAccesses(paths:List<List<Point>>) {
        val array=org.json.JSONArray()
        paths.forEach { path -> val points=org.json.JSONArray(); path.forEach { points.put(org.json.JSONArray().put(it.x).put(it.y)) }; array.put(points) }
        prefs.edit().putString("$key-accesses",array.toString()).apply()
    }
    fun save(base:MapBase) { prefs.edit().putString(key,"${base.point.x}|${base.point.y}|${base.origin}|${base.influenceRadiusM ?: ""}|${base.entryHeadingDegrees ?: ""}|${base.approachDistanceM}|2").apply() }
}
