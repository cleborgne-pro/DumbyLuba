package fr.luba.poc.diagnostics

import fr.luba.poc.map.*
import fr.luba.poc.simulation.PointPlanner
import fr.luba.poc.simulation.TrajectoryPlanner
import fr.luba.poc.obstacles.*
import org.junit.Assert.*
import org.junit.Test

class PointNavPlannerTest {
    private fun rect(x: Double,y: Double,w: Double,h: Double) = listOf(Point(x,y),Point(x+w,y),Point(x+w,y+h),Point(x,y+h))
    private val zone = rect(0.0,0.0,20.0,20.0)
    private fun map(vararg holes: List<Point>) = RobotMap(listOf(MapShape(1,0,zone)) +
        holes.mapIndexed { i,points -> MapShape((i+2).toLong(),1,points) },true,"BLE")
    @Test fun unobstructedPathIsExactlyTheDirectShortestSegment() {
        val start=Point(2.0,3.0); val end=Point(17.0,15.0)
        val plan=TrajectoryPlanner.pointPlan(map(),start,end,emptyList())
        assertEquals(listOf(start,end),plan.points)
        assertEquals(start.distance(end),plan.meters,1e-9)
        assertTrue(plan.bands.isEmpty() && plan.borders.isEmpty())
    }
    @Test fun exclusionUsesTheShorterSideAndEveryLegHasClearance() {
        val hole=rect(7.0,6.0,6.0,8.0)
        val start=Point(3.0,7.0); val end=Point(17.0,7.0)
        val plan=TrajectoryPlanner.pointPlan(map(hole),start,end,emptyList())
        assertTrue(plan.points.size>2)
        assertTrue(plan.points.any { it.y<6.0 })
        assertFalse(plan.points.any { it.y>14.0 })
        assertTrue(plan.meters>start.distance(end))
        // Same checks as mowing transits: LUBA 2 chassis inside the area along the whole route.
        requireNotNull(plan.routes).requirePath(plan.points)
        plan.points.zipWithNext().forEach { (a,b) -> assertTrue(Geometry.safe(a,b,zone,listOf(hole),RobotEnvelope.LUBA2.halfWidthM)) }
        assertTrue(plan.physicalGuard)
        assertEquals(start,plan.points.first()); assertEquals(end,plan.points.last())
    }
    @Test fun localObstacleIsAvoidedWithRobotTurningEnvelope() {
        val obstacle=LocalObstacle("tree",ObstacleKind.OTHER,10.0,10.0,0.5,0.3,createdAtMs=0)
        val plan=TrajectoryPlanner.pointPlan(map(),Point(3.0,10.0),Point(17.0,10.0),listOf(obstacle))
        plan.points.zipWithNext().forEach { (a,b) ->
            assertTrue(Geometry.distance(Point(obstacle.x,obstacle.y),a,b)>=obstacle.exclusionRadiusM+RobotEnvelope.LUBA2.halfWidthM)
        }
    }
    @Test fun rejectsForbiddenBoundaryDisconnectedAndOtherZoneTargets() {
        val start=Point(3.0,10.0)
        for ((m,target) in listOf(map(rect(7.0,6.0,6.0,8.0)) to Point(10.0,10.0),
            map() to Point(-0.2,10.0), map() to Point(30.0,10.0),
            map(rect(9.0,-1.0,2.0,22.0)) to Point(17.0,10.0))) {
            val error=runCatching { TrajectoryPlanner.pointPlan(m,start,target,emptyList()) }.exceptionOrNull()
            assertTrue("Dangerous destination accepted: $target", error is IllegalArgumentException || error is IllegalStateException)
        }
    }
    @Test fun rejectsIncompleteDemoAndNonFinitePositions() {
        for (m in listOf(map().copy(complete=false),Geometry.demo()))
            assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(m,Point(3.0,3.0),Point(10.0,10.0),emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),Point(Double.NaN,3.0),Point(10.0,10.0),emptyList()) }
    }

    /** Route accepted by the tracking checks (Fold) at every vertex, heading along the next segment. */
    private fun assertTracked(plan: MapNavPlan) {
        plan.points.zipWithNext().forEach { (a,b) ->
            val heading=Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))
            assertNull("$a",MapNavGuard.positionFailure(plan,a,heading))
            assertNull("$a→$b",MapNavGuard.motionFailure(plan,a,heading,b,150,0))
        }
        assertNull(MapNavGuard.positionFailure(plan,plan.points.last()))
    }
    private fun sectionLength(s: List<Point>) = s.zipWithNext().sumOf { (a,b) -> a.distance(b) }

    @Test fun destinationBeyondTheZonesIsJoinedStraightFromTheNearestEntry() {
        val start=Point(3.0,10.0); val target=Point(24.0,10.0)
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),start,target,emptyList()) }
        val plan=TrajectoryPlanner.pointPlan(map(),start,target,emptyList(),offMap=true)
        assertEquals(start,plan.points.first()); assertEquals(target,plan.points.last())
        assertEquals(1,plan.offMap.size)
        // Zone edge at x=20, robot centre half a width inside it: a little more than 4 m beyond the map.
        assertTrue("${plan.offMapMeters}",plan.offMapMeters in 4.0..4.6)
        assertEquals(target,plan.offMap.single().last())
        assertTrue(plan.offMap.single().all { it.x>=19.5 && kotlin.math.abs(it.y-10.0)<0.01 })
        requireNotNull(plan.routes).requirePath(plan.points)
        assertTracked(plan)
    }
    @Test fun robotBeyondTheZonesJoinsTheMapStraightThenUsesIt() {
        val start=Point(-3.0,10.0); val target=Point(10.0,10.0)
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),start,target,emptyList()) }
        val plan=TrajectoryPlanner.pointPlan(map(),start,target,emptyList(),offMap=true)
        assertEquals(start,plan.offMap.single().first())
        assertTrue("${plan.offMapMeters}",plan.offMapMeters in 3.0..3.6)
        assertTracked(plan)
    }
    @Test fun robotAndDestinationBeyondTheZonesUseTheDirectSection() {
        val start=Point(-3.0,10.0); val target=Point(-3.0,15.0)
        val plan=TrajectoryPlanner.pointPlan(map(),start,target,emptyList(),offMap=true)
        assertEquals(listOf(start,target),plan.points)
        assertEquals(5.0,plan.offMapMeters,0.05)
        assertTracked(plan)
    }
    @Test fun destinationAlongTheBorderIsReachedWithAShortSectionBeyondTheMap() {
        val start=Point(3.0,10.0); val target=Point(19.95,10.0)
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),start,target,emptyList()) }
        val plan=TrajectoryPlanner.pointPlan(map(),start,target,emptyList(),offMap=true)
        assertEquals(target,plan.points.last())
        assertTrue("${plan.offMapMeters}",plan.offMapMeters in 0.05..0.5)
    }
    @Test fun routeInsideTheZonesHasNoSectionBeyondTheMap() {
        val hole=rect(7.0,6.0,6.0,8.0)
        val plan=TrajectoryPlanner.pointPlan(map(hole),Point(3.0,7.0),Point(17.0,7.0),emptyList(),offMap=true)
        assertTrue(plan.points.size>2)
        assertTrue(plan.offMap.isEmpty())
        assertEquals(0.0,plan.offMapMeters,1e-9)
    }
    @Test fun sectionBeyondTheMapKeepsAwayFromNoGoZonesAndObstaclesAndStaysShort() {
        val start=Point(3.0,10.0)
        // No-go zone outside the zones, on the straight section.
        val outside=rect(21.0,9.0,1.5,2.0)
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(outside),start,Point(25.0,10.0),emptyList(),offMap=true) }
        // Destination inside the no-go zone.
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(outside),start,Point(21.5,10.0),emptyList(),offMap=true) }
        // Saved obstacle beyond the map, on the section.
        val tree=LocalObstacle("tree",ObstacleKind.OTHER,22.5,10.0,0.4,0.3,createdAtMs=0)
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),start,Point(25.0,10.0),listOf(tree),offMap=true) }
        // Too far beyond the map.
        val error=runCatching { TrajectoryPlanner.pointPlan(map(),start,Point(20.0+PointPlanner.MAX_OFF_MAP_M+5,10.0),emptyList(),offMap=true) }.exceptionOrNull()
        assertTrue("$error",error is IllegalArgumentException && error.message!!.contains("30"))
    }
    @Test fun referenceMapDestinationBeyondARecordedZoneIsTracked() {
        val json=org.json.JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array=json.getJSONArray("shapes")
        val shapes=(0 until array.length()).map { i -> val s=array.getJSONObject(i); val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0),p.getJSONArray(it).getDouble(1)) },s.getString("name")) }
        val map=RobotMap(shapes,true)
        val start=Point(1.2557,2.404)
        val zones=shapes.filter { it.kind==ShapeKind.ZONE }
        val home=zones.first { Geometry.inside(start,it.points) }
        // Points 3 m beyond each vertex of the robot's zone, outward from its centre, outside every shape.
        val cx=home.points.map { it.x }.average(); val cy=home.points.map { it.y }.average()
        val candidates=home.points.map { v -> val d=kotlin.math.hypot(v.x-cx,v.y-cy); Point(v.x+(v.x-cx)/d*3,v.y+(v.y-cy)/d*3) }
            .filter { p -> shapes.none { (it.kind==ShapeKind.ZONE || it.kind==ShapeKind.NO_GO) && Geometry.inside(p,it.points) } }
        val step=maxOf(1,candidates.size/8)
        val plans=candidates.filterIndexed { i,_ -> i%step==0 }.mapNotNull { target ->
            val began=System.nanoTime()
            runCatching { TrajectoryPlanner.pointPlan(map,start,target,emptyList(),offMap=true) }
                .also { println("POINT_OFF_MAP $target: ${(System.nanoTime()-began)/1_000_000} ms ${it.exceptionOrNull()?.message ?: "%.1f m beyond".format(it.getOrNull()?.offMapMeters)}") }
                .getOrNull()
        }
        assertTrue("no destination beyond ${home.name} reached among ${candidates.size}",plans.isNotEmpty())
        plans.forEach { plan ->
            assertTrue(plan.offMap.isNotEmpty() && plan.offMapMeters<=PointPlanner.MAX_OFF_MAP_M)
            assertTracked(plan)
        }
    }

    // Base at (2, 10) docking towards −x: link point at (3.5, 10), safety zone of ≈1.05 m around the base.
    private val base = fr.luba.poc.simulation.MapBase(Point(2.0,10.0),"Transmise par le robot",entryHeadingDegrees=180.0,approachDistanceM=1.5)
    private val safety = TrajectoryPlanner.baseSafetyRadius(1.5)

    @Test fun robotOnItsBaseLeavesStraightToTheLinkPointBeforeThePath() {
        val docked=Point(2.0,10.0); val target=Point(2.0,16.0)
        val plan=TrajectoryPlanner.pointPlan(map(),docked,target,emptyList(),base=base,charging=true)
        val exit=requireNotNull(plan.departure)
        assertEquals(Point(3.5,10.0),exit.approach)
        assertEquals(docked,exit.from)
        // The path starts at the link point and never enters the base safety zone (no pivot near the station).
        assertEquals(exit.approach,plan.points.first()); assertEquals(target,plan.points.last())
        plan.points.zipWithNext().forEach { (a,b) -> assertTrue("$a→$b",Geometry.distance(base.point,a,b)>=safety-0.03) }
        requireNotNull(plan.routes).requirePath(plan.points)
        assertTracked(plan)
    }
    @Test fun robotInsideTheBaseSafetyZoneAlsoLeavesStraight() {
        val plan=TrajectoryPlanner.pointPlan(map(),Point(2.6,10.03),Point(15.0,10.0),emptyList(),base=base)
        assertEquals(Point(3.5,10.0),requireNotNull(plan.departure).approach)
        // Away from the base, not charging: no exit.
        assertNull(TrajectoryPlanner.pointPlan(map(),Point(8.0,10.0),Point(15.0,10.0),emptyList(),base=base).departure)
    }
    @Test fun baseDepartureRefusals() {
        // Charging far from the configured base, next to the base outside the corridor, destination in the safety zone, axis unknown.
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),Point(8.0,10.0),Point(15.0,10.0),emptyList(),base=base,charging=true) }
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),Point(2.3,10.6),Point(15.0,10.0),emptyList(),base=base) }
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),Point(8.0,10.0),Point(2.5,10.2),emptyList(),base=base) }
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),Point(2.0,10.0),Point(15.0,10.0),emptyList(),
            base=base.copy(entryHeadingDegrees=null),charging=true) }
        assertThrows(IllegalArgumentException::class.java) { TrajectoryPlanner.pointPlan(map(),Point(2.0,10.0),Point(15.0,10.0),emptyList(),charging=true) }
    }
    @Test fun pathsAroundTheBaseNeverCrossItsSafetyZone() {
        // Straight line through the station without base, around its safety zone with it.
        val start=Point(1.5,8.0); val target=Point(1.5,12.0)
        val plan=TrajectoryPlanner.pointPlan(map(),start,target,emptyList(),base=base)
        assertNull(plan.departure)
        plan.points.zipWithNext().forEach { (a,b) -> assertTrue("$a→$b",Geometry.distance(base.point,a,b)>=safety-0.03) }
    }

    @Test(timeout=300000) fun referenceMapReachesEveryZoneThroughRecordedPassages() {
        val json=org.json.JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array=json.getJSONArray("shapes")
        val shapes=(0 until array.length()).map { i -> val s=array.getJSONObject(i); val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0),p.getJSONArray(it).getDouble(1)) },s.getString("name")) }
        val map=RobotMap(shapes,true)
        // Real robot position during the 2026-10-10 test (Aire de jeux zone).
        val start=Point(1.2557,2.404)
        val probe=fr.luba.poc.planning.RoutePlanning.navigation(map.copy(shapes=shapes.filter { it.kind==ShapeKind.ZONE || it.kind==ShapeKind.PASSAGE }),
            1.0,shapes.filter { it.kind==ShapeKind.NO_GO }.map { it.points },recordedBoundary=true) // destination well inside
        for (zone in shapes.filter { it.kind==ShapeKind.ZONE }) {
            val xs=zone.points.map { it.x }; val ys=zone.points.map { it.y }
            // A zone may contain pockets isolated by no-go zones: at least one point reached.
            val candidates=generateSequence(ys.min()) { it+0.5 }.takeWhile { it<ys.max() }.flatMap { y ->
                generateSequence(xs.min()) { it+0.5 }.takeWhile { it<xs.max() }.map { x -> Point(x,y) } }
                .filter { Geometry.inside(it,zone.points) && probe.safe(it,it) }.toList()
            val step=maxOf(1,candidates.size/25)
            val (target,plan)=candidates.filterIndexed { i,_ -> i%step==0 }.firstNotNullOfOrNull { t ->
                runCatching { TrajectoryPlanner.pointPlan(map,start,t,emptyList()) }.getOrNull()?.let { t to it } }
                ?: throw AssertionError("${zone.name} : aucun point atteignable")
            assertEquals("${zone.name}",target,plan.points.last())
            // No false stop: position and movement accepted by the tracking checks at every vertex.
            plan.points.zipWithNext().forEach { (a,b) ->
                val heading=Math.toDegrees(kotlin.math.atan2(b.y-a.y,b.x-a.x))
                assertNull("${zone.name} $a",MapNavGuard.positionFailure(plan,a,heading))
                assertNull("${zone.name} $a→$b",MapNavGuard.motionFailure(plan,a,heading,b,150,0))
            }
        }
    }

    /** Model of the real movement: positions at 1 Hz, gains measured on 2026-10-10, reported heading wrong by
     * [bias]° until the robot has corrected it after [biasUntilM] m travelled. Returns the maximum deviation
     * from the route and the number of false footprint alerts, or null if the arrival is not reached. */
    private fun drive(plan: MapNavPlan, bias: Double, biasUntilM: Double, fused: Boolean): Pair<Double,Int>? {
        val path = plan.points.zipWithNext().map { (a,b) -> fr.luba.poc.simulation.SimEdge(a,b,false) }
        val follower = fr.luba.poc.simulation.PathFollower(path,0,path.size,MapNavFollower.ARRIVAL_M)
        val reckoning = fr.luba.poc.simulation.DeadReckoning()
        var x = plan.points.first().x; var y = plan.points.first().y; var heading = 0.0
        var travelled = 0.0; var applied = 0 to 0; var worst = 0.0; var alerts = 0
        for (step in 0 until 15_000) {
            val ms = step * 200L
            val reported = (heading + if (travelled < biasUntilM) bias else 0.0) % 360
            if (ms % 1000 == 0L) {
                reckoning.fix(Point(x,y), reported, ms)
                val h = if (fused) reckoning.heading(reported) else reported
                val trusted = !fused || reckoning.headingConfirmed
                if (MapNavGuard.positionFailure(plan, Point(x,y), h, trusted) != null) alerts++
            }
            val pose = if (fused) reckoning.predict(ms)!! else fr.luba.poc.map.RobotPose(Point(x,y), reported)
            val linear = if (!fused || reckoning.headingConfirmed) fr.luba.poc.simulation.PathFollower.linearFor(30)
                else minOf(fr.luba.poc.simulation.PathFollower.linearFor(30), fr.luba.poc.simulation.DeadReckoning.UNCONFIRMED_LINEAR)
            val c = follower.command(pose.position, pose.headingDegrees, linear, fr.luba.poc.simulation.PathFollower.angularLimit(30),
                reckoning.degreesPerUnit, reckoning.metersPerUnit)
            if (c.done) return worst to alerts
            worst = maxOf(worst, follower.deviation(Point(x,y)))
            reckoning.sent(ms, c.linear, c.angular)
            val dt = 0.2
            heading += applied.second * 0.107 * dt
            val d = applied.first * 0.0007 * dt
            x += d * kotlin.math.cos(Math.toRadians(heading)); y += d * kotlin.math.sin(Math.toRadians(heading)); travelled += kotlin.math.abs(d)
            applied = c.linear to c.angular
        }
        return null
    }

    @Test(timeout=300000) fun wrongReportedHeadingAtStartNoLongerDerailsTheRealTrips() {
        val json=org.json.JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array=json.getJSONArray("shapes")
        val shapes=(0 until array.length()).map { i -> val s=array.getJSONObject(i); val p=s.getJSONArray("points")
            MapShape(s.getString("id").toLong(),s.getInt("type"),(0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0),p.getJSONArray(it).getDouble(1)) },s.getString("name")) }
        val map=RobotMap(shapes,true)
        // Starts and destinations of the 2026-10-10 tests at 11:18 and 11:20.
        for ((start,target) in listOf(Point(-5.2204,1.5013) to Point(-21.45854107697046,9.656652361875707),
                                      Point(-12.7004,8.1888) to Point(-9.095689373516244,-8.0470933199197))) {
            val plan=TrajectoryPlanner.pointPlan(map,start,target,emptyList())
            for (bias in listOf(90.0, -80.0, 0.0)) {
                val result = drive(plan, bias, 0.6, fused = true)
                assertNotNull("arrival not reached (bias $bias°)", result)
                val (worst, alerts) = result!!
                println("SUIVI_POINT start $start bias $bias°: max deviation %.3f m, alerts $alerts".format(worst))
                assertTrue("deviation %.2f m with a reported heading wrong by $bias°".format(worst), worst <= MapNavFollower.MAX_DEVIATION_M)
                assertEquals("false footprint alerts (bias $bias°)", 0, alerts)
            }
            // Control: without correction, the same wrong heading leaves the route (stops observed on 2026-10-10).
            val raw = drive(plan, 90.0, 0.6, fused = false)
            println("SUIVI_POINT control without correction: ${raw?.first ?: "arrival not reached"}")
            assertTrue(raw == null || raw.first > MapNavFollower.MAX_DEVIATION_M)
        }
    }
}
