package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.simulation.SimulatedRobot.Ground
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Disturbance bench: the mowing route of a garden-sized zone (same preparation as the map) played by the actual mowing
 * tracking ([ObstacleEncounterSimulation]) on disturbed ground: cross-slope drift, drift towards the border, wheel held
 * back by a branch, wet patch, wheels spinning on a branch or a root, RTK degraded under a tree, branch lying across a
 * pass. Each scenario reports how the mission ends and how far the chassis went beyond the drawn border.
 */
class DisturbanceBenchTest {
    private class Outcome(val name: String, val sim: ObstacleEncounterSimulation, val borderOverrunM: Double, val overrunWhere: String,
                          val travelledM: Double, val routeEnd: Point) {
        val finished get() = sim.phase == EncounterPhase.DONE && sim.edges.last().b == routeEnd
        override fun toString() = "%-48s %-8s %5.0f s %4d contact(s) %2d border %2d drift rec., deviation %.2f m, overrun %.2f m %s · %s"
            .format(name, sim.phase.name, sim.elapsedS, sim.contacts.size, sim.edgeContacts, sim.driftRecoveries, sim.worstDeviationM,
                borderOverrunM, overrunWhere, sim.message)
    }

    private companion object {
        /** Garden-sized L-shaped zone (a concave corner), base on its west side. */
        val zone = listOf(Point(0.0, 0.0), Point(16.0, 0.0), Point(16.0, 6.0), Point(9.0, 6.0), Point(9.0, 11.0), Point(0.0, 11.0))
        val map = RobotMap(listOf(MapShape(1, 0, zone, "Jardin")), true, "banc")
        val base = MapBase(Point(1.2, 5.5), "Placée manuellement", 1.5, 180.0, 1.5)
        val route by lazy { ObstacleEncounterSimulation.route(map, map.shapes.single(), base, emptyList(), emptyList()) }
        val border by lazy { EdgeContact.Border(route.zones) }
        /** Object far from the zone: nothing to touch. */
        val nothing = ProbeObject(ProbeShape.CIRCLE, Point(100.0, 100.0), 0.1)
    }

    /**
     * Plays [edges] (the whole route by default, or a stretch of it around a disturbance) and measures, at every
     * 200 ms, how far the chassis corners go beyond the drawn border.
     */
    private fun run(name: String, ground: ((SimulatedRobot.Motion) -> Ground)? = null, obstacle: ProbeObject = nothing,
                    outline: List<Point> = obstacle.outline(), noise: (Point) -> Double = { 0.01 },
                    pivotSlide: ((Point) -> Point)? = null, edges: List<SimEdge> = route.edges, envelopeGuard: Boolean = false): Outcome {
        val toBase = edges.last().b == route.engine.baseApproach
        val sim = ObstacleEncounterSimulation(route.engine.routes, route.physical, edges, obstacle, route.zones,
            baseApproach = route.engine.baseApproach.takeIf { toBase }, dynamics = SimulatedRobot.Params(pivotSlide = pivotSlide, ground = ground),
            outline = outline, positionNoiseM = noise, envelopeGuard = envelopeGuard)
        var overrun = 0.0; var where = ""
        while (sim.running && sim.elapsedS < 3 * 3600) {
            sim.advance(0.2)
            for (corner in sim.footprint(sim.pose)) {
                val out = -border.signedDistance(corner)
                if (out > overrun) {
                    overrun = out
                    val e = sim.edges[sim.index.coerceAtMost(sim.edges.lastIndex)]
                    where = "at (%.1f; %.1f) t=%.0f s, centre %.2f m inside, edge %d %s %.2f m".format(corner.x, corner.y, sim.elapsedS,
                        border.signedDistance(sim.pose.position), sim.index, if (e.bladesOn) "blades" else "transit", e.meters)
                }
            }
        }
        return Outcome(name, sim, overrun, where, sim.meters, edges.last().b).also {
            println("BENCH $it")
            if (!it.finished) sim.log.forEach { line -> println("BENCH   $line") }
        }
    }

    /** Longest cutting edge whose middle lies [band] metres inside the border, and that middle. */
    private fun pass(band: ClosedFloatingPointRange<Double>): Pair<Int, Point> {
        val e = route.edges
        val i = e.indices.filter { e[it].cutting && border.signedDistance(middle(e[it])) in band }.maxBy { e[it].meters }
        return i to middle(e[i])
    }
    private fun middle(e: SimEdge) = Point((e.a.x + e.b.x) / 2, (e.a.y + e.b.y) / 2)

    /** Stretch of the route around [edge]: a few edges before, a few after. */
    private fun around(edge: Int) = route.edges.subList((edge - 4).coerceAtLeast(0), (edge + 8).coerceAtMost(route.edges.size))

    private fun angle(e: SimEdge) = atan2(e.b.y - e.a.y, e.b.x - e.a.x)
    private fun moving(m: SimulatedRobot.Motion) = abs(m.speedMps) > 0.02
    /** Outward direction of the border at [p]. */
    private fun outward(p: Point): Point {
        val n = border.nearest(p); val d = n.distance(p).coerceAtLeast(1e-6)
        return if (border.signedDistance(p) >= 0) Point((n.x - p.x) / d, (n.y - p.y) / d) else Point((p.x - n.x) / d, (p.y - n.y) / d)
    }

    /** Whole route on sloping ground: cross-slope, slope towards the border, pivot slide. */
    @Test(timeout = 900000) fun slopingGroundBench() {
        val outcomes = mutableListOf<Outcome>()
        val reference = run("reference (firm flat ground)").also { outcomes += it }
        val (pass, _) = pass(1.5..100.0)
        val across = angle(route.edges[pass]) + PI / 2
        // Cross-slope: constant sideways slide while driving, perpendicular to the passes.
        for (cms in listOf(1, 3, 6)) {
            val drift = Point(cms / 100.0 * cos(across), cms / 100.0 * sin(across))
            outcomes += run("cross-slope $cms cm/s", { m -> if (moving(m)) Ground(drift = drift) else Ground() })
        }
        // Ground sloping towards the nearest border everywhere (worst case of a garden sloping outwards).
        for (cms in listOf(1, 3)) outcomes += run("slope towards the border $cms cm/s", { m ->
            if (moving(m)) outward(m.pose.position).let { Ground(drift = Point(it.x * cms / 100.0, it.y * cms / 100.0)) } else Ground() })
        outcomes += run("slope towards the border 3 cm/s + envelope", { m ->
            if (moving(m)) outward(m.pose.position).let { Ground(drift = Point(it.x * 0.03, it.y * 0.03)) } else Ground() }, envelopeGuard = true)
        // Pivot slide towards the border (2026-10-15: 20-45 cm per U-turn at the bottom of a sloping zone).
        for (cms in listOf(6, 12)) outcomes += run("pivot slide towards the border $cms cm/s", pivotSlide = { p -> outward(p).let { Point(it.x * cms / 100.0, it.y * cms / 100.0) } })
        println("BENCH route: ${route.edges.size} edges, %.0f m".format(route.edges.sumOf { it.meters }))
        outcomes.forEach { println("BENCH | $it") }
        assertTrue(reference.toString(), reference.finished)
    }

    /** Local disturbances on a pass in the middle of the zone and on the border loop. */
    @Test(timeout = 900000) fun localDisturbanceBench() {
        val outcomes = mutableListOf<Outcome>()
        // Border loop again with the projected footprint check of the actual tracking (off by default in the simulation).
        val (borderPass, borderMiddle) = pass(0.0..0.5)
        for ((yaw, seconds) in listOf(30.0 to 1.0, 60.0 to 1.5)) {
            var since = -1L
            outcomes += run("border loop + envelope: wheel held %.0f°/s for %.1f s".format(yaw, seconds), { m ->
                if (since < 0 && m.pose.position.distance(borderMiddle) < 0.3 && moving(m)) since = m.elapsedMs
                if (since >= 0 && m.elapsedMs - since < seconds * 1000) Ground(traction = 0.6, yawDegreesPerSecond = yaw) else Ground()
            }, edges = around(borderPass), envelopeGuard = true)
        }
        outcomes += run("border loop + envelope: RTK noise 5 cm under a tree", noise = { p -> if (p.distance(borderMiddle) < 3.0) 0.05 else 0.01 },
            edges = around(borderPass), envelopeGuard = true)
        for ((site, band) in listOf("interior" to 1.5..100.0, "border loop" to 0.0..0.5)) {
            val (pass, middle) = pass(band)
            val edges = around(pass)
            outcomes += run("$site: reference", edges = edges)
            // Branch catching one wheel: the chassis yaws for a moment.
            for ((yaw, seconds) in listOf(30.0 to 1.0, 60.0 to 1.5, 90.0 to 2.0)) {
                var since = -1L
                outcomes += run("$site: wheel held %.0f°/s for %.1f s".format(yaw, seconds), { m ->
                    if (since < 0 && m.pose.position.distance(middle) < 0.3 && moving(m)) since = m.elapsedMs
                    if (since >= 0 && m.elapsedMs - since < seconds * 1000) Ground(traction = 0.6, yawDegreesPerSecond = yaw) else Ground()
                }, edges = edges)
            }
            // Wet patch: wheels slip, the robot advances at a third of the commanded speed.
            outcomes += run("$site: wet patch (traction 0.3, r 1.5 m)", { m ->
                if (m.pose.position.distance(middle) < 1.5) Ground(traction = 0.3) else Ground() }, edges = edges)
            // Wheels spinning on a branch under the chassis: no bumper code, the robot stops moving on the spot, then the
            // branch lets go or not; backing off frees it.
            for (seconds in listOf(2.0, 5.0, Double.POSITIVE_INFINITY)) {
                var since = -1L
                outcomes += run("$site: " + if (seconds.isInfinite()) "stuck for good (root)" else "stuck %.0f s then free".format(seconds), { m ->
                    val near = m.pose.position.distance(middle) < 0.2
                    if (since < 0 && near) since = m.elapsedMs
                    if (near && since >= 0 && m.elapsedMs - since < seconds * 1000) Ground(traction = 0.0) else Ground()
                }, edges = edges)
            }
            // RTK degraded under a tree: published position noise in a 3 m disc.
            for (noise in listOf(0.05, 0.10, 0.25)) outcomes += run("$site: RTK noise %.0f cm under a tree".format(noise * 100), noise = { p ->
                if (p.distance(middle) < 3.0) noise else 0.01 }, edges = edges)
            // Branch lying across the pass, 2 m long, 5 cm thick, at 20° and 60° from the pass.
            for (tilt in listOf(20.0, 60.0)) {
                val h = angle(route.edges[pass]) + Math.toRadians(90.0 - tilt)
                val ux = cos(h); val uy = sin(h); val nx = -uy; val ny = ux
                val branch = listOf(1.0 to 0.025, 1.0 to -0.025, -1.0 to -0.025, -1.0 to 0.025)
                    .map { (l, t) -> Point(middle.x + l * ux + t * nx, middle.y + l * uy + t * ny) }
                outcomes += run("$site: branch across the pass at %.0f°".format(tilt), obstacle = ProbeObject(ProbeShape.SQUARE, middle, 0.1),
                    outline = branch, edges = edges)
            }
        }
        outcomes.forEach { println("BENCH | $it") }
        // Handled today: wheel briefly held, wet patch, branch freeing itself, root (estimated obstacle and bypass, like
        // actual mowing), branch across the pass. RTK noise >= 10 cm and long wheel holds on the border loop are known
        // limits (docs/DISTURBANCE_BENCH.md), reported but not asserted.
        val handled = listOf("reference", "wheel held 30°/s", "wet patch", "stuck 2 s", "stuck 5 s", "stuck for good", "branch across")
        for (o in outcomes.filter { o -> handled.any { o.name.contains(it) } }) assertTrue(o.toString(), o.finished)
        for (o in outcomes.filter { it.name.contains("stuck 5 s") || it.name.contains("stuck for good") }) {
            assertEquals(o.toString(), 1, o.sim.contacts.size)
            assertEquals("blockage estimated as an obstacle in front", 1, o.sim.discovered.size)
        }
        for (o in outcomes.filter { it.name.startsWith("interior") && handled.any { h -> it.name.contains(h) } })
            assertTrue(o.toString(), o.borderOverrunM < 0.01)
    }
}
