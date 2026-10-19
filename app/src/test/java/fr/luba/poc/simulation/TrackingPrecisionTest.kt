package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/**
 * Precision of actual mowing tracking: same loop as [fr.luba.poc.ble.RobotBle] (one range per blade state, STOP
 * between two ranges, command at 5 Hz on the predicted pose, deviation checked on the published
 * position).
 *
 * Robot modelled after the debug mowing of 2026-10-10 (14:55, painted zone): command → effect delay
 * ≈ 0.6 s (heading adjustment, in pivot as in forward motion), 0.11 °/s and 1.08 mm/s per unit,
 * positions published at 1 Hz with RTK noise, inertia and friction. On this route, the tracking without
 * delay compensation oscillated by ±10–20 cm on straight stripes and pivoted in the middle of stripes.
 */
class TrackingPrecisionTest {
    data class Robot(val delayMs: Long = 600, val degPerUnit: Double = 0.11, val metersPerUnit: Double = 0.00108,
                     val tauS: Double = 0.25, val friction: Int = 30, val noiseM: Double = 0.01, val headingNoise: Double = 1.0,
                     val headingError: Double = 0.0, val seed: Long = 1)

    data class Run(val worst: Double, val rmsStraight: Double, val zigzags: Int, val midPivots: Int, val seconds: Double,
                   val endErrors: List<Double>, val meters: Double, val where: String = "")

    private fun routeEdges(): List<SimEdge> {
        val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/painted-route-2026-10-10.json")).bufferedReader().use { it.readText() })
        val array = json.getJSONArray("edges")
        return (0 until array.length()).map { i -> val e = array.getJSONArray(i)
            SimEdge(Point(e.getDouble(0), e.getDouble(1)), Point(e.getDouble(2), e.getDouble(3)), e.getInt(4) == 1) }
    }

    /** Signed distance from [p] to the line of [e]. */
    private fun lateral(p: Point, e: SimEdge): Double {
        val dx = e.b.x - e.a.x; val dy = e.b.y - e.a.y; val l = hypot(dx, dy)
        return ((p.x - e.a.x) * dy - (p.y - e.a.y) * dx) / l
    }

    /** Follows [edges] like actual mowing; [commandDelayMs]: delay compensation in the predicted pose. */
    fun drive(edges: List<SimEdge>, robot: Robot, speedPercent: Int = 50, commandDelayMs: Long = DeadReckoning.ROUTE_COMMAND_DELAY_MS): Run {
        val random = Random(robot.seed)
        var x = edges.first().a.x; var y = edges.first().a.y
        var heading = PathFollower.headingOf(edges.first()) + robot.headingError
        var v = 0.0; var w = 0.0
        val delaySteps = (robot.delayMs / 200).toInt()
        val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat(delaySteps) { q.addLast(0 to 0) } }
        val reckoning = DeadReckoning(commandDelayMs = commandDelayMs)
        var ms = 0L; var fixAt = 0L
        fun publish(): Point = Point(x + random.nextGaussian() * robot.noiseM, y + random.nextGaussian() * robot.noiseM)
        var measured = publish()
        var worst = 0.0; var midPivots = 0; var where = ""
        val endErrors = mutableListOf<Double>()
        // Lateral deviation (true position) on the long edges, excluding 0.6 m after a vertex.
        val straight = mutableListOf<Double>()
        val signs = mutableListOf<Int>()
        fun step(command: Pair<Int, Int>) {
            pending.addLast(command); val (l, a) = pending.removeFirst()
            val k = 1 - exp(-0.2 / robot.tauS)
            val effective = sign(l.toDouble()) * max(0, abs(l) - robot.friction)
            v += (effective * robot.metersPerUnit - v) * k; w += (a * robot.degPerUnit - w) * k
            heading += w * 0.2
            x += v * 0.2 * cos(Math.toRadians(heading)); y += v * 0.2 * sin(Math.toRadians(heading))
            ms += 200
            if (ms % 1000 == 0L) { measured = publish(); fixAt = ms; reckoning.fix(measured, heading + random.nextGaussian() * robot.headingNoise, ms) }
            check(ms < 3_600_000) { "tracking blocked" }
        }
        reckoning.fix(measured, heading, 0)
        var from = 0
        while (from < edges.size) {
            var to = from + 1
            while (to < edges.size && edges[to].cutting == edges[from].cutting) to++
            reckoning.sent(ms, 0, 0); step(0 to 0)
            val follower = PathFollower(edges, from, to, RangeArrival.TARGET_M)
            val arrival = RangeArrival(commandDelayMs)
            var turning = false
            while (true) {
                follower.deviation(measured).let { d -> if (d > worst) { worst = d
                    val e = edges[follower.index]
                    where = "edge ${follower.index} (%.2f m, blades %s) at %.2f m, range $from-$to".format(e.meters, e.cutting, PathFollower.progressOf(measured, e) * e.meters) } }
                val edge = edges[follower.index]
                val along = PathFollower.progressOf(Point(x, y), edge) * edge.meters
                if (edge.meters > 1.5 && along > 0.6 && along < edge.meters - 0.3) {
                    val lat = lateral(Point(x, y), edge)
                    straight += lat
                    if (abs(lat) > 0.05) { val s = sign(lat).toInt(); if (signs.lastOrNull() != s) signs += s }
                }
                val pose = reckoning.predict(ms)!!
                val limit = if (reckoning.pivotUnconfirmed()) DeadReckoning.POST_PIVOT_LINEAR else PathFollower.linearFor(speedPercent)
                val c = follower.command(pose.position, pose.headingDegrees, limit, PathFollower.angularLimit(speedPercent),
                    reckoning.degreesPerUnit, reckoning.metersPerUnit, RangeArrival.APPROACH_MIN_LINEAR)
                if (c.done) {
                    when (arrival.check(ms, fixAt, measured, follower, edges[to - 1].b)) {
                        null -> { reckoning.sent(ms, 0, 0); step(0 to 0); continue }
                        false -> continue
                        true -> break
                    }
                }
                val pivot = c.linear == 0 && c.angular != 0
                if (pivot && !turning) {
                    val e = edges[follower.index]
                    val a = PathFollower.progressOf(pose.position, e) * e.meters
                    // Pivot far from both vertices of the edge: zigzag corrected in place.
                    if (a > 0.4 && a < e.meters - 0.4) midPivots++
                }
                turning = pivot
                reckoning.sent(ms, c.linear, c.angular); step(c.linear to c.angular)
            }
            endErrors += Point(x, y).distance(edges[to - 1].b)
            from = to
        }
        val rms = if (straight.isEmpty()) 0.0 else sqrt(straight.sumOf { it * it } / straight.size)
        return Run(worst, rms, max(0, signs.size - 1), midPivots, ms / 1000.0, endErrors, edges.sumOf { it.meters }, where)
    }

    private fun report(label: String, run: Run) = println("TRACKING " + label + ": " + "max deviation %.3f m, stripes RMS %.3f m, %d reversals, %d pivots in stripe, %.0f s (%.3f m/s), max ends %.2f m, worst %s"
        .format(run.worst, run.rmsStraight, run.zigzags, run.midPivots, run.seconds, run.meters / run.seconds, run.endErrors.max(), run.where))

    /**
     * Actual route of 2026-10-10 replayed with delays of 0.4 to 0.8 s (+ inertia 0.25 s), rotation ±20 %, initial
     * heading wrong by 15°, 30 to 100 % speed. Survey on the robot with the old tracking: deviation oscillating by
     * ±10–20 cm on the stripes, pivots in the middle of stripes, 0.12 m/s on average.
     */
    @Test(timeout = 600000) fun `painted zone mowing of 2026-10-10 in straight lines without zigzag or pivot in stripe`() {
        val edges = routeEdges()
        val failures = mutableListOf<String>()
        for (delay in listOf(400L, 600L, 800L)) for (degs in listOf(0.09, 0.11, 0.13)) for (percent in listOf(30, 50, 100)) for (seed in 1L..2L) {
            val robot = Robot(delayMs = delay, degPerUnit = degs, seed = seed, headingError = if (seed == 2L) 15.0 else 0.0)
            val run = drive(edges, robot, percent)
            report("retard $delay ms, $degs °/s·u, $percent %, graine $seed", run)
            val fast = percent == 100
            if (run.worst > (if (fast) 0.22 else 0.18) || run.rmsStraight > (if (fast) 0.075 else 0.025) || run.midPivots > 0 ||
                run.endErrors.any { it > 0.15 } || run.meters / run.seconds < (if (fast) 0.2 else if (percent == 50) 0.14 else 0.095))
                failures += "retard $delay ms, $degs °/s·u, $percent %, graine $seed : $run"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test(timeout = 600000) fun `delay compensation removes the zigzag at 50 percent`() {
        val compensated = drive(routeEdges(), Robot(), 50)
        val legacy = drive(routeEdges(), Robot(), 50, commandDelayMs = 0)
        report("compensated", compensated); report("without compensation", legacy)
        assertTrue("RMS ${compensated.rmsStraight} versus ${legacy.rmsStraight}", compensated.rmsStraight < 0.6 * legacy.rmsStraight)
        assertTrue(compensated.zigzags < legacy.zigzags)
    }
}
