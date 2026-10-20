package fr.luba.poc.protocol

import fr.luba.poc.autonomy.LegSpec
import fr.luba.poc.diagnostics.NavSafety
import fr.luba.poc.map.*
import fr.luba.poc.simulation.PointPlanner
import org.junit.Assert.*
import org.junit.Test

/** 2026-10-20: self-check 25 beyond the mapped zones does not prevent the blade-OFF moves there. */
class OffMapLatchTest {
    private val zone = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 20.0), Point(0.0, 20.0))
    private val map = RobotMap(listOf(MapShape(1, 0, zone)), true, "BLE")

    @Test fun onlySelfCheck25BeyondTheMapWithClearSensorsIsIgnored() {
        assertTrue(OffMapLatch.ignored(25L, 0, beyond = true))
        assertFalse("inside the zones: collision lock", OffMapLatch.ignored(25L, 0, beyond = false))
        assertFalse("bumper pressed", OffMapLatch.ignored(25L, 1, beyond = true))
        assertFalse("sensors unknown", OffMapLatch.ignored(25L, null, beyond = true))
        assertFalse("STOP lock", OffMapLatch.ignored(11L, 0, beyond = true))
        assertFalse("other protection", OffMapLatch.ignored(41L, 0, beyond = true))
        assertTrue(OffMapLatch.movable(10L, 0, beyond = false))
        assertFalse(OffMapLatch.movable(25L, 0, beyond = false))
    }

    @Test fun beyondTheMappedZonesIncludesTheChassisAcrossTheBorder() {
        assertFalse(PointPlanner.beyondMapped(map, Point(10.0, 10.0)))
        assertFalse(PointPlanner.beyondMapped(map, Point(19.5, 10.0)))
        assertTrue("centre 20 cm inside the border", PointPlanner.beyondMapped(map, Point(19.8, 10.0)))
        assertTrue(PointPlanner.beyondMapped(map, Point(24.0, 10.0)))
        assertFalse("no position", PointPlanner.beyondMapped(map, null))
        assertFalse("incomplete map", PointPlanner.beyondMapped(map.copy(complete = false), Point(24.0, 10.0)))
        assertFalse("demo map", PointPlanner.beyondMapped(Geometry.demo(), Point(500.0, 500.0)))
        // A passage counts as mapped.
        val withPassage = map.copy(shapes = map.shapes + MapShape(2, 2, listOf(Point(20.0, 10.0), Point(30.0, 10.0))))
        assertFalse(PointPlanner.beyondMapped(withPassage, Point(25.0, 10.0)))
    }

    @Test fun sectionsTravelToTheGatewayAndAreBounded() {
        val sections = listOf(listOf(Point(20.0, 10.0), Point(24.0, 10.0)))
        val spec = LegSpec(1, 2, 0, cutting = false, shown = true, watchBattery = false, departure = false, lastArrivalM = null,
            heightMm = 60, speedPercent = 50, returnPercent = 20, baseApproach = null, guardId = 3, physicalGuardId = 3,
            calibration = null, obstacles = emptyList(), sampling = false, offMapSections = sections)
        assertEquals(sections, LegSpec.decode(spec.encode()).offMapSections)
        assertEquals(emptyList<List<Point>>(), LegSpec.decode(spec.copy(offMapSections = emptyList()).encode()).offMapSections)
        assertTrue(OffMapLatch.onSections(Point(22.0, 10.8), sections))
        assertFalse(OffMapLatch.onSections(Point(22.0, 11.5), sections))
        val long = (0..500).map { Point(it * 0.1, 0.0) }
        val bounded = LegSpec.boundedSections(List(12) { long })
        assertEquals(LegSpec.MAX_OFF_MAP_SECTIONS, bounded.size)
        assertTrue(bounded.all { it.size <= LegSpec.MAX_OFF_MAP_POINTS && it.first() == long.first() && it.last() == long.last() })
    }

    @Test fun moveStartAcceptsTheLatchOnlyWhenAsked() {
        fun start(ignore: Boolean) = NavSafety.startFailure("Luba-VS", 100, 80, 11, 0, 25L, false, false, false, 60, ignoreOffMapLatch = ignore)
        assertNull(start(true))
        assertNotNull(start(false))
        assertNotNull(NavSafety.startFailure("Luba-VS", 100, 80, 11, 0, 11L, false, false, false, 60, ignoreOffMapLatch = true))
    }
}
