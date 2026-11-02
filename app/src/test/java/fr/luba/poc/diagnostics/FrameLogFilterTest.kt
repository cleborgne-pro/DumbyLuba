package fr.luba.poc.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLogFilterTest {
    private val report = "SYS/TOAPP_REPORT_DATA"

    @Test fun rawFramesAreLoggedOnlyWhenTheirMessageFailsToDecode() {
        val filter = FrameLogFilter()
        filter.received(byteArrayOf(1)); filter.received(byteArrayOf(2))
        filter.decoded()
        assertTrue(filter.undecoded().isEmpty())
        filter.received(byteArrayOf(3)); filter.received(byteArrayOf(4))
        assertEquals(listOf(listOf<Byte>(3), listOf<Byte>(4)), filter.undecoded().map { it.toList() })
        assertTrue("forgotten once returned", filter.undecoded().isEmpty())
    }

    @Test fun pendingRawFramesAreBounded() {
        val filter = FrameLogFilter()
        repeat(1000) { filter.received(byteArrayOf(it.toByte())) }
        assertEquals(64, filter.undecoded().size)
    }

    @Test fun periodicReportsAreWrittenEveryTenSecondsAndUnchangedOnesEveryMinute() {
        val filter = FrameLogFilter()
        assertTrue(filter.writeDecoded(report, 0) { "a" })
        assertFalse(filter.writeDecoded(report, 9_999) { "b" })
        assertTrue(filter.writeDecoded(report, 10_000) { "b" })
        assertFalse("same text", filter.writeDecoded(report, 59_000) { "b" })
        assertTrue(filter.writeDecoded(report, 70_000) { "b" })
        // One report per second for an hour: 360 written instead of 3600.
        val written = (0 until 3600).count { filter.writeDecoded("NET/TOAPP_WIFI_IOT_STATUS", 100_000L + it * 1000L) { "rssi $it" } }
        assertEquals(360, written)
    }

    @Test fun otherMessagesAreAlwaysWritten() {
        val filter = FrameLogFilter()
        val key = FrameLogFilter.messageKey("NAV", null)
        assertEquals("NAV", key)
        assertTrue((0 until 50).all { filter.writeDecoded(key, it.toLong()) { "same" } })
        assertTrue((0 until 5).all { filter.writeDecoded(FrameLogFilter.messageKey("SYS", "TOAPP_ERR_CODE"), it.toLong()) { "e" } })
    }
}
