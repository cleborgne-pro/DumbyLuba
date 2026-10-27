package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.CoverageMode
import fr.luba.poc.map.MapScreen
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.OrientationRule
import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.foldAngle
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Several different task settings changed one after the other in a single composition, each followed by "Route
 * preview": every computation succeeds and its result reflects the setting just changed (mode, manual angle, spacing,
 * perimeter loops, height passes, cutting height), not the one of an older composition. A real map change during the
 * computation is still refused.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class MapPreviewChainedSettingsTest {
    val compose = createComposeRule()
    // The test activity (ui-test-manifest) only exists in debug.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 8.0), Point(0.0, 8.0)), "Pelouse")
    private val state = RobotUi(robotMap = RobotMap(listOf(zone), true, "test"), connection = ConnectionState.Receiving, ready = true,
        telemetry = Telemetry(battery = 80, x = 6.0, y = 4.0, heading = 0.0, robotState = 11))

    private var preview: Preview? = null

    private fun open(ui: androidx.compose.runtime.State<RobotUi>) {
        // Created before the composition, as MainActivity does (BorderTypeRepository.init).
        fr.luba.poc.map.BorderTypeRepository.resetForTest()
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(ui.value, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = true,
                onPreview = { preview = it })
        } } }
        compose.waitForIdle()
        compose.onNodeWithText("Tout ajouter").performClick()
        compose.onNodeWithText("Suivant").performClick()
        compose.waitForIdle()
    }

    private fun refusals() = compose.onAllNodesWithText("recalcul requis", substring = true).fetchSemanticsNodes().size

    private fun screenTexts() = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.joinToString(" | ") { it.text }

    /** "Route preview" from the settings page, then back to the settings page; the computed preview. */
    private fun computed(step: String): Preview {
        preview = null
        compose.onNodeWithText("Aperçu du trajet").performClick()
        runCatching { compose.waitUntil(120_000) { preview != null || refusals() > 0 } }
        compose.waitForIdle()
        assertEquals("$step: no refusal (${screenTexts()})", 0, refusals())
        val result = assertNotNullAnd("$step: preview computed (${screenTexts()})", preview)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        return result
    }

    private fun assertNotNullAnd(message: String, value: Preview?): Preview { assertNotNull(message, value); return value!! }

    private fun range(min: Float, max: Float) = SemanticsMatcher("slider $min..$max") { node ->
        node.config.getOrElseNullable(SemanticsProperties.ProgressBarRangeInfo) { null }?.range?.let { abs(it.start - min) < 1e-4f && abs(it.endInclusive - max) < 1e-4f } == true
    }

    private fun slider(min: Float, max: Float, index: Int = 0): SemanticsNodeInteraction = compose.onAllNodes(range(min, max))[index]

    private fun setSlider(min: Float, max: Float, value: Float, index: Int = 0) {
        slider(min, max, index).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    private fun tile(label: String) { compose.onNodeWithText(label).performScrollTo().performClick(); compose.waitForIdle() }

    private fun guideMaps() = compose.onAllNodesWithTag("orientation-map").fetchSemanticsNodes().size

    /** Directions (0..179) of the straight cutting passes of [p]. */
    private fun directions(p: Preview) = p.passes.filter { it.size >= 2 && it.first().distance(it.last()) > 0.5 }
        .map { foldAngle(Math.toDegrees(atan2(it.last().y - it.first().y, it.last().x - it.first().x)).roundToInt()) }.toSet()

    private fun allNear(directions: Set<Int>, expected: Set<Int>) =
        directions.isNotEmpty() && directions.all { d -> expected.any { OrientationRule.distance(it, d) <= 1 } }

    @Test fun `different settings changed one after the other are each reflected in the next preview`() {
        open(mutableStateOf(state))
        val heights = state.cutHeights

        // Default: Zigzag at 25 cm.
        val zigzag = computed("Zigzag")
        assertEquals(CoverageMode.LINES, zigzag.mode)

        // Modes, one after the other.
        for ((label, mode) in listOf("Optimisé" to CoverageMode.OPTIMIZED, "Zamboni" to CoverageMode.ZAMBONI,
                "Spirale" to CoverageMode.SPIRAL, "Damier" to CoverageMode.CHECKERBOARD, "Zigzag" to CoverageMode.LINES)) {
            tile(label)
            assertEquals(label, mode, computed(label).mode)
        }

        // Manual angle: asymmetric angles, then the slider moved again between two previews.
        tile("Manuelle")
        for (angle in listOf(35, 145, 0, 90, 175)) {
            setSlider(0f, 175f, angle.toFloat())
            val p = computed("Zigzag $angle°")
            assertEquals("Zigzag $angle°", listOf(angle), p.zoneAngles)
            assertTrue("Zigzag $angle°: passes ${directions(p)}", allNear(directions(p), setOf(angle)))
        }
        // Checkerboard at the manual angle: both families of passes.
        tile("Damier")
        setSlider(0f, 175f, 35f)
        val checkerboard = computed("Damier 35°")
        assertEquals(CoverageMode.CHECKERBOARD, checkerboard.mode)
        assertTrue("Damier 35°: passes ${directions(checkerboard)}", allNear(directions(checkerboard), setOf(35, 125)))
        assertTrue("Damier 35°: both families ${directions(checkerboard)}",
            directions(checkerboard).any { OrientationRule.distance(it, 35) <= 1 } && directions(checkerboard).any { OrientationRule.distance(it, 125) <= 1 })
        // Back to Zigzag at 0°: rows along x, spaced across the 8 m side.
        tile("Zigzag")
        setSlider(0f, 175f, 0f)
        val narrow = computed("Zigzag 0° 25 cm")
        assertEquals(listOf(0), narrow.zoneAngles)

        // Spacing 25 -> 35 cm: fewer rows.
        compose.onNodeWithText("Réglages avancés").performScrollTo().performClick()
        compose.waitForIdle()
        setSlider(0.05f, 0.35f, 0.35f)
        val wide = computed("Zigzag 0° 35 cm")
        val narrowRows = narrow.passes.count { it.size >= 2 }; val wideRows = wide.passes.count { it.size >= 2 }
        assertTrue("rows at 25 cm $narrowRows, at 35 cm $wideRows", wideRows < narrowRows * 0.85)

        // Perimeter loops: 1 (default) -> 0 -> 2.
        assertEquals("default perimeter loops", 1, wide.outerLoops)
        setSlider(0f, 4f, 0f, index = 0)
        assertEquals("no perimeter loop", 0, computed("perimeter 0").outerLoops)
        setSlider(0f, 4f, 2f, index = 0)
        assertEquals("two perimeter loops", 2, computed("perimeter 2").outerLoops)

        // Height passes 1 -> 2, then the cutting height changed: the last pass follows it.
        assertTrue("single pass by default", wide.cutHeights.size <= 1)
        setSlider(1f, 3f, 2f)
        val twoPasses = computed("2 passes")
        assertEquals("2 passes: ${twoPasses.cutHeights}", 2, twoPasses.cutHeights.size)
        val lower = (twoPasses.cutHeights.last() - 10).coerceAtLeast(heights.minMm)
        setSlider(heights.minMm.toFloat(), heights.maxMm.toFloat(), lower.toFloat())
        val lowered = computed("height $lower mm")
        assertEquals("height $lower mm: ${lowered.cutHeights}", lower, lowered.cutHeights.last())
    }

    @Test fun `guide map only in Manual for the modes with a direction`() {
        open(mutableStateOf(state))
        tile("Zigzag")
        tile("Automatique")
        assertEquals("Automatic", 0, guideMaps())
        tile("Manuelle")
        assertEquals("Zigzag manual", 1, guideMaps())
        for ((label, shown) in listOf("Damier" to 1, "Zamboni" to 1, "Optimisé" to 0, "Spirale" to 0, "Zigzag" to 1)) {
            tile(label)
            assertEquals("$label manual", shown, guideMaps())
        }
        tile("Chaque séance")
        assertEquals("Each session", 0, guideMaps())
    }

    @Test fun `zone redrawn during the computation still refuses the route`() {
        val ui = mutableStateOf(state)
        open(ui)
        preview = null
        compose.onNodeWithText("Aperçu du trajet").performClick()
        // Changed before the main thread runs again: the computation in progress started on the old outline.
        val moved = zone.copy(points = zone.points.map { Point(it.x + 0.5, it.y) })
        ui.value = state.copy(robotMap = RobotMap(listOf(moved), true, "test"))
        runCatching { compose.waitUntil(120_000) { preview != null || refusals() > 0 } }
        assertEquals(null, preview)
        compose.onNodeWithText("recalcul requis", substring = true).assertExists()
    }
}
