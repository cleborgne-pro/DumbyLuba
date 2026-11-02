package fr.luba.poc.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStartReportTest {
    @Test fun startTextsOfTheFirmwareAreRead() {
        val start = GatewayStartReport.parse("Passerelle : #12 power on, up 6 s; previous run 52 s, battery 100 % on external power")
        assertEquals(GatewayStartReport.Start(12, "power on"), start)
        assertTrue(start!!.unexpected)
        assertEquals("brownout", GatewayStartReport.parse("#3 brownout, up 1 min 4 s; previous run: no state recorded")?.reason)
        assertFalse(GatewayStartReport.parse("#4 software restart, up 9 s")!!.unexpected)
        assertNull(GatewayStartReport.parse("Passerelle : liaison reprise"))
    }

    /** Fold test of 2026-11-02: the board restarted on power cuts while the app was using it. */
    @Test fun onlyANewBootForAReasonNotAskedForIsARestart() {
        val watch = GatewayStartReport.Watch()
        assertNull("first start of this app run", watch.unexpectedRestart("#12 power on, up 4 min"))
        assertNull("same boot, link resumed", watch.unexpectedRestart("#12 power on, up 9 min"))
        assertNotNull(watch.unexpectedRestart("#13 power on, up 6 s; previous run 9 min"))
        assertNull("update announced", watch.unexpectedRestart("#14 software restart, up 8 s"))
        assertNotNull(watch.unexpectedRestart("#15 watchdog, up 3 s"))
    }
}
