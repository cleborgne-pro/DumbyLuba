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

/** Drawings in the local map cache: round trip, older caches, damaged entries left out one by one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapCacheDrawingQaTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val zone = MapShape(11, 0, listOf(Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 6.0), Point(0.0, 6.0)), "Pelouse")
    private val map = RobotMap(listOf(zone), true, "Bluetooth", MapStation(Point(1.0, 1.0), 152.5, "Transmise par le robot"))
    private val svg = "<svg viewBox=\"0 0 100 100\"><path d=\"M10 10H90V90H10Z\"/><circle cx=\"50\" cy=\"50\" r=\"10\"/></svg>"
    private val drawing = MapDrawing(-4_611_686_018_427_387_903, Long.MAX_VALUE, 5.25, -3.125, 4.0, 2.5, 0.7, false, "fleur \"été\".svg", svg, 640, 400)

    private fun file() = context.filesDir.listFiles().orEmpty().single { it.name.startsWith("map-") }

    @Test fun `round trip keeps every field, long hashes and the outline`() {
        val cache = MapCache(context)
        val saved = map.copy(drawings = listOf(drawing, drawing.copy(hash = 1, hidden = true, svg = "", basePixelWidth = 0)))
        cache.save("robot-Q", saved)
        val read = MapCache(context).read("robot-Q")!!
        assertEquals(saved.drawings, read.drawings)
        assertEquals(saved.drawings[0].outline, read.drawings[0].outline)
        assertEquals(saved.shapes, read.shapes)
        assertEquals(saved.station, read.station)
        assertTrue(read.complete)
    }

    @Test fun `older cache without drawings, and a drawings field of the wrong type, read the map`() {
        val cache = MapCache(context)
        cache.save("robot-Q", map.copy(drawings = listOf(drawing)))
        val f = file()
        for (value in listOf<Any?>(null, "x", JSONObject(), 12, JSONArray())) {
            val json = JSONObject(f.readText()).apply { if (value == null) remove("drawings") else put("drawings", value) }
            f.writeText(json.toString())
            val read = cache.read("robot-Q")
            assertNotNull("drawings = $value", read)
            assertEquals(map.shapes, read!!.shapes)
            assertTrue(read.drawings.isEmpty())
        }
    }

    @Test fun `each damaged drawing is left out alone`() {
        val cache = MapCache(context)
        val good = (1L..9L).map { drawing.copy(hash = it) }
        cache.save("robot-Q", map.copy(drawings = good))
        val f = file()
        val json = JSONObject(f.readText())
        val array = json.getJSONArray("drawings")
        array.getJSONObject(0).remove("hash")
        array.getJSONObject(1).put("zone", "not a number")
        array.getJSONObject(2).put("width", 0)
        array.getJSONObject(3).put("height", MapDrawing.MAX_SIZE_M + 1)
        array.getJSONObject(4).put("rotation", "Infinity")
        array.getJSONObject(5).remove("y")
        array.put(6, "not an object")
        f.writeText(json.toString())
        val read = cache.read("robot-Q")!!
        assertEquals(listOf(8L, 9L), read.drawings.map { it.hash })
        assertEquals(map.shapes, read.shapes)
    }

    @Test fun `optional fields missing still give a drawing`() {
        val cache = MapCache(context)
        cache.save("robot-Q", map.copy(drawings = listOf(drawing)))
        val f = file()
        val json = JSONObject(f.readText())
        val o = json.getJSONArray("drawings").getJSONObject(0)
        listOf("hidden", "name", "svg", "widthPix", "heightPix").forEach { o.remove(it) }
        f.writeText(json.toString())
        val d = cache.read("robot-Q")!!.drawings.single()
        assertEquals(drawing.copy(hidden = false, fileName = "", svg = "", basePixelWidth = 0, basePixelHeight = 0), d)
    }

    @Test fun `cache holding more than 64 drawings keeps the first 64`() {
        val cache = MapCache(context)
        cache.save("robot-Q", map.copy(drawings = (1L..70L).map { drawing.copy(hash = it, svg = "") }))
        assertEquals((1L..64L).toList(), cache.read("robot-Q")!!.drawings.map { it.hash })
    }
}
