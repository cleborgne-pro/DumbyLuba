package fr.luba.poc.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic return on confirmed rain and resume once it is over ([RainReturn]), validation criteria of RAIN_WEATHER_FUSION.md. */
class RainReturnTest {
    private val minute = 60_000L

    @Test fun onlyConfirmedRainSendsTheRobotBackAndOnlyWhenTheSettingIsOn() {
        assertTrue(RainReturn.shouldReturn(true, RainLevel.RAIN))
        assertFalse("setting off: observation only", RainReturn.shouldReturn(false, RainLevel.RAIN))
        for (level in listOf(RainLevel.UNKNOWN, RainLevel.DRY, RainLevel.WATCH, RainLevel.IMMINENT, RainLevel.DRYING))
            assertFalse("$level: no return", RainReturn.shouldReturn(true, level))
    }

    @Test fun resumeWaitsForDryWeatherAndTheMinimumDelay() {
        assertEquals(RainWait.WAIT, RainReturn.resume(RainLevel.DRYING, 45 * minute, 0))
        assertEquals("dry too soon after docking", RainWait.WAIT, RainReturn.resume(RainLevel.DRY, 10 * minute, 0))
        assertEquals(RainWait.RESUME, RainReturn.resume(RainLevel.DRY, 30 * minute, 0))
        assertEquals("a new shower while waiting: still waiting", RainWait.WAIT, RainReturn.resume(RainLevel.RAIN, 50 * minute, 0))
    }

    @Test fun staleWeatherIsNeverTakenForDryWeather() {
        assertEquals(RainWait.WAIT, RainReturn.resume(RainLevel.UNKNOWN, 2 * 60 * minute, 0))
        assertEquals("but not forever", RainWait.GIVE_UP, RainReturn.resume(RainLevel.UNKNOWN, RainReturn.MAX_WAIT_MS + 1, 0))
    }

    @Test fun intermittentShowersDoNotLoopForever() {
        assertEquals(RainWait.RESUME, RainReturn.resume(RainLevel.DRY, 40 * minute, RainReturn.MAX_RESUMES - 1))
        assertEquals("third rain of the same mowing: resume by hand", RainWait.GIVE_UP, RainReturn.resume(RainLevel.DRY, 40 * minute, RainReturn.MAX_RESUMES))
    }
}
