package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

/** Pivot on a slope: slide tolerated up to 60 cm during the pivot and the reconvergence, chassis inside the safe zone. */
class PivotSlideTest {
    private val zone = ReturnDrift.BaseZone(Point(0.0, 0.0), 1.05)

    @Test fun slideDuringAPivotIsToleratedInsideTheSafeArea() {
        val p = Point(-25.68, 10.96)
        // Mowing of 2026-10-15, 09:08:57: 30 cm deviation at the end of a U-turn pivot, gateway stop.
        assertTrue(ReturnDrift.failure(0.30367, 16, returning = false, p, zone) { true }!!.startsWith("Écart au trajet >30 cm"))
        assertNull(ReturnDrift.failure(0.30367, 16, returning = false, p, zone, slide = true) { true })
        assertNull(ReturnDrift.failure(0.59, 16, returning = false, p, zone, slide = true) { true })
        assertTrue(ReturnDrift.failure(0.65, 16, returning = false, p, zone, slide = true) { true }!!.startsWith("Glissement au pivot >60 cm"))
        assertTrue(ReturnDrift.failure(0.4, 16, returning = false, p, zone, slide = true) { false }!!.contains("hors de la zone sûre"))
        assertTrue(ReturnDrift.failure(0.4, 16, returning = false, Point(0.5, 0.3), zone, slide = true) { true }!!.contains("zone de sécurité de la base"))
        // Return to base: the return tolerance (1 m) keeps priority.
        assertNull(ReturnDrift.failure(0.8, 16, returning = true, p, zone, slide = true) { true })
        // Recovered by a back-off then a link, like a route deviation.
        assertTrue(DriftRecovery.recoverable("Glissement au pivot >60 cm (65 cm, arête 17)"))
        assertTrue(DriftRecovery.recoverable("Glissement de 40 cm hors de la zone sûre (arête 17)"))
        assertFalse(DriftRecovery.recoverable("Glissement de 40 cm dans la zone de sécurité de la base (arête 17)"))
    }

    @Test fun toleranceLastsFromThePivotUntilTheRobotIsBackOnTheLine() {
        val slide = PivotSlide()
        assertFalse(slide.update(false, Point(0.0, 0.0), 0.05))
        assertTrue(slide.update(true, Point(0.0, 0.0), 0.1))
        assertTrue(slide.update(true, Point(0.0, -0.3), 0.32))
        // End of the pivot: reconvergence, tolerance kept while the deviation stays large.
        assertTrue(slide.update(false, Point(0.0, -0.3), 0.32))
        assertTrue(slide.update(false, Point(0.2, -0.25), 0.25))
        // Line found again (≤ 15 cm) after at least 30 cm of travel: normal deviation.
        assertFalse(slide.update(false, Point(0.5, -0.12), 0.12))
        // Reconvergence too long: tolerance lifted beyond 2 m.
        assertTrue(slide.update(true, Point(1.0, 0.0), 0.1))
        assertTrue(slide.update(false, Point(1.0, -0.3), 0.3))
        assertTrue(slide.update(false, Point(2.9, -0.3), 0.28))
        assertFalse(slide.update(false, Point(3.1, -0.3), 0.28))
    }

    @Test fun followerReportsThePivotOfAUturn() {
        // End of stripe, 25 cm link, return stripe: 99° pivot at the vertex.
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(5.0, 0.0), false), SimEdge(Point(5.0, 0.0), Point(5.0, 0.25), false),
            SimEdge(Point(5.0, 0.25), Point(0.0, 0.25), false))
        val follower = PathFollower(edges, 0, 3, RangeArrival.TARGET_M)
        assertFalse(follower.pivoting)
        follower.command(Point(2.0, 0.0), 0.0, 400, 300)
        assertFalse(follower.pivoting)
        val pivot = follower.command(Point(4.99, 0.0), 0.0, 400, 300)
        assertEquals(0, pivot.linear)
        assertTrue(follower.pivoting)
    }
}
