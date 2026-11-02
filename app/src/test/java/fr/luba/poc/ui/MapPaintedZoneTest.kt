package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.BorderTypeRepository
import fr.luba.poc.map.MapScreen
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.PaintDraft
import fr.luba.poc.map.PaintStroke
import fr.luba.poc.map.PaintedZone
import fr.luba.poc.map.Point
import fr.luba.poc.map.Preview
import fr.luba.poc.map.RobotMap
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.BaseStore
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MissionTask
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.withComposeTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Mow → zone choice → "Paint a zone": the painted part of a zone is the target of the usual task (settings, preview,
 * start), its route prepared on the robot map with the painted zones as continuous-blade zones.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class MapPaintedZoneTest {
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = withComposeTestActivity(compose)

    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 12.0), Point(0.0, 12.0)), "Pelouse")
    private val map = RobotMap(listOf(zone), true, "test")
    private val state = RobotUi(robotMap = map, connection = ConnectionState.Receiving, ready = true,
        telemetry = Telemetry(battery = 80, x = 10.0, y = 9.0, heading = 0.0, robotState = 11))
    private val stroke = PaintStroke(listOf(Point(10.0, 5.0), Point(15.0, 5.0)), 1.2)

    @After fun clearPainting() = PaintDraft.update(map) { emptyList() }

    @Test fun `painted part of a zone is mowed by the usual task on the robot map`() {
        // Station sent by the robot on the west edge, docking axis known: the route can be prepared and started.
        BaseStore(ApplicationProvider.getApplicationContext(), map).save(MapBase(Point(1.0, 6.0), "Transmise par le robot", 1.5, 180.0, 1.5))
        PaintDraft.update(map) { listOf(stroke) }
        BorderTypeRepository.resetForTest()
        var preview: Preview? = null
        var started: MowingSimulation? = null
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(state, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {}, openMowing = true,
                onPreview = { preview = it }, onStartRobotRoute = { engine, _, _ -> started = engine; true })
        } } }
        compose.waitForIdle()
        compose.onNodeWithText("Peindre une zone").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("1 zone peinte", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Suivant").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1. Zone peinte 1", substring = true).assertExists()
        compose.onNodeWithText("Aperçu du trajet").performClick()
        compose.awaitCurrentRoute { preview }
        val planned = requireNotNull(preview) { "no preview" }
        assertTrue("temporary zones planned: ${planned.zoneIds}", planned.zoneIds.isNotEmpty() && planned.zoneIds.all(PaintedZone::isTemporary))
        // Passes only in the painted part (brush 1.2 m around the stroke), not over the whole lawn.
        val area = PaintedZone.painted(listOf(stroke)).buffer(0.05)
        val factory = GeometryFactory()
        planned.cuttingPaths.flatten().forEach { p ->
            assertTrue("pass point $p outside the painting", area.covers(factory.createPoint(Coordinate(p.x, p.y))))
        }
        compose.waitUntil(120_000) { runCatching { compose.onNodeWithText("Démarrer").assertIsEnabled() }.isSuccess }
        compose.onNodeWithText("Démarrer").performClick()
        val engine = requireNotNull(started) { "route not started" }
        assertSame("route prepared on the robot map", map, engine.map)
        assertEquals(1, engine.bladeZones?.size)
        assertEquals(MissionTask.TRIAL, MissionTask.of(engine.preview, cutting = true, bladeZones = engine.bladeZones))
    }
}
