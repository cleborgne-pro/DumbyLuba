package fr.luba.poc.map

import fr.luba.poc.protocol.Commands
import fr.luba.poc.proto.CommonProto.CommDataCouple
import fr.luba.poc.proto.LubaMsgProto.LubaMsg
import fr.luba.poc.proto.MctrlNavProto.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

private const val SQUARE = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><path d=\"M10 10H90V90H10Z\" fill=\"#111\"/></svg>"

private fun near(expected: Point, actual: Point, tolerance: Double = 1e-9) =
    assertTrue("$actual instead of $expected", abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance)

/** Placement of a drawing tile on the map (formula of MapDrawing, to be confirmed on a capture). */
class MapDrawingPlacementTest {
    private fun drawing(rotate: Double = 0.0, scale: Double = 1.0, svg: String = SQUARE) =
        MapDrawing.of(13, 10, 5.0, 3.0, scale, rotate, 4.0, 4.0, 0, 0, "square.svg", svg, false)!!

    @Test fun `square of the published example is 3,2 m wide and centered on the tile`() {
        val d = drawing()
        val ring = d.outline.shapes.single().paths.single()
        assertTrue(ring.closed)
        assertTrue(d.outline.shapes.single().filled)
        assertEquals(4, ring.points.size)
        listOf(Point(3.4, 4.6), Point(6.6, 4.6), Point(6.6, 1.4), Point(3.4, 1.4)).zip(ring.points).forEach { (e, a) -> near(e, a) }
        // Tile footprint: 4 m square centered on (5, 3).
        listOf(Point(3.0, 1.0), Point(7.0, 1.0), Point(7.0, 5.0), Point(3.0, 5.0)).zip(d.footprint).forEach { (e, a) -> near(e, a) }
    }

    @Test fun `rotation is counterclockwise in radians`() {
        val d = drawing(rotate = PI / 2)
        val box = ViewBox(0.0, 0.0, 100.0, 100.0)
        // SVG corner (10, 10): a = -1.6, b = 1.6, turned by 90° → (5 - 1.6, 3 - 1.6).
        near(Point(3.4, 1.4), d.transform(box).apply(10.0, 10.0))
        // SVG corner (90, 10): a = 1.6, b = 1.6 → (5 - 1.6, 3 + 1.6).
        near(Point(3.4, 4.6), d.transform(box).apply(90.0, 10.0))
        near(Point(3.4, 1.4), d.outline.shapes.single().paths.single().points.first())
        near(Point(7.0, 1.0), d.footprint[0])
    }

    @Test fun `SVG y axis pointing down is flipped`() {
        val box = ViewBox(0.0, 0.0, 100.0, 100.0)
        // Top middle of the SVG → north edge of the tile, left middle → west edge.
        near(Point(5.0, 5.0), drawing().transform(box).apply(50.0, 0.0))
        near(Point(3.0, 3.0), drawing().transform(box).apply(0.0, 50.0))
        // viewBox with an origin: its center is the tile center.
        near(Point(5.0, 3.0), drawing().transform(ViewBox(-50.0, -50.0, 100.0, 100.0)).apply(0.0, 0.0))
    }

    @Test fun `scale 0 means 1, otherwise it multiplies the base size`() {
        assertEquals(4.0, drawing(scale = 0.0).widthM, 0.0)
        assertEquals(4.0, drawing(scale = -1.0).heightM, 0.0)
        assertEquals(8.0, drawing(scale = 2.0).widthM, 0.0)
        assertNull(MapDrawing.of(1, 0, Double.NaN, 0.0, 1.0, 0.0, 4.0, 4.0, 0, 0, "", SQUARE, false))
        assertNull(MapDrawing.of(1, 0, 0.0, 0.0, 1.0, 0.0, 0.0, 4.0, 0, 0, "", SQUARE, false))
    }

    @Test fun `built-in pattern name gives the footprint only`() {
        val d = drawing(svg = "heart")
        assertTrue(d.outline.shapes.isEmpty())
        assertFalse(d.outline.truncated)
        assertEquals(4, d.footprint.size)
    }
}

/** Minimal SVG reader: commands, shapes, transforms, frame, limits; it never throws. */
class SvgOutlineTest {
    /** Raw SVG units (y down), no placement. */
    private fun read(svg: String) = SvgOutline.parse(svg) { Affine() }
    private fun svg(body: String, root: String = "viewBox=\"0 0 100 100\"") = "<svg $root>$body</svg>"
    private fun points(svg: String) = read(svg).shapes.flatMap { s -> s.paths.flatMap { it.points } }

    @Test fun `relative commands, implicit linetos and packed numbers`() {
        assertEquals(listOf(Point(10.0, 10.0), Point(90.0, 10.0), Point(90.0, 90.0), Point(10.0, 90.0)),
            points(svg("<path d=\"m10 10h80v80h-80z\"/>")))
        assertEquals(listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)), points(svg("<path d=\"m0 0 10 0 0 10z\"/>")))
        assertEquals(listOf(Point(10.0, -5.0), Point(0.5, 0.5), Point(10.5, 0.5)), points(svg("<path d=\"M10-5L.5.5l1e1,0\"/>")))
        // Two subpaths, the second one drawn after a closepath from the first start point.
        val two = read(svg("<path d=\"M0 0H10V10ZL0 20\"/>")).shapes.single().paths
        assertEquals(2, two.size)
        assertTrue(two[0].closed); assertFalse(two[1].closed)
        assertEquals(listOf(Point(0.0, 0.0), Point(0.0, 20.0)), two[1].points)
        // Unreadable rest: what came before is kept.
        assertEquals(listOf(Point(0.0, 0.0), Point(5.0, 0.0)), points(svg("<path d=\"M0 0L5 0L7\"/>")))
    }

    @Test fun `arcs and curves flattened within the tolerance`() {
        // Half circle of radius 10 around (10, 0), sweep 1: through y = -10 in the SVG frame.
        val arc = points(svg("<path d=\"M0 0A10 10 0 0 1 20 0\"/>"))
        assertTrue(arc.size > 8)
        arc.forEach { assertEquals(10.0, hypot(it.x - 10, it.y), SvgOutline.TOLERANCE_M) }
        assertEquals(-10.0, arc.minOf { it.y }, SvgOutline.TOLERANCE_M)
        near(Point(20.0, 0.0), arc.last())
        // Packed flags ("0020 0"), relative endpoint, sweep 0: through y = +10.
        val packed = points(svg("<path d=\"M0 0a10 10 0 0020 0\"/>"))
        assertEquals(10.0, packed.maxOf { it.y }, SvgOutline.TOLERANCE_M)
        near(Point(20.0, 0.0), packed.last())
        // Quadratic, smooth cubic and smooth quadratic end where expected.
        near(Point(40.0, 0.0), points(svg("<path d=\"M0 0Q10 10 20 0T40 0\"/>")).last())
        near(Point(40.0, 0.0), points(svg("<path d=\"M0 0C0 10 20 10 20 0S40 -10 40 0\"/>")).last())
        val cubic = points(svg("<path d=\"M0 0C0 100 100 100 100 0\"/>"))
        assertEquals(75.0, cubic.maxOf { it.y }, 0.05)
    }

    @Test fun `rect, circle, ellipse, polygon, polyline and line`() {
        val rect = read(svg("<rect x=\"1\" y=\"2\" width=\"3\" height=\"4\"/>")).shapes.single().paths.single()
        assertTrue(rect.closed)
        assertEquals(listOf(Point(1.0, 2.0), Point(4.0, 2.0), Point(4.0, 6.0), Point(1.0, 6.0), Point(1.0, 2.0)), rect.points)
        val circle = read(svg("<circle cx=\"50\" cy=\"50\" r=\"20\"/>")).shapes.single().paths.single()
        assertTrue(circle.closed)
        circle.points.forEach { assertEquals(20.0, hypot(it.x - 50, it.y - 50), 0.05) }
        val ellipse = points(svg("<ellipse cx=\"0\" cy=\"0\" rx=\"20\" ry=\"10\"/>"))
        assertEquals(20.0, ellipse.maxOf { it.x }, 1e-9); assertEquals(10.0, ellipse.maxOf { it.y }, 0.05)
        val polygon = read(svg("<polygon points=\"0,0 4,0 4,3\"/>")).shapes.single()
        assertTrue(polygon.filled && polygon.paths.single().closed)
        assertEquals(3, polygon.paths.single().points.size)
        val polyline = read(svg("<polyline points=\"0 0 4 0 4 3\" fill=\"none\"/>")).shapes.single()
        assertFalse(polyline.filled || polyline.paths.single().closed)
        val line = read(svg("<line x1=\"0\" y1=\"0\" x2=\"5\" y2=\"5\"/>")).shapes.single()
        assertFalse(line.filled)
        assertEquals(listOf(Point(0.0, 0.0), Point(5.0, 5.0)), line.paths.single().points)
        // Zero size or missing radius: nothing.
        assertTrue(read(svg("<rect width=\"0\" height=\"4\"/><circle r=\"0\"/><ellipse rx=\"3\"/>")).shapes.isEmpty())
    }

    @Test fun `transforms accumulate over groups and elements`() {
        assertEquals(listOf(Point(10.0, 0.0), Point(12.0, 0.0), Point(12.0, 2.0), Point(10.0, 2.0), Point(10.0, 0.0)),
            points(svg("<g transform=\"translate(10,0)\"><rect width=\"1\" height=\"1\" transform=\"scale(2)\"/></g>")))
        near(Point(0.0, 1.0), points(svg("<g transform=\"rotate(90)\"><line x1=\"1\" y1=\"0\" x2=\"2\" y2=\"0\"/></g>")).first())
        // rotate about a point, then matrix (translate 5, 6).
        near(Point(10.0, 12.0), points(svg("<line x1=\"12\" y1=\"10\" x2=\"0\" y2=\"0\" transform=\"rotate(90 10 10)\"/>")).first())
        near(Point(6.0, 8.0), points(svg("<line x1=\"1\" y1=\"2\" x2=\"0\" y2=\"0\" transform=\"matrix(1 0 0 1 5 6)\"/>")).first())
        // Nested groups: outer transform applied last.
        near(Point(20.0, 0.0), points(svg("<g transform=\"scale(2)\"><g transform=\"translate(10 0)\"><line x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\"/></g></g>")).first())
        // Unreadable transform: none.
        near(Point(1.0, 2.0), points(svg("<line x1=\"1\" y1=\"2\" x2=\"0\" y2=\"0\" transform=\"wobble(3)\"/>")).first())
    }

    @Test fun `fill rule, fill none and skipped elements`() {
        val shapes = read(svg("<g fill-rule=\"evenodd\"><path d=\"M0 0H10V10Z\"/><path style=\"fill:none\" d=\"M0 0H5V5Z\"/></g>" +
            "<path style=\"fill-rule: evenodd\" d=\"M0 0H1V1Z\"/><path fill-rule=\"nonzero\" d=\"M0 0H2V2Z\"/>" +
            "<defs><rect width=\"9\" height=\"9\"/></defs><!-- <rect width=\"8\" height=\"8\"/> --><g display=\"none\"><rect width=\"7\" height=\"7\"/></g>")).shapes
        assertEquals(4, shapes.size)
        assertTrue(shapes[0].evenOdd && shapes[0].filled)
        assertTrue(shapes[1].evenOdd); assertFalse(shapes[1].filled)
        assertTrue(shapes[2].evenOdd)
        assertFalse(shapes[3].evenOdd)
    }

    @Test fun `frame without viewBox comes from the size, then the pixel size, then the content`() {
        fun frame(svg: String, w: Int = 0, h: Int = 0): ViewBox? { var seen: ViewBox? = null; SvgOutline.parse(svg, w, h) { seen = it; Affine() }; return seen }
        val body = "<rect x=\"10\" y=\"20\" width=\"30\" height=\"40\"/>"
        assertEquals(ViewBox(0.0, 0.0, 200.0, 100.0), frame(svg(body, "width=\"200px\" height=\"100\""), 64, 32))
        assertEquals(ViewBox(0.0, 0.0, 64.0, 32.0), frame(svg(body, "width=\"100%\" height=\"100%\""), 64, 32))
        assertEquals(ViewBox(10.0, 20.0, 30.0, 40.0), frame(svg(body, "")))
        assertEquals(ViewBox(-5.0, 0.0, 10.0, 20.0), frame(svg(body, "viewBox=\"-5,0 10 20\" width=\"300\" height=\"300\"")))
        // Placed with its own pixel frame: (10, 20) of a 64×32 frame on a 4×2 m tile centered on (0, 0).
        val tile = MapDrawing(1, 0, 0.0, 0.0, 4.0, 2.0, 0.0, false, svg = svg(body, ""), basePixelWidth = 64, basePixelHeight = 32)
        near(Point(10.0 / 64 * 4 - 2, 1 - 20.0 / 32 * 2), tile.outline.shapes.single().paths.single().points.first())
    }

    @Test fun `text that is not SVG or unreadable gives no outline and never throws`() {
        for (text in listOf("", "heart", "{\"pattern\":3}", "<svg", "<svg <<<>>> <path d=", "<svg viewBox=\"0 0 1 1\"><path d=\"M 0 0 L\"/></svg>",
            "<svg viewBox=\"a b c d\"><rect width=\"1\" height=\"1\"/></svg>", "<svg viewBox=\"0 0 10 10\"><path d=\"M0 0 A 0 0 0 0 0 0 0 Z\"/></svg>",
            "<svg><g><g><g>" + "<g>".repeat(5000) + "<rect width=\"1\" height=\"1\"/></svg>")) {
            val outline = read(text)
            assertFalse(text, outline.truncated)
        }
        assertTrue(read("heart").shapes.isEmpty())
        // Unknown placement (no frame and a point only): nothing.
        assertTrue(read("<svg><path d=\"M1 1Z\"/></svg>").shapes.isEmpty())
    }

    @Test fun `too large or too complex drawing keeps the footprint only`() {
        val many = (0 until SvgOutline.MAX_POINTS + 10).joinToString(" ") { "$it,${it % 7}" }
        val complex = read(svg("<polygon points=\"$many\"/>"))
        assertTrue(complex.truncated); assertTrue(complex.shapes.isEmpty())
        val big = read(svg("<!--" + "x".repeat(SvgOutline.MAX_SVG_CHARS) + "--><rect width=\"1\" height=\"1\"/>"))
        assertTrue(big.truncated); assertTrue(big.shapes.isEmpty())
        // Many curves each flattened finely: the point limit applies after flattening too.
        val curves = svg("<path d=\"M0 0" + " c0 1000 1000 1000 1000 0".repeat(200) + "\"/>")
        assertTrue(read(curves).truncated)
        assertTrue(read(curves).shapes.isEmpty())
    }
}

/** Content of the drawings kept by the map reading, without changing its progress. */
class MapTransferDrawingTest {
    private val c = Commands()
    private fun transfer() = MapTransfer(3, requestTimeoutMs = 10_000, frameTimeoutMs = 8_000, maxAttempts = 3, clock = { 0L })
    private fun msg(n: MctlNav.Builder) = LubaMsg.newBuilder().setNav(n).build()
    private fun roots(vararg hashes: Long) = msg(MctlNav.newBuilder().setToappGethashAck(NavGetHashListAck.newBuilder().setPver(1).setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(hashes.toList())))
    private fun zone(hash: Long) = msg(MctlNav.newBuilder().setToappGetCommondataAck(NavGetCommDataAck.newBuilder().setPver(1).setAction(8).setType(0).setHash(hash)
        .setTotalFrame(1).setCurrentFrame(1).addAllDataCouple(listOf(0f to 0f, 10f to 0f, 10f to 6f, 0f to 6f).map { (x, y) -> CommDataCouple.newBuilder().setX(x).setY(y).build() })))
    private fun svg(hash: Long, parent: Long, frame: Int, total: Int, data: String, rotate: Double = 0.0, hide: Boolean = false, name: String = "square.svg") =
        msg(MctlNav.newBuilder().setToappSvgMsg(svg_message_ack_t.newBuilder().setPver(1).setSubCmd(1).setDataHash(hash).setPaternalHashA(parent)
            .setTotalFrame(total).setCurrentFrame(frame).setType(13).setSvgMessage(svg_message_t.newBuilder().setXMove(5.0).setYMove(3.0).setScale(1.0)
                .setRotate(rotate).setBaseWidthM(4.0).setBaseHeightM(4.0).setBaseWidthPix(100).setBaseHeightPix(100).setSvgFileName(name)
                .setSvgFileData(data).setDataCount(data.length).setHideSvg(hide))))
    private fun requested(out: List<ByteArray>) = out.map { LubaMsg.parseFrom(it) }.filter { it.hasNav() && it.nav.hasTodevGetCommondata() && it.nav.todevGetCommondata.subCmd == 1 }.map { it.nav.todevGetCommondata.hash }
    private fun slices(text: String, size: Int) = text.chunked(size)

    @Test fun `frames out of order and repeated are assembled in frame order`() {
        val t = transfer()
        t.accept(roots(10, 13, 14), c); t.accept(zone(10), c)
        val parts = slices(SQUARE, 40)
        val order = listOf(3, 1, 1) + (parts.indices.map { it + 1 }.filter { it != 1 && it != 3 }.reversed()) + 3
        assertEquals(3, parts.size)
        // The element ends with its last distinct frame; the late repeat is only echoed.
        assertEquals(listOf(14L), order.flatMap { frame -> requested(t.accept(svg(13, 10, frame, parts.size, parts[frame - 1]), c)) })
        t.accept(zone(14), c)
        val map = t.snapshot()
        assertTrue(map.source, map.complete)
        val d = map.drawings.single()
        assertEquals(SQUARE, d.svg)
        assertEquals(13L, d.hash); assertEquals(10L, d.zoneHash); assertEquals("square.svg", d.fileName)
        assertEquals(1, d.outline.shapes.size)
        assertTrue(map.source, map.source.endsWith(" · 1 motifs"))
        assertEquals(3, t.read)
        // The log names the drawing, never its content.
        val notice = t.notices().single { it.kind == "map_drawing" }
        assertEquals(SQUARE.length, notice.fields["svgChars"]); assertEquals(10L, notice.fields["zone"]); assertEquals(false, notice.fields["hidden"])
        assertFalse(notice.fields.values.any { it.toString().contains("<svg") } || notice.detail.contains("<svg"))
    }

    @Test fun `single frame with a total of 0 is a whole drawing`() {
        val t = transfer()
        t.accept(roots(1, 2), c); t.accept(zone(1), c)
        assertTrue(requested(t.accept(svg(2, 1, 0, 0, SQUARE, rotate = PI / 2, hide = true), c)).isEmpty())
        val map = t.snapshot()
        assertTrue(map.complete); assertTrue(t.finished)
        val d = map.drawings.single()
        assertTrue(d.hidden)
        near(Point(3.4, 1.4), d.outline.shapes.single().paths.single().points.first())
    }

    @Test fun `tile pushed into a zone of the list is kept, the reading is unchanged`() {
        val t = transfer()
        t.accept(roots(10), c)
        // Tiles pushed while the zone is read: one in the zone, one in a zone the map does not know.
        t.accept(svg(99, 10, 0, 0, SQUARE), c)
        t.accept(svg(98, 77, 0, 0, SQUARE), c)
        assertFalse(t.finished)
        t.accept(zone(10), c)
        val map = t.snapshot()
        assertTrue(map.complete)
        assertEquals(1, t.read); assertEquals(1, t.announced)
        assertEquals(listOf(99L), map.drawings.map { it.hash })
        assertEquals(listOf(10L), map.shapes.map { it.id })
    }

    @Test fun `series restarted with another frame count replaces the previous one`() {
        val t = transfer()
        t.accept(roots(1, 2), c); t.accept(zone(1), c)
        t.accept(svg(2, 1, 1, 3, "<svg>junk"), c)
        val parts = slices(SQUARE, SQUARE.length / 2 + 1)
        t.accept(svg(2, 1, 1, 2, parts[0]), c)
        t.accept(svg(2, 1, 2, 2, parts[1]), c)
        assertEquals(SQUARE, t.snapshot().drawings.single().svg)
        assertTrue(t.snapshot().complete)
    }

    @Test fun `drawing over the size limit keeps its footprint only`() {
        val t = transfer()
        t.accept(roots(1, 2), c); t.accept(zone(1), c)
        t.accept(svg(2, 1, 0, 0, "x".repeat(SvgOutline.MAX_SVG_CHARS + 1)), c)
        val d = t.snapshot().drawings.single()
        assertEquals("", d.svg)
        assertEquals(4, d.footprint.size)
        assertEquals(true, t.notices().single { it.kind == "map_drawing" }.fields["oversized"])
    }

    @Test fun `drawing without placement is left out, the element is read anyway`() {
        val t = transfer()
        t.accept(roots(1, 2), c); t.accept(zone(1), c)
        t.accept(msg(MctlNav.newBuilder().setToappSvgMsg(svg_message_ack_t.newBuilder().setDataHash(2).setPaternalHashA(1).setTotalFrame(0)
            .setSvgMessage(svg_message_t.newBuilder().setSvgFileData(SQUARE)))), c)
        val map = t.snapshot()
        assertTrue(map.complete)
        assertTrue(map.drawings.isEmpty())
        assertFalse(map.source.contains("motif"))
        assertTrue(t.notices().any { it.kind == "map_drawing_ignored" })
    }
}
