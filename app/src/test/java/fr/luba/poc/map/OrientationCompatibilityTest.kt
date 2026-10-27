package fr.luba.poc.map

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Direction of the passes read back from older or damaged settings: never an error, never an angle out of 0..179. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrientationCompatibilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val prefs get() = context.getSharedPreferences("mowing_task", 0)

    @Test fun outOfRangeStoredAnglesAreFoldedAndAcceptedByThePlanner() {
        for ((stored, expected) in listOf(200 to 20, -5 to 175, 179 to 179, 180 to 0, 360 to 0, -185 to 175, Int.MIN_VALUE to foldAngle(Int.MIN_VALUE))) {
            prefs.edit().clear().putString("orientation_mode", OrientationMode.MANUAL.name).putInt("manual_angle_deg", stored).commit()
            val read = MowingTaskStore(context).read()
            assertEquals("stored $stored", OrientationMode.MANUAL, read.orientation)
            assertEquals("stored $stored", expected, read.manualAngleDeg)
            assertTrue("stored $stored", read.manualAngleDeg in 0..179)
            // The value read back always builds a planning configuration.
            fr.luba.poc.planning.PlanningConfig(orientation = read.orientation, manualAngleDeg = read.manualAngleDeg)
        }
    }

    @Test fun unknownOrMistypedModeNamesFallBackToAutomatic() {
        for (name in listOf("", "manual", "ZAMBONI", "AUTOMATIC ")) {
            prefs.edit().clear().putString("orientation_mode", name).commit()
            assertEquals("'$name'", OrientationMode.AUTOMATIC, MowingTaskStore(context).read().orientation)
        }
        // Mode key written with another type: automatic, no crash.
        prefs.edit().clear().putInt("orientation_mode", 2).commit()
        assertEquals(OrientationMode.AUTOMATIC, MowingTaskStore(context).read().orientation)
    }

    @Test fun savedFormStateFromBeforeTheSettingKeepsTheStoredDirection() {
        val saved = MowingTaskSettings(orientation = OrientationMode.MANUAL, manualAngleDeg = 35)
        val saver = MowingTaskForm.saver(saved)
        val current = with(saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(MowingTaskForm(saved)) }!!
        // 21 entries with the direction of the passes, 22 with the spiral direction.
        assertEquals(22, current.size)
        // Older state (19 entries): the stored settings stay.
        val older = saver.restore(current.take(19))!!
        assertEquals(OrientationMode.MANUAL, older.orientation)
        assertEquals(35, older.manualAngle)
        // Unknown name and out-of-range angle in the saved state: name ignored, angle folded.
        val damaged = saver.restore(current.take(19) + listOf("SIDEWAYS", 200))!!
        assertEquals(OrientationMode.MANUAL, damaged.orientation)
        assertEquals(20, damaged.manualAngle)
        val restored = saver.restore(current.take(19) + listOf(OrientationMode.EACH_SESSION.name, 90))!!
        assertEquals(OrientationMode.EACH_SESSION, restored.orientation)
        assertEquals(90, restored.manualAngle)
        assertEquals(OrientationMode.EACH_SESSION, restored.settings().orientation)
    }

    @Test fun savedFormStateFromBeforeTheSpiralDirectionKeepsTheStoredOne() {
        val saved = MowingTaskSettings(mode = CoverageMode.SPIRAL, spiralDirection = SpiralDirection.FROM_CENTRE)
        val saver = MowingTaskForm.saver(saved)
        val current = with(saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(MowingTaskForm(saved)) }!!
        // Older state (21 entries) or unknown name: the stored direction stays.
        assertEquals(SpiralDirection.FROM_CENTRE, saver.restore(current.take(21))!!.spiralDirection)
        assertEquals(SpiralDirection.FROM_CENTRE, saver.restore(current.take(21) + listOf("SIDEWAYS"))!!.spiralDirection)
        val restored = saver.restore(current.take(21) + listOf(SpiralDirection.FROM_EDGE.name))!!
        assertEquals(SpiralDirection.FROM_EDGE, restored.spiralDirection)
        assertEquals(SpiralDirection.FROM_EDGE, restored.settings().spiralDirection)
    }
}
