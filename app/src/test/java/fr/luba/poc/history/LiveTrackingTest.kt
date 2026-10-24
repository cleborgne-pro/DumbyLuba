package fr.luba.poc.history

import fr.luba.poc.map.Point
import fr.luba.poc.simulation.RobotRouteUi
import org.junit.Assert.*
import org.junit.Test

class LiveTrackingTest {
    private val route = RobotRouteUi(running = true, index = 2, count = 3,
        planned = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 5.0), Point(0.0, 5.0)))

    @Test fun deviationIsMeasuredFromTheEdgeBeingDriven() {
        // Edge 2: (10,0) → (10,5), heading north; the robot 4 cm east of it is on the right.
        val t = requireNotNull(LiveTracking.of(route, 10.04, 2.0))
        assertEquals(-0.04, t.lateralM, 1e-9)
        assertEquals(Point(10.0, 5.0), t.target)
        assertEquals(2, t.targetNumber); assertEquals(3, t.count)
        assertEquals(3.0, t.distanceToTargetM, 1e-3)
        val text = LiveTracking.text(t)
        assertTrue(text, text.contains("4") && text.contains("2/3"))
        assertTrue(requireNotNull(LiveTracking.of(route, 9.9, 2.0)).lateralM > 0)
    }

    @Test fun nothingWithoutPositionOrBeyondTheRoute() {
        assertNull(LiveTracking.of(route, null, 2.0))
        assertNull(LiveTracking.of(route.copy(index = 4), 1.0, 5.0))
        assertNotNull(LiveTracking.of(route.copy(index = 0), 1.0, 0.0))
    }
}
