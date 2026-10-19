package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class ReferenceTransitTest {
    private fun shapes():List<MapShape> {
        val a=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().readText()).getJSONArray("shapes")
        return (0 until a.length()).map { i -> val s=a.getJSONObject(i); val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { k ->
                val v=p.getJSONArray(k);Point(v.getDouble(0),v.getDouble(1)) },s.getString("name")) }
    }
    @Test fun playAreaLoggedRunsRemainConnectedThroughFullMap() {
        val shapes=shapes();val map=RobotMap(shapes,true)
        val planner=TrajectoryPlanner(map,0.3065,shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points },recordedBoundary=true)
        // Aire de jeux passes computed with the current rule (outline = chassis side); the passes recorded
        // before 0.40.0 (`reference-playarea-runs.json`) went too close to the border to remain a valid route.
        val zone=shapes.single { it.kind==ShapeKind.ZONE && it.name.trim()=="Aire de jeux" }
        val runs=Geometry.plan(zone.points,shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points },recordedBoundary=true).runs
        assertTrue(runs.size>=2)
        runs.zipWithNext().forEachIndexed { i,(before,after) ->
            try {
                fun heading(a:Point,b:Point)=Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))
                val incoming=heading(before[before.size-2],before.last())
                val outgoing=heading(after.first(),after[1])
                val path=planner.between(before.last(),after.first(),incoming,outgoing)
                planner.requirePath(path,incoming,outgoing)
            }
            catch(e:IllegalArgumentException) { throw AssertionError("Raccord $i : ${before.last()} → ${after.first()} : ${e.message}",e) }
        }
    }
    @Test fun unselectedMiddleZoneRemainsAvailableForBladeOffTransit() {
        fun zone(id:Long,x:Double)=MapShape(id,0,listOf(Point(x,0.0),Point(x+6,0.0),Point(x+6,6.0),Point(x,6.0)))
        val shapes=listOf(zone(1,0.0),zone(2,12.0),zone(3,24.0),
            MapShape(4,2,listOf(Point(6.0,3.0),Point(12.0,3.0))),
            MapShape(5,2,listOf(Point(18.0,3.0),Point(24.0,3.0))))
        val map=RobotMap(shapes,true)
        val preview=Preview(listOf(listOf(Point(2.0,2.0),Point(3.0,2.0)),
            listOf(Point(26.0,2.0),Point(27.0,2.0))),2.0,0,0,zoneIds=listOf(1,3))
        val engine=MowingSimulation(TrajectoryPlanner(map,0.3065,emptyList()),Point(2.0,3.0),preview,map,
            SimConfig(baseEntryHeadingDegrees=180.0))
        assertTrue(engine.edges.any { !it.cutting && it.a.x in 12.0..18.0 && it.b.x in 12.0..18.0 })
        assertFalse(engine.edges.any { it.cutting && it.a.x in 12.0..18.0 })
    }
    @Test fun selectedPlayAreaFullPreparationUsesAllMappedZones() {
        val shapes=shapes(); val map=RobotMap(shapes,true)
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val base=Point(-0.5351,1.0712)
        val preview=MultiZonePlanner.plan(shapes.filter { it.name=="Aire de jeux" },holes,0.25,0.6,
            CoverageMode.LINES,true,recordedBoundary=true,basePoint=base,obstacles=baseZone(base),bordersFirst=true,nearbyNoGo=true)
        val routes=TrajectoryPlanner.withDock(map,0.3065,holes,emptyList(),base,153.0312,1.5,recordedBoundary=true)
        val engine=MowingSimulation(routes,base,preview,map,SimConfig(baseEntryHeadingDegrees=153.0312))
        assertTrue(engine.edges.isNotEmpty())
        assertEquals(1,engine.preview.zoneIds.size)
    }

    @Test fun connectedReferenceZonesCanBePreparedInSelectionOrder() {
        val shapes=shapes();val map=RobotMap(shapes,true)
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val base=Point(-0.5351,1.0712)
        val preview=MultiZonePlanner.plan(shapes.filter { it.kind==ShapeKind.ZONE && it.name!="Potager" },holes,0.25,0.6,
            CoverageMode.LINES,true,recordedBoundary=true,basePoint=base,obstacles=baseZone(base),bordersFirst=true,nearbyNoGo=true)
        val routes=TrajectoryPlanner.withDock(map,0.3065,holes,emptyList(),base,153.0312,1.5,recordedBoundary=true)
        val engine=MowingSimulation(routes,base,preview,map,SimConfig(baseEntryHeadingDegrees=153.0312))
        assertEquals(6,engine.preview.zoneIds.size)
    }

    @Test fun playAreaToFosseUsesUnselectedZonesForTransit() {
        verifySelectionLinks(listOf("Aire de jeux","Fosse","Piscine"))
    }

    @Test fun potagerToFosseStartsAtTheNearbyCoverageEntry() {
        val shapes=shapes()
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val base=Point(-0.4563,1.0859)
        val zones=listOf("Potager","Fosse").map { name -> shapes.single { it.name.trim()==name } }
        val first=MowingOrder.apply(Geometry.plan(zones[0].points,holes,0.25,0.6,
            CoverageMode.LINES,true,recordedBoundary=true,basePoint=base),false,true,base)
        val exit=first.cuttingPaths.last().last()
        val next=MowingOrder.apply(Geometry.plan(zones[1].points,holes,0.25,0.6,
            CoverageMode.LINES,true,recordedBoundary=true,basePoint=exit),false,true,exit)
        val preview=MultiZonePlanner.plan(zones,holes,0.25,0.6,CoverageMode.LINES,true,
            recordedBoundary=true,basePoint=base,obstacles=baseZone(base),bordersFirst=false,nearbyNoGo=true)
        assertEquals(next.cuttingPaths.first().first(),preview.cuttingPaths[first.cuttingPaths.size].first())
        verifySelectionLinks(listOf("Potager","Fosse"))
    }

    @Test fun fosseToPotagerUsesUnselectedZonesForTransit() {
        verifySelectionLinks(listOf("Fosse","Potager"))
    }

    @Test fun mergedTourOfAllZonesLinksFosseToPotager() {
        verifySelectionLinks(listOf("Piscine","Potager","Jardin d’Eden","Aire de jeux","Figuier","Enfer","Fosse"),CoverageMode.TOUR)
    }

    /** Base safety zone, added by PlanningTerrain in production (axis 153°, approach 1.5 m). */
    private fun baseZone(base: Point) = listOf(fr.luba.poc.obstacles.ObstacleDisc(base.x,base.y,TrajectoryPlanner.baseSafetyRadius(1.5)))

    private fun verifySelectionLinks(names: List<String>, mode: CoverageMode = CoverageMode.LINES) {
        val shapes=shapes(); val map=RobotMap(shapes,true)
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val base=Point(-0.4563,1.0859)
        val zones=names.map { name -> shapes.single { it.name.trim()==name } }
        val preview=MultiZonePlanner.plan(zones,holes,0.25,0.6,mode,true,
            recordedBoundary=true,basePoint=base,obstacles=baseZone(base),bordersFirst=false,nearbyNoGo=false)
        val routes=TrajectoryPlanner.withDock(map,0.3065,holes,emptyList(),base,153.0312,1.5,recordedBoundary=true)
        preview.cuttingPaths.zipWithNext().forEachIndexed { index,(before,after) ->
            fun heading(a:Point,b:Point)=Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))
            val incoming=heading(before[before.size-2],before.last())
            val outgoing=heading(after.first(),after[1])
            try {
                val path=routes.between(before.last(),after.first(),incoming,outgoing)
                routes.requirePath(path,incoming,outgoing)
            } catch(e:IllegalArgumentException) {
                throw AssertionError("Raccord $index : ${before.last()} → ${after.first()} (caps $incoming → $outgoing) : ${e.message}",e)
            }
        }
    }

    @Test fun allReferenceZonesCanBePreparedThroughMappedSurfaces() {
        val shapes=shapes();val map=RobotMap(shapes,true)
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val base=Point(-0.5351,1.0712)
        val preview=MultiZonePlanner.plan(shapes.filter { it.kind==ShapeKind.ZONE },holes,0.25,0.6,
            CoverageMode.LINES,true,recordedBoundary=true,basePoint=base,obstacles=baseZone(base),bordersFirst=true,nearbyNoGo=true)
        val routes=TrajectoryPlanner.withDock(map,0.3065,holes,emptyList(),base,153.0312,1.5,recordedBoundary=true)
        val engine=MowingSimulation(routes,base,preview,map,SimConfig(baseEntryHeadingDegrees=153.0312))
        assertEquals(7,engine.preview.zoneIds.size)
        assertTrue(engine.edges.any { !it.cutting })
    }

    @Test fun singlePointPassageDoesNotConnectSeparatedZones() {
        fun zone(id:Long,x:Double)=MapShape(id,0,listOf(Point(x,0.0),Point(x+6,0.0),Point(x+6,6.0),Point(x,6.0)),"Zone $id")
        val map=RobotMap(listOf(zone(1,0.0),zone(2,12.0),MapShape(3,2,listOf(Point(9.0,3.0)))),true)
        val routes=TrajectoryPlanner(map,0.3065,emptyList(),recordedBoundary=true)
        val failure=assertThrows(IllegalArgumentException::class.java) { routes.between(Point(3.0,3.0),Point(15.0,3.0)) }
        assertTrue(failure.message.orEmpty().contains("sans axe exploitable"))
    }

}
