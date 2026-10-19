package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.map.*
import fr.luba.poc.ui.theme.LubaTheme
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opt-in screenshots (-PuiShots=folder) of the highlights shown in the README: signal heatmaps on the map,
 * progressive route drawing and painted zone. Deterministic fake signals ([SignalCoverage.simulated]),
 * never radio measurements. No pixel assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiShotsAtoutsTest {
    private val out: String? = System.getProperty("uiShots")
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(out != null); base.evaluate() } }
    }).around(compose)

    private val map: RobotMap by lazy {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes = json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        RobotMap(shapes, true, "reference-station", MapStation(Point(0.142, 1.286), 152.86, "Transmise par le robot"))
    }
    private val zones get() = map.shapes.filter { it.type == 0 }
    private val noGo get() = map.shapes.filter { it.type == 1 }
    private val playground get() = zones.single { it.name == "Aire de jeux" }
    /** Map reduced to one zone and its no-go zones, to frame the screenshot on it. */
    private fun focus(zone: MapShape): RobotMap =
        map.copy(shapes = listOf(zone) + noGo.filter { h -> h.points.any { inside(it, zone.points) } })

    private fun inside(p: Point, poly: List<Point>): Boolean {
        var hit = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) hit = !hit
            j = i
        }
        return hit
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    /** Dense fake survey over all zones (excluding no-go zones), base = map station. */
    private fun heatmap(name: String, signal: SurveySignal) {
        val all = zones.flatMap { it.points }
        val base = map.station!!.point
        val samples = mutableListOf<SignalSample>()
        var x = all.minOf { it.x }
        while (x <= all.maxOf { it.x }) {
            var y = all.minOf { it.y }
            while (y <= all.maxOf { it.y }) {
                val p = Point(x, y)
                if (zones.any { inside(p, it.points) } && noGo.none { inside(p, it.points) })
                    samples += SignalCoverage.simulated(p, base, 0.0)
                y += 0.5
            }
            x += 0.5
        }
        val cells = SignalCoverage.cells(samples, signal)
        shot(name) { MapLive(map = map, signalCells = cells, signal = signal, interactive = true, modifier = Modifier.fillMaxSize()) }
    }

    @Test fun heatmapWifi() = heatmap("atout-heatmap-wifi", SurveySignal.WIFI)
    @Test fun heatmapMobile() = heatmap("atout-heatmap-mobile", SurveySignal.MOBILE)
    @Test fun heatmapRtk() = heatmap("atout-heatmap-rtk", SurveySignal.RTK)

    /** Aire de jeux route drawn two-thirds of the way, like the preview animation. */
    @Test fun routeDrawing() {
        val zone = playground.points
        val holes = noGo.map { it.points }.filter { h -> h.any { inside(it, zone) } }
        val preview = CoveragePlanner.plan(zone, holes, 0.25, 0.6, CoverageMode.LINES, includeBorders = true)
        val reveal = RouteDrawing(preview).reveal(0.62)
        val tip = reveal.tip
        shot("atout-trace-anime") {
            MapLive(map = focus(playground), plannedBands = reveal.runs, plannedBorders = reveal.borders,
                cuttingZoneIds = setOf(playground.id), x = tip?.x, y = tip?.y, interactive = true,
                modifier = Modifier.fillMaxSize())
        }
    }

    /** Zone painted by finger on the Aire de jeux: strokes and retained area. */
    @Test fun paintedZone() {
        val zone = playground.points
        val cx = zone.map { it.x }.average(); val cy = zone.map { it.y }.average()
        val strokes = listOf(
            PaintStroke((0..16).map { Point(cx - 4.0 + it * 0.5, cy - 1.5 + kotlin.math.sin(it * 0.6) * 0.8) }, 1.4),
            PaintStroke((0..10).map { Point(cx - 0.5 + it * 0.35, cy + 0.8 + it * 0.3) }, 1.1))
        val pieces = PaintedZone.pieces(map, strokes)
        shot("atout-zone-peinte") {
            MapLive(map = focus(playground), paint = strokes, paintPieces = pieces, interactive = true, modifier = Modifier.fillMaxSize())
        }
    }
}
