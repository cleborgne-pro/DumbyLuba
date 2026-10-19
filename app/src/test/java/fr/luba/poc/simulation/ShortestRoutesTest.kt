package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

class ShortestRoutesTest {
    private val factory=GeometryFactory()
    private fun rectangle(x: Double,y: Double,w: Double,h: Double)=
        listOf(Point(x,y),Point(x+w,y),Point(x+w,y+h),Point(x,y+h))
    private fun polygon(points: List<Point>)=factory.createPolygon(
        (points+points.first()).map { Coordinate(it.x,it.y) }.toTypedArray())
    private fun length(path: List<Point>)=path.zipWithNext().sumOf { (a,b) -> a.distance(b) }

    @Test fun shortestMatchesExhaustiveVisibilityAcrossBothSidesAndMultipleHoles() {
        val zone=rectangle(0.0,0.0,20.0,20.0)
        val scenarios=listOf(
            listOf(rectangle(4.0,6.0,8.0,4.0)),
            listOf(rectangle(4.0,6.0,8.0,4.0),rectangle(2.0,12.0,5.0,2.0)),
            listOf(rectangle(7.0,2.0,2.0,10.0),rectangle(12.0,8.0,2.0,10.0)))
        val endpoints=listOf(Point(5.0,3.0) to Point(5.0,17.0),Point(2.0,10.0) to Point(18.0,10.0))
        scenarios.forEach { holes ->
            val routes=TrajectoryPlanner(RobotMap(listOf(MapShape(1,0,zone)),true),0.3,holes)
            var free=polygon(zone) as org.locationtech.jts.geom.Geometry
            holes.forEach { free=free.difference(polygon(it)) }
            free=free.buffer(-0.3)
            endpoints.forEach endpoint@ { (start,end) ->
                if(!routes.safe(start,start) || !routes.safe(end,end)) return@endpoint
                // Independent oracle: all vertices and all visible edges, without tangent filter.
                val nodes=(listOf(start,end)+free.coordinates.map { Point(it.x,it.y) }).distinct()
                val distances=DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
                val visited=BooleanArray(nodes.size)
                distances[0]=0.0
                repeat(nodes.size) {
                    val i=nodes.indices.filter { !visited[it] }.minByOrNull { distances[it] } ?: return@repeat
                    visited[i]=true
                    nodes.indices.filter { !visited[it] }.forEach { j ->
                        if(routes.safe(nodes[i],nodes[j]))
                            distances[j]=minOf(distances[j],distances[i]+nodes[i].distance(nodes[j]))
                    }
                }
                for((a,b) in listOf(start to end,end to start)) {
                    val path=routes.between(a,b)
                    assertTrue(path.zipWithNext().all { (p,q) -> routes.safe(p,q) })
                    // The exact shortest path stays the lower bound; the produced link may
                    // deviate from it to keep 50 cm from the borders, never shorten it.
                    assertEquals("Wrong side",distances[1],routes.distanceLowerBound(a,b),1e-7)
                    assertTrue("Link shorter than the lower bound: $path",length(path)>=distances[1]-1e-7)
                }
            }
        }
    }

    @Test fun returnCanUseShorterRecordedPassageEvenWhenBothEndsAreInSameZone() {
        val zone=listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(7.0,10.0),
            Point(7.0,3.0),Point(3.0,3.0),Point(3.0,10.0),Point(0.0,10.0))
        val start=Point(2.0,8.0); val end=Point(8.0,8.0)
        val map=RobotMap(listOf(MapShape(1,0,zone),MapShape(2,2,listOf(start,end))),true)
        val routes=TrajectoryPlanner(map,0.3,emptyList())
        assertEquals(6.0,length(routes.between(start,end)),1e-8)
        assertEquals(6.0,length(routes.between(end,start)),1e-8)
    }

    @Test fun repeatedTransitUsesThirtyCentimeterLanesAndKeepsEndpoints() {
        val zone=rectangle(0.0,0.0,20.0,20.0)
        val routes=TrajectoryPlanner(RobotMap(listOf(MapShape(1,0,zone)),true),0.3,emptyList())
        val start=Point(2.0,10.0); val end=Point(18.0,10.0)
        val paths=(0..4).map { routes.variedBetween(start,end,it) }
        assertEquals(5,paths.toSet().size)
        assertEquals(listOf(10.3,9.7,10.6,9.4),paths.drop(1).map { it[1].y })
        paths.forEach { path ->
            assertEquals(start,path.first()); assertEquals(end,path.last())
            assertTrue(path.zipWithNext().all { (a,b) -> routes.safe(a,b) })
            assertTrue(length(path)<17.0)
        }
        assertEquals(paths[1].reversed(),routes.variedBetween(end,start,1))
    }

    @Test fun variedRoutesKeepExclusionsAndRecordedPassageAxis() {
        val map=Geometry.demo(); val holes=map.shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val routes=TrajectoryPlanner(map,0.6,holes)
        val shortest=routes.between(Point(2.0,5.0),Point(13.0,5.0))
        for(i in 1..6) {
            val path=routes.variedBetween(Point(2.0,5.0),Point(13.0,5.0),i)
            assertTrue(path.zipWithNext().all { (a,b) -> routes.safe(a,b) })
            assertTrue(length(path)<=length(shortest)+1.0)
        }
        val left=MapShape(1,0,rectangle(0.0,0.0,6.0,6.0))
        val right=MapShape(2,0,rectangle(12.0,0.0,6.0,6.0))
        val linked=TrajectoryPlanner(RobotMap(listOf(left,right,MapShape(3,2,listOf(Point(4.0,3.0),Point(14.0,3.0)))),true),0.3,emptyList())
        for(i in 1..6) {
            val path=linked.variedBetween(Point(2.0,1.0),Point(16.0,5.0),i)
            assertTrue(path.zipWithNext().all { (a,b) -> linked.safe(a,b) })
            assertTrue(path.filter { it.x>6.0 && it.x<12.0 }.all { it.y==3.0 })
            assertTrue(path.zipWithNext().any { (a,b) -> a.y==3.0 && b.y==3.0 && a.x<=6.0 && b.x>=12.0 })
        }
    }
}
