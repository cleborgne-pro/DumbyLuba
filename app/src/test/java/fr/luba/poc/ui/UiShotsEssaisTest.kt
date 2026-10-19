package fr.luba.poc.ui

import fr.luba.poc.i18n.tr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleIncident
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.protocol.Telemetry
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

/** Opt-in screenshots (-PuiShots=folder) of the test screens: blades, obstacles, simulation, debug mowing, manual control. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h2200dp-xxhdpi")
class UiShotsEssaisTest {
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
    private val robot by lazy { fr.luba.poc.ble.RobotBle(androidx.test.core.app.ApplicationProvider.getApplicationContext()) }
    private val obstacles = listOf(
        LocalObstacle("a", ObstacleKind.TREE, 4.5, 3.0, 0.3, 0.2, label = tr("tree", "arbre"),
            incidents = List(3) { ObstacleIncident(atMs = 1_760_000_000_000 + it, kind = "Frottement") }),
        LocalObstacle("b", ObstacleKind.POLE, 1.0, 6.0, 0.1, 0.2, label = tr("pole", "poteau")))
    private fun ui(): RobotUi = RobotUi(robotMap = map, connection = ConnectionState.Receiving, ready = true,
        selected = fr.luba.poc.ble.SeenRobot("Luba-VS-DEMO", "00:00", -60), ageMs = 300, obstacles = obstacles,
        lastBumperCode = -1014, lastBumperAt = 1_760_000_000_000,
        telemetry = Telemetry(battery = 87, x = 2.0, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 4,
            bladeHeightMm = 60, robotState = 11, sensorStatus = 0, selfCheck = 0, cutterRpm = 0))

    private fun page(key: String, dark: Boolean = false, state: RobotUi = ui()) {
        compose.setContent { Themed(dark) { RobotPanel(robot, state, key, {}) } }
        compose.waitForIdle()
    }
    @Composable private fun Themed(dark: Boolean, content: @Composable () -> Unit) =
        LubaTheme(dark) { Box(Modifier.fillMaxSize()) { content() } }
    private fun capture(name: String) = compose.onRoot().captureRoboImage("$out/$name.png")
    private fun tab(text: String, index: Int = 0) { compose.onAllNodesWithText(text)[index].performClick(); compose.waitForIdle() }

    @Test fun height() { page("Hauteur"); capture("blades-height") }
    @Test fun blades() {
        page("Lames")
        for ((tab, name) in listOf(tr("Robot stationary", "Robot immobile") to "static", tr("Moving", "En déplacement") to "moving")) {
            tab(tab); capture("blades-$name")
        }
    }
    @Test fun bladesDark() { page("Lames", dark = true); capture("blades-static-dark") }

    @Test fun obstacles() {
        page("EssaisObstacle")
        for ((tab, name) in listOf(tr("Contact", "Contact") to "contact", tr("Learn", "Apprendre") to "learn", tr("Bypass", "Contournement") to "course")) {
            tab(tab); capture("obstacles-$name")
        }
    }
    @Test fun obstacleList() { page("Obstacles"); capture("obstacles-list") }
    @Test fun obstacleDialogs() {
        page("Obstacles")
        compose.onAllNodesWithText(tr("Edit", "Modifier")).onFirst().performClick(); compose.waitForIdle()
        compose.onAllNodes(isDialog()).onFirst().captureRoboImage("$out/obstacles-edit.png")
    }
    @Test fun obstacleIncident() {
        page("Obstacles")
        compose.onAllNodesWithText("Incident").onFirst().performClick(); compose.waitForIdle()
        compose.onAllNodes(isDialog()).onFirst().captureRoboImage("$out/obstacles-incident.png")
    }
    @Test fun obstacleDelete() {
        page("Obstacles")
        compose.onAllNodesWithText(tr("Delete", "Supprimer")).onFirst().performClick(); compose.waitForIdle()
        compose.onAllNodes(isDialog()).onFirst().captureRoboImage("$out/obstacles-delete.png")
    }
    @Test fun obstaclesDark() { page("Obstacles", dark = true); capture("obstacles-list-dark") }

    @Test fun obstacleSim() { page("SimObstacle"); capture("obstacle-sim") }
    @Test fun mowDebug() { page("TonteDebug"); capture("mowdebug-paint") }
    @Test fun border() { page("Bordure"); capture("mowdebug-border") }
    @Test fun mowDebugDark() { page("TonteDebug", dark = true); capture("mowdebug-paint-dark") }

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun manual() { page("Pilotage"); capture("manual") }
    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun manualDark() { page("Pilotage", dark = true); capture("manual-dark") }
}
