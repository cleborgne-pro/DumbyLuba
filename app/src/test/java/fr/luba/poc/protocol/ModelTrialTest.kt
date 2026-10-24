package fr.luba.poc.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model trial: armed for one robot that is not validated, for a limited time, joystick bounded. */
class ModelTrialTest {
    @After fun tearDown() = ModelTrial.resetForTests()

    @Test fun onlyAMowerInReadOnlyModeIsEligible() {
        assertTrue(ModelTrial.eligible("Luba-MN3K8A2B"))
        assertTrue(ModelTrial.eligible("Luba-VA123"))
        assertFalse("validated model", ModelTrial.eligible("Luba-VSABC"))
        assertFalse("validated X series", ModelTrial.eligible("Luba-VP3K8A2B"))
        assertFalse("not a mower", ModelTrial.eligible("Spino-123"))
        assertFalse(ModelTrial.eligible(null))
        assertFalse(ModelTrial.eligible(" "))
        assertTrue(runCatching { ModelTrial.arm("Luba-VSABC", 0) }.isFailure)
        assertNull(ModelTrial.state.value)
    }

    @Test fun armedForThatRobotOnlyAndForThirtyMinutes() {
        assertFalse(ModelTrial.testAllowed("Luba-VA1", 0))
        val armed = ModelTrial.arm(" Luba-VA1 ", 1_000)
        assertEquals("Luba-VA1", armed.robotName)
        assertTrue(ModelTrial.active("Luba-VA1", 1_000))
        assertTrue(ModelTrial.testAllowed("Luba-VA1", 1_000 + ModelTrial.DURATION_MS - 1))
        assertFalse("expired", ModelTrial.active("Luba-VA1", 1_000 + ModelTrial.DURATION_MS))
        assertFalse("another robot", ModelTrial.active("Luba-VA2", 2_000))
        assertTrue("validated model, trial or not", ModelTrial.testAllowed("Luba-VSABC", 0))
        assertEquals(armed, ModelTrial.end())
        assertFalse(ModelTrial.active("Luba-VA1", 2_000))
        assertNull(ModelTrial.end())
    }

    @Test fun joystickBoundedToLowSpeedOnATrialModel() {
        assertEquals(ModelTrial.MAX_LINEAR to -ModelTrial.MAX_ANGULAR, ModelTrial.joystick("Luba-VA1", 1000, -450))
        assertEquals(-ModelTrial.MAX_LINEAR to 100, ModelTrial.joystick("Luba-VA1", -800, 100))
        assertEquals(1000 to -450, ModelTrial.joystick("Luba-VSABC", 1000, -450))
    }
}
