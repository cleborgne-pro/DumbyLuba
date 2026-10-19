package fr.luba.poc.map

import fr.luba.poc.protocol.Telemetry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class SignalLiveMapTest {
    @get:Rule val temp = TemporaryFolder()
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private val map = RobotMap(listOf(MapShape(1, 0, listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 30.0), Point(0.0, 30.0)), "Pelouse")), true, "Robot")
    private fun sample(x: Double, y: Double, at: Long, wifi: Int = -60) = SignalSample(Point(x, y), at, wifi, -50, null, 0, 24)

    @Test fun samplesAnywhereWhenNoSurveyContourIsGiven() {
        val t = Telemetry(x = 12.0, y = 7.0, lastPositionAt = 1000, wifiRssi = -70, lastConnectAt = 1000)
        assertNotNull(SignalCoverage.sample(t, 1000, null))
        assertNull(SignalCoverage.sample(t, 1000, listOf(listOf(Point(0.0, 0.0), Point(1.0, 0.0)))))
    }

    @Test fun keepsOnlyMeasuresTakenWhileTheRobotMoves() {
        val first = sample(1.0, 1.0, 10_000)
        assertTrue(SignalLiveMap.accepts(null, first))
        assertFalse("Robot not moving", SignalLiveMap.accepts(first, sample(1.05, 1.0, 20_000)))
        assertFalse("Less than one second", SignalLiveMap.accepts(first, sample(2.0, 1.0, 10_500)))
        assertTrue(SignalLiveMap.accepts(first, sample(1.4, 1.0, 11_000)))
    }

    @Test fun eachCellKeepsItsMostRecentMeasuresOnly() {
        var samples = listOf(sample(5.5, 5.5, 1, wifi = -40))
        for (i in 0 until 10) samples = SignalLiveMap.merge(samples, sample(0.1 + i * 0.05, 0.2, 100L + i, wifi = -90 + i))
        val cell = samples.filter { it.point.x < 1.0 }
        assertEquals(SignalLiveMap.PER_CELL, cell.size)
        assertEquals((104L..109L).toList(), cell.map { it.at })
        assertTrue("Other cell untouched", samples.any { it.at == 1L })
    }

    @Test fun sizeStaysBoundedByTheGardenAreaAfterHoursOfMowing() {
        var samples = emptyList<SignalSample>()
        var at = 0L
        // Three hours of repeated passes over 30 × 30 m, one reading per second.
        repeat(3 * 3600) { i ->
            val row = (i / 60) % 30; val col = i % 30
            samples = SignalLiveMap.merge(samples, sample(col + 0.5, row + 0.5, at++))
        }
        assertTrue(samples.size <= 30 * 30 * SignalLiveMap.PER_CELL)
        assertEquals(900, SignalCoverage.cells(samples, SurveySignal.WIFI).size)
    }

    @Test fun oneVersionPerDayCarryingThePreviousMeasures() {
        val morning = 1_791_878_400_000L // 2026-10-13 10:00 Paris
        val empty = SignalSurvey()
        val first = SignalLiveMap.version(empty, "robot", map, morning, "live-a", paris)
        assertTrue(first.live); assertEquals("live-a", first.sessionId); assertTrue(first.samples.isEmpty())
        val filled = first.copy(samples = listOf(sample(1.0, 1.0, morning)))
        assertSame(filled, SignalLiveMap.version(filled, "robot", map, morning + 3 * 3600_000L, "live-b", paris))
        val nextDay = SignalLiveMap.version(filled, "robot", map, morning + 24 * 3600_000L, "live-c", paris)
        assertEquals("live-c", nextDay.sessionId); assertEquals(filled.samples, nextDay.samples)
        val edited = map.copy(shapes = map.shapes + MapShape(2, 1, listOf(Point(1.0, 1.0), Point(2.0, 1.0), Point(2.0, 2.0)), "Massif"))
        val newMap = SignalLiveMap.version(filled, "robot", edited, morning + 60_000, "live-d", paris)
        assertEquals("live-d", newMap.sessionId); assertEquals(SignalCoverage.mapKey(edited), newMap.mapKey); assertEquals(filled.samples, newMap.samples)
        assertTrue(SignalLiveMap.version(filled, "autre", map, morning, "live-e", paris).samples.isEmpty())
        // Dedicated survey shown before: the first continuous version starts from its readings.
        val dedicated = SignalSurvey("robot", SignalCoverage.mapKey(map), samples = listOf(sample(3.0, 3.0, 5)), sessionId = "survey", startedAt = morning)
        assertEquals(dedicated.samples, SignalLiveMap.version(dedicated, "robot", map, morning, "live-f", paris).samples)
    }

    @Test fun storeKeepsTheLatestVersionsAndEveryDedicatedSurvey() {
        val store = SignalSurveyStore(temp.root)
        val dedicated = SignalSurvey("robot", "k", samples = listOf(sample(1.0, 1.0, 1)), sessionId = "survey-1", startedAt = 1)
        store.save(dedicated, latest = false)
        assertNull("Dedicated survey: archive only", store.read("robot"))
        for (day in 0 until 14) {
            val at = 1_000_000L + day * 86_400_000L
            store.save(SignalSurvey("robot", "k", samples = listOf(sample(day.toDouble(), 1.0, at)), sessionId = SignalLiveMap.newId(at, "abc$day"),
                startedAt = at, live = true))
        }
        assertEquals(4, store.pruneLive())
        val history = store.history()
        assertEquals(SignalLiveMap.KEEP_VERSIONS, history.count { it.live })
        assertTrue(history.any { it.sessionId == "survey-1" && !it.live })
        val latest = requireNotNull(store.read("robot"))
        assertTrue(latest.live); assertEquals(13.0, latest.samples.single().point.x, 0.0)
        assertEquals(setOf("live-1-x", "live-3-y"), SignalLiveMap.expired(listOf("survey", "live-3-y", "live-9-z", "live-1-x"), keep = 1).toSet())
    }
}
