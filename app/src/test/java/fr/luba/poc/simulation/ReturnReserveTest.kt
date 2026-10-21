package fr.luba.poc.simulation

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.*
import org.junit.Assert.*
import org.junit.Test

/** "Never Die Outside": dynamic return threshold, its bounds, and the reference scenarios simulated with it. */
class ReturnReserveTest {
    @Test fun thresholdIsTheReturnCostPlusTheReserveNeverBelowTheLowThreshold() {
        assertEquals(20, ReturnReserve.threshold(20, 90, 4.0, 10))       // near the base: the low threshold decides
        assertEquals(33, ReturnReserve.threshold(20, 90, 22.3, 10))      // far: cost + reserve, rounded up
        assertEquals(65, ReturnReserve.threshold(20, 80, 120.0, 10))     // 15 points under the resume threshold
        assertEquals(20, ReturnReserve.threshold(20, 30, 120.0, 10))     // never below the low threshold
        assertEquals(80, ReturnReserve.threshold(20, 100, 120.0, 10))    // and within what the tracking accepts
        assertEquals(20, ReturnReserve.threshold(20, 90, Double.NaN, 10))
    }

    @Test fun reserveBoundsFollowTheThresholds() {
        assertEquals(5..20, ReturnReserve.range(20))
        assertEquals(5..30, ReturnReserve.range(60))
        assertEquals(5..10, ReturnReserve.range(10))
        assertEquals(10, ReturnReserve.sanitize(40, 10))
        assertNull(ReturnReserve.refusal(10, 20, 90))
        assertNotNull(ReturnReserve.refusal(25, 20, 90))
        assertNotNull(ReturnReserve.refusal(3, 20, 90))
        assertNotNull(ReturnReserve.refusal(10, 20, 22))
    }

    @Test fun returnDistanceUsesTheDetourOfTheRealPath() {
        assertEquals(31.5, ReturnReserve.returnMeters(20.0, 1.5, 1.5), 1e-9)
        assertEquals(1.0, ReturnReserve.detour(9.0, 10.0), 1e-9)
        assertEquals(2.0, ReturnReserve.detour(20.0, 10.0), 1e-9)
        assertEquals(ReturnReserve.MAX_DETOUR, ReturnReserve.detour(1000.0, 10.0), 1e-9)
        assertEquals(ReturnReserve.DEFAULT_DETOUR, ReturnReserve.detour(10.0, 0.0), 1e-9)
    }

    /** Long strip far from the base: at the low threshold the return alone would cost more than the battery left. */
    private fun farEngine(reserve: Double): MowingSimulation {
        val map = RobotMap(listOf(MapShape(1, 0, listOf(Point(0.0, 0.0), Point(120.0, 0.0), Point(120.0, 4.0), Point(0.0, 4.0)), "Bande")), true, "test")
        val cutting = (0..8).map { i -> val y = 0.4 + i * 0.4; if (i % 2 == 0) listOf(Point(1.0, y), Point(119.0, y)) else listOf(Point(119.0, y), Point(1.0, y)) }
        val preview = Preview(cutting, cutting.sumOf { it.zipWithNext().sumOf { (a, b) -> a.distance(b) } }, 0, 0)
        return MowingSimulation(TrajectoryPlanner(map, 0.3065, emptyList()), Point(1.0, 2.0), preview, map,
            SimConfig(autonomyMinutes = 20.0, returnPercent = 10.0, resumePercent = 90.0, chargeMinutes = 5.0, instantCharging = true,
                baseEntryHeadingDegrees = 180.0, returnReservePercent = reserve))
    }

    /** Runs [e] to its end; returns (final state, lowest battery while away from the base, batteries on arrival at the base). */
    private fun run(e: MowingSimulation, dt: Double = 1.0, maxSteps: Int = 200_000): Triple<SimState, Double, List<Double>> {
        var s = e.start(); var lowest = 100.0; val arrivals = mutableListOf<Double>()
        var steps = 0
        while (s.phase != SimPhase.COMPLETE && s.phase != SimPhase.BLOCKED && steps++ < maxSteps) {
            val before = s
            s = e.advance(s, dt)
            if (s.phase !in setOf(SimPhase.CHARGING, SimPhase.COMPLETE)) lowest = minOf(lowest, s.battery)
            if (before.phase == SimPhase.DOCKING && s.phase in setOf(SimPhase.CHARGING, SimPhase.COMPLETE)) arrivals += s.battery
        }
        return Triple(s, lowest, arrivals)
    }

    @Test fun farFromTheBaseTheRobotReturnsWithItsReserve() {
        val (s, lowest, arrivals) = run(farEngine(10.0))
        assertEquals(s.log.takeLast(5).toString(), SimPhase.COMPLETE, s.phase)
        assertTrue(s.log.toString(), s.log.any { "Réserve de retour" in it || "Return reserve" in it })
        // Every arrival at the base keeps about the reserve (estimate margin included), never an empty battery.
        assertTrue("arrivals $arrivals", arrivals.isNotEmpty() && arrivals.all { it >= 9.0 })
        assertTrue("lowest $lowest", lowest >= 9.0)
    }

    @Test fun referenceScenariosNeverEndWithAnEmptyBatteryOutsideTheBase() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true)
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE }
        assertTrue(zones.size >= 5)
        val failures = mutableListOf<String>()
        var completed = 0
        var reserveReturns = 0
        for (zone in zones) {
            val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point,
                config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
                navigation = PlanningNavigation(emptyList(), base)))
            // Short runtime and the lowest low threshold: several recharges, returns from the far end of each zone.
            for ((low, reserve) in listOf(10.0 to 5.0, 20.0 to 10.0)) {
                val e = MowingSimulation(routes, base.point, plan.preview, map, SimConfig(autonomyMinutes = 20.0, returnPercent = low, resumePercent = 90.0,
                    chargeMinutes = 5.0, instantCharging = true, baseEntryHeadingDegrees = base.entryHeadingDegrees,
                    baseApproachMeters = base.approachDistanceM, returnReservePercent = reserve))
                val (s, lowest, arrivals) = run(e, dt = 2.0)
                val name = "${zone.name.trim()} low $low reserve $reserve"
                // A zone the runtime cannot reach and come back from is refused at the base: safe, the robot stays docked.
                val refusedAtBase = s.phase == SimPhase.BLOCKED && s.position.distance(e.base) < 1e-6
                if (s.phase == SimPhase.COMPLETE) completed++
                if (s.log.any { "Réserve de retour" in it || "Return reserve" in it }) reserveReturns++
                else if (!refusedAtBase) failures += "$name: ${s.phase} at ${s.position} ${s.log.takeLast(3)}"
                if (lowest <= 0.0 || arrivals.any { it < reserve - 1.0 }) failures += "$name: lowest $lowest, arrivals $arrivals"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("completed $completed", completed >= zones.size * 2 - 2)
        // The reserve really decides some returns (far ends of the zones), not only the low threshold.
        assertTrue("reserve returns $reserveReturns", reserveReturns > 0)
        println("RESERVE reference scenarios: $completed completed of ${zones.size * 2}, $reserveReturns with a reserve return")
    }
}
