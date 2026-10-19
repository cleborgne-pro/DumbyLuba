package fr.luba.poc.mowing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class WorkWindowTest {
    private val window = WorkWindow(enabled = true, startMinutes = 9 * 60, endMinutes = 20 * 60)

    @Test fun `window disabled, work allowed at any time`() {
        val off = window.copy(enabled = false)
        listOf(LocalTime.MIDNIGHT, LocalTime.of(8, 59), LocalTime.of(21, 0)).forEach { assertTrue("$it", off.allows(it)) }
        assertNull(off.refusal(LocalTime.of(23, 0)))
    }

    @Test fun `start included, end excluded`() {
        assertFalse(window.allows(LocalTime.of(8, 59)))
        assertTrue(window.allows(LocalTime.of(9, 0)))
        assertTrue(window.allows(LocalTime.of(19, 59)))
        assertFalse(window.allows(LocalTime.of(20, 0)))
        assertFalse(window.allows(LocalTime.of(23, 30)))
    }

    @Test fun `refusal shows the window`() {
        assertNull(window.refusal(LocalTime.of(12, 0)))
        val refusal = window.refusal(LocalTime.of(21, 0))
        assertNotNull(refusal)
        refusal!!
        assertTrue(refusal, refusal.contains("09:00 – 20:00"))
    }

    @Test fun `window sanitized, 15 min steps, at least one hour, within the day`() {
        assertEquals(WorkWindow(true, 9 * 60, 20 * 60), WorkWindow(true, 9 * 60 + 7, 20 * 60 + 14).sanitized())
        assertEquals(WorkWindow(true, 600, 660), WorkWindow(true, 600, 610).sanitized())
        assertEquals(WorkWindow(true, 23 * 60, 24 * 60), WorkWindow(true, 2000, 3000).sanitized())
        assertEquals(WorkWindow(true, 0, 60), WorkWindow(true, -30, -10).sanitized())
        assertTrue(WorkWindow(true, 0, 24 * 60).allows(LocalTime.of(23, 59)))
    }
}
