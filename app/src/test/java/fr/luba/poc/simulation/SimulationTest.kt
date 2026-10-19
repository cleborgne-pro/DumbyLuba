package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class SimulationTest {
    private val map=Geometry.demo()
    private val holes=map.shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points }
    private fun engine(config:SimConfig=SimConfig(autonomyMinutes=15.0,chargeMinutes=5.0)):MowingSimulation {
        val preview=Geometry.plan(map.shapes.first().points,holes,borders=true)
        return MowingSimulation(TrajectoryPlanner(map,0.6,holes),Point(1.0,1.0),preview,map,config)
    }
    @Test fun failedHeadingConnectionIsCachedWithoutRepeatingGeometry() {
        fun zone(id:Long,x:Double)=MapShape(id,0,listOf(Point(x,0.0),Point(x+6,0.0),Point(x+6,6.0),Point(x,6.0)))
        val routes=TrajectoryPlanner(RobotMap(listOf(zone(1,0.0),zone(2,20.0)),true),0.6,emptyList())
        fun failure(finalHeading:Double):String {
            try { routes.between(Point(2.0,2.0),Point(22.0,2.0),0.0,finalHeading);fail("Access missing") }
            catch(e:IllegalArgumentException) { return e.message.orEmpty() }
            return ""
        }
        val first=failure(0.0);val queries=routes.geometryQueries
        assertEquals(first,failure(0.0))
        assertEquals("An identical failure must not restart the pivot searches",queries,routes.geometryQueries)
        failure(180.0)
        assertTrue("The cache must distinguish the headings",routes.geometryQueries>queries)
    }
    @Test fun roundedRecordedBoundaryHasSafeEntryAndExitConnections() {
        val circle = (0 until 64).map { i ->
            val angle = i*Math.PI*2/64
            Point(6*kotlin.math.cos(angle),6*kotlin.math.sin(angle))
        }
        val map = RobotMap(listOf(MapShape(1,0,circle)),true)
        val preview = Geometry.plan(circle,emptyList(),recordedBoundary=true)
        val routes = TrajectoryPlanner(map,0.6,emptyList(),recordedBoundary=true)
        val engine = MowingSimulation(routes,Point(0.0,0.0),preview,map,SimConfig())
        routes.requirePath(listOf(engine.edges.first().a)+engine.edges.map { it.b })
    }
    @Test fun closestBorderEntryIsAlsoTheSimulationStart() {
        val zone = listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(0.0,10.0))
        val base = Point(0.4,5.0)
        val map = RobotMap(listOf(MapShape(1,0,zone)),true)
        val preview = Geometry.plan(zone,emptyList(),basePoint=base,recordedBoundary=true)
        assertTrue(preview.startWithBorders)
        val routes = TrajectoryPlanner(map,0.3065,emptyList(),recordedBoundary=true)
        val engine = MowingSimulation(routes,base,preview,map,SimConfig(baseEntryHeadingDegrees=180.0))
        assertEquals(preview.cuttingPaths.first().first(),engine.edges.first().a)
        routes.requirePath(listOf(engine.edges.first().a)+engine.edges.map { it.b })
    }
    @Test fun lowerLimitStopsMowingWithoutAddingTheReturnCostToTheThreshold() {
        val e = engine(SimConfig(autonomyMinutes=60.0,returnPercent=30.0))
        val edge = e.edges.first()
        val heading = Math.toDegrees(kotlin.math.atan2(edge.b.y-edge.a.y,edge.b.x-edge.a.x))
        val s = SimState(edge.a,30.1,SimPhase.MOWING,heading=heading)
        val above = e.advance(s,0.1)
        assertEquals(SimPhase.MOWING,above.phase)
        val atLimit = e.advance(s.copy(battery=30.0),0.1)
        assertEquals(SimPhase.RETURNING,atLimit.phase)
        assertEquals(0.0,atLimit.covered,1e-6)
    }
    @Test fun instantChargingReachesConfiguredLimitAndPreservesTheoreticalTime() {
        // Short autonomy: the rest exceeds 85 - 20 points, the recharge goes up to the resume threshold.
        val e = engine(SimConfig(initialBattery=15.0,autonomyMinutes=5.0,chargeMinutes=5.0,
            resumePercent=85.0,instantCharging=true))
        val initial = e.start()
        assertEquals(SimPhase.CHARGING, initial.phase)
        val next = e.advance(initial,1.0)
        assertEquals(85.0,next.battery,1e-6)
        assertEquals(SimPhase.LEAVING_DOCK,next.phase)
        assertEquals(210.0,next.elapsed,1e-6)
        assertEquals(initial.covered,next.covered,1e-6)
        assertEquals(e.base,next.position)
    }
    @Test fun variedReturnPreservesSafeEndpointPivots() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,5.0),Point(0.0,5.0)))
        val start=Point(2.0,2.0); val end=Point(8.0,2.0)
        val angle=Math.toRadians(8.0)
        val x=start.x-0.345*kotlin.math.cos(angle)+0.2565*kotlin.math.sin(angle)
        val y=start.y-0.345*kotlin.math.sin(angle)-0.2565*kotlin.math.cos(angle)
        val hole=listOf(Point(x-0.0001,y-0.0001),Point(x+0.0001,y-0.0001),
            Point(x+0.0001,y+0.0001),Point(x-0.0001,y+0.0001))
        val routes=TrajectoryPlanner(RobotMap(listOf(zone),true),0.3065,listOf(hole))
        val varied=routes.variedBetween(start,end,1)
        assertTrue(varied.size>2)
        assertThrows(IllegalArgumentException::class.java) { routes.requirePath(varied,0.0,0.0) }
        val safe=routes.variedBetween(start,end,1,0.0,0.0)
        assertEquals(listOf(start,end),safe)
        routes.requirePath(safe,0.0,0.0)
    }
    @Test fun noGoDetourHugsClearanceInsteadOfTriangleCenters() {
        val routes = TrajectoryPlanner(map,0.6,holes)
        for ((start,end) in listOf(Point(2.0,5.0) to Point(13.0,5.0),
                                  Point(13.0,5.0) to Point(2.0,5.0))) {
            val path = routes.between(start,end)
            assertEquals(start,path.first())
            assertEquals(end,path.last())
            assertTrue(path.zipWithNext().all { (a,b) -> routes.safe(a,b) })
            val meters = path.zipWithNext().sumOf { (a,b) -> a.distance(b) }
            // A safe lower bypass exists in less than 11.8 m.
            assertTrue("Needless detour: $meters m, $path",meters < 11.8)
            val hole = holes.single()
            path.drop(1).dropLast(1).forEach { corner ->
                val clearance = hole.indices.minOf { i -> Geometry.distance(corner,hole[i],hole[(i+1)%hole.size]) }
                assertEquals("Turns must follow the reserve outline",0.6,clearance,0.001)
            }
        }
    }
    @Test fun dockOutsideZoneConnectsThroughItsKnownReverseCorridor() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val m=RobotMap(listOf(zone),true)
        val base=Point(-0.5,3.0)
        val routes=TrajectoryPlanner.withDock(m,0.3065,emptyList(),emptyList(),base,180.0,1.5)
        val e=MowingSimulation(routes,base,Preview(listOf(listOf(Point(2.0,3.0),Point(4.0,3.0))),2.0,0,0),m,
            SimConfig(baseEntryHeadingDegrees=180.0))
        assertEquals(1.0,e.baseApproach.x,1e-9)
        // Straight exit through the front corridor (base safety zone), then routable surface.
        val leaving=routes.between(base,Point(4.0,3.0))
        assertEquals(listOf(base,e.baseApproach),leaving.take(2))
        assertTrue(routes.maneuverSafe(leaving[0],leaving[1]))
        assertTrue(leaving.drop(1).zipWithNext().all { (a,b)->routes.safe(a,b) })
        routes.requirePath(leaving)
        val blocked=TrajectoryPlanner.withDock(m,0.3065,listOf(listOf(Point(-0.3,2.0),Point(0.3,2.0),Point(0.3,4.0),Point(-0.3,4.0))),emptyList(),base,180.0,1.5)
        assertFalse(blocked.maneuverSafe(base,Point(1.0,3.0)))
        val isolated=TrajectoryPlanner.withDock(m,0.3065,emptyList(),emptyList(),Point(-5.0,3.0),180.0,1.5)
        assertThrows(IllegalArgumentException::class.java) { isolated.between(Point(-5.0,3.0),Point(4.0,3.0)) }
    }
    @Test fun robotPivotsBeforeMovingAndConsumesConfiguredReferenceBattery() {
        val e=engine(SimConfig(autonomyMinutes=60.0))
        // Link after leaving the base: in-place pivot before advancing.
        val initial=e.start().copy(phase=SimPhase.DEPARTING,heading=180.0)
        val pivot=e.advance(initial,1.0)
        assertEquals(initial.position,pivot.position)
        assertEquals(0.0,pivot.covered,1e-9)
        assertEquals(100.0-100.0/3600.0,pivot.battery,1e-9)
        val moved=e.advance(initial,12.0)
        assertTrue(moved.position.distance(initial.position)>0)
    }
    @Test fun transitUsesRecordedAxisAndAlignsBeforeEnteringPassage() {
        val left=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val right=MapShape(2,0,left.points.map { Point(it.x+12,it.y) })
        val passage=listOf(Point(4.0,3.0),Point(14.0,3.0))
        val m=RobotMap(listOf(left,right,MapShape(3,2,passage)),true)
        val routes=TrajectoryPlanner(m,0.31,emptyList())
        val trip=routes.between(Point(2.0,1.0),Point(16.0,5.0))
        val approach=Point(3.31,3.0)
        assertTrue(trip.any { it.distance(approach)<1e-8 })
        assertTrue(trip.zipWithNext().filter { (a,b)->a.x>=3.31 && b.x<=14.69 && a.y==3.0 }.all { (a,b)->b.y==3.0 })
        val preview=Preview(listOf(listOf(Point(16.0,5.0),Point(16.0,4.0))),1.0,0,0)
        val e=MowingSimulation(routes,Point(2.0,1.0),preview,m,SimConfig())
        var s=e.start(); var sawPivot=false; var sawPassage=false
        repeat(100) {
            val before=s; s=e.advance(s,1.0)
            if(before.position.distance(approach)<1e-8 && s.position==before.position && before.heading!=s.heading) sawPivot=true
            if(s.position.x>6 && s.position.x<12) {
                sawPassage=true; assertEquals(3.0,s.position.y,1e-9); assertEquals(if(s.phase==SimPhase.RETURNING) 180.0 else 0.0,s.heading,1e-9)
            }
        }
        assertTrue(sawPivot); assertTrue(sawPassage)
    }
    @Test fun passagesEndingAtZoneBoundariesRemainConnectedAfterClearanceInset() {
        val left=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val right=MapShape(2,0,left.points.map { Point(it.x+12,it.y) })
        val passage=listOf(Point(6.0,3.0),Point(12.0,3.0))
        val m=RobotMap(listOf(left,right,MapShape(3,2,passage)),true)
        val routes=TrajectoryPlanner(m,0.3065,emptyList())
        val trip=routes.between(Point(2.0,1.0),Point(16.0,5.0))
        assertTrue(trip.zipWithNext().all { (a,b)->routes.safe(a,b) })
        assertTrue(trip.any { it.distance(Point(5.31,3.0))<1e-8 })
        assertTrue(trip.any { it.distance(Point(12.69,3.0))<1e-8 })
        val reverse=routes.between(Point(9.0,3.0),Point(2.0,1.0))
        assertTrue(reverse.zipWithNext().all { (a,b)->routes.safe(a,b) })
    }
    @Test fun incompletePassageReportsTheZonesAndMissingAxis() {
        val left=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)),"Première")
        val right=MapShape(2,0,left.points.map { Point(it.x+12,it.y) },"Deuxième")
        val routes=TrajectoryPlanner(RobotMap(listOf(left,right,MapShape(3,2,listOf(Point(9.0,3.0)))),true),0.31,emptyList())
        val failure=assertThrows(IllegalArgumentException::class.java) { routes.between(Point(2.0,2.0),Point(14.0,2.0)) }
        assertTrue(failure.message!!.contains("Première → Deuxième"))
        assertTrue(failure.message!!.contains("1 passage(s) sans axe"))
    }
    @Test fun crossingAccessAxesConnectWithoutSharedRecordedVertex() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val passage=MapShape(2,2,listOf(Point(-4.0,3.0),Point(3.0,3.0)))
        val routes=TrajectoryPlanner(RobotMap(listOf(zone,passage),true),0.3065,emptyList(),
            accesses=listOf(listOf(Point(-2.0,1.0),Point(-2.0,4.0))))
        val trip=routes.between(Point(-2.0,1.0),Point(3.0,2.0))
        assertTrue(trip.any { it.distance(Point(-2.0,3.0)) < 1e-8 })
        routes.requirePath(trip)
        routes.requirePath(routes.between(Point(3.0,2.0),Point(-2.0,1.0)))
    }
    @Test fun offsetBaseWithinMappedPassageJoinsAxisBeforeTransit() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val passage=listOf(Point(-4.0,3.0),Point(0.0,3.0))
        val m=RobotMap(listOf(zone,MapShape(2,2,passage)),true)
        val routes=TrajectoryPlanner(m,0.3065,emptyList())
        val base=Point(-3.0,3.33); val target=Point(3.0,2.0)
        val outbound=routes.between(base,target)
        assertEquals(base,outbound.first())
        assertEquals(Point(-3.0,3.0),outbound[1])
        assertTrue(outbound.zipWithNext().all { (a,b)->routes.safe(a,b) })
        val inbound=routes.between(target,base)
        assertEquals(base,inbound.last())
        assertEquals(Point(-3.0,3.0),inbound[inbound.size-2])
        try { routes.between(Point(-3.0,3.5),target); fail("A base outside the corridor must stay refused") }
        catch(_:IllegalArgumentException) { }
    }
    @Test fun rotatedBaseConstrainsDepartureAndFinalDockingAxis() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(0.0,10.0)))
        val m=RobotMap(listOf(zone),true)
        val preview=Preview(listOf(listOf(Point(7.0,7.0),Point(8.0,7.0))),1.0,0,0)
        for(heading in listOf(0.0,90.0,180.0,270.0)) {
            val e=MowingSimulation(TrajectoryPlanner(m,0.3065,emptyList()),Point(5.0,5.0),preview,m,
                SimConfig(baseEntryHeadingDegrees=heading))
            val initial=e.start()
            assertEquals(e.base,initial.transit.first())
            assertEquals(e.baseApproach,initial.transit[1])
            var done=initial; var sawActivation=false; var sawReverse=false
            repeat(240) {
                val before=done; done=e.advance(done,1.0)
                if(before.phase==SimPhase.ALIGNING && done.phase==SimPhase.DOCKING) {
                    sawActivation=true
                    assertEquals(e.baseApproach,done.position)
                    assertEquals((heading+180.0)%360.0,done.heading,1e-6)
                    assertTrue(done.log.last().contains("Mode retour recharge simulé activé"))
                }
                if(before.phase==SimPhase.DOCKING && done.position!=before.position) {
                    sawReverse=true
                    val rad=Math.toRadians(done.heading)
                    val dot=(done.position.x-before.position.x)*kotlin.math.cos(rad)+
                        (done.position.y-before.position.y)*kotlin.math.sin(rad)
                    assertTrue("The movement must be opposite to the robot's nose",dot<0)
                    assertEquals(before.covered,done.covered,1e-9)
                    assertEquals(before.heading,done.heading,1e-9)
                }
            }
            assertTrue(sawActivation); assertTrue(sawReverse)
            assertEquals(SimPhase.COMPLETE,done.phase)
            assertEquals(e.base,done.position)
            assertEquals((heading+180.0)%360.0,done.heading,1e-6)
            assertEquals(e.baseApproach,done.transit[done.transit.size-2])
        }
    }
    @Test fun approachDistanceIsIndependentOfBaseInfluenceRadius() {
        val base=MapBase(Point(5.0,5.0),"Test",influenceRadiusM=3.0,entryHeadingDegrees=90.0,approachDistanceM=1.0)
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(0.0,10.0)))
        val m=RobotMap(listOf(zone),true)
        val preview=Preview(listOf(listOf(Point(7.0,7.0),Point(8.0,7.0))),1.0,0,0)
        val e=MowingSimulation(TrajectoryPlanner(m,0.3065,emptyList()),base.point,preview,m,
            SimConfig(baseEntryHeadingDegrees=base.entryHeadingDegrees,baseApproachMeters=base.approachDistanceM))
        assertEquals(1.0,e.base.distance(e.baseApproach),1e-9)
        val done=e.advance(e.start(),240.0)
        assertEquals(SimPhase.COMPLETE,done.phase)
        assertEquals(270.0,done.heading,1e-6)
    }
    @Test fun baseOrientationCannotCreateApproachOutsideMappedSurface() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val m=RobotMap(listOf(zone),true)
        val preview=Preview(listOf(listOf(Point(3.0,3.0),Point(4.0,3.0))),1.0,0,0)
        try {
            MowingSimulation(TrajectoryPlanner(m,0.3065,emptyList()),Point(0.5,3.0),preview,m,
                SimConfig(baseEntryHeadingDegrees=0.0))
            fail("The base axis must respect the map")
        } catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("Axe d’entrée")) }
    }
    @Test fun completeCycleReturnsChargesResumesAtSamePointAndFinishesAtBase() {
        val e=engine(); var s=e.start(); var sawReturn=false; var sawCharge=false; var sawResume=false
        var interrupted:Point?=null; var coverage=0.0
        repeat(25000) {
            if(s.phase!=SimPhase.COMPLETE && s.phase!=SimPhase.BLOCKED) {
                val before=s; s=e.advance(s,1.0)
                assertTrue(s.battery in 0.0..100.0); assertTrue(s.covered+1e-7>=coverage); coverage=s.covered
                assertTrue(e.routes.safe(s.position,s.position))
                if(s.phase==SimPhase.RETURNING && before.phase==SimPhase.MOWING && !s.completedReturn) { sawReturn=true; interrupted=before.position }
                if(s.phase==SimPhase.CHARGING) { sawCharge=true; assertEquals(e.base,s.position) }
                if(before.phase==SimPhase.DEPARTING && s.phase==SimPhase.MOWING && s.cycles>0) { sawResume=true; assertEquals(interrupted!!.x,s.position.x,1e-6); assertEquals(interrupted!!.y,s.position.y,1e-6) }
                if(before.phase in setOf(SimPhase.RETURNING,SimPhase.CHARGING,SimPhase.DEPARTING)) assertEquals(before.covered,s.covered,1e-6)
            }
        }
        assertTrue(s.log.toString(),sawReturn && sawCharge && sawResume)
        assertEquals(s.log.toString(),SimPhase.COMPLETE,s.phase)
        assertEquals(e.base,s.position); assertEquals(e.total,s.covered,1e-6)
        assertTrue("Final battery: ${s.battery}",s.battery>=19.0)
    }
    @Test(timeout=30000) fun detailedContoursAboveOldLimitRouteAroundDenseExclusion() {
        fun circle(radius:Double,count:Int)= (0 until count).map { i ->
            val angle=i*2*Math.PI/count; Point(radius*kotlin.math.cos(angle),radius*kotlin.math.sin(angle))
        }
        val zone=circle(30.0,4000); val hole=circle(10.0,2000)
        val detailed=RobotMap(listOf(MapShape(1,0,zone),MapShape(2,1,hole)),true)
        val routes=TrajectoryPlanner(detailed,0.6,listOf(hole))
        val path=routes.between(Point(-25.0,0.0),Point(25.0,0.0))
        assertTrue(path.size>2)
        assertTrue(path.zipWithNext().all { (a,b)->routes.safe(a,b) })
        assertTrue(path.zipWithNext().sumOf { (a,b)->a.distance(b) } in 50.0..54.7)
        val reverse=routes.between(Point(25.0,0.0),Point(-25.0,0.0))
        assertTrue(reverse.zipWithNext().all { (a,b)->routes.safe(a,b) })
        val preview=Preview(listOf(listOf(Point(-20.0,-15.0),Point(20.0,-15.0))),40.0,0,0)
        val engine=MowingSimulation(routes,Point(-25.0,0.0),preview,detailed,SimConfig())
        val final=engine.advance(engine.start(),600.0)
        assertEquals(SimPhase.COMPLETE,final.phase)
        assertEquals(engine.base,final.position)
    }
    @Test fun routesGoAroundExclusionInsteadOfCrossingIt() {
        val r=TrajectoryPlanner(map,0.6,holes)
        val path=r.between(Point(3.0,5.0),Point(12.0,5.0))
        assertTrue(path.size>2)
        assertTrue(path.zipWithNext().all { (a,b)->r.safe(a,b) })
        assertTrue(path.zipWithNext().sumOf { (a,b)->a.distance(b) }>9)
    }
    @Test fun importedPassagesConnectZonesAndBaseWithoutShortcutAcrossUnknownGround() {
        val left=MapShape(1,0,listOf(Point(0.0,0.0),Point(6.0,0.0),Point(6.0,6.0),Point(0.0,6.0)))
        val right=MapShape(2,0,left.points.map { Point(it.x+14,it.y) })
        val passage=listOf(Point(3.0,3.0),Point(3.0,9.0),Point(17.0,9.0),Point(17.0,3.0))
        val basePassage=listOf(Point(-3.0,3.0),Point(3.0,3.0))
        val m=RobotMap(listOf(left,right,MapShape(3,2,passage),MapShape(4,2,basePassage)),true)
        val r=TrajectoryPlanner(m,0.6,emptyList())
        val path=r.between(Point(-3.0,3.0),Point(17.0,3.0))
        assertTrue(path.any { it.y>8.5 })
        assertTrue(path.zipWithNext().all { (a,b)->r.safe(a,b) })
        assertFalse(r.safe(Point(8.0,3.0),Point(10.0,3.0)))
        path.zipWithNext().forEach { (a,b) ->
            for(i in 0..100) {
                val p=Point(a.x+(b.x-a.x)*i/100,a.y+(b.y-a.y)*i/100)
                assertTrue(Geometry.inside(p,left.points) || Geometry.inside(p,right.points) ||
                    (passage.zipWithNext()+basePassage.zipWithNext()).any { (x,y)->Geometry.distance(p,x,y)<=0.401 })
            }
        }
        val preview=MultiZonePlanner.plan(listOf(left,right),emptyList(),0.5,0.6,CoverageMode.LINES,false)
        val engine=MowingSimulation(r,Point(-3.0,3.0),preview,m,SimConfig(autonomyMinutes=30.0,chargeMinutes=5.0))
        val done=engine.advance(engine.start(),3600.0)
        assertEquals(SimPhase.COMPLETE,done.phase)
        assertEquals(engine.base,done.position)
        assertTrue(engine.edges.filter { it.cutting }.all { Geometry.inside(it.a,left.points) || Geometry.inside(it.a,right.points) })
    }
    @Test fun transitClearanceKeepsMappedNarrowNeckOpenWithoutChangingCuttingMargin() {
        val narrow=listOf(Point(0.0,0.0),Point(4.0,0.0),Point(4.0,1.6),Point(8.0,1.6),Point(8.0,0.0),Point(12.0,0.0),Point(12.0,4.0),Point(8.0,4.0),Point(8.0,2.4),Point(4.0,2.4),Point(4.0,4.0),Point(0.0,4.0))
        val m=RobotMap(listOf(MapShape(1,0,narrow)),true)
        val r=TrajectoryPlanner(m,fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM+0.05,emptyList())
        assertTrue(r.safe(Point(2.0,2.0),Point(10.0,2.0)))
        assertFalse(r.safe(Point(5.0,1.5),Point(7.0,1.5)))
        assertFalse(TrajectoryPlanner(m,0.6,emptyList()).safe(Point(2.0,2.0),Point(10.0,2.0)))
    }
    @Test fun importedPassageDoesNotEraseExclusion() {
        val m=map.copy(shapes=map.shapes+MapShape(3,2,listOf(Point(3.0,5.0),Point(12.0,5.0))))
        val r=TrajectoryPlanner(m,0.6,holes)
        assertFalse(r.safe(Point(7.0,5.0),Point(7.0,5.0)))
        assertTrue(r.between(Point(3.0,5.0),Point(12.0,5.0)).size>2)
    }
    @Test fun disconnectedZonesAreRejected() {
        val second=MapShape(3,0,listOf(Point(30.0,0.0),Point(36.0,0.0),Point(36.0,6.0),Point(30.0,6.0)))
        val m=map.copy(shapes=map.shapes+second)
        try { TrajectoryPlanner(m,0.6,holes).between(Point(1.0,1.0),Point(32.0,2.0)); fail("An unmapped access must block") }
        catch(e:IllegalArgumentException) { assertTrue(e.message!!.contains("liaison",ignoreCase=true)) }
    }
    @Test fun initialPercentageSeeksCoverageAndLowBatteryChargesBeforeDeparture() {
        val e=engine(SimConfig(initialProgress=75.0,initialBattery=15.0,autonomyMinutes=60.0,chargeMinutes=5.0))
        val s=e.start(); assertEquals(e.total*0.75,s.covered,1e-6); assertEquals(SimPhase.CHARGING,s.phase); assertEquals(e.base,s.position)
        // Recharge only until the rest is covered (return threshold + energy of the rest), then departure.
        val next=e.advance(s,15.0); assertTrue(next.battery>15); assertEquals(SimPhase.CHARGING,next.phase); assertEquals(s.covered,next.covered,1e-6)
    }
    @Test fun accelerationHasSameResultAsSmallSteps() {
        val e=engine(); var slow=e.start(); repeat(600) { slow=e.advance(slow,1.0) }
        val fast=e.advance(e.start(),600.0)
        assertEquals(slow,fast)
    }
    @Test fun rechargeUsesDirectShortcutFromInterruptedSegmentWithoutFollowingItsEnd() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(20.0,0.0),Point(20.0,10.0),Point(0.0,10.0)))
        val map=RobotMap(listOf(zone),true)
        val base=Point(2.0,5.0)
        val plan=Preview(listOf(listOf(Point(4.0,4.0),Point(16.0,4.0))),12.0,0,0)
        val engine=MowingSimulation(TrajectoryPlanner(map,0.3065,emptyList()),base,plan,map,
            // Short autonomy: the rest cannot be finished below the return threshold ([FinishPolicy]), the robot returns.
            SimConfig(initialProgress=50.0,baseEntryHeadingDegrees=180.0,autonomyMinutes=3.0))
        val queries=engine.routes.geometryQueries
        var state=engine.start()
        fun length(path:List<Point>)=path.zipWithNext().sumOf { (a,b)->a.distance(b) }
        assertEquals(base.distance(engine.baseApproach)+engine.baseApproach.distance(Point(10.0,4.0)),length(state.transit),1e-6)
        var guard=0
        while(state.phase in setOf(SimPhase.LEAVING_DOCK,SimPhase.DEPARTING) && guard++<200) state=engine.advance(state,1.0)
        assertEquals(SimPhase.MOWING,state.phase)
        val position=state.position
        state=engine.advance(state.copy(battery=20.0),1.0)
        assertEquals(SimPhase.RETURNING,state.phase)
        assertEquals(position,state.transit.first())
        assertEquals(position.distance(engine.baseApproach),length(state.transit),1e-6)
        assertEquals(queries,engine.routes.geometryQueries)
    }
    @Test fun scenarioStartAndFullPlaybackNeverRecalculateGeometry() {
        val prepared=engine()
        val queries=prepared.routes.geometryQueries
        assertTrue(queries>0)
        for(progress in listOf(0.0,75.0)) {
            val engine=prepared.withConfig(prepared.config.copy(initialProgress=progress,initialBattery=15.0,autonomyMinutes=5.0,instantCharging=true))
            assertSame(prepared.edges,engine.edges)
            var state=engine.start()
            repeat(2500) { state=engine.advance(state,30.0) }
            assertEquals(state.log.toString(),SimPhase.COMPLETE,state.phase)
            assertTrue(state.cycles>0)
            assertEquals(engine.total,state.covered,1e-6)
            assertEquals(queries,prepared.routes.geometryQueries)
        }
    }
    @Test fun speedAndPauseChangesPreserveInFlightMovement() {
        val engine=engine()
        val before=SimulationUi(engine,engine.start(),true,20)
        val next=engine.advance(before.state!!,2.0)
        val faster=applySimulationAdvance(before.copy(speed=300),before,next)
        assertSame(next,faster.state)
        assertEquals(300,faster.speed)
        assertTrue(faster.playing)
        val paused=applySimulationAdvance(before.copy(playing=false),before,next)
        assertSame(next,paused.state)
        assertFalse(paused.playing)
        val cleared=SimulationUi(speed=5)
        assertSame(cleared,applySimulationAdvance(cleared,before,next))
        val restarted=before.copy(state=engine.start())
        assertSame(restarted,applySimulationAdvance(restarted,before,next))
        assertFalse(applySimulationAdvance(before,before,next.copy(phase=SimPhase.BLOCKED)).playing)
    }
    @Test fun repeatedHeadingConnectionsReuseValidatedRoute() {
        val routes=TrajectoryPlanner(map,0.6,holes)
        val start=Point(2.0,2.0); val end=Point(12.0,2.0)
        val first=routes.between(start,end,0.0,0.0)
        assertSame(first,routes.between(start,end,0.0,0.0))
        routes.requirePath(first,0.0,0.0)
    }
    @Test fun everyPlaybackSpeedMovesAndCompletesWithoutCollision() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0),Point(0.0,10.0)))
        val map=RobotMap(listOf(zone),true)
        val preview=Preview(listOf(listOf(Point(7.0,7.0),Point(8.0,7.0))),1.0,0,0)
        for(speed in listOf(1,5,20,60,120,300)) {
            val engine=MowingSimulation(TrajectoryPlanner(map,0.3065,emptyList()),Point(5.0,5.0),preview,map,
                SimConfig(baseEntryHeadingDegrees=90.0))
            var state=engine.start()
            var moved=false
            repeat(kotlin.math.ceil(240.0/(speed*0.1)).toInt()) {
                state=engine.advance(state,speed*0.1)
                moved=moved || state.position.distance(engine.base)>0.1
            }
            assertTrue("Speed ×$speed: no movement",moved)
            assertEquals("Speed ×$speed: ${state.log}",SimPhase.COMPLETE,state.phase)
            assertEquals(engine.total,state.covered,1e-6)
            assertEquals(engine.base,state.position)
        }
    }
    @Test fun changingPlaybackSpeedDuringMissionKeepsProgress() {
        val engine=engine(SimConfig(autonomyMinutes=60.0))
        var state=engine.start()
        repeat(10) {
            for(speed in listOf(1,300,5,120,20,60)) {
                val before=state
                state=engine.advance(state,speed*0.1)
                assertNotEquals(state.log.toString(),SimPhase.BLOCKED,state.phase)
                assertTrue(state.elapsed>=before.elapsed)
                assertTrue(state.covered>=before.covered)
            }
        }
        assertTrue(state.covered>0.0)
    }
    @Test fun alreadyFinishedStartsAtBaseWithoutReplayingMission() {
        val e=engine(SimConfig(initialProgress=100.0)); val s=e.start()
        assertEquals(SimPhase.COMPLETE,s.phase); assertEquals(e.base,s.position); assertEquals(e.total,s.covered,1e-6)
    }
    @Test fun manualAccessConnectsExternalBaseWithoutCuttingOutsideZone() {
        val access=listOf(Point(-3.0,1.0),Point(2.0,1.0))
        val r=TrajectoryPlanner(map,0.6,holes,listOf(access))
        val p=r.between(access.first(),Point(4.0,2.0))
        assertTrue(p.zipWithNext().all { (a,b)->r.safe(a,b) })
        val preview=Geometry.plan(map.shapes.first().points,holes)
        val e=MowingSimulation(r,access.first(),preview,map,SimConfig())
        assertEquals(access.first(),e.start().position)
        assertTrue(e.edges.filter { it.cutting }.all { Geometry.inside(it.a,map.shapes.first().points) })
    }
    @Test fun manualAccessCannotEraseAnExclusion() {
        val r=TrajectoryPlanner(map,0.6,holes,listOf(listOf(Point(3.0,5.0),Point(12.0,5.0))))
        assertFalse(r.safe(Point(7.0,5.0),Point(7.0,5.0)))
        assertTrue(r.between(Point(3.0,5.0),Point(12.0,5.0)).size>2)
    }
    @Test fun invalidBaseIsRejected() {
        try { val e=engine(); MowingSimulation(e.routes,Point(7.0,5.0),e.preview,map,e.config); fail("Base dans une exclusion") }
        catch(_:IllegalArgumentException) { }
    }
    @Test fun unreachableResumeWithSmallAutonomyBlocksInsteadOfEndlessRecharge() {
        val e=engine(SimConfig(initialProgress=50.0,initialBattery=20.0,autonomyMinutes=0.1,chargeMinutes=0.1))
        val s=e.advance(e.start(),10.0)
        assertEquals(SimPhase.BLOCKED,s.phase)
    }

    /** 2026-10-15: a small remainder no longer waits for the resume threshold; charging stops as soon as the rest is covered above the return threshold. */
    @Test fun shortRestLeavesTheBaseBeforeTheHighThreshold() {
        val e = engine(SimConfig(initialProgress=75.0,initialBattery=15.0,autonomyMinutes=60.0,chargeMinutes=5.0,
            resumePercent=85.0,instantCharging=true))
        val initial = e.start()
        assertEquals(SimPhase.CHARGING, initial.phase)
        val next = e.advance(initial,1.0)
        assertEquals(SimPhase.LEAVING_DOCK,next.phase)
        assertTrue("departure at ${next.battery} %", next.battery < 85.0 && next.battery > 20.0)
        assertEquals((next.battery-15.0)*3.0,next.elapsed,1e-6)
        assertTrue(next.log.last().contains("Batterie suffisante pour finir"))
        // End of mowing without a return to recharge (the initial charge is not a cycle).
        var s = next; var guard = 0
        while (s.phase != SimPhase.COMPLETE && s.phase != SimPhase.BLOCKED && guard++ < 2000) s = e.advance(s, 60.0)
        assertEquals(SimPhase.COMPLETE, s.phase)
        assertEquals(0, s.cycles)
    }

    /** Return threshold reached with a short remainder: the robot finishes the mowing then returns, without an intermediate recharge. */
    @Test fun lowThresholdWithShortRestFinishesInsteadOfReturning() {
        val e = engine(SimConfig(initialProgress=95.0,initialBattery=60.0,autonomyMinutes=60.0))
        var s = e.start(); var guard = 0
        while (s.phase in setOf(SimPhase.LEAVING_DOCK,SimPhase.DEPARTING) && guard++ < 500) s = e.advance(s,1.0)
        assertEquals(SimPhase.MOWING, s.phase)
        s = e.advance(s.copy(battery=20.0),1.0)
        assertEquals(SimPhase.MOWING, s.phase)
        assertTrue(s.finishing)
        guard = 0
        while (s.phase != SimPhase.COMPLETE && s.phase != SimPhase.BLOCKED && guard++ < 2000) s = e.advance(s, 60.0)
        assertEquals(SimPhase.COMPLETE, s.phase)
        assertEquals(0, s.cycles)
        assertTrue(s.battery >= FinishPolicy.ARRIVAL_FLOOR_PERCENT)
    }
}
