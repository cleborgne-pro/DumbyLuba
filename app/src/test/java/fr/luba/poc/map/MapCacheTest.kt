package fr.luba.poc.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Local cache of complete maps: one per identifier, atomic write, bounded read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapCacheTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = MapShape(11, 0, listOf(Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 6.0), Point(0.0, 6.0)), "Pelouse")
    private val noGo = MapShape(12, 1, listOf(Point(2.0, 2.0), Point(3.0, 2.0), Point(3.0, 3.0)), "Massif")
    private val passage = MapShape(13, 2, listOf(Point(8.0, 3.0), Point(10.0, 3.0)))
    private val map = RobotMap(listOf(zone, noGo, passage), true, "Bluetooth", MapStation(Point(1.0, 1.0), 152.5, "Transmise par le robot"))

    private fun cacheFiles() = context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("map-") }

    @Test fun `complete map read back with its shapes and station`() {
        val cache = MapCache(context)
        assertNull(cache.read("robot-A"))
        cache.save("robot-A", map)
        val read = MapCache(context).read("robot-A")!!
        assertEquals(map.shapes, read.shapes)
        assertEquals(map.station, read.station)
        assertTrue(read.complete)
        assertTrue(read.source, read.source.startsWith("Cache local") && read.source.endsWith("Bluetooth"))
        // No temporary file left, one map per identifier.
        assertEquals(listOf(".json"), cacheFiles().map { it.name.takeLast(5) })
        assertNull(cache.read("robot-B"))
    }

    @Test fun `map of a Luba 2 AWD X with vision module zones is read back from the cache`() {
        // Types 25 (No-Stop) and 26 (vision obstacle): written but refused on reading up to 1.14.0, so read again at every connection.
        val noStop = MapShape(14, 25, listOf(Point(4.0, 1.0), Point(5.0, 1.0), Point(5.0, 2.0), Point(4.0, 2.0)))
        val visionObstacle = MapShape(15, 26, listOf(Point(6.0, 4.0), Point(7.0, 4.0), Point(7.0, 5.0), Point(6.0, 5.0)))
        val vp = map.copy(shapes = map.shapes + noStop + visionObstacle)
        MapCache(context).save("robot-VP", vp)
        assertEquals(vp.shapes, MapCache(context).read("robot-VP")?.shapes)
    }

    @Test fun `incomplete map never cached`() {
        MapCache(context).save("robot-A", map.copy(complete = false))
        assertNull(MapCache(context).read("robot-A"))
        assertTrue(cacheFiles().isEmpty())
    }

    @Test fun `station without heading and new save replacing the old one`() {
        val cache = MapCache(context)
        cache.save("robot-A", map)
        cache.save("robot-A", map.copy(shapes = listOf(zone), station = MapStation(Point(2.0, 2.0), null, "Observée en recharge")))
        val read = cache.read("robot-A")!!
        assertEquals(listOf(zone), read.shapes)
        assertEquals(MapStation(Point(2.0, 2.0), null, "Observée en recharge"), read.station)
    }

    @Test fun `old station heading migrated to the Cartesian frame`() {
        val cache = MapCache(context)
        cache.save("robot-A", map)
        val file = cacheFiles().single()
        val json = JSONObject(file.readText())
        json.getJSONObject("station").remove("headingFrame")
        file.writeText(json.toString())
        assertEquals(MapHeading.migrateDockEntry(152.5), cache.read("robot-A")!!.station!!.entryHeadingDegrees!!, 1e-9)
    }

    @Test fun `element of a type not interpreted is kept by the cache`() {
        // Known (23, rectangular no-go zone) or unknown (99, -1) types: the map is read back from the cache.
        val cache = MapCache(context)
        cache.save("robot-A", map)
        val file = cacheFiles().single()
        val good = file.readText()
        for ((type, kind) in listOf(23 to ShapeKind.NO_GO_RECT, 99 to ShapeKind.UNKNOWN, -1 to ShapeKind.UNKNOWN)) {
            file.writeText(JSONObject(good).apply { getJSONArray("shapes").getJSONObject(0).put("type", type) }.toString())
            assertEquals(kind, cache.read("robot-A")!!.shapes.first { it.type == type }.kind)
        }
    }

    @Test fun `corrupted or abnormal file refused without exception`() {
        val cache = MapCache(context)
        cache.save("robot-A", map)
        val file = cacheFiles().single()
        val good = file.readText()
        fun refused(text: String, why: String) { file.writeText(text); assertNull(why, cache.read("robot-A")) }
        refused("{\"shapes\":[", "JSON tronqué")
        refused("", "fichier vide")
        refused(JSONObject(good).apply { getJSONObject("station").put("heading", 400.0) }.toString(), "cap hors bornes")
        refused(JSONObject(good).apply { getJSONArray("shapes").getJSONObject(0).getJSONArray("points").put(JSONArray().put("NaN").put(1.0)) }.toString(),
            "coordonnée non finie")
        refused(JSONObject(good).apply { remove("savedAt") }.toString(), "date absente")
        refused(JSONObject(good).apply { put("shapes", JSONArray().apply { repeat(1025) { put(JSONObject(good).getJSONArray("shapes").get(0)) } }) }.toString(),
            "plus de 1024 formes")
        file.writeText(good)
        assertNotNull(cache.read("robot-A"))
    }

    @Test fun `file larger than 16 MB ignored`() {
        val cache = MapCache(context)
        cache.save("robot-A", map)
        val file: File = cacheFiles().single()
        file.appendBytes(ByteArray(16 * 1024 * 1024 + 1) { ' '.code.toByte() })
        assertNull(cache.read("robot-A"))
    }
}
