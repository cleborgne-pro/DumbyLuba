package fr.luba.poc.ble

import fr.luba.poc.protocol.Commands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSetpointsTest {
    private val commands = Commands()

    private data class Pending(val label: String, val setpoint: MotionSetpoints.Setpoint?, val batch: Int)

    @Test fun `setpoint read from the movement command, nothing for other commands`() {
        assertEquals(MotionSetpoints.Setpoint(150, -20), MotionSetpoints.of(commands.movement(150, 20)))
        assertTrue(MotionSetpoints.of(commands.stop())!!.zero)
        assertNull(MotionSetpoints.of(commands.sync()))
        assertNull(MotionSetpoints.of(commands.manualBlades(false, 60)))
        assertNull(MotionSetpoints.of(byteArrayOf(1, 2, 3)))
    }

    @Test fun `new setpoint replaces pending non-zero setpoints, keeps stop and other frames`() {
        val queue = mutableListOf(
            Pending("ROUTE_DOCK_EXIT", MotionSetpoints.Setpoint(150, 0), 1),
            Pending("sync(2)", null, 2),
            Pending("ROUTE_SETTLE", MotionSetpoints.Setpoint(0, 0), 3),
            Pending("ROUTE_DOCK_EXIT", MotionSetpoints.Setpoint(150, 0), 4),
            Pending("RPT_KEEP", null, 5))
        val removed = MotionSetpoints.coalesce(queue, MotionSetpoints.of(commands.movement(150, 0)), { it.setpoint })
        assertEquals(2, removed)
        assertEquals(listOf("sync(2)", "ROUTE_SETTLE", "RPT_KEEP"), queue.map { it.label })
    }

    @Test fun `frame that is not a setpoint removes nothing`() {
        val queue = mutableListOf(Pending("ROUTE_DOCK_EXIT", MotionSetpoints.Setpoint(150, 0), 1))
        assertEquals(0, MotionSetpoints.coalesce(queue, MotionSetpoints.of(commands.sync()), { it.setpoint }))
        assertEquals(1, queue.size)
    }

    @Test fun `remainder of a message being written is kept`() {
        val queue = mutableListOf(
            Pending("ROUTE_MOVE fragment 2", MotionSetpoints.Setpoint(100, 5), 7),
            Pending("ROUTE_MOVE", MotionSetpoints.Setpoint(100, 5), 8))
        val removed = MotionSetpoints.coalesce(queue, MotionSetpoints.Setpoint(120, 0), { it.setpoint }, keep = { it.batch == 7 })
        assertEquals(1, removed)
        assertEquals(7, queue.single().batch)
    }

    @Test fun `2026-10-11 scenario queue bounded even with slow writes`() {
        // 700 ms write in flight, setpoints at 5 Hz, sync and report keep-alive: the queue no longer exceeds 4.
        val queue = mutableListOf<Pending>()
        var maxDepth = 0
        for (tick in 0 until 20) {
            val incoming = MotionSetpoints.Setpoint(150, 0)
            MotionSetpoints.coalesce(queue, incoming, { it.setpoint })
            queue.add(Pending("ROUTE_DOCK_EXIT", incoming, tick))
            if (tick % 5 == 0) queue.add(Pending("sync(2)", null, 100 + tick))
            if (tick % 4 == 3) queue.removeAt(0) // one write confirmed every ~800 ms
            maxDepth = maxOf(maxDepth, queue.size)
        }
        assertTrue("depth $maxDepth", maxDepth <= 4)
    }

    @Test fun `periodic command not re-queued while the previous one is pending or being written`() {
        val queue = listOf(Pending("NAV_MAP_MOVE", MotionSetpoints.Setpoint(300, 0), 1), Pending("NAV_ERROR_QUERY", null, 2))
        assertTrue(MotionSetpoints.periodicPending(queue, null, "NAV_ERROR_QUERY") { it.label })
        assertTrue(MotionSetpoints.periodicPending(emptyList(), Pending("NAV_BUMPER_KEEPALIVE_OFF", null, 3), "NAV_BUMPER_KEEPALIVE_OFF") { it.label })
        assertFalse(MotionSetpoints.periodicPending(queue, null, "NAV_BUMPER_KEEPALIVE_OFF") { it.label })
    }

    /** Longest interval (ms) between two written motion setpoints, over 10 s of simulated movement. */
    private fun longestMotionGap(writeMs: Long, periodic: Boolean, inPlace: Boolean = true): Long {
        val queue = mutableListOf<Pending>()
        var inFlight: Pending? = null
        var endAt = 0L
        var lastMotion = 0L
        var longest = 0L
        var nextKeepAlive = 0L
        var batch = 0
        fun enqueue(label: String, setpoint: MotionSetpoints.Setpoint?, isPeriodic: Boolean = false) {
            if (isPeriodic && periodic && MotionSetpoints.periodicPending(queue, inFlight, label) { it.label }) return
            val item = Pending(label, setpoint, ++batch)
            if (inPlace) MotionSetpoints.enqueue(queue, listOf(item), setpoint, { it.setpoint })
            else { MotionSetpoints.coalesce(queue, setpoint, { it.setpoint }); queue.add(item) }
        }
        for (t in 0L..10_000L step 10) {
            if (inFlight != null && t >= endAt) {
                if (inFlight.setpoint != null) { longest = maxOf(longest, t - lastMotion); lastMotion = t }
                inFlight = null
            }
            if (t % 200 == 0L) {
                // Go-to-point loop: maintenance every 500 ms, then motion setpoint.
                if (t >= nextKeepAlive) {
                    enqueue("NAV_ERROR_QUERY", null, isPeriodic = true)
                    enqueue("NAV_BUMPER_KEEPALIVE_OFF", null, isPeriodic = true)
                    nextKeepAlive = t + 500
                }
                enqueue("NAV_MAP_MOVE", MotionSetpoints.Setpoint(330, 0))
            }
            if (inFlight == null && queue.isNotEmpty()) { inFlight = queue.removeAt(0); endAt = t + writeMs }
        }
        return maxOf(longest, 10_000L - lastMotion)
    }

    @Test fun `2026-10-13 scenario motion setpoints still written with a slow gateway`() {
        // ~400 ms writes via the gateway: without throttling, maintenance commands took the whole link
        // and no setpoint was written any more (robot stopping every 30 cm).
        // Before: setpoint replaced then put back at the end of the queue, maintenance re-sent without throttling.
        assertTrue(longestMotionGap(writeMs = 400, periodic = false, inPlace = false) > 3000)
        assertTrue(longestMotionGap(writeMs = 400, periodic = true, inPlace = false) > 3000)
        val gap = longestMotionGap(writeMs = 400, periodic = true)
        assertTrue("interval $gap ms", gap <= 1200)
        // Normal link: one setpoint per loop iteration or nearly so.
        assertTrue(longestMotionGap(writeMs = 50, periodic = true) <= 400)
    }

    @Test fun `setpoint replaced in place in the queue, not behind commands added since`() {
        val queue = mutableListOf(
            Pending("NAV_MAP_MOVE", MotionSetpoints.Setpoint(300, 0), 1),
            Pending("NAV_ERROR_QUERY", null, 2),
            Pending("NAV_BUMPER_KEEPALIVE_OFF", null, 3))
        val removed = MotionSetpoints.enqueue(queue, listOf(Pending("NAV_MAP_MOVE", MotionSetpoints.Setpoint(320, 10), 4)),
            MotionSetpoints.Setpoint(320, 10), { it.setpoint })
        assertEquals(1, removed)
        assertEquals(listOf(4, 2, 3), queue.map { it.batch })
        // Nothing to replace (or a pending stop, never removed): appended at the end of the queue.
        val stop = mutableListOf(Pending("STOP", MotionSetpoints.Setpoint(0, 0), 5), Pending("sync(2)", null, 6))
        MotionSetpoints.enqueue(stop, listOf(Pending("NAV_MAP_MOVE", MotionSetpoints.Setpoint(100, 0), 7)), MotionSetpoints.Setpoint(100, 0), { it.setpoint })
        assertEquals(listOf(5, 6, 7), stop.map { it.batch })
        // Stop pending behind the replaced setpoint: the new setpoint stays after the stop.
        val settle = mutableListOf(Pending("NAV_MAP_MOVE", MotionSetpoints.Setpoint(300, 0), 8), Pending("NAV_SETTLE", MotionSetpoints.Setpoint(0, 0), 9))
        MotionSetpoints.enqueue(settle, listOf(Pending("NAV_MAP_MOVE", MotionSetpoints.Setpoint(310, 0), 10)), MotionSetpoints.Setpoint(310, 0), { it.setpoint })
        assertEquals(listOf(9, 10), settle.map { it.batch })
    }
}
