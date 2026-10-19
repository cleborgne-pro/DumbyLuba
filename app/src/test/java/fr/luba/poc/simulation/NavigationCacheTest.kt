package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class NavigationCacheTest {
    private fun rectangle(x:Double,y:Double,w:Double,h:Double)=
        listOf(Point(x,y),Point(x+w,y),Point(x+w,y+h),Point(x,y+h))
    private val map=RobotMap(listOf(MapShape(1,0,rectangle(-4.0,-4.0,8.0,8.0))),true)

    @Test fun repeatedChecksKeepObstaclesMarginsAndInstanceIsolation() {
        val free=TrajectoryPlanner(map,0.3,emptyList())
        val blocked=TrajectoryPlanner(map,0.3,listOf(rectangle(-0.5,-0.5,1.0,1.0)))
        val narrow=TrajectoryPlanner(map,1.0,emptyList())
        val a=Point(-2.0,0.0);val b=Point(2.0,0.0);val edge=Point(3.5,0.0)
        repeat(5) {
            assertTrue(free.safe(a,b));assertTrue(free.safe(b,a))
            free.requirePath(listOf(a,b))
            assertFalse(blocked.safe(a,b));assertFalse(blocked.safe(b,a))
            assertTrue(free.supportsPivot(Point(0.0,0.0)))
            assertFalse(blocked.supportsPivot(Point(0.0,0.0)))
            assertTrue(free.safe(edge,edge));assertFalse(narrow.safe(edge,edge))
            assertThrows(IllegalArgumentException::class.java) { blocked.requirePath(listOf(a,b)) }
        }
        assertEquals(30L,free.geometryQueries)
    }
    @Test fun cachedChecksCannotAddAConnectionToDisconnectedZones() {
        val left=MapShape(1,0,rectangle(0.0,0.0,4.0,4.0))
        val right=MapShape(2,0,rectangle(8.0,0.0,4.0,4.0))
        val a=Point(2.0,2.0);val b=Point(10.0,2.0)
        val separate=TrajectoryPlanner(RobotMap(listOf(left,right),true),0.3,emptyList())
        val connected=TrajectoryPlanner(RobotMap(listOf(left,right,MapShape(3,2,listOf(a,b))),true),0.3,emptyList())
        repeat(3) {
            assertFalse(separate.safe(a,b))
            assertThrows(IllegalArgumentException::class.java) { separate.between(a,b) }
            val path=connected.between(a,b)
            assertEquals(a,path.first());assertEquals(b,path.last())
            connected.requirePath(path)
        }
    }
}
