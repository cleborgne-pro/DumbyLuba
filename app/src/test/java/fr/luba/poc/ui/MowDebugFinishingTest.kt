package fr.luba.poc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotBle
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.history.CoverageGap
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.planning.FinishingOffer
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.BaseStore
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.ui.theme.LubaTheme
import fr.luba.poc.withComposeTestActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.GeometryFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Service → Finishing the gaps, opened from a mowing report: the route of the gaps is prepared and checked as soon as the
 * page opens. With the former Compute button, the prepared route was dropped whenever the page map differed from the
 * robot's ("Gaps or settings changed during computation: compute again").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h2200dp-xxhdpi")
class MowDebugFinishingTest {
    val compose = createComposeRule()
    @get:Rule val rules: RuleChain = withComposeTestActivity(compose)

    private val lawn = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 12.0), Point(0.0, 12.0)), "Pelouse")
    private val map = RobotMap(listOf(lawn), true, "test")
    private val robot by lazy { RobotBle(ApplicationProvider.getApplicationContext()) }
    private val state = RobotUi(robotMap = map, connection = ConnectionState.Receiving, ready = true,
        telemetry = Telemetry(battery = 80, x = 10.0, y = 9.0, heading = 0.0, robotState = 11))

    /** A 2 m × 1.5 m gap left in the middle of the lawn. */
    private fun offer(): FinishingOffer {
        val gap = GeometryFactory().toGeometry(Envelope(9.0, 11.0, 5.0, 6.5))
        return FinishingOffer("t", listOf(CoverageGap(1, gap.area, Point(10.0, 5.75), gap)), gap.area, 240.0, gap)
    }

    private fun texts(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
    private fun errors() = listOf("refusée", "impossible", "recalculez").flatMap { texts(it) }
        .flatMap { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty().map { it.text } }

    @After fun clearDraft() = FinishingDraft.set(map, null)

    @Test fun `finishing route is prepared on arrival, without a compute button`() {
        BaseStore(ApplicationProvider.getApplicationContext(), map).save(MapBase(Point(1.0, 6.0), "Transmise par le robot", 1.5, 180.0, 1.5))
        FinishingDraft.set(map, offer())
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) { MowDebugPage(robot, state, {}) {} } } }
        compose.waitForIdle()
        assertTrue("no compute button", texts("Calculer").isEmpty())
        compose.waitUntil(120_000) { texts("d'une nouvelle tonte (").isNotEmpty() || errors().isNotEmpty() }
        assertTrue("route not prepared: ${errors()}", texts("d'une nouvelle tonte (").isNotEmpty())
    }
}
