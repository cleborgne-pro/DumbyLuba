package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.RoutePlanning
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2

/**
 * Bypass of an obstacle discovered during mowing: back-off along the trail, remaining route repaired by the same
 * navigation as the preparation, borders and no-go zones respected, tracking replayed by the execution bench.
 */
class ObstacleBypassTest {
    private val robot = RobotEnvelope.LUBA2
    private val margin = robot.halfWidthM + 0.05
    private fun heading(e: SimEdge) = Math.toDegrees(atan2(e.b.y - e.a.y, e.b.x - e.a.x))
    private fun at(e: SimEdge, t: Double) = Point(e.a.x + (e.b.x - e.a.x) * t, e.a.y + (e.b.y - e.a.y) * t)

    /** Trail travelled up to [t] of edge [index] (route vertices, 6 m at most). */
    private fun trail(edges: List<SimEdge>, index: Int, t: Double): List<Point> {
        val points = mutableListOf(at(edges[index], t), edges[index].a)
        var meters = edges[index].a.distance(points[0])
        var i = index - 1
        while (i >= 0 && meters < 6.0) { points += edges[i].a; meters += edges[i].meters; i-- }
        return points.reversed()
    }

    private fun square(): Pair<RobotMap, List<SimEdge>> {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 10.0), Point(0.0, 10.0)), "Carré")
        val map = RobotMap(listOf(zone), true, "carré")
        // East-west passes 1 m apart, 1 m from the borders, connected by U-turns.
        val rows = (0 until 9).map { 1.0 + it * 1.0 }
        val points = rows.flatMapIndexed { i, y -> if (i % 2 == 0) listOf(Point(1.5, y), Point(10.5, y)) else listOf(Point(10.5, y), Point(1.5, y)) }
        val edges = points.zipWithNext().mapIndexed { i, (a, b) -> SimEdge(a, b, i % 2 == 0) }
        return map to edges
    }

    @Test fun contactOnAPassBacksOffThenRejoinsBeyondTheObstacleAndAvoidsItOnLaterPasses() {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        val index = 4 // third pass (y = 3), eastwards
        val contact = at(edges[index], 0.5)
        val obstacle = ObstacleBypass.contactObstacle("contact", contact, heading(edges[index]), 0L)
        assertEquals(contact.x + robot.frontM + 0.2, obstacle.x, 1e-9)
        val repair = ObstacleBypass.repair(routes, routes, edges, null, index, contact, heading(edges[index]), listOf(obstacle),
            listOf(map.shapes[0].points), contact = true, trail = trail(edges, index, 0.5))
        val back = requireNotNull(repair.backOff)
        assertEquals("back-off on the pass axis", contact.y, back.y, 1e-9)
        assertTrue(contact.x - back.x in ObstacleBypass.MIN_BACK_OFF_M - 1e-9..ObstacleBypass.MAX_BACK_OFF_M + 1e-9)
        assertEquals(back, repair.edges.first().a)
        assertEquals(edges.last().b, repair.edges.last().b)
        assertEquals(repair.edges.size, repair.origins.size)
        // Tracking: never inside the obstacle reserve (followPath check), bypass blades OFF first.
        val center = Point(obstacle.x, obstacle.y)
        repair.edges.forEach { assertTrue(Geometry.distance(center, it.a, it.b) >= ObstacleBypass.keepOutM(obstacle) - 1e-6) }
        assertFalse(repair.edges.first().bladesOn)
        // The following passes crossing the obstacle again (y = 4) are repaired too; the rest is kept.
        assertTrue(repair.bypasses >= 2)
        assertTrue(repair.skippedCuttingMeters > 0 && repair.skippedCuttingMeters < 2 * 2 * ObstacleBypass.clipRadiusM(obstacle) + 0.5)
        assertTrue(repair.edges.any { it.cutting && it.a.y == 8.0 })
        routes.requirePath(listOf(repair.edges.first().a) + repair.edges.map { it.b })
        // The bench replays the repaired route with the actual mowing loop.
        val run = RouteExecutionModel.drive(repair.edges, RouteExecutionModel.Robot(), 30)
        assertTrue(run.toString(), run.worst <= 0.27 && run.blocked == 0)
    }

    @Test fun backOffStopsBeforeANoGoBehindTheRobotAndNeedsATrail() {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 10.0), Point(0.0, 10.0)), "Carré")
        val noGo = MapShape(2, 1, listOf(Point(3.0, 4.0), Point(4.2, 4.0), Point(4.2, 6.0), Point(3.0, 6.0)), "Exclusion")
        val map = RobotMap(listOf(zone, noGo), true)
        val physical = RoutePlanning.navigation(map, margin, listOf(noGo.points), recordedBoundary = true)
        // Robot facing east, rear 0.5 m from the exclusion (centre trail: reduced forbidden area): the back-off stops before the chassis reaches it.
        val position = Point(4.2 + robot.rearM + 0.5, 5.0)
        val trail = listOf(Point(1.0, 5.0), position)
        val candidates = ObstacleBypass.backOffCandidates(physical, position, 0.0, trail)
        assertTrue(candidates.isNotEmpty())
        assertTrue("stop before the exclusion", candidates.size < 8)
        val next = Point(candidates.last().x - 0.1, 5.0)
        assertNotNull(physical.motionCollision(RobotPose(Point(position.x - 0.05, 5.0), 0.0), RobotPose(next, 0.0)))
        assertTrue("without trail, no back-off", ObstacleBypass.backOffCandidates(physical, position, 0.0, emptyList()).isEmpty())
        // Trail from another direction: the back-off does not leave the travelled path.
        assertTrue(ObstacleBypass.backOffCandidates(physical, position, 0.0, listOf(Point(position.x, 9.0), position)).isEmpty())
    }

    /**
     * 2026-10-15, 17:33 and 17:34: contact just after a pivot, the trail comes from another direction; no back-off "along
     * the trail" existed and the mowing stopped ("Recul impossible"). Straight 30 cm back-off checked on the map.
     */
    @Test fun contactRightAfterAPivotBacksOffStraightWithoutTrail() {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        val index = 4
        val contact = at(edges[index], 0.5)
        val obstacle = ObstacleBypass.contactObstacle("pivot", contact, heading(edges[index]), 0L)
        // The robot came from the south and has just pivoted east: trail perpendicular to the heading.
        val trail = listOf(Point(contact.x, contact.y - 2.0), contact)
        assertTrue(ObstacleBypass.backOffCandidates(routes, contact, heading(edges[index]), trail).isEmpty())
        val repair = ObstacleBypass.repair(routes, routes, edges, null, index, contact, heading(edges[index]), listOf(obstacle),
            listOf(map.shapes[0].points), contact = true, trail = trail)
        val back = requireNotNull(repair.backOff)
        assertEquals(contact.y, back.y, 1e-9)
        assertEquals(ObstacleBypass.BLIND_BACK_OFF_M, contact.x - back.x, 1e-9)
        assertEquals(back, repair.edges.first().a)
    }

    /** Straight back-off refused if the chassis would leave the zone (border behind the robot). */
    @Test fun straightBackOffStaysInsideTheZone() {
        val (map, _) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        // Robot right at the west border, nose facing east: behind it, the zone limit.
        val position = Point(0.45, 5.0)
        assertTrue(ObstacleBypass.backOffCandidates(routes, position, 0.0, ObstacleBypass.straightBehind(position, 0.0),
            ObstacleBypass.BLIND_BACK_OFF_M).isEmpty())
    }

    @Test fun obstacleAddedAheadIsBypassedWithoutBackOff() {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        val obstacle = LocalObstacle("ajout", ObstacleKind.TREE, 6.0, 5.0, 0.3, 0.3)
        val position = at(edges[2], 0.2)
        val repair = ObstacleBypass.repair(routes, routes, edges, null, 2, position, heading(edges[2]), listOf(obstacle),
            listOf(map.shapes[0].points), contact = false)
        assertNull(repair.backOff)
        assertEquals(position, repair.edges.first().a)
        val center = Point(obstacle.x, obstacle.y)
        repair.edges.forEach { assertTrue(Geometry.distance(center, it.a, it.b) >= ObstacleBypass.keepOutM(obstacle) - 1e-6) }
        // Links between passes of the same zone: blades kept (no stop at each bypass).
        assertTrue(repair.edges.filter { !it.cutting }.any { it.bladesOn })
        assertTrue(repair.origins.filter { it >= 0 }.zipWithNext().all { (a, b) -> a <= b })
    }

    /**
     * Contact during a move without mowing (L-shaped transit to the destination): no return to the line just behind
     * the obstacle, shortest direct link to the destination, arrival heading kept.
     */
    @Test fun contactDuringATransitGoesStraightToTheDestination() {
        val (map, _) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        val corner = Point(10.5, 8.5); val destination = Point(2.0, 8.5)
        val edges = listOf(SimEdge(Point(1.5, 1.0), Point(10.5, 1.0), true),
            SimEdge(Point(10.5, 1.0), corner, false), SimEdge(corner, destination, false))
        val contact = Point(10.5, 4.0)
        val obstacle = ObstacleBypass.contactObstacle("transit", contact, 90.0, 0L)
        val trail = listOf(Point(1.5, 1.0), Point(10.5, 1.0), contact)
        val repair = ObstacleBypass.repair(routes, routes, edges, null, 1, contact, 90.0, listOf(obstacle),
            listOf(map.shapes[0].points), contact = true, trail = trail)
        val back = requireNotNull(repair.backOff)
        val path = listOf(repair.edges.first().a) + repair.edges.map { it.b }
        val meters = repair.edges.sumOf { it.meters }
        val planned = back.distance(corner) + corner.distance(destination)
        println("TRANSIT : %.1f m au lieu de %.1f m par l'angle, %d raccord(s)".format(meters, planned, repair.bypasses))
        assertEquals(destination, repair.edges.last().b)
        // Arrival along the axis of the last prepared edge (westwards).
        assertEquals(180.0, Math.abs(heading(repair.edges.last())), 1.0)
        // Neither a return to the axis behind the obstacle, nor going through the corner of the prepared transit.
        assertTrue(path.none { it.distance(corner) < 1.5 })
        assertTrue(path.none { kotlin.math.abs(it.x - 10.5) < 0.05 && it.y > obstacle.y })
        assertTrue("%.1f m".format(meters), meters < planned - 3.0)
        val center = Point(obstacle.x, obstacle.y)
        repair.edges.forEach { assertTrue(Geometry.distance(center, it.a, it.b) >= ObstacleBypass.keepOutM(obstacle) - 1e-6) }
        assertTrue(repair.edges.none { it.cutting || it.bladesOn })
        routes.requirePath(path)
    }

    /**
     * Most favourable side: obstacle on a pass near a zone border, the detour goes on the inside (opposite to the
     * border), as close as possible to the obstacle and not on the 1.2 m comfort ring.
     */
    @Test fun tightDetourTakesTheInnerSideNearAZoneBorder() {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        // Obstacle slightly on the inner side of the pass: the outside would be shorter but runs along the border.
        // Pass y = 8 (westwards, north border at 10 m) then pass y = 2 (eastwards, south border at 0 m).
        for ((index, obstacleY) in listOf(14 to 7.85, 2 to 2.15)) {
            val obstacle = LocalObstacle("bord", ObstacleKind.TREE, 6.0, obstacleY, 0.2, 0.3)
            val position = at(edges[index], 0.2)
            val repair = ObstacleBypass.repair(routes, routes, edges, null, index, position, heading(edges[index]),
                listOf(obstacle), listOf(map.shapes[0].points), contact = false)
            val center = Point(obstacle.x, obstacle.y)
            val keepOut = ObstacleBypass.keepOutM(obstacle)
            val detour = repair.detours.first()
            assertEquals(center, detour.center)
            assertTrue("inner side: $detour", detour.inner)
            // Tightest radius keeping the tracking tolerance ([TrajectoryPlanner.FOLLOW_TOLERANCE_M]) to the reserve.
            assertTrue("tight radius: $detour", detour.radiusM <= keepOut + 0.26)
            // The first link after the interrupted pass stays on the inner side of the zone.
            val firstLink = repair.edges.indices.first { repair.origins[it] == -1 }
            val link = repair.edges.drop(firstLink).takeWhile { repair.origins[repair.edges.indexOf(it)] == -1 }
            val arc = link.dropLast(1).map { it.b } // without the return onto the pass
            assertTrue(arc.size >= 3)
            val inner = if (obstacleY > 5) arc.all { it.y <= obstacleY + 1e-6 } else arc.all { it.y >= obstacleY - 1e-6 }
            assertTrue("detour towards the inside: $arc", inner)
            assertTrue("tight detour", link.all { it.b.distance(center) <= keepOut + 0.6 })
            repair.edges.forEach { assertTrue(Geometry.distance(center, it.a, it.b) >= keepOut - 1e-6) }
            routes.requirePath(listOf(repair.edges.first().a) + repair.edges.map { it.b })
            val run = RouteExecutionModel.drive(repair.edges, RouteExecutionModel.Robot(), 30)
            assertTrue(run.toString(), run.worst <= 0.27 && run.blocked == 0)
        }
    }

    @Test fun arcAroundPassesOnTheRequestedSideOutsideTheRadius() {
        val c = Point(0.0, 0.0)
        val from = Point(-1.5, 0.0); val to = Point(1.5, 0.0)
        // Eastwards: left = north, right = south.
        val left = requireNotNull(ObstacleBypass.arcAround(from, to, c, 1.0, +1))
        val right = requireNotNull(ObstacleBypass.arcAround(from, to, c, 1.0, -1))
        assertTrue(left.drop(1).dropLast(1).all { it.y > 0 })
        assertTrue(right.drop(1).dropLast(1).all { it.y < 0 })
        for (path in listOf(left, right)) path.zipWithNext().forEach { (a, b) -> assertTrue(Geometry.distance(c, a, b) >= 1.0 - 1e-6) }
        assertNull("end inside the radius", ObstacleBypass.arcAround(Point(-0.9, 0.0), to, c, 1.0, +1))
    }

    @Test fun endOfRouteInsideTheReserveIsRefused() {
        val (map, edges) = square()
        val routes = RoutePlanning.navigation(map, margin, emptyList(), recordedBoundary = true)
        val end = edges.last().b
        val obstacle = LocalObstacle("fin", ObstacleKind.OTHER, end.x + 0.3, end.y, 0.2, 0.3)
        assertThrows(IllegalArgumentException::class.java) {
            ObstacleBypass.repair(routes, routes, edges, null, 0, edges[0].a, heading(edges[0]), listOf(obstacle),
                listOf(map.shapes[0].points), contact = false)
        }
    }

    /** Reference map, all zones: contact in the middle of the longest pass of the central third. */
    @Test(timeout = 600000) fun referenceZonesBypassAContactOnTheirLongestPass() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val noGo = map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val routes = RoutePlanning.navigation(map, margin, noGo, emptyList(), base = base, recordedBoundary = true)
        val physical = RoutePlanning.navigation(map, margin, noGo, recordedBoundary = true)
        val failures = mutableListOf<String>()
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE }
        assertTrue(zones.size >= 7)
        for (zone in zones) {
            val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone)
            val third = edges.size / 3
            val index = (third until 2 * third).filter { edges[it].cutting }.maxByOrNull { edges[it].meters } ?: continue
            val e = edges[index]
            val contact = at(e, 0.5)
            val obstacle = ObstacleBypass.contactObstacle("c", contact, heading(e), 0L)
            val repair = try {
                ObstacleBypass.repair(routes, physical, edges, null, index, contact, heading(e), listOf(obstacle),
                    listOf(zone.points), contact = true, trail = trail(edges, index, 0.5))
            } catch (error: IllegalArgumentException) { failures += "${zone.name} : ${error.message}"; continue }
            val center = Point(obstacle.x, obstacle.y)
            val near = repair.edges.minOf { Geometry.distance(center, it.a, it.b) }
            if (near < ObstacleBypass.keepOutM(obstacle) - 1e-6) failures += "${zone.name}: passage at %.2f m".format(near)
            if (repair.edges.last().b != approach) failures += "${zone.name} : fin hors du point d'approche"
            // Borders, no-go zones and base zone: same checks as the preparation.
            if (!repair.routes.pathSafe(listOf(repair.edges.first { !it.escape }.a) + repair.edges.filterNot { it.escape }.map { it.b }))
                failures += "${zone.name}: repaired route outside the safe surface"
            if (repair.routes.baseZoneViolation(repair.edges.map { it.a } + repair.edges.last().b) != null) failures += "${zone.name} : zone de la base"
            val run = RouteExecutionModel.drive(repair.edges, RouteExecutionModel.Robot(), 30, approach)
            println("BYPASS ${zone.name}: back-off %.2f m, %d bypasses (%.1f m), %.1f m of cutting abandoned, detours %s; %s".format(
                contact.distance(requireNotNull(repair.backOff)), repair.bypasses, repair.bypassMeters, repair.skippedCuttingMeters,
                repair.detours.joinToString(" ") { "%s%.2f/%.1fm/%s".format(if (it.side > 0) "G" else if (it.side < 0) "D" else "=", it.radiusM, it.borderClearanceM, if (it.bladesOn) "L" else "-") },
                run))
            if (run.worst > 0.27 || run.blocked > 0) failures += "${zone.name} : suivi $run"
            if (repair.skippedCuttingMeters > 0.1 * edges.filter { it.cutting }.sumOf { it.meters }) failures += "${zone.name}: too much cutting abandoned"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
