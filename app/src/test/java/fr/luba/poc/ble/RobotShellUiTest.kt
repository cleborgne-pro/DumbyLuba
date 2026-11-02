package fr.luba.poc.ble

import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell projection ignores the telemetry and follows the connection and the running activities. */
class RobotShellUiTest {
    private val idle = RobotUi(connection = ConnectionState.Receiving, ready = true)

    @Test fun `telemetry and route progress do not change the shell`() {
        val tracking = idle.copy(robotRoute = idle.robotRoute.copy(running = true))
        val moved = tracking.copy(telemetry = Telemetry(x = 3.0, y = 4.0, heading = 90.0),
            robotRoute = tracking.robotRoute.copy(index = 12, status = "Following"), events = listOf("12:00 position"))
        assertEquals(RobotShellUi.from(tracking), RobotShellUi.from(moved))
    }

    @Test fun `connection and activities change the shell`() {
        val shell = RobotShellUi.from(idle)
        assertFalse(shell.robotActive)
        assertNotEquals(shell, RobotShellUi.from(idle.copy(connection = ConnectionState.Disconnected, ready = false)))
        assertTrue(RobotShellUi.from(idle.copy(manualActive = true)).robotActive)
        assertTrue(RobotShellUi.from(idle.copy(robotRoute = idle.robotRoute.copy(running = true))).routeRunning)
        assertEquals("contact-1", RobotShellUi.from(idle.copy(manualContactId = "contact-1")).manualContactId)
    }
}
