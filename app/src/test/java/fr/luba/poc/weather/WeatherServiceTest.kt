package fr.luba.poc.weather

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * End-to-end weather service without Internet: RainViewer index, real PNG radar tiles (native decoding), simulated
 * Open-Meteo forecast and hourly weather. Refreshes started in the background are queued and run on demand ([drain]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WeatherServiceTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val queue = ArrayDeque<Runnable>()
    private fun drain() { while (true) { (synchronized(queue) { queue.removeFirstOrNull() } ?: break).run() } }
    private val calls = CopyOnWriteArrayList<String>()
    private val nowSec get() = System.currentTimeMillis() / 1000
    private val paris = GardenLocation(48.8566, 2.3522, "manuelle")
    private val lyon = GardenLocation(45.7640, 4.8357, "manuelle")

    // Simulated data, modifiable by each test.
    private var radarIndex: () -> String = { index(nowSec - 1200, nowSec - 600) }
    private var tile: (String) -> ByteArray = { png(0) }
    private var forecast: () -> String = { forecastJson() }
    private var hourly: () -> String = { hourlyJson() }
    /** Called on each tile request (before the response). */
    private var onTile: (String) -> Unit = {}

    private fun index(vararg times: Long) = JSONObject().put("host", "https://tilecache.rainviewer.com").put("radar", JSONObject()
        .put("past", JSONArray().apply { times.forEach { put(JSONObject().put("time", it).put("path", "/v2/radar/$it")) } })).toString()
    private val pngCache = HashMap<Int, ByteArray>()
    /** Uniform 512 px tile: palette colour of a dBZ, or transparent (0). */
    private fun png(argb: Int) = pngCache.getOrPut(argb) {
        val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
    private fun forecastJson(mm: (Int) -> Double = { 0.0 }): String {
        val first = nowSec / 900 * 900 - 2 * 900
        val steps = 0 until 36
        return JSONObject().put("minutely_15", JSONObject().put("time", JSONArray(steps.map { first + it * 900L }))
            .put("precipitation", JSONArray(steps.map { mm(it - 3) }))).toString()
    }
    private fun hourlyJson(): String {
        val hours = (0 until 80).map { nowSec / 3600 * 3600 - (76 - it) * 3600L }
        return JSONObject().put("hourly", JSONObject().put("time", JSONArray(hours)).put("temperature_2m", JSONArray(hours.map { 18.0 }))
            .put("dew_point_2m", JSONArray(hours.map { 9.0 })).put("relative_humidity_2m", JSONArray(hours.map { 55.0 }))
            .put("precipitation", JSONArray(hours.map { 0.0 })).put("vapour_pressure_deficit", JSONArray(hours.map { 1.0 }))).toString()
    }

    @Before fun setUp() {
        WeatherService.resetForTest()
        WeatherService.scope = CoroutineScope(SupervisorJob() + Executor { synchronized(queue) { queue.addLast(it) } }.asCoroutineDispatcher())
        WeatherService.http = { url ->
            calls += url
            when {
                url == "https://api.rainviewer.com/public/weather-maps.json" -> radarIndex().toByteArray()
                url.endsWith(".png") -> { onTile(url); tile(url) }
                "minutely_15" in url -> forecast().toByteArray()
                "hourly=" in url -> hourly().toByteArray()
                else -> error("URL inattendue $url")
            }
        }
    }
    @After fun tearDown() = WeatherService.resetForTest()

    private val state get() = WeatherService.flow.value
    private fun refresh(force: Boolean = true) = runBlocking { WeatherService.refresh(force) }
    private fun configured(location: GardenLocation = paris) { WeatherService.init(app); WeatherService.setLocation(location); drain() }
    private fun tiles() = calls.filter { it.endsWith(".png") }

    @Test fun `without position no network call`() {
        WeatherService.init(app); drain(); refresh()
        assertTrue(calls.isEmpty())
        assertEquals(RainLevel.UNKNOWN, state.assessment.level); assertEquals("Position du jardin non configurée", state.assessment.reason)
        assertNull(state.location)
    }

    @Test fun `rain on the garden observed by the radar`() {
        tile = { png(RadarPalette.color(35)) }
        configured()
        assertEquals(RainLevel.RAIN, state.assessment.level)
        assertEquals(35, state.assessment.localDbz); assertTrue(state.assessment.alertWhileMowing)
        assertEquals(2, state.frames.size); assertTrue(state.frames.all { it.complete })
        assertNotNull(state.radarFetchedAtMs); assertNull(state.error); assertFalse(state.loading)
        assertNotNull("wet grass computed", state.wetness)
        val outlook = MowingRainCheck.check(state.assessment, state.forecast, System.currentTimeMillis(), 30)
        assertTrue(outlook.warn); assertTrue(outlook.message, outlook.message.startsWith("Il pleut sur le jardin"))
        // Coordinates sent rounded to 4 decimals.
        assertTrue(calls.any { "latitude=48.8566&longitude=2.3522" in it })
    }

    @Test fun `dry radar and forecast no alert`() {
        configured()
        assertEquals(RainLevel.DRY, state.assessment.level)
        assertTrue(state.assessment.reason, state.assessment.reason.startsWith("Pas de pluie à moins de"))
        val outlook = MowingRainCheck.check(state.assessment, state.forecast, System.currentTimeMillis(), 90)
        assertFalse(outlook.warn); assertTrue(outlook.message.startsWith("Pas de pluie prévue pendant la tonte"))
    }

    @Test fun `rain forecast within half an hour announced during mowing`() {
        forecast = { forecastJson { step -> if (step == 1) 0.6 else 0.0 } }
        configured()
        assertEquals(RainLevel.WATCH, state.assessment.level)
        assertEquals("Prévision 0,6 mm dans l'heure", state.assessment.reason)
        val outlook = MowingRainCheck.check(state.assessment, state.forecast, System.currentTimeMillis(), 30)
        assertTrue(outlook.warn); assertTrue(outlook.message, outlook.message.startsWith("Pluie prévue"))
        // Past rain only: nothing announced for the coming mowing.
        forecast = { forecastJson { step -> if (step < 0) 2.0 else 0.0 } }
        refresh()
        assertEquals(RainLevel.DRY, state.assessment.level)
        assertFalse(MowingRainCheck.check(state.assessment, state.forecast, System.currentTimeMillis(), 30).warn)
    }

    @Test fun `radar unavailable the forecast alone never means dry`() {
        radarIndex = { throw IllegalStateException("HTTP 500") }
        configured()
        assertEquals(RainLevel.WATCH, state.assessment.level)
        assertEquals("Radar indisponible ; prévision sèche seulement", state.assessment.reason)
        assertEquals("Radar : HTTP 500", state.error); assertNull(state.radarFetchedAtMs)
    }

    @Test fun `no weather data state unavailable and errors listed`() {
        radarIndex = { throw IOException("réseau") }; forecast = { throw IOException("réseau") }; hourly = { throw IOException("réseau") }
        configured()
        assertEquals(RainLevel.UNKNOWN, state.assessment.level); assertEquals("Radar et prévision indisponibles", state.assessment.reason)
        assertEquals("Radar : réseau · Prévision : réseau · Météo horaire : réseau", state.error)
        assertFalse(state.loading); assertNull(state.wetness)
        // Malformed responses: same effects, without crash.
        radarIndex = { "{}" }; forecast = { "<html>" }; hourly = { """{"hourly":{}}""" }
        refresh()
        assertEquals(RainLevel.UNKNOWN, state.assessment.level); assertEquals(3, state.error!!.split(" · ").size)
    }

    @Test fun `missing tile retried and complete frames kept in cache`() {
        val times = longArrayOf(nowSec - 1200, nowSec - 600)
        radarIndex = { index(*times) }
        tile = { url -> if ("/v2/radar/${times[1]}/" in url) throw IOException("tuile") else png(0) }
        configured()
        assertEquals(listOf(true, false), state.frames.map { it.complete })
        val firstTiles = tiles().size
        tile = { png(0) }; calls.clear()
        refresh(force = false)
        assertTrue("only the incomplete frame is read again", tiles().isNotEmpty() && tiles().all { "/v2/radar/${times[1]}/" in it })
        assertTrue(tiles().size < firstTiles)
        assertEquals(listOf(true, true), state.frames.map { it.complete })
        assertFalse("forecast read again every 15 min only", calls.any { "minutely_15" in it })
        assertFalse("hourly weather read again every hour", calls.any { "hourly=" in it })
        calls.clear(); refresh(force = true)
        assertTrue(tiles().isEmpty()); assertTrue(calls.any { "minutely_15" in it })
    }

    @Test fun `monitoring off and position kept on restart`() {
        configured()
        WeatherService.setMonitoring(false); calls.clear(); drain(); refresh()
        assertTrue("no network call", calls.isEmpty())
        WeatherService.resetForTest(); WeatherService.init(app); drain()
        assertEquals(paris, state.location); assertFalse(state.monitoring); assertTrue(calls.isEmpty())
        WeatherService.setMonitoring(true); drain()
        assertTrue(calls.isNotEmpty()); assertEquals(RainLevel.DRY, state.assessment.level)
        WeatherService.setLocation(null); drain()
        assertNull(state.location)
        assertFalse(app.getSharedPreferences("weather", Context.MODE_PRIVATE).contains("lat"))
    }

    @Test fun `position change during a refresh without mixing radars`() {
        // Rain in Paris, dry in Lyon (distinct tiles by x column).
        val parisX = WebMercator.tileIndex(WebMercator.worldX(paris.lon, 8), 512)
        tile = { url -> if (url.split('/')[url.split('/').size - 4].toInt() == parisX) png(RadarPalette.color(35)) else png(0) }
        WeatherService.init(app); drain()
        WeatherService.setLocation(paris)
        // While the Paris tiles are being read, the user picks Lyon.
        var moved = false
        onTile = { if (!moved) { moved = true; WeatherService.setLocation(lyon) } }
        drain()
        assertEquals(lyon, state.location)
        val lyonOnly = WebMercator.tileIndex(WebMercator.worldX(lyon.lon, 8) + WeatherService.VIEW_PX / 2 - 1, 512)
        assertTrue(lyonOnly > WebMercator.tileIndex(WebMercator.worldX(paris.lon, 8) + WeatherService.VIEW_PX / 2 - 1, 512))
        assertTrue("Lyon tiles read", tiles().any { it.split('/')[it.split('/').size - 4].toInt() == lyonOnly })
        assertEquals("Lyon radar, not the Paris one", RainLevel.DRY, state.assessment.level)
    }

    @Test fun `new dry garden after a rainy garden without inherited end of rain`() {
        val parisX = WebMercator.tileIndex(WebMercator.worldX(paris.lon, 8), 512)
        tile = { url -> if (url.split('/')[url.split('/').size - 4].toInt() == parisX) png(RadarPalette.color(35)) else png(0) }
        configured(paris)
        assertEquals(RainLevel.RAIN, state.assessment.level)
        WeatherService.setLocation(lyon); drain()
        assertEquals(RainLevel.DRY, state.assessment.level)
    }
}
