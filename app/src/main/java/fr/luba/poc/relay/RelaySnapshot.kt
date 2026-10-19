package fr.luba.poc.relay

import fr.luba.poc.ble.RobotUi
import fr.luba.poc.simulation.MapBase
import org.json.JSONArray
import org.json.JSONObject

/** All public domain fields, without account credentials, Android context or logs on disk. */
object RelaySnapshot {
    fun json(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String, is Boolean -> value
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value else JSONObject.NULL
        is Number -> value
        is Enum<*> -> value.name
        is Iterable<*> -> JSONArray().also { out -> value.forEach { out.put(json(it)) } }
        is Map<*, *> -> JSONObject().also { out -> value.forEach { (k, v) -> out.put(k.toString(), json(v)) } }
        else -> {
            require(value.javaClass.name.startsWith("fr.luba.poc.")) { "Objet hors domaine" }
            JSONObject().also { out -> value.javaClass.methods.filter {
                it.parameterCount == 0 && it.name.startsWith("get") && it.name != "getClass" && it.declaringClass != Any::class.java
            }.forEach { getter -> out.put(getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }, json(getter.invoke(value))) } }
        }
    }
    fun capture(state: RobotUi, base: MapBase?, relayId: String, sequence: Long, now: Long): JSONObject = JSONObject()
        .put("version", 1).put("relayId", relayId).put("sequence", sequence).put("capturedAt", now)
        .put("robotId", state.selected?.address ?: state.lastKnown?.robotId ?: JSONObject.NULL)
        .put("state", JSONObject().put("connection", state.connection.label).put("status", state.status).put("ready", state.ready)
            .put("ageMs", state.ageMs ?: JSONObject.NULL).put("selected", state.selected?.address ?: JSONObject.NULL)
            .put("mapLoading", state.mapLoading).put("telemetry", json(state.telemetry))
            .put("position", RelayMapJson.position(state, now)).put("details", json(state)))
        .put("map", RelayMapJson.map(state.robotMap, base))
}
