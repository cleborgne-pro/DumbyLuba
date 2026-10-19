package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

class MapCameraTest {
    private val center = Point(540.0, 1200.0)

    @Test fun northTurnTakesShortestPath() {
        assertEquals(-30.0, MapCamera(rotation = 30.0).northTurn(), 1e-9)
        assertEquals(30.0, MapCamera(rotation = -30.0).northTurn(), 1e-9)
        assertEquals(10.0, MapCamera(rotation = 350.0).northTurn(), 1e-9)
        assertEquals(-10.0, MapCamera(rotation = -350.0).northTurn(), 1e-9)
        assertEquals(0.0, MapCamera().northTurn(), 1e-9)
    }

    @Test fun backToNorthKeepsScreenCenterOnSamePoint() {
        val camera = MapCamera().transform(Point(300.0, 800.0), center, Point(40.0, -25.0), 2.5, 73.0)
        val before = camera.inverse(center, center)
        val north = camera.rotatedBy(camera.northTurn())
        assertEquals(0.0, north.rotation, 1e-9)
        assertEquals(camera.zoom, north.zoom, 1e-12)
        val after = north.inverse(center, center)
        assertEquals(before.x, after.x, 1e-6)
        assertEquals(before.y, after.y, 1e-6)
    }

    @Test fun stepwiseRotationMatchesSingleRotation() {
        val camera = MapCamera().transform(Point(200.0, 500.0), center, Point(10.0, 60.0), 1.7, -128.0)
        var stepped = camera
        repeat(10) { stepped = stepped.rotatedBy(camera.northTurn() / 10) }
        val direct = camera.rotatedBy(camera.northTurn())
        assertEquals(0.0, MapCamera.signedDegrees(stepped.rotation), 1e-9)
        assertEquals(direct.offset.x, stepped.offset.x, 1e-6)
        assertEquals(direct.offset.y, stepped.offset.y, 1e-6)
    }

    @Test fun followingCentersTheRobotAndKeepsZoomAndRotation() {
        val camera = MapCamera().transform(Point(200.0, 500.0), center, Point(10.0, 60.0), 2.3, 37.0)
        val robot = Point(812.0, 433.0)
        val target = Point(540.0, 1050.0)
        val followed = camera.following(robot, center, target)
        assertEquals(camera.zoom, followed.zoom, 1e-12)
        assertEquals(camera.rotation, followed.rotation, 1e-12)
        val shown = followed.inverse(target, center)
        assertEquals(robot.x, shown.x, 1e-6)
        assertEquals(robot.y, shown.y, 1e-6)
    }

    @Test fun followModeOverridesOnlyTheOffset() {
        val camera = MapCamera(zoom = 3.0, rotation = 20.0, offset = Point(5.0, 6.0))
        val follow = RobotFollow()
        follow.offset = Point(100.0, -40.0)
        assertEquals(camera, follow.view(camera))
        follow.start()
        assertEquals(MapCamera(3.0, 20.0, Point(100.0, -40.0)), follow.view(camera))
        follow.stop()
        assertNull(follow.offset)
        assertEquals(camera, follow.view(camera))
    }
}
