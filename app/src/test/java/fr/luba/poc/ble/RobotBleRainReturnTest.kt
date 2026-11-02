package fr.luba.poc.ble

import fr.luba.poc.simulation.RoutePhase
import fr.luba.poc.weather.GardenLocation
import fr.luba.poc.weather.RainAssessment
import fr.luba.poc.weather.RainLevel
import fr.luba.poc.weather.RainReturn
import fr.luba.poc.weather.WeatherService
import fr.luba.poc.weather.WeatherState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Return to base on confirmed rain during a mowing, wait on the base, resume once dry ([RainReturnRunner]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleRainReturnTest {
    private val bench = RobotBench()
    @After fun tearDown() { WeatherService.resetForTest(); bench.close() }

    private fun weather(level: RainLevel, rainReturn: Boolean = true) = WeatherService.setStateForTest(WeatherState(
        location = GardenLocation(48.0, 2.0, "test"), monitoring = true, rainReturn = rainReturn, assessment = RainAssessment(level, "test")))

    @Test fun `confirmed rain while mowing, return to base, then mowing resumed once dry`() {
        weather(RainLevel.DRY)
        val engine = bench.mowingUnderway()
        bench.advance(20_000)
        weather(RainLevel.RAIN)
        bench.until(5000, "rain return announced") { bench.state.status.contains("Pluie confirmée") }
        bench.until(10_000, "return") { bench.state.robotRoute.phase == RoutePhase.RETURNING }
        assertTrue(bench.state.robotRoute.status, bench.state.robotRoute.status.contains("Pluie confirmée"))
        bench.until(10 * 60_000, "mission stopped on the base") { !bench.state.robotRoute.running }
        assertTrue(bench.sim.charging)
        assertTrue(bench.state.status, bench.state.status.contains("reprise après la pluie"))
        assertNotNull("Progress kept", bench.state.missionCheckpoint)
        bench.assertNativeDockOnlyAtApproach(engine)
        // Rain goes on, then stops: no resume before 30 min on the base.
        bench.advance(10 * 60_000)
        assertFalse(bench.state.robotRoute.running)
        assertTrue(bench.state.autoResumeStatus, bench.state.autoResumeStatus.contains("attente sur la base"))
        weather(RainLevel.DRY)
        bench.advance(5 * 60_000)
        assertFalse("not before the minimum wait", bench.state.robotRoute.running)
        bench.until(RainReturn.MIN_WAIT_MS, "mowing resumed after the rain") { bench.state.robotRoute.running }
        bench.until(90_000, "cutting again") { bench.sim.bladesOn && !bench.sim.charging }
    }

    @Test fun `setting off, rain is only an alert`() {
        weather(RainLevel.DRY, rainReturn = false)
        bench.mowingUnderway()
        weather(RainLevel.RAIN, rainReturn = false)
        bench.advance(30_000)
        assertTrue(bench.state.robotRoute.running)
        assertTrue(bench.state.robotRoute.phase == RoutePhase.FOLLOWING)
    }
}
