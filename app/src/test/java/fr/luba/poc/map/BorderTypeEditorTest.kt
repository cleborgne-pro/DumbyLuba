package fr.luba.poc.map

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Border types editor exercised through the interface: portion chosen by two taps on the boundary then typed, retyped
 * by tapping it, deleted then restored with "Annuler", and a whole boundary typed at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class BorderTypeEditorTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { org.junit.Assume.assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 10.0), Point(0.0, 10.0)), "Pelouse")
    private val map = RobotMap(listOf(zone), true, "test")
    private val saves = mutableListOf<List<BorderAnnotation>>()

    private fun show(initial: List<BorderAnnotation> = emptyList()) {
        compose.setContent {
            var list by androidx.compose.runtime.remember { mutableStateOf(initial) }
            LubaTheme { BorderTypeEditor(map, list, onSave = { saves.add(it); list = it; true }, onClose = {}) }
        }
        compose.waitForIdle()
    }

    /** Tap on the map point [p] (default camera: framing of the editor, header 96 dp, panel 40 % of the height). */
    private fun tapAt(p: Point) {
        compose.onRoot().performTouchInput {
            val proj = mapScreenProjection(MapBounds.of(zone.points), Size(width.toFloat(), height.toFloat()), 96 * density, height * 0.40f)
            click(proj.screen(p))
        }
        compose.waitForIdle()
    }

    @Test fun aPortionIsChosenByTwoTapsThenTyped() {
        show()
        tapAt(Point(4.0, 0.0))
        compose.onNodeWithText("Touchez l'autre extrémité sur le même contour").assertExists()
        tapAt(Point(16.0, 0.0))
        compose.onNodeWithText("Valider").assertIsNotEnabled()
        compose.onNodeWithText("Mur").performClick()
        compose.onNodeWithText("Valider").assertIsEnabled().performClick()
        compose.waitForIdle()
        val saved = saves.last().single()
        assertEquals(BorderType.WALL, saved.type)
        assertEquals(zone.id, saved.shapeId)
        assertFalse(saved.whole)
        assertEquals(12.0, BorderTypes.resolve(saved, map).lengthM, 0.3)
        assertEquals(BorderType.WALL, BorderTypes.typeAt(map, saves.last(), zone.id, Point(10.0, 0.0)))
        assertNull(BorderTypes.typeAt(map, saves.last(), zone.id, Point(10.0, 10.0)))
    }

    @Test fun aTypedPortionIsRetypedDeletedAndRestored() {
        val wall = BorderTypes.annotate(zone, requireNotNull(BorderTypes.select(zone, Point(4.0, 0.0), Point(16.0, 0.0))), BorderType.WALL, 1, 1)
        show(listOf(wall))
        tapAt(Point(10.0, 0.0))
        compose.onNodeWithText("Mur · 12 m").assertExists()
        compose.onNodeWithText("Danger").performClick()
        compose.waitForIdle()
        assertEquals(listOf(BorderType.DANGER), saves.last().map { it.type })
        tapAt(Point(10.0, 0.0))
        compose.onNodeWithText("Supprimer").performClick()
        compose.waitForIdle()
        assertTrue(saves.last().isEmpty())
        compose.onAllNodesWithText("Annuler")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(BorderType.DANGER), saves.last().map { it.type })
    }

    @Test fun aWholeBoundaryIsTypedAtOnce() {
        show()
        tapAt(Point(0.0, 5.0))
        compose.onNodeWithText("Tout le contour").performClick()
        compose.onNodeWithText("Bordure").performClick()
        compose.onNodeWithText("Valider").performClick()
        compose.waitForIdle()
        val saved = saves.last().single()
        assertTrue(saved.whole)
        assertEquals(listOf(BorderType.CURB to 60.0), BorderTypes.portions(map, saves.last()).map { it.type to it.lengthM })
    }
}
