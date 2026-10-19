package fr.luba.poc.simulation

import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.map.*
import fr.luba.poc.planning.RoutePlanning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Resume after docking again: the station observed while charging (RTK noise, docking axis off by a few degrees)
 * replaced the base, the re-prepared route changed and the resume was refused ("Trajet re-préparé différent"), flaky
 * `RobotBleIncidentsTest` test of 2026-10-18. The save keeps the start base and the resume reuses it as long as the
 * station has not really moved.
 */
class MissionResumeBaseTest {
    @get:Rule val folder = TemporaryFolder()

    private val shapes: List<MapShape> = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    private val map = RobotMap(shapes, true, "reference-station")
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Transmise par le robot", 1.5, 152.8624890921028, 1.5)
    /** Same station observed while charging: 1.3 cm and 8° of axis difference, as when the bench docked again. */
    private val observed = MapBase(Point(base.point.x + 0.0027, base.point.y + 0.0128), "Position et axe observés en recharge",
        1.5, base.entryHeadingDegrees!! + 8.0, 1.5)
    /** Station moved by one metre. */
    private val moved = MapBase(Point(base.point.x + 1.0, base.point.y), "Transmise par le robot", 1.5, base.entryHeadingDegrees, 1.5)

    private fun config(b: MapBase) = SimConfig(speedMps = 0.3, baseEntryHeadingDegrees = b.entryHeadingDegrees, baseApproachMeters = b.approachDistanceM)

    /** Mowing of a painted zone starting from the base (passes, links, return), saved as `RobotBle.startRoute` does. */
    private fun started(savedBase: MapBase?): MissionCheckpoint {
        val strokes = listOf(PaintStroke(listOf(Point(-15.0, -4.0), Point(-10.0, -4.0), Point(-10.0, -2.0)), 1.2))
        val engine = PaintedZone.prepare(map, strokes, base.point, base, emptyList(), emptyList(), spacingM = 0.25, borders = true, speedMps = 0.3).engine
        return MissionCheckpoint("AA:BB", MissionProgress.mapKey(engine.map), MissionProgress.fingerprint(engine.edges), engine.edges.size,
            engine.preview, true, 50, 20, 90, totalMeters = MissionProgress.totalCuttingMeters(engine.edges), startedAt = 1_792_150_000_000L,
            avoidance = MissionAvoidance(emptyList(), emptyList()), bladeZones = engine.bladeZones, base = savedBase)
    }

    private fun resumedFingerprint(checkpoint: MissionCheckpoint, current: MapBase) = MissionProgress.fingerprint(
        RoutePlanning.resume(checkpoint, map, current, emptyList(), emptyList(), DifficultyUi(), config(current)).edges)

    @Test(timeout = 300_000) fun observedStationAfterRedockingKeepsTheStartPlan() {
        val saved = started(base)
        assertSame(base, saved.planningBase(observed))
        assertEquals(saved.planFingerprint, resumedFingerprint(saved, observed))
        // Older save (without base): re-preparation on the current station, as before.
        assertSame(observed, saved.copy(base = null).planningBase(observed))
    }

    @Test(timeout = 300_000) fun movedStationStillRefusesThePlan() {
        val saved = started(base)
        assertSame(moved, saved.planningBase(moved))
        // Station moved: re-preparation on the new base, refused by the geometry or by the fingerprint.
        val fingerprint = runCatching { resumedFingerprint(saved, moved) }.getOrNull()
        assertNotEquals(saved.planFingerprint, fingerprint)
    }

    @Test fun sameStationToleratesObservationDriftOnly() {
        assertTrue(MissionProgress.sameStation(base, observed))
        assertFalse(MissionProgress.sameStation(base, moved))
        assertFalse(MissionProgress.sameStation(base, base.copy(entryHeadingDegrees = base.entryHeadingDegrees!! + 20.0)))
        assertFalse(MissionProgress.sameStation(base, base.copy(entryHeadingDegrees = null)))
        // Docking axis around 0° / 360°.
        assertTrue(MissionProgress.sameStation(base.copy(entryHeadingDegrees = 355.0), base.copy(entryHeadingDegrees = 4.0)))
        assertTrue(MissionProgress.sameStation(base.copy(entryHeadingDegrees = null), base.copy(entryHeadingDegrees = null)))
    }

    @Test fun savedBaseSurvivesTheStore() {
        val checkpoint = MissionCheckpoint("AA:BB", "carte", "plan", 0, Preview(emptyList(), 0.0, 0, 0), false, 50, 20, 90,
            totalMeters = 0.0, startedAt = 1L, base = observed)
        val store = MissionCheckpointStore(folder.root)
        store.save(checkpoint)
        assertEquals(observed, store.read("AA:BB")?.base)
        store.save(checkpoint.copy(base = null))
        assertNull(store.read("AA:BB")?.base)
    }
}
