package fr.luba.poc.ble

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.map.Geometry
import fr.luba.poc.map.MapCache
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.MapStation
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.planning.BoundaryMeaning
import fr.luba.poc.planning.PlanningConfig
import fr.luba.poc.planning.PlanningNavigation
import fr.luba.poc.planning.PlanningRequest
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.BaseStore
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimConfig
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * End-to-end test bench for [RobotBle]: real ViewModel, [SimRobot] robot plugged in via [RobotBle.linkFactory],
 * virtual time of the Robolectric main looper. Work RobotBle hands to `Dispatchers.Default` / `IO`
 * (links, route repair, disk writes) runs in real time while virtual time advances.
 */
class RobotBench(start: Point = Point(5.0, 4.0), heading: Double = 0.0, val name: String = "Luba-VS-SIM01") {
    val app: Application = ApplicationProvider.getApplicationContext()
    val sim = SimRobot(start, heading)
    private val store = ViewModelStore()
    val ble: RobotBle = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[RobotBle::class.java]
    val links = mutableListOf<SimLink>()
    /** False: opened links never become ready (robot off, out of range). */
    var reachable = true
    val device = SeenRobot(name, "AA:BB:CC:00:00:01", -48)
    private val logQueue: java.util.concurrent.BlockingQueue<Runnable>? = runCatching {
        val log = RobotBle::class.java.getDeclaredField("log").apply { isAccessible = true }.get(ble)
        (log.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(log) as ThreadPoolExecutor).queue
    }.getOrNull()

    init {
        ble.linkFactory = { _, listener -> SimLink(sim, listener).also { links += it; it.open(reachable) } }
    }

    val link: SimLink get() = links.last()
    val state: RobotUi get() = ble.state.value
    val now: Long get() = SystemClock.elapsedRealtime()

    /** Advances virtual time in 100 ms steps; the log (real writer thread) is never overrun. */
    fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            awaitBackground()
            val step = minOf(100L, left)
            shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            drainLog()
            left -= step
        }
    }

    private var workers: List<Thread> = emptyList()
    private var workersAt = 0L

    /**
     * Work RobotBle hands to `Dispatchers.Default` (route repair, links) runs in real time.
     * Virtual time does not advance while a coroutine worker thread is computing (at most 5 real seconds per step).
     */
    private fun awaitBackground() {
        val wall = System.currentTimeMillis()
        if (wall - workersAt > 500) { workersAt = wall; workers = Thread.getAllStackTraces().keys.filter { it.name.startsWith("DefaultDispatcher-worker") } }
        var waited = 0
        fun computing(t: Thread) = t.state == Thread.State.RUNNABLE && t.stackTrace.any { f ->
            f.className.startsWith("fr.luba.poc.simulation") || f.className.startsWith("fr.luba.poc.planning") || f.className.startsWith("org.locationtech") }
        while (workers.any(::computing) && waited++ < 2500) Thread.sleep(2)
    }

    private fun drainLog() {
        val queue = logQueue ?: return
        var waited = 0
        while (queue.size > 512 && waited++ < 2000) Thread.sleep(1)
    }

    /** Advances until [condition] (at most [maxMs] virtual), otherwise fails with the current state. */
    fun until(maxMs: Long, what: String, condition: () -> Boolean) {
        val deadline = now + maxMs
        while (!condition()) {
            if (now >= deadline) fail("$what: not reached within ${maxMs / 1000} s\n${dump()}")
            advance(100)
        }
    }

    /** Waits for [condition] in real time (work handed to background threads: alerts, disk), with virtual time advancing little. */
    fun awaitReal(maxRealMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + maxRealMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) fail("$what: not observed within ${maxRealMs} real ms\n${dump()}")
            advance(20); Thread.sleep(5)
        }
    }

    /** Project rule: the official Mammotion return is only requested once the robot has stopped at the approach point in front of the base. */
    fun assertNativeDockOnlyAtApproach(engine: MowingSimulation) {
        org.junit.Assert.assertTrue("Official return requested", sim.nativeReturnRequests.isNotEmpty())
        sim.nativeReturnRequests.forEach { at ->
            org.junit.Assert.assertTrue("Native docking requested ${"%.2f".format(at.distance(engine.baseApproach))} m from the approach point",
                at.distance(engine.baseApproach) <= 0.25)
        }
        // No other native navigation command (mowing, leaving the base…).
        val native = sim.trace.map { it.second }.filter { it.startsWith("task ") }
        org.junit.Assert.assertTrue(native.toString(), native.isEmpty())
    }

    fun alerted(kind: fr.luba.poc.alerts.AlertKind) = ble.localAlerts.value.entries.any { it.kind == kind }

    fun dump(): String = "planned=${state.robotRoute.planned.take(8).map { "(%.2f,%.2f)".format(it.x, it.y) }}\n" +
        "track=${sim.track.takeLast(60).filterIndexed { i, _ -> i % 5 == 0 }.map { "(%.2f,%.2f)".format(it.x, it.y) }}\nstatus=${state.status}\nroute=${state.robotRoute.status} phase=${state.robotRoute.phase} running=${state.robotRoute.running}\n" +
        "connection=${state.connection} ready=${state.ready} manual=${state.manualStatus}\nnav=${state.navTest.status}\n" +
        "robot=${sim.position} heading=${"%.0f".format(sim.heading)} state=${sim.robotState} battery=${"%.1f".format(sim.battery)}\n" +
        "robot commands=${sim.trace.takeLast(12).map { it.second }}\nevents=\n" + state.events.takeLast(40).joinToString("\n")

    /** Connects until the first telemetry with a position. */
    fun connect() {
        ble.connect(device)
        until(10_000, "telemetry received") { state.connection == ConnectionState.Receiving && state.telemetry.x != null && state.telemetry.robotState != null }
        // Robot charging: the map's station is replaced by the observed position and axis ([fr.luba.poc.map.StationReader]).
        if (sim.charging) advance(2500)
    }

    fun close() {
        sim.shutdown()
        store.clear()
        advance(200)
    }

    // ───── Synthetic map: one rectangular zone, oriented base ─────

    companion object {
        const val ZONE_ID = 1L
        /** 8 m × 6 m zone; base at (1.5, 3.0), docking towards −x: approach point at (3.0, 3.0). */
        val ZONE = MapShape(ZONE_ID, 0, listOf(Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 6.0), Point(0.0, 6.0)), "Pelouse")
        val BASE = Point(1.5, 3.0)
        const val BASE_ENTRY = 180.0
        /** Heading of the docked robot: nose towards the approach point. */
        const val DOCKED_HEADING = 0.0
        val MAP = RobotMap(listOf(ZONE), true, "Banc d'essai", MapStation(BASE, BASE_ENTRY, "Transmise par le robot"))
    }

    /** Robot map saved in the local cache before connecting (as after a BLE read). */
    fun installMap(map: RobotMap = MAP) {
        MapCache(app).save(device.address, map)
        if (sim.dock == null) { sim.dock = BASE; sim.dockedHeading = DOCKED_HEADING }
    }

    val base: MapBase get() = requireNotNull(BaseStore(app, state.robotMap).resolve(state.robotMap)) { "Base missing from the map" }

    /**
     * Zone mowing route prepared as the map does (same planning building block as the simulation):
     * spacing [spacingM], border included, oriented base.
     */
    fun mowingEngine(spacingM: Double = 0.38, obstacles: List<LocalObstacle> = emptyList(), config: SimConfig? = null, zone: MapShape = ZONE): MowingSimulation {
        val map = state.robotMap
        check(map.complete) { "Robot map missing: ${map.source} · cache=${MapCache(app).read(device.address)?.source} · dir=${app.filesDir}" }
        val base = base
        val routes = RoutePlanning.mission(map, obstacles, emptyList(), base, recordedBoundary = true)
        val planning = PlanningConfig(spacingM = spacingM, marginM = 0.6, cutWidthM = Geometry.CUT_WIDTH_M, minimumCoverage = 0.8,
            borders = true, borderPasses = 1, noGoPasses = 1, bordersFirst = true, nearbyNoGo = true, boundary = BoundaryMeaning.RECORDED_TRACE)
        val plan = RoutePlanning.coverage(PlanningRequest(map, listOf(zone.id), base.point, config = planning,
            obstacles = fr.luba.poc.planning.DockClearance.planning(obstacles, base), navigation = PlanningNavigation(emptyList(), base)))
        check(!plan.preview.localOnly) { "Plan not validated: ${plan.report?.selected?.diagnostics}" }
        return MowingSimulation(routes, base.point, plan.preview, map, config ?: SimConfig(instantCharging = true,
            baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
    }

    /**
     * Resume route re-prepared as the app does ([RoutePlanning.resume]): saved preview, obstacles and base from the start,
     * current map and base.
     */
    fun resumeEngine(): MowingSimulation {
        val checkpoint = requireNotNull(state.missionCheckpoint) { "No task to resume" }
        val base = base
        return RoutePlanning.resume(checkpoint, state.robotMap, base, emptyList(), state.obstacles, state.difficulties,
            SimConfig(instantCharging = true, baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM))
    }

    /** Robot docked, connected, mowing started: returns once the blades are running and the robot is moving. */
    fun mowingUnderway(cutting: Boolean = true, battery: Double = 80.0): MowingSimulation {
        sim.placeDocked(BASE, DOCKED_HEADING)
        sim.battery = battery
        installMap()
        connect()
        val engine = mowingEngine()
        org.junit.Assert.assertTrue(dump(), ble.startRobotRoute(engine, cutting, 60))
        until(90_000, "cutting in progress") { (!cutting || sim.bladesOn) && sim.speed > 0.05 && !sim.charging }
        advance(5_000)
        return engine
    }

    /** Command labels received by the robot since [since] (virtual time). */
    fun labelsSince(since: Long) = sim.labelsSince(since)
    /** Marker in the list of received commands: [labelsAfter] returns those received afterwards. */
    fun mark(): Int = sim.trace.size
    fun labelsAfter(mark: Int) = sim.trace.drop(mark).map { it.second }

    /** Station route ("Recharge" button) from the robot's position. */
    fun stationEngine(): MowingSimulation {
        val map = state.robotMap
        val base = base
        val t = state.telemetry
        val config = SimConfig(instantCharging = true, baseEntryHeadingDegrees = base.entryHeadingDegrees, baseApproachMeters = base.approachDistanceM)
        return RoutePlanning.station(RoutePlanning.mission(map, emptyList(), emptyList(), base), map, base, Point(t.x!!, t.y!!), config)
    }
}
