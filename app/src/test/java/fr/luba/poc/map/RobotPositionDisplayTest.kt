package fr.luba.poc.map

import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.*
import org.junit.Test

class RobotPositionDisplayTest {
    private val now=20000L
    private fun position(at:Long, quality:Int?=0)=Telemetry(x=3.0,y=4.0,heading=90.0,lastPositionAt=at,positioningLevel=quality,rtkStatus=quality?.let { 4 })
    @Test fun freshBleWinsOverCloud() {
        assertEquals("BLE",RobotPositionDisplay.resolve(position(19000),null,position(19500),null,now)!!.source)
    }
    @Test fun cloudPositionVisibleWithoutFixButNeverAddedToReliableHistory() {
        val api=position(19000,null)
        val known=LastKnownPosition("id","Luba").accept(api)
        assertNull(known.last)
        val marker=RobotPositionDisplay.resolve(Telemetry(),null,api,known,now)!!
        assertEquals(Point(3.0,4.0),marker.point)
        assertEquals("API",marker.source)
        assertTrue(marker.fresh)
        assertNull(marker.quality)
    }
    @Test fun staleBleDoesNotHideNewCloudPosition() {
        assertEquals("API",RobotPositionDisplay.resolve(position(10000),null,position(19000),null,now)!!.source)
    }
    @Test fun oldApiPositionIsLabelledOld() {
        assertFalse(RobotPositionDisplay.resolve(Telemetry(),null,position(5000),null,now)!!.fresh)
    }
    @Test fun invalidCoordinatesOrFutureDatesAreNotRendered() {
        assertNull(RobotPositionDisplay.resolve(position(now+10_000),null,position(19000).copy(x=Double.NaN),null,now))
    }
    @Test fun positionNewerThanTheOneSecondDisplayClockStaysOnBle() {
        // Display clock 900 ms behind the last position (1 Hz): no flickering.
        val marker=RobotPositionDisplay.resolve(position(now+900),null,null,null,now)!!
        assertEquals("BLE",marker.source); assertTrue(marker.fresh)
    }
    @Test fun reliableCachedPositionSurvivesMissingReports() {
        val p=KnownPoint(8.0,9.0,0.0,18000,50,11,null,null,0)
        val known=LastKnownPosition("id","Luba",listOf(p))
        val marker=RobotPositionDisplay.resolve(Telemetry(),known,position(10000),null,now)!!
        assertEquals(Point(8.0,9.0),marker.point)
        assertEquals("Dernier FIX",marker.source)
        assertFalse(marker.fresh)
    }
    @Test fun reconnectionKeepsLastBlePositionInsteadOfOldFixAtBase() {
        // Telemetry reset by the gateway reconnection; last FIX is old, on the base.
        val base=KnownPoint(0.1,1.3,0.0,1000,100,11,null,null,0)
        val known=LastKnownPosition("id","Luba",listOf(base))
        val lastBle=RobotPositionDisplay.resolve(position(18500,1),known,null,null,19000)!!
        assertEquals("BLE",lastBle.source)
        val marker=RobotPositionDisplay.resolve(Telemetry(),known,null,null,now,recentBle=lastBle)!!
        assertEquals(Point(3.0,4.0),marker.point)
        assertEquals("Dernière position BLE",marker.source)
        assertFalse(marker.fresh)
    }
    @Test fun newerFixOrCloudPositionStillWinsOverRememberedBle() {
        val lastBle=RobotPositionDisplay.resolve(position(5000),null,null,null,5000)!!
        val known=LastKnownPosition("id","Luba",listOf(KnownPoint(8.0,9.0,0.0,18000,50,11,null,null,0)))
        assertEquals("Dernier FIX",RobotPositionDisplay.resolve(Telemetry(),known,null,null,now,recentBle=lastBle)!!.source)
        assertEquals("API",RobotPositionDisplay.resolve(Telemetry(),null,position(19000),null,now,recentBle=lastBle)!!.source)
    }
    @Test fun freshBleIgnoresRememberedPosition() {
        val old=RobotPositionDisplay.resolve(position(10000),null,null,null,10000)!!
        val marker=RobotPositionDisplay.resolve(position(19500).copy(x=5.0),null,null,null,now,recentBle=old)!!
        assertEquals("BLE",marker.source); assertEquals(5.0,marker.point.x,0.0)
    }
}
