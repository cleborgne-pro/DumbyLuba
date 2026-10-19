package fr.luba.poc.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wet grass from the hourly garden weather: pivot slides converted to dry grass then predicted. */
class GrassWetnessTest {
    /** Garden, 2026-10-14 18:00 → 2026-10-15 13:00 (Open-Meteo, local time = UTC + 2): no rain, saturated air at night and in the morning. */
    private val october15 = listOf(
        HourlyWeather(1791993600, 15.5, 11.1, 75.0, 0.0, 0.44),
        HourlyWeather(1791997200, 14.2, 11.2, 82.0, 0.0, 0.29),
        HourlyWeather(1792000800, 13.2, 10.0, 81.0, 0.0, 0.29),
        HourlyWeather(1792004400, 12.9, 9.6, 80.0, 0.0, 0.3),
        HourlyWeather(1792008000, 13.0, 10.0, 82.0, 0.0, 0.27),
        HourlyWeather(1792011600, 12.3, 10.6, 89.0, 0.0, 0.16),
        HourlyWeather(1792015200, 12.7, 11.6, 93.0, 0.0, 0.1),
        HourlyWeather(1792018800, 14.0, 13.0, 94.0, 0.0, 0.1),
        HourlyWeather(1792022400, 14.9, 13.8, 93.0, 0.0, 0.12),
        HourlyWeather(1792026000, 15.1, 14.1, 94.0, 0.0, 0.1),
        HourlyWeather(1792029600, 15.1, 14.3, 95.0, 0.0, 0.09),
        HourlyWeather(1792033200, 15.1, 14.5, 96.0, 0.0, 0.07),
        HourlyWeather(1792036800, 15.1, 14.7, 97.0, 0.0, 0.05),
        HourlyWeather(1792040400, 15.2, 14.8, 97.0, 0.0, 0.05),
        HourlyWeather(1792044000, 15.6, 15.3, 98.0, 0.0, 0.04),
        HourlyWeather(1792047600, 15.9, 15.4, 97.0, 0.0, 0.05),
        HourlyWeather(1792051200, 16.3, 15.4, 94.0, 0.0, 0.11),
        HourlyWeather(1792054800, 16.6, 15.5, 93.0, 0.0, 0.13),
        HourlyWeather(1792058400, 17.6, 15.9, 90.0, 0.0, 0.2),
        HourlyWeather(1792062000, 18.2, 15.8, 86.0, 0.0, 0.29),
    )
    private fun local(hour: Int, minute: Int) = 1792015200L + hour * 3600L + minute * 60L

    @Test fun dewOnTheMorningOfThe15thThenDrying() {
        val at9 = GrassWetness.of(october15, local(9, 6))!!
        assertEquals("dew at 9:06: ${at9.reason}", 1.0, at9.index, 1e-9)
        val at1037 = GrassWetness.of(october15, local(10, 37))!!
        assertTrue("drying at 10:37: ${at1037.index} (${at1037.reason})", at1037.index in 0.5..0.85)
        val at1230 = GrassWetness.of(october15, local(12, 30))!!
        assertTrue("drier at 12:30: ${at1230.index}", at1230.index < at1037.index)
        assertEquals(0.0, at9.rainIndex, 1e-9)
        assertTrue(GrassWetness.slideFactor(at9.index) > GrassWetness.slideFactor(at1037.index))
    }

    @Test fun recentRainCountsAndFades() {
        val now = 1_800_000_000L
        val dry = (0 until 30).map { HourlyWeather(now - (29 - it) * 3600L, 20.0, 8.0, 45.0, 0.0, 1.2) }
        assertEquals(0.0, GrassWetness.of(dry, now)!!.index, 1e-9)
        val shower = dry.mapIndexed { i, h -> if (i == 27) h.copy(precipitationMm = 3.0) else h }   // 2 h ago
        val wet = GrassWetness.of(shower, now)!!
        assertTrue("3 mm shower 2 h ago: ${wet.index} (${wet.reason})", wet.index > 0.9 && wet.reason.startsWith("pluie"))
        val old = dry.mapIndexed { i, h -> if (i == 0) h.copy(precipitationMm = 3.0) else h }       // 29 h ago
        assertTrue(GrassWetness.of(old, now)!!.index < 0.15)
    }

    @Test fun noCurrentWeatherMeansUnknown() {
        assertNull(GrassWetness.of(october15, local(23, 0)))
        assertNull(GrassWetness.of(emptyList(), local(9, 0)))
    }

    @Test fun openMeteoHourlyIsParsed() {
        val json = """{"hourly":{"time":[1792047600,1792051200],"temperature_2m":[15.9,16.3],"dew_point_2m":[15.4,15.4],
            "relative_humidity_2m":[97,94],"precipitation":[0.0,null],"vapour_pressure_deficit":[0.05,0.11]}}"""
        val hours = HourlyWeather.parse(json)
        assertEquals(2, hours.size)
        assertTrue(hours[0].saturated)
        assertEquals(0.0, hours[1].precipitationMm, 1e-9)
    }
}
