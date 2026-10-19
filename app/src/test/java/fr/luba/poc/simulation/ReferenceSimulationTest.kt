package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReferenceSimulationTest {
    @Test(timeout=60000) fun recordedPoolRouteStartsAndCompletesWithoutLiveGeometry() {
        val json=JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json"))
            .bufferedReader().use { it.readText() })
        val array=json.getJSONArray("shapes")
        val shapes=(0 until array.length()).map { index ->
            val shape=array.getJSONObject(index)
            val points=shape.getJSONArray("points")
            MapShape(shape.getString("id").toLong(),shape.getInt("type"),(0 until points.length()).map {
                val point=points.getJSONArray(it); Point(point.getDouble(0),point.getDouble(1))
            },shape.getString("name"))
        }
        val map=RobotMap(shapes,true)
        val station=json.getJSONObject("station")
        val base=Point(station.getDouble("x"),station.getDouble("y"))
        val heading=station.getDouble("heading")
        val zone=shapes.single { it.name=="Piscine" }
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val preview=MowingOrder.apply(Geometry.plan(zone.points,holes,spacing=0.25,margin=0.6,
            borders=true,recordedBoundary=true,basePoint=base),true,true,base)
        val routes=TrajectoryPlanner.withDock(map,0.3065,holes,emptyList(),base,heading,1.5,recordedBoundary=true)
        val prepared=MowingSimulation(routes,base,preview,map,SimConfig(baseEntryHeadingDegrees=heading,instantCharging=true))
        for(progress in listOf(10.0,25.0,50.0,75.0)) {
            val scenario=prepared.withConfig(prepared.config.copy(initialProgress=progress))
            val state=scenario.start()
            val target=state.transit.last()
            val shortest=routes.between(prepared.baseApproach,target,(heading+180)%360,null)
            fun length(p:List<Point>)=p.zipWithNext().sumOf { (a,b)->a.distance(b) }
            val minimum=length(shortest)+base.distance(prepared.baseApproach)
            assertTrue("Piscine departure at $progress %: ${length(state.transit)} m, link $minimum m",
                length(state.transit)<=minimum+0.25)
            val edge=scenario.edges[state.edge]
            val cutHeading=Math.toDegrees(kotlin.math.atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
            routes.requirePath(state.transit,(heading+180)%360,cutHeading)
            val returning=scenario.advance(state.copy(position=target,phase=SimPhase.MOWING,heading=cutHeading,battery=20.0),1.0)
            assertEquals(SimPhase.RETURNING,returning.phase)
            val returnMinimum=length(routes.between(target,prepared.baseApproach,cutHeading,(heading+180)%360))
            assertTrue("Piscine return at $progress %: ${length(returning.transit)} m, link $returnMinimum m",
                length(returning.transit)<=returnMinimum+0.25)
            routes.requirePath(returning.transit,cutHeading,(heading+180)%360)
        }
        val queries=routes.geometryQueries
        val began=System.nanoTime()
        val engine=prepared.withConfig(prepared.config.copy(initialProgress=25.0))
        var state=engine.start()
        assertTrue("Starting must only initialise the playback",System.nanoTime()-began<1_000_000_000L)
        repeat(1200) { state=engine.advance(state,30.0) }
        assertEquals(state.log.toString(),SimPhase.COMPLETE,state.phase)
        assertEquals(engine.total,state.covered,1e-6)
        assertEquals(base,state.position)
        assertEquals(queries,routes.geometryQueries)
    }
    @Test(timeout=120000) fun recentLoggedPoolBaseReturnsStayShort() {
        val a=JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().readText()).getJSONArray("shapes")
        val shapes=(0 until a.length()).map { i -> val s=a.getJSONObject(i);val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { k ->
                val v=p.getJSONArray(k);Point(v.getDouble(0),v.getDouble(1)) },s.getString("name")) }
        val map=RobotMap(shapes,true);val base=Point(-0.4563,1.0859);val heading=153.0312
        val holes=shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
        val preview=MultiZonePlanner.plan(shapes.filter { it.name=="Piscine" },holes,0.25,0.6,
            CoverageMode.LINES,true,recordedBoundary=true,basePoint=base,bordersFirst=true,nearbyNoGo=true)
        val routes=TrajectoryPlanner.withDock(map,0.3065,holes,emptyList(),base,heading,1.5,recordedBoundary=true)
        val engine=MowingSimulation(routes,base,preview,map,SimConfig(baseEntryHeadingDegrees=heading,instantCharging=true))
        fun length(p:List<Point>)=p.zipWithNext().sumOf { (a,b)->a.distance(b) }
        val results=(1..99).mapNotNull { progress ->
            val s=engine.withConfig(engine.config.copy(initialProgress=progress.toDouble())).start()
            val point=s.transit.last();val edge=engine.edges[s.edge]
            val cap=Math.toDegrees(kotlin.math.atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
            val returning=engine.advance(s.copy(position=point,phase=SimPhase.MOWING,heading=cap,battery=20.0),1.0)
            assertEquals("Return at $progress %",SimPhase.RETURNING,returning.phase)
            routes.requirePath(returning.transit,cap,(heading+180)%360)
            val direct=try { routes.between(point,engine.baseApproach,cap,(heading+180)%360) } catch(_:IllegalArgumentException) { return@mapNotNull null }
            Triple(progress,length(returning.transit)-length(direct),point)
        }
        assertTrue(results.isNotEmpty())
        results.forEach { (progress,detour,point) ->
            assertTrue("Return at $progress % from $point: detour $detour m",detour<=3.0)
            if(progress in listOf(1,5,7,40)) assertTrue("Regression of the long detour at $progress %: $detour m",detour<=1.0)
        }
    }

}
