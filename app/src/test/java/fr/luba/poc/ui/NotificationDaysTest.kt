package fr.luba.poc.ui

import fr.luba.poc.alerts.AlertEntry
import fr.luba.poc.alerts.AlertKind
import fr.luba.poc.alerts.Severity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class NotificationDaysTest {
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        Calendar.getInstance(Locale.FRANCE).apply { set(year, month - 1, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val now = at(2026, 10, 15, 8)
    private fun entry(at: Long) = AlertEntry(AlertKind.TEST, Severity.INFO, at, "Luba", "Test")

    @Test fun labelsTodayYesterdayThenDate() {
        assertEquals("Aujourd'hui", NotificationDays.label(at(2026, 10, 15, 0, 5), now))
        assertEquals("Hier", NotificationDays.label(at(2026, 10, 14, 23, 59), now))
        assertEquals("mardi 13 octobre", NotificationDays.label(at(2026, 10, 13, 12), now))
        assertEquals("mercredi 31 décembre 2025", NotificationDays.label(at(2025, 12, 31, 12), now))
        // January 1: the day before is December 31 of the previous year.
        assertEquals("Hier", NotificationDays.label(at(2025, 12, 31, 22), at(2026, 1, 1, 9)))
    }

    @Test fun groupsConsecutiveDaysInGivenOrder() {
        val rows = listOf(at(2026, 10, 15, 7), at(2026, 10, 15, 1), at(2026, 10, 14, 20), at(2026, 10, 12, 9)).map(::entry)
        val groups = NotificationDays.group(rows, now)
        assertEquals(listOf("Aujourd'hui", "Hier", "lundi 12 octobre"), groups.map { it.first })
        assertEquals(listOf(2, 1, 1), groups.map { it.second.size })
        assertEquals(rows, groups.flatMap { it.second })
    }

    @Test fun timeIsHoursAndMinutes() = assertEquals("07:05", NotificationDays.time(at(2026, 10, 15, 7, 5)))
}
