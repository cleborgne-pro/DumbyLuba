package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.*
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.*

class TrajectoryEnvelopeTest {
    private val factory = GeometryFactory()
    private fun rectangle(x: Double, y: Double, w: Double, h: Double): JtsGeometry = factory.createPolygon(
        arrayOf(Coordinate(x,y),Coordinate(x+w,y),Coordinate(x+w,y+h),Coordinate(x,y+h),Coordinate(x,y)))
    private fun room() = rectangle(-5.0,-5.0,10.0,10.0)
    private fun obstacle(x: Double,y: Double,r: Double) = factory.createPoint(Coordinate(x,y)).buffer(r)
    @Test fun standardDimensionsAndAsymmetricReferenceIncludeRearOverhang() {
        val standard = RobotEnvelope.LUBA2
        assertEquals(0.690,standard.lengthM,0.0)
        assertEquals(0.513,standard.widthM,0.0)
        assertEquals(0.273,standard.heightM,0.0)
        val shifted = RobotEnvelope(0.69,0.513,0.20)
        assertEquals(0.49,shifted.rearOverhangM,1e-9)
        assertEquals(hypot(0.49,0.2565),shifted.turnClearanceM,1e-9)
        val envelope = TrajectoryEnvelope(room().difference(obstacle(-0.45,0.0,0.02)),shifted)
        assertFalse(envelope.poseSafe(RobotPose(Point(0.0,0.0),0.0)))
        assertTrue(envelope.poseSafe(RobotPose(Point(0.0,0.0),180.0)))
    }
    @Test fun chassisOverlappingAnEdgeMayOnlyMoveAway() {
        // Robot facing east, its rear 14.5 cm beyond the west wall (stopped there after a drifting back-off).
        val envelope = TrajectoryEnvelope(room())
        val stuck = Point(-4.8, 0.0)
        assertFalse(envelope.poseSafe(RobotPose(stuck, 0.0)))
        assertNotNull(envelope.motionCollision(RobotPose(stuck, 0.0), RobotPose(Point(-3.5, 0.0), 0.0)))
        assertTrue("straight ahead, away from the wall", envelope.leavesStraight(stuck, Point(-3.5, 0.0), 0.0))
        assertTrue("slightly oblique", envelope.leavesStraight(stuck, Point(-3.5, 0.05), 0.0))
        assertFalse("diagonal: the overlapping rear sweeps new ground beyond the wall", envelope.leavesStraight(stuck, Point(-3.8, 1.0), 0.0))
        assertFalse("towards the wall", envelope.leavesStraight(stuck, Point(-5.5, 0.0), 0.0))
        assertFalse("along the wall, never inside", envelope.leavesStraight(stuck, Point(-4.8, 2.0), 0.0))
        assertFalse("no translation", envelope.leavesStraight(stuck, stuck, 0.0))
        // Thin obstacle after the chassis is back inside: the rest of the translation is still checked.
        val post = TrajectoryEnvelope(room().difference(obstacle(-3.0, 0.0, 0.05)))
        assertTrue(post.leavesStraight(stuck, Point(-3.9, 0.0), 0.0))
        assertFalse(post.leavesStraight(stuck, Point(-2.5, 0.0), 0.0))
        // Start already inside: plain translation check.
        assertTrue(envelope.leavesStraight(Point(0.0, 0.0), Point(1.0, 0.0), 0.0))
        assertFalse(post.leavesStraight(Point(-4.0, 0.0), Point(-2.0, 0.0), 0.0))
    }
    @Test fun straightSweepDetectsThinObstacleBetweenClearEndpoints() {
        val envelope = TrajectoryEnvelope(room().difference(rectangle(-0.001,-0.01,0.002,0.02)))
        assertTrue(envelope.poseSafe(RobotPose(Point(-2.0,0.0),0.0)))
        assertTrue(envelope.poseSafe(RobotPose(Point(2.0,0.0),0.0)))
        assertNotNull(envelope.straightCollision(Point(-2.0,0.0),Point(2.0,0.0),0.0))
    }
    @Test fun pivotEndpointsFitButCornerSweepHitsObstacle() {
        // The corner goes past x=0.345 m during the pivot, even though the 0° and 90° poses are clear.
        val envelope = TrajectoryEnvelope(room().difference(obstacle(0.42,0.0,0.008)))
        assertTrue(envelope.poseSafe(RobotPose(Point(0.0,0.0),0.0)))
        assertTrue(envelope.poseSafe(RobotPose(Point(0.0,0.0),90.0)))
        val collision = envelope.turnCollision(Point(0.0,0.0),0.0,90.0)
        assertNotNull(collision)
        assertEquals(EnvelopeMotion.TURN,collision!!.motion)
    }
    @Test fun smallObstacleBetweenAngularSamplesCannotTunnelThroughCornerArc() {
        val r=RobotEnvelope.LUBA2
        val angle=Math.toRadians(2.5)
        val x=r.frontM*cos(angle)-r.halfWidthM*sin(angle)
        val y=r.frontM*sin(angle)+r.halfWidthM*cos(angle)
        val envelope=TrajectoryEnvelope(room().difference(obstacle(x,y,0.00005)))
        assertTrue(envelope.poseSafe(RobotPose(Point(0.0,0.0),0.0)))
        assertTrue(envelope.poseSafe(RobotPose(Point(0.0,0.0),5.0)))
        assertNotNull(envelope.turnCollision(Point(0.0,0.0),0.0,5.0))
    }
    @Test fun straightPassageCanFitButHalfTurnCannot() {
        val envelope=TrajectoryEnvelope(rectangle(-3.0,-0.30,6.0,0.60))
        assertNull(envelope.pathCollision(listOf(Point(-2.0,0.0),Point(2.0,0.0))))
        assertNotNull(envelope.turnCollision(Point(0.0,0.0),0.0,180.0))
        assertNull(TrajectoryEnvelope(room()).turnCollision(Point(0.0,0.0),350.0,10.0))
    }
    @Test fun repeatedBorderTurnsKeepTheirGeometryAndTheCallingSegmentIndex() {
        val envelope=TrajectoryEnvelope(rectangle(0.0,0.0,2.0,2.0))
        val point=Point(1.0,0.29)
        repeat(3) { assertNull(envelope.turnCollision(point,0.0,5.0)) }
        val first=envelope.turnCollision(point,0.0,90.0,2)
        assertNotNull(first)
        val repeated=envelope.turnCollision(point,0.0,90.0,99)
        assertEquals(first!!.pose,repeated!!.pose)
        assertEquals(first.motion,repeated.motion)
        assertEquals(99,repeated.segment)
        assertNull(envelope.turnCollision(Point(1.0,0.8),0.0,90.0))
        assertNotNull(envelope.turnCollision(point,90.0,95.0))
    }
    @Test fun turnResultsAreNotSharedBetweenDifferentTerrainsOrRobotReferences() {
        val point=Point(0.0,0.0)
        val free=TrajectoryEnvelope(room())
        val blocked=TrajectoryEnvelope(room().difference(obstacle(0.42,0.0,0.008)))
        repeat(3) {
            assertNull(free.turnCollision(point,0.0,90.0))
            assertNotNull(blocked.turnCollision(point,0.0,90.0))
        }
        val rolling=TrajectoryEnvelope(room(),RobotEnvelope.LUBA2.copy(minimumTurnRadiusM=0.5))
        repeat(3) { assertThrows(IllegalArgumentException::class.java) { rolling.turnCollision(point,0.0,90.0) } }
    }
    @Test fun nonZeroTurningRadiusRejectsPivotAndArcSweepsCornerVolume() {
        val robot=RobotEnvelope.LUBA2.copy(minimumTurnRadiusM=0.50)
        val envelope=TrajectoryEnvelope(room().difference(obstacle(1.1,1.0,0.02)),robot)
        assertThrows(IllegalArgumentException::class.java) { envelope.turnCollision(Point(0.0,0.0),0.0,90.0) }
        assertThrows(IllegalArgumentException::class.java) { envelope.arcCollision(RobotPose(Point(0.0,0.0),0.0),90.0,0.49) }
        assertNotNull(envelope.arcCollision(RobotPose(Point(0.0,0.0),0.0),90.0,1.0))
        assertNull(TrajectoryEnvelope(room(),robot).arcCollision(RobotPose(Point(0.0,0.0),0.0),-90.0,1.0))
    }
    @Test fun plannerReservesRotatingCornersAtSmallMarginsAndClosesBordersSafely() {
        val zone=listOf(Point(0.0,0.0),Point(8.0,0.0),Point(8.0,6.0),Point(0.0,6.0))
        val hole=listOf(Point(3.0,2.0),Point(4.0,2.0),Point(4.0,3.0),Point(3.0,3.0))
        fun polygon(p:List<Point>)=factory.createPolygon((p+p.first()).map { Coordinate(it.x,it.y) }.toTypedArray())
        val envelope=TrajectoryEnvelope(polygon(zone).difference(polygon(hole)))
        val plan=Geometry.plan(zone,listOf(hole),margin=0.3)
        (plan.runs+plan.borders).forEach { run ->
            val closure=if(run.first()==run.last()) Math.toDegrees(atan2(run[1].y-run[0].y,run[1].x-run[0].x)) else null
            assertNull(envelope.pathCollision(run,finalHeading=closure))
        }
    }
    @Test fun simulationRefusesPointSafeTrajectoryWithCollidingOverhang() {
        val zone=listOf(Point(0.0,0.0),Point(5.0,0.0),Point(5.0,5.0),Point(0.0,5.0))
        val map=RobotMap(listOf(MapShape(1,0,zone)),true)
        val routes=TrajectoryPlanner(map,0.3,emptyList())
        val path=listOf(Point(1.0,0.31),Point(4.0,0.31),Point(4.0,1.0))
        assertTrue(path.zipWithNext().all { (a,b) -> routes.safe(a,b) })
        assertThrows(IllegalArgumentException::class.java) { routes.requirePath(path) }
        assertThrows(IllegalArgumentException::class.java) {
            MowingSimulation(routes,Point(2.0,2.0),Preview(listOf(path),3.69,0,0),map,SimConfig())
        }
    }
}
