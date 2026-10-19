package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MapHeadingTest {
    @Test fun compassCardinalsMapToCartesianAndDockApproachFacesRobotFront() {
        for((compass,map) in listOf(0.0 to 90.0,90.0 to 0.0,180.0 to 270.0,270.0 to 180.0)) {
            assertEquals(map,MapHeading.fromCompass(compass),1e-9)
            val entry=Math.toRadians(MapHeading.dockEntry(compass))
            val front=Math.toRadians(map)
            assertEquals(cos(front),-cos(entry),1e-9)
            assertEquals(sin(front),-sin(entry),1e-9)
        }
    }
    @Test fun legacyCacheAndLiveHeadingUseSameCorrectedAxis() {
        for(compass in listOf(-20.0,0.0,90.0,116.9688,180.0,270.0,360.0,450.0)) {
            val old=MapHeading.normalize(compass+180.0)
            assertEquals(MapHeading.dockEntry(compass),MapHeading.migrateDockEntry(old),1e-9)
        }
        assertEquals(153.0312,MapHeading.migrateDockEntry(296.9688),1e-9)
    }
}
