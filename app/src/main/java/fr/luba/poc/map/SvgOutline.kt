package fr.luba.poc.map

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Subpath of a drawing in map coordinates (meters); an open subpath of a filled shape is closed implicitly when filled. */
data class OutlinePath(val points: List<Point>, val closed: Boolean)

/** One SVG element in map coordinates: its subpaths, filled with the [evenOdd] or non-zero rule, or stroke only. */
data class OutlineShape(val paths: List<OutlinePath>, val filled: Boolean, val evenOdd: Boolean = false)

/**
 * Outlines of a drawing in map coordinates. [truncated]: SVG too large or too complex ([SvgOutline.MAX_SVG_CHARS],
 * [SvgOutline.MAX_POINTS]), nothing is kept and only the tile footprint is drawn.
 */
data class DrawingOutline(val shapes: List<OutlineShape> = emptyList(), val truncated: Boolean = false) {
    val points: Int get() = shapes.sumOf { s -> s.paths.sumOf { it.points.size } }
}

/** SVG viewBox `[x, y, width, height]` (width and height strictly positive). */
data class ViewBox(val x: Double, val y: Double, val width: Double, val height: Double)

/** 2D affine transform in the SVG convention: x' = a·x + c·y + e, y' = b·x + d·y + f. */
data class Affine(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
    fun apply(x: Double, y: Double) = Point(a * x + c * y + e, b * x + d * y + f)
    fun apply(p: Point) = apply(p.x, p.y)
    /** `this · o`: [o] is applied first. */
    operator fun times(o: Affine) = Affine(a * o.a + c * o.b, b * o.a + d * o.b, a * o.c + c * o.d, b * o.c + d * o.d,
        a * o.e + c * o.f + e, b * o.e + d * o.f + f)
    val finite: Boolean get() = a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite() && e.isFinite() && f.isFinite()
}

/**
 * Minimal, defensive SVG reader for the Mammotion drawings ([MapDrawing]): it never throws and needs no XML library.
 *
 * Read: `path` (`d` with M L H V C S Q T A Z, absolute and relative, packed numbers and exponents), `rect` (corner radii
 * ignored), `circle`, `ellipse`, `polygon`, `polyline`, `line`, nested `g`, the `transform` attribute (matrix,
 * translate, scale, rotate, skewX, skewY) accumulated over the groups and the element, `fill="none"` (stroke only),
 * `fill-rule` (also inside `style`) and `display="none"`. Definitions, clip paths, masks, text, images and the like are
 * skipped. Curves and arcs are flattened once placed on the map, with the fixed [TOLERANCE_M]. A text that is not SVG
 * (a built-in pattern name for instance) gives no outline; a text too large ([MAX_SVG_CHARS]) or too complex
 * ([MAX_POINTS]) gives no outline and [DrawingOutline.truncated].
 *
 * Frame: the viewBox of the root `svg` element; without one, its `width`/`height`, then the fallback pixel size, then
 * the bounding box of the content.
 */
object SvgOutline {
    /** Longest SVG text read (characters). */
    const val MAX_SVG_CHARS = 256 * 1024
    /** Most points (and raw segments) kept for one drawing. */
    const val MAX_POINTS = 20_000
    /** Flattening tolerance of curves and arcs, in meters on the map. */
    const val TOLERANCE_M = 0.02
    private const val MAX_DEPTH = 256
    private const val MAX_CURVE_STEPS = 256

    /**
     * Outlines of [svg] placed on the map by [place] (viewBox → meters). [fallbackWidth] / [fallbackHeight]: pixel size
     * of the drawing (`base_width_pix` / `base_height_pix`), used when the SVG has neither viewBox nor size.
     */
    fun parse(svg: String, fallbackWidth: Int = 0, fallbackHeight: Int = 0, place: (ViewBox) -> Affine): DrawingOutline = try {
        when {
            svg.length > MAX_SVG_CHARS -> DrawingOutline(truncated = true)
            !svg.contains("<svg", ignoreCase = true) -> DrawingOutline()
            else -> Reader(svg).read()?.let { doc -> flatten(doc, fallbackWidth, fallbackHeight, place) } ?: DrawingOutline(truncated = true)
        }
    } catch (_: Exception) {
        DrawingOutline()
    } catch (_: StackOverflowError) {
        DrawingOutline()
    }

    // ---- Raw geometry, in the frame of the root svg (element transforms applied, viewBox not yet). ----

    private sealed interface Segment { val to: Point }
    private class Line(override val to: Point) : Segment
    private class Cubic(val c1: Point, val c2: Point, override val to: Point) : Segment
    private class SubPath(val start: Point, val segments: MutableList<Segment> = mutableListOf(), var closed: Boolean = false)
    private class RawShape(val paths: List<SubPath>, val filled: Boolean, val evenOdd: Boolean)
    private class Document(val viewBox: ViewBox?, val width: Double?, val height: Double?, val shapes: List<RawShape>)

    /** Too many segments: the whole drawing is given up. */
    private class TooComplex : RuntimeException()

    private fun flatten(doc: Document, fallbackWidth: Int, fallbackHeight: Int, place: (ViewBox) -> Affine): DrawingOutline {
        val box = doc.viewBox
            ?: (if (doc.width != null && doc.height != null) ViewBox(0.0, 0.0, doc.width, doc.height) else null)
            ?: (if (fallbackWidth > 0 && fallbackHeight > 0) ViewBox(0.0, 0.0, fallbackWidth.toDouble(), fallbackHeight.toDouble()) else null)
            ?: contentBox(doc.shapes) ?: return DrawingOutline()
        val m = place(box)
        if (!m.finite) return DrawingOutline()
        var count = 0
        val shapes = ArrayList<OutlineShape>()
        for (raw in doc.shapes) {
            val paths = ArrayList<OutlinePath>()
            for (sub in raw.paths) {
                val pts = ArrayList<Point>()
                var current = m.apply(sub.start); pts.add(current)
                for (s in sub.segments) {
                    when (s) {
                        is Line -> { current = m.apply(s.to); pts.add(current) }
                        is Cubic -> {
                            val p1 = m.apply(s.c1); val p2 = m.apply(s.c2); val p3 = m.apply(s.to)
                            val steps = cubicSteps(current, p1, p2, p3)
                            for (i in 1..steps) pts.add(cubicAt(current, p1, p2, p3, i.toDouble() / steps))
                            current = p3
                        }
                    }
                    if (pts.size + count > MAX_POINTS) return DrawingOutline(truncated = true)
                }
                if (pts.size >= 2 && pts.all { it.x.isFinite() && it.y.isFinite() }) { count += pts.size; paths.add(OutlinePath(pts, sub.closed)) }
            }
            if (paths.isNotEmpty()) shapes.add(OutlineShape(paths, raw.filled, raw.evenOdd))
        }
        return DrawingOutline(shapes)
    }

    /** Bounding box of the control points, when the SVG gives no frame at all. */
    private fun contentBox(shapes: List<RawShape>): ViewBox? {
        val pts = shapes.flatMap { s -> s.paths.flatMap { p -> listOf(p.start) + p.segments.flatMap { seg -> if (seg is Cubic) listOf(seg.c1, seg.c2, seg.to) else listOf(seg.to) } } }
        if (pts.isEmpty()) return null
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val w = maxX - minX; val h = maxY - minY
        return if (w > 0 && h > 0 && w.isFinite() && h.isFinite()) ViewBox(minX, minY, w, h) else null
    }

    /** Wang's bound: number of chords staying within [TOLERANCE_M] of the cubic. */
    private fun cubicSteps(p0: Point, p1: Point, p2: Point, p3: Point): Int {
        val d1 = norm(p0.x - 2 * p1.x + p2.x, p0.y - 2 * p1.y + p2.y)
        val d2 = norm(p1.x - 2 * p2.x + p3.x, p1.y - 2 * p2.y + p3.y)
        val n = ceil(sqrt(0.75 * max(d1, d2) / TOLERANCE_M))
        return if (n.isNaN()) 1 else n.coerceIn(1.0, MAX_CURVE_STEPS.toDouble()).toInt()
    }

    private fun norm(x: Double, y: Double) = sqrt(x * x + y * y)

    private fun cubicAt(p0: Point, p1: Point, p2: Point, p3: Point, t: Double): Point {
        val u = 1 - t
        val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        return Point(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }

    // ---- Tag reader ----

    private class Style(val ctm: Affine, val filled: Boolean, val evenOdd: Boolean, val skip: Boolean)

    private val SKIPPED = setOf("defs", "clippath", "mask", "symbol", "pattern", "marker", "style", "script", "text", "title", "desc",
        "metadata", "lineargradient", "radialgradient", "filter", "foreignobject", "switch", "use", "image")

    private class Reader(val s: String) {
        private val shapes = mutableListOf<RawShape>()
        private var segments = 0
        private var viewBox: ViewBox? = null
        private var width: Double? = null
        private var height: Double? = null
        private var rootSeen = false

        /** Null when the drawing is too complex. */
        fun read(): Document? {
            // Open elements, at most MAX_DEPTH: deeper ones are skipped and only counted ([ignored]), so a closing tag
            // looks at MAX_DEPTH names at most and the reading stays linear in the text.
            val stack = ArrayList<Pair<String, Style>>()
            var ignored = 0
            var i = 0
            try {
                while (i < s.length) {
                    val lt = s.indexOf('<', i)
                    if (lt < 0) break
                    i = when {
                        s.startsWith("<!--", lt) -> end(s.indexOf("-->", lt + 4), 3)
                        s.startsWith("<![CDATA[", lt) -> end(s.indexOf("]]>", lt + 9), 3)
                        s.startsWith("<?", lt) -> end(s.indexOf("?>", lt + 2), 2)
                        s.startsWith("<!", lt) -> end(s.indexOf('>', lt + 2), 1)
                        s.startsWith("</", lt) -> {
                            val close = s.indexOf('>', lt + 2)
                            if (ignored > 0) ignored-- // closes the innermost skipped element, whatever its name
                            else {
                                val name = localName(s.substring(lt + 2, if (close < 0) s.length else close).trim())
                                val at = stack.indexOfLast { it.first == name }
                                if (at >= 0) while (stack.size > at) stack.removeAt(stack.size - 1)
                            }
                            end(close, 1)
                        }
                        else -> {
                            val tag = tag(lt) ?: break
                            if (stack.size >= MAX_DEPTH) { if (!tag.selfClosing) ignored++ }
                            else {
                                val parent = stack.lastOrNull()?.second ?: Style(Affine(), filled = true, evenOdd = false, skip = false)
                                val style = element(tag.name, tag.attributes, parent)
                                if (!tag.selfClosing) stack.add(tag.name to style)
                            }
                            tag.end
                        }
                    }
                }
            } catch (_: TooComplex) { return null }
            return Document(viewBox, width, height, shapes)
        }

        private fun end(index: Int, length: Int) = if (index < 0) s.length else index + length

        private class Tag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean, val end: Int)

        private fun localName(raw: String) = raw.substringAfterLast(':').lowercase()

        /** Start tag at [lt]; null when it never ends. */
        private fun tag(lt: Int): Tag? {
            var i = lt + 1
            val nameStart = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            if (i == nameStart) return null
            val name = localName(s.substring(nameStart, i))
            val attributes = HashMap<String, String>()
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) return null
                if (s[i] == '>') return Tag(name, attributes, false, i + 1)
                if (s[i] == '/') { val close = s.indexOf('>', i); return if (close < 0) null else Tag(name, attributes, true, close + 1) }
                val keyStart = i
                while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/') i++
                val key = s.substring(keyStart, i).substringAfterLast(':')
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == '=') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i >= s.length) return null
                    val quote = s[i]
                    if (quote == '"' || quote == '\'') {
                        val close = s.indexOf(quote, i + 1)
                        if (close < 0) return null
                        attributes[key] = unescape(s.substring(i + 1, close)); i = close + 1
                    } else {
                        val valueStart = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                        attributes[key] = unescape(s.substring(valueStart, i))
                    }
                }
            }
            return null
        }

        private fun unescape(v: String) = if ('&' !in v) v else
            v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private fun element(name: String, a: Map<String, String>, parent: Style): Style {
            val declared = a["style"]?.split(';')?.mapNotNull { decl ->
                val k = decl.substringBefore(':', "").trim().lowercase(); val v = decl.substringAfter(':', "").trim()
                if (k.isEmpty()) null else k to v
            }?.toMap().orEmpty()
            fun prop(key: String) = declared[key] ?: a[key]?.trim()
            val skip = parent.skip || prop("display") == "none" || name in SKIPPED
            val fill = prop("fill")
            val filled = if (fill == null || fill == "inherit") parent.filled else fill != "none" && fill != "transparent"
            val evenOdd = when (prop("fill-rule")) { "evenodd" -> true; "nonzero" -> false; else -> parent.evenOdd }
            if (name == "svg" && !rootSeen) {
                rootSeen = true
                viewBox = a["viewBox"]?.let(::parseViewBox)
                width = a["width"]?.let(::length)?.takeIf { it > 0 }
                height = a["height"]?.let(::length)?.takeIf { it > 0 }
                return Style(parent.ctm, filled, evenOdd, skip)
            }
            val ctm = parent.ctm * (a["transform"]?.let(::parseTransform) ?: Affine())
            val style = Style(ctm, filled, evenOdd, skip)
            if (skip) return style
            val paths: List<SubPath>? = when (name) {
                "path" -> a["d"]?.let { PathData(it, ::count).read() }
                "rect" -> rect(a)
                "circle" -> num(a["r"])?.takeIf { it > 0 }?.let { r -> ellipse(num(a["cx"]) ?: 0.0, num(a["cy"]) ?: 0.0, r, r) }
                "ellipse" -> {
                    val rx = num(a["rx"]); val ry = num(a["ry"])
                    if (rx != null && ry != null && rx > 0 && ry > 0) ellipse(num(a["cx"]) ?: 0.0, num(a["cy"]) ?: 0.0, rx, ry) else null
                }
                "polygon", "polyline" -> a["points"]?.let { poly(it, name == "polygon") }
                "line" -> {
                    count(1)
                    listOf(SubPath(Point(num(a["x1"]) ?: 0.0, num(a["y1"]) ?: 0.0), mutableListOf(Line(Point(num(a["x2"]) ?: 0.0, num(a["y2"]) ?: 0.0)))))
                }
                else -> null
            }
            // A line has no area: never filled.
            if (!paths.isNullOrEmpty()) shapes.add(RawShape(paths.map { transform(it, ctm) }, filled && name != "line", evenOdd))
            return style
        }

        private fun count(n: Int) { segments += n; if (segments > MAX_POINTS) throw TooComplex() }

        private fun transform(p: SubPath, m: Affine): SubPath = SubPath(m.apply(p.start), p.segments.mapTo(mutableListOf()) { s ->
            when (s) { is Line -> Line(m.apply(s.to)); is Cubic -> Cubic(m.apply(s.c1), m.apply(s.c2), m.apply(s.to)) }
        }, p.closed)

        private fun rect(a: Map<String, String>): List<SubPath>? {
            val x = num(a["x"]) ?: 0.0; val y = num(a["y"]) ?: 0.0
            val w = num(a["width"]) ?: return null; val h = num(a["height"]) ?: return null
            if (w <= 0 || h <= 0) return null
            count(4)
            return listOf(SubPath(Point(x, y), mutableListOf(Line(Point(x + w, y)), Line(Point(x + w, y + h)), Line(Point(x, y + h)), Line(Point(x, y))), closed = true))
        }

        private fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double): List<SubPath> {
            count(4)
            val k = 0.5522847498307936
            val segs = mutableListOf<Segment>(
                Cubic(Point(cx + rx, cy + k * ry), Point(cx + k * rx, cy + ry), Point(cx, cy + ry)),
                Cubic(Point(cx - k * rx, cy + ry), Point(cx - rx, cy + k * ry), Point(cx - rx, cy)),
                Cubic(Point(cx - rx, cy - k * ry), Point(cx - k * rx, cy - ry), Point(cx, cy - ry)),
                Cubic(Point(cx + k * rx, cy - ry), Point(cx + rx, cy - k * ry), Point(cx + rx, cy)))
            return listOf(SubPath(Point(cx + rx, cy), segs, closed = true))
        }

        private fun poly(raw: String, closed: Boolean): List<SubPath>? {
            val n = Numbers(raw); val pts = ArrayList<Point>()
            while (true) { val x = n.number() ?: break; val y = n.number() ?: break; pts.add(Point(x, y)); count(1) }
            if (pts.size < 2) return null
            return listOf(SubPath(pts[0], pts.drop(1).mapTo(mutableListOf()) { Line(it) }, closed))
        }
    }

    // ---- Attribute values ----

    private fun num(raw: String?): Double? = raw?.let(::length)

    /** Length without unit or in px, pt…; a percentage gives null. */
    private fun length(raw: String): Double? {
        val t = raw.trim()
        if (t.endsWith("%")) return null
        return Numbers(t).number()
    }

    private fun parseViewBox(raw: String): ViewBox? {
        val n = Numbers(raw)
        val v = List(4) { n.number() ?: return null }
        return if (v[2] > 0 && v[3] > 0) ViewBox(v[0], v[1], v[2], v[3]) else null
    }

    /** `transform` list; an unreadable list counts as none (SVG rule). */
    internal fun parseTransform(raw: String): Affine? {
        var m = Affine()
        var i = 0
        while (true) {
            while (i < raw.length && (raw[i].isWhitespace() || raw[i] == ',')) i++
            if (i >= raw.length) break
            val open = raw.indexOf('(', i); val close = raw.indexOf(')', i)
            if (open < 0 || close < open) return null
            val n = Numbers(raw.substring(open + 1, close)); val v = ArrayList<Double>()
            while (true) v.add(n.number() ?: break)
            val t = when (raw.substring(i, open).trim()) {
                "matrix" -> if (v.size == 6) Affine(v[0], v[1], v[2], v[3], v[4], v[5]) else return null
                "translate" -> if (v.size in 1..2) Affine(e = v[0], f = v.getOrElse(1) { 0.0 }) else return null
                "scale" -> if (v.size in 1..2) Affine(a = v[0], d = v.getOrElse(1) { v[0] }) else return null
                "rotate" -> if (v.size == 1 || v.size == 3) {
                    val r = Math.toRadians(v[0]); val cs = cos(r); val sn = sin(r)
                    val rot = Affine(cs, sn, -sn, cs)
                    if (v.size == 3) Affine(e = v[1], f = v[2]) * rot * Affine(e = -v[1], f = -v[2]) else rot
                } else return null
                "skewX" -> if (v.size == 1) Affine(c = tan(Math.toRadians(v[0]))) else return null
                "skewY" -> if (v.size == 1) Affine(b = tan(Math.toRadians(v[0]))) else return null
                else -> return null
            }
            m *= t
            i = close + 1
        }
        return m.takeIf { it.finite }
    }

    /** Number scanner of the SVG attributes: spaces and commas separate, numbers may be packed ("10-5", ".5.5", "1e-3"). */
    private class Numbers(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun atNumber(): Boolean { skip(); return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.') }
        fun number(): Double? {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var digits = 0
            while (i < s.length && s[i].isDigit()) { i++; digits++ }
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) { i++; digits++ } }
            if (digits == 0) { i = start; return null }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '-' || s[j] == '+')) j++
                if (j < s.length && s[j].isDigit()) { while (j < s.length && s[j].isDigit()) j++; i = j }
            }
            return s.substring(start, i).toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        /** Arc flag: a single 0 or 1, possibly packed with what follows. */
        fun flag(): Boolean? { skip(); if (i < s.length && (s[i] == '0' || s[i] == '1')) return s[i++] == '1'; return null }
    }

    /** `d` attribute reader; on an error, what was read before it is kept (SVG rule). */
    private class PathData(private val s: String, private val count: (Int) -> Unit) {
        private val n = Numbers(s)
        private val out = mutableListOf<SubPath>()
        private var sub: SubPath? = null
        private var cx = 0.0; private var cy = 0.0
        private var startX = 0.0; private var startY = 0.0
        private var lastC2: Point? = null; private var lastQ: Point? = null

        fun read(): List<SubPath> {
            var cmd = ' '
            while (true) {
                n.skip()
                if (n.i >= s.length) break
                val ch = s[n.i]
                if (ch.isLetter()) { cmd = ch; n.i++ }
                else if (!n.atNumber() || cmd == ' ' || cmd == 'z' || cmd == 'Z') break
                if (!command(cmd)) break
                // Coordinate pairs after a moveto are linetos.
                if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            }
            return out.filter { it.segments.isNotEmpty() }
        }

        /** Current subpath, a new one at the current point after a closepath. */
        private fun open(): SubPath = sub ?: SubPath(Point(cx, cy)).also { sub = it; out.add(it); startX = cx; startY = cy }

        private fun lineTo(x: Double, y: Double) { count(1); open().segments.add(Line(Point(x, y))); cx = x; cy = y }

        private fun cubicTo(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
            count(1); open().segments.add(Cubic(Point(x1, y1), Point(x2, y2), Point(x, y))); cx = x; cy = y
        }

        /** Executes one command with its arguments; false when they cannot be read. */
        private fun command(cmd: Char): Boolean {
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0.0; val oy = if (rel) cy else 0.0
            var c2: Point? = null; var q: Point? = null
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = n.number() ?: return false; val y = n.number() ?: return false
                    cx = ox + x; cy = oy + y
                    sub = SubPath(Point(cx, cy)).also { out.add(it) }; startX = cx; startY = cy
                }
                'L' -> { val x = n.number() ?: return false; val y = n.number() ?: return false; lineTo(ox + x, oy + y) }
                'H' -> { val x = n.number() ?: return false; lineTo(ox + x, cy) }
                'V' -> { val y = n.number() ?: return false; lineTo(cx, oy + y) }
                'C' -> {
                    val v = List(6) { n.number() ?: return false }
                    cubicTo(ox + v[0], oy + v[1], ox + v[2], oy + v[3], ox + v[4], oy + v[5]); c2 = Point(ox + v[2], oy + v[3])
                }
                'S' -> {
                    val v = List(4) { n.number() ?: return false }
                    val r = lastC2?.let { Point(2 * cx - it.x, 2 * cy - it.y) } ?: Point(cx, cy)
                    c2 = Point(ox + v[0], oy + v[1])
                    cubicTo(r.x, r.y, c2.x, c2.y, ox + v[2], oy + v[3])
                }
                'Q' -> {
                    val v = List(4) { n.number() ?: return false }
                    q = Point(ox + v[0], oy + v[1])
                    quadTo(q.x, q.y, ox + v[2], oy + v[3])
                }
                'T' -> {
                    val v = List(2) { n.number() ?: return false }
                    q = lastQ?.let { Point(2 * cx - it.x, 2 * cy - it.y) } ?: Point(cx, cy)
                    quadTo(q.x, q.y, ox + v[0], oy + v[1])
                }
                'A' -> {
                    val rx = n.number() ?: return false; val ry = n.number() ?: return false; val rot = n.number() ?: return false
                    val large = n.flag() ?: return false; val sweep = n.flag() ?: return false
                    val x = n.number() ?: return false; val y = n.number() ?: return false
                    arcTo(rx, ry, rot, large, sweep, ox + x, oy + y)
                }
                'Z' -> { sub?.closed = true; cx = startX; cy = startY; sub = null }
                else -> return false
            }
            lastC2 = c2; lastQ = q
            return true
        }

        private fun quadTo(qx: Double, qy: Double, x: Double, y: Double) =
            cubicTo(cx + 2.0 / 3 * (qx - cx), cy + 2.0 / 3 * (qy - cy), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y)

        /** Elliptical arc as cubic pieces of at most 90° (SVG 1.1, appendix F.6). */
        private fun arcTo(rx0: Double, ry0: Double, angle: Double, large: Boolean, sweep: Boolean, x: Double, y: Double) {
            val x1 = cx; val y1 = cy
            if (x1 == x && y1 == y) return
            var rx = abs(rx0); var ry = abs(ry0)
            if (rx == 0.0 || ry == 0.0) { lineTo(x, y); return }
            val phi = Math.toRadians(angle % 360.0); val cs = cos(phi); val sn = sin(phi)
            val dx = (x1 - x) / 2; val dy = (y1 - y) / 2
            val x1p = cs * dx + sn * dy; val y1p = -sn * dx + cs * dy
            val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
            if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
            if (large == sweep) coef = -coef
            val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
            val ccx = cs * cxp - sn * cyp + (x1 + x) / 2; val ccy = sn * cxp + cs * cyp + (y1 + y) / 2
            val theta1 = vectorAngle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
            var delta = vectorAngle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
            if (!sweep && delta > 0) delta -= 2 * Math.PI else if (sweep && delta < 0) delta += 2 * Math.PI
            val pieces = ceil(abs(delta) / (Math.PI / 2) - 1e-9).toInt().coerceIn(1, 4)
            val step = delta / pieces
            val k = 4.0 / 3 * tan(step / 4)
            fun at(t: Double) = Point(ccx + rx * cos(t) * cs - ry * sin(t) * sn, ccy + rx * cos(t) * sn + ry * sin(t) * cs)
            fun tangent(t: Double) = Point(-rx * sin(t) * cs - ry * cos(t) * sn, -rx * sin(t) * sn + ry * cos(t) * cs)
            var t = theta1
            repeat(pieces) { i ->
                val t2 = t + step
                val p0 = at(t); val d0 = tangent(t)
                val p3 = if (i == pieces - 1) Point(x, y) else at(t2); val d3 = tangent(t2)
                cubicTo(p0.x + k * d0.x, p0.y + k * d0.y, p3.x - k * d3.x, p3.y - k * d3.y, p3.x, p3.y)
                t = t2
            }
        }

        private fun vectorAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            val a = acos(((ux * vx + uy * vy) / len).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
    }
}
