package fr.luba.poc.map

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.ui.theme.LubaTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Map view logic exercised through the interface: route preview (estimated duration, start chosen with a tap, zoom),
 * live map (tap and brush converted into meters), layer bar, sheet of an encountered obstacle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class MapComposeTest {
    // The test activity (ui-test-manifest) only exists in debug.
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() { override fun evaluate() { org.junit.Assume.assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() } }
    }).around(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 10.0), Point(0.0, 10.0)), "Pelouse")
    private val map = RobotMap(listOf(zone), true, "test")
    private val plan = Preview(runs = listOf(listOf(Point(1.0, 1.0), Point(19.0, 1.0)), listOf(Point(19.0, 2.0), Point(1.0, 2.0))),
        meters = 840.0, angle = 0, connectors = 1, zoneIds = listOf(1L))

    @After fun reset() {
        MapExtraLayers.close(); MapExtraLayers.actions = null; MapExtraLayers.latest = DifficultyUi()
        if (MapExtraLayer.DIFFICULTIES in MapExtraLayers.flow.value) MapExtraLayers.toggle(MapExtraLayer.DIFFICULTIES)
        if (MapExtraLayer.ENCOUNTERS !in MapExtraLayers.flow.value) MapExtraLayers.toggle(MapExtraLayer.ENCOUNTERS)
    }

    /** Animations off and manual clock: the map animates the robot and the comet without ever being idle. */
    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
        compose.setContent { LubaTheme(false) { content() } }
        frames()
    }
    private fun frames(count: Int = 5) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    @Test fun `preview, estimated duration and recharges, area, distance`() {
        show { Column { MowingPreview(map, plan, emptyList(), MowingEstimate(3_900.0, 2), 0.3f) } }
        compose.onNodeWithText("≈ 1 h 5 min").assertExists()
        compose.onNodeWithText("≈ 2").assertExists()
        compose.onNodeWithText("200 m²").assertExists()
        compose.onNodeWithText("840 m").assertExists()
        compose.onNodeWithText("Glisser · pincer pour zoomer · tourner à deux doigts").assertExists()
    }

    @Test fun `preview without detailed estimate, simplified estimate from the distance`() {
        val simple = MowingEstimate.fromDistance(840.0, 0.3, 60.0, 30.0, 100.0)
        show { Column { MowingPreview(map, plan.copy(skippedBorderLoops = 2), emptyList(), null, 0.3f) } }
        val minutes = Math.ceil(simple.totalSeconds / 60).toInt()
        compose.onNodeWithText("≈ ${if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"}").assertExists()
        compose.onNodeWithText("2 fragment(s) de bordure trop étroit(s) pour manœuvrer : non parcouru(s).").assertExists()
    }

    @Test fun `preview being computed, pending values and displayed stage`() {
        show { Column { MowingPreview(map, null, emptyList(), null, 0.3f, computing = true, stage = "Ordre des zones") } }
        compose.onNodeWithText("Ordre des zones").assertExists()
        compose.onNodeWithText("—").assertExists()
        assertEquals(2, compose.onAllNodes(androidx.compose.ui.test.hasText("…")).fetchSemanticsNodes().size)
    }

    @Test fun `preview, start chosen with a tap on the map, zoom and recentering`() {
        val picked = CopyOnWriteArrayList<Point>()
        show { Column { MowingPreview(map, plan, emptyList(), MowingEstimate(600.0, 0), 0.3f, requestedStart = Point(2.0, 2.0), onPickStart = { picked += it }) } }
        compose.onNodeWithText("Touchez le point de départ · pincer pour zoomer").assertExists()
        compose.onNodeWithContentDescription("Zoomer").performClick(); frames()
        compose.onNodeWithText("×1,4").assertExists()
        compose.onNodeWithContentDescription("Recentrer la carte").performClick(); frames()
        compose.onNodeWithText("×1,0").assertExists()
        compose.onNodeWithText("Touchez le point de départ · pincer pour zoomer").performTouchInput {
            // Tap above the label: on the map, near its bottom left corner.
            click(Offset(centerX, top - 60f))
        }
        frames(20)
        assertEquals(1, picked.size)
        // Map point in meters (near the bottom of the view), not in pixels.
        assertTrue("${picked.single()}", picked.single().x in -5.0..25.0 && picked.single().y in -15.0..15.0)
    }

    @Test fun `live map, tap converted into meters at the map center`() {
        val taps = CopyOnWriteArrayList<Point>()
        show { Box(Modifier.size(300.dp).testTag("carte")) { MapLive(map, Modifier.size(300.dp), onTap = { taps += it }) } }
        compose.onNodeWithTag("carte").performTouchInput { click(center) }
        frames(20)
        assertEquals(10.0, taps.single().x, 0.1)
        assertEquals(5.0, taps.single().y, 0.1)
    }

    @Test fun `brush, one finger paints, two fingers move the view without painting`() {
        val events = CopyOnWriteArrayList<String>()
        val input = MapPaintInput({ events += "début" }, { events += "trait" }, { events += if (it) "fin" else "abandon" })
        show { Box(Modifier.size(300.dp).testTag("carte")) { MapLive(map, Modifier.size(300.dp), onPaint = input) } }
        compose.onNodeWithTag("carte").performTouchInput { down(center); moveBy(Offset(80f, 0f)); moveBy(Offset(80f, 10f)); up() }
        frames()
        assertEquals("début", events.first()); assertEquals("fin", events.last())
        assertTrue(events.contains("trait"))
        events.clear()
        compose.onNodeWithTag("carte").performTouchInput {
            down(0, center); down(1, center + Offset(100f, 0f))
            moveBy(0, Offset(-40f, 0f)); moveBy(1, Offset(40f, 0f)); up(0); up(1)
        }
        frames()
        assertTrue("pinch: nothing painted ($events)", events.none { it == "début" || it == "fin" })
    }

    @Test fun `robot following, robot brought to the center of the visible part, zoom and rotation kept`() {
        val follow = RobotFollow(enabled = true)
        val camera = MapCamera(zoom = 2.0, rotation = 30.0)
        val points = map.shapes.flatMap { it.points }
        val robot = androidx.compose.runtime.mutableStateOf<DisplayPose?>(DisplayPose(Point(15.0, 7.0), 90.0))
        val size = androidx.compose.ui.unit.IntSize(400, 800)
        show { RobotFollowDriver(follow, camera, robot.value, size, top = 100f, bottom = 300f) { s -> fitProjection(points, s.width, s.height) } }
        val view = follow.view(camera)
        assertEquals(camera.zoom, view.zoom, 1e-9); assertEquals(camera.rotation, view.rotation, 1e-9)
        // The robot point on the layer ends up at the center of the visible band (between header and bottom panels).
        val onLayer = view.inverse(Point(200.0, 300.0), Point(200.0, 400.0))
        val expected = fitProjection(points, 400f, 800f).screen(Point(15.0, 7.0))
        assertEquals(expected.x.toDouble(), onLayer.x, 0.5); assertEquals(expected.y.toDouble(), onLayer.y, 0.5)
        // Position lost: no more forced recentering, user view.
        compose.runOnUiThread { robot.value = null; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        frames(10)
        assertNull(follow.offset)
        assertSame(camera, follow.view(camera))
    }

    private val tilted = listOf(SignalSample(Point(1.0, 1.0), 0, -60, null, null, 4, 20, heading = 0.0, gravity = PhoneGravity(0.0, 0.5, 9.8)))
    private val relief = ReliefResult(listOf(SignalCell(Point(1.5, 1.5), 8.0, 2)), listOf(SignalCell(Point(1.5, 1.5), 0.1, 2, 0.5f)), 0.1,
        0.0, 0.0, offsetEstimated = false, used = 2, components = 1)

    @Test fun `heatmap settings, color, hide, shading, orientation, reference, 3D view`() {
        val actions = CopyOnWriteArrayList<String>()
        show {
            Column {
                SignalReliefControls(signalReliefView(tilted, SurveySignal.WIFI, relief, shading = true), tilted,
                    onSignal = { actions += "couleur ${it.name}" }, onShading = { actions += "ombrage $it" },
                    mount = PhoneMount.AUTO, onMount = { actions += "pose ${it.name}" }, on3d = { actions += "3D" },
                    onHide = { actions += "masquer" }, onCalibrate = { actions += "référence" })
            }
        }
        compose.onNodeWithText("RTK FIX").performClick()
        compose.onNodeWithText("Masquer").performClick()
        compose.onNodeWithText("Relief en ombrage").performClick()
        compose.onNodeWithText("Haut vers la gauche").performClick()
        compose.onNodeWithText("Enregistrer l'inclinaison et l'orientation actuelles").performClick()
        compose.onNodeWithText("Vue 3D : Wi-Fi sur le relief").performClick()
        assertEquals(listOf("couleur RTK", "masquer", "ombrage false", "pose TOP_LEFT", "référence", "3D"), actions)
        // "Saved" orientation offered only with a reference that contains it.
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasText("Enregistrée")).fetchSemanticsNodes().size)
    }

    @Test fun `Terrain color, shading not applicable, 3D view without height explained`() {
        show {
            Column {
                SignalReliefControls(signalReliefView(tilted, SurveySignal.RELIEF, relief, shading = true), tilted, {}, {},
                    PhoneMount.CALIBRATED, {}, null, calibration = TiltCalibration(PhoneGravity(0.0, 0.0, 9.8), 90.0, 0))
                Relief3DContent(signalReliefView(tilted, SurveySignal.WIFI, relief.copy(heightCells = emptyList()), true), map, "Légende", 5f) {}
                Relief3DContent(signalReliefView(tilted, SurveySignal.RELIEF, relief, true), map, "", 5f) {}
            }
        }
        compose.onNodeWithText("Sans objet : la couleur montre déjà le relief").assertExists()
        compose.onNodeWithText("Enregistrée").assertExists()
        compose.onNodeWithText("Aucune hauteur reconstruite pour ce relevé.").assertExists()
        compose.onNodeWithText("Légende").assertExists()
    }

    @Test fun `layer bar, choose, hide, back to the continuous map`() {
        val actions = CopyOnWriteArrayList<String>()
        show {
            MapLayerBar(listOf(SurveySignal.WIFI, SurveySignal.RTK), SurveySignal.WIFI, visible = true, simulated = true,
                onSelect = { actions += "choix ${it.name}" }, onToggleVisible = { actions += "œil" }, onSettings = { actions += "réglages" },
                source = "Relevé du 12/10", onClearSource = { actions += "continue" })
        }
        compose.onNodeWithText("Wi-Fi").assertIsSelected()
        compose.onNodeWithText("RTK").performClick()
        compose.onNodeWithText("Wi-Fi").performClick()
        compose.onNodeWithContentDescription("Masquer la heatmap").performClick()
        compose.onNodeWithContentDescription("Revenir à la carte continue").performClick()
        compose.onNodeWithContentDescription("Réglages, légende et historique des signaux").performClick()
        compose.onNodeWithText("Simulé").assertExists()
        assertEquals(listOf("choix RTK", "œil", "œil", "continue", "réglages"), actions)
        // Difficulties toggle: independent of the signal, kept by MapExtraLayers.
        compose.onNodeWithContentDescription(MapExtraLayer.DIFFICULTIES.description).performClick()
        assertTrue(MapExtraLayer.DIFFICULTIES in MapExtraLayers.flow.value)
    }

    @Test fun `layer bar without survey, message and access to the history`() {
        show { MapLayerBar(emptyList(), SurveySignal.WIFI, visible = true, simulated = false, onSelect = {}, onToggleVisible = {}, onSettings = {}, showExtras = false) }
        compose.onNodeWithText("Aucun relevé").assertExists()
        compose.onNodeWithContentDescription("Historique des heatmaps").assertExists()
    }

    @Test fun `encountered obstacle sheet, dismiss or save with its size`() {
        val contact = DifficultyEvent("c1", 1_760_000_000_000L, DifficultyKind.CONTACT, 4.5, 2.0, code = 1014, obstacleX = 5.0, obstacleY = 2.0,
            source = "tonte", side = 1, inner = true)
        val ui = DifficultyUi(events = listOf(contact))
        val received = CopyOnWriteArrayList<DifficultyAction>()
        MapExtraLayers.actions = { received += it }
        show { EncounterSheet(ui, emptyList()) }
        MapExtraLayers.open(contact); frames()
        compose.onNodeWithText("Obstacle rencontré").assertExists()
        compose.onNodeWithText("par la gauche, côté intérieur").assertExists()
        compose.onNodeWithText("Pas de photo pour ce contact (option désactivée, compte non connecté ou flux indisponible).").assertExists()
        compose.onNodeWithText("Écarter").performClick(); frames()
        assertEquals(listOf<DifficultyAction>(DifficultyAction.Dismiss("c1")), received)
        assertNull(MapExtraLayers.openedEncounter.value)
        MapExtraLayers.open(contact); frames()
        compose.onNodeWithText("Enregistrer l'obstacle").performClick(); frames()
        val register = received.last() as DifficultyAction.Register
        assertEquals(5.0, register.obstacle.x, 1e-9); assertEquals(2.0, register.obstacle.y, 1e-9)
        assertEquals(ObstacleKind.OTHER, register.obstacle.kind)
        assertTrue(register.obstacle.label, register.obstacle.label.startsWith("Obstacle rencontré le "))
        assertNull(MapExtraLayers.openedEncounter.value)
    }
}
