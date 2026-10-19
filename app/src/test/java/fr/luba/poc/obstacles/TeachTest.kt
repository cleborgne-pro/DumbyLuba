package fr.luba.poc.obstacles

import org.junit.Assert.*
import org.junit.Test

class TeachTest {

    @Test fun `approach refuses stale or non-finite position and limit distance`() {
        assertNull(TeachSafety.approachFailure(2000, 0.9, 0.0, 0.0, 0.0))
        assertNotNull(TeachSafety.approachFailure(2001, 0.0, 0.0, 0.0, 0.0))
        assertNotNull(TeachSafety.approachFailure(0, 1.0, 0.0, 0.0, 0.0))
        assertNotNull(TeachSafety.approachFailure(0, Double.NaN, 0.0, 0.0, 0.0))
        assertNotNull(TeachSafety.approachFailure(0, null, 0.0, 0.0, 0.0))
    }

    @Test fun `teach config slow and bounded`() {
        assertEquals(20, TeachConfig().linearSpeed)
        assertTrue(TeachConfig().linearSpeed <= TeachConfig.MAX_TEACH_SPEED)
        val scenarios = listOf<() -> Unit>(
            { TeachConfig(linearSpeed = 0) },
            { TeachConfig(linearSpeed = TeachConfig.MAX_TEACH_SPEED + 1) },
            { TeachConfig(maxDurationMs = 4_999) },
            { TeachConfig(maxDurationMs = 120_001) },
            { TeachConfig(batteryFloor = 9) },
            { TeachConfig(batteryFloor = 81) },
        )
        scenarios.forEach { action -> assertThrows(IllegalArgumentException::class.java) { action() } }
    }

    @Test fun `start refuses everything but a ready luba with clear bumper and blades off`() {
        assertNull(TeachSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, false, false, false))
        val scenarios = listOf<Pair<String, String?>>(
            "nom" to TeachSafety.startFailure("Luba", 100, 72, 11, 0, 0, false, false, false, false),
            "etat stale" to TeachSafety.startFailure("Luba-VS", 2001, 72, 11, 0, 0, false, false, false, false),
            "batterie basse" to TeachSafety.startFailure("Luba-VS", 100, 30, 11, 0, 0, false, false, false, false),
            "robot non pret" to TeachSafety.startFailure("Luba-VS", 100, 72, 13, 0, 0, false, false, false, false),
            "bumper" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 1, 0, false, false, false, false),
            "lames" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 512, 0, false, false, false, false),
            "self-check" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 0, 34, false, false, false, false),
            "campagne" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, true, false, false, false),
            "essai lame" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, true, false, false),
            "marche" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, false, true, false),
            "carte" to TeachSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, false, false, true),
        )
        scenarios.forEach { (label, failure) -> assertNotNull(label, failure) }
    }

    @Test fun `tick watches freshness battery and blades but not the bumper`() {
        assertNull(TeachSafety.tickFailure(100, 72, 0, batteryFloor = 30))
        assertNotNull(TeachSafety.tickFailure(null, 72, 0, batteryFloor = 30))
        assertNotNull(TeachSafety.tickFailure(2001, 72, 0, batteryFloor = 30))
        assertNotNull(TeachSafety.tickFailure(100, 30, 0, batteryFloor = 30))
        assertNotNull(TeachSafety.tickFailure(100, 72, 512, batteryFloor = 30))
        assertNotNull(TeachSafety.tickFailure(100, 72, null, batteryFloor = 30))
    }

    @Test fun `bumper = expected contact, not a failure`() {
        assertTrue(TeachSafety.bumperHealthAlert(1))
        assertTrue(TeachSafety.bumperHealthAlert(4))
        assertTrue(TeachSafety.bumperHealthAlert(7))
        assertFalse(TeachSafety.bumperHealthAlert(0))
        assertFalse(TeachSafety.bumperHealthAlert(512))
        assertFalse(TeachSafety.bumperHealthAlert(null))
    }

    @Test fun `proposal places the obstacle in front of the bumper with the chosen radius`() {
        val contact = TeachContact(x = 2.0, y = 3.0, headingDeg = 90.0, atMs = 42)
        val proposal = TeachSafety.proposal(contact, radiusM = 0.30)
        assertEquals(0.30, proposal.radiusM, 0.0)
        assertEquals(contact, proposal.contact)
        assertEquals(2.0, proposal.estimated.x, 1e-9)
        assertEquals(3.0 + RobotEnvelope.LUBA2.frontM + 0.30, proposal.estimated.y, 1e-9)
    }
    @Test fun stationaryHandPressAndPresumedBlockingNeverCreateMappedObstacle() {
        assertFalse(TeachSafety.canProposeObstacle(false,"CONTACT_DETECTED",100))
        assertFalse(TeachSafety.canProposeObstacle(true,"ABORTED",100))
        assertFalse(TeachSafety.canProposeObstacle(true,"CONTACT_DETECTED",2001))
        assertFalse(TeachSafety.canProposeObstacle(true,"CONTACT_DETECTED",-1))
        assertTrue(TeachSafety.canProposeObstacle(true,"CONTACT_DETECTED",2000))
    }
}
