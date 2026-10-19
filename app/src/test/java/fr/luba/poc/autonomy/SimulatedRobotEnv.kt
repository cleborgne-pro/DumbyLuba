package fr.luba.poc.autonomy

import fr.luba.poc.map.Point
import fr.luba.poc.map.SignalSample
import fr.luba.poc.ble.MotionSetpoints
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.protocol.Telemetry
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * Simulated robot for the on-board route tracking: same command → effect delay (0.6 s), first-order inertia, friction,
 * position published at 1 Hz with RTK noise and state reports at 5 Hz as in [fr.luba.poc.simulation.RouteExecutionModel].
 * Time is virtual: `delay` moves the robot forward, nothing really waits.
 */
class SimulatedRobotEnv(
    start: Point, startHeading: Double,
    private val delayMs: Long = 600, private val degPerUnit: Double = 0.11, private val metersPerUnit: Double = 0.00108,
    private val tauS: Double = 0.25, private val friction: Int = 30, private val angularFriction: Int = 20,
    private val noiseM: Double = 0.01, private val headingNoise: Double = 1.0, seed: Long = 1,
    var battery: Int = 100,
) : LegEnv {
    private val random = Random(seed)
    private var x = start.x; private var y = start.y; private var heading = startHeading
    private var v = 0.0; private var w = 0.0
    private val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat((delayMs / 200).toInt()) { q.addLast(0 to 0) } }
    private var clock = 1_000L
    private var wallBase = 1_700_000_000_000L
    private var manual = false
    private var bladesOn = false
    private var heightMm = 60
    private var heightReportedAt = 0L
    /** Like the LUBA 2: point published 8 cm behind the rotation centre, on the robot axis. */
    private val behindM = fr.luba.poc.simulation.DeadReckoning.PUBLISHED_POINT_BEHIND_M
    private var published = Point(start.x - behindM * cos(Math.toRadians(startHeading)), start.y - behindM * sin(Math.toRadians(startHeading)))
    private var publishedHeading = startHeading
    private var gate: ((String, Long) -> Unit)? = null

    private var devicePrevious = clock
    private var positionPrevious = clock
    private var positionWall = wallBase
    private var blocked = false

    // --- Test controls ---
    var healthy = true
    var hostFailure: String? = null
    var pendingContact: LegContact? = null
    /** Slide of the centre during an in-place rotation (slope), m/s; null: flat ground. */
    var pivotSlide: Point? = null
    /** Published self-check (0 nominal; 25: collision lock of 2026-10-15). */
    var selfCheck: Long = 0L
    var bypassArmed = false; private set
    var staleEverything = false
    /** Heading missing from telemetry (missing data). */
    var dropHeading = false
    /** Charging contacts: chargeState 1 while the robot is less than 8 cm from this point (null: never charging). */
    var dock: Point? = null
    /** Error added to the published heading (wrong heading when stopped, realigned while rolling on the real robot). */
    var headingBias = 0.0
    var obstacleList: List<LegObstacle> = emptyList()
    /** Called at each 200 ms step (virtual time): test scenarios. */
    var onTick: (SimulatedRobotEnv) -> Unit = {}

    val sent = mutableListOf<String>()
    val progresses = mutableListOf<LegProgress>()
    val samples = mutableListOf<SignalSample>()
    val events = mutableListOf<String>()
    var maxLinearSeen = 0
    val position get() = Point(x, y)
    /** Slip (slope, wet grass): the robot is moved by ([dx], [dy]) without a command. */
    fun shove(dx: Double, dy: Double) { x += dx; y += dy }
    val trueHeading get() = heading
    val speed get() = hypot(v, 0.0)
    val elapsedMs get() = clock

    override fun now() = clock
    override fun wall() = wallBase + clock
    override fun view(): RobotView = RobotView(telemetry(), devicePrevious, positionPrevious)
    private fun telemetry(): Telemetry =
        Telemetry(
            battery = battery, robotState = if (manual) 20 else 11, selfCheck = selfCheck,
            chargeState = if (dock?.let { it.distance(Point(x, y)) < 0.08 } == true) 1 else 0,
            sensorStatus = (if (bladesOn) 1 else 0) shl 9,
            x = published.x, y = published.y, heading = (((publishedHeading + headingBias) % 360 + 360) % 360).takeUnless { dropHeading },
            bladeHeightMm = heightMm, driverHeightMm = heightMm, lastDriverHeightAt = heightReportedAt.takeIf { it > 0 }, heightMoving = false,
            lastPositionAt = positionWall, lastDeviceAt = wallBase + devicePrevious, lastReportAt = wallBase + devicePrevious,
            lastConnectAt = positionWall, wifiRssi = -60, bleRssi = -50, mobileRssi = -90, lastRtkAt = positionWall,
            rtkStatus = 4, positioningLevel = 1, gpsStars = 24,
        )

    override fun linkHealthy() = healthy
    override fun hostFailure() = hostFailure
    var cuttingFailure: String? = null
    override fun cuttingFailure() = cuttingFailure
    override fun contact() = pendingContact
    override fun armBypass(armed: Boolean) { bypassArmed = armed }
    /** Report to the pilot (end-to-end tests): progress, survey samples, events and obstacles. */
    var hooks: LegHooks? = null
    override fun obstacles() = hooks?.obstacles() ?: obstacleList
    override fun onWritten(listener: ((label: String, at: Long) -> Unit)?) { gate = listener }
    override fun purge() = Unit
    /** Tilt of the phone placed on the robot (terrain tests). */
    var gravity: fr.luba.poc.map.PhoneGravity? = null
    override fun tilt() = gravity
    var confirmed: Double? = null; private set
    override fun confirmedHeading(heading: Double?) { confirmed = heading }
    override fun event(kind: String, text: String, fields: Map<String, Any?>) { events += kind; hooks?.event(kind, text, fields) }
    override fun progress(progress: LegProgress) { progresses += progress; hooks?.progress(progress) }
    override fun sample(sample: SignalSample) { samples += sample; hooks?.sample(sample) }

    override fun send(payload: ByteArray, label: String) {
        sent += label
        val setpoint = MotionSetpoints.of(payload)
        if (setpoint != null) {
            maxLinearSeen = max(maxLinearSeen, abs(setpoint.linear))
            pending.addLast(setpoint.linear to -setpoint.angular)   // Commands.movement inverts the angular value for the firmware
        } else {
            val message = LubaMsg.parseFrom(payload)
            if (message.hasDriver() && message.driver.hasMowCtrlByHand()) {
                val hand = message.driver.mowCtrlByHand
                manual = hand.mainCtrl == 1
                bladesOn = hand.cutKnifeCtrl == 1 && manual
                if (hand.cutKnifeHeight in 25..70) { heightMm = hand.cutKnifeHeight; heightReportedAt = wall() + 1 }
            }
        }
        // GATT write confirmed at once (fast local link).
        gate?.invoke(label, clock)
    }

    override suspend fun delay(ms: Long) {
        var left = ms
        while (left > 0) { step(); left -= 200 }
        kotlinx.coroutines.yield()   // suspension point: a cancellation of the tracking is observed
    }

    private fun step() {
        if (!staleEverything) {
            val (l, a) = if (pending.isEmpty()) 0 to 0 else pending.removeFirst()
            val linear = sign(l.toDouble()) * max(0, abs(l) - friction)
            val angular = sign(a.toDouble()) * max(0, abs(a) - angularFriction)
            val k = 1 - exp(-0.05 / tauS)
            repeat(4) {
                v += (linear * metersPerUnit - v) * k; w += (angular * degPerUnit - w) * k
                heading += w * 0.05
                x += v * 0.05 * cos(Math.toRadians(heading)); y += v * 0.05 * sin(Math.toRadians(heading))
                pivotSlide?.takeIf { abs(w) > 5.0 && abs(v) < 0.03 }?.let { s -> x += s.x * 0.05; y += s.y * 0.05 }
            }
        }
        clock += 200
        if (!staleEverything) {
            devicePrevious = clock
            if (clock % 1000 == 0L) {
                val r = Math.toRadians(heading)
                published = Point(x - behindM * cos(r) + random.nextGaussian() * noiseM, y - behindM * sin(r) + random.nextGaussian() * noiseM)
                publishedHeading = (((heading + random.nextGaussian() * headingNoise) % 360) + 360) % 360
                positionPrevious = clock; positionWall = wall()
            }
        }
        onTick(this)
    }
}
