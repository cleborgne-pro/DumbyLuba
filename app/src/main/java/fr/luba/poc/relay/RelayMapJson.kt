package fr.luba.poc.relay

import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.RobotPositionDisplay
import fr.luba.poc.simulation.MapBase
import org.json.JSONArray
import org.json.JSONObject

/** Read-only representation, in the same local metre frame as the Android map. */
object RelayMapJson {
    fun map(map: RobotMap, savedBase: MapBase? = null): JSONObject {
        val shapes = JSONArray()
        map.shapes.forEach { shape ->
            if (shape.points.all { it.x.isFinite() && it.y.isFinite() }) {
                val points = JSONArray()
                shape.points.forEach { points.put(JSONArray().put(it.x).put(it.y)) }
                shapes.put(JSONObject().put("id", shape.id.toString()).put("type", shape.type).put("name", shape.name).put("points", points))
            }
        }
        val point = map.station?.point ?: savedBase?.point
        val base = point?.takeIf { it.x.isFinite() && it.y.isFinite() }?.let {
            JSONObject().put("x", it.x).put("y", it.y).put("source", map.station?.origin ?: savedBase?.origin)
        } ?: JSONObject.NULL
        return JSONObject().put("shapes", shapes).put("complete", map.complete).put("source", map.source).put("base", base)
    }

    fun position(state: RobotUi, now: Long): Any {
        val known = state.lastKnown?.takeIf { state.selected == null || state.selected.address == it.robotId }
        val displayed = RobotPositionDisplay.resolve(state.telemetry, known, null, null, now) ?: return JSONObject.NULL
        return JSONObject().put("x", displayed.point.x).put("y", displayed.point.y)
            .put("heading", displayed.heading ?: JSONObject.NULL).put("at", displayed.at)
            .put("ageMs", now - displayed.at).put("fresh", displayed.fresh).put("source", displayed.source)
            .put("quality", displayed.quality ?: JSONObject.NULL)
    }
}
