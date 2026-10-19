package fr.luba.poc.manual

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualSpeedTest {
    @Test fun `joystick maximum speed clamped and persisted`() {
        val prefs = ManualSpeed.preferences(ApplicationProvider.getApplicationContext())
        assertEquals(ManualSpeed.DEFAULT_PERCENT, ManualSpeed.read(prefs))
        ManualSpeed.save(prefs, 250)
        assertEquals(100, ManualSpeed.read(prefs))
        ManualSpeed.save(prefs, 3)
        assertEquals(10, ManualSpeed.read(prefs))
        ManualSpeed.save(prefs, 45)
        assertEquals(45, ManualSpeed.read(ManualSpeed.preferences(ApplicationProvider.getApplicationContext())))
    }
}
