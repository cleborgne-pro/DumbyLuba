package fr.luba.poc.gateway

import fr.luba.poc.ble.MotionSetpoints
import fr.luba.poc.ble.RobotLink
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlSysProto.MctlSys
import fr.luba.poc.proto.MctrlSysProto.SysDevErrCode
import fr.luba.poc.proto.MctrlSysProto.report_info_data
import fr.luba.poc.proto.MctrlSysProto.rpt_connect_status
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_location
import fr.luba.poc.proto.MctrlSysProto.rpt_rtk
import fr.luba.poc.proto.MctrlSysProto.rpt_dev_status
import fr.luba.poc.proto.MctrlSysProto.rpt_work
import fr.luba.poc.protocol.Blufi
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * Simulated robot behind the gateway's Bluetooth link: decodes the written BluFi frames (speed setpoints, manual mode,
 * blades, height), drives a modelled LUBA 2 (0.6 s delay, inertia, friction, RTK noise) and sends its telemetry back
 * as BluFi frames at 5 Hz (position at 1 Hz), like the real one. Real time.
 */
class FakeRobotLink(private val listener: RobotLink.Listener, startX: Double, startY: Double, startHeading: Double, seed: Long = 1) : RobotLink {
    override val description = "robot simulé"
    @Volatile var closed = false
    val writes = CopyOnWriteArrayList<ByteArray>()
    /** Decoded messages, in write order. */
    val received = CopyOnWriteArrayList<LubaMsg>()
    /** Zero-speed, blades-OFF… writes seen in order (readable labels). */
    val trace = CopyOnWriteArrayList<String>()

    private val random = Random(seed)
    private val incoming = Blufi()
    private val outgoing = Blufi()
    @Volatile var x = startX; @Volatile var y = startY; @Volatile var heading = startHeading
    private var v = 0.0; private var w = 0.0
    private val pending = ArrayDeque<Pair<Int, Int>>().also { q -> repeat(3) { q.addLast(0 to 0) } }
    @Volatile var manual = false; @Volatile var bladesOn = false; @Volatile var heightMm = 60
    @Volatile var battery = 100
    @Volatile var selfCheck = 0
    private var published = startX to startY
    private var publishedHeading = startHeading
    @Volatile var lastLinear = 0
    @Volatile var silent = false
    private val pendingErrors = java.util.concurrent.ConcurrentLinkedQueue<Int>()
    private var tick = 0
    private val ticker = Thread({
        try {
            while (!closed) {
                Thread.sleep(200)
                step()
            }
        } catch (_: InterruptedException) { }
    }, "fake-robot").apply { isDaemon = true }

    fun start() { ticker.start() }

    fun injectError(code: Int) { pendingErrors.add(code) }

    override fun write(frame: ByteArray): Boolean {
        if (closed) return false
        writes += frame.copyOf()
        val payload = runCatching { incoming.receive(frame) }.getOrNull()
        if (payload != null && payload.firstOrNull() != '{'.code.toByte()) {
            val message = runCatching { LubaMsg.parseFrom(payload) }.getOrNull()
            if (message != null) handle(message)
        }
        Thread { Thread.sleep(5); if (!closed) listener.onWritten(this, 0) }.start()
        return true
    }

    private fun handle(message: LubaMsg) {
        received += message
        MotionSetpoints.of(message.toByteArray())?.let {
            lastLinear = it.linear
            trace += if (it.zero) "stop" else "move"
            synchronized(pending) { pending.addLast(it.linear to -it.angular) }
            return
        }
        if (message.hasDriver() && message.driver.hasMowCtrlByHand()) {
            val hand = message.driver.mowCtrlByHand
            manual = hand.mainCtrl == 1
            bladesOn = hand.cutKnifeCtrl == 1 && manual
            if (hand.cutKnifeHeight in 25..70) heightMm = hand.cutKnifeHeight
            trace += if (bladesOn) "blades-on" else if (manual) "manual" else "blades-off"
        }
    }

    /** Signal measured by the gateway (default: steady, phone on the robot); read on each RSSI request. */
    @Volatile var rssi: (FakeRobotLink) -> Int = { -50 }
    @Volatile var rssiReads = 0
    override fun readRssi(): Boolean { val value = rssi(this); rssiReads++; Thread { Thread.sleep(5); if (!closed) listener.onRssi(this, value, 0) }.start(); return true }
    override fun close() { closed = true; ticker.interrupt() }

    private fun step() {
        val (l, a) = synchronized(pending) { if (pending.isEmpty()) 0 to 0 else pending.removeFirst() }
        val linear = sign(l.toDouble()) * max(0, abs(l) - 30)
        val angular = sign(a.toDouble()) * max(0, abs(a) - 20)
        val k = 1 - exp(-0.05 / 0.25)
        repeat(4) {
            v += (linear * 0.00108 - v) * k; w += (angular * 0.11 - w) * k
            heading += w * 0.05
            x += v * 0.05 * cos(Math.toRadians(heading)); y += v * 0.05 * sin(Math.toRadians(heading))
        }
        tick++
        if (silent) return
        pendingErrors.poll()?.let { code -> notify(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappErrCode(SysDevErrCode.newBuilder().setErrorCode(code))).build()) }
        val withPosition = tick % 5 == 0
        if (withPosition) {
            published = x + random.nextGaussian() * 0.01 to y + random.nextGaussian() * 0.01
            publishedHeading = heading + random.nextGaussian()
        }
        val data = report_info_data.newBuilder()
            .setDev(rpt_dev_status.newBuilder().setSysStatus(if (manual) 20 else 11).setChargeState(0).setBatteryVal(battery)
                .setSensorStatus((if (bladesOn) 1 else 0) shl 9).setSelfCheckStatus(selfCheck))
            .setWork(rpt_work.newBuilder().setKnifeHeight(heightMm))
            .setRtk(rpt_rtk.newBuilder().setStatus(4).setPosLevel(0).setGpsStars(24))
            .setConnect(rpt_connect_status.newBuilder().setWifiRssi(-60).setBleRssi(-50).setMnetRssi(-90))
        if (withPosition) {
            val compass = ((90.0 - publishedHeading) % 360 + 360) % 360
            data.addLocations(rpt_dev_location.newBuilder().setRealPosX((published.first * 10000).toInt()).setRealPosY((published.second * 10000).toInt())
                .setRealToward((compass * 10000).toInt()))
        }
        notify(LubaMsg.newBuilder().setSys(MctlSys.newBuilder().setToappReportData(data)).build())
    }

    private fun notify(message: LubaMsg) {
        if (closed) return
        outgoing.encode(message.toByteArray(), 247).forEach { listener.onNotification(this, it) }
    }
}
