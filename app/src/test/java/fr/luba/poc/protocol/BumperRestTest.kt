package fr.luba.poc.protocol

import fr.luba.poc.diagnostics.NavSafety
import fr.luba.poc.obstacles.TeachSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resting value of the bumper slot on a validated model (DumbyLuba#1, LUBA 2 AWD 3000X reading 1 in every report). */
class BumperRestTest {
    @After fun tearDown() = ModelTrial.resetForTests()

    private val blades = 1 shl 9

    @Test fun `alert at rest from the connection is learnt and cleared for the guards only`() {
        val rest = BumperRest()
        assertEquals(BumperRest.Change.LEARNT, rest.observe(1, 11, 10, idle = true, contactSeen = false))
        assertEquals(1, rest.value)
        assertEquals(0, BumperRest.clear(rest.value, 1))
        // Other bits untouched (blades), raw value 1 still an alert for a guard that does not clear it.
        assertEquals(blades, BumperRest.clear(rest.value, 1 or blades))
        assertTrue(TeachSafety.bumperHealthAlert(1))
        assertFalse(TeachSafety.bumperHealthAlert(BumperRest.guard("Luba-VP-3000X", 1, rest.value, 0)))
        assertNull(rest.observe(1, 11, 10, idle = true, contactSeen = false))
    }

    @Test fun `waits for the robot ready and idle while the alert stays from the connection`() {
        val rest = BumperRest()
        assertNull(rest.observe(1, 14, 10, idle = true, contactSeen = false))
        assertNull(rest.observe(1, 11, 10, idle = false, contactSeen = false))
        assertEquals(0, rest.value)
        assertEquals(BumperRest.Change.LEARNT, rest.observe(1, 11, 10, idle = true, contactSeen = false))
    }

    @Test fun `error or obstacle values are never learnt nor cleared`() {
        for (slot in 2..7) {
            val rest = BumperRest()
            assertNull(rest.observe(slot, 11, 10, idle = true, contactSeen = false))
            assertEquals(0, rest.value)
            assertEquals(slot, BumperRest.guard("Luba-VP-3000X", slot, rest.value, 0))
            assertTrue(TeachSafety.bumperHealthAlert(BumperRest.guard("Luba-VP-3000X", slot, rest.value, 0)))
            // Even with a learnt alert at rest, 2–7 stays visible to the guards.
            assertEquals(slot, BumperRest.clear(1, slot))
            assertTrue(NavSafety.tickFailure(100, 80, slot, false, 30)!!.contains("pare-chocs ($slot)"))
        }
    }

    @Test fun `alert that appears after a clear bumper is not learnt on this connection`() {
        val rest = BumperRest()
        assertNull(rest.observe(0, 11, 10, idle = true, contactSeen = false))
        assertNull(rest.observe(1, 11, 10, idle = true, contactSeen = false))
        assertEquals(0, rest.value)
        assertTrue(BumperRest.refusal(1).contains("reconnecter"))
        rest.reset()
        assertEquals(BumperRest.Change.LEARNT, rest.observe(1, 11, 10, idle = true, contactSeen = false))
    }

    @Test fun `value change forgets the resting value until the next connection`() {
        val rest = BumperRest()
        rest.observe(1, 11, 10, idle = true, contactSeen = false)
        assertEquals(BumperRest.Change.FORGOTTEN, rest.observe(0, 11, 10, idle = true, contactSeen = false))
        assertNull(rest.observe(1, 11, 10, idle = true, contactSeen = false))
        assertEquals(0, rest.value)
        assertEquals(1, BumperRest.guard("Luba-VP-3000X", 1, rest.value, 0))
    }

    @Test fun `collision lock or contact code prevent learning, and do not unlearn the same value`() {
        val latched = BumperRest()
        assertNull(latched.observe(1, 11, CollisionLatch.VALUE, idle = true, contactSeen = false))
        assertNull(latched.observe(1, 11, 10, idle = true, contactSeen = false))
        assertEquals(0, latched.value)
        val contact = BumperRest()
        assertNull(contact.observe(1, 11, 10, idle = true, contactSeen = true))
        assertNull(contact.observe(1, 11, 10, idle = true, contactSeen = false))
        assertEquals(0, contact.value)
        // Learnt first: a later lock keeps it (the lock itself stops and refuses through SelfCheck / CollisionLatch).
        val learnt = BumperRest()
        learnt.observe(1, 11, 10, idle = true, contactSeen = false)
        assertNull(learnt.observe(1, 11, CollisionLatch.VALUE, idle = true, contactSeen = true))
        assertEquals(1, learnt.value)
        assertFalse(SelfCheck.joystickAllowed(CollisionLatch.VALUE))
    }

    @Test fun `model trial rule unchanged on a model that is not validated`() {
        // A resting value passed for a LUBA 3 is ignored: only the armed trial clears its alert (DumbyLuba#2).
        assertEquals(1, BumperRest.guard("Luba-VA-SIM01", 1, 1, 1000))
        ModelTrial.arm("Luba-VA-SIM01", 1000, 1)
        assertEquals(0, BumperRest.guard("Luba-VA-SIM01", 1, 0, 2000))
        assertEquals(2, BumperRest.guard("Luba-VA-SIM01", 2, 0, 2000))
        assertEquals(ModelTrial.sensors("Luba-VA-SIM01", 1, 2000), BumperRest.guard("Luba-VA-SIM01", 1, 0, 2000))
    }

    @Test fun `unknown sensors keep the plain refusal`() {
        assertEquals("Alerte de santé bumper", BumperRest.refusal(null))
        assertEquals("Alerte de santé bumper", NavSafety.tickFailure(100, 80, null, false, 30))
    }
}
