package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.RoutePlanning
import org.junit.Assert.*
import org.junit.Test

/** Route exit recovered: back-off along the trail until the chassis is back inside, then link to the route. */
class DriftRecoveryTest {
    private val robot = RobotEnvelope.LUBA2
    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 10.0), Point(0.0, 10.0)), "Carré")
    private val map = RobotMap(listOf(zone), true, "carré")
    private val routes = RoutePlanning.navigation(map, robot.halfWidthM + 0.05, emptyList(), recordedBoundary = true)
    /** East-west passes 1 m apart, 1 m from the borders, connected by U-turns. */
    private val edges = (0 until 9).map { 1.0 + it }
        .flatMapIndexed { i, y -> if (i % 2 == 0) listOf(Point(1.5, y), Point(10.5, y)) else listOf(Point(10.5, y), Point(1.5, y)) }
        .zipWithNext().mapIndexed { i, (a, b) -> SimEdge(a, b, i % 2 == 0) }

    /** First position under the pass y = 1 where the chassis, at heading [heading], sticks out of the physical surface. */
    private fun outside(heading: Double): Point =
        generateSequence(0.5) { it - 0.02 }.takeWhile { it > -1.0 }.map { Point(6.0, it) }
            .first { !DriftRecovery.bodyInside(routes, it, heading) }

    @Test fun recoverableReasonsAndLimits() {
        assertTrue(DriftRecovery.recoverable("Écart au trajet >30 cm (32 cm, arête 1)"))
        assertTrue(DriftRecovery.recoverable("Enveloppe robot hors du couloir sûr"))
        assertTrue(DriftRecovery.recoverable("Dérive de 45 cm hors de la zone sûre (arête 12)"))
        assertFalse(DriftRecovery.recoverable("Dérive de 45 cm dans la zone de sécurité de la base (arête 12)"))
        assertFalse(DriftRecovery.recoverable("Blocage présumé : aucune translation depuis 3 s"))
        assertFalse(DriftRecovery.recoverable("Protection / état robot changé (état 11, protection 25)"))
        // Same reasons in English (the gateway and the pilot phone may not use the same language).
        assertTrue(DriftRecovery.recoverable("Route deviation >30 cm (32 cm, edge 1)"))
        assertTrue(DriftRecovery.recoverable("Robot envelope outside the safe corridor"))
        assertTrue(DriftRecovery.recoverable("Drift of 45 cm outside the safe zone (edge 12)"))
        assertTrue(DriftRecovery.recoverable("Local obstacle inside the robot envelope"))
        assertFalse(DriftRecovery.recoverable("Drift of 45 cm inside the base safety zone (edge 12)"))
        // Reserve of an already bypassed obstacle (2026-10-15, 09:06:41): recoverable; added obstacle: not.
        assertTrue(DriftRecovery.recoverable("Obstacle local dans l'enveloppe robot"))
        assertFalse(DriftRecovery.recoverable("Obstacle local ajouté dans l'enveloppe robot"))
        assertNull(DriftRecovery.refusal(0, 0))
        assertNotNull(DriftRecovery.refusal(DriftRecovery.MAX_RECOVERIES, 0))
        assertNotNull(DriftRecovery.refusal(1, DriftRecovery.MAX_SAME_SPOT))
        // A route exit of the tracking keeps the message and the type of a safety stop.
        val drift: IllegalStateException = RouteDrift(3, "Enveloppe robot hors du couloir sûr")
        assertEquals("Enveloppe robot hors du couloir sûr", drift.message)
    }

    @Test fun bodyOutsideTheZoneBacksOffOnItsTrailThenRejoinsThePass() {
        // Pass y = 1 eastwards; the robot drifted towards the south border in a straight line, until the nose stuck out.
        val heading = 300.0
        val position = outside(heading)
        val rad = Math.toRadians(heading)
        val trail = listOf(1.5, 1.0, 0.5, 0.0).map { d -> Point(position.x - d * kotlin.math.cos(rad), position.y - d * kotlin.math.sin(rad)) }
        val plan = DriftRecovery.plan(routes, routes, edges, null, 0, position, heading, trail)
        val back = requireNotNull(plan.backOff) { "recul attendu" }
        assertTrue("chassis back inside at the back-off point", DriftRecovery.bodyInside(routes, back, heading))
        assertTrue("straight back-off behind the robot", back.distance(position) in DriftRecovery.MIN_BACK_OFF_M..DriftRecovery.MAX_BACK_OFF_M + 1e-9)
        assertTrue("backwards", (back.x - position.x) * kotlin.math.cos(rad) + (back.y - position.y) * kotlin.math.sin(rad) < 0)
        // Blades-OFF link from the back-off point, then the pass and the rest of the route unchanged.
        assertEquals(back, plan.edges.first().a)
        val link = plan.edges.indices.takeWhile { plan.origins[it] == -1 }
        assertTrue(link.isNotEmpty())
        assertTrue(link.all { !plan.edges[it].cutting && !plan.edges[it].bladesOn })
        val joined = plan.edges.filterNot { it.escape }
        routes.requirePath(listOf(joined.first().a) + joined.map { it.b })
        assertEquals(edges.last().b, plan.edges.last().b)
        assertEquals(edges.size - 1, plan.origins.last())
        assertTrue("skipped cutting bounded", plan.skippedCuttingMeters <= DriftRecovery.ENTRY_OFFSETS_M.last() + 1e-9)
    }

    @Test fun robotStillInsideRejoinsWithoutBackingOff() {
        val position = Point(6.0, 1.32)   // 32 cm deviation, chassis inside the zone
        val plan = DriftRecovery.plan(routes, routes, edges, null, 0, position, 0.0, listOf(Point(4.0, 1.0), position))
        assertNull(plan.backOff)
        assertEquals(position, plan.edges.first().a)
        assertTrue(plan.rejoinMeters < 1.5)
    }

    @Test fun repeatedExitAtTheSameSpotRejoinsFurtherOnTheRoute() {
        val position = Point(6.0, 1.32)
        val first = DriftRecovery.plan(routes, routes, edges, null, 0, position, 0.0, listOf(position))
        val again = DriftRecovery.plan(routes, routes, edges, null, 0, position, 0.0, listOf(position), skipM = DriftRecovery.SAME_SPOT_M)
        val entry = { p: DriftPlan -> p.edges[p.origins.indexOfFirst { it >= 0 }].a }
        assertTrue(entry(again).x >= entry(first).x + DriftRecovery.SAME_SPOT_M - 0.05)
    }

    @Test fun noTrailAndBodyOutsideIsRefused() {
        val position = outside(300.0)
        val error = assertThrows(IllegalArgumentException::class.java) {
            DriftRecovery.plan(routes, routes, edges, null, 0, position, 300.0, emptyList())
        }
        assertTrue(error.message!!.contains("Recul impossible"))
    }

    /**
     * Border contact ([EdgeContactKind.LIMIT]): the collision lock only clears once the robot has moved away, so the
     * resume always starts with a back-off of at least 30 cm, even with the chassis inside the surface; without a trail
     * behind, the straight 30 cm back-off checked on the map; nothing possible behind: refused (obstacle path then).
     */
    @Test fun borderContactAlwaysBacksOffAtLeast30cm() {
        // Robot on the first pass (y = 1), heading east, chassis inside: a trajectory exit would link from here.
        val position = Point(6.0, 1.0)
        val trail = listOf(Point(1.5, 1.0), position)
        assertNull(DriftRecovery.plan(routes, routes, edges, null, 0, position, 0.0, trail).backOff)
        val plan = DriftRecovery.plan(routes, routes, edges, null, 0, position, 0.0, trail, afterContact = true)
        val back = requireNotNull(plan.backOff)
        assertTrue(position.distance(back) >= fr.luba.poc.protocol.CollisionLatch.UNLOCK_BACK_OFF_M - 1e-9)
        assertEquals(1.0, back.y, 1e-9)
        assertEquals(back, plan.edges.first().a)
        assertEquals(edges.last().b, plan.edges.last().b)
        // Trail from another direction (contact right after a pivot): straight 30 cm back-off.
        val blind = DriftRecovery.plan(routes, routes, edges, null, 0, position, 0.0, listOf(Point(6.0, 3.0), position), afterContact = true)
        assertEquals(ObstacleBypass.BLIND_BACK_OFF_M, position.distance(requireNotNull(blind.backOff)), 1e-9)
        // Border right behind the robot: no back-off, refused.
        val corner = Point(0.45, 5.0)
        assertThrows(IllegalArgumentException::class.java) {
            DriftRecovery.plan(routes, routes, edges, null, 8, corner, 0.0, listOf(Point(0.45, 3.0), corner), afterContact = true)
        }
    }
}
