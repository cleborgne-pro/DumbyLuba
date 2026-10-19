package fr.luba.poc.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Precise reason for stopping autonomous tracking on an unusable robot link (2026-10-16: only a generic reason). */
class GatewayLinkProblemTest {
    private fun problem(connected: Boolean = true, failed: Int? = null, queued: Int = 0, inFlight: Long? = null) =
        GatewayRobot.linkProblem(connected, failed, queued, inFlight, stallMs = 3000)

    @Test fun healthyLink() {
        assertNull(problem())
        assertNull(problem(queued = 4, inFlight = 2999))
    }

    @Test fun eachCauseIsNamed() {
        assertEquals("BLE interrompu : liaison Bluetooth du robot fermée", problem(connected = false, inFlight = 9000))
        assertEquals("BLE interrompu : écriture refusée par le robot (code 133)", problem(failed = 133))
        assertEquals("BLE interrompu : écriture sans confirmation du robot depuis 3,2 s", problem(inFlight = 3200))
        assertEquals("BLE interrompu : file d'écriture saturée (5 trames en attente)", problem(queued = 5))
    }

    @Test fun summarizedAsARobotLinkLoss() {
        listOf(problem(connected = false), problem(failed = 1), problem(inFlight = 5000), problem(queued = 9)).forEach {
            assertTrue(it, fr.luba.poc.map.InterruptionSummary.of("Trajet interrompu : $it").label == "Liaison perdue avec le robot")
        }
    }
}
