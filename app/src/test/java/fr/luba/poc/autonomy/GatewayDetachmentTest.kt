package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class GatewayDetachmentTest {
    /** Robot driving along x at 0.4 m/s, one reading per second; [rssi] from the robot position. */
    private fun drive(detector: GatewayDetachment, seconds: Int, startX: Double = 0.0, rssi: (Point) -> Int): Pair<Int, GatewayDetachment.Verdict>? {
        for (t in 0 until seconds) {
            val here = Point(startX + 0.4 * t, 0.0)
            detector.reading(t * 1000L, rssi(here), here)?.let { return t to it }
        }
        return null
    }
    private fun fallen(at: Point, here: Point) = (-24 - 20 * log10(at.distance(here).coerceAtLeast(0.1) / 0.1)).toInt()

    @Test fun phoneFallenOffTheRobotIsDetectedWithItsPosition() {
        val detector = GatewayDetachment()
        val drop = Point(4.0, 0.0)
        val (second, verdict) = requireNotNull(drive(detector, 40) { if (it.x < drop.x) -24 + (it.x * 10).toInt() % 3 else fallen(drop, it) })
        assertTrue("phone within one reading of the drop point: ${verdict.point}", verdict.point.distance(drop) <= 0.41)
        assertTrue("detected a few seconds after the fall (second $second)", second in 11..18)
        assertTrue("robot moved away: ${verdict.distanceM}", verdict.distanceM!! >= GatewayDetachment.MIN_DISTANCE_M)
        assertEquals(false, verdict.probable)
        assertTrue(verdict.text, verdict.text.contains("tombée du robot") && verdict.text.contains("x=4.0 m"))
        // Verdict kept until the next route.
        assertEquals(verdict, detector.reading(99_000, -24, Point(0.0, 0.0)))
    }

    @Test fun steadyOrNoisySignalOnTheRobotNeverConcludes() {
        val detector = GatewayDetachment()
        val random = java.util.Random(7)
        assertNull(drive(detector, 300) { (-26 + random.nextGaussian() * 3).toInt() })
        assertEquals(-26.0, detector.mountedDbm!!.toDouble(), 2.0)
    }

    @Test fun weakReadingsWithTheRobotStillNextToTheDropPointAreNotEnough() {
        val detector = GatewayDetachment()
        drive(detector, 15) { -24 }   // level confirmed, robot at x = 5.6 m
        val here = Point(5.6, 0.0)
        // Robot stopped (pivot, obstacle) with weak readings: no displacement from the drop point candidate, no verdict.
        repeat(10) { assertNull(detector.reading(20_000L + it * 1000, -50, here)) }
        // It drives away with the signal still weak: concluded.
        assertNotNull((1..5).firstNotNullOfOrNull { detector.reading(40_000L + it * 1000, -50, Point(5.6 + 0.4 * it, 0.0)) })
    }

    @Test fun gatewayLeftOnTheGroundOnPurposeIsNeverConfirmedNorReported() {
        // Phone used as a fixed relay at (0, 0): the signal changes with the robot's position from the start.
        val detector = GatewayDetachment()
        assertNull(drive(detector, 60) { fallen(Point(0.0, 0.0), it) })
        assertNull(detector.mountedDbm)
        assertNull(detector.linkLost(Point(20.0, 0.0)))
        // Robot passing close to the relay then leaving again: never confirmed either.
        val passing = GatewayDetachment()
        assertNull(drive(passing, 60, startX = -10.0) { fallen(Point(0.0, 0.5), it) })
        assertNull(passing.mountedDbm)
    }

    @Test fun levelNotConfirmedBeforeTheRobotHasDriven() {
        val detector = GatewayDetachment()
        repeat(30) { detector.reading(it * 1000L, -24, Point(0.0, 0.0)) }
        assertNull("robot on the spot: level not confirmed", detector.mountedDbm)
        drive(detector, 9) { -24 }
        assertEquals(-24, detector.mountedDbm)
    }

    @Test fun linkLostWhileTheSignalWasFallingGivesAProbableVerdict() {
        val detector = GatewayDetachment()
        drive(detector, 12) { -24 }                                   // confirmed, last mounted reading at x = 4.4
        assertNull(detector.reading(13_000, -38, Point(5.0, 0.0)))   // falling, not yet weak enough
        val verdict = requireNotNull(detector.linkLost(Point(5.6, 0.0)))
        assertTrue(verdict.probable)
        assertEquals(4.4, verdict.point.x, 1e-9)
        assertTrue(verdict.text, verdict.text.contains("probablement"))
    }

    @Test fun linkLostWithASteadySignalIsAPlainBluetoothLoss() {
        val detector = GatewayDetachment()
        drive(detector, 12) { -24 }
        assertNull(detector.linkLost(Point(8.0, 0.0)))
    }

    @Test fun rearmedOnTheNextRouteConcludesAgainOnlyIfThePhoneIsStillOnTheGround() {
        val detector = GatewayDetachment()
        val drop = Point(4.0, 0.0)
        val verdict = requireNotNull(drive(detector, 40) { if (it.x < drop.x) -24 else fallen(drop, it) }).second
        // Phone put back on the robot: mounted readings, nothing concluded.
        detector.rearm()
        assertNull(drive(detector, 20, startX = 6.0) { -24 })
        // Next drop: concluded from the new position.
        detector.rearm()
        val again = Point(12.0, 0.0)
        val second = requireNotNull(drive(detector, 40, startX = 8.0) { if (it.x < again.x) -24 else fallen(again, it) }).second
        assertTrue("${second.point} vs first ${verdict.point}", second.point.distance(again) <= 0.41)
    }

    @Test fun verdictRebuiltFromEventFields() {
        val verdict = GatewayDetachment.Verdict(Point(3.25, -1.5), 0, -55, -24, Point(5.0, -1.5), 1.75, probable = false)
        val json = org.json.JSONObject(verdict.fields())
        val fields = json.keys().asSequence().associateWith { k -> json.get(k).let { if (it == org.json.JSONObject.NULL) null else it } }
        val rebuilt = requireNotNull(GatewayDetachment.Verdict.fromFields(fields, 0))
        assertEquals(verdict, rebuilt)
        assertNull(GatewayDetachment.Verdict.fromFields(mapOf("x" to 1.0), 0))
    }
}
