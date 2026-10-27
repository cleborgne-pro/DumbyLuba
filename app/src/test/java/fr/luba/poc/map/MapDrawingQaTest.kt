package fr.luba.poc.map

import fr.luba.poc.planning.CoverageEngine
import fr.luba.poc.planning.PlanningConfig
import fr.luba.poc.planning.PlanningRequest
import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.CommonProto.CommDataCouple
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*
import fr.luba.poc.simulation.TrajectoryPlanner
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val QA_SQUARE = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><path d=\"M10 10H90V90H10Z\" fill=\"#111\"/></svg>"

private fun close(expected: Point, actual: Point, tolerance: Double = 1e-9) =
    assertTrue("$actual instead of $expected", abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance)

/** Runs [block] and fails when it takes longer than [maxMs] (hostile inputs must stay cheap). */
private fun <T> within(maxMs: Long, label: String, block: () -> T): T {
    val start = System.nanoTime()
    val result = block()
    val ms = (System.nanoTime() - start) / 1_000_000
    assertTrue("$label took $ms ms (limit $maxMs ms)", ms <= maxMs)
    return result
}

/** Placement contract of the drawings, checked against the formula written independently of MapDrawing. */
class MapDrawingContractQaTest {
    /** Contract formula: tile centered on (xMove, yMove), stretched viewBox, Y flipped, counterclockwise rotation. */
    private fun expected(u: Double, v: Double, box: ViewBox, cx: Double, cy: Double, w: Double, h: Double, theta: Double): Point {
        val a = ((u - box.x) / box.width - 0.5) * w
        val b = (0.5 - (v - box.y) / box.height) * h
        return Point(cx + a * cos(theta) - b * sin(theta), cy + a * sin(theta) + b * cos(theta))
    }

    @Test fun `published example at rotate 0 and rotate pi over 2`() {
        val flat = MapDrawing.of(1, 2, 5.0, 3.0, 1.0, 0.0, 4.0, 4.0, 0, 0, "", QA_SQUARE, false)!!
        val pts = flat.outline.shapes.single().paths.single().points
        assertEquals(3.4, pts.minOf { it.x }, 1e-9); assertEquals(6.6, pts.maxOf { it.x }, 1e-9)
        assertEquals(1.4, pts.minOf { it.y }, 1e-9); assertEquals(4.6, pts.maxOf { it.y }, 1e-9)
        val turned = MapDrawing.of(1, 2, 5.0, 3.0, 1.0, PI / 2, 4.0, 4.0, 0, 0, "", QA_SQUARE, false)!!
        // First point of the path is the SVG corner (10, 10).
        close(Point(3.4, 1.4), turned.outline.shapes.single().paths.single().points.first())
    }

    @Test fun `transform follows the contract for any viewBox, scale and rotation`() {
        val boxes = listOf(ViewBox(0.0, 0.0, 100.0, 100.0), ViewBox(-20.0, 15.0, 300.0, 60.0), ViewBox(7.5, -3.0, 0.5, 2.0))
        val angles = listOf(0.0, 0.3, PI / 2, 2.5, -1.2, 7.0)
        for (box in boxes) for (theta in angles) for (scale in listOf(0.0, 0.5, 2.5)) {
            val d = MapDrawing.of(1, 2, -12.0, 40.5, scale, theta, 3.0, 1.5, 0, 0, "", "", false)!!
            val s = if (scale > 0) scale else 1.0
            assertEquals(3.0 * s, d.widthM, 1e-12); assertEquals(1.5 * s, d.heightM, 1e-12)
            val m = d.transform(box)
            for ((u, v) in listOf(box.x to box.y, box.x + box.width to box.y + box.height, box.x + box.width * 0.3 to box.y + box.height * 0.8)) {
                close(expected(u, v, box, -12.0, 40.5, d.widthM, d.heightM, theta), m.apply(u, v), 1e-9)
            }
        }
    }

    @Test fun `non square viewBox is stretched without keeping the aspect ratio`() {
        val svg = "<svg viewBox=\"0 0 200 50\"><rect x=\"0\" y=\"0\" width=\"200\" height=\"50\"/></svg>"
        val d = MapDrawing.of(1, 2, 0.0, 0.0, 1.0, 0.0, 4.0, 4.0, 0, 0, "", svg, false)!!
        val pts = d.outline.shapes.single().paths.single().points
        assertEquals(-2.0, pts.minOf { it.x }, 1e-9); assertEquals(2.0, pts.maxOf { it.x }, 1e-9)
        assertEquals(-2.0, pts.minOf { it.y }, 1e-9); assertEquals(2.0, pts.maxOf { it.y }, 1e-9)
        // SVG top-left (0, 0) is the north-west corner of the tile.
        close(Point(-2.0, 2.0), pts.first())
    }

    @Test fun `unusable placements are refused`() {
        for (args in listOf(
            doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 1.0, 0.0, 4.0, 4.0),
            doubleArrayOf(0.0, 0.0, 1.0, Double.NaN, 4.0, 4.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, -4.0, 4.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, 4.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, 501.0, 4.0),
            doubleArrayOf(0.0, 0.0, 200.0, 0.0, 4.0, 4.0),
        )) assertNull(args.joinToString(), MapDrawing.of(1, 2, args[0], args[1], args[2], args[3], args[4], args[5], 0, 0, "", QA_SQUARE, false))
        // Non-finite scale means 1, like an unset one.
        assertEquals(4.0, MapDrawing.of(1, 2, 0.0, 0.0, Double.NaN, 0.0, 4.0, 4.0, 0, 0, "", "", false)!!.widthM, 0.0)
        assertEquals(4.0, MapDrawing.of(1, 2, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0, 4.0, 4.0, 0, 0, "", "", false)!!.widthM, 0.0)
    }
}

/** SVG reader: hostile inputs, relative commands, fill none and evenodd. */
class SvgOutlineQaTest {
    private fun read(svg: String) = SvgOutline.parse(svg) { Affine() }
    private fun svg(body: String) = "<svg viewBox=\"0 0 100 100\">$body</svg>"
    private fun points(svg: String) = read(svg).shapes.flatMap { s -> s.paths.flatMap { it.points } }

    @Test fun `hostile inputs never throw and stay fast`() {
        val inputs = listOf(
            "not xml at all", "\u0000\u0001￿<<<>>>", "<", "<svg", "<svg viewBox=\"0 0 10 10\"", "<svg viewBox=\"0 0 10 10\"><g><path d=\"M0 0L5 5L5 0\"",
            "<svg viewBox=\"0 0 10 10\"><path d=\"M0 0L5 5L5 0Z\"></g></g></svg></svg>", "<svg viewBox='0 0 10 10'><path d='M0 0L5 5", "<svg><path d=\"M0 0 L1e400 0 L5 5Z\"/></svg>",
            "<svg viewBox=\"0 0 1e400 10\"><rect width=\"1e400\" height=\"5\"/></svg>", "<svg viewBox=\"0 0 10 10\"><path d=\"M NaN NaN L 5 5\"/></svg>",
            "<svg viewBox=\"0 0 10 10\"><path d=\"M0 0A0 5 0 0 1 10 0A5 0 0 1 1 0 10\"/></svg>", "<svg viewBox=\"0 0 10 10\"><path d=\"M0 0A1e300 1e300 0 1 1 10 0\"/></svg>",
            "<svg viewBox=\"0 0 1e-320 1e-320\"><rect width=\"1\" height=\"1\"/></svg>", "<svg viewBox=\"0 0 10 10\"><circle r=\"1e308\"/><ellipse rx=\"1e308\" ry=\"1e308\"/></svg>",
            "<!DOCTYPE svg [<!ENTITY x \"<svg>\">]><svg viewBox=\"0 0 10 10\"><rect width=\"1\" height=\"1\"/></svg>", "<svg viewBox=\"0 0 10 10\"><path d=\"M0 0 L5\"/><path d=\"\"/><path/></svg>",
            "<svg viewBox=\"0 0 10 10\"><path d=\"Z Z M0 0 Z 1 2\"/><polygon points=\"1\"/><polyline points=\"a b c\"/></svg>",
        )
        for (text in inputs) within(2_000, text.take(40)) {
            val outline = read(text)
            outline.shapes.forEach { s -> s.paths.forEach { p -> assertTrue(text, p.points.all { it.x.isFinite() && it.y.isFinite() }) } }
        }
    }

    @Test fun `out of range numbers end the path, they never become infinite points`() {
        assertEquals(listOf(Point(0.0, 0.0), Point(5.0, 0.0)), points(svg("<path d=\"M0 0L5 0L1e400 0L9 9\"/>")))
        assertTrue(points(svg("<path d=\"MNaN 0L5 5\"/>")).isEmpty())
        // viewBox out of range: ignored, the frame then comes from the content.
        var seen: ViewBox? = null
        SvgOutline.parse("<svg viewBox=\"0 0 1e400 10\"><rect x=\"1\" y=\"1\" width=\"2\" height=\"3\"/></svg>") { seen = it; Affine() }
        assertEquals(ViewBox(1.0, 1.0, 2.0, 3.0), seen)
    }

    @Test fun `arc with a zero radius is a straight line`() {
        assertEquals(listOf(Point(0.0, 0.0), Point(10.0, 0.0)), points(svg("<path d=\"M0 0A0 5 0 0 1 10 0\"/>")))
        assertEquals(listOf(Point(0.0, 0.0), Point(10.0, 0.0)), points(svg("<path d=\"M0 0a5 0 0 0 0 10 0\"/>")))
    }

    @Test fun `invalid transforms count as none`() {
        for (t in listOf("rotate(", "matrix(1 2 3)", "translate(1e400)", "scale(NaN)", "translate(1 2 3)", "skewX()", "rotate(1 2)", "foo(1)", "translate(5) )(", "translate(1,2) junk")) {
            close(Point(1.0, 2.0), points(svg("<line x1=\"1\" y1=\"2\" x2=\"3\" y2=\"4\" transform=\"$t\"/>")).first())
        }
        // Unreadable transform on a group: the child keeps its own, the group one is dropped.
        close(Point(11.0, 2.0), points(svg("<g transform=\"wobble\"><line x1=\"1\" y1=\"2\" x2=\"3\" y2=\"4\" transform=\"translate(10)\"/></g>")).first())
    }

    @Test fun `very long path is given up quickly`() {
        val d = StringBuilder("M0 0")
        while (d.length < SvgOutline.MAX_SVG_CHARS - 200) d.append(" L1 1")
        val outline = within(2_000, "long path") { read(svg("<path d=\"$d\"/>")) }
        assertTrue(outline.truncated); assertTrue(outline.shapes.isEmpty())
        // Long but within the point limit: read.
        val ok = (1..5000).joinToString(" ") { "L$it ${it % 3}" }
        assertEquals(5001, within(2_000, "5000 segments") { points(svg("<path d=\"M0 0 $ok\"/>")) }.size)
    }

    @Test fun `deep nesting is read without recursion and stays fast`() {
        val depth = 30_000
        val text = "<svg viewBox=\"0 0 10 10\">" + "<g>".repeat(depth) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(depth) + "<rect width=\"2\" height=\"2\"/></svg>"
        assertTrue(text.length <= SvgOutline.MAX_SVG_CHARS)
        val outline = within(3_000, "deep nesting") { read(text) }
        // Beyond the depth limit the content is skipped; once the groups are closed the next element is read.
        assertEquals(listOf(Point(0.0, 0.0), Point(2.0, 0.0), Point(2.0, 2.0), Point(0.0, 2.0), Point(0.0, 0.0)), outline.shapes.single().paths.single().points)
    }

    @Test fun `many unclosed groups followed by stray closing tags stay fast`() {
        // Each stray closing tag looks for a matching open element.
        val n = 36_000
        val text = "<svg viewBox=\"0 0 10 10\">" + "<g>".repeat(n) + "</x>".repeat(n) + "</svg>"
        assertTrue(text.length <= SvgOutline.MAX_SVG_CHARS)
        within(3_000, "stray closing tags") { read(text) }
    }

    private fun square(x: Double, size: Double) = listOf(Point(x, 0.0), Point(x + size, 0.0), Point(x + size, size), Point(x, size), Point(x, 0.0))
    private val shift = "<g transform=\"translate(1 0)\">"

    @Test fun `nesting just under the depth limit keeps the cumulated transform`() {
        // The root svg counts as one level: 250 and 254 groups are read with every translation applied.
        for (groups in listOf(250, 254)) {
            val outline = read(svg(shift.repeat(groups) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(groups)))
            outline.shapes.single().paths.single().points.zip(square(groups.toDouble(), 1.0)).forEach { (a, e) -> close(e, a) }
        }
        // One more level reaches the limit: the element is skipped, as before the bounded stack.
        assertTrue(points(svg(shift.repeat(255) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(255))).isEmpty())
    }

    @Test fun `content beyond the depth limit is skipped and the elements after the correct closings are read`() {
        // Skipped content with its own open and closed elements, self-closing ones and other names.
        val deep = "<g><path d=\"M0 0H9V9Z\"></path><rect width=\"9\" height=\"9\"/><circle r=\"3\"></circle></g>"
        val text = svg(shift.repeat(300) + deep + "</g>".repeat(300) +
            "<rect width=\"2\" height=\"2\"/><g transform=\"translate(5 0)\"><rect width=\"1\" height=\"1\"/></g>")
        val shapes = read(text).shapes
        assertEquals(2, shapes.size)
        shapes[0].paths.single().points.zip(square(0.0, 2.0)).forEach { (a, e) -> close(e, a) }
        shapes[1].paths.single().points.zip(square(5.0, 1.0)).forEach { (a, e) -> close(e, a) }
        // Partial closing: back to 200 groups, the next element is read with those 200 translations only.
        val partial = svg(shift.repeat(300) + "</g>".repeat(100) + "<rect width=\"1\" height=\"1\"/>" + "</g>".repeat(200) + "<rect width=\"3\" height=\"3\"/>")
        val back = read(partial).shapes
        assertEquals(2, back.size)
        back[0].paths.single().points.zip(square(200.0, 1.0)).forEach { (a, e) -> close(e, a) }
        back[1].paths.single().points.zip(square(0.0, 3.0)).forEach { (a, e) -> close(e, a) }
    }

    @Test fun `relative commands give the same outline as their absolute form`() {
        val absolute = points(svg("<path d=\"M10 10 L20 10 H30 V20 C30 30 40 30 40 20 S50 10 50 20 Q55 30 60 20 T70 20 A5 5 0 0 1 80 20 Z M5 5 L6 6\"/>"))
        val relative = points(svg("<path d=\"m10 10 l10 0 h10 v10 c0 10 10 10 10 0 s10 -10 10 0 q5 10 10 0 t10 0 a5 5 0 0 1 10 0 z m-5 -5 l1 1\"/>"))
        assertTrue(absolute.size > 20)
        assertEquals(absolute.size, relative.size)
        absolute.zip(relative).forEach { (a, r) -> close(a, r, 1e-9) }
        // After a closepath, a relative moveto starts from the subpath start, not from the last point.
        val after = read(svg("<path d=\"M10 10 L50 10 L50 50 z m5 5 l1 0\"/>")).shapes.single().paths
        assertEquals(listOf(Point(15.0, 15.0), Point(16.0, 15.0)), after[1].points)
    }

    @Test fun `fill none and evenodd`() {
        val shapes = read(svg("<path fill=\"none\" d=\"M0 0H10V10Z\"/>" +
            "<g fill=\"none\"><path fill=\"red\" d=\"M0 0H5V5Z\"/><path d=\"M0 0H4V4Z\"/></g>" +
            "<path fill-rule=\"evenodd\" d=\"M0 0H20V20H0Z M5 5H15V15H5Z\"/>" +
            "<g fill-rule=\"evenodd\"><path fill-rule=\"nonzero\" d=\"M0 0H3V3Z\"/></g>" +
            "<path fill=\"transparent\" d=\"M0 0H2V2Z\"/>")).shapes
        assertEquals(6, shapes.size)
        assertFalse(shapes[0].filled)
        assertTrue(shapes[1].filled); assertFalse(shapes[2].filled)
        assertTrue(shapes[3].filled && shapes[3].evenOdd); assertEquals(2, shapes[3].paths.size)
        assertTrue(shapes[3].paths.all { it.closed })
        assertFalse(shapes[4].evenOdd)
        assertFalse(shapes[5].filled)
    }
}

/** Map reading with drawing content: assembly rules, links, caps, and an unchanged reading. */
class MapTransferDrawingQaTest {
    private fun transfer() = MapTransfer(3, requestTimeoutMs = 10_000, frameTimeoutMs = 8_000, maxAttempts = 3, clock = { 0L })
    private fun msg(n: MctlNav.Builder) = LubaMsg.newBuilder().setNav(n).build()
    private fun roots(hashes: List<Long>) = msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setPver(1).setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(hashes)))
    private fun zone(hash: Long) = msg(MctlNav.newBuilder().setToappGetCommondataAck(NavGetCommDataAck.newBuilder().setPver(1).setAction(8).setType(0).setHash(hash)
        .setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(listOf(0f to 0f, 20f to 0f, 20f to 12f, 0f to 12f).map { (x, y) -> CommDataCouple.newBuilder().setX(x).setY(y).build() })))
    private fun svg(hash: Long, parent: Long, frame: Int, total: Int, data: String?, x: Double = 5.0) =
        msg(MctlNav.newBuilder().setToappSvgMsg(svg_message_ack_t.newBuilder().setPver(1).setSubCmd(1).setDataHash(hash).setPaternalHashA(parent)
            .setTotalFrame(total).setCurrentFrame(frame).setType(13).apply {
                if (data != null) setSvgMessage(svg_message_t.newBuilder().setXMove(x).setYMove(3.0).setScale(1.0).setBaseWidthM(4.0).setBaseHeightM(4.0)
                    .setSvgFileName("p.svg").setSvgFileData(data).setDataCount(data.length))
            }))
    private fun requested(out: List<ByteArray>) = out.map { LubaMsg.parseFrom(it) }.filter { it.hasNav() && it.nav.hasTodevGetCommondata() && it.nav.todevGetCommondata.subCmd == 1 }.map { it.nav.todevGetCommondata.hash }
    /** Answer without its send time (the only field that differs between two runs). */
    private fun stamped(bytes: ByteArray) = LubaMsg.parseFrom(bytes).toBuilder().clearTimestamp().build()

    @Test fun `five frames out of order with repeats carrying other content`() {
        val t = transfer(); val c = Commands()
        t.accept(roots(listOf(1, 2)), c); t.accept(zone(1), c)
        val parts = QA_SQUARE.chunked(QA_SQUARE.length / 5 + 1)
        assertEquals(5, parts.size)
        for (frame in listOf(4, 2, 5, 2, 1, 3, 4)) {
            // Each frame is followed by a repeat carrying other bytes: the first copy of the frame is the one kept.
            t.accept(svg(2, 1, frame, 5, parts[frame - 1]), c)
            t.accept(svg(2, 1, frame, 5, "<junk/>"), c)
        }
        val map = t.snapshot()
        assertTrue(map.complete); assertTrue(t.finished)
        assertEquals(QA_SQUARE, map.drawings.single().svg)
    }

    @Test fun `new series with another frame count drops the frames of the old one`() {
        val t = transfer(); val c = Commands()
        t.accept(roots(listOf(1, 2)), c); t.accept(zone(1), c)
        t.accept(svg(2, 1, 1, 3, "OLD1"), c)
        t.accept(svg(2, 1, 2, 3, "OLD2"), c)
        val parts = QA_SQUARE.chunked(QA_SQUARE.length / 2 + 1)
        t.accept(svg(2, 1, 2, 2, parts[1]), c)
        t.accept(svg(2, 1, 1, 2, parts[0]), c)
        assertEquals(QA_SQUARE, t.snapshot().drawings.single().svg)
        // The finished drawing is never replaced by later frames.
        t.accept(svg(2, 1, 0, 0, "<svg viewBox=\"0 0 1 1\"/>"), c)
        assertEquals(QA_SQUARE, t.snapshot().drawings.single().svg)
    }

    @Test fun `single frame with total 0 assembles at once whatever its frame number`() {
        val t = transfer(); val c = Commands()
        t.accept(roots(listOf(1, 2)), c); t.accept(zone(1), c)
        t.accept(svg(2, 1, 7, 0, QA_SQUARE), c)
        assertEquals(QA_SQUARE, t.snapshot().drawings.single().svg)
        assertTrue(t.snapshot().complete)
    }

    @Test fun `pushed tiles after the list kept when their zone is listed, ignored otherwise`() {
        val t = transfer(); val c = Commands()
        t.accept(roots(listOf(1)), c)
        t.accept(svg(50, 1, 0, 0, QA_SQUARE), c)       // in a listed zone: kept
        t.accept(svg(51, 77, 0, 0, QA_SQUARE), c)      // unknown zone: ignored
        t.accept(svg(52, 0, 0, 0, QA_SQUARE), c)       // no parent: ignored
        t.accept(zone(1), c)
        val map = t.snapshot()
        assertTrue(map.complete)
        assertEquals(listOf(50L), map.drawings.map { it.hash })
        assertEquals(1, t.read); assertEquals(1, t.announced)
    }

    @Test fun `tile without link pushed before the list is not published`() {
        val t = transfer(); val c = Commands()
        t.accept(svg(60, 0, 0, 0, QA_SQUARE), c)
        t.accept(svg(61, 88, 0, 0, QA_SQUARE), c)
        t.accept(roots(listOf(1)), c); t.accept(zone(1), c)
        assertTrue(t.snapshot().complete)
        assertTrue(t.snapshot().drawings.isEmpty())
        assertFalse(t.snapshot().source.contains("motif"))
    }

    @Test fun `at most 64 drawings, the reading still completes`() {
        val t = transfer(); val c = Commands()
        val drawings = (100L until 170L).toList()
        t.accept(roots(listOf(1L) + drawings), c)
        t.accept(zone(1), c)
        for (h in drawings) t.accept(svg(h, 1, 0, 0, QA_SQUARE, x = h.toDouble() / 10), c)
        val map = t.snapshot()
        assertTrue(map.source, map.complete); assertTrue(t.finished)
        assertEquals(71, t.read)
        assertEquals(MapTransfer.MAX_DRAWINGS, map.drawings.size)
        assertEquals(drawings.take(64), map.drawings.map { it.hash })
        assertTrue(t.notices().any { it.kind == "map_drawing_ignored" })
    }

    @Test fun `total SVG size cap keeps the footprint of the drawings beyond it`() {
        val t = transfer(); val c = Commands()
        val drawings = (200L until 209L).toList()
        t.accept(roots(listOf(1L) + drawings), c); t.accept(zone(1), c)
        val big = "<svg viewBox=\"0 0 1 1\">" + " ".repeat(250_000 - 30) + "</svg>"
        for (h in drawings) t.accept(svg(h, 1, 0, 0, big), c)
        val map = t.snapshot()
        assertTrue(map.complete)
        assertEquals(9, map.drawings.size)
        assertTrue(map.drawings.sumOf { it.svg.length } <= MapTransfer.MAX_TOTAL_SVG_CHARS)
        assertEquals("", map.drawings.last().svg)
        assertEquals(4, map.drawings.last().footprint.size)
    }

    /** Same frames with or without drawing content: same answers, same progress, same map. */
    @Test fun `reading ends identically with or without SVG content`() {
        fun run(content: (Long) -> String?): Triple<List<List<ByteArray>>, RobotMap, Int> {
            val t = transfer(); val c = Commands(); val outs = mutableListOf<List<ByteArray>>()
            outs += t.accept(svg(9, 1, 0, 0, content(9)), c)  // pushed before the list
            outs += t.accept(roots(listOf(1, 2, 3, 4)), c)
            outs += t.accept(zone(1), c)
            outs += t.accept(svg(2, 1, 2, 2, content(2)), c)
            outs += t.accept(svg(2, 1, 1, 2, content(2)), c)
            outs += t.accept(svg(2, 1, 1, 2, content(2)), c)
            outs += t.accept(svg(3, 1, 0, 0, content(3)), c)
            outs += t.accept(zone(4), c)
            assertTrue(t.finished)
            return Triple(outs, t.snapshot(), t.read)
        }
        val reference = run { null }
        val variants = listOf<(Long) -> String?>({ QA_SQUARE }, { "" }, { "heart" }, { "<svg <<< 1e400 NaN" }, { "x".repeat(SvgOutline.MAX_SVG_CHARS + 5) })
        for (variant in variants) {
            val other = run(variant)
            assertEquals(reference.first.size, other.first.size)
            reference.first.zip(other.first).forEach { (a, b) -> assertEquals(a.map(::stamped), b.map(::stamped)) }
            assertEquals(reference.second.complete, other.second.complete)
            assertEquals(reference.second.shapes, other.second.shapes)
            assertEquals(reference.third, other.third)
        }
        assertTrue(reference.second.complete)
        assertTrue(reference.second.drawings.isEmpty())
        // Tile 9 was pushed before the list into zone 1, which the list holds: kept.
        assertEquals(listOf(9L, 2L, 3L), run { QA_SQUARE }.second.drawings.map { it.hash })
    }

    @Test fun `drawing with a non-finite placement does not disturb the reading`() {
        val t = transfer(); val c = Commands()
        t.accept(roots(listOf(1, 2)), c); t.accept(zone(1), c)
        assertTrue(requested(t.accept(svg(2, 1, 0, 0, QA_SQUARE, x = Double.NaN), c)).isEmpty())
        assertTrue(t.snapshot().complete)
        assertTrue(t.snapshot().drawings.isEmpty())
    }
}

/** Drawings are display only: the planning and the moves are the same with or without them. */
class MapDrawingPlanningQaTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private val zone = MapShape(1, 0, rect(0.0, 0.0, 20.0, 12.0))
    private val noGo = MapShape(2, 1, rect(12.0, 4.0, 2.0, 2.0))
    private val plain = RobotMap(listOf(zone, noGo), true, "BLE")
    /** A pattern across the whole zone, one off it and a hidden one. */
    private val patterned = plain.copy(drawings = listOf(
        MapDrawing(13, 1, 6.0, 6.0, 8.0, 8.0, 0.4, false, "p.svg", QA_SQUARE),
        MapDrawing(14, 1, 30.0, 30.0, 4.0, 4.0, 0.0, false, "p.svg", QA_SQUARE),
        MapDrawing(15, 1, 10.0, 6.0, 20.0, 12.0, 0.0, true, "heart")))

    @Test fun `refusals are unchanged`() {
        assertNull(plain.planningRefusal()); assertNull(patterned.planningRefusal())
        assertNull(plain.movesRefusal()); assertNull(patterned.movesRefusal())
    }

    @Test fun `mowing plan is the same with or without drawings`() {
        fun plan(map: RobotMap) = CoverageEngine.plan(PlanningRequest(map, listOf(1), Point(1.0, 1.0), config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65))).selected
        val a = plan(plain); val b = plan(patterned)
        assertTrue(a.valid)
        assertEquals(a.valid, b.valid)
        assertEquals(a.preview!!.cuttingPaths, b.preview!!.cuttingPaths)
        assertEquals(a.preview!!.meters, b.preview!!.meters, 0.0)
    }

    @Test fun `move to a point is the same with or without drawings`() {
        val start = Point(2.0, 6.0); val end = Point(18.0, 6.0)
        val a = TrajectoryPlanner.pointPlan(plain, start, end, emptyList())
        val b = TrajectoryPlanner.pointPlan(patterned, start, end, emptyList())
        assertEquals(a.points, b.points)
    }

    @Test fun `map read with drawing content plans like the map read without`() {
        fun read(withContent: Boolean): RobotMap {
            val t = MapTransfer(3, requestTimeoutMs = 10_000, frameTimeoutMs = 8_000, maxAttempts = 3, clock = { 0L }); val c = Commands()
            fun nav(n: MctlNav.Builder) = LubaMsg.newBuilder().setNav(n).build()
            t.accept(nav(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setPver(1).setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(listOf(1L, 2L)))), c)
            t.accept(nav(MctlNav.newBuilder().setToappGetCommondataAck(NavGetCommDataAck.newBuilder().setPver(1).setAction(8).setType(0).setHash(1).setTotalFrame(1).setCurrentFrame(1)
                .addAllDataCouple(zone.points.map { CommDataCouple.newBuilder().setX(it.x.toFloat()).setY(it.y.toFloat()).build() }))), c)
            t.accept(nav(MctlNav.newBuilder().setToappSvgMsg(svg_message_ack_t.newBuilder().setPver(1).setSubCmd(1).setDataHash(2).setPaternalHashA(1).setTotalFrame(0).apply {
                if (withContent) setSvgMessage(svg_message_t.newBuilder().setXMove(6.0).setYMove(6.0).setScale(1.0).setBaseWidthM(8.0).setBaseHeightM(8.0).setSvgFileData(QA_SQUARE))
            })), c)
            return t.snapshot()
        }
        val without = read(false); val with = read(true)
        assertTrue(without.complete); assertTrue(with.complete)
        assertEquals(1, with.drawings.size)
        assertEquals(without.shapes, with.shapes)
        assertEquals(without.planningRefusal(), with.planningRefusal())
        assertEquals(without.movesRefusal(), with.movesRefusal())
        fun plan(map: RobotMap) = CoverageEngine.plan(PlanningRequest(map, listOf(1), Point(1.0, 1.0), config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65))).selected
        assertEquals(plan(without).preview!!.cuttingPaths, plan(with).preview!!.cuttingPaths)
    }
}
