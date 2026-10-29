package fr.luba.poc.ble

import fr.luba.poc.protocol.Commands
import fr.luba.poc.protocol.CutHeightRange
import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Height of the manual-mode commands that must not move the deck (DumbyLuba#2). */
class HoldHeightTest {
    private fun reported(mm: Int?) = Telemetry(bladeHeightMm = mm)

    @Test fun `known reported height is the one sent`() {
        assertEquals(40, HoldHeight.mm(reported(40), 60, CutHeightRange.STANDARD))
        // H version: 85 mm reported, the 60 mm default of the session is not sent.
        assertEquals(85, HoldHeight.mm(reported(85), 60, CutHeightRange.HIGH))
        assertEquals(25, HoldHeight.mm(reported(25), 60, CutHeightRange.STANDARD))
    }

    @Test fun `unknown reported height, last requested height brought into the range`() {
        assertEquals(60, HoldHeight.mm(reported(null), 60, CutHeightRange.STANDARD))
        assertEquals(45, HoldHeight.mm(reported(null), 45, CutHeightRange.STANDARD))
        // Height requested for another robot during the session: brought into the connected range.
        assertEquals(55, HoldHeight.mm(reported(null), 30, CutHeightRange.HIGH))
        assertEquals(65, HoldHeight.mm(reported(null), 90, CutHeightRange.MINI))
    }

    @Test fun `reported height outside the range, fallback without exception`() {
        // Below the minimum of the standard version, above the maximum of the mini, outside the protocol bound.
        assertEquals(60, HoldHeight.mm(reported(20), 60, CutHeightRange.STANDARD))
        assertEquals(60, HoldHeight.mm(reported(100), 60, CutHeightRange.MINI))
        assertEquals(60, HoldHeight.mm(reported(150), 60, CutHeightRange.ANY))
        assertEquals(60, HoldHeight.mm(reported(0), 60, CutHeightRange.ANY))
        assertEquals(60, HoldHeight.mm(reported(-5), 60, CutHeightRange.STANDARD))
    }

    @Test fun `height setting in progress, its target stays the one sent`() {
        assertEquals(40, HoldHeight.mm(reported(60), 40, CutHeightRange.STANDARD, adjusting = true))
        // Deck moving towards the target: the intermediate reported height does not replace it.
        assertEquals(70, HoldHeight.mm(reported(52), 70, CutHeightRange.STANDARD, adjusting = true))
        assertEquals(35, HoldHeight.mm(reported(null), 35, CutHeightRange.STANDARD, adjusting = true))
    }

    @Test fun `every result passes the manual-mode command bound`() {
        val ranges = listOf(CutHeightRange.STANDARD, CutHeightRange.HIGH, CutHeightRange.LUBA_1, CutHeightRange.LUBA_1_HIGH,
            CutHeightRange.MINI, CutHeightRange.ANY)
        val commands = Commands()
        for (range in ranges) for (report in listOf<Int?>(null, -1, 0, 19, 20, 24, 25, 60, 65, 70, 99, 100, 101, 1000))
            for (requested in listOf(-10, 0, 20, 60, 100, 500)) for (adjusting in listOf(false, true)) {
                val mm = HoldHeight.mm(reported(report), requested, range, adjusting)
                if (!adjusting) assertEquals("$range $report $requested", true, mm in range)
                commands.manualMow(1, 0, mm)
                commands.manualBlades(false, mm)
            }
    }
}
