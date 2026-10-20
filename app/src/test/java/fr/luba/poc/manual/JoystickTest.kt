package fr.luba.poc.manual

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class JoystickTest {
    @Test fun independentAxesAllowTwoFingersAndReleaseSeparately() {
        assertEquals(1000 to -450, Joystick.dualCommand(-1f,1f,100))
        assertEquals(0 to -450, Joystick.dualCommand(0f,1f,100))
        assertEquals(1000 to 0, Joystick.dualCommand(-1f,0f,100))
        assertEquals(0 to 0, Joystick.dualCommand(0f,0f,100))
        assertEquals(-300 to 135, Joystick.dualCommand(1f,-1f,30))
        assertEquals(0 to 0, Joystick.dualCommand(0.1f,-0.1f,100))
        assertEquals(1000 to -450, Joystick.dualCommand(-3f,2f,100))
    }
    @Test fun hundredPercentReachesOfficialRockerMaximum() {
        // PyMammotion control.py: linear_speed == ±1000, angular_speed == ±450 at the rocker ceiling.
        assertEquals(fr.luba.poc.protocol.Commands.PROTOCOL_MAX_LINEAR, Joystick.dualCommand(-1f,0f,100).first)
        assertEquals(-fr.luba.poc.protocol.Commands.PROTOCOL_MAX_ANGULAR, Joystick.dualCommand(0f,1f,100).second)
        assertEquals(1.0, Joystick.estimatedLinearMps(100), 1e-9)
        assertEquals(0.3, Joystick.estimatedLinearMps(30), 1e-9)
    }
    @Test fun sliderScalesTranslationWithinProtocolBounds() {
        for (percent in listOf(10,30,50,100)) {
            val forward = Joystick.dualCommand(-1f,0f,percent)
            assertEquals(percent * 10,forward.first)
            val reverse = Joystick.dualCommand(1f,0f,percent)
            assertEquals(-percent * 10,reverse.first)
            val wire = fr.luba.poc.proto.LubaMsgProto.LubaMsg.parseFrom(
                fr.luba.poc.protocol.Commands().movement(forward.first,forward.second))
            assertEquals(percent * 10,wire.driver.todevDevmotionCtrl.setLinearSpeed)
        }
        assertThrows(IllegalArgumentException::class.java) { Joystick.dualCommand(-1f,0f,101) }
        assertThrows(IllegalArgumentException::class.java) { Joystick.dualCommand(-1f,0f,9) }
    }
    @Test fun responseIsProgressiveBeyondTheDeadZone() {
        // No jump when leaving the dead zone: just beyond it, the command is almost zero.
        assertEquals(0f, Joystick.response(Joystick.DEAD_ZONE), 0f)
        assertTrue(Joystick.response(0.13f) in 0f..0.01f)
        // Fine control at half travel, full speed at the end stop, symmetric and clamped.
        assertEquals(0.26f, Joystick.response(0.5f), 0.01f)
        assertEquals(1f, Joystick.response(1f), 1e-6f)
        assertEquals(-Joystick.response(0.7f), Joystick.response(-0.7f), 1e-6f)
        assertEquals(1f, Joystick.response(4f), 1e-6f)
        val samples = (0..100).map { Joystick.response(it / 100f) }
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
        // Half travel at 100 %: about a quarter of the ceiling instead of half.
        assertEquals(-261 to 0, Joystick.dualCommand(0.5f, 0f, 100))
    }
    @Test fun notchesTickByThirdsAndAtTheEndStop() {
        assertEquals(0, Joystick.notch(0f))
        assertEquals(0, Joystick.notch(-0.12f))
        assertEquals(1, Joystick.notch(0.2f))
        assertEquals(2, Joystick.notch(-0.5f))
        assertEquals(3, Joystick.notch(0.8f))
        assertEquals(4, Joystick.notch(1f))
        assertEquals(4, Joystick.notch(-3f))
    }
    @Test fun padCapturesTheWholeAreaAndClampsAtTheEndStops() {
        val pad = AxisPad(300f, 400f, vertical = true)
        assertEquals(0f, pad.value(pad.center), 0f)
        assertEquals(-1f, pad.value(Offset(150f, 0f)), 0f)
        assertEquals(1f, pad.value(Offset(5f, 399f)), 0f)
        // Up = negative value = forward; the knob reaches the end of the capsule.
        assertEquals(Offset(150f, 200f - pad.travel), pad.knob(-1f))
        val turn = AxisPad(300f, 200f, vertical = false)
        assertEquals(0.5f, turn.value(Offset(150f + turn.travel / 2f, 10f)), 1e-4f)
        assertTrue(turn.thickness <= 200f)
    }
    @Test fun directionsDeadZoneAndSpeedBounds() {
        assertEquals(0 to 0, Joystick.command(0.1f,0f,100))
        assertEquals(1000 to 0, Joystick.command(0f,-1f,100))
        assertEquals(-300 to 0, Joystick.command(0f,1f,30))
        assertEquals(0 to -450, Joystick.command(1f,0f,100))
        assertEquals(0 to 450, Joystick.command(-1f,0f,100))
        assertEquals(Joystick.command(1f,-1f,100),Joystick.command(2f,-2f,100))
        assertThrows(IllegalArgumentException::class.java) { Joystick.command(Float.NaN,0f,30) }
    }
}
