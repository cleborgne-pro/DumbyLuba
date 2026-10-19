package fr.luba.poc.relay

import fr.luba.poc.ble.RobotUi
import fr.luba.poc.ble.SeenRobot
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayMapJsonTest {
    @Test fun shapesAndStationKeepLocalCoordinatesAndExactIds() {
        val map = RobotMap(listOf(MapShape(Long.MAX_VALUE, 1, listOf(Point(-5.0, 7.0)))), true, "BLE", MapStation(Point(3.0, -2.0), null, "Robot"))
        val json = RelayMapJson.map(map, MapBase(Point(99.0, 99.0), "Sauvegardée"))
        assertEquals(Long.MAX_VALUE.toString(), json.getJSONArray("shapes").getJSONObject(0).getString("id"))
        assertEquals(-5.0, json.getJSONArray("shapes").getJSONObject(0).getJSONArray("points").getJSONArray(0).getDouble(0), 0.0)
        assertEquals(3.0, json.getJSONObject("base").getDouble("x"), 0.0)
        assertTrue(json.getBoolean("complete"))
    }
    @Test fun missingPositionsAreNullAndInvalidGeometryIsExcluded() {
        assertEquals(JSONObject.NULL, RelayMapJson.position(RobotUi(), 10000))
        val json = RelayMapJson.map(RobotMap(listOf(MapShape(1,0,listOf(Point(Double.NaN,0.0))))))
        assertEquals(0, json.getJSONArray("shapes").length())
        assertTrue(json.isNull("base"))
    }
    @Test fun robotPositionIncludesFreshnessAndHeading() {
        val t = Telemetry(x=2.0,y=-3.0,heading=90.0,lastPositionAt=9000)
        val live = RelayMapJson.position(RobotUi(telemetry=t), 10000) as JSONObject
        assertTrue(live.getBoolean("fresh"))
        assertEquals(90.0,live.getDouble("heading"),0.0)
        val old = RelayMapJson.position(RobotUi(telemetry=t), 20000) as JSONObject
        assertFalse(old.getBoolean("fresh"))
        assertEquals(11000L,old.getLong("ageMs"))
    }
    @Test fun savedBaseAndLastFixAreShownWithoutInventingLivePosition() {
        val known = LastKnownPosition("A", "Luba", listOf(KnownPoint(7.0,8.0,0.0,5000,null,null,null,null,0)))
        val position = RelayMapJson.position(RobotUi(lastKnown=known),10000) as JSONObject
        assertFalse(position.getBoolean("fresh"))
        assertEquals("Dernier FIX",position.getString("source"))
        assertEquals(JSONObject.NULL,RelayMapJson.position(RobotUi(lastKnown=known,selected=SeenRobot("Other","B",null)),10000))
        assertEquals("Manuelle", RelayMapJson.map(RobotMap(),MapBase(Point(1.0,2.0),"Manuelle")).getJSONObject("base").getString("source"))
    }
}
