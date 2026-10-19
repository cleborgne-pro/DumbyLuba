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
        else -> idleState
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
