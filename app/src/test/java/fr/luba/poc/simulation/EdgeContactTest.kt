package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.RobotMap
import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Contact at the border of the task area ([EdgeContact]): limit (back-off and resume), object at the edge (tighter
 * estimate) or object (unchanged). Square zone 20 × 20 m, outer border y = 20 (outward direction 90°).
 */
class EdgeContactTest {
    private val robot = RobotEnvelope.LUBA2
    private val square = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 20.0), Point(0.0, 20.0))
    private val border = EdgeContact.Border(listOf(square))

    /** Robot centre whose front edge (nearest corner or bumper centre) stands [front] inside the border y = 20, heading [heading]. */
    private fun centre(front: Double, heading: Double): Point {
        val r = Math.toRadians(heading)
        val bumperY = 20.0 - front - robot.halfWidthM * abs(cos(r))
        return Point(10.0, bumperY - robot.frontM * sin(r))
    }

    /** Coded contact at [front] / [heading] after a drift of [drift] from a horizontal planned line. */
    private fun coded(front: Double, heading: Double, drift: Double, previous: List<Point> = emptyList()): EdgeContactClass {
        val c = centre(front, heading)
        val line = c.y - drift
        val path = listOf(SimEdge(Point(2.0, line), Point(18.0, line), true))
        return EdgeContact.classify(true, 0L, 1, c, heading, border, path, 0, emptyList(), previous)
    }

    /** Collision lock without a code, centre [inside] metres inside the border. */
    private fun lock(inside: Double, sensors: Int?, heading: Double = 150.0): EdgeContactClass {
        val c = Point(10.0, 20.0 - inside)
        val path = listOf(SimEdge(Point(2.0, 19.56), Point(10.0, 19.56), true), SimEdge(Point(10.0, 19.56), Point(10.0, 19.2), false))
        return EdgeContact.classify(false, 25L, sensors, c, heading, border, path, 1, listOf(Point(6.0, 19.56), Point(10.0, 19.56), c))
    }

    /**
     * 2026-10-19 at 14:50 (Fosse): self-check 25 without a code during a 150° pivot at the end of a pass, centre slid
     * 24 cm towards the border: back-off and resume, no obstacle.
     */
    @Test fun lockWithoutCodeAtTheOuterBorderIsALimitContact() {
        val c = lock(0.20, 0)
        assertEquals(EdgeContactKind.LIMIT, c.kind)
        assertEquals("lock_at_limit", c.cause)
        assertEquals("slide seen as a drift", 0.24, c.driftM, 0.01)
        assertEquals(0.20, requireNotNull(c.centerBorderDistM), 1e-6)
        // Same rule as the joystick ([fr.luba.poc.protocol.OffMapLatch.INSIDE_TOLERANCE_M]): 35 cm inside at most.
        assertEquals(EdgeContactKind.LIMIT, lock(0.34, 0).kind)
        assertEquals(EdgeContactKind.OBJECT, lock(0.36, 0).kind)
        assertEquals("lock_inside", lock(0.36, 0).cause)
        // Beyond the border too.
        assertEquals(EdgeContactKind.LIMIT, lock(-0.3, 0).kind)
    }

    @Test fun lockWithBumperBitsSetIsNeverALimitContact() {
        for (bits in listOf(1, 2, 4, 7, 0x1F)) assertEquals("bits $bits", EdgeContactKind.OBJECT, lock(0.10, bits).kind)
        assertEquals("sensors unknown", EdgeContactKind.OBJECT, lock(0.10, null).kind)
        // Other bits (blades, lift 3 to 8 have their own stop) do not decide here.
        assertEquals(EdgeContactKind.LIMIT, lock(0.10, 1 shl 9).kind)
        // A protection other than 25 is never a limit contact.
        val c = Point(10.0, 19.9)
        assertEquals(EdgeContactKind.OBJECT, EdgeContact.classify(false, 41L, 0, c, 90.0, border, emptyList(), 0, emptyList()).kind)
    }

    @Test fun codedContactAtTheBorderAfterADriftIsALimitContact() {
        val c = coded(0.05, 60.0, 0.15)
        assertEquals(EdgeContactKind.LIMIT, c.kind)
        assertEquals("coded_with_drift", c.cause)
        assertEquals(0.15, c.driftM, 1e-6)
        assertEquals(30.0, requireNotNull(c.approachDegrees), 1e-6)
        assertEquals(0.05, requireNotNull(c.frontBorderDistM), 1e-6)
    }

    /**
     * 2026-10-28 at 09:52:08 (Zamboni zone, passes at 59°, 2 m corridor): wood pile at the border, bumper point 0.41 m
     * inside, deviation 4 cm. No excursion: the planned line itself touches the object, which stays an obstacle.
     */
    @Test fun codedContactWithoutDriftNearTheBorderIsAnObjectAtTheEdge() {
        val corridor = listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 2.0), Point(0.0, 2.0))
        val narrow = EdgeContact.Border(listOf(corridor))
        val heading = 59.0
        val r = Math.toRadians(heading)
        // Bumper point 0.41 m inside the north border (y = 2), centre 4 cm left of the planned pass (towards the border).
        val centre = Point(15.0, 2.0 - 0.41 - robot.frontM * sin(r))
        val left = Point(-sin(r), cos(r))
        val q = Point(centre.x - 0.04 * left.x, centre.y - 0.04 * left.y)
        val path = listOf(SimEdge(Point(q.x - 2.0 * cos(r), q.y - 2.0 * sin(r)), Point(q.x + 0.5 * cos(r), q.y + 0.5 * sin(r)), true))
        val c = EdgeContact.classify(true, 0L, 1, centre, heading, narrow, path, 0, listOf(path[0].a, centre))
        assertEquals(0.41, requireNotNull(c.borderDistM), 1e-6)
        assertTrue("deviation ${c.driftM}", c.driftM < EdgeContact.DRIFT_M)
        assertEquals(31.0, requireNotNull(c.approachDegrees), 1e-6)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, c.kind)
        assertEquals(ObstacleBypass.CONTACT_EDGE_MARGIN_M, EdgeContact.marginM(c.kind), 1e-12)
    }

    @Test fun midZoneContactIsAnObject() {
        val path = listOf(SimEdge(Point(2.0, 10.0), Point(18.0, 10.0), true))
        val c = EdgeContact.classify(true, 0L, 1, Point(10.0, 10.0), 0.0, border, path, 0, listOf(Point(5.0, 10.0)))
        assertEquals(EdgeContactKind.OBJECT, c.kind)
        assertEquals("mid_zone", c.cause)
        assertEquals(ObstacleBypass.CONTACT_MARGIN_M, EdgeContact.marginM(c.kind), 1e-12)
        // Without a map border (incomplete map, demonstration), never anything else.
        assertEquals(EdgeContactKind.OBJECT, EdgeContact.classify(true, 0L, 0, Point(10.0, 19.9), 90.0, null, path, 0, emptyList()).kind)
        assertNull(EdgeContact.border(RobotMap(listOf(MapShape(1, 0, square)), false)))
        assertNotNull(EdgeContact.border(RobotMap(listOf(MapShape(1, 0, square)), true, "test")))
    }

    @Test fun secondLimitContactWithin1500mmBecomesAnObject() {
        val first = coded(0.05, 60.0, 0.15)
        assertEquals(EdgeContactKind.LIMIT, first.kind)
        val again = coded(0.05, 60.0, 0.15, listOf(Point(first.bumper.x + 1.4, first.bumper.y)))
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, again.kind)
        assertTrue(again.sameSpot)
        assertEquals(EdgeContactKind.LIMIT, coded(0.05, 60.0, 0.15, listOf(Point(first.bumper.x + 1.6, first.bumper.y))).kind)
        // Lock without a code too.
        val lock = lock(0.2, 0)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, EdgeContact.classify(false, 25L, 0, Point(10.0, 19.8), 150.0, border,
            listOf(SimEdge(Point(2.0, 19.56), Point(10.0, 19.56), true)), 0, emptyList(), listOf(lock.bumper)).kind)
    }

    @Test fun thresholdsOfTheCodedLimitContact() {
        // Band: 15 cm inside, 40 cm beyond.
        assertEquals(EdgeContactKind.LIMIT, coded(0.14, 60.0, 0.2).kind)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(0.16, 60.0, 0.2).kind)
        assertEquals(EdgeContactKind.LIMIT, coded(-0.39, 60.0, 0.6).kind)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(-0.41, 60.0, 0.6).kind)
        // Drift: 8 cm.
        assertEquals(EdgeContactKind.LIMIT, coded(0.05, 60.0, 0.09).kind)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(0.05, 60.0, 0.07).kind)
        // Approach: 75° from the outward direction.
        assertEquals(EdgeContactKind.LIMIT, coded(0.05, 16.0, 0.2).kind)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(0.05, 14.0, 0.2).kind)
        // Object band: front edge 60 cm inside at most.
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(0.59, 60.0, 0.0).kind)
        assertEquals(EdgeContactKind.OBJECT, coded(0.61, 60.0, 0.0).kind)
    }

    /** At 45°, a front corner touches the border while the bumper centre is still 18 cm back: the band is measured on the front edge. */
    @Test fun frontCornerCountsForTheBand() {
        val c = coded(0.05, 45.0, 0.2)
        assertEquals(EdgeContactKind.LIMIT, c.kind)
        assertEquals(0.05 + robot.halfWidthM * cos(Math.toRadians(45.0)), requireNotNull(c.borderDistM), 1e-6)
    }
}
