package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.*
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.MissionCheckpoint
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runners.model.Statement

/**
 * The buttons of the map's right vertical bar are never covered by the bottom panels (protection alert,
 * mowing resume, action bar…), even on a short screen where they stack high.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h640dp-xxhdpi")
class MapSideBarOverlapTest {
    val compose = createComposeRule()
    // The test activity (ui-test-manifest) only exists in debug: the shipped variant (fold) does not include this manifest.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val sideButtons = listOf("Afficher la caméra", "Boussole, nord en haut", "Station de charge", "Recentrer la carte",
        "Suivre le robot", "Déplacer à un point", "Masquer les calques")

    private fun map(): RobotMap {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes = json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        return RobotMap(shapes, true, "reference-station", MapStation(Point(0.142, 1.286), 152.86, "Transmise par le robot"))
    }

    /** Robot locked after STOP and interrupted mowing: the two largest bottom panels at the same time. */
    private fun lockedWithCheckpoint(): RobotUi {
        val map = map()
        val zones = map.shapes.filter { it.kind == ShapeKind.ZONE }.take(2).map { it.id }
        val checkpoint = MissionCheckpoint("robot", "map", "plan", 10, Preview(emptyList(), 1000.0, 0, 0, zoneIds = zones),
            true, 60, 20, 90, doneMeters = 420.0, totalMeters = 1000.0, startedAt = 1_760_000_000_000,
            interruption = "Connexion Bluetooth perdue")
        return RobotUi(robotMap = map, connection = ConnectionState.Receiving, ready = true, missionCheckpoint = checkpoint,
            telemetry = Telemetry(battery = 87, x = 2.0, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 4, selfCheck = 11L))
    }

    @Test fun sideButtonsStayUncovered() {
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(lockedWithCheckpoint(), SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {})
        } } }
        compose.waitForIdle()

        val all = mutableListOf<Pair<SemanticsNode, List<Int>>>()
        fun walk(node: SemanticsNode, ancestors: List<Int>) {
            all += node to ancestors
            node.children.forEach { walk(it, ancestors + node.id) }
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), emptyList())
        fun description(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        fun text(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

        // The expected panels are displayed (protection, layers strip open by default).
        assertTrue(all.any { text(it.first) == "Robot verrouillé après STOP" })
        assertTrue(all.any { text(it.first)?.contains("Déverrouiller") == true })

        val screen = all.first().first.boundsInRoot
        sideButtons.forEach { label ->
            val (button, ancestors) = all.singleOrNull { description(it.first) == label } ?: error("$label absent : ${all.mapNotNull { description(it.first) }}")
            val bounds = button.boundsInRoot
            assertTrue("$label off screen: $bounds", bounds.height > 0 && bounds.bottom <= screen.bottom)
            val covering = all.filter { (node, nodeAncestors) ->
                node.config.any() && node.id != button.id && node.id !in ancestors && button.id !in nodeAncestors && node.boundsInRoot.overlaps(bounds)
            }.map { (node, _) -> text(node) ?: description(node) ?: "nœud ${node.id}" }
            assertEquals("$label covered by $covering", emptyList<String>(), covering)
        }
    }

    private fun Rect.overlaps(other: Rect) = width > 0 && height > 0 &&
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}
