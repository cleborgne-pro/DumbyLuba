package fr.luba.poc.map

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MowingTaskSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun lastMowingSettingsAreRestoredAfterARestart() {
        val store = MowingTaskStore(context)
        assertEquals(MowingTaskSettings(), store.read())
        val chosen = MowingTaskSettings(heightMm = 45f, speedMps = 0.45f, mode = CoverageMode.CHECKERBOARD, spacingM = 0.2f,
            borderPasses = 2, noGoPasses = 0, bordersFirst = false, nearbyNoGo = false, chargeLow = 25f, chargeHigh = 95f)
        store.write(chosen)
        // New store: as after an app restart.
        assertEquals(chosen, MowingTaskStore(context).read())
    }

    @Test fun storedValuesAreBoundedToTheSliders() {
        val wild = MowingTaskSettings(heightMm = 200f, speedMps = Float.NaN, mode = CoverageMode.TOUR, spacingM = 0.01f,
            borderPasses = 9, noGoPasses = -1, chargeLow = 50f, chargeHigh = 52f).sanitized()
        // Every model (H versions up to 100 mm): the Map screen then brings it into the connected robot's range.
        assertEquals(100f, wild.heightMm)
        assertEquals(0.3f, wild.speedMps)
        assertEquals(CoverageMode.LINES, wild.mode)
        assertEquals(0.05f, wild.spacingM)
        assertEquals(4, wild.borderPasses)
        assertEquals(0, wild.noGoPasses)
        assertEquals(20f, wild.chargeLow); assertEquals(90f, wild.chargeHigh)
        // Return above 80 %: brought back to 80 %, like the slider.
        val high = MowingTaskSettings(chargeLow = 85f, chargeHigh = 95f).sanitized()
        assertEquals(80f, high.chargeLow); assertEquals(95f, high.chargeHigh)
    }

    @Test fun outerOffsetGoesUpToOneMetreAndIsKept() {
        OuterOffsetSetting.init(context)
        OuterOffsetSetting.set(1.0)
        assertEquals(1.0, OuterOffsetSetting.meters, 1e-9)
        assertEquals(1.0, BorderFollow.MAX_OFFSET_M, 1e-9)
        // Since 0.40.0: margin from the side of the robot, new key (the old one measured from the center).
        assertEquals(1.0, context.getSharedPreferences("planner", 0).getFloat("outer_clearance_m", 0f).toDouble(), 1e-6)
        OuterOffsetSetting.set(OuterOffsetSetting.DEFAULT_M)
    }
}
