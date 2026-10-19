package fr.luba.poc.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Product photos looked up by name; kept in the app and the publication until a removal request (RULES.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotModelImageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun modelWithoutPhotoUsesIllustration() {
        assertEquals(R.drawable.robot_illustration, RobotModel.UNKNOWN.image(context))
        assertEquals(R.drawable.robot_illustration, RobotModel.LUBA_OTHER.image(context))
    }

    @Test fun providedPhotoIsFoundByName() {
        val photos = RobotModel.entries.filter { it.named }
            .map { it.image(context) }.filter { it != R.drawable.robot_illustration }
        // Every named model has its own photo: the real images stay until a removal request.
        assertEquals(RobotModel.entries.count { it.named }, photos.size)
        assertEquals(photos.size, photos.toSet().size)
        assertNotEquals(R.drawable.robot_illustration, RobotModel.LUBA_2.image(context))
    }
}
