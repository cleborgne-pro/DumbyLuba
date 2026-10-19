package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TiltCalibrationTest {
    /** Rotation by [deg] around a horizontal (x, y) axis of the phone: tilted holder. */
    private fun tilt(g: PhoneGravity, axisX: Double, axisY: Double, deg: Double): PhoneGravity {
        val n = sqrt(axisX * axisX + axisY * axisY); val kx = axisX / n; val ky = axisY / n
        val t = Math.toRadians(deg); val c = cos(t); val s = sin(t)
        val dot = kx * g.x + ky * g.y
        val cx = ky * g.z; val cy = -kx * g.z; val cz = kx * g.y - ky * g.x
        return PhoneGravity(g.x * c + cx * s + kx * dot * (1 - c), g.y * c + cy * s + ky * dot * (1 - c), g.z * c + cz * s)
    }

    private val flat = PhoneGravity(0.0, 0.0, PhoneMount.G)

    @Test fun referenceTakenOnFlatGroundStraightensEveryReading() {
        val reference = tilt(flat, 1.0, 0.4, 15.0)
        val c = requireNotNull(TiltCalibration.capture(List(6) { reference }, 37.0, 1000).first)
        assertEquals(15.0, c.tiltDegrees, 1e-6)
        val leveledRef = c.level(reference)
        assertEquals(0.0, leveledRef.x, 1e-9); assertEquals(0.0, leveledRef.y, 1e-9); assertEquals(PhoneMount.G, leveledRef.z, 1e-9)
        // A slope seen through the tilted holder becomes again the slope seen with the phone flat.
        val slope = PhoneTilt.gravity(0.08, -0.03, 37.0)
        val leveled = c.level(tilt(slope, 1.0, 0.4, 15.0))
        assertEquals(slope.x, leveled.x, 1e-9); assertEquals(slope.y, leveled.y, 1e-9); assertEquals(slope.z, leveled.z, 1e-9)
        assertEquals(flat, TiltCalibration(flat, null, 0).level(flat))
    }

    @Test fun captureRefusesMovementTooFewReadingsAndPhonesThatAreNotFlat() {
        assertNotNull(TiltCalibration.capture(List(4) { flat }, null, 0).second)
        val shaky = List(6) { i -> PhoneGravity(if (i % 2 == 0) 0.3 else -0.3, 0.0, PhoneMount.G) }
        assertTrue(TiltCalibration.capture(shaky, null, 0).second!!.contains("en mouvement"))
        assertTrue(TiltCalibration.capture(List(6) { tilt(flat, 0.0, 1.0, 40.0) }, null, 0).second!!.contains("30°"))
        val ok = TiltCalibration.capture(List(6) { i -> PhoneGravity(0.02 * i, 0.0, PhoneMount.G) }, 400.0, 5)
        assertNull(ok.second); assertEquals(40.0, ok.first!!.angle!!, 1e-9); assertEquals(5L, ok.first!!.at)
    }

    @Test fun inclinedHolderWithReferenceGivesTheSameReliefAsAFlatPhone() {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 16.0), Point(0.0, 16.0)))
        val map = RobotMap(listOf(zone), true)
        val plan = MultiZonePlanner.plan(listOf(zone), emptyList(), 3.0, 0.6, CoverageMode.SURVEY_GRID, true)
        val engine = fr.luba.poc.simulation.MowingSimulation(fr.luba.poc.simulation.TrajectoryPlanner(map, 0.6, emptyList()), Point(4.0, 4.0), plan, map,
            fr.luba.poc.simulation.SimConfig(autonomyMinutes = 600.0, baseEntryHeadingDegrees = 0.0, instantCharging = true))
        var state = engine.start()
        repeat(400) { state = engine.advance(state, 30.0) }
        val flatSamples = state.signalSamples
        // Same survey, phone on a holder tilted by 12°: without a reference, the offset is only estimated.
        val inclined = flatSamples.map { it.copy(gravity = tilt(it.gravity!!, 0.3, 1.0, 12.0)) }
        val reference = requireNotNull(TiltCalibration.capture(List(6) { tilt(flat, 0.3, 1.0, 12.0) }, 0.0, 1).first)
        val expected = requireNotNull(ReliefModel.result(flatSamples, PhoneMount.TOP_FORWARD))
        val calibrated = requireNotNull(ReliefModel.result(inclined, PhoneMount.CALIBRATED, reference))
        assertTrue(calibrated.calibrated)
        assertEquals(MountSource.CALIBRATED, calibrated.mount.source)
        assertEquals(expected.dropM, calibrated.dropM, 0.03)
        assertTrue(terrainLegend(SurveySignal.RELIEF, calibrated, inclined).contains("référence sur sol plat"))
        // Automatic orientation on the corrected readings: a reference without orientation keeps the detection.
        val auto = requireNotNull(ReliefModel.result(inclined, PhoneMount.AUTO, reference.copy(angle = null)))
        assertNotEquals(MountSource.CALIBRATED, auto.mount.source)
        assertEquals(expected.dropM, auto.dropM, 0.05)
        // "Saved" without an orientation in the reference: falls back to automatic detection.
        assertNotEquals(MountSource.CALIBRATED, ReliefModel.result(inclined, PhoneMount.CALIBRATED, reference.copy(angle = null))!!.mount.source)
    }
}
