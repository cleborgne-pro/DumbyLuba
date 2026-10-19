package fr.luba.poc.simulation

import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.planning.RoutePlanning
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Resume after a bumper contact (test of 2026-10-14, 17:31): the contact interrupting the mowing becomes a presumed
 * obstacle lying on the current stripe. The resume must prepare the start plan again, without this obstacle.
 */
class MissionResumeAvoidanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val map = RobotMap(listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0)))),true)
    private val base = MapBase(Point(2.0,2.0),"essai")
    private val preview = Geometry.plan(map.shapes.first().points,emptyList(),borders=true)
    private val startedAt = 1_791_990_725_000L

    private fun engine(obstacles: List<LocalObstacle>, avoid: List<List<Point>> = emptyList()) =
        MowingSimulation(RoutePlanning.mission(map,obstacles,emptyList(),base,avoidAreas=avoid),base.point,preview,map,SimConfig())

    private fun contact(id: String, at: Long, p: Point) =
        DifficultyEvent(id, at, DifficultyKind.CONTACT, p.x - 0.3, p.y, obstacleX = p.x, obstacleY = p.y, contactAtMs = at)

    private val original = engine(emptyList())
    private val fingerprint = MissionProgress.fingerprint(original.edges)
    /** Mowing stripe in the middle of the zone, interrupted halfway by a contact. */
    private val interrupted = original.edges.indices.first { i ->
        val e = original.edges[i]
        e.cutting && e.meters > 3.0 && Point((e.a.x+e.b.x)/2,(e.a.y+e.b.y)/2).distance(Point(6.0,6.0)) < 3.0
    }
    private val hit = original.edges[interrupted].let { e -> Point((e.a.x+e.b.x)/2,(e.a.y+e.b.y)/2) }

    private fun checkpoint(avoidance: MissionAvoidance?) = MissionCheckpoint("AA:BB", MissionProgress.mapKey(map), fingerprint,
        original.edges.size, preview, true, 40, 20, 90, interrupted, hit,
        MissionProgress.cuttingMetersBefore(original.edges,interrupted,hit), MissionProgress.totalCuttingMeters(original.edges), startedAt,
        interruption = "Trajet interrompu : Écart au recul >12 cm (13 cm)", avoidance = avoidance)

    /** Difficulties published after the interruption: the stripe contact is a presumed obstacle (less than 4 h). */
    private val afterContact = DifficultyUi.derive(DifficultyUi(), listOf(contact("c1", startedAt + 600_000, hit)), emptyList(), startedAt + 660_000)

    @Test fun contactOnTheInterruptedPassChangesTheCurrentPlanningContext() {
        assertEquals(1, afterContact.presumed.size)
        // Re-preparation with the current obstacles (0.40.1 behaviour): refused or another plan.
        val current = runCatching { MissionProgress.fingerprint(engine(afterContact.presumed, afterContact.transitAvoidance).edges) }
        assertNotEquals(fingerprint, current.getOrNull())
    }

    @Test fun savedContextRebuildsTheInterruptedPlan() {
        val saved = checkpoint(MissionAvoidance(emptyList(), emptyList()))
        val avoidance = MissionAvoidance.forResume(saved, afterContact, emptyList())
        assertTrue(avoidance.presumed.isEmpty() && avoidance.avoidAreas.isEmpty())
        val rebuilt = engine(avoidance.planningObstacles(emptyList()), avoidance.avoidAreas)
        assertEquals(fingerprint, MissionProgress.fingerprint(rebuilt.edges))
        assertFalse(MissionProgress.mowingDone(rebuilt.edges, saved.edge, saved.point))
    }

    @Test fun legacyCheckpointRecomputesTheContextAtTheStartDate() {
        // 0.40.1 save (without context): only the difficulties before the start count.
        val legacy = checkpoint(null)
        val avoidance = MissionAvoidance.forResume(legacy, afterContact, emptyList())
        assertTrue(avoidance.presumed.isEmpty())
        assertEquals(fingerprint, MissionProgress.fingerprint(engine(avoidance.planningObstacles(emptyList()), avoidance.avoidAreas).edges))
        // A contact from before the start (less than 4 h) still counts, as at the original preparation.
        val corner = Point(10.5, 10.5)
        val earlier = DifficultyUi.derive(DifficultyUi(), listOf(contact("c0", startedAt - 60_000, corner),
            contact("c1", startedAt + 600_000, hit)), emptyList(), startedAt + 660_000)
        val withEarlier = MissionAvoidance.forResume(legacy, earlier, emptyList())
        assertEquals(1, withEarlier.presumed.size)
        assertEquals(corner.x, withEarlier.presumed.single().x, 0.01)
        assertEquals(MissionAvoidance.of(DifficultyUi.derive(DifficultyUi(), listOf(contact("c0", startedAt - 60_000, corner)), emptyList(), startedAt)),
            withEarlier)
    }

    @Test fun disabledPresumedObstaclesAreNotSaved() {
        val off = afterContact.copy(avoidPresumed = false, avoidHot = false)
        assertEquals(MissionAvoidance(emptyList(), emptyList()), MissionAvoidance.of(off))
        assertEquals(afterContact.presumed, MissionAvoidance.of(afterContact).presumed)
    }

    @Test fun storeKeepsTheContextAndReadsOlderFiles() {
        val store = MissionCheckpointStore(folder.root)
        val avoidance = MissionAvoidance.of(afterContact)
        assertTrue(avoidance.presumed.isNotEmpty())
        val saved = checkpoint(avoidance)
        store.save(saved)
        assertEquals(saved, store.read("AA:BB"))
        store.save(checkpoint(null))
        assertNull(requireNotNull(store.read("AA:BB")).avoidance)
    }
}
