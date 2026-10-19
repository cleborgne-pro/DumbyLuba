package fr.luba.poc.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.WifiTethering
import org.junit.Assert.*
import org.junit.Test

/** Task history labels: durations, outcome agreeing with the task, summary, exclusion, replayed distance. */
class TaskHistoryTextTest {
    private val forecast = MowingForecaster.forecast(1080.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
    private fun record(kind: TaskKind = TaskKind.MOW, outcome: MowingOutcome = MowingOutcome.COMPLETE, done: Double = 100.0,
                       recharges: Int = 0, excluded: Boolean = false, zones: List<String> = listOf("Pelouse", "Allée"),
                       active: Double = forecast.totalSeconds) =
        MowingRecord("r", "AA", startedAt = 1_760_000_000_000L, endedAt = 1_760_003_600_000L, outcome = outcome, zoneNames = zones,
            cuttingMeters = 100.0, doneMeters = done, forecast = forecast, totals = MowingTotals(activeSeconds = active),
            recharges = recharges, excluded = excluded, kind = kind)

    @Test fun `durations rounded to the minute, hours beyond 59 min`() {
        assertEquals("0 min", MowingText.duration(29.0))
        assertEquals("30 min", MowingText.duration(1_799.0))
        assertEquals("1 h 00", MowingText.duration(3_570.0))
        assertEquals("2 h 05", MowingText.duration(7_500.0))
        assertEquals("0 min 59 s", TaskText.elapsed(59_999))
        assertEquals("59 min 59 s", TaskText.elapsed(3_599_999))
        assertEquals("1 h 00 min", TaskText.elapsed(3_600_000))
        assertEquals("0 min 00 s", TaskText.elapsed(-5_000))
        assertEquals("43 %", MowingText.percent(42.6))
        assertEquals("×1,25", MowingText.factor(1.25))
    }

    @Test fun `outcome agrees with the task type`() {
        assertEquals("Terminée", TaskText.outcome(record()))
        assertEquals("Interrompue", TaskText.outcome(record(outcome = MowingOutcome.INTERRUPTED)))
        assertEquals("Terminé", TaskText.outcome(record(TaskKind.SURVEY)))
        assertEquals("Interrompu", TaskText.outcome(record(TaskKind.FOLLOW, MowingOutcome.INTERRUPTED)))
        assertEquals("En cours", TaskText.outcome(record(outcome = MowingOutcome.RUNNING)))
        assertEquals("Tonte · Pelouse, Allée", TaskText.title(record()))
        assertEquals("Relevé des signaux · Zones sans nom", TaskText.title(record(TaskKind.SURVEY, zones = emptyList())))
    }

    @Test fun `summary, deviation from estimate, recharges and part done`() {
        assertEquals("${MowingText.duration(forecast.totalSeconds)} (+0 %) · 0 recharge", MowingText.summary(record()))
        val slow = MowingText.summary(record(active = forecast.totalSeconds * 1.2, recharges = 2))
        assertTrue(slow, slow.contains("(+20 %)") && slow.endsWith("· 2 recharges"))
        val partial = MowingText.summary(record(outcome = MowingOutcome.INTERRUPTED, done = 40.0, active = forecast.totalSeconds * 0.3))
        assertTrue(partial, partial.contains("(−25 %)") && partial.endsWith("· 40 % tondus"))
        assertTrue(MowingText.summary(record(TaskKind.SURVEY, MowingOutcome.INTERRUPTED, done = 50.0)).endsWith("50 % parcourus"))
        // Too short to compare: no deviation shown.
        assertFalse(MowingText.summary(record(outcome = MowingOutcome.INTERRUPTED, done = 0.0, active = 30.0)).contains("%)"))
        val subtitle = TaskText.subtitle(record(excluded = true))
        assertTrue(subtitle, subtitle.contains(" · Terminée · ") && subtitle.endsWith(" · écartée"))
        assertFalse(TaskText.subtitle(record()).contains("écartée"))
    }

    @Test fun `icon by task and outcome`() {
        assertEquals(Icons.Rounded.PlayCircle, TaskText.icon(record(TaskKind.SURVEY, MowingOutcome.RUNNING)))
        assertEquals(Icons.Rounded.WifiTethering, TaskText.icon(record(TaskKind.SURVEY)))
        assertEquals(Icons.Rounded.Route, TaskText.icon(record(TaskKind.FOLLOW, MowingOutcome.INTERRUPTED)))
        assertEquals(Icons.Rounded.CheckCircle, TaskText.icon(record()))
        assertEquals(Icons.Rounded.PauseCircle, TaskText.icon(record(outcome = MowingOutcome.INTERRUPTED)))
    }

    @Test fun `replayed distance ignores link jumps`() {
        fun s(at: Long, x: Double) = TrackSample(at, x, 0.0, 0.0, TrackPhase.CUTTING)
        assertEquals(0.0, distance(emptyList()), 1e-9)
        assertEquals(2.0, distance(listOf(s(0, 0.0), s(1_000, 1.0), s(2_000, 2.0))), 1e-9)
        // Jump over 3 m (relocalisation) and gap over one minute: not counted.
        assertEquals(2.0, distance(listOf(s(0, 0.0), s(1_000, 1.0), s(2_000, 10.0), s(3_000, 11.0), s(70_000, 11.5))), 1e-9)
    }
}
