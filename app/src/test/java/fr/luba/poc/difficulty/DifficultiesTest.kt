package fr.luba.poc.difficulty

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.ObstacleBypass
import fr.luba.poc.simulation.SimEdge
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2

/** Difficulty log: classification, heatmap, presumed obstacles, storage and use by routes. */
class DifficultiesTest {
    private val day = 86_400_000L
    private val now = 400 * day

    private fun contact(id: String, at: Long, x: Double, y: Double, dismissed: Boolean = false) =
        DifficultyEvent(id, at, DifficultyKind.CONTACT, x - 0.5, y, obstacleX = x, obstacleY = y, contactAtMs = at, dismissed = dismissed)

    @Test fun stopReasonsAndRobotCodesAreClassified() {
        assertEquals(DifficultyKind.BLOCKED, DifficultyClassifier.fromReason("Trajet interrompu : Blocage présumé : aucune translation depuis 3 s"))
        assertEquals(DifficultyKind.BLOCKED, DifficultyClassifier.fromReason("Roues commandées sans progression : 3 cm parcourus pour 40 cm attendus — robot soulevé ou bloqué"))
        assertEquals(DifficultyKind.NO_PROGRESS, DifficultyClassifier.fromReason("Absence de progression depuis 15 s (arête 4)"))
        assertEquals(DifficultyKind.NO_PROGRESS, DifficultyClassifier.fromReason("Plage trop longue : 12 m non parcourus en 300 s"))
        assertEquals(DifficultyKind.DEVIATION, DifficultyClassifier.fromReason("Écart au trajet >30 cm (34 cm, arête 2)"))
        assertEquals(DifficultyKind.LIFT, DifficultyClassifier.fromReason("Capteur de sécurité changé pendant le mouvement"))
        assertEquals(DifficultyKind.SLIP, DifficultyClassifier.fromReason("Patinage des roues"))
        assertNull(DifficultyClassifier.fromReason("Arrêt demandé"))
        assertNull(DifficultyClassifier.fromReason("Batterie faible : retour à la base"))
        // Same reasons in English (English interface).
        assertEquals(DifficultyKind.BLOCKED, DifficultyClassifier.fromReason("Wheels commanded without progress: 3 cm travelled for 40 cm expected — robot lifted or blocked"))
        assertEquals(DifficultyKind.NO_PROGRESS, DifficultyClassifier.fromReason("No progress for 15 s (edge 4)"))
        assertEquals(DifficultyKind.DEVIATION, DifficultyClassifier.fromReason("Route deviation >30 cm (34 cm, edge 2)"))
        assertEquals(DifficultyKind.LIFT, DifficultyClassifier.fromReason("Safety sensor changed during the movement"))
        assertEquals(DifficultyKind.SLIP, DifficultyClassifier.fromReason("Wheel slip"))
        assertNull(DifficultyClassifier.fromReason("Stop requested"))
        assertNull(DifficultyClassifier.fromReason("Low battery: return to base"))
        assertEquals(DifficultyKind.SLIP, DifficultyClassifier.fromErrorCode(11134))
        assertEquals(DifficultyKind.BLOCKED, DifficultyClassifier.fromErrorCode(-1201))
        assertEquals(DifficultyKind.LIFT, DifficultyClassifier.fromErrorCode(2801))
        assertNull(DifficultyClassifier.fromErrorCode(1014))
    }

    @Test fun heatDecaysWithAgeAndDistanceAndIgnoresDismissed() {
        val fresh = DifficultyField(listOf(contact("a", now, 5.0, 5.0)), now)
        assertEquals(1.0, fresh.score(Point(5.0, 5.0)), 1e-9)
        assertTrue(fresh.score(Point(5.6, 5.0)) in 0.5..0.7)
        assertEquals(0.0, fresh.score(Point(8.0, 5.0)), 1e-9)
        val old = DifficultyField(listOf(contact("a", now - 60 * day, 5.0, 5.0)), now)
        assertEquals(0.5, old.score(Point(5.0, 5.0)), 1e-6)
        assertTrue(DifficultyField(listOf(contact("a", now, 5.0, 5.0, dismissed = true)), now).isEmpty)
        val cells = fresh.cells()
        assertTrue(cells.isNotEmpty())
        assertEquals(DifficultyKind.CONTACT, cells.maxByOrNull { it.score }!!.dominant)
        assertTrue(fresh.hotAreas().isNotEmpty())
        // An isolated route deviation (weight 0.4) makes no cell "to avoid".
        val deviation = DifficultyField(listOf(DifficultyEvent("d", now, DifficultyKind.DEVIATION, 5.0, 5.0)), now)
        assertTrue(deviation.hotAreas().isEmpty())
    }

    @Test fun recentSingleContactIsAvoidedUntilItAges() {
        // 10-14: contact at 14:28:21, resume at 14:28:35 then mowing restarted at 14:35 on the same object.
        val touched = contact("a", now - 14_000, -19.55, -13.47)
        val presumed = PresumedObstacles.obstacles(listOf(touched), emptyList(), now)
        assertEquals(1, presumed.size)
        assertEquals(-19.55, presumed[0].x, 1e-9)
        assertEquals("Obstacle touché récemment", presumed[0].label)
        assertEquals(0.5, presumed[0].exclusionRadiusM, 1e-9)
        // Rebound of the same contact: a single obstacle.
        assertEquals(1, PresumedObstacles.obstacles(listOf(touched, contact("b", now - 10_000, -19.69, -13.16)), emptyList(), now).size)
        // More than 4 h later: forgotten (unless contacts are repeated); dismissed: ignored.
        assertTrue(PresumedObstacles.obstacles(listOf(touched), emptyList(), now + PresumedObstacles.RECENT_MS).isEmpty())
        assertTrue(PresumedObstacles.obstacles(listOf(touched.copy(dismissed = true)), emptyList(), now).isEmpty())
        assertEquals(1, DifficultyUi.derive(DifficultyUi(), listOf(touched), emptyList(), now).presumed.size)
    }

    @Test fun repeatedContactsInDistinctMovesMakeAPresumedObstacle() {
        val first = contact("a", now - 2 * day, 5.0, 5.0)
        val again = contact("b", now - day, 5.3, 5.1)
        val presumed = PresumedObstacles.obstacles(listOf(first, again), emptyList(), now)
        assertEquals(1, presumed.size)
        assertEquals(5.15, presumed[0].x, 1e-9)
        assertTrue(presumed[0].id.startsWith(PresumedObstacles.ID_PREFIX))
        // Same move (rebounds 2 min apart): not yet a presumed obstacle.
        assertTrue(PresumedObstacles.obstacles(listOf(first, contact("c", first.atMs + 120_000, 5.1, 5.0)), emptyList(), now).isEmpty())
        // Already saved: nothing more.
        val known = LocalObstacle("k", ObstacleKind.TREE, 5.1, 5.0, 0.3, 0.3)
        assertTrue(PresumedObstacles.obstacles(listOf(first, again), listOf(known), now).isEmpty())
        // Contact dismissed by the user: no longer counts.
        assertTrue(PresumedObstacles.obstacles(listOf(first, again.copy(dismissed = true)), emptyList(), now).isEmpty())
        // During a route, the presumed obstacles and avoided areas from the start are kept.
        val derived = DifficultyUi.derive(DifficultyUi(), listOf(first, again), emptyList(), now)
        assertEquals(1, derived.presumed.size)
        assertTrue(derived.hotAreas.isNotEmpty())
        val frozen = DifficultyUi.derive(DifficultyUi(), listOf(first, again), emptyList(), now, planning = false)
        assertTrue(frozen.presumed.isEmpty() && frozen.hotAreas.isEmpty() && frozen.cells.isNotEmpty())
        // Distant contacts: two distinct objects, none presumed.
        assertTrue(PresumedObstacles.obstacles(listOf(first, contact("d", now - day, 8.0, 5.0)), emptyList(), now).isEmpty())
    }

    @Test fun logDeduplicatesAndJsonRoundTrips() {
        val stop = DifficultyEvent("s", now, DifficultyKind.BLOCKED, 3.0, 4.0, code = 1201, note = "Erreur robot", source = "tonte")
        val log = DifficultyLog.add(emptyList(), stop)
        assertEquals(1, DifficultyLog.add(log, stop.copy(id = "s2", atMs = now + 5_000, x = 3.5)).size)
        assertEquals(2, DifficultyLog.add(log, stop.copy(id = "s3", atMs = now + 120_000)).size)
        val events = listOf(stop, contact("c", now, 1.0, 2.0).copy(headingDeg = 90.0, side = -1, inner = true, robotId = "AA:BB"))
        val decoded = DifficultyJson.decode(DifficultyJson.encode(events))
        assertEquals(events, decoded)
        // Unknown entry skipped, not the whole log.
        val text = DifficultyJson.encode(events).replace("\"kind\":\"blocked\"", "\"kind\":\"inconnu\"")
        assertEquals(1, DifficultyJson.decode(text).size)
    }

    private fun square(): RobotMap {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(16.0, 0.0), Point(16.0, 10.0), Point(0.0, 10.0)), "Carré")
        return RobotMap(listOf(zone), true, "carré")
    }
    private val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05

    @Test fun transitsAvoidHotAreasWhenTheDetourIsReasonable() {
        val map = square()
        val start = Point(2.0, 5.0); val end = Point(14.0, 5.0)
        val plain = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        val direct = plain.between(start, end)
        val hotSpot = Point(8.0, 5.0)
        assertTrue("without heatmap, straight line", direct.zipWithNext().any { (a, b) -> Geometry.distance(hotSpot, a, b) < 0.5 })
        val field = DifficultyField(listOf(
            DifficultyEvent("b", now, DifficultyKind.BLOCKED, 8.0, 5.0),
            DifficultyEvent("s", now, DifficultyKind.SLIP, 8.0, 5.6)), now)
        val hot = field.hotAreas()
        assertTrue(hot.isNotEmpty())
        val avoiding = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true, avoidAreas = hot)
        val path = avoiding.between(start, end)
        avoiding.requirePath(path)
        assertEquals(start, path.first()); assertEquals(end, path.last())
        val factory = org.locationtech.jts.geom.GeometryFactory()
        val line = factory.createLineString(path.map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }.toTypedArray())
        hot.forEach { square ->
            val polygon = factory.createPolygon((square + square.first()).map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }.toTypedArray())
            assertFalse("difficult area bypassed: $path", polygon.intersects(line))
        }
        val length = path.zipWithNext().sumOf { (a, b) -> a.distance(b) }
        assertTrue(length <= 1.5 * 12.0 + 4.0)
        // Difficult band across the whole width: no detour possible, the usual path is kept.
        val wall = (0 until 10).map { DifficultyEvent("w$it", now, DifficultyKind.BLOCKED, 8.0, 0.5 + it) }
        val blocked = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true,
            avoidAreas = DifficultyField(wall, now).hotAreas())
        blocked.requirePath(blocked.between(start, end))
    }

    @Test fun bypassSideAvoidsKnownDifficulties() {
        val map = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        // Pass y = 5 eastwards, far from the borders; obstacle on the axis, known difficulties to the north.
        val edges = listOf(SimEdge(Point(1.5, 5.0), Point(14.5, 5.0), true), SimEdge(Point(14.5, 5.0), Point(14.5, 6.0), false))
        val obstacle = LocalObstacle("o", ObstacleKind.OTHER, 8.0, 5.0, 0.2, 0.3)
        val heading = Math.toDegrees(atan2(0.0, 1.0))
        fun side(difficulty: ((Point) -> Double)?) = ObstacleBypass.repair(routes, routes, edges, null, 0, Point(3.0, 5.0), heading,
            listOf(obstacle), listOf(map.shapes[0].points), contact = false, difficulty = difficulty).detours.first().side
        val north = DifficultyField(listOf(DifficultyEvent("n", now, DifficultyKind.BLOCKED, 8.0, 6.0)), now)
        val south = DifficultyField(listOf(DifficultyEvent("n", now, DifficultyKind.BLOCKED, 8.0, 4.0)), now)
        assertEquals("difficulty to the north: bypass on the right (south)", -1, side(north::costPerMeter))
        assertEquals("difficulty to the south: bypass on the left (north)", +1, side(south::costPerMeter))
    }

    @Test fun contactRecordedOnReceiptIsRefinedByTheBypassUnderTheSameSheet() {
        // 10-15 11:00: contact logged on the -1014, then refined by the bypass 35 s later (back-off + photo).
        val early = DifficultyEvent("early", now, DifficultyKind.CONTACT, 10.0, 5.0, code = -1014, obstacleX = 10.5, obstacleY = 5.0,
            contactAtMs = now, note = "Contact pare-chocs (-1014)")
        val events = DifficultyLog.refine(emptyList(), early)
        val bypass = DifficultyEvent("late", now + 35_000, DifficultyKind.CONTACT, 9.8, 5.0, code = -1014, obstacleX = 10.4, obstacleY = 5.1,
            contactAtMs = now, side = 1, inner = true, note = "Contact bumper (-1014)")
        val refined = DifficultyLog.refine(events, bypass)
        assertEquals(1, refined.size)
        val e = refined.single()
        assertEquals("early", e.id)
        assertEquals(now, e.atMs)
        assertEquals(10.4, e.obstacleX!!, 1e-9)
        assertEquals(1, e.side)
        assertEquals("Contact bumper (-1014)", e.note)
        // A contact dismissed in the meantime stays dismissed.
        assertTrue(DifficultyLog.refine(listOf(early.copy(dismissed = true)), bypass).single().dismissed)
    }

    @Test fun anotherContactOrAnotherKindIsAddedNotMerged() {
        val first = contact("a", now, 10.0, 5.0)
        assertEquals(2, DifficultyLog.refine(listOf(first), contact("b", now + 5 * 60_000, 10.2, 5.0)).size)
        assertEquals(2, DifficultyLog.refine(listOf(first), contact("c", now + 1_000, 14.0, 5.0)).size)
        val slip = DifficultyEvent("s", now + 1_000, DifficultyKind.SLIP, 10.0, 5.0)
        assertEquals(2, DifficultyLog.refine(listOf(first), slip).size)
        // Duplicate of another difficulty: ignored as before.
        assertEquals(2, DifficultyLog.refine(listOf(first, slip), slip.copy(id = "s2", atMs = now + 2_000)).size)
    }
}
