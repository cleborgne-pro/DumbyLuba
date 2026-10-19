package fr.luba.poc.planning

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import fr.luba.poc.diagnostics.MapNavFollower
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.Blufi
import fr.luba.poc.protocol.Commands
import fr.luba.poc.protocol.TelemetryReducer
import fr.luba.poc.simulation.*
import org.json.JSONObject

/**
 * "Autonomous gateway" bench: the Fold generated the route (all zones, `follower-reference-route.json`), the measured
 * phone does the rest, exactly the computations of RobotBle's tracking loop on every 200 ms tick: decoding of real
 * telemetry (BluFi frames of 2026-10-11), pose correction and prediction, follower command, route deviation, envelope
 * check against the navigation geometry, BluFi encoding of the setpoint.
 * The simulated robot follows the route perfectly (fixed step along the ranges): only the computing cost is measured.
 * Aucune commande robot, aucun ViewModel ni service.
 */
class FollowerDeviceBenchmark(private val context: Context, private val stream: (String) -> Unit) {
    private class Stats {
        val wall = ArrayList<Long>(); val cpu = ArrayList<Long>()
        fun add(w: Long, c: Long) { wall += w; cpu += c }
        private fun pct(v: List<Long>, p: Double) = v.sorted().let { s -> if (s.isEmpty()) 0.0 else s[((s.size - 1) * p).toInt()] / 1e6 }
        fun json() = JSONObject().put("ticks", wall.size)
            .put("wallP50Ms", pct(wall, .5)).put("wallP95Ms", pct(wall, .95)).put("wallP99Ms", pct(wall, .99)).put("wallMaxMs", pct(wall, 1.0))
            .put("cpuMeanMs", if (cpu.isEmpty()) 0.0 else cpu.average() / 1e6).put("cpuP99Ms", pct(cpu, .99))
            .put("wallOver20Ms", wall.count { it > 20_000_000 }).put("wallOver100Ms", wall.count { it > 100_000_000 })
    }

    /**
     * "Bypass on the gateway" bench: obstacle met in the middle of [samples] cutting passes spread over the whole
     * route, bumper contact (back-off along the travelled trail, then link), remaining route repaired by
     * [ObstacleBypass.repair] — the real-mowing function, on the geometry built by the phone.
     */
    fun runBypass(samples: Int): JSONObject {
        val row = JSONObject().put("mode", "bypass").put("device", Build.MODEL).put("androidSdk", Build.VERSION.SDK_INT)
            .put("maxHeapMb", Runtime.getRuntime().maxMemory() / 1048576)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
        val mapJson = JSONObject(asset("planner-reference-map.json"))
        val shapes = mapJson.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        val map = RobotMap(shapes, true, "reference-station")
        val json = JSONObject(asset("follower-reference-route.json"))
        val e = json.getJSONArray("edges")
        val route = (0 until e.length()).map { i -> e.getJSONArray(i).let { v ->
            SimEdge(Point(v.getDouble(0), v.getDouble(1)), Point(v.getDouble(2), v.getDouble(3)), v.getInt(4) == 1, v.getInt(6) == 1, v.getInt(5) == 1) } }
        val b = json.getJSONArray("base")
        val base = MapBase(Point(b.getDouble(0), b.getDouble(1)), "Reference station", MapBase.DEFAULT_INFLUENCE_RADIUS_M, b.getDouble(2), b.getDouble(3))
        val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        val t0 = SystemClock.elapsedRealtime()
        val routes = RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05, noGo, emptyList(), base = base, recordedBoundary = true)
        val physical = RoutePlanning.physical(map, emptyList(), recordedBoundary = true)
        row.put("geometryBuildMs", SystemClock.elapsedRealtime() - t0).put("edges", route.size)
        val zones = shapes.filter { it.kind == ShapeKind.ZONE }.map { it.points }
        fun heading(edge: SimEdge) = Math.toDegrees(kotlin.math.atan2(edge.b.y - edge.a.y, edge.b.x - edge.a.x))
        // Cutting passes of at least 2 m, evenly spread over the whole mission.
        val candidates = route.indices.filter { route[it].cutting && route[it].meters >= 2.0 }
        val picked = (0 until samples).map { k -> candidates[(k * (candidates.size - 1)) / maxOf(1, samples - 1)] }.distinct()
        val wall = ArrayList<Long>(); val cpu = ArrayList<Long>()
        val cases = org.json.JSONArray()
        for (index in picked) {
            val edge = route[index]
            val contact = Point((edge.a.x + edge.b.x) / 2, (edge.a.y + edge.b.y) / 2)
            // Travelled trail: the first half of the pass, one point every 3 cm (like the tracking trail).
            val trail = (0..((edge.meters / 2) / 0.03).toInt()).map { i ->
                val f = (i * 0.03 / edge.meters).coerceAtMost(0.5); Point(edge.a.x + (edge.b.x - edge.a.x) * f, edge.a.y + (edge.b.y - edge.a.y) * f) }
            val obstacle = ObstacleBypass.contactObstacle("bench-$index", contact, heading(edge), 0L)
            Runtime.getRuntime().gc()
            val w0 = SystemClock.elapsedRealtimeNanos(); val c0 = Debug.threadCpuTimeNanos()
            val outcome = runCatching { ObstacleBypass.repair(routes, physical, route, null, index, contact, heading(edge), listOf(obstacle), zones, true, trail) }
            val w = SystemClock.elapsedRealtimeNanos() - w0; val c = Debug.threadCpuTimeNanos() - c0
            wall += w; cpu += c
            val case = JSONObject().put("edge", index).put("wallMs", w / 1e6).put("cpuMs", c / 1e6).put("ok", outcome.isSuccess)
            outcome.onSuccess { r -> case.put("bypasses", r.bypasses).put("bypassM", r.bypassMeters).put("skippedCuttingM", r.skippedCuttingMeters).put("edgesAfter", r.edges.size) }
                .onFailure { case.put("error", it.message) }
            cases.put(case)
            stream("CASE $case")
        }
        fun pct(v: List<Long>, p: Double) = v.sorted().let { s -> if (s.isEmpty()) 0.0 else s[((s.size - 1) * p).toInt()] / 1e6 }
        row.put("samples", picked.size).put("ok", (0 until cases.length()).count { cases.getJSONObject(it).getBoolean("ok") })
            .put("wallP50Ms", pct(wall, .5)).put("wallMaxMs", pct(wall, 1.0)).put("cpuMeanMs", if (cpu.isEmpty()) 0.0 else cpu.average() / 1e6)
            .put("cases", cases)
        stream("RESULT $row")
        return row
    }

    fun run(stepM: Double): JSONObject {
        val row = JSONObject().put("device", Build.MODEL).put("androidSdk", Build.VERSION.SDK_INT)
            .put("abi", Build.SUPPORTED_ABIS.joinToString()).put("cores", Runtime.getRuntime().availableProcessors())
            .put("maxHeapMb", Runtime.getRuntime().maxMemory() / 1048576)
            .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        fun <T> measured(key: String, block: () -> T): T {
            val wall = SystemClock.elapsedRealtimeNanos(); val cpu = Debug.threadCpuTimeNanos()
            try { return block() } finally {
                row.put(key + "WallMs", (SystemClock.elapsedRealtimeNanos() - wall) / 1e6).put(key + "CpuMs", (Debug.threadCpuTimeNanos() - cpu) / 1e6)
            }
        }
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

        // 1. Reception of the route sent by the Fold (JSON) and the map.
        val mapJson = JSONObject(asset("planner-reference-map.json"))
        val shapes = mapJson.getJSONArray("shapes").let { a -> (0 until a.length()).map { i ->
            val s = a.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { j ->
                Point(p.getJSONArray(j).getDouble(0), p.getJSONArray(j).getDouble(1)) }, s.getString("name"))
        } }
        val map = RobotMap(shapes, true, "reference-station")
        val routeText = asset("follower-reference-route.json")
        row.put("routeJsonKb", routeText.length / 1024)
        val (route, base) = measured("routeDecode") {
            val json = JSONObject(routeText)
            val e = json.getJSONArray("edges")
            val edges = (0 until e.length()).map { i -> e.getJSONArray(i).let { v ->
                SimEdge(Point(v.getDouble(0), v.getDouble(1)), Point(v.getDouble(2), v.getDouble(3)), v.getInt(4) == 1, v.getInt(6) == 1, v.getInt(5) == 1) } }
            val b = json.getJSONArray("base")
            edges to MapBase(Point(b.getDouble(0), b.getDouble(1)), "Reference station", MapBase.DEFAULT_INFLUENCE_RADIUS_M, b.getDouble(2), b.getDouble(3))
        }
        row.put("edges", route.size).put("routeMeters", route.sumOf { it.meters }).put("zones", JSONObject(routeText).getJSONArray("zones"))

        // 2. Geometry needed by the on-board checks (safe corridor): built by the tracking phone.
        Runtime.getRuntime().gc()
        val heapBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val routes = measured("navigationBuild") { RoutePlanning.navigation(map, RobotEnvelope.LUBA2.halfWidthM + 0.05,
            shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, emptyList(), base = base, recordedBoundary = true) }
        measured("physicalBuild") { RoutePlanning.physical(map, emptyList(), recordedBoundary = true) }
        Runtime.getRuntime().gc()
        row.put("geometryHeapMb", (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } - heapBefore) / 1048576.0)

        // 3. Tracking loop, range by range (same split as RobotBle: blade state change).
        val frames = asset("follower-telemetry-frames.txt").lines().filter { it.isNotBlank() }.map { hex ->
            ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }
        var incoming = Blufi(); val outgoing = Blufi(); val reducer = TelemetryReducer(); val commands = Commands()
        val reckoning = DeadReckoning(commandDelayMs = DeadReckoning.ROUTE_COMMAND_DELAY_MS)
        val speedPercent = 30
        val tick = Stats(); val telemetryStats = Stats(); val controlStats = Stats(); val envelopeStats = Stats()
        var frameIndex = 0; var decoded = 0; var collisions = 0; var deviations = 0
        var clock = 0L
        val began = SystemClock.elapsedRealtime()
        // stepM ≤ 0: closed loop, kinematic robot executing the follower setpoints (default gains).
        val kinematic = stepM <= 0
        var px = route[0].a.x; var py = route[0].a.y
        var ph = Math.toDegrees(kotlin.math.atan2(route[0].b.y - route[0].a.y, route[0].b.x - route[0].a.x))
        var stalls = 0; var rangeCount = 0; var maxDeviation = 0.0
        var index = 0
        while (index < route.size) {
            val blades = route[index].bladesOn
            var to = index + 1
            while (to < route.size && route[to].bladesOn == blades) to++
            val follower = PathFollower(route, index, to, RangeArrival.TARGET_M)
            // Simulated robot: moves [stepM] along the range.
            var edge = index; var along = 0.0; var doneTicks = 0
            rangeCount++
            val tickBudget = (route.subList(index, to).sumOf { it.meters } / 0.01).toInt() + 1500; var rangeTicks = 0
            while (true) {
                clock += 200
                val wall0 = SystemClock.elapsedRealtimeNanos(); val cpu0 = Debug.threadCpuTimeNanos()
                // Telemetry: three frames per tick (5 Hz reports, fragmented), as received from the robot.
                repeat(3) {
                    val frame = frames[frameIndex]; frameIndex = (frameIndex + 1) % frames.size
                    if (frameIndex == 0) incoming = Blufi()
                    val payload = runCatching { incoming.receive(frame) }.getOrElse { incoming = Blufi(); null }
                    if (payload != null && payload.firstOrNull() != '{'.code.toByte())
                        runCatching { LubaMsg.parseFrom(payload) }.getOrNull()?.let { reducer.accept(it, clock, clock); decoded++ }
                }
                val wall1 = SystemClock.elapsedRealtimeNanos(); val cpu1 = Debug.threadCpuTimeNanos()
                val current = route[edge]
                val heading = if (kinematic) ph else Math.toDegrees(kotlin.math.atan2(current.b.y - current.a.y, current.b.x - current.a.x))
                val position = if (kinematic) Point(px, py) else Point(current.a.x + (current.b.x - current.a.x) * (along / current.meters.coerceAtLeast(1e-9)),
                    current.a.y + (current.b.y - current.a.y) * (along / current.meters.coerceAtLeast(1e-9)))
                if (clock % 1000 == 0L) reckoning.fix(position, heading, clock)
                val corrected = reckoning.heading(heading)
                val predicted = reckoning.predict(clock) ?: RobotPose(position, corrected)
                val linearLimit = if (!reckoning.headingConfirmed) minOf(PathFollower.linearFor(speedPercent), DeadReckoning.UNCONFIRMED_LINEAR)
                    else reckoning.postPivotLinear(PathFollower.linearFor(speedPercent))
                val deviation = follower.deviation(position)
                val command = follower.command(predicted.position, predicted.headingDegrees, linearLimit, PathFollower.angularLimit(speedPercent),
                    reckoning.degreesPerUnit, reckoning.metersPerUnit, RangeArrival.APPROACH_MIN_LINEAR, deviation, reckoning.rotationCalibrated)
                follower.deviation(position).let { d -> if (d > MapNavFollower.MAX_DEVIATION_M) deviations++; maxDeviation = maxOf(maxDeviation, d) }
                val wall2 = SystemClock.elapsedRealtimeNanos(); val cpu2 = Debug.threadCpuTimeNanos()
                val linear = command.linear; val angular = command.angular
                val rad = Math.toRadians(corrected)
                val reach = minOf(maxOf(0.25, linear * reckoning.metersPerUnit * 1.5), command.stopDistanceM)
                val next = if (linear == 0) RobotPose(position, corrected + if (angular > 0) 6.0 else -6.0)
                    else RobotPose(Point(position.x + reach * kotlin.math.cos(rad), position.y + reach * kotlin.math.sin(rad)), corrected)
                if (routes.motionCollision(RobotPose(position, corrected), next) != null) collisions++
                val wall3 = SystemClock.elapsedRealtimeNanos(); val cpu3 = Debug.threadCpuTimeNanos()
                outgoing.encode(commands.movement(linear.coerceIn(-Commands.PROTOCOL_MAX_LINEAR, Commands.PROTOCOL_MAX_LINEAR), angular.coerceIn(-Commands.PROTOCOL_MAX_ANGULAR, Commands.PROTOCOL_MAX_ANGULAR), clock), 512)
                reckoning.sent(clock, linear, angular)
                val wall4 = SystemClock.elapsedRealtimeNanos(); val cpu4 = Debug.threadCpuTimeNanos()
                tick.add(wall4 - wall0, cpu4 - cpu0); telemetryStats.add(wall1 - wall0, cpu1 - cpu0)
                controlStats.add(wall2 - wall1 + wall4 - wall3, cpu2 - cpu1 + cpu4 - cpu3); envelopeStats.add(wall3 - wall2, cpu3 - cpu2)
                // Simulated robot moves forward.
                if (kinematic) {
                    if (command.done) break
                    if (++rangeTicks > tickBudget) {
                        stalls++; px = route[to - 1].b.x; py = route[to - 1].b.y
                        ph = Math.toDegrees(kotlin.math.atan2(route[to - 1].b.y - route[to - 1].a.y, route[to - 1].b.x - route[to - 1].a.x)); break
                    }
                    ph += angular * reckoning.degreesPerUnit * 0.2
                    val r = Math.toRadians(ph)
                    px += kotlin.math.cos(r) * linear * reckoning.metersPerUnit * 0.2; py += kotlin.math.sin(r) * linear * reckoning.metersPerUnit * 0.2
                } else if (edge >= to - 1 && along >= route[edge].meters) {
                    if (command.done || ++doneTicks > 10) break
                } else {
                    along += stepM
                    while (edge < to && along > route[edge].meters) {
                        if (edge == to - 1) { along = route[edge].meters; break }
                        along -= route[edge].meters; edge++
                    }
                }
            }
            index = to
        }
        val elapsed = SystemClock.elapsedRealtime() - began
        row.put("stepM", stepM).put("tick", tick.json()).put("telemetry", telemetryStats.json()).put("control", controlStats.json())
            .put("envelope", envelopeStats.json()).put("messagesDecoded", decoded).put("collisionFlags", collisions)
            .put("deviationFlags", deviations).put("maxDeviationM", maxDeviation).put("ranges", rangeCount).put("stalls", stalls)
            .put("simulatedHours", clock / 3_600_000.0).put("loopWallMs", elapsed)
            // Budget: one 200 ms tick; share of one core used by tracking at 5 Hz.
            .put("cpuShareOfOneCorePercent", tick.cpu.average() / 1e6 / 200.0 * 100)
        stream("RESULT $row")
        return row
    }
}
