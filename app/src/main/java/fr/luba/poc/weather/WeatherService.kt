package fr.luba.poc.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import fr.luba.poc.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.floor
import fr.luba.poc.i18n.tr

/** Garden position for the weather (WGS84 degrees) and source declared by the user. */
data class GardenLocation(val lat: Double, val lon: Double, val source: String) {
    val label: String get() = "%.4f, %.4f".format(Locale.US, lat, lon)
}

/** Summary of a radar frame around the garden (no image is kept or displayed). */
data class RadarFrame(val stats: FrameStats, val complete: Boolean) {
    val timeSec: Long get() = stats.timeSec
}

data class WeatherState(
    val location: GardenLocation? = null,
    val monitoring: Boolean = true,
    val frames: List<RadarFrame> = emptyList(),
    val forecast: PrecipForecast? = null,
    val assessment: RainAssessment = RainAssessment(RainLevel.UNKNOWN, tr("Garden position not set", "Position du jardin non configurée")),
    val radarFetchedAtMs: Long? = null,
    /** Wet grass from the hourly garden weather ([GrassWetness]), null if unknown. */
    val wetness: GrassWetness? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Garden weather check: rain observed by the RainViewer radar (2 h in 10-min steps, analysed without display) and
 * Open-Meteo forecast in 15-min steps over 8 h. Observation mode: no command is sent to the robot; the state feeds
 * the map chip, the pre-mowing warning and the alerts.
 *
 * The radar is read in a frame of [VIEW_PX] Web Mercator pixels at zoom [VIEW_ZOOM] (256 tiles) centred on the
 * garden: the 512 px radar tiles at zoom 7 overlap it exactly.
 */
object WeatherService {
    private const val TAG = "WeatherService"
    const val VIEW_ZOOM = 8
    const val VIEW_PX = 320
    private const val RADAR_ZOOM = 7
    private const val RADAR_TILE = 512
    private const val REFRESH_MS = 5 * 60_000L
    private const val FORECAST_REFRESH_MS = 15 * 60_000L
    /** Forecast horizon: covers a long mowing without relying on the hourly forecast. */
    const val FORECAST_STEPS = 32

    /** Refresh thread and network access; replaced only by JVM tests (no Internet call). */
    @androidx.annotation.VisibleForTesting internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @androidx.annotation.VisibleForTesting internal var http: (String) -> ByteArray = ::download
    private val mutex = Mutex()
    private val state = MutableStateFlow(WeatherState())
    val flow: StateFlow<WeatherState> get() = state
    private var prefs: android.content.SharedPreferences? = null
    private var loop: Job? = null
    private var monitor = RainMonitor()
    /** Frames already analysed, per garden and RainViewer path (immutable): `position|path`. */
    private val frameCache = LinkedHashMap<String, RadarFrame>()
    private var forecastAtMs = 0L
    private var hourlyAtMs = 0L
    private const val HOURLY_REFRESH_MS = 60 * 60_000L

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)
        prefs = p
        // Preference of the old radar panel, removed.
        if (p.contains("overlay")) p.edit().remove("overlay").apply()
        val location = if (p.contains("lat") && p.contains("lon"))
            GardenLocation(Double.fromBits(p.getLong("lat", 0)), Double.fromBits(p.getLong("lon", 0)), p.getString("source", null) ?: "manuelle")
        else null
        state.value = WeatherState(location = location, monitoring = p.getBoolean("monitoring", true),
            assessment = if (location == null) WeatherState().assessment else RainAssessment(RainLevel.UNKNOWN, tr("Loading the weather…", "Chargement de la météo…")))
        start()
    }

    fun setLocation(location: GardenLocation?) {
        prefs?.edit()?.apply {
            if (location == null) remove("lat").remove("lon").remove("source")
            else putLong("lat", location.lat.toBits()).putLong("lon", location.lon.toBits()).putString("source", location.source)
        }?.apply()
        synchronized(frameCache) { frameCache.clear() }
        forecastAtMs = 0
        // "End of rain" hysteresis specific to the old garden.
        monitor = RainMonitor()
        state.update { WeatherState(location = location, monitoring = it.monitoring,
            assessment = if (location == null) WeatherState().assessment else RainAssessment(RainLevel.UNKNOWN, tr("Loading the weather…", "Chargement de la météo…"))) }
        refreshNow()
    }

    /** Screenshots and tests: forced state, without network. */
    @androidx.annotation.VisibleForTesting
    internal fun setStateForTest(value: WeatherState) { state.value = value }

    /** Tests: service reset to a first-launch state (preferences not read, caches empty, loop stopped). */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTest() {
        loop?.cancel(); loop = null; prefs = null; monitor = RainMonitor()
        synchronized(frameCache) { frameCache.clear() }
        forecastAtMs = 0; hourlyAtMs = 0; state.value = WeatherState()
    }

    fun setMonitoring(on: Boolean) { prefs?.edit()?.putBoolean("monitoring", on)?.apply(); state.update { it.copy(monitoring = on) }; if (on) refreshNow() }

    fun refreshNow() { scope.launch { refresh(force = true) } }

    private fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (true) {
                refresh(force = false)
                delay(REFRESH_MS)
            }
        }
    }

    internal suspend fun refresh(force: Boolean) = mutex.withLock {
        val s = state.value
        val location = s.location ?: return@withLock
        if (!s.monitoring) return@withLock
        state.update { it.copy(loading = true) }
        val errors = mutableListOf<String>()
        try {
            val frames = runCatching { radarFrames(location) }.getOrElse {
                if (it is CancellationException) throw it
                Log.w(TAG, "Radar", it); errors += tr("Radar: ${it.message ?: it.javaClass.simpleName}", "Radar : ${it.message ?: it.javaClass.simpleName}"); null
            }
            val now = System.currentTimeMillis()
            var forecast = state.value.forecast
            if (force || now - forecastAtMs >= FORECAST_REFRESH_MS) {
                runCatching { forecast(location) }.onSuccess { forecast = it; forecastAtMs = now }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Forecast", it); errors += tr("Forecast: ${it.message ?: it.javaClass.simpleName}", "Prévision : ${it.message ?: it.javaClass.simpleName}") }
            }
            var wetness = state.value.wetness
            if (force || now - hourlyAtMs >= HOURLY_REFRESH_MS) {
                runCatching { GrassWetness.of(hourly(location), now / 1000) }.onSuccess { wetness = it; hourlyAtMs = now }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Hourly weather", it); errors += tr("Hourly weather: ${it.message ?: it.javaClass.simpleName}", "Météo horaire : ${it.message ?: it.javaClass.simpleName}") }
            }
            // Position changed during the read: result of the old garden discarded (forced refresh queued).
            if (state.value.location != location) { forecastAtMs = 0; hourlyAtMs = 0; return@withLock }
            val usable = frames ?: state.value.frames
            val assessment = monitor.update(RainInputs(now, usable.filter { it.complete }.map { it.stats }, forecast))
            state.update { it.copy(frames = usable, forecast = forecast, assessment = assessment, wetness = wetness,
                radarFetchedAtMs = if (frames != null) now else it.radarFetchedAtMs, loading = false,
                error = errors.joinToString(" · ").ifBlank { null }) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "Weather refresh", e)
            state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    // ---- Radar ----

    private fun originX(l: GardenLocation) = floor(WebMercator.worldX(l.lon, VIEW_ZOOM) - VIEW_PX / 2.0).toInt()
    private fun originY(l: GardenLocation) = floor(WebMercator.worldY(l.lat, VIEW_ZOOM) - VIEW_PX / 2.0).toInt()
    private fun tilesFor(origin: Int) = WebMercator.tileIndex(origin.toDouble(), RADAR_TILE)..WebMercator.tileIndex((origin + VIEW_PX - 1).toDouble(), RADAR_TILE)

    private fun radarFrames(l: GardenLocation): List<RadarFrame> {
        val json = JSONObject(http("https://api.rainviewer.com/public/weather-maps.json").decodeToString())
        val host = json.getString("host")
        val past = json.getJSONObject("radar").getJSONArray("past")
        val ox = originX(l); val oy = originY(l)
        val gx = WebMercator.worldX(l.lon, VIEW_ZOOM) - ox; val gy = WebMercator.worldY(l.lat, VIEW_ZOOM) - oy
        val mpp = WebMercator.metersPerPixel(l.lat, VIEW_ZOOM)
        val wanted = (0 until past.length()).map { past.getJSONObject(it) }
        val n = 1 shl RADAR_ZOOM
        val result = wanted.map { f ->
            val path = f.getString("path"); val time = f.getLong("time")
            synchronized(frameCache) { frameCache[cacheKey(l, path)] }?.let { return@map it }
            val dbz = ByteArray(VIEW_PX * VIEW_PX) { RadarGrid.NO_ECHO }
            var complete = true
            val px = IntArray(RADAR_TILE * RADAR_TILE)
            for (ty in tilesFor(oy)) for (tx in tilesFor(ox)) {
                if (ty !in 0 until n) continue
                val bytes = runCatching { http("$host$path/$RADAR_TILE/$RADAR_ZOOM/${Math.floorMod(tx, n)}/$ty/2/0_0.png") }.getOrNull()
                val tile = bytes?.let { decodeUnpremultiplied(it) }
                if (tile == null || tile.width != RADAR_TILE) { complete = false; continue }
                tile.getPixels(px, 0, RADAR_TILE, 0, 0, RADAR_TILE, RADAR_TILE)
                tile.recycle()
                val left = tx * RADAR_TILE - ox; val top = ty * RADAR_TILE - oy
                for (y in maxOf(0, top) until minOf(VIEW_PX, top + RADAR_TILE)) for (x in maxOf(0, left) until minOf(VIEW_PX, left + RADAR_TILE))
                    dbz[y * VIEW_PX + x] = RadarGrid.encode(RadarPalette.dbz(px[(y - top) * RADAR_TILE + (x - left)]))
            }
            RadarFrame(RadarAnalysis.stats(RadarGrid(time, VIEW_PX, VIEW_PX, dbz, gx, gy, mpp)), complete).also {
                // An incomplete frame will be retried on the next refresh.
                if (complete) synchronized(frameCache) { frameCache[cacheKey(l, path)] = it }
            }
        }
        synchronized(frameCache) { frameCache.keys.retainAll(wanted.map { cacheKey(l, it.getString("path")) }.toSet()) }
        return result.sortedBy { it.timeSec }
    }

    /** A frame read for an old garden (position changed during the read) is never used for the new one. */
    private fun cacheKey(l: GardenLocation, path: String) = "${l.label}|$path"

    private fun decodeUnpremultiplied(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })

    // ---- Open-Meteo forecast ----

    private fun forecast(l: GardenLocation): PrecipForecast {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&minutely_15=precipitation&past_minutely_15=8&forecast_minutely_15=%d&timeformat=unixtime&timezone=GMT"
            .format(Locale.US, l.lat, l.lon, FORECAST_STEPS)
        return PrecipForecast.parse(http(url).decodeToString(), System.currentTimeMillis())
    }

    /** Hourly weather of the last 3 days and today (temperature, dew, humidity, rain, vapour pressure deficit). */
    private fun hourly(l: GardenLocation): List<HourlyWeather> {
        val url = ("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=temperature_2m,dew_point_2m," +
            "relative_humidity_2m,precipitation,vapour_pressure_deficit&past_days=3&forecast_days=1&timeformat=unixtime&timezone=GMT")
            .format(Locale.US, l.lat, l.lon)
        return HourlyWeather.parse(http(url).decodeToString())
    }

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "DumbyLuba/${BuildConfig.VERSION_NAME} (Android; personal use)")
            val code = c.responseCode
            if (code != 200) error("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
