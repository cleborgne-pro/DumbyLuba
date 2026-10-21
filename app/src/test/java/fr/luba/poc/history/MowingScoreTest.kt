package fr.luba.poc.history

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.ceil

/** Mowing Score: contributions measured on the track, the task journal and the real coverage. */
class MowingScoreTest {
    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 6.0), Point(0.0, 6.0)), "Pelouse")
    private val map = RobotMap(listOf(zone), true, "test")
    private val forecast = MowingForecaster.forecast(300.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
    private fun record(resumes: Int = 0, obstacles: Int = 0) = MowingRecord("R-1", "R", startedAt = 0, outcome = MowingOutcome.COMPLETE,
        forecast = forecast, resumes = resumes, obstacles = obstacles, cuttingMeters = 170.0, doneMeters = 170.0)

    /**
     * Robot driving [points] forward at 0.1 m per second, pivoting on the spot (15° steps) at each change of direction,
     * like the tracking; [reverse]: segments driven backwards (heading kept).
     */
    private fun drive(points: List<Point>, phase: TrackPhase = TrackPhase.CUTTING, reverse: Set<Int> = emptySet()): List<TrackSample> {
        val out = mutableListOf<TrackSample>()
        var t = 0L
        var heading: Double? = null
        points.zipWithNext().forEachIndexed { index, (a, b) ->
            val back = index in reverse
            val target = Math.toDegrees(atan2(b.y - a.y, b.x - a.x)).let { if (back) it + 180 else it }
            val h0 = heading
            if (h0 == null) out += TrackSample(t, a.x, a.y, target, phase).also { t += 1_000 }
            else {
                val d = ((target - h0 + 540.0) % 360.0) - 180.0
                val steps = ceil(Math.abs(d) / 15).toInt()
                for (i in 1..steps) { out += TrackSample(t, a.x, a.y, h0 + d * i / steps, phase); t += 500 }
            }
            heading = target
            val n = ceil(a.distance(b) / 0.1).toInt().coerceAtLeast(1)
            for (i in 1..n) { out += TrackSample(t, a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n, target, phase); t += 1_000 }
        }
        return out
    }

    private fun stripes(): List<List<Point>> = (0..16).map { i ->
        val y = 0.2 + i * 0.35
        if (i % 2 == 0) listOf(Point(0.2, y), Point(9.8, y)) else listOf(Point(9.8, y), Point(0.2, y))
    }

    @Test fun plannedPivotsCountTwoPerZigzagTurnAndCorners() {
        assertEquals(32, TrackMetrics.plannedPivots(stripes()))
        // One stripe with a right-angle corner, continued without a link.
        assertEquals(2, TrackMetrics.plannedPivots(listOf(listOf(Point(0.0, 0.0), Point(2.0, 0.0), Point(2.0, 2.0)), listOf(Point(2.0, 2.0), Point(0.0, 2.0)))))
        assertEquals(0, TrackMetrics.plannedPivots(emptyList()))
    }

    @Test fun pivotsAreTurnsOnTheSpotOnly() {
        // Square of 1 m sides: three pivots of 90° on the spot.
        assertEquals(3, TrackMetrics.pivots(drive(listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0), Point(0.0, 0.0)))))
        // A wide curve (10° every 10 cm) is not a pivot.
        val curve = (0..18).map { i -> TrackSample(i * 1_000L, i * 0.1, 0.0, i * 10.0, TrackPhase.CUTTING) }
        assertEquals(0, TrackMetrics.pivots(curve))
        // Turns while charging do not count.
        assertEquals(0, TrackMetrics.pivots(drive(listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0)), TrackPhase.CHARGING)))
    }

    @Test fun reversingIsMovementAgainstTheHeading() {
        val samples = drive(listOf(Point(0.0, 0.0), Point(2.0, 0.0), Point(1.0, 0.0)), reverse = setOf(1))
        assertEquals(1.0, TrackMetrics.reverseMeters(samples), 1e-6)
        assertEquals(0.0, TrackMetrics.reverseMeters(drive(listOf(Point(0.0, 0.0), Point(2.0, 0.0)))), 1e-9)
    }

    @Test fun incidentsAreGroupedAndUserStopsLeftOut() {
        val events = listOf(
            TaskEvent(0, TaskEventType.START),
            TaskEvent(10_000, TaskEventType.LINK_LOST), TaskEvent(12_000, TaskEventType.STOP),     // one incident
            TaskEvent(100_000, TaskEventType.CONTACT), TaskEvent(101_000, TaskEventType.OBSTACLE), // one incident
            TaskEvent(500_000, TaskEventType.RECHARGE),
            TaskEvent(900_000, TaskEventType.STOP, data = mapOf("intentional" to "true")),
        )
        assertEquals(2, TrackMetrics.incidents(events))
    }

    @Test fun cleanMowingScoresHighAndEachDefectTakesPointsOff() {
        val cutting = stripes()
        val clean = TaskTrack(TrackMeta(map, cutting = cutting, zoneIds = listOf(1L)), drive(cutting.flatten()))
        val good = MowingScore.of(record(), clean, CoverageMap.of(clean))
        assertTrue(good.realCoverage)
        assertEquals(0.0, good.of(ScoreFactor.ROTATIONS).penalty, 1e-9)
        assertEquals(0.0, good.of(ScoreFactor.REVERSING).penalty, 1e-9)
        assertEquals(0.0, good.of(ScoreFactor.INCIDENTS).penalty, 1e-9)
        assertTrue("coverage ${good.of(ScoreFactor.COVERAGE)}", good.of(ScoreFactor.COVERAGE).penalty < 2.0)
        assertTrue("score ${good.total} $good", good.total >= 90)

        // Same stripes with half of them skipped, wiggles (extra pivots), a 6 m back-off and three incidents.
        val half = cutting.filterIndexed { i, _ -> i < 9 }
        val wiggles = half.flatten() + listOf(Point(0.2, 3.5), Point(0.2, 3.6), Point(0.3, 3.6), Point(0.3, 3.8), Point(0.5, 3.8), Point(0.5, 4.0),
            Point(0.7, 4.0), Point(0.7, 4.2), Point(0.9, 4.2), Point(0.9, 4.4), Point(1.1, 4.4), Point(1.1, 4.6))
        val samples = drive(wiggles + listOf(Point(7.1, 4.6), Point(1.1, 4.6)), reverse = setOf(wiggles.size - 1))
        val events = listOf(TaskEvent(1_000, TaskEventType.CONTACT), TaskEvent(200_000, TaskEventType.BLOCKED), TaskEvent(400_000, TaskEventType.LINK_LOST))
        val bad = TaskTrack(TrackMeta(map, cutting = cutting, zoneIds = listOf(1L)), samples, events)
        val worse = MowingScore.of(record().copy(outcome = MowingOutcome.INTERRUPTED, doneMeters = 85.0), bad, CoverageMap.of(bad))
        assertTrue(worse.of(ScoreFactor.COVERAGE).penalty > good.of(ScoreFactor.COVERAGE).penalty + 10)
        assertEquals(12.0, worse.of(ScoreFactor.INCIDENTS).penalty, 1e-9)
        assertTrue("reverse ${worse.of(ScoreFactor.REVERSING)}", worse.of(ScoreFactor.REVERSING).penalty > 1.0)
        assertTrue(worse.total < good.total - 20)
        assertEquals(100 - worse.contributions.sumOf { it.penalty }, worse.total.toDouble(), 0.5)
    }

    @Test fun withoutTrackTheScoreFallsBackOnTheRecord() {
        val score = MowingScore.of(record(obstacles = 2).copy(outcome = MowingOutcome.INTERRUPTED, doneMeters = 85.0), null, null)
        assertFalse(score.realCoverage)
        assertTrue(score.of(ScoreFactor.COVERAGE).estimated)
        assertEquals(20.0, score.of(ScoreFactor.COVERAGE).penalty, 1e-6)       // 50 % of the route
        assertFalse(score.of(ScoreFactor.OVERLAP).measured)
        assertFalse(score.of(ScoreFactor.ROTATIONS).measured)
        assertEquals(8.0, score.of(ScoreFactor.INCIDENTS).penalty, 1e-9)       // obstacles counted
        assertEquals(72, score.total)
    }
}
