package fr.luba.poc.mowing

import fr.luba.poc.proto.CommonProto.CommDataCouple
import fr.luba.poc.map.Point
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*
import fr.luba.poc.protocol.Commands
import org.junit.Assert.*
import org.junit.Test

class MowPathTransferTest {
    private fun roots(hashes: List<Long>) = LubaMsg.newBuilder().setNav(MctlNav.newBuilder()
        .setToappGethashAck(NavGetHashListAck.newBuilder().setSubCmd(3).setTotalFrame(1)
            .setCurrentFrame(1).addAllDataCouple(hashes))).build()
    private fun frame(transaction: Long, number: Int, total: Int, packet: cover_path_packet_t) =
        LubaMsg.newBuilder().setNav(MctlNav.newBuilder().setCoverPathUpload(cover_path_upload_t.newBuilder()
            .setTransactionId(transaction).setTotalFrame(total).setCurrentFrame(number).addPathPackets(packet))).build()
    private fun packet(hash: Long, index: Int, total: Int, x: Float) = cover_path_packet_t.newBuilder()
        .setPathHash(hash).setPathCur(index).setPathTotal(total)
        .addDataCouple(CommDataCouple.newBuilder().setX(x).setY(2f)).build()
    @Test fun collectsOnlyCurrentTransactionInPacketOrderWithoutJoiningLines() {
        val c = Commands(); val t = MowPathTransfer(100)
        assertEquals(3, LubaMsg.parseFrom(t.start(c).last()).nav.todevGethash.subCmd)
        val requests = t.accept(roots(listOf(9, 0)), c)
        val request = LubaMsg.parseFrom(requests.last()).nav.appRequestCoverPaths
        assertEquals(listOf(9L), request.hashListList)
        assertEquals(101L, request.transactionId)
        t.accept(frame(99, 1, 1, packet(9, 1, 1, 100f)), c)
        assertTrue(t.paths().isEmpty())
        t.accept(frame(101, 2, 2, packet(9, 2, 2, 4f)), c)
        assertFalse(t.complete); assertTrue(t.paths().isEmpty())
        t.accept(frame(101, 1, 2, packet(9, 1, 2, 3f)), c)
        assertTrue(t.complete)
        assertEquals(listOf(listOf(Point(3.0,2.0),Point(4.0,2.0))), t.paths())
    }
    @Test fun rejectsIncompletePacketsAndUnexpectedHashes() {
        val c = Commands(); val t = MowPathTransfer(100)
        t.accept(roots(listOf(9)),c)
        assertThrows(IllegalArgumentException::class.java) { t.accept(frame(101,1,1,packet(10,1,1,3f)),c) }
        assertThrows(IllegalArgumentException::class.java) { t.accept(frame(101,1,1,packet(9,2,2,3f)),c) }
        assertTrue(t.paths().isEmpty())
    }
}
