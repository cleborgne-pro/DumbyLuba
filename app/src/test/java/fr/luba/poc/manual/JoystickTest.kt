package fr.luba.poc.manual

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
