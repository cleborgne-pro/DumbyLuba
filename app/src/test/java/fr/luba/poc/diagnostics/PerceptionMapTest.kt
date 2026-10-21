package fr.luba.poc.diagnostics

import com.google.protobuf.ByteString
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.LubaMsgProto.MsgCmdType
import fr.luba.poc.proto.LubaMsgProto.MsgDevice
import fr.luba.poc.proto.MctrlPeptProto.MctlPept
import fr.luba.poc.proto.MctrlPeptProto.perception_local_map_visualization_t
import fr.luba.poc.proto.MctrlPeptProto.perception_obstacles_visualization_t
import org.junit.Assert.*
import org.junit.Test

class PerceptionMapTest {
    /** Map received from the LUBA 2 on 2026-10-20 at 19:21:49, 1.4 s after the official return to base was requested. */
    private val real = hex("5249464620000000574542505650384c140000002f3c800700071011fd0f0009e1ff7935a2ffa90d")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun map(bytes: ByteArray) = LubaMsg.newBuilder().setMsgtype(MsgCmdType.MSG_CMD_TYPE_PEPT)
        .setSender(MsgDevice.DEV_PERCEPTION).setRcver(MsgDevice.DEV_MOBILEAPP)
        .setPept(MctlPept.newBuilder().setPerceptionLocalMapVisualization(perception_local_map_visualization_t.newBuilder().setMap(ByteString.copyFrom(bytes))))
        .build()

    @Test fun `real robot map is a 61 x 31 lossless WebP`() {
        val info = PerceptionImage.info(real)
        assertEquals("WebP lossless", info.format)
        assertEquals(61, info.width)
        assertEquals(31, info.height)
        assertEquals(40, info.bytes)
    }

    @Test fun `other headers`() {
        val png = hex("89504e470d0a1a0a0000000d49484452") + byteArrayOf(0, 0, 0, 0x3d, 0, 0, 0, 0x1f)
        assertEquals(PerceptionImage.Info("PNG", 61, 31, 24), PerceptionImage.info(png))
        // Extended WebP: canvas 100 × 50 (stored as width-1, height-1 on 24 bits).
        val vp8x = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(8) + byteArrayOf(99, 0, 0, 49, 0, 0)
        assertEquals(100, PerceptionImage.info(vp8x).width); assertEquals(50, PerceptionImage.info(vp8x).height)
        assertEquals("unknown", PerceptionImage.info(byteArrayOf(1, 2, 3)).format)
        assertEquals("empty", PerceptionImage.info(ByteArray(0)).format)
        assertNull(PerceptionImage.info("RIFF".toByteArray() + ByteArray(4) + "WEBPVP8L".toByteArray()).width)
    }

    @Test fun `observer keeps maps only`() {
        val observer = PerceptionMapObserver()
        val obstacles = LubaMsg.newBuilder().setPept(MctlPept.newBuilder().setPerceptionObstaclesVisualization(perception_obstacles_visualization_t.newBuilder().setNum(1))).build()
        assertNull(observer.accept(obstacles, 1, 14))
        assertNull(observer.accept(LubaMsg.getDefaultInstance(), 2, 14))
        val first = requireNotNull(observer.accept(map(real), 3, 14))
        assertEquals(1L, first.count); assertEquals(14, first.robotState); assertArrayEquals(real, first.image)
        val second = requireNotNull(observer.accept(map(real), 4, 11))
        assertEquals(2L, second.count); assertSame(second, observer.last); assertNotEquals(first, second)
    }

    @Test fun `oversized map is counted but not kept`() {
        val frame = requireNotNull(PerceptionMapObserver().accept(map(ByteArray(PerceptionMapObserver.MAX_BYTES + 1)), 1, null))
        assertEquals(0, frame.image.size)
        assertEquals("too large", frame.info.format)
        assertEquals(PerceptionMapObserver.MAX_BYTES + 1, frame.info.bytes)
    }
}
