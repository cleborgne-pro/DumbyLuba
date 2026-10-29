package fr.luba.poc.gateway

import fr.luba.poc.map.Point
import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android gateway docking without a pilot ([GatewayRobot] `dockAlone`, the only navigation message the gateway builds
 * itself): official return addressed to the navigation receiver of the kept robot's model (DumbyLuba#3). LUBA 2: the
 * same frames as before the change (`Commands()`), byte for byte once the clock timestamp is fixed.
 */
class GatewayNavReceiverTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val links = LinkedBlockingQueue<FakeRobotLink>()
    private val reports = CopyOnWriteArrayList<String>()
    private var robot: GatewayRobot? = null

    @After fun close() { robot?.shutdown() }

    private fun docked(name: String): Pair<String, List<LubaMsg>> {
        val gateway = GatewayRobot({ _, listener ->
            FakeRobotLink(listener, 0.0, 0.0, 0.0).also { link ->
                links.offer(link); link.start()
                Thread { Thread.sleep(20); listener.onDiscovering(link); listener.onReady(link, 247) }.start()
            }
        }, { reports += it }, keepaliveMs = 60_000).also { robot = it }
        gateway.connect(address, name)
        val link = requireNotNull(links.poll(8, TimeUnit.SECONDS)) { "robot link opened" }
        // First position received (1 Hz) by the gateway's observer.
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline && !(gateway.state.ready && link.received.isNotEmpty())) Thread.sleep(50)
        Thread.sleep(2200)
        val start = link.received.size
        val dock = GatewayRobot::class.java.getDeclaredMethod("dockAlone", Point::class.java).apply { isAccessible = true }
        val result = dock.invoke(gateway, Point(0.0, 0.0)) as String
        return result to link.received.drop(start)
    }

    private fun normalized(m: LubaMsg) = m.toBuilder().setTimestamp(0).build().toByteArray().joinToString("") { "%02x".format(it) }

    @Test(timeout = 60_000) fun luba2DockingFramesUnchanged() {
        val (result, sent) = docked("Luba-VS123")
        assertTrue(result, result.startsWith(fr.luba.poc.autonomy.OrphanDock.RESULT_PREFIX))
        val returns = sent.filter { it.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV }
        assertEquals(1, returns.size)
        assertEquals(MsgDevice.DEV_NAVIGATION, returns.single().rcver)
        // Same sequence as before the change: a fresh Commands() for reports, manual mode off, stop, official return.
        val before = Commands()
        val expected = listOf(before.reports(true, periodMs = 200), before.manualMow(0, 0, 60), before.stop(), before.returnToDock()).map { LubaMsg.parseFrom(it) }
        val own = sent.filter { it.msgtype != MsgCmdType.MSG_CMD_TYPE_ESP }.takeLast(4)
        assertEquals(expected.map(::normalized), own.map(::normalized))
    }

    @Test(timeout = 60_000) fun luba1DockingReturnAddressedToTheMainController() {
        val (result, sent) = docked("Luba-ABC123")
        assertTrue(result, result.startsWith(fr.luba.poc.autonomy.OrphanDock.RESULT_PREFIX))
        val returns = sent.filter { it.msgtype == MsgCmdType.MSG_CMD_TYPE_NAV }
        assertEquals(1, returns.size)
        assertEquals(MsgDevice.DEV_MAINCTL, returns.single().rcver)
        assertEquals(5, returns.single().nav.todevTaskctrl.action)
    }
}
