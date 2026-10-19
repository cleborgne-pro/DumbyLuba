package fr.luba.poc.ui

import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import fr.luba.poc.ble.ConnectionState
import fr.luba.poc.ble.RobotUi
import fr.luba.poc.map.*
import fr.luba.poc.mowing.MowPhase
import fr.luba.poc.mowing.MowUi
import fr.luba.poc.protocol.Telemetry
import fr.luba.poc.simulation.SimulationSession
import fr.luba.poc.ui.theme.LubaTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * During mowing, opening "Map" must neither shift the map nor move the modal on every position
 * reading. System animations off: effects frozen, pixel-exact comparison.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class MapCarteStabilityTest {
    // The test activity (ui-test-manifest) only exists in debug: the shipped variant (fold) does not include this manifest.
    @get:Rule val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { org.junit.Assume.assumeTrue(fr.luba.poc.BuildConfig.BUILD_TYPE == "debug"); base.evaluate() }
        }
    }).around(createAndroidComposeRule<ComponentActivity>().also { compose = it })
    private lateinit var compose: androidx.compose.ui.test.junit4.AndroidComposeTestRule<androidx.test.ext.junit.rules.ActivityScenarioRule<ComponentActivity>, ComponentActivity>

    private val map: RobotMap by lazy {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val shapes = json.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        RobotMap(shapes, true, "reference-station", MapStation(Point(0.142, 1.286), 152.86, "Transmise par le robot"))
    }

    private fun mowing(at: Long, x: Double) = RobotUi(robotMap = map, connection = ConnectionState.Receiving, ready = true,
        mowing = MowUi(phase = MowPhase.WORKING, status = MowPhase.WORKING.label),
        telemetry = Telemetry(battery = 87, x = x, y = 3.0, heading = 40.0, rtkStatus = 4, positioningLevel = 0,
            lastPositionAt = at, lastRtkAt = at, lastReportAt = at))

    private fun View.pixels(): IntArray {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(android.graphics.Canvas(bmp))
        return IntArray(width * height).also { bmp.getPixels(it, 0, width, 0, 0, width, height) }
    }
    private fun changed(a: IntArray, b: IntArray) = a.indices.count { a[it] != b[it] }

    @Test fun carteOpenedDuringMowingKeepsMapAndDialogStill() {
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var state by mutableStateOf(mowing(System.currentTimeMillis(), 2.0))
        // Manual clock: the map runs clock loops (position, effects) that never leave the UI idle.
        compose.mainClock.autoAdvance = false
        compose.setContent { LubaTheme(false) { Box(Modifier.fillMaxSize()) {
            MapScreen(state, SimulationSession(), onFetchMap = {}, onOpenRobot = {}, onOpenAccount = {}, onBack = {})
        } } }
        // The position display clock follows real time: a reading at the same point, dated now, before each
        // capture (otherwise the robot switches to "old position" when the machine is slow).
        fun fresh() { state = mowing(System.currentTimeMillis(), 2.0); compose.mainClock.advanceTimeBy(100) }
        compose.mainClock.advanceTimeBy(1500)
        val main = compose.activity.window.decorView
        fresh()
        val before = main.pixels()

        compose.onAllNodesWithText("Carte")[0].performClick()
        compose.mainClock.advanceTimeBy(1500)
        fresh()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("The \"Map\" modal must open", dialog)
        assertEquals("The map does not move when \"Map\" opens", 0, changed(before, main.pixels()))

        // "Map" sheet (ModalBottomSheet, 0.56.0): its animated entry starts on the next frame.
        var shown = dialog.window!!.decorView.pixels()
        repeat(5) { if (shown.toSet().size <= 10) { compose.mainClock.advanceTimeBy(1000); fresh(); shown = dialog.window!!.decorView.pixels() } }
        assert(shown.toSet().size > 10) { "Capture de la modale vide" }
        for (k in 1..4) {
            state = mowing(System.currentTimeMillis(), 2.0 + k * 0.3)
            compose.mainClock.advanceTimeBy(1000)
            val next = dialog.window!!.decorView.pixels()
            assertEquals("The modal stays still at reading $k", 0, changed(shown, next))
            shown = next
        }
    }
}
