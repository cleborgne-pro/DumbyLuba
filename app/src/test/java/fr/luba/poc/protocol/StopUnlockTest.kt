package fr.luba.poc.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopUnlockTest {
    @Test fun `attempt allowed only with the robot locked, connected, free and a fresh state`() {
        assertNull(StopUnlock.refusal(true, false, 500, 11, 0))
        assertEquals("Connexion Bluetooth requise", StopUnlock.refusal(false, false, 500, 11, 0))
        assertNotNull(StopUnlock.refusal(true, true, 500, 11, 0))
        assertNotNull(StopUnlock.refusal(true, false, null, 11, 0))
        assertNotNull(StopUnlock.refusal(true, false, 2500, 11, 0))
        assertEquals("Robot déjà déverrouillé", StopUnlock.refusal(true, false, 500, 10, 0))
        // Tests of 2026-10-14: protection 25 stayed active for 20 min with no way out.
        assertNull(StopUnlock.refusal(true, false, 500, 25, 0))
        assertNull(StopUnlock.refusal(true, false, 500, 34, 0))
        assertNotNull("self-check missing", StopUnlock.refusal(true, false, 500, null, 0))
        assertNotNull("bumper under 25", StopUnlock.refusal(true, false, 500, 25, 1))
        assertNotNull("bumper", StopUnlock.refusal(true, false, 500, 11, 1))
        assertNotNull("lift", StopUnlock.refusal(true, false, 500, 11, 4))
        assertNotNull("unknown sensors", StopUnlock.refusal(true, false, 500, 11, null))
    }

    @Test fun `blocking protection only for a present, non-nominal value`() {
        assertTrue(StopUnlock.blocked(11)); assertTrue(StopUnlock.blocked(25))
        assertFalse(StopUnlock.blocked(10)); assertFalse(StopUnlock.blocked(0)); assertFalse(StopUnlock.blocked(null))
        assertEquals(StopUnlock.PHYSICAL, StopUnlock.physical(11))
        assertEquals(StopUnlock.PROTECTION_PHYSICAL, StopUnlock.physical(25))
    }

    @Test fun `success only on a report after the write with a nominal self-check`() {
        assertTrue(StopUnlock.confirmed(1000, 1500, 200, 10))
        assertTrue(StopUnlock.confirmed(1000, 1500, 200, 0))
        assertFalse("still locked", StopUnlock.confirmed(1000, 1500, 200, 11))
        assertFalse("earlier report", StopUnlock.confirmed(1000, 900, 200, 10))
        assertFalse("write not confirmed", StopUnlock.confirmed(0, 1500, 200, 10))
        assertFalse("stale report", StopUnlock.confirmed(1000, 1500, 2500, 10))
    }

    @Test fun `commands sent without movement and allowed by the navigation policy`() {
        val commands = Commands()
        listOf(commands.stop(), commands.taskControl(4), commands.errorQuery(), commands.selfCheckInfo()).forEach {
            assertTrue(ExternalNavigationPolicy.allows(it))
        }
        val stop = fr.luba.poc.proto.LubaMsgProto.LubaMsg.parseFrom(commands.stop())
        assertEquals(0, stop.driver.todevDevmotionCtrl.setLinearSpeed)
        assertEquals(0, stop.driver.todevDevmotionCtrl.setAngularSpeed)
        val cancel = fr.luba.poc.proto.LubaMsgProto.LubaMsg.parseFrom(commands.taskControl(4))
        assertEquals(1, cancel.nav.todevTaskctrl.type)
        assertEquals(4, cancel.nav.todevTaskctrl.action)
        val info = fr.luba.poc.proto.LubaMsgProto.LubaMsg.parseFrom(commands.selfCheckInfo())
        assertTrue(info.sys.hasToDevSelfCheckInfoReq())
        assertFalse(info.hasDriver())
    }
}
