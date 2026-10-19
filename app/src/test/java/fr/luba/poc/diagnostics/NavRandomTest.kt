package fr.luba.poc.diagnostics

import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class NavRandomTest {
    @Test fun `auto mode refuses missing stale or non-finite position`() {
        assertNull(NavSafety.automaticPositionFailure(2000, 0.0, 1.0))
        assertNotNull(NavSafety.automaticPositionFailure(2001, 0.0, 1.0))
        assertNotNull(NavSafety.automaticPositionFailure(null, 0.0, 1.0))
        assertNotNull(NavSafety.automaticPositionFailure(0, null, 1.0))
        assertNotNull(NavSafety.automaticPositionFailure(0, Double.NaN, 1.0))
    }


    @Test fun `stop requires two new positions after STOP`() {
        val old = NavSample(0.0, 0.0, null, 100)
        val fresh = NavSample(0.0, 0.0, null, 1100)
        assertNull(NavSafety.observedStopDelay(listOf(old, fresh), 1000))
        assertNull(NavSafety.observedStopDelay(listOf(fresh, fresh), 1000))
        assertEquals(1100L, NavSafety.observedStopDelay(listOf(fresh, fresh.copy(elapsedMs = 2100)), 1000))
        assertNull(NavSafety.observedStopDelay(listOf(fresh, fresh.copy(x = 0.1, elapsedMs = 2100)), 1000))
    }

    private val config = NavConfig(segments = 6, linearSpeed = 50, angularSpeed = 30, maxDurationMs = 60_000)

    @Test fun `auto move without mowing runs on healthy telemetry`() {
        val result = walk(config)
        assertEquals("PASS", result.outcome)
        assertNull(result.motif)
        assertTrue(result.ticks > 0)
        assertEquals(NavSegment.Kind.STOP_PAUSE, result.plan.last().kind)
        assertTrue(result.plan.sumOf { it.durationMs } <= config.maxDurationMs)
    }

    @Test fun `auto move with mowing tolerates discs off then on`() {
        val mowing = config.copy(tonte = true)
        assertEquals("PASS", walk(mowing).outcome)
        assertEquals("PASS", walk(mowing, sensors = { 512 }).outcome)
    }

    @Test fun `auto move without mowing stops on active blade`() {
        for (bladeBits in 1..7) {
            val result = walk(config, sensors = { tick -> if (tick < 2) 0 else bladeBits shl 9 })
            assertEquals("ABORTED", result.outcome)
            assertEquals("Lames actives inattendues", result.motif)
        }
    }

    @Test fun `auto move with mowing stops on unrecognised blade state`() {
        for (bladeBits in 2..7) {
            val result = walk(config.copy(tonte = true), sensors = { tick -> if (tick < 2) 512 else bladeBits shl 9 })
            assertEquals("ABORTED", result.outcome)
            assertEquals("État lames non reconnu", result.motif)
        }
    }

    @Test fun `movement plan identical with and without mowing`() {
        for (seed in 0L..10L) {
            assertEquals(
                RandomWalk.plan(Random(seed), config),
                RandomWalk.plan(Random(seed), config.copy(tonte = true, heightMm = 45)),
            )
        }
    }

    @Test fun `radius guard identical with and without mowing`() {
        for (tonte in listOf(false, true)) {
            val blades = if (tonte) 512 else 0
            val inside = walk(config.copy(tonte = tonte), x = { 4.9 }, y = { 0.0 }, sensors = { blades })
            assertEquals("PASS", inside.outcome)
            val outside = walk(config.copy(tonte = tonte), x = { 5.1 }, y = { 0.0 }, sensors = { blades })
            assertEquals("ABORTED", outside.outcome)
            assertEquals("Rayon maximal dépassé autour du départ", outside.motif)
        }
    }

    @Test fun `start with mowing also requires blades OFF before activation`() {
        for (bladeBits in 1..7) {
            val failure = NavSafety.startFailure("Luba-VS", 100, 72, 11, bladeBits shl 9, 0, false, false, false, 45)
            assertEquals("Lames non confirmées OFF par la télémétrie", failure)
        }
        assertNull(NavSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, false, false, 45))
    }

    @Test fun `movement commands without blade and mowing keepalive without speed`() {
        val commands = Commands()
        val move = LubaMsg.parseFrom(commands.movement(50, 30, 1))
        assertEquals(50, move.driver.todevDevmotionCtrl.setLinearSpeed)
        assertEquals(-30, move.driver.todevDevmotionCtrl.setAngularSpeed)
        assertFalse(move.driver.hasMowCtrlByHand())
        val keepalive = LubaMsg.parseFrom(commands.manualMow(mainCtrl = 1, cutKnifeCtrl = 1, heightMm = 60, now = 2))
        assertEquals(1, keepalive.driver.mowCtrlByHand.mainCtrl)
        assertEquals(1, keepalive.driver.mowCtrlByHand.cutKnifeCtrl)
        assertEquals(60, keepalive.driver.mowCtrlByHand.cutKnifeHeight)
        assertFalse(keepalive.driver.hasTodevDevmotionCtrl())
        val mode = LubaMsg.parseFrom(commands.manualMow(mainCtrl = 1, cutKnifeCtrl = 0, heightMm = 60, now = 3))
        assertEquals(0, mode.driver.mowCtrlByHand.cutKnifeCtrl)
    }

    @Test fun `deterministic random walk bounded and ended by a STOP`() {
        val first = RandomWalk.plan(Random(42), config)
        assertEquals(first, RandomWalk.plan(Random(42), config))
        assertTrue(first.isNotEmpty())
        assertEquals(NavSegment.Kind.STOP_PAUSE, first.last().kind)
        assertTrue(first.sumOf { it.durationMs } <= config.maxDurationMs)
        first.forEach { segment ->
            assertTrue(segment.durationMs > 0)
            when (segment.kind) {
                NavSegment.Kind.ROTATE -> {
                    assertEquals(0, segment.linear)
                    assertEquals(30, kotlin.math.abs(segment.angular))
                    assertTrue(segment.durationMs <= RandomWalk.MAX_ROTATE_MS)
                }
                NavSegment.Kind.ADVANCE -> {
                    assertEquals(50, segment.linear)
                    assertEquals(0, segment.angular)
                    assertTrue(segment.durationMs <= RandomWalk.MAX_ADVANCE_MS)
                }
                NavSegment.Kind.STOP_PAUSE -> {
                    assertEquals(0, segment.linear)
                    assertEquals(0, segment.angular)
                    assertTrue(segment.durationMs <= RandomWalk.MAX_PAUSE_MS)
                }
            }
        }
        // Every movement is followed by a STOP_PAUSE; never two consecutive movements.
        first.zipWithNext { current, next ->
            if (current.kind == NavSegment.Kind.ADVANCE) assertEquals(NavSegment.Kind.STOP_PAUSE, next.kind)
            if (current.kind == NavSegment.Kind.ROTATE) assertNotEquals(NavSegment.Kind.ROTATE, next.kind)
        }
    }

    @Test fun `duration cap truncates movements but keeps the final STOP`() {
        val tight = NavConfig(segments = 20, linearSpeed = 50, angularSpeed = 30, maxDurationMs = 10_000)
        for (seed in 0L..20L) {
            val plan = RandomWalk.plan(Random(seed), tight)
            assertTrue(plan.isNotEmpty())
            assertEquals(NavSegment.Kind.STOP_PAUSE, plan.last().kind)
            assertTrue(plan.sumOf { it.durationMs } <= tight.maxDurationMs)
            assertTrue(plan.all { it.durationMs > 0 })
        }
    }

    @Test fun `config refuses out-of-range values`() {
        val scenarios = listOf<() -> Unit>(
            { NavConfig(segments = 2) }, { NavConfig(segments = 21) },
            { NavConfig(linearSpeed = 0) }, { NavConfig(linearSpeed = Commands.MAX_LINEAR_SPEED + 1) },
            { NavConfig(angularSpeed = 0) }, { NavConfig(angularSpeed = -Commands.MAX_ANGULAR_SPEED) },
            { NavConfig(heightMm = 24) }, { NavConfig(heightMm = 71) },
            { NavConfig(maxDurationMs = 9_999) }, { NavConfig(maxDurationMs = 600_001) },
            { NavConfig(maxRadiusM = 0.5) }, { NavConfig(maxRadiusM = 20.5) },
            { NavConfig(batteryFloor = 9) }, { NavConfig(batteryFloor = 81) },
        )
        scenarios.forEach { action -> assertThrows(IllegalArgumentException::class.java) { action() } }
    }

    @Test fun `start refuses unknown robot stale state low battery protection and blades`() {
        assertNull(NavSafety.startFailure("Luba-VS-test", 100, 72, 11, 0, 0, false, false, false, 60))
        val scenarios = listOf<Pair<String, String?>>(
            "nom" to NavSafety.startFailure("Luba", 100, 72, 11, 0, 0, false, false, false, 60),
            "etat stale" to NavSafety.startFailure("Luba-VS", 2001, 72, 11, 0, 0, false, false, false, 60),
            "batterie basse" to NavSafety.startFailure("Luba-VS", 100, 30, 11, 0, 0, false, false, false, 60),
            "robot non pret" to NavSafety.startFailure("Luba-VS", 100, 72, 13, 0, 0, false, false, false, 60),
            "bumper" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 1, 0, false, false, false, 60),
            "lames" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 512, 0, false, false, false, 60),
            "self-check" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 0, 34, false, false, false, 60),
            "campagne" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, true, false, false, 60),
            "essai lame" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, true, false, 60),
            "carte" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, false, true, 60),
            "hauteur" to NavSafety.startFailure("Luba-VS", 100, 72, 11, 0, 0, false, false, false, 24),
        )
        scenarios.forEach { (label, failure) -> assertNotNull(label, failure) }
    }

    @Test fun `tick monitors freshness battery bumper blades radius and zone`() {
        assertNull(NavSafety.tickFailure(100, 72, 0, tonte = false, batteryFloor = 30))
        assertNotNull(NavSafety.tickFailure(null, 72, 0, tonte = false, batteryFloor = 30))
        assertNotNull(NavSafety.tickFailure(2001, 72, 0, tonte = false, batteryFloor = 30))
        assertNotNull(NavSafety.tickFailure(100, 30, 0, tonte = false, batteryFloor = 30))
        assertNotNull(NavSafety.tickFailure(100, 72, 1, tonte = false, batteryFloor = 30))
        assertNotNull(NavSafety.tickFailure(100, 72, 512, tonte = false, batteryFloor = 30))
        assertNull(NavSafety.tickFailure(100, 72, 512, tonte = true, batteryFloor = 30))
        assertNull(NavSafety.tickFailure(100, 72, 0, tonte = true, batteryFloor = 30))
        assertNotNull(NavSafety.tickFailure(100, 72, 1024, tonte = true, batteryFloor = 30))
        val start = NavSample(0.0, 0.0, 0.0, 0)
        assertNull(NavSafety.tickFailure(100, 72, 0, tonte = false, batteryFloor = 30, x = 4.9, y = 0.0, start = start, maxRadiusM = 5.0))
        assertNotNull(NavSafety.tickFailure(100, 72, 0, tonte = false, batteryFloor = 30, x = 5.1, y = 0.0, start = start, maxRadiusM = 5.0))
    }

    @Test fun `zone away from the margin accepted and edge refused`() {
        val zone = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0))
        assertTrue(NavSafety.insideMargin(5.0, 5.0, zone, 0.5))
        assertFalse(NavSafety.insideMargin(0.2, 5.0, zone, 0.5))
        assertFalse(NavSafety.insideMargin(12.0, 5.0, zone, 0.5))
        assertNull(NavSafety.tickFailure(100, 72, 0, tonte = false, batteryFloor = 30, x = 5.0, y = 5.0, start = NavSample(5.0, 5.0, 0.0, 0), zone = zone, margin = 0.5))
        assertNotNull(NavSafety.tickFailure(100, 72, 0, tonte = false, batteryFloor = 30, x = 0.2, y = 5.0, start = NavSample(5.0, 5.0, 0.0, 0), zone = zone, margin = 0.5))
        assertNotNull(NavSafety.tickFailure(100, 72, 0, tonte = false, batteryFloor = 30, x = 12.0, y = 5.0, start = NavSample(5.0, 5.0, 0.0, 0), zone = zone, margin = 0.5))
    }

    private data class Walk(val outcome: String, val motif: String?, val ticks: Int, val plan: List<NavSegment>)

    /** Runs a full plan like the walk engine: 200 ms ticks, NavSafety guard at every tick. */
    private fun walk(
        config: NavConfig,
        seed: Long = 42L,
        sensors: (tick: Int) -> Int = { 0 },
        battery: (tick: Int) -> Int = { 72 },
        x: (tick: Int) -> Double = { 0.0 },
        y: (tick: Int) -> Double = { 0.0 },
    ): Walk {
        val plan = RandomWalk.plan(Random(seed), config)
        val start = NavSample(0.0, 0.0, 0.0, 0)
        var tick = 0
        for (segment in plan) {
            val steps = ((segment.durationMs + 199) / 200).toInt().coerceAtLeast(1)
            repeat(steps) {
                val failure = NavSafety.tickFailure(
                    100, battery(tick), sensors(tick), config.tonte, config.batteryFloor,
                    x(tick), y(tick), start, config.maxRadiusM,
                )
                if (failure != null) return Walk("ABORTED", failure, tick, plan)
                tick++
            }
        }
        return Walk("PASS", null, tick, plan)
    }
}
