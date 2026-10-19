package fr.luba.poc.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionLatchTest {
    @Test fun anyNonNominalProtectionExceptTheStopLockMayAnnounceAContact() {
        assertTrue(CollisionLatch.mayAnnounceContact(20, 25L))
        assertTrue(CollisionLatch.mayAnnounceContact(11, 41L))
        assertFalse("lock after STOP", CollisionLatch.mayAnnounceContact(20, 11L))
        assertFalse("nominal", CollisionLatch.mayAnnounceContact(20, 10L))
        assertFalse("missing", CollisionLatch.mayAnnounceContact(20, null))
        assertFalse("not in ready or manual mode", CollisionLatch.mayAnnounceContact(15, 25L))
    }

    @Test fun reverseUnderTheCollisionLatchOnlyAfterAContact() {
        assertTrue(CollisionLatch.reverseAllowed(20, 10L, afterContact = false))
        assertTrue(CollisionLatch.reverseAllowed(11, 25L, afterContact = true))
        assertFalse("without a contact, the lock remains a protection", CollisionLatch.reverseAllowed(11, 25L, afterContact = false))
        assertFalse("other protection", CollisionLatch.reverseAllowed(20, 41L, afterContact = true))
        assertFalse("lock after STOP", CollisionLatch.reverseAllowed(20, 11L, afterContact = true))
        assertFalse("robot state", CollisionLatch.reverseAllowed(15, 25L, afterContact = true))
    }

    @Test fun refusalNamesTheCollisionLatchAndItsUnlock() {
        val text = SelfCheck.refusal(25L)
        assertTrue(text, text.contains("collision") && text.contains("Déverrouiller"))
        // The lock remains a protection: joystick and routes refused.
        assertFalse(SelfCheck.nominal(25L))
        assertTrue(StopUnlock.blocked(25L))
    }
}
