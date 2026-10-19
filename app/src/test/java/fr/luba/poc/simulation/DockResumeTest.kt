package fr.luba.poc.simulation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DockResumeTest {
    @Test fun resumedOnlyDuringRechargeOfSavedMowing() {
        assertTrue(DockResume.wanted(rechargeCycle = true, routeRunning = true, hasCheckpoint = true))
        assertFalse(DockResume.wanted(rechargeCycle = false, routeRunning = true, hasCheckpoint = true))
        assertFalse(DockResume.wanted(rechargeCycle = true, routeRunning = false, hasCheckpoint = true))
        assertFalse(DockResume.wanted(rechargeCycle = true, routeRunning = true, hasCheckpoint = false))
    }

    @Test fun linkCausesAreResumedRobotFaultsAreNot() {
        // 2026-10-16, 16:28:54: gateway silent during docking, link closed.
        assertTrue(DockResume.linkCause("Liaison fermée", outage = false))
        assertTrue(DockResume.linkCause("Liaison perdue pendant l'accostage Mammotion : vérifier le robot", outage = false))
        assertTrue(DockResume.linkCause("Wi-Fi de la passerelle coupé depuis 120 s : reprise refusée", outage = false))
        assertTrue(DockResume.linkCause("BLE interrupted during the obstacle photo", outage = false))
        assertTrue(DockResume.linkCause("État robot périmé", outage = true))
        assertFalse(DockResume.linkCause("Accostage Mammotion interrompu (état 11) sans contact de charge", outage = false))
        assertFalse(DockResume.linkCause("Protection robot active", outage = false))
        assertFalse(DockResume.linkCause(null, outage = false))
    }

    @Test fun dockedNeedsChargeContactAndReturnFinished() {
        assertTrue(DockResume.docked(robotState = 11, chargeState = 1))
        assertTrue(DockResume.docked(robotState = 15, chargeState = 2))
        // Charge contact reported while the Mammotion return (14) is not finished.
        assertFalse(DockResume.docked(robotState = 14, chargeState = 1))
        assertFalse(DockResume.docked(robotState = 11, chargeState = 0))
        assertFalse(DockResume.docked(robotState = null, chargeState = null))
    }
}
