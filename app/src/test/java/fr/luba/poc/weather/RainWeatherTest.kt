package fr.luba.poc.weather

import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto
import fr.luba.poc.proto.MctrlSysProto.*
import fr.luba.poc.protocol.TelemetryReducer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class RainWeatherTest {
    private val t0 = 1_791_660_000L

    @Test fun `Universal Blue palette converted to dBZ`() {
        assertEquals(15, RadarPalette.dbz(0xff88ddee.toInt()))
        assertEquals(35, RadarPalette.dbz(0xffffee00.toInt()))
        assertEquals(-10, RadarPalette.dbz(0x14636159))
        assertNull("transparent", RadarPalette.dbz(0x00000000))
        // Decoder rounding on a barely opaque pixel: neighbouring colour accepted.
        assertEquals(5, RadarPalette.dbz(0x5e938871))
        assertNull("foreign colour", RadarPalette.dbz(0xff123456.toInt()))
        for (d in -10..64) assertEquals(d, RadarPalette.dbz(RadarPalette.color(d)))
    }

    @Test fun `Marshall-Palmer relation`() {
        assertEquals(1.0, Reflectivity.rainRateMmH(Reflectivity.dbzFor(1.0)), 1e-9)
        assertEquals(0.65, Reflectivity.rainRateMmH(20.0), 0.05)
        assertEquals(2.7, Reflectivity.rainRateMmH(30.0), 0.1)
        assertEquals("pluie modérée", Reflectivity.label(32))
        assertEquals("aucune pluie", Reflectivity.label(10))
    }

    @Test fun `Web Mercator projection and tiles`() {
        val lat = 48.8566; val lon = 2.3522
        assertEquals(64, WebMercator.tileIndex(WebMercator.worldX(lon, 7), 256))
        assertEquals(44, WebMercator.tileIndex(WebMercator.worldY(lat, 7), 256))
        assertEquals(lat, WebMercator.lat(WebMercator.worldY(lat, 8), 8), 1e-9)
        assertEquals(lon, WebMercator.lon(WebMercator.worldX(lon, 8), 8), 1e-9)
        // 512 px radar tiles at zoom 7 = zoom 8 pixels in 256 tiles.
        assertEquals(WebMercator.worldX(lon, 8), WebMercator.worldX(lon, 7, 512), 1e-9)
        assertEquals(400.0, WebMercator.metersPerPixel(lat, 8), 15.0)
    }

    /** 200×200 px grid of 500 m, garden in the centre, rain disc of [radiusKm] centred at ([cxKm], 0). */
    private fun grid(time: Long, cxKm: Double?, radiusKm: Double = 3.0, dbz: Int = 30, localDbz: Int? = null): RadarGrid {
        val w = 200; val mpp = 500.0; val g = 100.0
        val cells = ByteArray(w * w) { RadarGrid.NO_ECHO }
        for (y in 0 until w) for (x in 0 until w) {
            val dx = (x + 0.5 - g) * mpp / 1000; val dy = (y + 0.5 - g) * mpp / 1000
            if (cxKm != null && hypot(dx - cxKm, dy) <= radiusKm) cells[y * w + x] = RadarGrid.encode(dbz)
            if (localDbz != null && hypot(dx, dy) <= 0.6) cells[y * w + x] = RadarGrid.encode(localDbz)
        }
        return RadarGrid(time, w, w, cells, g, g, mpp)
    }

    @Test fun `distance of the nearest rain and local rain`() {
        val s = RadarAnalysis.stats(grid(t0, 20.0))
        assertNull(s.localDbz)
        assertEquals(17.0, s.nearestKm!!, 0.6)
        assertEquals(30, s.nearestDbz)
        val wet = RadarAnalysis.stats(grid(t0, null, localDbz = 25))
        assertEquals(25, wet.localDbz)
        assertEquals(0.0, wet.nearestKm!!, 0.5)
        val dry = RadarAnalysis.stats(grid(t0, null))
        assertNull(dry.nearestKm); assertNull(dry.localDbz); assertEquals(0.0, dry.coverage10Km, 0.0)
    }

    @Test fun `approach speed estimated by regression`() {
        val frames = listOf(33.0, 28.0, 23.0, 18.0).mapIndexed { i, km -> RadarAnalysis.stats(grid(t0 + i * 600, km)) }
        val a = RadarAnalysis.approach(frames)!!
        assertTrue(a.approaching)
        assertEquals(30.0, a.speedKmH, 2.0)
        assertEquals(30.0, a.etaMin!!.toDouble(), 4.0)
        val leaving = RadarAnalysis.approach(listOf(18.0, 23.0, 28.0).mapIndexed { i, km -> RadarAnalysis.stats(grid(t0 + i * 600, km)) })!!
        assertFalse(leaving.approaching); assertNull(leaving.etaMin)
        assertNull("two frames are not enough", RadarAnalysis.approach(frames.take(2))!!.etaMin)
    }

    private fun stats(i: Int, local: Int? = null, nearKm: Double? = null) =
        FrameStats(t0 + i * 600L, local, nearKm ?: local?.let { 0.0 }, nearKm?.let { 30 } ?: local, 0.0)
    private fun at(i: Int, extraMin: Int = 4) = (t0 + i * 600L + extraMin * 60) * 1000

    @Test fun `a few isolated drops do not trigger rain`() {
        val m = RainMonitor()
        val a = m.update(RainInputs(at(2), listOf(stats(0), stats(1), stats(2, local = 24)), null))
        assertEquals(RainLevel.WATCH, a.level)
        assertFalse(a.alertWhileMowing)
    }

    @Test fun `persistent rain confirmed then drying with hysteresis`() {
        val m = RainMonitor()
        val frames = mutableListOf(stats(0), stats(1, local = 22), stats(2, local = 26))
        assertEquals(RainLevel.RAIN, m.update(RainInputs(at(2), frames, null)).level)
        assertTrue(m.current.alertWhileMowing)
        frames += stats(3); frames += stats(4)
        val drying = m.update(RainInputs(at(4), frames, null))
        assertEquals(RainLevel.DRYING, drying.level)
        assertFalse(drying.alertWhileMowing)
        frames += stats(5); frames += stats(6)
        assertEquals(RainLevel.DRY, m.update(RainInputs(at(6), frames, null)).level)
    }

    @Test fun `heavy shower on a single frame`() {
        assertEquals(RainLevel.RAIN, RainMonitor().update(RainInputs(at(1), listOf(stats(0), stats(1, local = 34)), null)).level)
    }

    @Test fun `approaching rain announced as imminent`() {
        val frames = listOf(26.0, 21.0, 16.0, 11.0).mapIndexed { i, km -> stats(i, nearKm = km) }
        val a = RainMonitor().update(RainInputs(at(3, 2), frames, null))
        assertEquals(RainLevel.IMMINENT, a.level)
        assertTrue(a.etaMin!! in 15..30)
        assertTrue(a.alertWhileMowing)
    }

    @Test fun `stale data never considered dry`() {
        val m = RainMonitor()
        assertEquals(RainLevel.UNKNOWN, m.update(RainInputs(at(2), emptyList(), null)).level)
        assertEquals(RainLevel.UNKNOWN, m.update(RainInputs(at(2, 60), listOf(stats(0), stats(1), stats(2)), null)).level)
        val forecastOnly = PrecipForecast(at(2, 60), listOf(PrecipForecast.Step(t0 + 3600, 0.0)))
        assertEquals(RainLevel.WATCH, m.update(RainInputs(at(2, 60), listOf(stats(2)), forecastOnly)).level)
    }

    @Test fun `Open-Meteo forecast in 15-minute steps`() {
        val json = """{"minutely_15":{"time":[${t0},${t0 + 900},${t0 + 1800},${t0 + 2700},${t0 + 3600}],"precipitation":[0.4,0.0,0.1,0.3,null]}}"""
        val f = PrecipForecast.parse(json, 0)
        assertEquals(4, f.steps.size)
        val now = t0 + 300
        assertEquals(0.4, f.nextMm(now, 60), 1e-9)
        assertEquals(0.4, f.pastMm(t0 + 900, 30), 1e-9)
        assertEquals(10, f.firstRainMin(now))
        val m = RainMonitor()
        assertEquals(RainLevel.WATCH, m.update(RainInputs(now * 1000, listOf(FrameStats(t0, null, null, null, 0.0)), f.copy(fetchedAtMs = now * 1000))).level)
    }

    @Test fun `garden position entry`() {
        assertEquals(48.8566 to 2.3522, parseLatLon("48.8566, 2.3522"))
        assertEquals(48.8566 to 2.3522, parseLatLon("48,8566 2,3522"))
        assertEquals(48.8566 to -2.3522, parseLatLon("48.8566;-2.3522"))
        assertNull(parseLatLon("48.8566"))
        assertNull(parseLatLon("95.0, 2.0"))
    }

    @Test fun `geographic position published by the robot`() {
        val r = TelemetryReducer()
        val rad = LubaMsg.newBuilder().setNav(MctrlNavProto.MctlNav.newBuilder()
            .setToappLatUp(MctrlNavProto.NavLatLonUp.newBuilder().setLat(Math.toRadians(48.8566)).setLon(Math.toRadians(2.3522)))).build()
        r.accept(rad, 1, 1)
        assertEquals(48.8566, r.value.geoLat!!, 1e-6); assertEquals(2.3522, r.value.geoLon!!, 1e-6)
        val rtk = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder()
            .setRtk(rpt_rtk.newBuilder().setMqttRtkInfo(mqtt_rtk_connect.newBuilder().setLatitude(45.5).setLongitude(4.85))))).build()
        r.accept(rtk, 2, 2)
        assertEquals(45.5, r.value.geoLat!!, 1e-9); assertEquals("station RTK", r.value.geoSource)
        val zero = LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(report_info_data.newBuilder()
            .setRtk(rpt_rtk.newBuilder().setMqttRtkInfo(mqtt_rtk_connect.getDefaultInstance())))).build()
        r.accept(zero, 3, 3)
        assertEquals("zero coordinates ignored", 45.5, r.value.geoLat!!, 1e-9)
    }

    private fun forecast(fetchedMs: Long, vararg mm: Double) =
        PrecipForecast(fetchedMs, mm.mapIndexed { i, v -> PrecipForecast.Step(t0 + (i + 1) * 900L, v) })
    private val dry = RainAssessment(RainLevel.DRY, "Pas de pluie")

    @Test fun `rain forecast during the mowing duration`() {
        val now = t0 * 1000
        // Dry for 1 h, then rain from 1 h 15.
        val f = forecast(now, 0.0, 0.0, 0.0, 0.0, 0.0, 0.3, 0.5, 0.2)
        val short = MowingRainCheck.check(dry, f, now, 45)
        assertFalse(short.message, short.warn)
        assertTrue(short.message.startsWith("Pas de pluie prévue pendant la tonte"))
        val long = MowingRainCheck.check(dry, f, now, 120)
        assertTrue(long.warn)
        assertEquals(75, long.firstRainMin)
        assertTrue(long.message, long.message.contains("1,0 mm pendant la tonte (≈ 2 h)"))
    }

    @Test fun `unknown duration, the coming hour is checked`() {
        val now = t0 * 1000
        val f = forecast(now, 0.0, 0.0, 0.15)
        val o = MowingRainCheck.check(dry, f, now, null)
        assertTrue(o.warn); assertEquals(30, o.firstRainMin)
        assertTrue(o.message, o.message.contains("dans l'heure"))
    }

    @Test fun `forecast horizon shorter than the mowing reported`() {
        val now = t0 * 1000
        val o = MowingRainCheck.check(dry, forecast(now, 0.0, 0.0), now, 90)
        assertFalse(o.warn)
        assertTrue(o.message, o.message.contains("prévision disponible jusqu'à"))
    }

    @Test fun `observed rain takes precedence and stale forecast reported`() {
        val now = t0 * 1000
        val raining = RainAssessment(RainLevel.RAIN, "Pluie faible sur le jardin", localDbz = 26)
        assertTrue(MowingRainCheck.check(raining, forecast(now, 0.0), now, 60).warn)
        assertTrue(MowingRainCheck.check(RainAssessment(RainLevel.DRYING, "Pluie terminée depuis 10 min"), null, now, 60).warn)
        val stale = MowingRainCheck.check(dry, forecast(now - 3 * 3600_000L, 2.0), now, 60)
        assertFalse(stale.warn)
        assertTrue(stale.message.startsWith("Prévision météo indisponible"))
        assertEquals("2 h05", MowingRainCheck.duration(125)); assertEquals("40 min", MowingRainCheck.duration(40))
    }
}
