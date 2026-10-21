package fr.luba.poc.ble

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import fr.luba.poc.map.Point
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.SysDevErrCode
import fr.luba.poc.proto.MctrlSysProto.report_info_data
import fr.luba.poc.proto.MctrlSysProto.rpt_connect_status
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_location
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_status
import fr.luba.poc.proto.MctrlSysProto.rpt_rtk
import fr.luba.poc.proto.MctrlSysProto.rpt_work
import fr.luba.poc.protocol.Blufi
import fr.luba.poc.simulation.DeadReckoning
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * LUBA 2 simulated in VIRTUAL time for end-to-end tests of [RobotBle]: lives on the Robolectric main looper
 * (200 ms step posted with [Handler.postDelayed], advanced by `ShadowLooper.idleFor`), with the motion model of
 * [fr.luba.poc.autonomy.SimulatedRobotEnv] (command → effect delay 0.6 s, inertia, friction, RTK noise, published point
 * 8 cm behind the center of rotation). It decodes written BluFi frames ([SimLink]) and publishes its telemetry as BluFi
 * frames like the real one: state at the period requested by the report subscription (1 s at rest, 200 ms while
 * following a route), position at 1 Hz. The robot keeps living when the link is cut (last setpoint held until its
 * own safety stop [motorWatchdogMs]).
 */
class SimRobot(start: Point, startHeading: Double, seed: Long = 1) {
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random(seed)

    // ───── Physics (center of rotation, mathematical heading in degrees) ─────
    var x = start.x; private set
    var y = start.y; private set
    var heading = startHeading; private set
    private var v = 0.0; private var w = 0.0
    val position get() = Point(x, y)
    /** Actual linear speed (m/s). */
    val speed get() = v
    var meters = 0.0; private set

    private data class Setpoint(val at: Long, val linear: Int, val angular: Int)
    private val setpoints = ArrayDeque<Setpoint>()
    private var active: Setpoint? = null
    /** The robot stops on its own with no new setpoint for this long (null: never, it keeps the last setpoint). */
    var motorWatchdogMs: Long? = 1000L
    var noiseM = 0.01
    /** Friction: forward setpoint (units) absorbed before any movement. */
    var friction = FRICTION
    var headingNoise = 1.0

    // ───── Published state ─────
    var battery = 80.0
    var drainPerMeter = 0.0
    var drainPerSecond = 0.0
    var chargePerSecond = 0.5
    var manual = false; private set
    var bladesOn = false; private set
    var heightMm = 60; private set
    var selfCheck = 0L
    /** `sensor_status` bits other than blades (0–8): bits 0–2 = bumper health alert. */
    var sensorLow = 0
    /** State outside manual mode, return and charging (11: ready; 13: Mammotion task running…). */
    var idleState = 11
    /** State published while charging on the base. */
    var chargingState = 15
    var rtkStatus = 4
    /** No telemetry at all (robot frozen, reports lost). */
    var silent = false
    /** State report period requested by the app (`report_info_cfg`). */
    var reportPeriodMs = 1000L; private set
    private var reporting = false

    // ───── Base, official return ─────
    /** Published point when the robot is docked (station), and heading of the docked robot (nose towards the approach point). */
    var dock: Point? = null
    var dockedHeading = 0.0
    var nativeReturn = false; private set
    private var returnSettle = 0
    /** Robot position (published point) at each official Mammotion return request. */
    val nativeReturnRequests = mutableListOf<Point>()
    /** Speed of the simulated official return (m/s). */
    var nativeReturnSpeed = 0.4
    /** The official return is ignored (robot that does not start its return). */
    var refuseNativeReturn = false

    // ───── Physical obstacle, errors ─────
    /** Real obstacle (center, radius): forward motion is blocked on bumper contact, code 1014 published. */
    var obstacle: Pair<Point, Double>? = null
    /** Collision lock (self-check 25) published on contact, cleared after a 10 cm back-off. */
    var latchOnContact = false
    var contacts = 0; private set
    private var contactAt: Point? = null
    private var latchFrom: Point? = null
    /** Collision lock (25) cleared on its own once the robot has moved 10 cm away (otherwise held until reset to 0). */
    var latchRelease = false
    /** Delay before the lock clears once the robot has moved away (the robot publishes it late). */
    var latchReleaseDelayMs = 0L
    private var latchClearAt = 0L
    private val pendingErrors = ArrayDeque<Int>()
    private var clearError = false

    // ───── Link and log ─────
    var link: SimLink? = null
    /** Decoded messages in write order, with the virtual time. */
    val received = mutableListOf<Pair<Long, LubaMsg>>()
    /** Readable labels of received commands: "move l a", "stop", "manual", "blades-on", "blades-off", "dock"… */
    val trace = mutableListOf<Pair<Long, String>>()
    var tick = 0L; private set
    /** Successive centers of rotation (one per 200 ms step). */
    val track = mutableListOf<Point>()
    /** Centers of rotation during app-driven moves (excluding charging and official return). */
    val driven = mutableListOf<Point>()
    /** Steps where the blades were spinning while the robot was charging or on official return (safety violation). */
    var bladesWhileDocked = 0; private set
    /** Blades that no longer obey the stop (test of the unconfirmed OFF path). */
    var stuckBlades = false
    /** Cutting deck stuck: the height no longer changes. */
    var freezeHeight = false
    /** Lock after STOP (self-check 11) cleared by task cancellation. */
    var unlockOnCancel = true
    /** Called at each step, after the physics: scenarios. */
    var onTick: (SimRobot) -> Unit = {}
    var debug = false
    private var lastDevAt = 0L
    private var running = true

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            handler.postDelayed(this, STEP_MS)
        }
    }
    init { handler.postDelayed(step, STEP_MS) }

    fun shutdown() { running = false; handler.removeCallbacks(step) }

    /** Places the robot docked: published point on [dock], nose towards the approach point. */
    fun placeDocked(dock: Point, heading: Double) {
        this.dock = dock; dockedHeading = heading
        val r = Math.toRadians(heading)
        x = dock.x + BEHIND * cos(r); y = dock.y + BEHIND * sin(r); this.heading = heading
    }

    /** Slide (slope, wet grass): the robot is moved without any command. */
    fun shove(dx: Double, dy: Double) { x += dx; y += dy }

    fun place(p: Point, heading: Double) { x = p.x; y = p.y; this.heading = heading; v = 0.0; w = 0.0 }

    val published: Point get() { val r = Math.toRadians(heading); return Point(x - BEHIND * cos(r), y - BEHIND * sin(r)) }
    /** Charging contacts: made within 2 cm of the docking point (broken from the first step of leaving). */
    val charging: Boolean get() = !nativeReturn && dock?.let { published.distance(it) < 0.02 } == true
    val robotState: Int get() = when {
        nativeReturn || returnSettle > 0 -> 14
        charging -> chargingState
        manual -> 20
        drawing != null -> DRAW_STATES.getValue(requireNotNull(drawing))
        else -> idleState
    }

    // ───── Map stored in the robot: reading, recording by driving, deletion (APK `DeviceWorkState` 31/32/34) ─────
    /** Elements of the robot map by hash: upstream type (0 zone, 1 no-go, 2 passage) and points. */
    val mapElements = linkedMapOf<Long, Pair<Int, List<Point>>>()
    var nextHash = 7_000_000_001L
    /** Elements the robot deletes together with an element (no-go zones attached to a zone). */
    var deletedWith: Map<Long, List<Long>> = emptyMap()
    /** Type being recorded, null outside a recording. */
    var drawing: Int? = null; private set
    /** Published positions recorded during the current recording. */
    val drawn = mutableListOf<Point>()
    /** Recordings broken by the manual mode (the firmware leaves the recording state when it receives it). */
    var drawsBrokenByManual = 0; private set
    /** Recordings started while the robot was not READY (refused, as the firmware does). */
    var drawsRefused = 0; private set
    /** The element is only kept after `todev_save_task` (false: saved by the end of the recording itself). */
    var saveNeeded = false
    private var unsaved: Pair<Long, Pair<Int, List<Point>>>? = null
    /** Map commands received: "draw-start 0", "draw-end 0", "draw-cancel", "delete <hash>", "save". */
    val mapCommands = mutableListOf<String>()

    /** The robot answers the map reading (off by default: the other tests check a robot that does not answer it). */
    var servesMap = false

    fun loadMap(map: fr.luba.poc.map.RobotMap) { servesMap = true; mapElements.clear(); map.shapes.forEach { mapElements[it.id] = it.type to it.points } }

    /** Robot map as it would be read now. */
    fun storedMap(): List<fr.luba.poc.map.MapShape> = mapElements.map { (h, e) -> fr.luba.poc.map.MapShape(h, e.first, e.second) }

    private fun reply(nav: fr.luba.poc.proto.MctrlNavProto.MctlNav.Builder) {
        val message = LubaMsg.newBuilder().setNav(nav).build()
        handler.postDelayed({ link?.notify(message) }, 100)
    }

    private fun receiveNav(nav: fr.luba.poc.proto.MctrlNavProto.MctlNav, now: Long) {
        if (nav.hasTodevSaveTask()) {
            mapCommands += "save"; trace += now to "map-save"
            unsaved?.let { (h, e) -> mapElements[h] = e }; unsaved = null
        }
        if (nav.hasTodevGethash() && nav.todevGethash.subCmd == 0 && servesMap) {
            reply(fr.luba.poc.proto.MctrlNavProto.MctlNav.newBuilder().setToappGethashAck(fr.luba.poc.proto.MctrlNavProto.NavGetHashListAck.newBuilder()
                .setPver(1).setSubCmd(0).setResult(0).setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(mapElements.keys.toList())))
        }
        if (!nav.hasTodevGetCommondata()) return
        val data = nav.todevGetCommondata
        when {
            data.subCmd == 1 && data.action == 8 -> mapElements[data.hash]?.takeIf { servesMap }?.let { (type, points) ->
                reply(fr.luba.poc.proto.MctrlNavProto.MctlNav.newBuilder().setToappGetCommondataAck(fr.luba.poc.proto.MctrlNavProto.NavGetCommDataAck.newBuilder()
                    .setPver(1).setSubCmd(1).setResult(0).setAction(8).setType(type).setHash(data.hash).setTotalFrame(1).setCurrentFrame(1)
                    .addAllDataCouple(points.map { fr.luba.poc.proto.CommonProto.CommDataCouple.newBuilder().setX(it.x.toFloat()).setY(it.y.toFloat()).build() })))
            }
            data.subCmd != 0 -> Unit
            data.action == 0 -> {
                mapCommands += "draw-start ${data.type}"; trace += now to "draw-start ${data.type}"
                if (robotState == 11 && data.type in DRAW_STATES.keys) { drawing = data.type; drawn.clear(); drawn += published } else drawsRefused++
            }
            data.action == 1 -> {
                mapCommands += "draw-end ${data.type}"; trace += now to "draw-end ${data.type}"
                if (drawing == data.type) {
                    stored(data.type, drawn.toList())?.let { element ->
                        val hash = nextHash++
                        if (saveNeeded) unsaved = hash to element else mapElements[hash] = element
                    }
                }
                drawing = null; drawn.clear()
            }
            data.action == 7 -> { mapCommands += "draw-cancel"; trace += now to "draw-cancel"; drawing = null; drawn.clear() }
            data.action == 6 -> {
                mapCommands += "delete ${data.hash}"; trace += now to "delete ${data.hash}"
                if (mapElements.remove(data.hash) != null) deletedWith[data.hash].orEmpty().forEach { mapElements.remove(it) }
            }
        }
    }

    /** What the firmware stores for a recorded trace of the published point: outer side of the chassis for a zone,
     * inner side for a no-go zone, the axis for a passage. */
    private fun stored(type: Int, trace: List<Point>): Pair<Int, List<Point>>? {
        val points = trace.fold(mutableListOf<Point>()) { acc, p -> if (acc.isEmpty() || acc.last().distance(p) >= 0.1) acc += p; acc }
        if (type == 2) return if (points.size >= 2) type to points else null
        if (points.size < 4 || points.first().distance(points.last()) > 1.0) return null
        val factory = org.locationtech.jts.geom.GeometryFactory()
        val ring = (points + points.first()).map { org.locationtech.jts.geom.Coordinate(it.x, it.y) }.toTypedArray()
        val half = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM
        val area = factory.createPolygon(ring).buffer(0.0).buffer(if (type == 0) half else -half)
        val polygon = (0 until area.numGeometries).map { area.getGeometryN(it) }.filterIsInstance<org.locationtech.jts.geom.Polygon>()
            .maxByOrNull { it.area } ?: return null
        return type to polygon.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
    }
    val sensorStatus: Int get() = ((if (bladesOn) 1 else 0) shl 9) or sensorLow

    fun injectError(code: Int) { pendingErrors.addLast(code) }

    /** Was a movement setpoint received since [since] (virtual time)? */
    fun movedCommandSince(since: Long): Boolean = trace.any { (at, label) -> at >= since && label.startsWith("move") }
    fun labelsSince(since: Long): List<String> = trace.filter { it.first >= since }.map { it.second }

    // ───── Command reception ─────
    fun receive(message: LubaMsg) {
        val now = SystemClock.elapsedRealtime()
        received += now to message
        MotionSetpoints.of(message.toByteArray())?.let {
            // Firmware: positive angular = right; model: mathematical angle (left positive).
            setpoints.addLast(Setpoint(now, it.linear, -it.angular))
            trace += now to if (it.zero) "stop" else "move ${it.linear} ${-it.angular}"
            return
        }
        if (message.hasDriver()) {
            val driver = message.driver
            if (driver.hasMowCtrlByHand()) {
                val hand = driver.mowCtrlByHand
                // Manual mode during a recording: the firmware leaves the recording state, the trace is lost.
                if (hand.mainCtrl == 1 && drawing != null) { drawing = null; drawn.clear(); drawsBrokenByManual++; trace += now to "draw-broken" }
                manual = hand.mainCtrl == 1
                bladesOn = (hand.cutKnifeCtrl == 1 && manual) || (stuckBlades && bladesOn)
                if (manual && !freezeHeight && hand.cutKnifeHeight in 25..70) heightMm = hand.cutKnifeHeight
                trace += now to when { bladesOn -> "blades-on"; manual -> "manual"; else -> "blades-off" }
            }
            if (driver.hasTodevKnifeHeightSet()) {
                if (manual && !freezeHeight) heightMm = driver.todevKnifeHeightSet.knifeHeight
                trace += now to "height ${driver.todevKnifeHeightSet.knifeHeight}"
            }
        }
        if (message.hasSys()) {
            val sys = message.sys
            if (sys.hasTodevReportCfg()) {
                reporting = true
                reportPeriodMs = sys.todevReportCfg.period.toLong().coerceIn(200, 1000)
            }
            if (sys.hasTodevKnifeCtrl()) {
                bladesOn = sys.todevKnifeCtrl.knifeStatus == 1 && manual
                trace += now to if (bladesOn) "knife-on" else "knife-off"
            }
        }
        if (message.hasNav()) receiveNav(message.nav, now)
        if (message.hasNav() && message.nav.hasTodevTaskctrl()) {
            when (val action = message.nav.todevTaskctrl.action) {
                5 -> {
                    nativeReturnRequests += published
                    trace += now to "dock"
                    if (dock != null && !refuseNativeReturn && !charging) { nativeReturn = true; manual = false; bladesOn = false; setpoints.clear(); active = null }
                }
                12 -> { trace += now to "dock-cancel"; nativeReturn = false }
                2 -> { trace += now to "task-pause"; nativeReturn = false }
                4 -> { trace += now to "task-cancel"; nativeReturn = false; if (idleState == 13) idleState = 11; if (unlockOnCancel && selfCheck == 11L) selfCheck = 0 }
                else -> trace += now to "task $action"
            }
        }
    }

    // ───── Simulation step (200 ms) ─────
    private fun step() {
        val now = SystemClock.elapsedRealtime()
        tick++
        while (setpoints.isNotEmpty() && setpoints.first().at + DELAY_MS <= now) active = setpoints.removeFirst()
        val command = active?.takeIf { s -> motorWatchdogMs?.let { now - s.at <= it + DELAY_MS } ?: true }
        var l = command?.linear ?: 0
        val a = command?.angular ?: 0
        val dt = STEP_MS / 1000.0
        if (nativeReturn) {
            val target = requireNotNull(dock)
            val p = published
            val d = p.distance(target)
            val stepM = nativeReturnSpeed * dt
            if (d <= stepM) {
                placeDocked(target, dockedHeading); nativeReturn = false; returnSettle = 1
            } else {
                x += (target.x - p.x) / d * stepM; y += (target.y - p.y) / d * stepM; meters += stepM
            }
            v = 0.0; w = 0.0
        } else {
            if (returnSettle > 0 && tick % 5 == 0L) returnSettle--
            // Bumper: forward motion towards a real obstacle is blocked on contact.
            obstacle?.let { (c, r) ->
                val front = Point(x + FRONT * cos(Math.toRadians(heading)), y + FRONT * sin(Math.toRadians(heading)))
                if (front.distance(c) <= r && l > 0) {
                    l = 0; v = 0.0
                    if (contactAt == null) {
                        contactAt = position; contacts++
                        injectError(1014)
                        if (latchOnContact) { selfCheck = 25; latchRelease = true }
                    }
                }
            }
            val linear = sign(l.toDouble()) * max(0, abs(l) - friction)
            val angular = sign(a.toDouble()) * max(0, abs(a) - ANGULAR_FRICTION)
            val k = 1 - exp(-0.05 / TAU_S)
            repeat(4) {
                v += (linear * METERS_PER_UNIT - v) * k; w += (angular * DEG_PER_UNIT - w) * k
                heading += w * 0.05
                val dx = v * 0.05 * cos(Math.toRadians(heading)); val dy = v * 0.05 * sin(Math.toRadians(heading))
                x += dx; y += dy; meters += kotlin.math.hypot(dx, dy)
            }
            contactAt?.let { if (it.distance(position) >= 0.10) contactAt = null }
            // Collision lock (25) cleared by the robot once it is 10 cm away from where it was set.
            if (selfCheck == 25L && latchRelease) {
                val from = latchFrom ?: position.also { latchFrom = it }
                if (from.distance(position) >= 0.10 && latchClearAt == 0L) latchClearAt = now + latchReleaseDelayMs
                if (latchClearAt != 0L && now >= latchClearAt) { selfCheck = 0; latchFrom = null; latchClearAt = 0L }
            } else { latchFrom = null; latchClearAt = 0L }
        }
        track += position
        if (drawing != null) published.let { p -> if (drawn.isEmpty() || drawn.last().distance(p) >= 0.05) drawn += p }
        if (debug) println("SIMTICK t=$now x=%.3f y=%.3f h=%.1f v=%.3f state=$robotState charging=$charging".format(x, y, heading, v))
        if (!charging && !nativeReturn) driven += position
        if (bladesOn && (charging || nativeReturn)) bladesWhileDocked++
        onTick(this)
        battery = if (charging) (battery + chargePerSecond * dt).coerceAtMost(100.0)
            else (battery - drainPerSecond * dt - drainPerMeter * abs(v) * dt).coerceAtLeast(0.0)
        publish(now)
    }

    private fun publish(now: Long) {
        val target = link?.takeIf { it.connected } ?: return
        if (!reporting || silent) return
        if (clearError) { clearError = false; target.notify(errorMessage(0)) }
        pendingErrors.removeFirstOrNull()?.let { code -> target.notify(errorMessage(code)); clearError = true }
        val withPosition = tick % 5 == 0L
        if (!withPosition && now - lastDevAt < reportPeriodMs) return
        lastDevAt = now
        val data = report_info_data.newBuilder()
            .setDev(rpt_dev_status.newBuilder().setSysStatus(robotState).setChargeState(if (charging) 1 else 0)
                .setBatteryVal(battery.toInt().coerceIn(0, 100)).setSensorStatus(sensorStatus).setSelfCheckStatus(selfCheck.toInt()))
            .setWork(rpt_work.newBuilder().setKnifeHeight(heightMm))
            .setRtk(rpt_rtk.newBuilder().setStatus(rtkStatus).setPosLevel(1).setGpsStars(24))
            .setConnect(rpt_connect_status.newBuilder().setWifiRssi(-60).setBleRssi(-50).setMnetRssi(-90))
        if (withPosition) {
            val p = published
            // RTK noise also published while charging: the observed station must not change because of it (StationReader).
            val px = p.x + random.nextGaussian() * noiseM
            val py = p.y + random.nextGaussian() * noiseM
            val shown = heading + random.nextGaussian() * headingNoise
            val compass = ((90.0 - shown) % 360 + 360) % 360
            data.addLocations(rpt_dev_location.newBuilder().setRealPosX((px * 10000).toInt()).setRealPosY((py * 10000).toInt())
                .setRealToward((compass * 10000).toInt()))
        }
        target.notify(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(data)).build())
    }

    private fun errorMessage(code: Int) = LubaMsg.newBuilder().setSys(MctlSys.newBuilder()
        .setToappErrCode(SysDevErrCode.newBuilder().setErrorCode(code))).build()

    companion object {
        /** Robot state while it records a zone, a no-go zone or a passage. */
        val DRAW_STATES = mapOf(0 to 31, 1 to 32, 2 to 34)
        const val STEP_MS = 200L
        const val DELAY_MS = 600L
        const val METERS_PER_UNIT = 0.00108
        const val DEG_PER_UNIT = 0.11
        const val TAU_S = 0.25
        const val FRICTION = 30
        const val ANGULAR_FRICTION = 20
        val BEHIND = DeadReckoning.PUBLISHED_POINT_BEHIND_M
        /** Distance from the center of rotation to the front bumper: front of the LUBA 2 template (0.345 m from the published point). */
        val FRONT = fr.luba.poc.obstacles.RobotEnvelope.LUBA2.frontM - BEHIND
    }
}

/**
 * Link to [SimRobot] as seen by [RobotBle]: discovery then notifications enabled (onReady), BluFi writes decoded and
 * confirmed by `onWritten` after [writeLatencyMs] virtual ms. Injectable faults: refused write, GATT error code,
 * confirmation never returned, link loss, unreachable robot.
 */
class SimLink(private val robot: SimRobot, private val listener: RobotLink.Listener, private val mtu: Int = 512) : RobotLink {
    private val handler = Handler(Looper.getMainLooper())
    private val incoming = Blufi()
    private val outgoing = Blufi()
    override val description = "robot simulé"
    var closed = false; private set
    var lost = false; private set
    var ready = false; private set
    val connected get() = ready && !closed && !lost
    var writeLatencyMs = 20L
    /** GATT status returned by write confirmations. */
    var writeStatus = 0
    /** No write confirmations at all (GATT operation stuck). */
    var muteWrites = false
    /** Writes refused by the Bluetooth stack. */
    var refuseWrites = false
    val frames = mutableListOf<ByteArray>()
    val protocolErrors = mutableListOf<String>()

    fun open(reachable: Boolean = true) {
        robot.link = this
        if (!reachable) return
        handler.postDelayed({ if (!closed) listener.onDiscovering(this) }, 50)
        handler.postDelayed({ if (!closed && !lost) { ready = true; listener.onReady(this, mtu) } }, 150)
    }

    override fun write(frame: ByteArray): Boolean {
        if (closed || lost || refuseWrites) return false
        frames += frame.copyOf()
        val payload = runCatching { incoming.receive(frame) }.onFailure { protocolErrors += it.message ?: it.javaClass.simpleName }.getOrNull()
        if (payload != null && payload.firstOrNull() != '{'.code.toByte()) {
            runCatching { LubaMsg.parseFrom(payload) }.onFailure { protocolErrors += "protobuf : ${it.message}" }.getOrNull()?.let(robot::receive)
        }
        if (!muteWrites) { val status = writeStatus; handler.postDelayed({ if (!closed && !lost) listener.onWritten(this, status) }, writeLatencyMs) }
        return true
    }

    override fun readRssi(): Boolean {
        if (closed || lost) return false
        handler.postDelayed({ if (!closed && !lost) listener.onRssi(this, -55, 0) }, writeLatencyMs)
        return true
    }

    override fun close() { closed = true; if (robot.link === this) robot.link = null }

    /** Unexpected link loss (GATT code 8: supervision timeout). */
    fun drop(code: Int = 8) {
        if (closed || lost) return
        lost = true
        listener.onLost(this, code, "Perte BLE ($code)")
    }

    fun notify(message: LubaMsg) {
        if (!connected) return
        outgoing.encode(message.toByteArray(), mtu).forEach { listener.onNotification(this, it) }
    }
}
