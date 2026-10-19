package fr.luba.poc.simulation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.map.MapHeading
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Manual base and accesses remembered per map geometry ("map-bases" preferences). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val square = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0)), "Jardin")
    private val map = RobotMap(listOf(square), true)
    private val prefs get() = context.getSharedPreferences("map-bases", Context.MODE_PRIVATE)

    /** Raw key of a map (SHA-256 of its geometry), to write old formats. */
    private fun rawKey(): String {
        val before = prefs.all.keys.toSet()
        BaseStore(context, map).save(MapBase(Point(1.0, 1.0), "x"))
        return (prefs.all.keys - before).single()
    }

    @Test fun `base missing then read back identically after a restart`() {
        assertNull(BaseStore(context, map).read())
        val base = MapBase(Point(2.5, -1.25), "Placée manuellement", 1.2, 135.0, 1.0)
        BaseStore(context, map).save(base)
        assertEquals(base, BaseStore(context, map).read())
    }

    @Test fun `each geometry has its own base`() {
        BaseStore(context, map).save(MapBase(Point(1.0, 1.0), "Placée manuellement"))
        val other = RobotMap(listOf(square.copy(points = square.points.map { Point(it.x + 0.5, it.y) })), true)
        assertNull(BaseStore(context, other).read())
        // Same geometry, other source (cache, cloud): same base.
        assertNotNull(BaseStore(context, map.copy(source = "Cache local")).read())
    }

    @Test fun `corrupted or out of bounds value ignored without exception`() {
        val key = rawKey()
        for (raw in listOf("", "abc", "1|2", "x|y|Placée manuellement", "1|2|Placée manuellement|1.5||9.0|2", "1|2|Placée manuellement|-1|||2")) {
            prefs.edit().putString(key, raw).commit()
            assertNull("\"$raw\"", BaseStore(context, map).read())
        }
    }

    @Test fun `old formats read back with the heading migrated or discarded`() {
        val key = rawKey()
        // Before the Cartesian frame: docking heading of a base observed while charging, migrated.
        prefs.edit().putString(key, "1.0|2.0|Observée en recharge (12/10)|1.5|30.0").commit()
        val observed = BaseStore(context, map).read()!!
        assertEquals(MapHeading.migrateDockEntry(30.0), observed.entryHeadingDegrees!!, 1e-9)
        assertEquals(1.5, observed.approachDistanceM, 1e-9)
        // Base placed by hand: the heading has always been Cartesian.
        prefs.edit().putString(key, "1.0|2.0|Placée manuellement|1.5|30.0").commit()
        assertEquals(30.0, BaseStore(context, map).read()!!.entryHeadingDegrees!!, 1e-9)
        // Other origin without version: heading of an unknown old frame, discarded.
        prefs.edit().putString(key, "1.0|2.0|Transmise par le robot|1.5|30.0").commit()
        assertNull(BaseStore(context, map).read()!!.entryHeadingDegrees)
        // Influence radius missing: default value.
        prefs.edit().putString(key, "1.0|2.0|Placée manuellement").commit()
        assertEquals(MapBase.DEFAULT_INFLUENCE_RADIUS_M, BaseStore(context, map).read()!!.influenceRadiusM!!, 1e-9)
    }

    @Test fun `station sent by the robot takes precedence, saved base kept only if observed`() {
        val store = BaseStore(context, map)
        val saved = MapBase(Point(3.0, 3.0), "Observée en recharge", 1.2, 45.0, 1.1)
        // Robot station: position and heading of the station, set radius and approach kept.
        val withStation = map.copy(station = MapStation(Point(5.0, 5.0), 90.0, "Transmise par le robot"))
        assertEquals(MapBase(Point(5.0, 5.0), "Transmise par le robot", 1.2, 90.0, 1.1), store.resolve(withStation, saved))
        assertEquals(1.5, store.resolve(withStation, null)!!.approachDistanceM, 1e-9)
        // Without station: observed or sent base kept; manual or demo base discarded.
        assertEquals(saved, store.resolve(map, saved))
        assertNotNull(store.resolve(map, saved.copy(origin = "Position et axe observés en recharge")))
        assertNull(store.resolve(map, saved.copy(origin = "Placée manuellement")))
        assertNull(store.resolve(map, saved.copy(origin = "Démonstration")))
        assertNull(store.resolve(map, null))
    }

    @Test fun `manual accesses read back, missing or corrupted give an empty list`() {
        val store = BaseStore(context, map)
        assertEquals(emptyList<List<Point>>(), store.accesses())
        val paths = listOf(listOf(Point(-3.0, 1.0), Point(2.0, 1.0)), listOf(Point(0.5, 0.5), Point(1.5, 2.5), Point(4.0, 4.0)))
        store.saveAccesses(paths)
        assertEquals(paths, BaseStore(context, map).accesses())
        prefs.edit().putString("${rawKey()}-accesses", "[[[1,").commit()
        assertEquals(emptyList<List<Point>>(), BaseStore(context, map).accesses())
    }
}
