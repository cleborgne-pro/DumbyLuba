package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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

/** Opt-in screenshots (-PuiShots=folder) of the map's animated effects, clock advanced by hand. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class UiShotsMapFxTest {
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

    private fun fx(name: String, dark: Boolean, advanceMs: Long) {
        val plan = fr.luba.poc.diagnostics.MapNavPlanner.plan(map, Point(2.0, 3.0), 25.0, emptyList())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LubaTheme(dark) {
                Box(Modifier.fillMaxSize()) {
                    MapLive(map = map, x = 2.6, y = 3.0, heading = 40.0, plannedBands = plan.bands, plannedBorders = plan.borders,
                        interactive = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    /** Base up close, as in the Mammotion app: grey safety zone, corridor, station, badge and light border laps. */
    private fun base(name: String, dark: Boolean, metersAcross: Double) {
        val station = map.station!!
        val plan = fr.luba.poc.diagnostics.MapNavPlanner.plan(map, Point(2.0, 3.0), 25.0, emptyList())
        compose.setContent {
            LubaTheme(dark) {
                val colors = fr.luba.poc.ui.theme.MammotionColors
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().then(Modifier.drawBehind { drawRect(colors.MapBackground) })) {
                    val scale = size.width / metersAcross
                    val unit = density
                    fun screen(p: Point) = androidx.compose.ui.geometry.Offset(
                        (size.width / 2 + (p.x - station.point.x) * scale).toFloat(), (size.height / 2 - (p.y - station.point.y) * scale).toFloat())
                    fun path(points: List<Point>, closed: Boolean) = androidx.compose.ui.graphics.Path().apply {
                        points.forEachIndexed { i, p -> val o = screen(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                        if (closed) close()
                    }
                    drawLawn(map, { true }, ::path, unit, colors)
                    drawBaseSafetyZone(screen(station.point),
                        (fr.luba.poc.simulation.TrajectoryPlanner.baseSafetyRadius(1.5) * scale).toFloat(), unit, colors.BaseSafety)
                    val band = androidx.compose.ui.graphics.drawscope.Stroke((0.40 * scale).toFloat(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                    plan.borders.forEach { drawPath(path(it, false), colors.BandBorder, style = band) }
                    plan.bands.forEach { drawPath(path(it, false), colors.LinePass, style = androidx.compose.ui.graphics.drawscope.Stroke(0.75f * unit)) }
                    plan.borders.forEach { drawPath(path(it, false), colors.LineBorder, style = androidx.compose.ui.graphics.drawscope.Stroke(0.75f * unit)) }
                    drawStation(screen(station.point), scale, station.entryHeadingDegrees, null, colors.Ink, 1.5, 1.5f)
                    if (stationNeedsBadge(scale, unit)) drawStationBadge(screen(station.point), unit, colors.Ink, colors.OnInk)
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    @Test fun baseNear() = base("carte-base", false, 9.0)
    @Test fun baseFar() = base("carte-base-loin", false, 40.0)
    @Test fun baseDark() = base("carte-base-dark", true, 9.0)

    @Test fun fxLight() = fx("fx-route", false, 1300)
    @Test fun fxDark() = fx("fx-route-dark", true, 2300)
}
