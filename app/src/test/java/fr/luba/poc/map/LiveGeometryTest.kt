package fr.luba.poc.map

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Geometry cache of the live map: a zoom rebuilds neither the paths nor the areas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LiveGeometryTest {
    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 8.0), Point(0.0, 8.0)), "Zone")
    private val map = RobotMap(listOf(zone))
    private val frame = zone.points
    private val size = Size(400f, 300f)
    private val pass = listOf(Point(1.0, 1.0), Point(9.0, 1.0), Point(9.0, 2.0))

    @Test fun pathsAndSurfacesSurviveFramesWithTheSameData() {
        val geometry = LiveGeometry()
        geometry.begin(frame, size)
        val first = geometry.path(pass, false)
        val surface = geometry.surface(map, emptySet())
        geometry.end()
        // Next frame (zoom): same data, no path rebuilt.
        geometry.begin(frame, size)
        assertSame(first, geometry.path(pass, false))
        assertSame(surface, geometry.surface(map, emptySet()))
        geometry.end()
    }

    @Test fun samePlacementKeepsPathsWhenFramePointsChange() {
        val geometry = LiveGeometry()
        geometry.begin(frame, size)
        val first = geometry.path(pass, false)
        geometry.end()
        // Trace tip or robot inside the extent: same framing, paths kept.
        geometry.begin(frame + Point(5.0, 4.0), size)
        assertSame(first, geometry.path(pass, false))
        geometry.end()
    }

    @Test fun resizeOrUnusedPathRebuilds() {
        val geometry = LiveGeometry()
        geometry.begin(frame, size)
        val first = geometry.path(pass, false)
        geometry.end()
        geometry.begin(frame, Size(800f, 600f))
        val resized = geometry.path(pass, false)
        assertNotSame(first, resized)
        geometry.end()
        // Path missing from a frame (progressive reveal): forgotten, rebuilt if it comes back.
        geometry.begin(frame, Size(800f, 600f))
        geometry.end()
        geometry.begin(frame, Size(800f, 600f))
        assertNotSame(resized, geometry.path(pass, false))
        geometry.end()
    }

    @Test fun listGrownInPlaceIsRebuilt() {
        val geometry = LiveGeometry()
        val trail = mutableListOf(Point(1.0, 1.0), Point(2.0, 1.0))
        geometry.begin(frame, size)
        val first = geometry.path(trail, false)
        geometry.end()
        trail += Point(3.0, 2.0)
        geometry.begin(frame, size)
        assertNotSame(first, geometry.path(trail, false))
        geometry.end()
    }
}
