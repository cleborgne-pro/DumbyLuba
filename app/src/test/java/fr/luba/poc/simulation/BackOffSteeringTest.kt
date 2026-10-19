package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackOffSteeringTest {
    private val from = Point(0.0, 0.0)
    private val south = Point(0.0, -0.8)

    @Test fun `offset positive on the left of the travel direction`() {
        // Reversing southwards: the left of the travel direction is the east.
        assertEquals(0.1, BackOffSteering.offset(from, south, Point(0.1, -0.4)), 1e-9)
        assertEquals(-0.1, BackOffSteering.offset(from, south, Point(-0.1, -0.4)), 1e-9)
        assertEquals(0.0, BackOffSteering.offset(from, from, Point(1.0, 1.0)), 1e-9)
    }

    @Test fun `wanted heading tilted so that the reverse goes back to the line`() {
        // Facing north, reversing south, robot 10 cm west of the line: the front turns west (heading > 90),
        // so the rear and the reverse go back east.
        val wanted = BackOffSteering.wanted(90.0, from, south, Point(-0.1, -0.4))
        assertEquals(90.0 + Math.toDegrees(kotlin.math.atan2(0.1, 0.4)), wanted, 1e-9)
        assertEquals(90.0, BackOffSteering.wanted(90.0, from, south, Point(0.0, -0.4)), 1e-9)
        // Tilt bounded far from the line.
        assertEquals(90.0 + BackOffSteering.MAX_TILT_DEGREES, BackOffSteering.wanted(90.0, from, south, Point(-2.0, -0.4)), 1e-9)
        assertEquals(350.0, BackOffSteering.wanted(10.0, Point(0.0, 0.0), Point(-0.8, 0.0), Point(-0.4, -0.5)), 1e-9)
    }

    @Test fun `current heading from the change of the reported heading, across north`() {
        assertEquals(79.185, BackOffSteering.current(90.0, 90.465, 79.65), 1e-9)
        assertEquals(335.0, BackOffSteering.current(355.0, 10.0, 350.0), 1e-9)
        assertEquals(5.0, BackOffSteering.current(355.0, 350.0, 0.0), 1e-9)
        assertEquals(-20.0, BackOffSteering.error(335.0, 355.0), 1e-9)
        assertEquals(20.0, BackOffSteering.error(5.0, 345.0), 1e-9)
    }

    @Test fun `pivot with hysteresis, sign and bounded speed`() {
        assertFalse(BackOffSteering.pivotNeeded(5.0, pivoting = false))
        assertTrue(BackOffSteering.pivotNeeded(5.0, pivoting = true))
        assertTrue(BackOffSteering.pivotNeeded(-9.0, pivoting = false))
        assertFalse(BackOffSteering.pivotNeeded(-2.0, pivoting = true))
        assertEquals(BackOffSteering.MAX_PIVOT, BackOffSteering.pivotAngular(40.0, 0.0996))
        assertEquals(-BackOffSteering.MIN_PIVOT, BackOffSteering.pivotAngular(-3.5, 0.0996))
        assertEquals(50, BackOffSteering.pivotAngular(10.0, 0.1))
    }

    /**
     * 2026-10-19 at 14:02, Fosse: 0.8 m back-off after a contact, heading 90°, zero angular command, the robot yawed to
     * 55° and drifted 14 cm sideways (mowing stopped). With the heading hold, the pivot starts while the robot is still
     * 2 cm from its line, long before the 12 cm limit, and turns the heading back up.
     */
    @Test fun `real back-off of October 19 corrected before the deviation limit`() {
        val start = Point(-40.0335, 2.1202)
        val target = Point(-40.0335, 1.3202)
        val reportedAtStart = 90.465
        val samples = listOf( // measured position and reported heading, 1 Hz
            Triple(-40.0369, 2.0372, 90.465), Triple(-40.0393, 1.8934, 86.2279), Triple(-40.0505, 1.8013, 82.8325),
            Triple(-40.0562, 1.7442, 81.5016), Triple(-40.0621, 1.7137, 79.6535), Triple(-40.1064, 1.583, 72.1438))
        val first = samples.indexOfFirst { (x, y, reported) ->
            val position = Point(x, y)
            val error = BackOffSteering.error(BackOffSteering.wanted(90.0, start, target, position),
                BackOffSteering.current(90.0, reportedAtStart, reported))
            start.distance(position) >= BackOffSteering.FREE_START_M && BackOffSteering.pivotNeeded(error, pivoting = false)
        }
        assertEquals("pivot from the third report (heading 82.8°)", 2, first)
        val (x, y, reported) = samples[first]
        val lateral = kotlin.math.abs(BackOffSteering.offset(start, target, Point(x, y)))
        assertTrue("lateral deviation when the pivot starts: $lateral", lateral < 0.03)
        val error = BackOffSteering.error(BackOffSteering.wanted(90.0, start, target, Point(x, y)), BackOffSteering.current(90.0, reportedAtStart, reported))
        assertTrue("heading must increase (back towards north-west)", BackOffSteering.pivotAngular(error, 0.0996) > 0)
    }
}
