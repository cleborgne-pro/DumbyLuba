package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

/** Animated wheels: distance driven by each side from the displayed poses. */
class WheelOdometerTest {
    @Test fun forwardMotionTurnsBothSidesEqually() {
        val odometer = WheelOdometer(trackM = 0.4, maxSideStepM = 1.0)
        odometer.advance(DisplayPose(Point(0.0, 0.0), 0.0))
        val travel = odometer.advance(DisplayPose(Point(0.3, 0.0), 0.0))
        assertEquals(0.3, travel.leftM, 1e-9)
        assertEquals(0.3, travel.rightM, 1e-9)
        // Reverse: scrolling backwards.
        val back = odometer.advance(DisplayPose(Point(0.1, 0.0), 0.0))
        assertEquals(0.1, back.leftM, 1e-9)
    }

    @Test fun pivotTurnsSidesInOppositeDirections() {
        val odometer = WheelOdometer(trackM = 0.4, maxSideStepM = 1.0)
        odometer.advance(DisplayPose(Point(1.0, 1.0), 350.0))
        val travel = odometer.advance(DisplayPose(Point(1.0, 1.0), 20.0))   // +30° by the shortest way
        val arc = Math.toRadians(30.0) * 0.2
        assertEquals(-arc, travel.leftM, 1e-9)
        assertEquals(arc, travel.rightM, 1e-9)
    }

    @Test fun jumpsAndUnknownHeadingDoNotSpinTheWheels() {
        val odometer = WheelOdometer()
        odometer.advance(DisplayPose(Point(0.0, 0.0), 90.0))
        assertEquals(WheelTravel.ZERO, odometer.advance(DisplayPose(Point(0.0, 5.0), 90.0)))
        assertEquals(WheelTravel.ZERO, odometer.advance(DisplayPose(Point(0.0, 5.2), null)))
        assertEquals(WheelTravel.ZERO, odometer.advance(null))
    }

    @Test fun fastPlaybackRollsTreadsForwardWithoutStrobing() {
        // Fast simulation: 15 cm between two frames, more than four lug pitches.
        val odometer = WheelOdometer()
        val pitch = WheelOdometer.TREAD_PITCH_M
        var previousPhase = 0.0
        odometer.advance(DisplayPose(Point(0.0, 0.0), 0.0))
        for (i in 1..20) {
            val travel = odometer.advance(DisplayPose(Point(0.15 * i, 0.0), 0.0))
            assertEquals(WheelOdometer.MAX_SIDE_STEP_M * i, travel.leftM, 1e-9)
            assertEquals(travel.leftM, travel.rightM, 1e-9)
            // Phase always advancing by less than half a pitch: the lugs scroll forward.
            val phase = WheelTravel.phase(travel.leftM, pitch)
            val advance = ((phase - previousPhase) % 1.0 + 1.0) % 1.0
            assertTrue(advance in 0.01..0.49)
            previousPhase = phase
        }
        // Fast reverse: also bounded, in the other direction.
        val back = odometer.advance(DisplayPose(Point(2.85, 0.0), 0.0))
        assertEquals(WheelOdometer.MAX_SIDE_STEP_M * 19, back.leftM, 1e-9)
    }

    @Test fun treadPhaseWrapsInBothDirections() {
        assertEquals(0.25, WheelTravel.phase(0.125, 0.5), 1e-9)
        assertEquals(0.75, WheelTravel.phase(-0.125, 0.5), 1e-9)
        assertEquals(0.0, WheelTravel.phase(1.0, 0.5), 1e-9)
    }
}
