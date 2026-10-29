package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.map.TurnOptimizer
import fr.luba.poc.mapsync.MapSyncPlanner
import fr.luba.poc.mapsync.SyncKind
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Edge cases of the curve tracking (turn tracking plan, step C of docs/TRACKING_TURNS_AND_BORDER_CONTACTS.md): arc-edge
 * threshold ([PathFollower.CURVE_ARC_EDGE_M]), degenerate arcs, pivot threshold inside an arc ([PathFollower.CURVE_PIVOT_DEGREES]),
 * uncalibrated rotation, exit of an arc onto a straight line, chamfered corners of the map sync outlines.
 */
class CurveTrackingEdgeCasesTest {
    private val dpu = DeadReckoning.DEFAULT_DEGREES_PER_UNIT
    private val mpu = DeadReckoning.DEFAULT_METERS_PER_UNIT
    private val linear = PathFollower.linearFor(63)
    private val angular = PathFollower.angularLimit(63)

    /** Edges of an arc of [radius] turning [degrees] (+ left) from [start] heading [heading], one vertex every 4°. */
    private fun arcEdges(start: Point, heading: Double, radius: Double, degrees: Double): List<SimEdge> {
        val sign = if (degrees >= 0) 1.0 else -1.0
        val h = Math.toRadians(heading)
        val centre = Point(start.x - sign * radius * sin(h), start.y + sign * radius * cos(h))
        val steps = ceil(abs(degrees) / 4.0).toInt()
        val a0 = atan2(start.y - centre.y, start.x - centre.x)
        return (0..steps).map { k -> val a = a0 + Math.toRadians(degrees * k / steps); Point(centre.x + radius * cos(a), centre.y + radius * sin(a)) }
            .zipWithNext().map { (a, b) -> SimEdge(a, b, true) }
    }

    /** Rolling U of 0.35 m between two 2 m passes (as in PathFollowerTest). */
    private fun rollingU(): List<SimEdge> = listOf(SimEdge(Point(-2.0, 0.0), Point(0.0, 0.0), true)) +
        arcEdges(Point(0.0, 0.0), 0.0, 0.35, 180.0) + listOf(SimEdge(Point(0.0, 0.7), Point(-2.0, 0.7), true))

    /** Point of the 0.35 m rolling U [degrees] round it, on the circle. */
    private fun onU(degrees: Double) = Point(0.35 * sin(Math.toRadians(degrees)), 0.35 - 0.35 * cos(Math.toRadians(degrees)))

    /**
     * Polygonal bend of [count] edges of [length] turning [turn]° left at each vertex, after a 2 m straight line, and
     * a 2 m straight line after it. With [atLeast], every bend edge is at least [length] long (floating point).
     */
    private fun polygonBend(length: Double, turn: Double, count: Int, atLeast: Boolean = false): List<SimEdge> {
        val points = mutableListOf(Point(-2.0, 0.0), Point(0.0, 0.0))
        var heading = 0.0
        repeat(count) {
            heading += turn
            val from = points.last()
            var l = length
            var next: Point
            while (true) {
                next = Point(from.x + l * cos(Math.toRadians(heading)), from.y + l * sin(Math.toRadians(heading)))
                if (!atLeast || from.distance(next) >= length) break
                l = Math.nextUp(l)
            }
            points += next
        }
        heading += turn
        val last = points.last()
        points += Point(last.x + 2.0 * cos(Math.toRadians(heading)), last.y + 2.0 * sin(Math.toRadians(heading)))
        return points.zipWithNext().map { (a, b) -> SimEdge(a, b, true) }
    }

    // ---------------------------------------------------------------------------------------------- degenerate arcs

    @Test fun `a straight line cut into short collinear edges is a straight pass, bit for bit`() {
        // Infinite radius: 2 cm edges, no turn. Same commands as on a single 4 m edge.
        val long = listOf(SimEdge(Point(0.0, 0.0), Point(4.0, 0.0), true))
        val short = (0 until 200).map { k -> SimEdge(Point(0.02 * k, 0.0), Point(0.02 * (k + 1), 0.0), true) }
        for ((lateralM, heading) in listOf(0.0 to 0.0, 0.05 to -3.0, -0.12 to 7.0, 0.2 to 12.0, -0.02 to -20.0)) {
            val a = PathFollower(long, 0, long.size, 0.15).command(Point(1.5, lateralM), heading, linear, angular)
            val b = PathFollower(short, 0, short.size, 0.15).command(Point(1.5, lateralM), heading, linear, angular)
            assertEquals("lateral $lateralM, heading $heading", a.linear to a.angular, b.linear to b.angular)
        }
        assertEquals(Double.POSITIVE_INFINITY, PathFollower.curveSpeed(0.0, angular, dpu), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, PathFollower.curveSpeed(1e-7, angular, dpu, rotationCalibrated = false), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, PathFollower.exitLimit(0.0, 0.5, 0.3), 0.0)
    }

    @Test fun `zero-length edges in an arc do not break the tracking`() {
        // Duplicated vertex at the entry of the arc (heading 0 there, as the atan2 of a zero edge): same commands as
        // without it, curve tracking, no pivot.
        val u = rollingU()
        val withZero = listOf(u[0], SimEdge(u[0].b, u[0].b, true)) + u.drop(1)
        assertTrue(withZero.any { it.meters == 0.0 })
        for (degrees in listOf(1.0, 3.0, 10.0, 45.0, 90.0, 135.0)) {
            val plain = PathFollower(u, 0, u.size, 0.15)
            val zero = PathFollower(withZero, 0, withZero.size, 0.15)
            val a = plain.command(onU(degrees), degrees, linear, angular)
            val b = zero.command(onU(degrees), degrees, linear, angular)
            assertTrue("$degrees°: $b", b.linear > 0 && b.angular > 0 && !b.done)
            assertFalse("$degrees°", zero.pivoting)
            assertEquals("$degrees°", a.linear to a.angular, b.linear to b.angular)
        }
        // Zero-length edges anywhere in the arc: finite commands at every pose (no NaN from a zero edge's tangent).
        val scattered = u.flatMapIndexed { i, e -> if (i in 1 until u.size - 1 && i % 9 == 0) listOf(e, SimEdge(e.b, e.b, true)) else listOf(e) }
        val follower = PathFollower(scattered, 0, scattered.size, 0.15)
        for (degrees in (1..179 step 2).map { it.toDouble() }) {
            val c = follower.command(onU(degrees), degrees, linear, angular)
            assertTrue("$degrees°: $c", c.linear >= 0 && abs(c.angular) <= angular && !c.done && !c.stopDistanceM.isNaN())
        }
        // A whole range made of one zero-length edge: done at once.
        val point = listOf(SimEdge(Point(1.0, 1.0), Point(1.0, 1.0), true))
        assertTrue(PathFollower(point, 0, 1, 0.15).command(Point(1.0, 1.0), 0.0, linear, angular).done)
    }

    // -------------------------------------------------------------------------------------- arc edge threshold (C1)

    /**
     * Robot on the middle of a bend edge, turned 6° to the right of it: on a chord (straight tracking) the steering is
     * saturated at half of the rotation limit; on a curve it may reach [PathFollower.CURVE_STEER_LIMIT] of it.
     */
    private fun steerOnBend(edges: List<SimEdge>, index: Int = 4, offset: Double = -6.0): Int {
        val e = edges[index]
        val middle = Point((e.a.x + e.b.x) / 2, (e.a.y + e.b.y) / 2)
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        val c = follower.command(middle, PathFollower.headingOf(e) + offset, linear, angular)
        assertFalse("$c", follower.pivoting)
        assertTrue("$c", c.linear > 0)
        return c.angular
    }

    @Test fun `bends of edges of 15 cm exactly keep the chord, just under 15 cm they are curves`() {
        // The plan's threshold, not the constant: arcs are made of edges under 15 cm.
        assertEquals(0.15, PathFollower.CURVE_ARC_EDGE_M, 0.0)
        val exact = polygonBend(0.15, 10.0, 9, atLeast = true)
        assertTrue(exact.subList(1, 10).all { it.meters >= 0.15 && it.meters < 0.15 + 1e-12 })
        // No stop vertex: 10° per vertex, 20° within the 15 cm of a tight rounded turn.
        assertTrue(PathFollower.stopVertices(exact, 0, exact.size).dropLast(1).none { it })
        assertEquals(angular / 2, steerOnBend(exact))
        val under = polygonBend(0.149, 10.0, 9)
        assertTrue(PathFollower.stopVertices(under, 0, under.size).dropLast(1).none { it })
        val curved = steerOnBend(under)
        assertTrue("$curved", curved > angular / 2 && curved <= (PathFollower.CURVE_STEER_LIMIT * angular).roundToInt())
        // Transit links of the bench (14 cm edges) are curves.
        assertTrue(steerOnBend(polygonBend(0.14, 10.0, 9)) > angular / 2)
    }

    /**
     * Chamfered corner of 16.5 cm edges (90° in four vertices of 22.5°, under the pivot threshold and not a tight rounded
     * turn): driven on the chord, not with the curve steering, so that the chassis stays in the recording corridor of
     * the map sync (MapSyncBenchTest).
     */
    @Test fun `a chamfered corner of 16_5 cm edges keeps the chord`() {
        val chamfer = polygonBend(0.165, 22.5, 3)
        assertTrue(PathFollower.stopVertices(chamfer, 0, chamfer.size).dropLast(1).none { it })
        // Robot on the line, turned 6° towards the next side: the chord leads it, the steering saturates at half of the
        // limit (the curve steering gives 77 to 153 units there).
        for (index in 1..3) assertEquals("edge $index", angular / 2, steerOnBend(chamfer, index, offset = 6.0))
    }

    /**
     * Map sync outline of a 1 m no-go zone (corners rounded to the pivot radius then simplified to chords): no edge under
     * [PathFollower.CURVE_ARC_EDGE_M], its corners are driven on the chord, never with the curve steering.
     */
    @Test fun `corners of a map sync outline keep the chord`() {
        val trace = MapSyncPlanner.trace(SyncKind.NO_GO, listOf(Point(7.0, 3.0), Point(8.0, 3.0), Point(8.0, 4.0), Point(7.0, 4.0)))
        val edges = trace.zipWithNext().map { (a, b) -> SimEdge(a, b, false) }
        val stops = PathFollower.stopVertices(edges, 0, edges.size)
        assertTrue("${edges.map { "%.3f".format(it.meters) }}", edges.all { it.meters >= PathFollower.CURVE_ARC_EDGE_M })
        for (i in 1 until edges.size - 1) {
            if (stops[i - 1]) continue
            val e = edges[i]
            for (t in listOf(0.25, 0.5, 0.75)) for (offset in listOf(-8.0, 0.0, 8.0)) {
                val p = Point(e.a.x + (e.b.x - e.a.x) * t, e.a.y + (e.b.y - e.a.y) * t)
                val follower = PathFollower(edges, 0, edges.size, 0.15)
                // Bring the follower to this edge along the route (the followed edge only moves forward).
                for (k in 0 until i) follower.command(edges[k].b, PathFollower.headingOf(edges[k]), linear, angular)
                val c = follower.command(p, PathFollower.headingOf(e) + offset, linear, angular)
                if (!follower.pivoting) assertTrue("edge $i, t $t, offset $offset: $c", abs(c.angular) <= angular / 2)
            }
        }
    }

    // ----------------------------------------------------------------------------------- pivots inside an arc (C3)

    @Test fun `inside an arc the robot pivots only beyond 40 degrees of heading error`() {
        val edges = rollingU()
        val at = onU(90.0)
        // Tangent at 90° round the U: 90°. 35° behind it, on the line: driven, not pivoted (25° on a straight line).
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        val driven = follower.command(at, 90.0 - 35.0, linear, angular)
        assertFalse("$driven", follower.pivoting)
        assertTrue("$driven", driven.linear > 0 && driven.angular > 0)
        // 35° ahead of it as well (the robot turned too much).
        val ahead = PathFollower(edges, 0, edges.size, 0.15)
        val c = ahead.command(at, 90.0 + 35.0, linear, angular)
        assertFalse("$c", ahead.pivoting)
        // 42° behind: pivot in place towards the tangent.
        val far = PathFollower(edges, 0, edges.size, 0.15)
        val pivot = far.command(at, 90.0 - 42.0, linear, angular)
        assertTrue("$pivot", far.pivoting)
        assertEquals(0, pivot.linear); assertTrue(pivot.angular > 0)
    }

    @Test fun `away from the arc or on a straight line the pivot threshold stays at 25 degrees`() {
        val edges = rollingU()
        // 20 cm outside the arc (beyond CURVE_PIVOT_LATERAL_M), 30° off the tangent: pivot.
        val outside = Point(0.55, 0.35)
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        follower.command(outside, 90.0 + 30.0, linear, angular)
        assertTrue(follower.pivoting)
        // 10 cm inside it, same heading error: driven.
        val inside = PathFollower(edges, 0, edges.size, 0.15)
        inside.command(Point(0.25, 0.35), 90.0 + 30.0, linear, angular)
        assertFalse(inside.pivoting)
        // Straight pass, 30° off: pivot, as before.
        val straight = listOf(SimEdge(Point(0.0, 0.0), Point(10.0, 0.0), true))
        val line = PathFollower(straight, 0, 1, 0.15)
        line.command(Point(3.0, 0.0), 30.0, linear, angular)
        assertTrue(line.pivoting)
    }

    // ------------------------------------------------------------------------------------ uncalibrated rotation (C2)

    @Test fun `uncalibrated rotation drives the arc at 0_7 of the calibrated curve speed`() {
        val edges = rollingU()
        val p = onU(60.0)
        val calibrated = PathFollower(edges, 0, edges.size, 0.15).command(p, 60.0, linear, angular, rotationCalibrated = true)
        val careful = PathFollower(edges, 0, edges.size, 0.15).command(p, 60.0, linear, angular, rotationCalibrated = false)
        assertTrue("$calibrated", calibrated.linear < linear)
        assertEquals(PathFollower.UNCALIBRATED_TURN_SHARE * calibrated.linear, careful.linear.toDouble(), 2.0)
        // The feed-forward follows the commanded speed: the rotation is smaller as well.
        assertTrue("$careful against $calibrated", careful.angular < calibrated.angular)
        for (kappa in listOf(20.0, 164.0, 400.0)) for (a in listOf(263, 351, 450))
            assertEquals(PathFollower.UNCALIBRATED_TURN_SHARE * PathFollower.curveSpeed(kappa, a, dpu),
                PathFollower.curveSpeed(kappa, a, dpu, rotationCalibrated = false), 1e-12)
        // 22 °/s at most whatever the setting; below the cap, proportional to the rotation limit.
        assertEquals(PathFollower.CURVE_MAX_RATE / 164.0, PathFollower.curveSpeed(164.0, 450, dpu), 1e-12)
        assertEquals(263 * dpu * PathFollower.CURVE_FF_SHARE / 164.0, PathFollower.curveSpeed(164.0, 263, dpu), 1e-12)
    }

    // ------------------------------------------------------------------------------ exit of an arc onto a line (C3)

    @Test fun `after the arc the straight line is tracked on the chord, held at the arc speed then raised`() {
        val edges = rollingU()
        val exit = edges.last()
        // Speed of the arc (turns over CURVE_WINDOW_M behind its last vertex).
        var sum = 0.0; var length = 0.0; var j = edges.size - 2
        while (length < PathFollower.CURVE_WINDOW_M) {
            sum += PathFollower.turnDegrees(edges[j], edges[j + 1]); length += edges[j].meters; j--
        }
        val hold = PathFollower.curveSpeed(sum / min(length, PathFollower.CURVE_WINDOW_M), angular, dpu)
        assertTrue(hold < linear * mpu)
        fun at(behind: Double): PathFollower.Command {
            val follower = PathFollower(edges, 0, edges.size, 0.15)
            for (k in 0 until edges.size - 1) follower.command(edges[k].b, PathFollower.headingOf(edges[k]), linear, angular)
            return follower.command(Point(exit.a.x - behind, exit.a.y), 180.0, linear, angular).also { assertFalse(follower.pivoting) }
        }
        for (behind in listOf(0.05, 0.2, 0.29)) {
            val c = at(behind)
            // On the line, along it: chord tracking, no feed-forward of the arc left behind.
            assertEquals("$behind m: $c", 0, c.angular)
            assertEquals("$behind m: $c", hold / mpu, c.linear.toDouble(), 1.5)
        }
        val ramp = at(0.55)
        assertEquals("$ramp", PathFollower.exitLimit(0.55, hold, linear * mpu) / mpu, ramp.linear.toDouble(), 1.5)
        assertEquals(linear, at(0.85).linear)
        // Same exit in the planner's duration model: 2 m after the arc cost more than at the set speed.
        val path = listOf(edges[1].a) + edges.subList(1, edges.size).map { it.b }
        val arcOnly = TurnOptimizer.drive(path.dropLast(1), linear * mpu)
        assertTrue(TurnOptimizer.drive(path, linear * mpu).durationS - arcOnly.durationS > 2.0 / (linear * mpu) + 0.1)
    }
}
