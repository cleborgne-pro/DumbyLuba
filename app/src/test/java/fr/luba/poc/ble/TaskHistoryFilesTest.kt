package fr.luba.poc.ble

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.history.MowingForecaster
import fr.luba.poc.history.MowingHistoryStore
import fr.luba.poc.history.MowingRecord
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Local files of the task history: a write that fails leaves a trace in the log instead of vanishing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskHistoryFilesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val blockedTemp = File(app.filesDir, "mowing-history.json.tmp")
    private var bench: RobotBench? = null

    @After fun tearDown() {
        bench?.close()
        blockedTemp.deleteRecursively()
        File(app.filesDir, "mowing-history.json").delete()
    }

    @Test(timeout = 900_000) fun `a history that cannot be saved is logged`() {
        // Task still running when the app closed: closed at load time, then saved again. A directory in place of
        // the temporary file makes that save fail.
        val forecast = MowingForecaster.forecast(100.0, 0.3, 60.0, 30.0, 100.0, 20.0, 90.0, null)
        MowingHistoryStore(app.filesDir).save(listOf(MowingRecord("AA-1", "AA:BB:CC:DD:EE:FF", startedAt = 1_000L, forecast = forecast)))
        blockedTemp.mkdirs()

        val robot = RobotBench().also { bench = it }
        robot.awaitReal(30_000, "history save failure logged") { robot.state.events.any { "Enregistrement impossible (history)" in it } }
    }
}
