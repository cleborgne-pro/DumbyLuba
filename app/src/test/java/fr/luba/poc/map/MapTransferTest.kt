package fr.luba.poc.map

import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.CommonProto.CommDataCouple
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*
import org.junit.Assert.*
import org.junit.Test

/** Map reading that keeps going: drawings, silent elements, stray frames (tester export, app 1.15.0, 48 elements). */
class MapTransferTest {
    private val c = Commands()
    private var now = 0L
    private fun transfer() = MapTransfer(3, requestTimeoutMs = 10_000, frameTimeoutMs = 8_000, maxAttempts = 3, clock = { now })
    private fun msg(n: MctlNav.Builder) = LubaMsg.newBuilder().setNav(n).build()
    private fun roots(vararg hashes: Long) = msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setPver(1).setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(hashes.toList())))
    private fun square() = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).map { (x, y) -> CommDataCouple.newBuilder().setX(x).setY(y).build() }
    private fun boundary(hash: Long, type: Int, points: List<CommDataCouple> = square(), result: Int = 0) =
        msg(MctlNav.newBuilder().setToappGetCommondataAck(NavGetCommDataAck.newBuilder().setPver(1).setAction(8).setType(type).setHash(hash)
            .setTotalFrame(1).setCurrentFrame(1).setResult(result).addAllDataCouple(points)))
    private fun drawing(hash: Long, parent: Long, frame: Int, total: Int) =
        msg(MctlNav.newBuilder().setToappSvgMsg(svg_message_ack_t.newBuilder().setPver(1).setSubCmd(1).setDataHash(hash).setPaternalHashA(parent)
            .setTotalFrame(total).setCurrentFrame(frame).setSvgMessage(svg_message_t.newBuilder().setSvgFileName("pattern.svg"))))
    private fun requested(out: List<ByteArray>) = out.map { LubaMsg.parseFrom(it) }.filter { it.hasNav() && it.nav.hasTodevGetCommondata() && it.nav.todevGetCommondata.subCmd == 1 }.map { it.nav.todevGetCommondata.hash }
    private fun svgAcks(out: List<ByteArray>) = out.map { LubaMsg.parseFrom(it) }.filter { it.hasNav() && it.nav.hasTodevSvgMsg() }.map { it.nav.todevSvgMsg }

    @Test fun `drawing element is acknowledged and the reading goes on, as in the tester export`() {
        // Export: zone, two no-go zones, then a drawing (toapp_svg_msg) repeated every second because never echoed.
        val t = transfer()
        assertEquals(listOf(10L), requested(t.accept(roots(10, 11, 12, 13, 14), c)))
        assertEquals(listOf(11L), requested(t.accept(boundary(10, 0), c)))
        assertEquals(listOf(12L), requested(t.accept(boundary(11, 1), c)))
        assertEquals(listOf(13L), requested(t.accept(boundary(12, 1), c)))
        val first = t.accept(drawing(13, 10, 1, 2), c)
        val ack = svgAcks(first).single()
        assertEquals(2, ack.subCmd); assertEquals(13L, ack.dataHash); assertEquals(10L, ack.paternalHashA); assertEquals(1, ack.currentFrame); assertEquals(2, ack.totalFrame)
        assertFalse(ack.hasSvgMessage())
        assertTrue(requested(first).isEmpty())
        // Repeated frame (ack not heard): echoed again, no progress counted twice.
        assertEquals(1, svgAcks(t.accept(drawing(13, 10, 1, 2), c)).size)
        assertEquals(listOf(14L), requested(t.accept(drawing(13, 10, 2, 2), c)))
        t.accept(boundary(14, 0), c)
        val map = t.snapshot()
        assertTrue(map.source, map.complete)
        assertTrue(t.finished)
        assertEquals(listOf(10L, 11L, 12L, 14L), map.shapes.map { it.id }.sorted())
        assertEquals(5, t.read)
    }

    @Test fun `drawing answered on the boundary channel is done without geometry`() {
        val t = transfer()
        t.accept(roots(1, 2), c); t.accept(boundary(1, 0), c)
        assertTrue(t.accept(boundary(2, MapTransfer.DRAWING_TYPE, emptyList()), c).isNotEmpty())
        assertTrue(t.snapshot().complete)
        assertEquals(1, t.snapshot().shapes.size)
    }

    @Test fun `silent element is asked again then left out, the reading ends incomplete`() {
        val t = transfer()
        t.accept(roots(1, 2, 3), c)
        now = 9_999; assertTrue(t.tick(c).isEmpty())
        now = 10_000; assertEquals(listOf(1L), requested(t.tick(c)))
        now = 20_000; assertEquals(listOf(1L), requested(t.tick(c)))
        now = 30_000; assertEquals(listOf(2L), requested(t.tick(c)))
        t.accept(boundary(2, 0), c); t.accept(boundary(3, 1), c)
        assertTrue(t.finished)
        assertFalse(t.snapshot().complete)
        assertEquals(1, t.missing)
        assertTrue(t.notices().any { it.kind == "map_element_abandoned" && it.fields["hash"] == 1L })
        // A late answer of the left-out element changes nothing.
        t.accept(boundary(1, 0), c)
        assertFalse(t.snapshot().complete)
    }

    @Test fun `frame of another element is echoed and does not end the current one`() {
        val t = transfer()
        t.accept(roots(1, 2), c)
        val stray = t.accept(boundary(99, 18), c)
        assertEquals(1, stray.size)
        assertEquals(2, LubaMsg.parseFrom(stray.single()).nav.todevGetCommondata.subCmd)
        assertFalse(t.snapshot().complete)
        // Refused answer: echoed, not stored, asked again later.
        assertEquals(1, t.accept(boundary(1, 0, result = 1), c).size)
        assertTrue(t.snapshot().shapes.isEmpty())
        now = 10_000; assertEquals(listOf(1L), requested(t.tick(c)))
    }

    @Test fun `element answered empty does not hide a boundary`() {
        val t = transfer()
        t.accept(roots(1, 2), c)
        assertEquals(listOf(2L), requested(t.accept(boundary(1, 1, emptyList()), c)))
        t.accept(boundary(2, 0), c)
        assertTrue(t.finished)
        assertFalse(t.snapshot().complete)
        assertEquals(1, t.missing)
    }

    @Test fun `element list asked again, then the reading fails`() {
        val t = transfer()
        now = 10_000; assertTrue(t.tick(c).any { LubaMsg.parseFrom(it).nav.hasTodevGethash() })
        now = 20_000; assertTrue(t.tick(c).isNotEmpty())
        now = 30_000; assertTrue(t.tick(c).isEmpty())
        assertTrue(t.finished)
        assertNotNull(t.failure)
    }

    @Test fun `map acknowledgements stay within the read-only barriers`() {
        val t = transfer()
        t.accept(roots(1, 2), c)
        val acks = t.accept(drawing(1, 0, 1, 1), c) + t.accept(boundary(2, 0), c)
        acks.forEach { assertTrue(fr.luba.poc.protocol.ReadOnlyPolicy.allows(it)); fr.luba.poc.auth.CloudReadOnly.requireAllowed(it) }
        val svg = svgAcks(acks).single()
        assertFalse(svg.hasSvgMessage())
    }
}

/** Every PyMammotion element type has its kind; any other type is kept and never blocks the reading. */
class MapElementTypesTest {
    private val c = Commands()
    private fun msg(n: MctlNav.Builder) = LubaMsg.newBuilder().setNav(n).build()
    private fun read(vararg elements: Pair<Int, Int>): MapTransfer {
        // (type, number of points) per element, hashes 1..n.
        val t = MapTransfer(3, clock = { 0L })
        t.accept(msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setTotalFrame(1).setCurrentFrame(1).addAllDataCouple((1L..elements.size).toList()))), c)
        elements.forEachIndexed { i, (type, points) ->
            val f = NavGetCommDataAck.newBuilder().setAction(8).setType(type).setHash(i + 1L).setTotalFrame(1).setCurrentFrame(1)
            listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f).take(points).forEach { (x, y) -> f.addDataCouple(CommDataCouple.newBuilder().setX(x).setY(y)) }
            t.accept(msg(MctlNav.newBuilder().setToappGetCommondataAck(f)), c)
        }
        return t
    }

    @Test fun `every PyMammotion type has its kind`() {
        // PyMammotion PathType (hash_list.py, commit c0e5ada).
        val expected = mapOf(0 to ShapeKind.ZONE, 1 to ShapeKind.NO_GO, 2 to ShapeKind.PASSAGE, 3 to ShapeKind.TRANSFER_ZONE, 10 to ShapeKind.MOW_LINE,
            12 to ShapeKind.DUMP, 13 to ShapeKind.DRAWING, 18 to ShapeKind.MOW_PROGRESS, 19 to ShapeKind.CORRIDOR, 20 to ShapeKind.CORRIDOR,
            21 to ShapeKind.VIRTUAL_WALL, 22 to ShapeKind.NO_GO_VARIANT, 23 to ShapeKind.NO_GO_RECT, 25 to ShapeKind.NO_STOP_ZONE, 26 to ShapeKind.VISION_ZONE)
        expected.forEach { (type, kind) -> assertEquals("type $type", kind, ShapeKind.from(type)) }
        assertEquals(expected.keys, ShapeKind.SUPPORTED_TYPES)
        listOf(-1, 4, 24, 27, 999).forEach { assertEquals(ShapeKind.UNKNOWN, ShapeKind.from(it)) }
    }

    @Test fun `unknown types complete the map, with or without points, and refuse planning and moves`() {
        val t = read(0 to 4, 99 to 4, 24 to 2, 150 to 0)
        val map = t.snapshot()
        assertTrue(map.source, map.complete)
        assertEquals(4, map.shapes.size)
        assertTrue(map.source, map.source.contains("24, 99, 150"))
        assertNotNull(map.planningRefusal())
        assertNotNull(map.movesRefusal())
        // The closed unknown outline is avoided by the moves.
        assertTrue(map.shapes.first { it.type == 99 }.kind.avoidedByMoves)
    }

    @Test fun `every known type completes the map`() {
        val t = read(0 to 4, 1 to 4, 2 to 2, 3 to 4, 10 to 2, 12 to 1, 13 to 0, 18 to 2, 19 to 2, 20 to 1, 21 to 2, 22 to 4, 23 to 4, 25 to 4, 26 to 4)
        val map = t.snapshot()
        assertTrue(map.source, map.complete)
        assertFalse(map.source, map.source.contains("inconnu") || map.source.contains("unknown"))
        assertEquals(14, map.shapes.size) // the empty drawing has no shape
        assertTrue(map.shapes.first { it.type == 23 }.excludes)
        val refusal = map.planningRefusal()!!
        listOf("(21)", "(22)", "(26)").forEach { assertTrue(refusal, refusal.contains(it)) }
        listOf("(23)", "(25)", "(3)", "(12)").forEach { assertFalse(refusal, refusal.contains(it)) }
        assertTrue(map.movesRefusal()!!.contains("(21)"))
    }

    @Test fun `informational and empty known elements`() {
        assertTrue(read(0 to 4, 12 to 0, 18 to 0).snapshot().complete)
        // Rectangular no-go zone without points: kept, the map completes, planning and moves refused.
        val rect = read(0 to 4, 23 to 0).snapshot()
        assertTrue(rect.complete)
        assertNotNull(rect.planningRefusal()); assertNotNull(rect.movesRefusal())
        // Zone without points: map incomplete.
        assertFalse(read(0 to 4, 0 to 0).snapshot().complete)
    }

    @Test fun `refused element list frame is asked again instead of ending the reading`() {
        var now = 0L
        val t = MapTransfer(3, requestTimeoutMs = 10_000, clock = { now })
        t.accept(msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setResult(1).setTotalFrame(1).setCurrentFrame(1))), c)
        t.accept(msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setTotalFrame(0).setCurrentFrame(0))), c)
        assertNull(t.failure)
        now = 10_000
        assertTrue(t.tick(c).any { LubaMsg.parseFrom(it).nav.hasTodevGethash() })
    }
}
