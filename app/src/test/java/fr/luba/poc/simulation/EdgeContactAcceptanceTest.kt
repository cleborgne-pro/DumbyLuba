package fr.luba.poc.simulation

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Acceptance edge cases of the border contact classification ([EdgeContact], plan 2.1-2.3) and of the detour budget
 * ([ObstacleBypass.detourRefusal], plan 3.1) not covered by [EdgeContactTest]. Square zone 20 × 20 m, outer border
 * y = 20 (outward direction 90°).
 */
class EdgeContactAcceptanceTest {
    private val robot = RobotEnvelope.LUBA2
    private val square = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 20.0), Point(0.0, 20.0))
    private val border = EdgeContact.Border(listOf(square))

    /** Robot centre at [x] whose front edge (nearest corner or bumper centre) stands [front] inside the border y = 20, heading [heading]. */
    private fun centre(front: Double, heading: Double, x: Double = 10.0): Point {
        val r = Math.toRadians(heading)
        val bumperY = 20.0 - front - robot.halfWidthM * abs(cos(r))
        return Point(x, bumperY - robot.frontM * sin(r))
    }

    /** Coded contact at [front] / [heading] after a drift of [drift] (centre towards the border) from a horizontal planned line. */
    private fun coded(front: Double, heading: Double, drift: Double, x: Double = 10.0, previous: List<Point> = emptyList(),
                      trail: List<Point> = emptyList(), discovered: List<LocalObstacle> = emptyList()): EdgeContactClass {
        val c = centre(front, heading, x)
        val line = c.y - drift
        val path = listOf(SimEdge(Point(1.0, line), Point(19.0, line), true))
        return EdgeContact.classify(true, 0L, 1, c, heading, border, path, 0, trail, previous, discovered)
    }

    /** Collision lock without a code, centre [inside] metres inside the border (negative: beyond). */
    private fun lock(inside: Double, sensors: Int?, heading: Double = 90.0): EdgeContactClass {
        val c = Point(10.0, 20.0 - inside)
        val path = listOf(SimEdge(Point(2.0, 19.56), Point(10.0, 19.56), true))
        return EdgeContact.classify(false, 25L, sensors, c, heading, border, path, 0, listOf(Point(6.0, 19.56), c))
    }

    /** Self-check 25 without a code but a bumper or lift bit set: never a limit contact, inside, on or beyond the border. */
    @Test fun lockWithoutCodeWithSensorBitsIsNeverALimitWhereverTheRobotIs() {
        for (inside in listOf(0.34, 0.2, 0.0, -0.2, -1.0)) for (bits in 1..7) for (heading in listOf(90.0, 0.0, -90.0)) {
            val c = lock(inside, bits, heading)
            assertEquals("inside $inside bits $bits heading $heading", EdgeContactKind.OBJECT, c.kind)
            assertEquals("sensors", c.cause)
            // Extra bits beyond the bumper / lift ones change nothing.
            assertEquals(EdgeContactKind.OBJECT, lock(inside, bits or (1 shl 9), heading).kind)
        }
        // Control: the same poses with the sensors clear are limit contacts.
        for (inside in listOf(0.34, 0.2, 0.0, -0.2, -1.0)) assertEquals("inside $inside", EdgeContactKind.LIMIT, lock(inside, 0).kind)
    }

    /** Coded contact in the limit band, facing the border, without any drift: the planned line itself touches an object. */
    @Test fun codedContactWithoutDriftInTheLimitBandIsAnObjectAtTheEdge() {
        for (front in listOf(0.14, 0.05, 0.0, -0.2, -0.39)) for (heading in listOf(90.0, 60.0, 120.0)) {
            val c = coded(front, heading, 0.0)
            assertEquals("front $front heading $heading", EdgeContactKind.OBJECT_AT_EDGE, c.kind)
            assertEquals("object_at_edge", c.cause)
            assertEquals(0.0, c.driftM, 1e-9)
            assertEquals(ObstacleBypass.CONTACT_EDGE_MARGIN_M, EdgeContact.marginM(c.kind), 1e-12)
        }
        // Robot inside its line (moved away from the border): no drift towards the border.
        val inward = coded(0.05, 90.0, -0.2)
        assertEquals(0.0, inward.driftM, 1e-9)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, inward.kind)
    }

    /** Heading parallel to the border (more than 75° from the outward direction) or turned away from it: never a limit contact. */
    @Test fun headingAlongOrAwayFromTheBorderIsNeverALimit() {
        for (heading in listOf(0.0, 180.0, 10.0, 170.0, 14.0, 166.0, -30.0, -90.0, -150.0)) {
            val c = coded(0.05, heading, 0.3)
            assertTrue("heading $heading approach ${c.approachDegrees}", requireNotNull(c.approachDegrees) > EdgeContact.APPROACH_DEGREES)
            assertNotEquals("heading $heading", EdgeContactKind.LIMIT, c.kind)
            assertEquals("heading $heading", EdgeContactKind.OBJECT_AT_EDGE, c.kind)
        }
        // Symmetric on both sides of the outward direction.
        assertEquals(EdgeContactKind.LIMIT, coded(0.05, 16.0, 0.3).kind)
        assertEquals(EdgeContactKind.LIMIT, coded(0.05, 164.0, 0.3).kind)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(0.05, 166.0, 0.3).kind)
    }

    /**
     * Drift measured on the last 1.5 m of trail (about 10 s): an excursion towards the border 1 m back counts even with
     * the centre back on its line; 2 m back, or before a trail break, it does not.
     */
    @Test fun driftIsMeasuredOnTheRecentTrail() {
        val c = centre(0.05, 60.0)
        fun at(trail: List<Point>) = coded(0.05, 60.0, 0.0, trail = trail + c)
        val recent = at(listOf(Point(c.x - 1.2, c.y), Point(c.x - 1.0, c.y + 0.10), Point(c.x - 0.5, c.y)))
        assertEquals(0.10, recent.driftM, 1e-6)
        assertEquals(EdgeContactKind.LIMIT, recent.kind)
        val old = at(listOf(Point(c.x - 2.2, c.y + 0.10), Point(c.x - 2.0, c.y), Point(c.x - 1.0, c.y), Point(c.x - 0.5, c.y)))
        assertEquals(0.0, old.driftM, 1e-6)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, old.kind)
        val broken = at(listOf(Point(c.x - 1.0, c.y + 0.10), MissionTrail.BREAK, Point(c.x - 0.5, c.y)))
        assertEquals(0.0, broken.driftM, 1e-6)
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, broken.kind)
    }

    /** Second limit contact: 1.4 m from any earlier one of the mission is an object at the edge, 1.6 m is a limit again. */
    @Test fun secondLimitContactAt1400mmIsAnObjectAndAt1600mmALimit() {
        val first = coded(0.05, 60.0, 0.15)
        assertEquals(EdgeContactKind.LIMIT, first.kind)
        val b = first.bumper
        // Diagonal offsets, and the near one hidden among far ones.
        val near = Point(b.x + 1.4 / Math.sqrt(2.0), b.y - 1.4 / Math.sqrt(2.0))
        val far = Point(b.x - 1.6 / Math.sqrt(2.0), b.y - 1.6 / Math.sqrt(2.0))
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, coded(0.05, 60.0, 0.15, previous = listOf(far, near, Point(1.0, 1.0))).kind)
        assertTrue(coded(0.05, 60.0, 0.15, previous = listOf(near)).sameSpot)
        assertEquals("same_spot", coded(0.05, 60.0, 0.15, previous = listOf(near)).cause)
        val again = coded(0.05, 60.0, 0.15, previous = listOf(far, Point(1.0, 1.0)))
        assertEquals(EdgeContactKind.LIMIT, again.kind)
        assertFalse(again.sameSpot)
    }

    /**
     * Limit contact next to an obstacle discovered during the mission (QA 2026-10-28, Enfer: resume link of 52 m around
     * the disc estimated 0.3 m away): within 1.5 m of the estimated disc it is an object at the edge (`near_obstacle`),
     * at 1.6 m a limit again; same for a lock without a code. Distance measured to the disc, not to its centre.
     */
    @Test fun limitContactWithin1500mmOfADiscoveredObstacleIsAnObjectAtTheEdge() {
        val first = coded(0.05, 60.0, 0.15)
        assertEquals(EdgeContactKind.LIMIT, first.kind)
        val b = first.bumper
        fun disc(gap: Double) = ObstacleBypass.contactObstacle("d", Point(10.0, 10.0), 0.0, 1L).let { o ->
            o.copy(x = b.x - (gap + o.radiusM) / Math.sqrt(2.0), y = b.y - (gap + o.radiusM) / Math.sqrt(2.0))
        }
        val near = coded(0.05, 60.0, 0.15, discovered = listOf(disc(1.6), disc(1.4)))
        assertEquals(EdgeContactKind.OBJECT_AT_EDGE, near.kind)
        assertEquals("near_obstacle", near.cause)
        assertTrue(near.sameSpot)
        assertEquals(ObstacleBypass.CONTACT_EDGE_MARGIN_M, EdgeContact.marginM(near.kind), 1e-12)
        assertEquals(EdgeContactKind.LIMIT, coded(0.05, 60.0, 0.15, discovered = listOf(disc(1.6))).kind)
        // Bumper inside the estimated disc.
        assertEquals("near_obstacle", coded(0.05, 60.0, 0.15, discovered = listOf(disc(-0.1))).cause)
        // Lock without a code next to it: same rule.
        val lockAt = Point(10.0, 19.8)
        val lockBumper = EdgeContact.bumper(lockAt, 90.0)
        val o = ObstacleBypass.contactObstacle("d", lockAt, 90.0, 1L)
        assertTrue(Point(o.x, o.y).distance(lockBumper) - o.radiusM < 1.5)
        assertEquals(EdgeContactKind.LIMIT, EdgeContact.classify(false, 25L, 0, lockAt, 90.0, border, emptyList(), 0, emptyList()).kind)
        assertEquals("near_obstacle", EdgeContact.classify(false, 25L, 0, lockAt, 90.0, border, emptyList(), 0, emptyList(), discovered = listOf(o)).cause)
    }

    /** Resume link of a limit contact: up to 2 m kept (plan 2.7), longer drops the limit path for the obstacle path. */
    @Test fun resumeLinkLongerThan2mDropsTheLimitPath() {
        assertEquals(2.0, EdgeContact.MAX_LINK_M, 1e-12)
        assertNull(EdgeContact.longLink(0.0))
        assertNull(EdgeContact.longLink(2.0))
        assertTrue(requireNotNull(EdgeContact.longLink(2.01)).contains("traité comme un obstacle"))
        assertTrue(requireNotNull(EdgeContact.longLink(52.0)).contains("52,0 m"))
        assertNull(EdgeContact.longLink(0.3, maxM = 0.5))
        assertNotNull(EdgeContact.longLink(0.3, maxM = 0.1))
    }

    /**
     * Four limit contacts per mission: five contacts far apart along the border are each classified LIMIT, the cap is
     * the mission counter ([EdgeContact.refusal]): accepted up to the 4th, the 5th keeps the stop.
     */
    @Test fun fifthLimitContactOfTheMissionKeepsTheStop() {
        assertEquals(4, EdgeContact.MAX_PER_MISSION)
        val handled = mutableListOf<Point>()
        var stop: String? = null
        for ((n, x) in listOf(2.0, 5.0, 8.0, 11.0, 14.0).withIndex()) {
            val c = coded(0.05, 60.0, 0.15, x = x, previous = handled)
            assertEquals("contact ${n + 1}", EdgeContactKind.LIMIT, c.kind)
            val refusal = EdgeContact.refusal(handled.size)
            if (n < 4) assertNull("contact ${n + 1}", refusal) else stop = refusal
            if (refusal == null) handled += c.bumper
        }
        assertEquals(4, handled.size)
        assertTrue(requireNotNull(stop).contains("4 contacts en bordure"))
        assertNotNull(EdgeContact.refusal(5))
    }

    /**
     * A lock without a code well inside the outer border, here inside a no-go zone drawn in the middle of the zone, is
     * never a limit contact: no-go zones are not part of the border ([EdgeContact.Border]); the Fold keeps its stop there.
     */
    @Test fun lockInsideANoGoZoneIsNotALimitContact() {
        val noGo = listOf(Point(8.0, 8.0), Point(12.0, 8.0), Point(12.0, 12.0), Point(8.0, 12.0))
        val map = RobotMap(listOf(MapShape(1, 0, square, "Zone"), MapShape(2, 1, noGo, "No-go")), true, "test")
        val b = requireNotNull(EdgeContact.border(map))
        assertEquals(8.0, b.signedDistance(Point(10.0, 12.0)), 1e-9)
        val c = EdgeContact.classify(false, 25L, 0, Point(10.0, 10.0), 90.0, b, emptyList(), 0, emptyList())
        assertEquals(EdgeContactKind.OBJECT, c.kind)
        assertEquals("lock_inside", c.cause)
    }

    /** Mid-zone contact: same estimated obstacle (margin 0.30 m) as before the border classification. */
    @Test fun midZoneContactKeepsTheFormerEstimate() {
        val path = listOf(SimEdge(Point(2.0, 10.0), Point(18.0, 10.0), true))
        for (heading in listOf(0.0, 45.0, 90.0, 180.0)) {
            val c = EdgeContact.classify(true, 0L, 1, Point(10.0, 10.0), heading, border, path, 0, listOf(Point(8.0, 10.3), Point(10.0, 10.0)))
            assertEquals(EdgeContactKind.OBJECT, c.kind)
            assertEquals(ObstacleBypass.contactObstacle("a", Point(10.0, 10.0), heading, 5L),
                ObstacleBypass.contactObstacle("a", Point(10.0, 10.0), heading, 5L, marginM = EdgeContact.marginM(c.kind)))
        }
        assertEquals(0.3, ObstacleBypass.CONTACT_MARGIN_M, 1e-12)
        // Front edge 0.61 m inside (just beyond the object band): mid-zone too.
        assertEquals(EdgeContactKind.OBJECT, coded(0.61, 90.0, 0.3).kind)
    }

    /** Detour budget around the floor / ratio crossover (5 m of cutting skipped) and just below / above each. */
    @Test fun detourBudgetJustBelowAndAbove() {
        val any = RouteRepair(emptyList(), emptyList(), EdgeContactAcceptanceTest.planner, null, null, 1, 0.0, 0.0)
        fun refused(links: Double, skipped: Double) = ObstacleBypass.detourRefusal(any.copy(bypassMeters = links, skippedCuttingMeters = skipped)) != null
        assertFalse(refused(14.99, 0.0)); assertFalse(refused(15.0, 0.0)); assertTrue(refused(15.01, 0.0))
        assertFalse(refused(15.0, 5.0)); assertTrue(refused(15.01, 5.0))
        assertFalse(refused(15.02, 5.01)); assertTrue(refused(15.04, 5.01))
        assertFalse(refused(42.29, 14.1)); assertTrue(refused(42.31, 14.1))
        // Nothing skipped (transit): the 15 m floor only.
        assertTrue(refused(52.8, 0.0))
        assertTrue(requireNotNull(ObstacleBypass.detourRefusal(any.copy(bypassMeters = 79.2, skippedCuttingMeters = 14.1))).contains("42,3"))
    }

    companion object {
        private val planner by lazy {
            fr.luba.poc.planning.RoutePlanning.navigation(RobotMap(listOf(MapShape(1, 0,
                listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 20.0), Point(0.0, 20.0)))), true, "test"),
                RobotEnvelope.LUBA2.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
        }
    }
}
