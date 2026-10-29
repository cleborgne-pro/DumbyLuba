package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PathFollowerTest {
    private fun arc(radius: Double, steps: Int, cutting: Boolean = true): List<SimEdge> =
        (0..steps).map { i -> val a = PI * i / steps; Point(radius * cos(a), radius * sin(a)) }
            .zipWithNext().map { (a, b) -> SimEdge(a, b, cutting) }

    @Test fun `advances without pivot on a curve made of tiny edges`() {
        val edges = arc(3.0, 300) // 3 cm edges, 0.6° per vertex
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        val start = edges.first().a
        val command = follower.command(start, PathFollower.headingOf(edges.first()), 90, 75)
        assertTrue("advance expected, not a stop: $command", command.linear > 0)
        assertFalse(command.done)
        // The anticipated target goes beyond several edges: no more stopping every 3 cm.
        assertTrue(start.distance(command.target) > 0.3)
    }

    @Test fun `aligned first, a still robot faces a line it would otherwise join in an arc`() {
        // Map sync join of 2026-10-24: 23° to turn, under the 25° pivot threshold; the arc swung the chassis towards
        // the no-go zone the line ends next to.
        val heading = Math.toDegrees(atan2(0.28, 0.65))
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(0.65, 0.28), false))
        val arc = PathFollower(edges, 0, 1, 0.05).command(Point(0.0, 0.0), 0.0, 150, 263)
        assertTrue("arc expected without the alignment: $arc", arc.linear > 0 && arc.angular > 0)
        val aligned = PathFollower(edges, 0, 1, 0.05).apply { alignFirst() }
        val pivot = aligned.command(Point(0.0, 0.0), 0.0, 150, 263)
        assertEquals(0, pivot.linear); assertTrue(pivot.angular > 0); assertTrue(aligned.pivoting)
        // Faced within the exit threshold: straight on, no further pivot.
        val forward = aligned.command(Point(0.0, 0.0), heading - 2.0, 150, 263)
        assertTrue("$forward", forward.linear > 0); assertFalse(aligned.pivoting)
        // Already facing it: alignFirst costs nothing.
        val facing = PathFollower(edges, 0, 1, 0.05).apply { alignFirst() }.command(Point(0.0, 0.0), heading, 150, 263)
        assertTrue("$facing", facing.linear > 0)
    }

    @Test fun `pivots at the vertex of a sharp corner and not before`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), false), SimEdge(Point(2.0, 0.0), Point(2.0, 2.0), false))
        val follower = PathFollower(edges, 0, 2, 0.15)
        // Braking at constant deceleration: full speed far from the corner, slowed down when approaching it.
        assertEquals(500, follower.command(Point(1.0, 0.0), 0.0, 500, 75).linear)
        val before = follower.command(Point(1.6, 0.0), 0.0, 500, 75)
        assertEquals(0, before.angular); assertTrue(before.linear in 20 until 500)
        assertEquals(0, follower.index)
        // No pivot 10 cm from the vertex: the next stripe would start offset by as much.
        follower.command(Point(1.9, 0.0), 0.0, 90, 75)
        assertEquals(0, follower.index)
        val atCorner = follower.command(Point(1.97, 0.0), 0.0, 90, 75)
        assertEquals(1, follower.index)
        assertEquals(0, atCorner.linear); assertTrue(atCorner.angular >= PathFollower.MIN_PIVOT)
    }

    @Test fun `the pivot never goes below the useful minimum`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(0.0, 3.0), false))
        val follower = PathFollower(edges, 0, 1, 0.15)
        assertEquals(0 to 450, follower.command(Point(0.0, 0.0), 0.0, 150, 450).let { it.linear to it.angular })
        // Pivot in progress, 7° left: never below the threshold where the LUBA 2 stalls (survey of 2026-10-10).
        val near = follower.command(Point(0.0, 0.0), 83.0, 150, 450)
        assertEquals(0, near.linear); assertTrue(near.angular >= PathFollower.MIN_PIVOT)
        assertTrue(PathFollower.angularLimit(30) >= 250)
        assertEquals(450, PathFollower.angularLimit(100))
        assertEquals(500, PathFollower.linearFor(100))
    }

    /**
     * Rounded no-go corner (execution bench of 2026-10-10): 9 vertices of 6° over 7 cm, impossible to follow while
     * driving. A single stop, in the middle of the turn; a wide curve is still followed without stopping.
     */
    @Test fun `a tight rounded turn becomes a single stop in its middle`() {
        val points = mutableListOf(Point(-1.0, 0.0), Point(0.0, 0.0))
        var heading = 0.0
        repeat(9) { heading += 6.0; val last = points.last()
            points += Point(last.x + 0.008 * cos(Math.toRadians(heading)), last.y + 0.008 * sin(Math.toRadians(heading))) }
        points += points.last().let { Point(it.x + cos(Math.toRadians(heading)), it.y + sin(Math.toRadians(heading))) }
        val edges = points.zipWithNext().map { (a, b) -> SimEdge(a, b, true) }
        val stops = PathFollower.stopVertices(edges, 0, edges.size)
        assertEquals("a single stop", 1, stops.count { it })
        assertTrue("stop inside the rounding: vertex ${stops.indexOfFirst { it }}", stops.indexOfFirst { it } in 1..4)
        // The tracking stops and pivots at this vertex towards the rest of the route, instead of crawling through the rounding.
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        val corner = stops.indexOfFirst { it }
        val atCorner = follower.command(edges[corner].b, 0.0, 300, 300)
        assertTrue(follower.index > corner)
        assertEquals(0, atCorner.linear); assertTrue(atCorner.angular > 0)
        // Wide curve (radius 3 m, 3 cm edges): no stop.
        assertEquals(0, PathFollower.stopVertices(arc(3.0, 300), 0, 300).count { it })
    }

    @Test fun `arrives at the corner at approach speed without crawling`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), false), SimEdge(Point(2.0, 0.0), Point(2.0, 2.0), false))
        val follower = PathFollower(edges, 0, 2, 0.15)
        // 10 cm from the vertex, still above the minimum approach: no more stopping 8 cm before the corner.
        val near = follower.command(Point(1.9, 0.0), 0.0, 500, 300, minLinear = RangeArrival.APPROACH_MIN_LINEAR)
        assertTrue("advance ${near.linear}", near.linear > RangeArrival.APPROACH_MIN_LINEAR)
        val close = follower.command(Point(1.945, 0.0), 0.0, 500, 300, minLinear = RangeArrival.APPROACH_MIN_LINEAR)
        assertEquals(RangeArrival.APPROACH_MIN_LINEAR, close.linear)
    }

    @Test fun `slows down when the measured deviation grows without going below half`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(10.0, 0.0), false))
        fun speed(deviation: Double) = PathFollower(edges, 0, 1, 0.15).command(Point(1.0, 0.0), 0.0, 500, 300, deviationM = deviation).linear
        assertEquals(500, speed(0.0)); assertEquals(500, speed(PathFollower.GOVERNOR_START_M))
        assertTrue(speed(0.2) in 251..499)
        assertEquals(250, speed(0.29))
    }

    @Test fun `careful in curves as long as the rotation is not calibrated`() {
        val edges = arc(1.0, 100)
        fun speed(calibrated: Boolean) = PathFollower(edges, 0, edges.size, 0.15)
            .command(edges[10].a, PathFollower.headingOf(edges[10]), 500, 450, rotationCalibrated = calibrated).linear
        assertTrue("${speed(false)} < ${speed(true)}", speed(false) < speed(true))
    }

    @Test fun `finishes at the last point with the requested tolerance`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(1.0, 0.0), false))
        val follower = PathFollower(edges, 0, 1, 0.05)
        assertFalse(follower.command(Point(0.9, 0.0), 0.0, 90, 75).done)
        assertTrue(follower.command(Point(0.97, 0.0), 0.0, 90, 75).done)
    }

    /**
     * Unicycle model at 5 Hz: positions published at 1 Hz only (firmware 1.30 survey), command applied with a 200 ms
     * delay, actual gains [rawToMps]/[rawToDegPerS] unknown to the tracking (estimator initialised to the default
     * values then calibrated online).
     */
    private fun drive(edges: List<SimEdge>, rawToMps: Double, rawToDegPerS: Double, speedPercent: Int,
                      delaySteps: Int = 1, headingError: Double = 0.0, commandDelayMs: Long = 0L): Triple<Double, Double, Int> {
        var x = edges.first().a.x; var y = edges.first().a.y
        var heading = PathFollower.headingOf(edges.first()) + headingError
        val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat(delaySteps - 1) { q.addLast(0 to 0) } }
        var applied = 0 to 0
        var ms = 0L; var worst = 0.0; var pivots = 0
        val reckoning = DeadReckoning(commandDelayMs = commandDelayMs)
        var from = 0
        reckoning.fix(Point(x, y), heading, 0)
        while (from < edges.size) {
            var to = from + 1
            while (to < edges.size && edges[to].cutting == edges[from].cutting) to++
            val follower = PathFollower(edges, from, to, 0.15)
            var turning = false
            while (true) {
                if (ms % 1000 == 0L) reckoning.fix(Point(x, y), heading, ms)
                val pose = reckoning.predict(ms)!!
                val c = follower.command(pose.position, pose.headingDegrees, PathFollower.linearFor(speedPercent),
                    PathFollower.angularLimit(speedPercent), reckoning.degreesPerUnit, reckoning.metersPerUnit)
                if (c.done) break
                worst = max(worst, follower.deviation(Point(x, y)))
                val pivot = c.linear == 0 && c.angular != 0
                if (pivot && !turning) pivots++
                turning = pivot
                reckoning.sent(ms, c.linear, c.angular)
                // Delay of [delaySteps] 200 ms periods between sending and the effect on the robot.
                val dt = 0.2
                heading += applied.second * rawToDegPerS * dt
                x += applied.first * rawToMps * dt * cos(Math.toRadians(heading))
                y += applied.first * rawToMps * dt * sin(Math.toRadians(heading))
                pending.addLast(c.linear to c.angular); applied = pending.removeFirst()
                ms += 200
                check(ms < 20_000_000) { "tracking blocked" }
            }
            reckoning.sent(ms, 0, 0)
            applied = 0 to 0; pending.clear(); repeat(delaySteps - 1) { pending.addLast(0 to 0) }
            from = to
        }
        return Triple(ms / 1000.0, worst, pivots)
    }

    private fun piscineEdges(): List<SimEdge> {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/planner-reference-map.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i -> val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { Point(p.getJSONArray(it).getDouble(0), p.getJSONArray(it).getDouble(1)) }, s.getString("name")) }
        val map = RobotMap(shapes, true); val station = json.getJSONObject("station")
        val base = Point(station.getDouble("x"), station.getDouble("y")); val heading = station.getDouble("heading")
        val zone = shapes.single { it.name == "Piscine" }; val holes = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val preview = MowingOrder.apply(Geometry.plan(zone.points, holes, spacing = 0.25, margin = 0.6, borders = true, recordedBoundary = true, basePoint = base), true, true, base)
        val routes = TrajectoryPlanner.withDock(map, 0.3065, holes, emptyList(), base, heading, 1.5, recordedBoundary = true)
        val edges = MowingSimulation(routes, base, preview, map, SimConfig(baseEntryHeadingDegrees = heading, instantCharging = true)).edges
        return edges
    }

    @Test(timeout = 300000) fun `actual Piscine route followed continuously under 30 cm of deviation`() {
        val edges = piscineEdges()
        val sharp = edges.zipWithNext().count { (a, b) -> PathFollower.turnDegrees(a, b) > PathFollower.CORNER_DEGREES }
        // Gains measured on 2026-10-10 (≈ 0.95 mm/s and 0.12 °/s per unit) then ±40 % deviations.
        for ((mps, degs) in listOf(0.00095 to 0.12, 0.0006 to 0.08, 0.0013 to 0.17)) for (percent in listOf(30, 100)) {
            val (seconds, worst, pivots) = drive(edges, mps, degs, percent)
            val meters = edges.sumOf { it.meters }
            println("TRACKING $mps m/s·u, $degs °/s·u, $percent %% : ${edges.size} edges, $sharp corners, $pivots pivots, max deviation %.3f m, %.0f s, %.3f m/s".format(worst, seconds, meters / seconds))
            assertTrue("max deviation $worst m ($mps, $degs, $percent %)", worst <= 0.3)
            assertTrue("pivots $pivots for $sharp sharp corners", pivots <= sharp + edges.size / 10)
            assertTrue("average speed ${meters / seconds} m/s", meters / seconds > 0.35 * PathFollower.linearFor(percent) * mps)
        }
    }

    /**
     * Survey of 2026-10-10 (navigation test): effective command → measured heading delay of 0.5 to 1 s; the tracking
     * zigzagged by ±30 cm at 100 % speed (stop "écart supérieur à 30 cm"). With the pose predicted at the moment the
     * command takes effect, the striped course stays under 25 cm.
     */
    @Test(timeout = 300000) fun `stripes at full speed under 25 cm despite the command delay`() {
        val points = listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(4.0, 0.4), Point(0.0, 0.4), Point(0.0, 0.8), Point(4.0, 0.8))
        val bands = points.zipWithNext().map { (a, b) -> SimEdge(a, b, false) }
        for (delaySteps in 1..5) for (error in listOf(0.0, 20.0)) for (degs in listOf(0.08, 0.10, 0.13)) {
            val (_, worst, _) = drive(bands, 0.00114, degs, 100, delaySteps, error, DeadReckoning.NAV_COMMAND_DELAY_MS)
            assertTrue("max deviation $worst m (delay ${delaySteps * 200} ms, initial heading $error°, $degs °/s·u)", worst <= 0.27)
        }
    }

    @Test(timeout = 300000) fun `straight line at full speed under 25 cm despite the command delay`() {
        val line = listOf(SimEdge(Point(0.0, 0.0), Point(8.0, 0.0), false))
        for (delaySteps in 1..5) for (error in listOf(0.0, 20.0, -40.0)) for (degs in listOf(0.08, 0.10, 0.13)) {
            val (_, worst, _) = drive(line, 0.00114, degs, 100, delaySteps, error, DeadReckoning.NAV_COMMAND_DELAY_MS)
            assertTrue("max deviation $worst m (delay ${delaySteps * 200} ms, initial heading $error°, $degs °/s·u)", worst <= 0.27)
        }
    }

    private data class RangeRun(val worst: Double, val seconds: Double, val endErrors: List<Double>, val approaches: Int)

    /**
     * Same loop as the actual cutting test ([fr.luba.poc.ble.RobotBle.runMapNavTest]): positions published at 1 Hz,
     * command delay, inertia, actual gains unknown to the tracking, one range per blade state with STOP between two
     * ranges, deviation checked on the **published** position (not the true one).
     */
    private fun driveRanges(edges: List<SimEdge>, rawToDegPerS: Double, delaySteps: Int, headingError: Double = 0.0,
                            rawToMps: Double = 0.00105, tauS: Double = 0.25, friction: Int = 0): RangeRun {
        var x = edges.first().a.x; var y = edges.first().a.y
        var heading = PathFollower.headingOf(edges.first()) + headingError
        var v = 0.0; var w = 0.0
        val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat(delaySteps) { q.addLast(0 to 0) } }
        val reckoning = DeadReckoning(commandDelayMs = DeadReckoning.NAV_COMMAND_DELAY_MS)
        var ms = 0L; var fixAt = 0L; var measured = Point(x, y)
        var worst = 0.0; var approaches = 0
        val endErrors = mutableListOf<Double>()
        fun step(command: Pair<Int, Int>) {
            pending.addLast(command); val (l, a) = pending.removeFirst()
            val k = 1 - exp(-0.2 / tauS)
            // LUBA 2 friction: survey of 2026-10-10, almost no forward motion below ≈ 60 units.
            val effective = sign(l.toDouble()) * max(0, abs(l) - friction)
            v += (effective * rawToMps - v) * k; w += (a * rawToDegPerS - w) * k
            heading += w * 0.2
            x += v * 0.2 * cos(Math.toRadians(heading)); y += v * 0.2 * sin(Math.toRadians(heading))
            ms += 200
            if (ms % 1000 == 0L) { measured = Point(x, y); fixAt = ms; reckoning.fix(measured, heading, ms) }
            check(ms < 3_600_000) { "tracking blocked" }
        }
        reckoning.fix(measured, heading, 0)
        var from = 0
        while (from < edges.size) {
            var to = from + 1
            while (to < edges.size && edges[to].cutting == edges[from].cutting) to++
            // Start of range: STOP then blade command, like the robot.
            reckoning.sent(ms, 0, 0); step(0 to 0)
            val follower = PathFollower(edges, from, to, RangeArrival.TARGET_M)
            val arrival = RangeArrival(DeadReckoning.NAV_COMMAND_DELAY_MS)
            // Same guard as the robot: 3 s of forward commands without 3 cm travelled = presumed blockage.
            val guard = fr.luba.poc.diagnostics.MotionProgressGuard()
            while (true) {
                val deviation = follower.deviation(measured)
                worst = max(worst, deviation)
                assertTrue("deviation %.3f m > 30 cm, edge ${follower.index} (rotation $rawToDegPerS, delay ${delaySteps * 200} ms)".format(deviation), deviation <= 0.3)
                val pose = reckoning.predict(ms)!!
                val limit = if (reckoning.pivotUnconfirmed()) DeadReckoning.POST_PIVOT_LINEAR else PathFollower.linearFor(100)
                val c = follower.command(pose.position, pose.headingDegrees, limit, PathFollower.angularLimit(100),
                    reckoning.degreesPerUnit, reckoning.metersPerUnit, RangeArrival.APPROACH_MIN_LINEAR)
                if (c.done) {
                    when (arrival.check(ms, fixAt, measured, follower, edges[to - 1].b)) {
                        null -> { guard.reset(); reckoning.sent(ms, 0, 0); step(0 to 0); continue }
                        false -> continue
                        true -> break
                    }
                }
                assertFalse("presumed blockage %.2f m from the point (friction $friction, delay ${delaySteps * 200} ms)"
                    .format(measured.distance(edges[to - 1].b)), guard.blocked(measured, fixAt, ms, c.linear))
                reckoning.sent(ms, c.linear, c.angular); step(c.linear to c.angular)
            }
            approaches += arrival.approaches
            endErrors += Point(x, y).distance(edges[to - 1].b)
            from = to
        }
        return RangeRun(worst, ms / 1000.0, endErrors, approaches)
    }

    /**
     * Cutting test of 2026-10-10 (14:07, Luba-VSDEMO01): stripe 1 blades running, 0.8 m link, then stop "écart supérieur
     * à 30 cm (30,3 cm)" at the start of stripe 2: the blades never came back on.
     */
    @Test(timeout = 300000) fun `actual cutting test of 2026-10-10 all ranges chain under 30 cm`() {
        val points = listOf(Point(-13.346, -1.5371), Point(-14.3004, -1.5371), Point(-11.846, -1.5371), Point(-11.846, -0.7371),
            Point(-13.7268, -0.7371), Point(-14.7847, -2.3371), Point(-11.846, -2.3371))
        val cutting = listOf(false, true, false, true, false, true)
        val edges = points.zipWithNext().mapIndexed { i, (a, b) -> SimEdge(a, b, cutting[i]) }
        for (delaySteps in 2..5) for (degs in listOf(0.08, 0.10, 0.13)) for (error in listOf(0.0, 25.0)) for (friction in listOf(0, 30, 55)) {
            val run = driveRanges(edges, degs, delaySteps, error, friction = friction)
            println("CUTTING rotation $degs, delay ${delaySteps * 200} ms, heading $error°, friction $friction: max deviation %.3f m, ends %s, %d resumes, %.0f s"
                .format(run.worst, run.endErrors.joinToString { "%.2f".format(it) }, run.approaches, run.seconds))
            assertTrue("max deviation ${run.worst}", run.worst <= 0.27)
            // Each blade change happens at the actual corner, not 30 cm before. Actual delay measured ≈ 0.6 s;
            // at 1 s (+ inertia, without friction) the robot overshoots the corner by 20–25 cm at most.
            assertTrue("range ends ${run.endErrors}", run.endErrors.all { it <= if (delaySteps <= 4) 0.2 else 0.27 })
            assertEquals(6, run.endErrors.size)
        }
    }

    @Test fun `end of range arrival waits for a position after the stop then resumes if short`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), true))
        val follower = PathFollower(edges, 0, 1, 0.15)
        val arrival = RangeArrival(600)
        // Position published before the stop: wait.
        assertNull(arrival.check(10_000, 9_800, Point(1.7, 0.0), follower, edges[0].b))
        assertNull(arrival.check(10_400, 10_800, Point(1.7, 0.0), follower, edges[0].b))
        // Position after the stop, 30 cm before the corner: resume the approach.
        assertEquals(false, arrival.check(10_600, 11_000, Point(1.7, 0.0), follower, edges[0].b))
        assertEquals(1, arrival.approaches)
        assertNull(arrival.check(12_000, 11_000, Point(1.9, 0.0), follower, edges[0].b))
        assertEquals(true, arrival.check(12_200, 13_000, Point(1.9, 0.0), follower, edges[0].b))
        // Passed or offset sideways only: accepted without reversing.
        val other = RangeArrival(600)
        other.check(0, 0, Point(2.2, 0.1), follower, edges[0].b)
        assertEquals(true, other.check(100, 1_000, Point(2.2, 0.1), follower, edges[0].b))
    }

    /** Execution bench of 2026-10-10: `arrival?.check(...) ?: true` accepted the end of range without waiting for the position. */
    @Test fun `the end of range waits for the measured position except when approaching the base`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), true))
        val follower = PathFollower(edges, 0, 1, RangeArrival.TARGET_M)
        assertNull(RangeArrival.settle(RangeArrival(600), 10_000, 9_800, Point(1.9, 0.0), follower, edges[0].b))
        assertEquals(true, RangeArrival.settle(null, 10_000, 9_800, Point(1.9, 0.0), follower, edges[0].b))
    }

    @Test fun `deviation measured on the short edges already left by the predicted pose`() {
        // Move to a point of 2026-10-10: 13 cm edge skipped, 45 cm deviation wrongly reported.
        val points = listOf(Point(-11.418, 4.846), Point(-12.56, 1.551), Point(-12.61, 1.427), Point(-13.535, -0.421))
        val edges = points.zipWithNext().map { (a, b) -> SimEdge(a, b, false) }
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        follower.command(Point(-12.75, 1.30), 243.0, 500, 450)
        assertEquals(2, follower.index)
        // Published position still on the first edge (≈ 6 cm), not 45 cm away.
        assertTrue(follower.deviation(Point(-12.4727, 1.9946)) < 0.1)
    }

    @Test fun `slow advance after a pivot as long as no position confirms the heading`() {
        val r = DeadReckoning(commandDelayMs = 600)
        r.fix(Point(0.0, 0.0), 0.0, 0)
        assertFalse(r.pivotUnconfirmed())
        r.sent(100, 0, 450); r.sent(800, 0, 100); r.sent(1000, 300, 20)
        r.fix(Point(0.0, 0.0), 40.0, 1000)
        assertTrue(r.pivotUnconfirmed())
        r.fix(Point(0.0, 0.05), 80.0, 2000)
        assertFalse(r.pivotUnconfirmed())
    }

    /**
     * Survey of 2026-10-10 (14:20, two tests): robot stopped short of the point, resume reduced to a 60-unit command
     * for 200 ms (predicted pose immediately "arrived"), robot stationary, blockage guard never reset during the wait:
     * stop "contact bumper non confirmé".
     */
    @Test fun `approach resume after a short stop without presumed blockage`() {
        // Second test: 14 cm before the point, 7 cm sideways, heading 12° off the stripe.
        for (shortM in listOf(0.14, 0.16, 0.2, 0.3, 0.45)) for (friction in listOf(0, 40, 60, 70)) for ((side, cap) in listOf(0.0 to 0.0, -0.07 to 12.0)) {
            val edge = SimEdge(Point(0.0, 0.0), Point(2.0, 0.0), true)
            val follower = PathFollower(listOf(edge), 0, 1, RangeArrival.TARGET_M)
            val arrival = RangeArrival(DeadReckoning.NAV_COMMAND_DELAY_MS)
            val guard = fr.luba.poc.diagnostics.MotionProgressGuard()
            val reckoning = DeadReckoning(commandDelayMs = DeadReckoning.NAV_COMMAND_DELAY_MS)
            var x = 2.0 - shortM; var y = side; var heading = cap; var v = 0.0; var w = 0.0
            val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat(3) { q.addLast(0 to 0) } }
            var ms = 0L; var fixAt = 0L; var measured = Point(x, y)
            reckoning.fix(measured, heading, 0)
            // Guard already anchored by the approach that has just ended, as on the robot.
            guard.blocked(measured, 0, 0, PathFollower.linearFor(100))
            var result: Boolean? = null
            var approaches = 0
            // Real situation: arrival announced by the predicted pose while the robot is short.
            var announced = true
            while (result != true) {
                check(ms < 120_000) { "approche sans fin (court de $shortM m, frottement $friction)" }
                val pose = reckoning.predict(ms)!!
                val c = follower.command(pose.position, pose.headingDegrees, PathFollower.linearFor(100), 450,
                    reckoning.degreesPerUnit, reckoning.metersPerUnit, RangeArrival.APPROACH_MIN_LINEAR)
                var linear = 0; var angular = 0
                if (c.done || announced) {
                    result = arrival.check(ms, fixAt, measured, follower, edge.b)
                    if (result != null) announced = false
                    if (result == false) { approaches++; continue }
                    guard.reset()
                } else {
                    linear = c.linear; angular = c.angular
                    assertFalse("presumed blockage at %.2f m (short by $shortM m, friction $friction, side $side)".format(measured.distance(edge.b)),
                        guard.blocked(measured, fixAt, ms, linear))
                }
                reckoning.sent(ms, linear, angular)
                pending.addLast(linear to angular); val (l, a) = pending.removeFirst()
                val k = 1 - exp(-0.2 / 0.25)
                v += (max(0, l - friction) * 0.00105 - v) * k; w += (a * 0.11 - w) * k
                heading += w * 0.2
                x += v * 0.2 * cos(Math.toRadians(heading)); y += v * 0.2 * sin(Math.toRadians(heading)); ms += 200
                if (ms % 1000 == 0L) { measured = Point(x, y); fixAt = ms; reckoning.fix(measured, heading, ms) }
            }
            // First stop judged short (> 18 cm): at least one resume, final arrival under 18 cm without reversing.
            if (shortM > RangeArrival.ACCEPT_M) assertTrue("resume expected", approaches >= 1)
            assertTrue("arrival at %.3f m (short by $shortM m, friction $friction, side $side)".format(Point(x, y).distance(edge.b)),
                Point(x, y).distance(edge.b) <= RangeArrival.ACCEPT_M + 0.02 && x <= 2.0 + 0.15)
        }
    }

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

    /**
     * Turn tracking (step C of docs/TRACKING_TURNS_AND_BORDER_CONTACTS.md): on a 0.35 m arc, a robot on the line and
     * along the tangent gets the rotation of the arc at its speed, without a heading error to correct. The 0.25 m chord
     * led the tangent by about 20°: the robot settled inside the arc and overshot at its exit (rolling U-turns of
     * 2026-10-28, 8 cm median, 25 cm at the exit).
     */
    @Test fun `curve feed-forward keeps the tangent reference`() {
        val edges = listOf(SimEdge(Point(-2.0, 0.0), Point(0.0, 0.0), true)) + arcEdges(Point(0.0, 0.0), 0.0, 0.35, 180.0) +
            listOf(SimEdge(Point(0.0, 0.7), Point(-2.0, 0.7), true))
        val follower = PathFollower(edges, 0, edges.size, 0.15)
        // A quarter of the way round the half circle, on the circle, heading along its tangent (45°).
        val p = Point(0.35 * sin(Math.toRadians(45.0)), 0.35 - 0.35 * cos(Math.toRadians(45.0)))
        val dpu = DeadReckoning.DEFAULT_DEGREES_PER_UNIT; val mpu = DeadReckoning.DEFAULT_METERS_PER_UNIT
        val c = follower.command(p, 45.0, 315, PathFollower.angularLimit(63), dpu, mpu)
        assertTrue("$c", c.linear > 0)
        assertFalse(follower.pivoting)
        // Rotation commanded = the arc's at the commanded speed (4° every 2.44 cm), within one 4° vertex of tangent error.
        val kappa = 4.0 / (2 * 0.35 * sin(Math.toRadians(2.0)))
        val feedForward = c.linear * mpu * kappa / dpu
        assertEquals(feedForward, c.angular.toDouble(), 4.0 * PathFollower.STEER_GAIN / dpu)
        // Speed of the arc: [PathFollower.curveSpeed] with the share of the rotation limit given to the curve.
        val expected = PathFollower.curveSpeed(kappa, PathFollower.angularLimit(63), dpu) / mpu
        assertEquals(expected, c.linear.toDouble(), 0.12 * expected)
        // Much faster than the former governor, 0.6 × half of the rotation limit.
        assertTrue(c.linear * mpu > 1.5 * PathFollower.angularLimit(63) / 2 * dpu * 0.6 / kappa)
    }

    /** S-bend: the feed-forward follows the sign of the bend under the robot, it does not cancel over the window. */
    @Test fun `s-bend does not cancel the feed-forward`() {
        val left = arcEdges(Point(0.0, 0.0), 0.0, 0.5, 90.0)
        val right = arcEdges(left.last().b, 90.0, 0.5, -90.0)
        val edges = listOf(SimEdge(Point(-2.0, 0.0), Point(0.0, 0.0), true)) + left + right +
            listOf(SimEdge(right.last().b, Point(right.last().b.x + 2.0, right.last().b.y), true))
        fun at(onLeft: Boolean, degrees: Double): PathFollower.Command {
            val follower = PathFollower(edges, 0, edges.size, 0.15)
            val (p, heading) = if (onLeft) Point(0.5 * sin(Math.toRadians(degrees)), 0.5 - 0.5 * cos(Math.toRadians(degrees))) to degrees
                else Point(1.0 - 0.5 * cos(Math.toRadians(degrees)), 0.5 + 0.5 * sin(Math.toRadians(degrees))) to 90.0 - degrees
            return follower.command(p, heading, 315, PathFollower.angularLimit(63))
        }
        // 10° before the inflection: still turning left; 10° after it: turning right.
        val before = at(true, 80.0); val after = at(false, 10.0)
        assertTrue("$before", before.linear > 0 && before.angular > 0)
        assertTrue("$after", after.linear > 0 && after.angular < 0)
    }

    /** Straight pass: the command is the one of the proportional tracking, bit for bit (chord reference, ±angular / 2). */
    @Test fun `straight pass is unchanged`() {
        val edges = listOf(SimEdge(Point(0.0, 0.0), Point(10.0, 0.0), true), SimEdge(Point(10.0, 0.0), Point(10.0, 10.0), true))
        val dpu = DeadReckoning.DEFAULT_DEGREES_PER_UNIT; val mpu = DeadReckoning.DEFAULT_METERS_PER_UNIT
        for ((lateralM, heading) in listOf(0.0 to 0.0, 0.05 to -3.0, -0.12 to 7.0, 0.2 to 12.0, -0.02 to -20.0)) {
            val follower = PathFollower(edges, 0, edges.size, 0.15)
            val c = follower.command(Point(3.0, lateralM), heading, 315, 351, dpu, mpu)
            val error = -Math.toDegrees(atan(lateralM / max(PathFollower.CONVERGE_M, 315 * mpu * PathFollower.CONVERGE_S))) - heading
            val forward = (315.0 * cos(Math.toRadians(error)).coerceAtLeast(0.0)).roundToInt().coerceIn(PathFollower.MIN_LINEAR, 315)
            assertEquals("lateral $lateralM, heading $heading", forward to (error * PathFollower.STEER_GAIN / dpu).roundToInt().coerceIn(-175, 175),
                c.linear to c.angular)
        }
    }

    /**
     * Parity vectors of the curve tracking, the same in the gateway firmware (test_follow,
     * `test_follower_curve_parity_vectors`): a rolling U of 0.35 m, an S-bend of 0.5 m right after it, then the exit
     * onto a straight line, one follower fed with poses in order. Commands to within one unit (rounding of the
     * trigonometry).
     */
    @Test fun `curve commands match the gateway parity vectors`() {
        val left = arcEdges(Point(0.0, 0.0), 0.0, 0.35, 180.0)
        val right = arcEdges(left.last().b, 180.0, 0.5, -90.0)
        val edges = listOf(SimEdge(Point(-2.0, 0.0), Point(0.0, 0.0), true)) + left + right +
            listOf(SimEdge(right.last().b, Point(right.last().b.x, right.last().b.y + 2.0), true))
        assertEquals(70, edges.size)
        val follower = PathFollower(edges, 0, edges.size, 0.12)
        fun onLeft(deg: Double, r: Double) = Point(r * sin(Math.toRadians(deg)), 0.35 - r * cos(Math.toRadians(deg)))
        fun onRight(deg: Double) = Point(left.last().b.x + 0.5 * cos(Math.toRadians(-90.0 - deg)), left.last().b.y + 0.5 + 0.5 * sin(Math.toRadians(-90.0 - deg)))
        val end = right.last().b
        // Pose, heading, deviation, rotation calibrated; expected linear, angular, followed edge.
        data class Case(val p: Point, val heading: Double, val deviation: Double, val calibrated: Boolean, val linear: Int, val angular: Int, val index: Int)
        val cases = listOf(
            Case(Point(-0.5, 0.02), 2.0, 0.0, true, 314, -47, 0),
            Case(Point(-0.1, -0.03), 0.0, 0.0, true, 131, 67, 2),
            Case(onLeft(10.0, 0.33), 13.0, 0.0, true, 131, 152, 9),
            Case(onLeft(45.0, 0.33), 48.0, 0.0, true, 131, 141, 18),
            Case(onLeft(90.0, 0.39), 93.0, 0.0, true, 125, 189, 29),
            Case(onLeft(135.0, 0.33), 138.0, 0.13, true, 124, 152, 41),
            Case(onLeft(170.0, 0.33), 173.0, 0.0, false, 87, 7, 48),
            Case(onRight(5.0), 175.0, 0.0, true, 131, -122, 51),
            Case(onRight(45.0), 135.0, 0.0, true, 166, -186, 61),
            Case(onRight(85.0), 95.0, 0.0, true, 178, -83, 69),
            Case(Point(end.x + 0.01, end.y + 0.3), 92.0, 0.0, true, 185, -18, 69),
            Case(Point(end.x + 0.01, end.y + 0.6), 92.0, 0.0, true, 263, -18, 69),
            Case(Point(end.x + 0.01, end.y + 1.2), 92.0, 0.0, true, 315, -18, 69))
        for (c in cases) {
            val command = follower.command(c.p, c.heading, 315, 351, 0.11, 0.00105, 80, c.deviation, c.calibrated)
            assertEquals("$c: $command", c.linear.toDouble(), command.linear.toDouble(), 1.0)
            assertEquals("$c: $command", c.angular.toDouble(), command.angular.toDouble(), 1.0)
            assertEquals("$c", c.index, follower.index)
        }
        assertEquals(0.134146, PathFollower.curveSpeed(164.0, 351, 0.12), 1e-6)
        assertEquals(0.093902, PathFollower.curveSpeed(164.0, 450, 0.12, rotationCalibrated = false), 1e-6)
        assertEquals(0.21, PathFollower.exitLimit(0.55, 0.12, 0.3), 1e-9)
    }
}
