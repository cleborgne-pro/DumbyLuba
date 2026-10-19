package fr.luba.poc.map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Local corrections editor exercised through the interface: obstacle added, shape added to a zone chosen with a tap,
 * correction deleted then restored with "Annuler" (Undo). The Mammotion map passed to the editor does not change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class MapCorrectionEditorTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { org.junit.Assume.assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 10.0), Point(0.0, 10.0)), "Pelouse")
    private val noGo = MapShape(2, 1, listOf(Point(16.0, 1.0), Point(18.0, 1.0), Point(18.0, 3.0), Point(16.0, 3.0)))
    private val raw = RobotMap(listOf(zone, noGo), true, "test")
    private val saves = mutableListOf<List<MapCorrection>>()

    private fun show(initial: List<MapCorrection> = emptyList()) {
        compose.setContent {
            var list by androidx.compose.runtime.remember { mutableStateOf(initial) }
            LubaTheme {
                MapCorrectionEditor(raw, list, onSave = { saves.add(it); list = it; true }, onClose = {})
            }
        }
    }

    private fun validate() {
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Valider").assertIsEnabled() }.isSuccess }
        compose.onNodeWithText("Valider").performClick()
        compose.waitForIdle()
    }

    @Test fun anObstacleBecomesANamedNoGo() {
        show()
        compose.onNodeWithText("Obstacle").performClick()
        compose.onNodeWithText("Nouvel obstacle").assertExists()
        validate()
        val added = saves.last().single() as AddedNoGo
        assertEquals(NoGoOrigin.OBSTACLE, added.origin)
        assertTrue(added.label.startsWith("Arbre"))
        val corrected = MapCorrections.apply(raw, saves.last()).map
        assertEquals(3, corrected.shapes.size)
        assertEquals(raw.shapes, corrected.originalShapes)
    }

    @Test fun aZoneIsChosenByATouchAndAShapeIsAdded() {
        show()
        // Top of the screen: the 20 × 10 m zone is framed above the panel.
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.25f, height * 0.30f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Pelouse").assertExists()
        compose.onNodeWithText("Forme").performClick()
        // Removing a square in the middle of the zone would make a hole: refused, nothing to validate.
        compose.onNodeWithText("Retirer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("La correction creuserait un trou : créez plutôt une no-go").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(saves.isEmpty())
        compose.onNodeWithText("Ajouter").performClick()
        validate()
        val patch = saves.last().single() as ShapePatch
        assertEquals(zone.id, patch.target)
        assertEquals(CorrectionTool.SHAPE, patch.tool)
        assertFalse(patch.removing)
        assertNotNull(patch.primitive)
    }

    @Test fun aDeletedCorrectionCanBeRestored() {
        val offset = ShapeOffset(1, 1L, "Pelouse · décalage +50 cm", zone.id, null, 0.5)
        show(listOf(offset))
        compose.onNodeWithContentDescription("Corrections").performClick()
        compose.onNodeWithText("Pelouse · décalage +50 cm").assertExists()
        compose.onNodeWithText("Supprimer").performClick()
        compose.waitForIdle()
        assertTrue(saves.last().isEmpty())
        compose.onAllNodesWithText("Annuler")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(offset), saves.last())
    }
}
