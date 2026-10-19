package fr.luba.poc.map

import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.DockClearance
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/** Debug → Mowing, "Border" mode: a boundary followed at a chosen offset, prepared like real mowing. */
class BorderFollowTest {
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
    private val factory = GeometryFactory()
    private fun area(points: List<Point>) = factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
    private val noGos = shapes.filter { it.kind == ShapeKind.NO_GO }.map { area(it.points) }
    private fun choice(name: String) = BorderFollow.choices(map).single { it.label == name }

    @Test fun choicesListZonesThenNamedNoGos() {
        val choices = BorderFollow.choices(map)
        assertEquals(shapes.count { it.kind == ShapeKind.ZONE }, choices.count { !it.noGo })
        assertEquals(shapes.count { it.kind == ShapeKind.NO_GO }, choices.count { it.noGo })
        assertTrue(choices.any { it.label == "Limite · Aire de jeux" })
        // Unnamed no-go zones: numbered and attached to the zone containing them.
        assertTrue(choices.filter { it.noGo }.all { it.label.startsWith("No-go ") })
        assertTrue(choices.any { it.noGo && it.label.endsWith("· Aire de jeux") })
    }

    @Test fun tapSelectsTheNearestBorder() {
        val noGo = shapes.first { it.id == 3635643446717199663 }
        val corner = noGo.points.first()
        assertEquals(noGo.id, BorderFollow.nearest(map, Point(corner.x + 0.05, corner.y))?.shape?.id)
        assertNull(BorderFollow.nearest(map, Point(60.0, 60.0)))
    }

    @Test(timeout = 180_000) fun zoneBorderIsFollowedAtTheRequestedOffset() {
        val choice = choice("Limite · Aire de jeux")
        val start = Point(-12.0, 6.0)
        val near = BorderFollow.prepare(map, choice.shape.id, 0.10, start, base, emptyList(), emptyList(), 0.3)
        val far = BorderFollow.prepare(map, choice.shape.id, 0.40, start, base, emptyList(), emptyList(), 0.3)
        println("Aire de jeux : 10 cm → appliqué ${near.appliedOffsetM}, arrondi ${near.roundingM}, min ${near.minDistanceM}, médiane ${near.medianDistanceM}, ${near.loops.size} boucle(s)")
        println("Aire de jeux : 40 cm → appliqué ${far.appliedOffsetM}, arrondi ${far.roundingM}, min ${far.minDistanceM}, médiane ${far.medianDistanceM}, ${far.loops.size} boucle(s)")
        for ((mission, requested) in listOf(near to 0.10, far to 0.40)) {
            assertTrue(mission.appliedOffsetM >= requested - 1e-9 && mission.appliedOffsetM <= requested + BorderFollow.MAX_EXTRA_M + 1e-9)
            // Never closer than the applied offset; most of the loop holds it exactly.
            assertTrue("min ${mission.minDistanceM}", mission.minDistanceM >= mission.appliedOffsetM - 0.01)
            assertEquals(mission.appliedOffsetM, mission.medianDistanceM, 0.05)
            assertTrue(mission.loops.all { it.first().distance(it.last()) < 1e-6 })
            // Prepared like real mowing: border passes with blades running, links with blades stopped.
            val edges = mission.engine.edges
            mission.engine.routes.requirePath(listOf(edges.first().a) + edges.map { it.b })
            assertTrue(edges.filter { it.cutting }.all { it.bladesOn })
            assertTrue(edges.filterNot { it.cutting }.none { it.bladesOn })
            assertEquals(mission.meters, edges.filter { it.cutting }.sumOf { it.meters }, 1e-6)
            edges.forEach { e ->
                val segment = factory.createLineString(arrayOf(Coordinate(e.a.x, e.a.y), Coordinate(e.b.x, e.b.y)))
                assertTrue(noGos.none { it.intersects(segment) })
            }
            assertFalse(mission.preview.localOnly)
            // "Blades running" checkbox: unchecked, no cutting on the whole route; checked, cutting on the loop.
            assertFalse(fr.luba.poc.simulation.RobotRoute.cuttingAllowed(mission.preview, false))
            assertTrue(fr.luba.poc.simulation.RobotRoute.cuttingAllowed(mission.preview, true))
        }
        // Loop entry nearest to the start, not at an arbitrary anchor.
        val from = RoutePlanning.missionStart(near.engine.routes, start, docked = false, base = base.point)!!
        val entry = near.loops.first().first()
        val closest = near.loops.first().minOf { it.distance(from) }
        assertTrue("Entry at ${entry.distance(from)} m (nearest ${closest} m)", entry.distance(from) <= closest + 3.0)
    }

    @Test(timeout = 180_000) fun noGoBorderIsFollowedOutsideTheExclusion() {
        val noGo = shapes.first { it.id == 3635643446717199663 }
        val choice = BorderFollow.choices(map).single { it.shape.id == noGo.id }
        val mission = BorderFollow.prepare(map, noGo.id, 0.20, Point(-12.0, 6.0), base, emptyList(), emptyList(), 0.3)
        println("No-go : 20 cm → appliqué ${mission.appliedOffsetM}, arrondi ${mission.roundingM}, min ${mission.minDistanceM}, médiane ${mission.medianDistanceM}")
        assertEquals(1, mission.loops.size)
        assertTrue(mission.preview.borderKinds.all { it })
        val exclusion = area(noGo.points)
        val loop = area(mission.loops.single())
        assertTrue("The loop surrounds the no-go zone", loop.covers(exclusion))
        assertTrue(mission.minDistanceM >= mission.appliedOffsetM - 0.01)
        assertEquals(mission.appliedOffsetM, mission.medianDistanceM, 0.08)
        assertTrue(BorderFollow.distance(choice, mission.loops.single().first()) >= 0.19)
    }

    /**
     * Real station of the reference map, 39 cm from the Aire de jeux boundary (tests of 2026-10-13): the border loop,
     * even without offset, bypasses the base safety zone; only the docking corridor enters it.
     */
    @Test(timeout = 180_000) fun zoneBorderNextToTheStationAvoidsTheBaseZone() {
        val station = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
            .getJSONObject("station")
        val real = MapBase(Point(station.getDouble("x"), station.getDouble("y")), "Station observée", 1.5, station.getDouble("heading"), 1.5)
        val mission = BorderFollow.prepare(map, choice("Limite · Aire de jeux").shape.id, 0.0, Point(-12.0, 6.0), real, emptyList(), emptyList(), 0.3)
        val (center, approach, radius) = requireNotNull(mission.engine.routes.baseSafety)
        val nearest = mission.loops.flatMap { it.zipWithNext() }.minOf { (a, b) -> Geometry.distance(center, a, b) }
        println("Aire de jeux à 0 cm, station réelle : tour à ${"%.2f".format(nearest)} m de la station (zone de la base : $radius m)")
        assertTrue("Loop $nearest m from the station, base zone $radius m", nearest >= radius)
        val path = listOf(mission.engine.edges.first().a) + mission.engine.edges.map { it.b }
        assertNull("Route inside the base zone", mission.engine.routes.baseZoneViolation(path))
        assertTrue(mission.engine.edges.filter { it.cutting }.all { Geometry.distance(center, it.a, it.b) >= radius })
        assertTrue(approach.distance(center) > radius)
    }

    /**
     * Tests of 2026-10-14: obstacles saved from the morning contacts, one of them 82 cm from the station approach
     * point. Its 30 cm margin forbade the pivot at the approach point: every computation failed with "Axe d'entrée de
     * la base hors du passage accessible". The margin is reduced just enough near the docking corridor.
     */
    @Test(timeout = 180_000) fun obstacleLearnedInFrontOfTheStationNoLongerBlocksTheBase() {
        val station = MapBase(Point(-0.5336, 1.097), "Position et axe observés en recharge", 1.5, 153.04, 1.5)
        val front = LocalObstacle("devant", ObstacleKind.OTHER, 1.6227, 0.4706, 0.2, 0.3)
        val far = LocalObstacle("loin", ObstacleKind.OTHER, -19.4727, -8.8001, 0.2, 0.3)
        val (_, approach) = requireNotNull(DockClearance.corridor(station))
        assertEquals(0.82, approach.distance(Point(front.x, front.y)), 0.01)
        val fitted = DockClearance.fit(listOf(front, far), station)
        val margin = fitted.first().marginM
        println("Obstacle devant la station : marge ${front.marginM} → $margin m")
        assertTrue("margin $margin", margin in 0.1..0.2)
        assertEquals(front.radiusM, fitted.first().radiusM, 0.0)
        assertSame(far, fitted.last())
        assertEquals(fitted, DockClearance.fit(fitted, station))
        for (start in listOf(station.point, Point(1.08, 0.19), Point(-12.0, 6.0))) {
            val mission = BorderFollow.prepare(map, choice("Limite · Aire de jeux").shape.id, 0.1, start, station, emptyList(), listOf(front, far), 0.3)
            val path = listOf(mission.engine.edges.first().a) + mission.engine.edges.map { it.b }
            mission.engine.routes.requirePath(path)
            assertEquals(approach.x, mission.engine.baseApproach.x, 1e-6)
            // Adjusted reserve held over the whole route, and the object itself never brushed by the chassis.
            mission.engine.edges.forEach { e ->
                assertTrue(Geometry.distance(Point(front.x, front.y), e.a, e.b) >= fitted.first().exclusionRadiusM + RobotEnvelope.LUBA2.halfWidthM)
            }
            // Real tracking guards: approach point outside the pivot envelope of the adjusted obstacle.
            assertTrue(approach.distance(Point(front.x, front.y)) >= fitted.first().exclusionRadiusM + RobotEnvelope.LUBA2.turnClearanceM)
            // Start as on the robot (except at the contact point, stuck to the object: out of scope here);
            // when charging, tracking starts from the approach point after leaving the base.
            if (start == Point(1.08, 0.19)) continue
            val launch = if (start == station.point) mission.engine.baseApproach else start
            val heading = if (start == station.point) (station.entryHeadingDegrees!! + 180) % 360 else 0.0
            // Robot stopped inside the obstacle reserve (contact place): clearance checked against the physical object.
            val physical = RoutePlanning.physical(map, listOf(front, far))
            val edges = fr.luba.poc.simulation.RobotRoute.actionEdges(mission.engine, launch, heading, fr.luba.poc.simulation.RouteIntent.FOLLOW, physical)
            println("Start $start: ${edges.size} edges, escape ${edges.any { it.escape }}")
        }
        // Object placed on the approach point: explicit refusal naming the obstacle, not the base.
        val blocking = LocalObstacle("bloque", ObstacleKind.OTHER, approach.x + 0.3, approach.y, 0.2, 0.3, label = "Pot de fleurs")
        val refused = assertThrows(IllegalArgumentException::class.java) {
            BorderFollow.prepare(map, choice("Limite · Aire de jeux").shape.id, 0.1, Point(-12.0, 6.0), station, emptyList(), listOf(blocking), 0.3)
        }
        assertTrue(refused.message, refused.message!!.startsWith("Pot de fleurs à 30 cm du couloir d'accostage"))
    }

    @Test fun offsetOutOfRangeIsRefused() {
        val choice = choice("Limite · Aire de jeux")
        assertThrows(IllegalArgumentException::class.java) {
            BorderFollow.prepare(map, choice.shape.id, BorderFollow.MAX_OFFSET_M + 0.05, Point(-12.0, 6.0), base, emptyList(), emptyList(), 0.3)
        }
    }
}
