package fr.luba.poc.obstacles

import android.content.Context
import fr.luba.poc.i18n.tr
import java.io.File

/**
 * Local storage of point obstacles, in the app-private file. Local reads/writes only: no Mammotion no-go zone is
 * created, neither on the robot nor in the cloud.
 */
class ObstacleStore(private val context: Context) {
    private fun file(): File = File(context.filesDir, "obstacles.json")

    fun load(): List<LocalObstacle> = try {
        val stored = file()
        if (!stored.exists() || stored.length() > 4L * 1024 * 1024) emptyList()
        else ObstacleJson.decode(stored.readText())
    } catch (_: Exception) {
        emptyList()
    }

    fun save(obstacles: List<LocalObstacle>) {
        val stored = file()
        val temp = File(stored.path + ".tmp")
        temp.outputStream().use { stream ->
            stream.write(ObstacleJson.encode(obstacles).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temp.renameTo(stored)) { tr("Local obstacles not saved", "Obstacles locaux non enregistrés") }
    }
}
