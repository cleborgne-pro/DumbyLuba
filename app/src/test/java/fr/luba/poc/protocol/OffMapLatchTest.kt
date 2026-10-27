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

    @Test fun noGoZoneCountsAsAnEdgeOfTheTaskArea() {
        val noGo = MapShape(3, 1, listOf(Point(8.0, 8.0), Point(12.0, 8.0), Point(12.0, 12.0), Point(8.0, 12.0)))
        val withNoGo = map.copy(shapes = map.shapes + noGo)
        assertTrue("centre inside the no-go", PointPlanner.atExclusion(withNoGo, Point(10.0, 10.0)))
        assertTrue("centre 18 cm from it, rear of the chassis inside", PointPlanner.atExclusion(withNoGo, Point(12.18, 10.0)))
        assertFalse(PointPlanner.atExclusion(withNoGo, Point(12.5, 10.0)))
        assertFalse("no no-go zone", PointPlanner.atExclusion(map, Point(10.0, 10.0)))
        assertFalse("incomplete map", PointPlanner.atExclusion(withNoGo.copy(complete = false), Point(10.0, 10.0)))
        assertFalse(PointPlanner.atExclusion(withNoGo, null))
        // The move to a point keeps beyondMapped alone: a no-go zone is not beyond the map.
        assertFalse(PointPlanner.beyondMapped(withNoGo, Point(12.18, 10.0)))
        assertTrue(OffMapLatch.atEdge(withNoGo, Point(12.18, 10.0)))
        assertTrue(OffMapLatch.atEdge(withNoGo, Point(19.8, 10.0)))
        assertFalse(OffMapLatch.atEdge(withNoGo, Point(15.0, 15.0)))
    }

    /**
     * 2026-10-25, 15:33:37 (Fold log): pivot at a corner of the Aire de jeux border pass, self-check 25 without a contact
     * code. On the real map, the robot stood 18 cm from a no-go zone, right behind it: back-off impossible, joystick
     * refused, robot pushed by hand.
     */
    @Test fun fieldCaseOfTheBorderPivotAgainstANoGoZone() {
        val json = org.json.JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val real = RobotMap(shapes, true, "BLE")
        val stuck = Point(-18.8601, 12.9687)
        assertTrue(PointPlanner.atExclusion(real, stuck))
        assertFalse("inside the zone: not beyond the map", PointPlanner.beyondMapped(real, stuck))
        assertTrue(OffMapLatch.ignored(25L, 0, OffMapLatch.atEdge(real, stuck)))
        // Swing poles (registered obstacles, not no-go zones): 25 there stays the collision lock.
        assertFalse(OffMapLatch.atEdge(real, Point(-8.8, 9.34)))
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
