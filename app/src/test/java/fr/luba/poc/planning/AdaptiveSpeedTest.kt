package fr.luba.poc.planning

import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.AdaptiveSpeed
import fr.luba.poc.simulation.PathSpeedCaps
import fr.luba.poc.simulation.SimEdge
import fr.luba.poc.simulation.SmartFeatureFlags
import fr.luba.poc.simulation.SmartFeatures
import fr.luba.poc.simulation.SpeedCap
import fr.luba.poc.simulation.SpeedReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive speed ([AdaptiveSpeed], [fr.luba.poc.simulation.SpeedZones]): where and how much the set speed is lowered. */
class AdaptiveSpeedTest {
    @After fun switchesBack() = SmartFeatures.set(SmartFeatureFlags())
    private val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05

    /** Two 8 × 8 m lawns joined by a 0.9 m wide, 4 m long neck: the robot centre cannot keep 50 cm from its sides. */
    private fun dumbbell() = RobotMap(listOf(MapShape(1, 0, listOf(
        Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 3.55), Point(12.0, 3.55), Point(12.0, 0.0), Point(20.0, 0.0),
        Point(20.0, 8.0), Point(12.0, 8.0), Point(12.0, 4.45), Point(8.0, 4.45), Point(8.0, 8.0), Point(0.0, 8.0)), "Haltère")),
        true, "haltère")

    private fun edges(path: List<Point>) = path.zipWithNext().map { (a, b) -> SimEdge(a, b, false) }

    @Test fun rtkSolutionLowersTheSpeedUnlessFix() {
        assertEquals(100, AdaptiveSpeed.rtkPercent(4, 500))
        assertEquals("not reported: unchanged", 100, AdaptiveSpeed.rtkPercent(null, null))
        assertEquals(AdaptiveSpeed.RTK_FLOAT_PERCENT, AdaptiveSpeed.rtkPercent(5, 500))
        assertEquals(AdaptiveSpeed.RTK_LOST_PERCENT, AdaptiveSpeed.rtkPercent(1, 500))
        assertEquals("FIX too old", AdaptiveSpeed.RTK_LOST_PERCENT, AdaptiveSpeed.rtkPercent(4, AdaptiveSpeed.RTK_STALE_MS + 1))
    }

    @Test fun switchedOffTheSetSpeedApplies() {
        val path = listOf(SimEdge(Point(0.0, 0.0), Point(4.0, 0.0), false))
        val caps = listOf(SpeedCap(0.0, 1.0, 50))
        val float = Telemetry(rtkStatus = 5, lastRtkAt = 1_000)
        assertEquals(200, AdaptiveSpeed.setLinear(80, caps, path, 0, Point(1.0, 0.0), float, 1_500))
        SmartFeatures.set(SmartFeatureFlags(adaptiveSpeed = false))
        assertEquals("no slowdown, RTK float included", 400, AdaptiveSpeed.setLinear(80, caps, path, 0, Point(1.0, 0.0), float, 1_500))
        val routes = RoutePlanning.navigation(dumbbell(), margin, emptyList())
        assertTrue("no slowed stretch", PathSpeedCaps { routes.speedZones }.of(edges(routes.between(Point(3.0, 4.0), Point(17.0, 4.0)))).isEmpty())
    }

    @Test fun limitNeverRaisesNorGoesBelowTheCreepFloor() {
        assertEquals(400, AdaptiveSpeed.linear(400, 100))
        assertEquals(240, AdaptiveSpeed.linear(400, 60))
        assertEquals("30 % setting: floor", AdaptiveSpeed.MIN_LINEAR, AdaptiveSpeed.linear(150, 50))
        assertEquals("set speed already under the floor: kept", 80, AdaptiveSpeed.linear(80, 50))
        // Simulation: same rule in m/s (floor 100 units ≈ 0.095 m/s).
        assertEquals(0.18, AdaptiveSpeed.speedMps(0.3, 60), 1e-9)
        assertEquals(0.095, AdaptiveSpeed.speedMps(0.144, 50), 1e-9)
    }

    @Test fun strictestStretchAtARoutePositionApplies() {
        val caps = listOf(SpeedCap(2.0, 5.5, 60), SpeedCap(5.0, 6.0, 70), SpeedCap(5.2, 5.4, 50))
        assertEquals(listOf(100, 60, 60, 50, 60, 70, 100), listOf(1.9, 2.0, 5.1, 5.3, 5.45, 5.6, 6.0).map { AdaptiveSpeed.percentAt(caps, it) })
        val path = listOf(SimEdge(Point(0.0, 0.0), Point(4.0, 0.0), false))
        assertEquals(0.25, AdaptiveSpeed.position(path, 0, Point(1.0, 0.3)), 1e-9)
    }

    @Test fun transitThroughANeckIsSlowedAndTheOpenLawnIsNot() {
        val routes = RoutePlanning.navigation(dumbbell(), margin, emptyList())
        val path = edges(routes.between(Point(3.0, 4.0), Point(17.0, 4.0)))
        val caps = routes.speedZones.caps(path)
        assertTrue("neck slowed: $caps", caps.isNotEmpty() && caps.all { it.reason == SpeedReason.NARROW })
        val slowed = AdaptiveSpeed.slowedMeters(path, caps).getValue(SpeedReason.NARROW)
        assertTrue("about the neck length, not the whole transit: $slowed m", slowed in 2.0..7.0)
        // Slower from half a metre before the neck, at the set speed again once out of it.
        fun at(x: Double) = AdaptiveSpeed.percentAt(caps, AdaptiveSpeed.position(path, path.indexOfFirst { x <= maxOf(it.a.x, it.b.x) }, Point(x, 4.0)))
        assertEquals(100, at(5.0))
        assertEquals(SpeedReason.NARROW.percent, at(10.0))
        assertEquals(100, at(15.0))
        val open = edges(routes.between(Point(2.0, 2.0), Point(6.0, 6.0)))
        assertTrue("open lawn at the set speed", routes.speedZones.caps(open).isEmpty())
    }

    @Test fun dangerBandAndIncidentAreasAreSlowedTheStrictestWins() {
        val lawn = RobotMap(listOf(MapShape(1, 0, rectangle(w = 16.0, h = 10.0), "Pelouse")), true, "pelouse")
        val incident = rectangle(4.0, 0.0, 2.0, 10.0)
        val routes = RoutePlanning.navigation(lawn, margin, emptyList(), avoidAreas = listOf(incident),
            dangerLines = listOf(listOf(Point(0.0, 0.0), Point(16.0, 0.0))))
        // Pass along the bottom edge (inside the DANGER band), crossing the incident area, then a pass in the middle.
        val bottom = SimEdge(Point(1.0, 0.6), Point(15.0, 0.6), true)
        val middle = SimEdge(Point(1.0, 5.0), Point(3.0, 5.0), true)
        val across = SimEdge(Point(3.0, 5.0), Point(8.0, 5.0), true)
        val caps = routes.speedZones.caps(listOf(bottom, middle, across))
        assertEquals(SpeedReason.DANGER.percent, AdaptiveSpeed.percentAt(caps, 0.5))
        assertEquals("middle pass at the set speed", 100, AdaptiveSpeed.percentAt(caps, 1.5))
        // Incident area from x = 4 on the last pass (3 → 8), slowing from 0.5 m before it.
        assertEquals(100, AdaptiveSpeed.percentAt(caps, 2.0))
        assertEquals(SpeedReason.INCIDENT.percent, AdaptiveSpeed.percentAt(caps, 2.15))
        assertEquals(SpeedReason.INCIDENT.percent, AdaptiveSpeed.percentAt(caps, 2.5))
        assertEquals(100, AdaptiveSpeed.percentAt(caps, 2.7))
    }
}
