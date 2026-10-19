package fr.luba.poc.map

import fr.luba.poc.planning.BoundaryMeaning
import fr.luba.poc.planning.PlanningConfig
import fr.luba.poc.planning.PlanningNavigation
import fr.luba.poc.planning.PlanningRequest
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.MapBase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Local map corrections: layer applied on the Mammotion map without modifying it, each correction undoable on its own,
 * readable refusals, saved format read back, and corrected map accepted by the planner.
 */
class MapCorrectionsTest {
    private val zone = MapShape(10L, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 8.0), Point(0.0, 8.0)), "Pelouse")
    private val noGo = MapShape(20L, 1, listOf(Point(4.0, 3.0), Point(6.0, 3.0), Point(6.0, 5.0), Point(4.0, 5.0)))
    private val raw = RobotMap(listOf(zone, noGo), true, "test")
    private var nextId = 1L

    private fun patch(target: MapShape, after: List<Point>, before: List<Point> = target.points): ShapePatch {
        val (add, cut) = MapCorrections.diff(before, after)
        return ShapePatch(nextId++, 0L, "retouche", target.id, MapCorrections.fingerprint(target), add, cut, CorrectionTool.POINTS)
    }

    private fun shape(map: RobotMap, id: Long) = map.shapes.single { it.id == id }
    private fun area(points: List<Point>) = MapCorrections.polygonArea(points)

    @Test fun noCorrectionKeepsTheMammotionMapUntouched() {
        val result = MapCorrections.apply(raw, emptyList())
        assertSame(raw, result.map)
    }

    @Test fun movingAPointEnlargesTheZoneAndKeepsItsIdentity() {
        val moved = PointEditing.move(zone.points, 1, Point(12.0, 0.0))
        val c = patch(zone, moved)
        val result = MapCorrections.apply(raw, listOf(c))
        val edited = shape(result.map, zone.id)
        assertEquals(CorrectionStatus.APPLIED, result.status[c.id])
        assertEquals(80.0 + 8.0, area(edited.points), 1e-3)
        assertEquals("Pelouse", edited.name)
        assertEquals(0, edited.type)
        assertEquals("Same starting point as the Mammotion boundary", zone.points.first(), edited.points.first())
        // The Mammotion map is not modified.
        assertEquals(80.0, area(raw.shapes.first().points), 1e-9)
        assertEquals(listOf(c.id), result.edited[zone.id])
    }

    @Test fun anOldCorrectionCanBeRemovedAloneWithoutTouchingTheNextOnes() {
        val first = patch(zone, PointEditing.move(zone.points, 1, Point(12.0, 0.0)))
        val afterFirst = shape(MapCorrections.apply(raw, listOf(first)).map, zone.id)
        // Second edit, on the opposite corner, made after the first one.
        val i = afterFirst.points.indexOfFirst { it == Point(0.0, 8.0) }
        val second = patch(zone, PointEditing.move(afterFirst.points, i, Point(0.0, 10.0)), afterFirst.points)
        val both = shape(MapCorrections.apply(raw, listOf(first, second)).map, zone.id)
        assertEquals(80.0 + 8.0 + 10.0, area(both.points), 1e-3)
        // Undo only the first one: the second stays applied.
        val onlySecond = shape(MapCorrections.apply(raw, listOf(second)).map, zone.id)
        assertEquals(80.0 + 10.0, area(onlySecond.points), 1e-3)
        // Disabling amounts to the same, without losing the correction.
        val disabled = MapCorrections.apply(raw, listOf(first.withEnabled(false), second))
        assertEquals(CorrectionStatus.DISABLED, disabled.status[first.id])
        assertEquals(80.0 + 10.0, area(shape(disabled.map, zone.id).points), 1e-3)
    }

    @Test fun aPointMovedBackLeavesNoCrumbs() {
        val dense = zone.copy(points = (0 until 40).map { Point(it * 0.25, 0.0) } + listOf(Point(10.0, 0.0), Point(10.0, 8.0), Point(0.0, 8.0)))
        val moved = PointEditing.move(dense.points, 7, Point(1.75, 0.3))
        val back = PointEditing.move(moved, 7, Point(1.75, 0.0))
        val (add, cut) = MapCorrections.diff(dense.points, back)
        assertEquals("", add)
        assertEquals("", cut)
    }

    @Test fun reappliedPatchIsIdempotentWhenMammotionAlreadyFixedIt() {
        val enlarged = PointEditing.move(zone.points, 1, Point(12.0, 0.0))
        val c = patch(zone, enlarged)
        // Zone recorded again on the Mammotion side with the same correction: applying it changes nothing any more.
        val fixed = raw.copy(shapes = listOf(zone.copy(points = enlarged), noGo))
        val result = MapCorrections.apply(fixed, listOf(c))
        assertEquals(CorrectionStatus.CHANGED, result.status[c.id])
        assertEquals(88.0, area(shape(result.map, zone.id).points), 1e-3)
    }

    @Test fun edgeRedrawReplacesTheShorterPartOfTheContour() {
        // Straight edge drawn from (10,2) to (10,6) on a zone whose edge bulges outwards there.
        val wavy = zone.copy(points = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 2.0), Point(10.6, 3.0),
            Point(10.4, 5.0), Point(10.0, 6.0), Point(10.0, 8.0), Point(0.0, 8.0)))
        val redrawn = requireNotNull(EdgeRedraw.replace(wavy.points, listOf(Point(9.95, 2.0), Point(9.95, 6.0))))
        assertEquals(80.0, area(redrawn), 1e-6)
        assertFalse(redrawn.any { it.x > 10.0 + 1e-9 })
        val other = requireNotNull(EdgeRedraw.replace(wavy.points, listOf(Point(9.95, 2.0), Point(9.95, 6.0)), otherSide = true))
        assertTrue("The other side keeps only the small bump", area(other) < 5.0)
        // Coinciding ends: no edge to replace.
        assertNull(EdgeRedraw.replace(wavy.points, listOf(Point(10.0, 2.0), Point(10.01, 2.0))))
    }

    @Test fun shapesAreAddedAndRemoved() {
        val square = PrimitiveShape(PrimitiveKind.SQUARE, Point(10.0, 4.0), 2.0, 2.0)
        val add = ShapePatch(nextId++, 0L, "carré", zone.id, null, MapCorrections.primitiveWkt(square), "", CorrectionTool.SHAPE, square)
        assertEquals(82.0, area(shape(MapCorrections.apply(raw, listOf(add)).map, zone.id).points), 1e-3)
        val corner = PrimitiveShape(PrimitiveKind.TRIANGLE, Point(0.0, 8.0), 2.0, 2.0, 45.0)
        val cut = ShapePatch(nextId++, 0L, "triangle", zone.id, null, "", MapCorrections.primitiveWkt(corner), CorrectionTool.SHAPE, corner, removing = true)
        val cutArea = area(shape(MapCorrections.apply(raw, listOf(cut)).map, zone.id).points)
        assertTrue(cutArea < 80.0 && cutArea > 77.0)
        // A removal in the middle of the zone would make a hole: refused, the zone stays intact.
        val middle = PrimitiveShape(PrimitiveKind.CIRCLE, Point(2.0, 2.0), 1.0, 1.0)
        val hole = ShapePatch(nextId++, 0L, "rond", zone.id, null, "", MapCorrections.primitiveWkt(middle), CorrectionTool.SHAPE, middle, removing = true)
        val refused = MapCorrections.apply(raw, listOf(hole))
        assertEquals(CorrectionStatus.REFUSED, refused.status[hole.id])
        assertTrue(refused.reasons[hole.id]!!.contains("no-go"))
        assertEquals(80.0, area(shape(refused.map, zone.id).points), 1e-9)
    }

    @Test fun primitiveRotationAndSize() {
        val s = PrimitiveShape(PrimitiveKind.SQUARE, Point(1.0, 1.0), 4.0, 2.0, 90.0)
        val pts = s.polygon()
        assertEquals(2.0, pts.maxOf { it.x } - pts.minOf { it.x }, 1e-9)
        assertEquals(4.0, pts.maxOf { it.y } - pts.minOf { it.y }, 1e-9)
        assertEquals(Point(2.0, 1.0).x, s.toMap(s.toLocal(Point(2.0, 1.0))).x, 1e-9)
        assertEquals("⌀ 1,2 m", PrimitiveShape(PrimitiveKind.CIRCLE, Point(0.0, 0.0), 1.2, 1.2).sizeText())
        assertEquals(Math.PI * 0.36, area(PrimitiveShape(PrimitiveKind.CIRCLE, Point(0.0, 0.0), 1.2, 1.2).polygon()), 0.02)
    }

    @Test fun offsetMovesTheWholeContour() {
        val out = ShapeOffset(nextId++, 0L, "décalage", zone.id, null, 0.5)
        assertEquals(11.0 * 9.0, area(shape(MapCorrections.apply(raw, listOf(out)).map, zone.id).points), 1e-3)
        val inNoGo = ShapeOffset(nextId++, 0L, "marge", noGo.id, null, -0.5)
        assertEquals(1.0, area(shape(MapCorrections.apply(raw, listOf(inNoGo)).map, noGo.id).points), 1e-3)
        // A Mammotion no-go zone cannot be shrunk to nothing.
        val gone = ShapeOffset(nextId++, 0L, "trop", noGo.id, null, -1.0)
        assertEquals(CorrectionStatus.REFUSED, MapCorrections.apply(raw, listOf(gone)).status[gone.id])
    }

    @Test fun addedNoGoGetsAStableUniqueIdAndCanBeEdited() {
        val tree = PrimitiveShape(PrimitiveKind.CIRCLE, Point(8.0, 6.0), 1.0, 1.0)
        val added = AddedNoGo(nextId++, 0L, "Arbre", tree.polygon(), NoGoOrigin.OBSTACLE, tree)
        val margin = ShapeOffset(nextId++, 0L, "marge", added.shapeId, null, 0.2)
        val result = MapCorrections.apply(raw, listOf(added, margin))
        val shape = shape(result.map, added.shapeId)
        assertEquals(ShapeKind.NO_GO, shape.kind)
        assertEquals("Arbre", shape.name)
        assertTrue(shape.id < -2)
        assertEquals(Math.PI * 0.49, area(shape.points), 0.03)
        assertEquals(result.map.shapes.size, result.map.shapes.map { it.id }.distinct().size)
        // Deleting the added no-go zone also removes its edits.
        assertEquals(2, MapCorrections.dependents(listOf(added, margin), added.id))
        assertTrue(MapCorrections.remove(listOf(added, margin), added.id).isEmpty())
        assertEquals(listOf(added), MapCorrections.remove(listOf(added, margin), margin.id))
    }

    @Test fun crossedOrSplittingCorrectionsAreRefused() {
        val crossed = listOf(Point(0.0, 0.0), Point(2.0, 2.0), Point(2.0, 0.0), Point(0.0, 2.0))
        assertTrue(MapCorrections.check(crossed, ShapeKind.NO_GO) is ShapeResult.Refused)
        val band = PrimitiveShape(PrimitiveKind.SQUARE, Point(5.0, 4.0), 12.0, 1.0)
        val split = ShapePatch(nextId++, 0L, "coupe", zone.id, null, "", MapCorrections.primitiveWkt(band), CorrectionTool.SHAPE, band, true)
        val result = MapCorrections.apply(raw, listOf(split))
        assertEquals(CorrectionStatus.REFUSED, result.status[split.id])
        assertTrue(result.reasons[split.id]!!.contains("morceaux"))
    }

    @Test fun missingTargetIsInactive() {
        val c = ShapeOffset(nextId++, 0L, "décalage", 999L, null, 0.5)
        val result = MapCorrections.apply(raw, listOf(c))
        assertEquals(CorrectionStatus.MISSING, result.status[c.id])
        assertSame(raw, result.map)
        // Incomplete map (reading in progress): nothing is applied.
        assertSame(raw.copy(complete = false).shapes, MapCorrections.apply(raw.copy(complete = false), listOf(c)).map.shapes)
    }

    @Test fun jsonRoundTripsAndSkipsBrokenEntries() {
        val square = PrimitiveShape(PrimitiveKind.SQUARE, Point(10.0, 4.0), 2.0, 1.5, 30.0)
        val list = listOf(
            patch(zone, PointEditing.move(zone.points, 1, Point(12.0, 0.0))),
            ShapePatch(nextId++, 5L, "carré", zone.id, 42L, MapCorrections.primitiveWkt(square), "", CorrectionTool.SHAPE, square),
            ShapeOffset(nextId++, 6L, "décalage", noGo.id, null, -0.25, enabled = false),
            AddedNoGo(nextId++, 7L, "Poteau", square.polygon(), NoGoOrigin.SHAPE, square),
        )
        val text = MapCorrectionJson.encode(list)
        assertEquals(list, MapCorrectionJson.decode(text))
        val json = JSONObject(text)
        json.getJSONArray("corrections").put(JSONObject().put("id", 99).put("type", "inconnu"))
        json.getJSONArray("corrections").put(JSONObject().put("id", 98).put("type", "patch").put("target", "1").put("tool", "POINTS").put("add", "POLYGON (("))
        assertEquals(list, MapCorrectionJson.decode(json.toString()))
        assertTrue(MapCorrectionJson.decode("pas du json").isEmpty())
        assertTrue(MapCorrectionJson.decode(JSONObject().put("schemaVersion", 99).toString()).isEmpty())
    }

    @Test fun storeWritesAtomicallyAndReadsBack() {
        val dir = createTempDir("corrections")
        try {
            val store = MapCorrectionStore(dir)
            assertTrue(store.load().isEmpty())
            val list = listOf(ShapeOffset(1L, 1L, "décalage", zone.id, null, 0.3))
            store.save(list)
            assertEquals(list, store.load())
        } finally { dir.deleteRecursively() }
    }

    @Test fun correctedReferenceMapIsPlannedOnEveryZone() {
        val json = JSONObject(javaClass.getResourceAsStream("/planner-reference-map.json")!!.bufferedReader().use { it.readText() })
        val array = json.getJSONArray("shapes")
        val shapes = (0 until array.length()).map { i ->
            val s = array.getJSONObject(i); val p = s.getJSONArray("points")
            MapShape(s.getString("id").toLong(), s.getInt("type"), (0 until p.length()).map { k ->
                Point(p.getJSONArray(k).getDouble(0), p.getJSONArray(k).getDouble(1)) }, s.getString("name"))
        }
        val reference = RobotMap(shapes, true, "reference")
        val piscine = shapes.single { it.name.trim() == "Piscine" }
        // Edge redrawn as a straight line on part of Piscine, and a tree added in Potager.
        val i = piscine.points.size / 3
        val line = listOf(piscine.points[i], piscine.points[i + 6])
        val redrawn = requireNotNull(EdgeRedraw.replace(piscine.points, line))
        val (add, cut) = MapCorrections.diff(piscine.points, redrawn)
        val edge = ShapePatch(1L, 0L, "Piscine · bord redessiné", piscine.id, MapCorrections.fingerprint(piscine), add, cut, CorrectionTool.EDGE)
        val potager = shapes.single { it.name.trim() == "Potager" }
        val inside = potager.points.let { pts -> Point(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size) }
        val tree = PrimitiveShape(PrimitiveKind.CIRCLE, inside, 0.6, 0.6)
        val added = AddedNoGo(2L, 0L, "Arbre", tree.polygon(), NoGoOrigin.OBSTACLE, tree)
        val corrected = MapCorrections.apply(reference, listOf(edge, added))
        assertTrue(corrected.status.values.all { it == CorrectionStatus.APPLIED })
        val base = MapBase(Point(0.14231226759294913, 1.2859193243581544), "Placée manuellement", 1.5, 152.8624890921028, 1.5)
        for (name in listOf("Piscine", "Potager")) {
            val id = shapes.single { it.name.trim() == name }.id
            val plan = RoutePlanning.coverage(PlanningRequest(corrected.map, listOf(id), base.point,
                config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
                navigation = PlanningNavigation(emptyList(), base)))
            val report = requireNotNull(plan.report)
            assertTrue("$name: ${report.selected.diagnostics}", report.selected.valid)
        }
    }
}
