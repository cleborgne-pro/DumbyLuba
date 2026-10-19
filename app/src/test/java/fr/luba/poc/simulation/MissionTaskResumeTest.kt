package fr.luba.poc.simulation

import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.planning.RoutePlanning
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Resume of any coverage task after a stop or an app restart (2026-10-16): border following, trial mowing (painted
 * zone), routes with blades stopped and surveys are saved like a mowing, and the shared re-preparation
 * ([RoutePlanning.resume]) gives back exactly the start plan, on the reference map.
 */
class MissionTaskResumeTest {
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
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    private val obstacle = LocalObstacle("loin", ObstacleKind.OTHER, -19.4727, -8.8001, 0.2, 0.3)

    /** Save created at the start as `RobotBle.startRoute` does (tests: neither presumed obstacles nor avoided areas). */
    private fun checkpoint(engine: MowingSimulation, cutting: Boolean) = MissionCheckpoint("AA:BB", MissionProgress.mapKey(engine.map),
        MissionProgress.fingerprint(engine.edges), engine.edges.size, engine.preview, cutting, 50, 20, 90,
        totalMeters = MissionProgress.totalCuttingMeters(engine.edges), startedAt = 1_792_150_000_000L,
        avoidance = MissionAvoidance(emptyList(), emptyList()), bladeZones = engine.bladeZones)

    private fun resumed(checkpoint: MissionCheckpoint, registered: List<LocalObstacle>) =
        RoutePlanning.resume(checkpoint, map, base, emptyList(), registered, DifficultyUi(), SimConfig(speedMps = 0.3))

    @Test(timeout = 300_000) fun borderFollowIsSavedAndRebuiltIdentically() {
        val choice = BorderFollow.choices(map).first { it.label == "Limite · Aire de jeux" }
        val mission = BorderFollow.prepare(map, choice.shape.id, 0.20, Point(-12.0, 6.0), base, emptyList(), listOf(obstacle), 0.3)
        val saved = checkpoint(mission.engine, cutting = false)
        assertEquals(MissionTask.BORDER, saved.task)
        assertEquals("Suivi de bordure interrompu", saved.task.title)
        // Saved obstacles reserved by the border ([DockClearance.fit]) then by the navigation: same plan.
        assertEquals(saved.planFingerprint, MissionProgress.fingerprint(resumed(saved, listOf(obstacle)).edges))
        assertTrue(saved.totalMeters > 1.0)
    }

    @Test(timeout = 300_000) fun paintedTrialKeepsItsBladeZones() {
        val strokes = listOf(PaintStroke(listOf(Point(-15.0, -4.0), Point(-10.0, -4.0), Point(-10.0, -2.0)), 1.2))
        val mission = PaintedZone.prepare(map, strokes, base.point, base, emptyList(), emptyList(), spacingM = 0.25, borders = true, speedMps = 0.3)
        assertEquals(mission.zones.map { it.points }, mission.engine.bladeZones)
        val saved = checkpoint(mission.engine, cutting = true)
        assertEquals(MissionTask.TRIAL, saved.task)
        assertEquals(saved.planFingerprint, MissionProgress.fingerprint(resumed(saved, emptyList()).edges))
        // Read back from disk: blade zones included, same plan.
        val store = MissionCheckpointStore(folder.root)
        store.save(saved)
        val read = requireNotNull(store.read("AA:BB"))
        assertEquals(saved.bladeZones, read.bladeZones)
        assertEquals(MissionTask.TRIAL, read.task)
        assertEquals(saved.planFingerprint, MissionProgress.fingerprint(resumed(read, emptyList()).edges))
        // A map preparation has none: older save read without the field, preview zones.
        store.save(saved.copy(bladeZones = null))
        assertNull(store.read("AA:BB")!!.bladeZones)
    }

    @Test fun taskOfEachCoverage() {
        val zone = shapes.first { it.kind == ShapeKind.ZONE }
        val lines = Geometry.plan(zone.points, emptyList(), borders = false)
        assertEquals(MissionTask.MOW, MissionTask.of(lines, cutting = true, bladeZones = null))
        assertEquals(MissionTask.FOLLOW, MissionTask.of(lines, cutting = false, bladeZones = null))
        assertEquals(MissionTask.SURVEY, MissionTask.of(lines.copy(mode = CoverageMode.SURVEY_GRID), cutting = false, bladeZones = null))
        assertEquals(MissionTask.TRIAL, MissionTask.of(lines, cutting = true, bladeZones = listOf(zone.points)))
        assertEquals(MissionTask.BORDER, MissionTask.of(lines.copy(plannerLabel = BorderFollow.PLANNER_LABEL), cutting = true, bladeZones = null))
    }
}
