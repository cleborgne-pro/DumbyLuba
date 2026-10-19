package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * Point published by the LUBA 2 8 cm behind its rotation centre (mowing of 2026-10-15, 212 pivots): the tracking
 * checks the centre, estimated by [DeadReckoning] from the published point and the fused heading.
 */
class PublishedPointTest {
    private val behind = DeadReckoning.PUBLISHED_POINT_BEHIND_M

    private fun published(center: Point, heading: Double): Point {
        val r = Math.toRadians(heading)
        return Point(center.x - behind * cos(r), center.y - behind * sin(r))
    }

    @Test fun pivotOnTheSpotKeepsTheMeasuredCentreStill() {
        val center = Point(-25.7, 11.3)
        val r = DeadReckoning(publishedBehindM = behind)
        // U-turn in place, one position per second: the published point describes a half-circle of 8 cm radius.
        var heading = 237.0
        r.fix(published(center, heading), heading, 0)
        for (s in 1..6) {
            r.sent(s * 1000L - 1000, 0, 300)
            heading += 30.0
            r.fix(published(center, heading), heading, s * 1000L)
            val measured = requireNotNull(r.measured)
            assertTrue("centre measured at ${measured.distance(center)} m after ${s * 30}°", measured.distance(center) < 0.005)
        }
        // The published point moved by 16 cm: that was the apparent "overshoot" at the pass ends.
        assertEquals(0.16, published(center, 237.0).distance(published(center, 417.0)), 0.001)
        // Without a declared offset: published point taken as the centre, behaviour unchanged.
        val plain = DeadReckoning()
        plain.fix(published(center, 237.0), 237.0, 0)
        assertEquals(published(center, 237.0), plain.measured)
    }

    @Test(timeout = 600000) fun compensationBringsTheRealCentreBackOnTheEdenRoute() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == "Jardin d’Eden" }
        val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone)
        val robot = RouteExecutionModel.Robot(publishedBehindM = behind)
        val raw = RouteExecutionModel.drive(edges, robot, 50, approach, compensatedBehindM = 0.0)
        val centred = RouteExecutionModel.drive(edges, robot, 50, approach)
        println("PUBLISHED POINT not compensated: max %.3f, RMS %.3f, ends max %.3f · compensated: max %.3f, RMS %.3f, ends max %.3f".format(
            raw.trueWorst, raw.trueRms, raw.endErrors.max(), centred.trueWorst, centred.trueRms, centred.endErrors.max()))
        println("  not compensated: $raw · actual ${raw.trueWhere}")
        println("  compensated: $centred · actual ${centred.trueWhere}")
        assertTrue("mean actual deviation ${centred.trueRms} ≥ ${raw.trueRms}", centred.trueRms < raw.trueRms)
        assertTrue(centred.toString(), centred.worst <= 0.27 && centred.blocked == 0 && centred.midPivots == 0)
    }
}
