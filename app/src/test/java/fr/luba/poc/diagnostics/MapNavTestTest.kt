package fr.luba.poc.diagnostics

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.*
import org.junit.Assert.*
import org.junit.Test

class MapNavTestTest {
    private val zone = listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0))
    private val hole = listOf(Point(5.0,4.0),Point(6.0,4.0),Point(6.0,5.0),Point(5.0,5.0))
    private val map = RobotMap(listOf(MapShape(1,0,zone),MapShape(2,1,hole)),true)

    @Test fun `real course starts at the robot and respects no-go zones and radius`() {
        val start = Point(3.0,3.0)
        val plan = MapNavPlanner.plan(map,start,5.0,emptyList())
        assertEquals(start,plan.points.first())
        assertEquals(3,plan.bands.size)
        assertTrue(plan.borders.isNotEmpty())
        assertTrue(plan.labels.contains("No-go / obstacle"))
        assertEquals(plan.points.size,plan.labels.size)
        assertTrue(plan.points.all { it.distance(start) <= 5 })
        assertTrue(plan.points.zipWithNext().all { (a,b) -> Geometry.safe(a,b,zone,listOf(hole),MapNavPlanner.MARGIN_M) })
    }

    @Test fun `outlines outside the radius produce a course without border`() {
        val large = listOf(Point(0.0,0.0),Point(100.0,0.0),Point(100.0,100.0),Point(0.0,100.0))
        val plan = MapNavPlanner.plan(RobotMap(listOf(MapShape(1,0,large)),true),Point(50.0,50.0),3.0,emptyList())
        assertEquals(3,plan.bands.size)
        assertTrue(plan.borders.isEmpty())
        assertTrue(plan.points.size > 3)
    }
    @Test fun `refuses incomplete map start outside zone and insufficient margin`() {
        listOf(map.copy(complete=false) to Point(3.0,3.0),map to Point(-1.0,2.0),map to Point(0.2,2.0),map to Point(5.5,4.5)).forEach { (m,p) ->
            assertThrows(Exception::class.java) { MapNavPlanner.plan(m,p,5.0,emptyList()) }
        }
    }

    @Test fun `reserves local obstacles in the course`() {
        val obstacle = LocalObstacle("pole",ObstacleKind.POLE,3.0,4.8,0.1,0.2)
        val plan = MapNavPlanner.plan(map,Point(3.0,3.0),4.0,listOf(obstacle))
        val center = Point(obstacle.x,obstacle.y)
        assertTrue(plan.points.zipWithNext().all { (a,b) -> Geometry.distance(center,a,b) >= obstacle.keepOutDisc(RobotEnvelope.LUBA2).radiusM })
    }

    @Test fun `every planned point can produce a command without out-of-range command`() {
        val plan = MapNavPlanner.plan(map,Point(3.0,3.0),5.0,emptyList())
        plan.points.zipWithNext().forEach { (a,b) ->
            val angle = Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))
            val (linear,angular) = MapNavFollower.command(a,angle,b,30,45)
            assertTrue(linear in 0..30)
            assertEquals(0,angular)
            assertEquals(0 to 0,MapNavFollower.command(b,angle,b,30,45))
        }
    }
    @Test fun `controller turns before advancing slows down and handles 180 degree crossing`() {
        val p=Point(0.0,0.0)
        assertEquals(0,MapNavFollower.command(p,0.0,Point(0.0,1.0),50,100).first)
        assertTrue(MapNavFollower.command(p,0.0,Point(0.0,1.0),50,100).second > 0)
        assertTrue(MapNavFollower.command(p,0.0,Point(0.0,-1.0),50,100).second < 0)
        assertEquals(50 to 0,MapNavFollower.command(p,0.0,Point(1.0,0.0),50,100))
        assertTrue(MapNavFollower.command(p,0.0,Point(0.2,0.0),500,100).first < 500)
        assertEquals(0 to 0,MapNavFollower.command(p,0.0,Point(0.1,0.0),50,100))
        // 180° crossing: moves forward with a small correction, no U-turn.
        MapNavFollower.command(p,179.0,Point(-1.0,-0.01),50,100).let { (linear,angular) -> assertEquals(50,linear); assertTrue(kotlin.math.abs(angular) <= 20) }
    }

    @Test fun `RTK noise on a planned border does not stop tracking`() {
        val plan = MapNavPlanner.plan(map,Point(3.0,3.0),5.0,emptyList())
        val border = plan.borders.first()
        val routes = requireNotNull(plan.routes)
        // A few centimeters outside the planning corridor, boundary side: physically safe position.
        val noisy = border.map { p ->
            val toEdge = listOf(Point(p.x,0.0),Point(0.0,p.y),Point(p.x,12.0),Point(12.0,p.y)).minBy { it.distance(p) }
            val d = p.distance(toEdge); Point(p.x+(toEdge.x-p.x)/d*0.04,p.y+(toEdge.y-p.y)/d*0.04)
        }.filter { Geometry.inside(it,zone) && !routes.safe(it,it) }
        assertTrue("noisy positions leave the planning corridor", noisy.isNotEmpty())
        noisy.forEach { assertNull(MapNavGuard.positionFailure(plan,it)) }
        assertNotNull(MapNavGuard.positionFailure(plan,Point(0.5,3.0)))
        assertNotNull(MapNavGuard.positionFailure(plan,Point(5.5,4.5)))
    }

    @Test fun `an obstacle already bypassed by the plan does not trigger an incident`() {
        val obstacle = LocalObstacle("pole",ObstacleKind.POLE,3.0,4.8,0.1,0.2)
        val plan = MapNavPlanner.plan(map,Point(3.0,3.0),4.0,listOf(obstacle))
        val known = setOf(obstacle)
        plan.points.zipWithNext().forEach { (a,b) -> assertNull(MapNavGuard.unplannedObstacle(a,b,listOf(obstacle),known)) }
        // An obstacle added on the route after planning is still detected.
        val added = LocalObstacle("new",ObstacleKind.OTHER,plan.points[1].x,plan.points[1].y,0.2,0.3)
        assertEquals(added,MapNavGuard.unplannedObstacle(plan.points[0],plan.points[1],listOf(obstacle,added),known))
    }

    @Test fun `perpendicular approach of a border point without false stop`() {
        val plan = MapNavPlanner.plan(map,Point(3.0,3.0),5.0,emptyList())
        // Planned point 0.95 m from the south boundary, robot 25 cm before it, nose towards the boundary.
        val target = Point(3.0,0.95); val position = Point(3.0,1.2)
        assertNull(MapNavGuard.motionFailure(plan,position,-90.0,target,60,0))
        assertNull(MapNavGuard.motionFailure(plan,target,0.0,Point(4.0,0.95),0,30))
        // Real projection beyond the physical reserve: refusal kept.
        assertNotNull(MapNavGuard.motionFailure(plan,Point(3.0,0.75),-90.0,Point(3.0,0.2),60,0))
    }

    @Test fun `the map course duration can cover ten minutes`() {
        assertEquals(600_000L,NavConfig(maxDurationMs = 600_000).maxDurationMs)
        assertThrows(IllegalArgumentException::class.java) { NavConfig(maxDurationMs = 600_001) }
    }
}
