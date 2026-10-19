package fr.luba.poc.map

import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.RobotRoute
import fr.luba.poc.simulation.RouteIntent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/** Debug mowing: zone painted with a finger, limited to the existing zones, prepared like real mowing. */
class PaintedZoneTest {
    private val shapes: List<MapShape> = run {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
    }
    private val map = RobotMap(shapes, true, "reference-station")
    private val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
    private val factory = GeometryFactory()
    private fun area(points: List<Point>) = factory.createPolygon((points + points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())

    @Test fun paintingOutsideTheZonesIsIgnored() {
        // Aire de jeux ends at x ≈ 3.1 m: a stroke entirely beyond it gives nothing.
        assertTrue(PaintedZone.pieces(map, listOf(PaintStroke(listOf(Point(8.0, 6.0), Point(12.0, 6.0)), 1.0))).isEmpty())
    }

    @Test fun pieceIsClippedToTheExistingZones() {
        // Stroke overflowing north of Aire de jeux (boundary near y = 10.4 m): only the inner part is kept.
        val stroke = PaintStroke(listOf(Point(-11.0, 7.0), Point(-11.0, 14.0)), 1.0)
        val pieces = PaintedZone.pieces(map, listOf(stroke))
        assertTrue(pieces.isNotEmpty())
        val zones = PaintedZone.zonesArea(map)
        val painted = PaintedZone.painted(listOf(stroke))
        pieces.forEach { piece ->
            val g = area(piece)
            assertTrue("Simple and valid boundary", g.isValid)
            assertTrue("Piece outside the zones", zones.buffer(0.05).covers(g))
            assertTrue("Piece outside the paint", painted.buffer(0.05).covers(g))
        }
        val kept = pieces.sumOf { area(it).area }
        assertTrue("Kept area $kept", kept > 5.0 && kept < painted.area * 0.9)
    }

    @Test fun eraserRemovesPaintAndHolesAreSplitIntoSimpleContours() {
        val disc = PaintStroke(listOf(Point(-12.0, -4.0)), 3.0)
        val eraser = PaintStroke(listOf(Point(-12.0, -4.0)), 1.0, erase = true)
        val ring = PaintedZone.pieces(map, listOf(disc, eraser))
        assertTrue("Ring split into boundaries without holes", ring.size >= 2)
        val full = PaintedZone.pieces(map, listOf(disc)).sumOf { area(it).area }
        val kept = ring.sumOf { area(it).area }
        assertEquals(Math.PI * (9 - 1), kept, 1.0)
        assertTrue(kept < full)
        // The hole is not filled: its center belongs to no piece.
        assertTrue(ring.none { Geometry.inside(Point(-12.0, -4.0), it) })
        // The pieces do not overlap.
        val union = factory.buildGeometry(ring.map(::area)).union()
        assertEquals(kept, union.area, 0.05)
        assertTrue(PaintedZone.pieces(map, listOf(disc, PaintStroke(listOf(Point(-12.0, -4.0)), 4.0, erase = true))).isEmpty())
    }

    @Test(timeout = 120_000) fun paintedZoneIsPlannedAndPreparedLikeARealMowing() {
        val strokes = listOf(PaintStroke(listOf(Point(-15.0, -4.0), Point(-10.0, -4.0), Point(-10.0, -2.0)), 1.2))
        val mission = PaintedZone.prepare(map, strokes, base.point, base, emptyList(), emptyList(),
            spacingM = 0.25, borders = true, speedMps = 0.3)
        assertTrue(mission.zones.all { it.id < 0 && it.kind == ShapeKind.ZONE })
        assertTrue(mission.zones.none { z -> shapes.any { it.id == z.id } })
        assertFalse(mission.preview.localOnly)
        assertTrue(mission.report!!.selected.valid)
        // Preparation on the unchanged robot map: the verified route does not depend on the temporary zones.
        assertSame(map, mission.engine.map)
        val edges = mission.engine.edges
        mission.engine.routes.requirePath(listOf(edges.first().a) + edges.map { it.b })
        // Cutting passes stay inside the paint (within the radius) and inside the zones.
        val painted = PaintedZone.painted(strokes).buffer(0.05)
        edges.filter { it.cutting }.forEach { e ->
            assertTrue(painted.covers(factory.createPoint(Coordinate(e.a.x, e.a.y))))
            assertTrue(painted.covers(factory.createPoint(Coordinate(e.b.x, e.b.y))))
        }
        assertTrue(mission.areaM2 > 5.0)
        // Blades running continuously in each temporary zone: a single blades-on range per zone, all internal links
        // included.
        val ranges = edges.zipWithNext().count { (x, y) -> !x.bladesOn && y.bladesOn } + if (edges.first().bladesOn) 1 else 0
        println("PaintedZone : ${mission.zones.size} zone(s), ${ranges} plage(s) lames en marche, ${edges.count { !it.cutting && it.bladesOn }} arêtes de liaison lames en marche")
        assertTrue(edges.any { !it.cutting && it.bladesOn })
        assertTrue("Ranges $ranges for ${mission.zones.size} zone(s)", ranges <= mission.zones.size)
    }

    /** Capture of 2026-10-10 15:10: painting in Aire de jeux against the north boundary, around the elongated no-go
     * zone and the two small no-go zones, robot starting in the east corridor of Piscine. */
    private val screenshotOutline = listOf(Point(-1.542, 7.448), Point(-1.209, 7.206), Point(-0.862, 6.681), Point(-0.08, 6.331),
        Point(0.685, 5.684), Point(0.288, 5.337), Point(0.397, 5.112), Point(0.782, 4.941), Point(0.951, 4.673), Point(0.913, 4.246),
        Point(0.661, 3.975), Point(0.746, 3.641), Point(0.503, 3.181), Point(0.16, 2.885), Point(-0.854, 2.44), Point(-1.01, 2.099),
        Point(-1.405, 1.931), Point(-1.924, 2.218), Point(-2.273, 2.562), Point(-2.811, 2.498), Point(-3.503, 2.547), Point(-4.082, 2.75),
        Point(-4.498, 3.179), Point(-4.898, 3.379), Point(-5.066, 3.773), Point(-5.233, 4.168), Point(-5.196, 4.595), Point(-4.972, 4.978),
        Point(-4.637, 5.189), Point(-4.266, 5.374), Point(-3.802, 5.763), Point(-3.251, 6.272), Point(-2.575, 6.326), Point(-2.17, 6.759))

    /** Fills a boundary with horizontal brush strokes, like a finger colouring in. */
    private fun fill(outline: List<Point>, radius: Double): List<PaintStroke> {
        val g = area(outline)
        val env = g.envelopeInternal
        return generateSequence(env.minY + radius / 2) { it + radius }.takeWhile { it < env.maxY }.mapNotNull { y ->
            val line = factory.createLineString(arrayOf(Coordinate(env.minX - 1, y), Coordinate(env.maxX + 1, y)))
            val cut = g.intersection(line)
            (0 until cut.numGeometries).map { cut.getGeometryN(it) }.filter { it.length > 0 }.map { part ->
                PaintStroke(part.coordinates.map { Point(it.x, it.y) }, radius) }.takeIf { it.isNotEmpty() }
        }.flatten().toList()
    }

    @Test(timeout = 300_000) fun noGoInsideThePaintingWithALearnedObstacleNextToIt() {
        // Observed failure: "Collision du châssis" — the loop of the elongated no-go zone went through the reserve of a
        // learned obstacle stuck to the no-go zone, and the whole computation was refused.
        val strokes = fill(screenshotOutline, 0.5)
        val obstacle = LocalObstacle("swing", ObstacleKind.SWING, -3.47, 3.82, radiusM = 0.27, marginM = 0.2)
        val realBase = MapBase(Point(-0.5365, 1.0944), "Journal du 10/10", 1.05, 152.9, 1.5)
        val start = Point(-12.573, -1.9334)
        val mission = PaintedZone.prepare(map, strokes, start, realBase, emptyList(), listOf(obstacle),
            spacingM = 0.25, borders = true, speedMps = 0.3)
        assertTrue(mission.report!!.selected.valid)
        // Enclosed no-go zones keep their loop, which bypasses the obstacle.
        assertTrue("No-go loops", mission.preview.noGoLoops >= 1)
        val disc = obstacle.keepOutDisc(fr.luba.poc.obstacles.RobotEnvelope.LUBA2)
        val edges = mission.engine.edges
        edges.forEach { e -> assertTrue(Geometry.distance(Point(disc.x, disc.y), e.a, e.b) >= disc.radiusM - 0.02) }
        // No edge inside a no-go zone.
        val noGo = shapes.filter { it.kind == ShapeKind.NO_GO }.map { area(it.points) }
        edges.forEach { e ->
            val segment = factory.createLineString(arrayOf(Coordinate(e.a.x, e.a.y), Coordinate(e.b.x, e.b.y)))
            assertTrue(noGo.none { it.intersects(segment) })
        }
        // Start from the real robot pose (heading 31°), with the physical obstacles.
        val physical = fr.luba.poc.planning.RoutePlanning.navigation(map, fr.luba.poc.obstacles.RobotEnvelope.LUBA2.halfWidthM + 0.05,
            shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }, recordedBoundary = true)
        assertTrue(RobotRoute.actionEdges(mission.engine, start, 30.98, RouteIntent.FOLLOW, physical).isNotEmpty())
        // Displayed area and outline exclude no-go zones: the three enclosed no-go zones are circled holes.
        val pieces = PaintedZone.pieces(map, strokes)
        val mowable = PaintedZone.mowable(map, pieces)
        assertEquals(mowable.area, mission.areaM2, 1e-6)
        val noGoArea = pieces.sumOf { area(it).area } - mowable.area
        assertTrue("Enclosed no-go zones excluded: %.2f m²".format(noGoArea), noGoArea > 1.5)
        assertTrue(PaintedZone.outlines(mowable).size >= 4)
    }

    @Test fun preparationRefusesAnEmptyPainting() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PaintedZone.prepare(map, listOf(PaintStroke(listOf(Point(10.0, 6.0)), 1.0)), base.point, base, emptyList(), emptyList(),
                spacingM = 0.25, borders = true, speedMps = 0.3)
        }
        assertTrue(error.message!!.startsWith("Aucune surface peinte"))
    }
}
