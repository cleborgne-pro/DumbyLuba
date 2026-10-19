package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pivot slide map learnt during mowing (2026-10-15: 3.5 cm/s downhill at the bottom of the Jardin d'Eden). */
class SlideFieldTest {
    private fun sample(x: Double, y: Double, vy: Double, s: Double = 4.0) = SlideSample(x, y, 0.0, vy, s, 1L)

    @Test fun cellsAverageByDurationAndNeedEnoughMeasures() {
        var f = SlideField().learned(sample(-25.5, 11.5, -0.04, 2.0))
        assertNull("2 s of measurement are not enough", f.velocity(Point(-25.5, 11.5)))
        f = f.learned(sample(-25.2, 11.2, -0.03, 2.0))
        assertEquals(-0.035, f.velocity(Point(-25.5, 11.5))!!.y, 1e-9)
        // Neighbouring cell without measurement: the neighbour counts for half (4 s × 0.5 < 3 s), then is enough with 6 s.
        assertNull(f.velocity(Point(-24.5, 11.5)))
        f = f.learned(sample(-25.5, 11.5, -0.035, 2.0))
        assertEquals(-0.035, f.velocity(Point(-24.5, 11.5))!!.y, 1e-9)
        // Far away: nothing.
        assertNull(f.velocity(Point(-20.0, 11.5)))
    }

    @Test fun outliersAreIgnoredAndSpeedIsBounded() {
        val f = SlideField().learned(sample(0.5, 0.5, -0.5)).learned(sample(0.5, 0.5, -0.1, 0.5))
        assertTrue("50 cm/s push or too short measurement ignored", f.isEmpty)
        val strong = SlideField().learned(sample(0.5, 0.5, -0.12, 10.0))
        assertEquals(SlideField.MAX_SPEED_MPS, hypot(strong.velocity(Point(0.5, 0.5))!!.x, strong.velocity(Point(0.5, 0.5))!!.y), 1e-9)
    }

    @Test fun recentMissionsOutweighOldOnes() {
        var f = SlideField()
        repeat(20) { f = f.learned(sample(0.5, 0.5, -0.04)) }        // wet grass: 80 s of measurements
        repeat(10) { f = f.learned(sample(0.5, 0.5, -0.01)) }        // dry grass: 40 s
        val v = f.velocity(Point(0.5, 0.5))!!.y
        assertTrue("cap of ${SlideField.MAX_WEIGHT_S} s: the recent measurement weighs at least as much ($v)", v > -0.026 && v < -0.01)
    }

    @Test fun jsonAndBinaryRoundTripAndRouteSubset() {
        var f = SlideField()
        for (i in 0 until 6) f = f.learned(SlideSample(i + 0.5, 0.5, 0.01 * i, -0.02, 4.0, 1000L + i))
        assertEquals(f, SlideField.decodeJson(f.toJson()))
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> f.write(out) } }.toByteArray()
        val back = SlideField.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(f.size, back.size)
        assertEquals(f.velocity(Point(2.5, 0.5))!!.x, back.velocity(Point(2.5, 0.5))!!.x, 1e-6)
        assertEquals(3, f.around(listOf(Point(0.5, 0.5)), marginM = 2.0).size)
    }

    @Test fun scaledFieldFollowsTheGrassOfTheDay() {
        val f = SlideField().learned(sample(0.5, 0.5, -0.02))
        assertEquals(-0.03, f.scaled(1.5).velocity(Point(0.5, 0.5))!!.y, 1e-9)
        assertEquals(f, f.scaled(1.0))
    }

    @Test fun meterAggregatesThePureRotationIntervalsOfOnePivot() {
        val m = PivotSlideMeter()
        assertNull(m.update(Point(0.0, 0.0), 1000, Point(0.0, -0.03) to 1.0))
        assertNull(m.update(Point(0.0, -0.03), 2000, Point(0.0, -0.05) to 1.0))
        val s = requireNotNull(m.update(Point(0.0, -0.08), 3000, null))
        assertEquals(-0.04, s.vy, 1e-9); assertEquals(2.0, s.seconds, 1e-9)
        assertNull(m.update(Point(0.0, 0.0), 4000, null))
    }

    @Test fun deadReckoningMeasuresTheSlideAndPredictsIt() {
        val behind = DeadReckoning.PUBLISHED_POINT_BEHIND_M
        fun published(c: Point, h: Double) = Point(c.x - behind * cos(Math.toRadians(h)), c.y - behind * sin(Math.toRadians(h)))
        val r = DeadReckoning(publishedBehindM = behind)
        var c = Point(-25.7, 11.3); var h = 237.0
        r.fix(published(c, h), h, 0)
        r.sent(0, 0, 300)
        for (s in 1..3) {
            c = Point(c.x, c.y - 0.035); h += 30.0
            r.fix(published(c, h), h, s * 1000L)
            val (d, seconds) = requireNotNull(r.rotationSlide) { "intervalle $s : rotation pure" }
            assertEquals(-0.035, d.y / seconds, 0.003)
        }
        // Commanded advance: no longer a pure rotation.
        r.sent(3000, 200, 0)
        r.fix(published(Point(c.x, c.y + 0.2), h), h, 4500)
        assertNull(r.rotationSlide)
        // Prediction of a pivot with a known slide.
        val p = DeadReckoning()
        p.slide = { Point(0.0, -0.035) }
        p.fix(Point(0.0, 0.0), 0.0, 0); p.sent(0, 0, 300)
        assertEquals(-0.035 * 1.5, p.predict(1500)!!.position.y, 1e-6)
    }

    /**
     * Execution bench on the Jardin d'Eden route, slope modelled as on 2026-10-15 (downhill slide of 3.5 cm/s at the
     * bottom of the zone, 2 cm/s in the middle, 0.9 cm/s at the top): a first mowing learns the map, the second uses it.
     */
    @Test(timeout = 900000) fun learnedSlideFieldBringsTheRobotBackOnTheEdenStripes() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == "Jardin d’Eden" }
        val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone)
        val slope: (Point) -> Point = { p -> Point(0.0, -(if (p.y < 13.5) 0.035 else if (p.y < 17.0) 0.02 else 0.009)) }
        val robot = RouteExecutionModel.Robot(publishedBehindM = DeadReckoning.PUBLISHED_POINT_BEHIND_M, pivotSlide = slope)
        var field = SlideField()
        val first = RouteExecutionModel.drive(edges, robot, 50, approach, learn = { field = field.learned(it) })
        val second = RouteExecutionModel.drive(edges, robot, 50, approach, slideField = field)
        println("SLIDE 1st mowing (no map): max %.3f, RMS %.3f, checked %.3f · 2nd mowing (map of %d cells): max %.3f, RMS %.3f, checked %.3f".format(
            first.trueWorst, first.trueRms, first.worst, field.size, second.trueWorst, second.trueRms, second.worst))
        println("  1re : $first"); println("  2e : $second")
        val bottom = field.velocity(Point(-25.0, 11.5))
        assertTrue("slide learnt at the bottom: $bottom", bottom != null && bottom.y < -0.02 && bottom.y > -0.05)
        assertTrue("mean actual deviation ${second.trueRms} ≥ ${first.trueRms}", second.trueRms < first.trueRms)
        assertTrue(second.toString(), second.blocked == 0 && second.midPivots == 0 && second.worst <= first.worst + 0.02)
    }
}
