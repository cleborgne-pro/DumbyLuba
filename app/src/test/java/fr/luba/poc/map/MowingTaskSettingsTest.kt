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

    @Test fun directionOfThePassesIsKeptAndFolded() {
        val store = MowingTaskStore(context)
        val chosen = MowingTaskSettings(mode = CoverageMode.CHECKERBOARD, orientation = OrientationMode.MANUAL, manualAngleDeg = 35)
        store.write(chosen)
        assertEquals(chosen, MowingTaskStore(context).read())
        store.write(MowingTaskSettings(orientation = OrientationMode.EACH_SESSION))
        assertEquals(OrientationMode.EACH_SESSION, MowingTaskStore(context).read().orientation)
        // Angle folded into 0..179, like the direction of a pass.
        assertEquals(20, MowingTaskSettings(manualAngleDeg = 200).sanitized().manualAngleDeg)
        assertEquals(170, MowingTaskSettings(manualAngleDeg = -10).sanitized().manualAngleDeg)
        assertEquals(0, MowingTaskSettings(manualAngleDeg = 180).sanitized().manualAngleDeg)
    }

    @Test fun unknownNamesAndOlderFilesFallBackToTheDefaults() {
        val prefs = context.getSharedPreferences("mowing_task", 0)
        prefs.edit().clear().putString("mode", "SPIRAL_FROM_THE_FUTURE").putString("orientation_mode", "SIDEWAYS")
            .putInt("manual_angle_deg", 400).commit()
        val read = MowingTaskStore(context).read()
        assertEquals(CoverageMode.LINES, read.mode)
        assertEquals(OrientationMode.AUTOMATIC, read.orientation)
        assertEquals(40, read.manualAngleDeg)
        // File written before the direction setting: automatic, 0°.
        prefs.edit().clear().putString("mode", CoverageMode.OPTIMIZED.name).putFloat("spacing_m", 0.3f).commit()
        val older = MowingTaskStore(context).read()
        assertEquals(CoverageMode.OPTIMIZED, older.mode)
        assertEquals(OrientationMode.AUTOMATIC, older.orientation)
        assertEquals(0, older.manualAngleDeg)
    }

    @Test fun zamboniIsAMowingModeKeptByNameWithTheDirectionOfThePasses() {
        val store = MowingTaskStore(context)
        val chosen = MowingTaskSettings(mode = CoverageMode.ZAMBONI, orientation = OrientationMode.MANUAL, manualAngleDeg = 120)
        assertEquals(chosen, chosen.sanitized())
        store.write(chosen)
        assertEquals(chosen, MowingTaskStore(context).read())
        assertEquals("ZAMBONI", context.getSharedPreferences("mowing_task", 0).getString("mode", null))
        assertTrue(CoverageMode.ZAMBONI.usesOrientation)
        assertEquals(180, CoverageMode.ZAMBONI.orientationPeriod)
        // Appended at the end (Spiral after it): the names and positions of the older modes are unchanged.
        assertEquals(listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD, CoverageMode.TOUR, CoverageMode.SURVEY_GRID, CoverageMode.OPTIMIZED,
            CoverageMode.ZAMBONI, CoverageMode.SPIRAL), CoverageMode.entries)
        assertEquals("Zamboni", CoverageMode.ZAMBONI.label)
    }

    @Test fun spiralIsAMowingModeKeptByNameWithoutDirectionOfThePasses() {
        val store = MowingTaskStore(context)
        // The saved direction stays for the next zigzag, unused by the spiral.
        val chosen = MowingTaskSettings(mode = CoverageMode.SPIRAL, orientation = OrientationMode.MANUAL, manualAngleDeg = 35)
        assertEquals(chosen, chosen.sanitized())
        store.write(chosen)
        assertEquals(chosen, MowingTaskStore(context).read())
        assertEquals("SPIRAL", context.getSharedPreferences("mowing_task", 0).getString("mode", null))
        assertFalse(CoverageMode.SPIRAL.usesOrientation)
        assertFalse(CoverageMode.SPIRAL.isSurvey)
        assertEquals(CoverageMode.entries.last(), CoverageMode.SPIRAL)
        assertEquals("Spirale", CoverageMode.SPIRAL.label)
        assertTrue(orientationUnusedNote().contains("Spirale"))
    }

    @Test fun spiralDirectionIsKeptByNameAndDefaultsToTheEdge() {
        val store = MowingTaskStore(context)
        val prefs = context.getSharedPreferences("mowing_task", 0)
        assertEquals(SpiralDirection.FROM_EDGE, MowingTaskSettings().spiralDirection)
        val chosen = MowingTaskSettings(mode = CoverageMode.SPIRAL, spiralDirection = SpiralDirection.FROM_CENTRE)
        assertEquals(chosen, chosen.sanitized())
        store.write(chosen)
        assertEquals(chosen, MowingTaskStore(context).read())
        assertEquals("FROM_CENTRE", prefs.getString("spiral_direction", null))
        // Kept while another mode is chosen, for the next spiral.
        store.write(chosen.copy(mode = CoverageMode.LINES))
        assertEquals(SpiralDirection.FROM_CENTRE, MowingTaskStore(context).read().spiralDirection)
        // Unknown name or file written before the setting: from the edge, the behaviour before it.
        prefs.edit().clear().putString("mode", CoverageMode.SPIRAL.name).putString("spiral_direction", "SIDEWAYS").commit()
        assertEquals(SpiralDirection.FROM_EDGE, MowingTaskStore(context).read().spiralDirection)
        prefs.edit().clear().putString("mode", CoverageMode.SPIRAL.name).commit()
        assertEquals(SpiralDirection.FROM_EDGE, MowingTaskStore(context).read().spiralDirection)
        // Labels, note and summary of the preview (unit tests run in French).
        assertEquals(listOf("Depuis le bord", "Depuis le centre"), SpiralDirection.entries.map { it.label })
        assertTrue(spiralNote(SpiralDirection.FROM_CENTRE).contains("du centre vers le contour"))
        assertTrue(spiralNote().contains("vers l'intérieur"))
        assertEquals("Spirale depuis le centre (expérimental)", spiralSummary(SpiralDirection.FROM_CENTRE))
        assertEquals("Spirale depuis le bord (expérimental)", spiralSummary(SpiralDirection.FROM_EDGE))
    }

    @Test fun returnReserveIsKeptAndBoundedByTheLowThreshold() {
        val store = MowingTaskStore(context)
        store.write(MowingTaskSettings(chargeLow = 25f, chargeHigh = 90f, returnReserve = 15f))
        assertEquals(15f, MowingTaskStore(context).read().returnReserve)
        // Never above the low threshold, never below the tracking safety reserve.
        assertEquals(12f, MowingTaskSettings(chargeLow = 12f, returnReserve = 20f).sanitized().returnReserve)
        assertEquals(5f, MowingTaskSettings(returnReserve = 1f).sanitized().returnReserve)
        assertEquals(30f, MowingTaskSettings(chargeLow = 40f, returnReserve = 80f).sanitized().returnReserve)
        assertEquals(10f, MowingTaskSettings(returnReserve = Float.NaN).sanitized().returnReserve)
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
