package fr.luba.poc.simulation

import org.junit.Assert.*
import org.junit.Test

class NativeDockingTest {
    @Test fun waitsForTheReturnStateThenTheChargingContact() {
        val dock = NativeDocking(requestedAt = 1000)
        assertEquals("Report older than the request ignored", NativeDocking.Step.Waiting, dock.observe(1500, 900, 11, 0))
        assertEquals(NativeDocking.Step.Waiting, dock.observe(2000, 1900, 11, 0))
        assertEquals(NativeDocking.Step.Returning, dock.observe(4000, 3900, 14, 0))
        assertEquals(NativeDocking.Step.Returning, dock.observe(60_000, 59_900, 14, 0))
        assertEquals(NativeDocking.Step.Docked, dock.observe(70_000, 69_900, 15, 1))
    }

    @Test fun chargingReportedLateAfterLeavingTheReturnStateIsAccepted() {
        val dock = NativeDocking(requestedAt = 0)
        dock.observe(1000, 900, 14, 0)
        assertEquals(NativeDocking.Step.Returning, dock.observe(2000, 1900, 11, 0))
        assertEquals(NativeDocking.Step.Docked, dock.observe(4000, 3900, 11, 2))
    }

    @Test fun failsWhenTheRobotNeverStartsOrStopsWithoutContact() {
        val idle = NativeDocking(requestedAt = 0)
        assertTrue(idle.observe(16_000, 15_900, 11, 0) is NativeDocking.Step.Failed)
        val stopped = NativeDocking(requestedAt = 0)
        stopped.observe(1000, 900, 14, 0)
        stopped.observe(2000, 1900, 11, 0)
        val failure = stopped.observe(8000, 7900, 11, 0)
        assertTrue(failure is NativeDocking.Step.Failed)
        assertTrue((failure as NativeDocking.Step.Failed).reason.contains("sans contact de charge"))
        val slow = NativeDocking(requestedAt = 0)
        slow.observe(1000, 900, 14, 0)
        assertTrue(slow.observe(301_000, 300_900, 14, 0) is NativeDocking.Step.Failed)
    }

    @Test fun chargingContactBeforeLeavingTheReturnStateIsNotYetSettled() {
        // 2026-10-15, 13:59:18: charge contact reported in state 14, state 11 one second later.
        val dock = NativeDocking(requestedAt = 0)
        dock.observe(1000, 900, 14, 0)
        assertEquals(NativeDocking.Step.Docked, dock.observe(41_000, 40_900, 14, 1))
        assertFalse(NativeDocking.settled(14))
        assertFalse(NativeDocking.settled(null))
        assertTrue(NativeDocking.settled(11))
        assertTrue(NativeDocking.settled(15))
    }
}
