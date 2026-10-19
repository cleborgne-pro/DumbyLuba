package fr.luba.poc.diagnostics

import fr.luba.poc.map.*
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
