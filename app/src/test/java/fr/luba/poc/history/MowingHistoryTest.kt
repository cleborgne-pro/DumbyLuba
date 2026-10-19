package fr.luba.poc.history

import fr.luba.poc.map.MowingEstimate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MowingHistoryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun forecast(meters: Double = 1080.0, speed: Double = 0.3, calibration: MowingCalibration? = null) =
        MowingForecaster.forecast(meters, speed, 60.0, 30.0, 100.0, 20.0, 90.0, calibration)

    private fun record(id: String, totals: MowingTotals, outcome: MowingOutcome = MowingOutcome.COMPLETE, done: Double = 100.0,
                       recharges: Int = 0, robot: String = "AA", endedAt: Long = 2_000, excluded: Boolean = false,
                       f: MowingForecast = forecast()) =
        MowingRecord(id, robot, startedAt = endedAt - 1_000, endedAt = endedAt, outcome = outcome, zoneNames = listOf("Pelouse"),
            plannedMeters = 1080.0, cuttingMeters = 100.0, doneMeters = done, forecast = f, totals = totals, recharges = recharges,
            excluded = excluded)

    @Test fun forecastWithoutCalibrationIsTheSimplifiedEstimate() {
        val f = forecast()
        val plain = MowingEstimate.fromDistance(1080.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0)
        assertEquals(plain.totalSeconds, f.totalSeconds, 1e-6)
        assertEquals(plain.recharges, f.recharges)
        assertEquals(3600.0, f.travelSeconds, 1e-6)
        assertEquals(0, f.calibratedFrom)
    }

    @Test fun trackerSplitsActiveAndChargingTimeAndSkipsGaps() {
        val tracker = MowingTracker()
        tracker.sample(0, 80, charging = false)
        tracker.sample(10_000, 79, charging = false)   // 10 s active, 1 % used
        tracker.sample(20_000, 78, charging = true)    // interval attributed to the previous state (active)
        tracker.sample(30_000, 80, charging = true)    // 10 s charging, +2 %
        tracker.sample(130_000, 95, charging = false)  // 100 s gap: neither duration nor battery
        val t = tracker.sample(132_000, 94, charging = false)
        assertEquals(22.0, t.activeSeconds, 1e-9)
        assertEquals(10.0, t.chargingSeconds, 1e-9)
        assertEquals(3.0, t.batteryUsed, 1e-9)
        assertEquals(2.0, t.batteryCharged, 1e-9)
    }

    @Test fun trackerContinuesFromSavedTotalsAfterResume() {
        val tracker = MowingTracker(MowingTotals(activeSeconds = 100.0, batteryUsed = 4.0))
        tracker.sample(0, 50, false)
        val t = tracker.sample(2_000, 49, false)
        assertEquals(102.0, t.activeSeconds, 1e-9)
        assertEquals(5.0, t.batteryUsed, 1e-9)
    }

    @Test fun completeMowingIsComparedToTheFullEstimate() {
        val f = forecast()   // 3600 s of route, 1 recharge
        val r = record("a", MowingTotals(activeSeconds = 4320.0, chargingSeconds = f.totalSeconds - 3600.0 + 600, batteryUsed = 90.0, batteryCharged = 70.0),
            recharges = 2, f = f)
        val c = MowingComparison.of(r)
        assertEquals(f.totalSeconds, c.expectedSeconds, 1e-6)
        assertTrue(c.durationDelta!! > MowingCalibration.TOLERANCE)
        assertTrue(c.durationVerdict.startsWith("Plus longue que prévu"))
        assertEquals(1, c.rechargeDelta)
        assertEquals(1.2, c.observedTimeFactor!!, 1e-9)
        assertEquals(4320.0 / 60 / 90 * 100, c.observedAutonomyMinutes!!, 1e-9)
    }

    @Test fun interruptedMowingIsComparedToTheMownShare() {
        val f = forecast()
        val r = record("a", MowingTotals(activeSeconds = 1800.0, batteryUsed = 30.0), outcome = MowingOutcome.INTERRUPTED, done = 50.0, f = f)
        val c = MowingComparison.of(r)
        assertEquals(f.totalSeconds * 0.5, c.expectedSeconds, 1e-6)
        assertEquals(0, c.expectedRecharges)
        assertEquals(1.0, c.observedTimeFactor!!, 1e-9)
    }

    @Test fun calibrationLearnsFromRecentFinishedRecordsOnly() {
        val slow = record("slow", MowingTotals(activeSeconds = 4320.0, chargingSeconds = 2700.0, batteryUsed = 108.0, batteryCharged = 60.0), endedAt = 5_000)
        val fast = record("fast", MowingTotals(activeSeconds = 1440.0, batteryUsed = 36.0), outcome = MowingOutcome.INTERRUPTED, done = 40.0, endedAt = 4_000)
        val running = record("run", MowingTotals(activeSeconds = 9999.0, batteryUsed = 99.0), outcome = MowingOutcome.RUNNING, endedAt = 6_000)
        val excluded = record("x", MowingTotals(activeSeconds = 9999.0, batteryUsed = 10.0), excluded = true, endedAt = 7_000)
        val short = record("short", MowingTotals(activeSeconds = 60.0, batteryUsed = 1.0), endedAt = 8_000)
        val c = MowingCalibration.from(listOf(slow, fast, running, excluded, short), "AA")
        // Factors 1.2 (4320 s) and 1.0 (1440 s), weighted by duration.
        assertEquals((1.2 * 4320 + 1.0 * 1440) / 5760, c.timeFactor!!, 1e-9)
        // Pooled runtime: 5760 s for 144 % → 66.7 min.
        assertEquals(5760.0 / 60 / 144 * 100, c.autonomyMinutes!!, 1e-9)
        assertEquals(2700.0 / 60 / 60 * 100, c.chargeMinutes!!, 1e-9)
        assertEquals(2, c.records)
    }

    @Test fun calibrationPrefersTheRobotRecordsAndFallsBackToAll() {
        val other = record("o", MowingTotals(activeSeconds = 3600.0, batteryUsed = 50.0), robot = "BB")
        assertEquals(120.0, MowingCalibration.from(listOf(other), "AA").autonomyMinutes!!, 1e-9)
        val own = record("a", MowingTotals(activeSeconds = 3600.0, batteryUsed = 100.0), robot = "AA")
        assertEquals(60.0, MowingCalibration.from(listOf(other, own), "AA").autonomyMinutes!!, 1e-9)
        assertTrue(MowingCalibration.from(emptyList(), "AA").empty)
    }

    @Test fun calibratedForecastAdaptsDurationAndRecharges() {
        val base = forecast()
        val calibration = MowingCalibration(timeFactor = 1.3, autonomyMinutes = 45.0, chargeMinutes = 50.0, records = 3)
        val adapted = forecast(calibration = calibration)
        assertTrue(adapted.totalSeconds > base.totalSeconds)
        assertTrue(adapted.recharges > base.recharges)
        assertEquals(1.3, adapted.timeFactor, 1e-9)
        assertEquals(45.0, adapted.autonomyMinutes, 1e-9)
        assertEquals(3, adapted.calibratedFrom)
        // Raw time stays the route time (reference of the observed factor).
        assertEquals(base.travelSeconds, adapted.travelSeconds, 1e-9)
        assertTrue(MowingForecaster.note(calibration).startsWith("Estimation ajustée sur 3 tontes"))
        assertTrue(MowingForecaster.note(null).startsWith("Estimation simplifiée"))
    }

    @Test fun storeRoundTripsAndClosesStaleRecords() {
        val store = MowingHistoryStore(folder.root)
        assertTrue(store.read().isEmpty())
        val done = record("a", MowingTotals(1.0, 2.0, 3.0, 4.0), recharges = 1).copy(batteryStart = 90, batteryEnd = 40, obstacles = 2, resumes = 1, reason = "ok")
        val running = record("b", MowingTotals(activeSeconds = 120.0), outcome = MowingOutcome.RUNNING, endedAt = 9_000).copy(endedAt = null)
        store.save(MowingHistoryStore.upsert(listOf(done), running))
        val read = store.read()
        assertEquals(listOf("b", "a"), read.map { it.id })
        assertEquals(done, read.first { it.id == "a" })
        val closed = MowingHistoryStore.closeStale(read).first { it.id == "b" }
        assertEquals(MowingOutcome.INTERRUPTED, closed.outcome)
        assertEquals("Application fermée pendant la tonte", closed.reason)
        assertEquals(running.startedAt + 120_000, closed.endedAt)
    }

    @Test fun storeIgnoresUnreadableFile() {
        java.io.File(folder.root, "mowing-history.json").writeText("{pas du json")
        assertTrue(MowingHistoryStore(folder.root).read().isEmpty())
    }

    @Test fun summaryShowsDurationDeltaAndProgress() {
        val r = record("a", MowingTotals(activeSeconds = 1800.0), outcome = MowingOutcome.INTERRUPTED, done = 50.0)
        val text = MowingText.summary(r)
        assertTrue(text, text.startsWith("30 min"))
        assertTrue(text, text.contains("50 % tondus"))
    }

    /** Decision during mowing: shortest runtime and longest duration factor (estimate or running mowing). */
    @Test fun finishModelIsTheMostCautious() {
        // Estimate: 60 min, factor 1. Running mowing: 1,800 s active for 40 % (75 min), half done in 1,800 s
        // for 1,800 s of planned raw route (factor 1).
        val running = record("r", MowingTotals(activeSeconds = 1800.0, batteryUsed = 40.0), outcome = MowingOutcome.RUNNING, done = 50.0)
        assertEquals(60.0 to 1.0, MowingCalibration.finishModel(running))
        // Mowing slower than planned and hungrier battery: 3,600 s active, 120 % used (50 min per 100 %).
        val slow = record("s", MowingTotals(activeSeconds = 3600.0, batteryUsed = 120.0), outcome = MowingOutcome.RUNNING, done = 50.0)
        val (autonomy, factor) = MowingCalibration.finishModel(slow)
        assertEquals(50.0, autonomy, 1e-9)
        assertEquals(2.0, factor, 1e-9)
    }

    /** Surveys and runs with blades stopped: in the history, never in the mowing calibration. */
    @Test fun tasksWithoutBladesDoNotCalibrate() {
        val mow = record("m", MowingTotals(activeSeconds = 3600.0, batteryUsed = 100.0))
        val survey = record("s", MowingTotals(activeSeconds = 3600.0, batteryUsed = 20.0)).copy(kind = TaskKind.SURVEY)
        assertEquals(60.0, MowingCalibration.from(listOf(mow, survey), "AA").autonomyMinutes!!, 1e-9)
        assertTrue(MowingCalibration.from(listOf(survey), "AA").empty)
        val follow = record("f", MowingTotals(activeSeconds = 600.0), outcome = MowingOutcome.INTERRUPTED, done = 40.0).copy(kind = TaskKind.FOLLOW)
        assertTrue(MowingText.summary(follow), MowingText.summary(follow).contains("40 % parcourus"))
    }

    @Test fun taskKindSurvivesStorageAndOldRecordsAreMowings() {
        val survey = record("s", MowingTotals(activeSeconds = 60.0)).copy(kind = TaskKind.SURVEY)
        val json = MowingHistoryJson.encode(survey)
        assertEquals(survey, MowingHistoryJson.decode(org.json.JSONObject(json.toString())))
        json.remove("kind")
        assertEquals(TaskKind.MOW, MowingHistoryJson.decode(org.json.JSONObject(json.toString())).kind)
    }
}
