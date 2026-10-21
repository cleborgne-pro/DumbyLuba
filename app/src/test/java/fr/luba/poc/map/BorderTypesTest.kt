package fr.luba.poc.map

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/**
 * Border types: choice of a boundary portion, reprojection when the boundary changes (point added, ring reversed, zone
 * recorded again under another id), "to review" when it changes too much, overlaps (the newest wins), storage.
 */
class BorderTypesTest {
    private val square = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0))
    private val zone = MapShape(1, 0, square, "Pelouse")
    private val map = RobotMap(listOf(zone), true, "test")
    private var nextId = 1L

    @After fun reset() = BorderTypeRepository.resetForTest()

    private fun annotate(a: Point, b: Point, type: BorderType, shape: MapShape = zone, otherSide: Boolean = false): BorderAnnotation =
        BorderTypes.annotate(shape, requireNotNull(BorderTypes.select(shape, a, b, otherSide)), type, nextId++, 1000L + nextId)

    private fun lengths(list: List<BorderAnnotation>, m: RobotMap = map) =
        BorderTypes.portions(m, list).map { it.type to Math.round(it.lengthM * 100) / 100.0 }

    @Test fun selectionTakesTheShorterArcOrTheOtherSide() {
        val s = requireNotNull(BorderTypes.select(zone, Point(2.0, -0.3), Point(8.0, 0.2)))
        assertEquals(6.0, s.lengthM, 1e-9)
        val other = requireNotNull(BorderTypes.select(zone, Point(2.0, 0.0), Point(8.0, 0.0), otherSide = true))
        assertEquals(34.0, other.lengthM, 1e-9)
        assertNull("ends too close", BorderTypes.select(zone, Point(2.0, 0.0), Point(2.1, 0.0)))
        assertEquals(40.0, BorderTypes.selectWhole(zone).lengthM, 1e-9)
    }

    @Test fun unchangedBoundaryIsExact() {
        val a = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        val r = BorderTypes.resolve(a, map)
        assertEquals(BorderAnnotationStatus.EXACT, r.status)
        assertEquals(6.0, r.lengthM, 1e-9)
        assertEquals(listOf(BorderType.WALL to 6.0), lengths(listOf(a)))
    }

    @Test fun redrawnBoundaryIsReprojected() {
        val a = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.CURB)
        // Point inserted on the edge and slight shift (10 cm): same portion, reprojected.
        val redrawn = RobotMap(listOf(zone.copy(points = listOf(Point(0.0, 0.0), Point(5.0, 0.1), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0)))), true)
        val r = BorderTypes.resolve(a, redrawn)
        assertEquals(BorderAnnotationStatus.REPROJECTED, r.status)
        assertEquals(6.0, r.lengthM, 0.01)
        // Ring reversed (recorded the other way round).
        val reversed = RobotMap(listOf(zone.copy(points = square.reversed())), true)
        assertEquals(6.0, BorderTypes.resolve(a, reversed).lengthM, 1e-9)
        assertTrue(BorderTypes.resolve(a, reversed).status.applied)
    }

    @Test fun zoneRecordedAgainUnderAnotherIdKeepsItsTypes() {
        val a = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.DANGER)
        val again = RobotMap(listOf(zone.copy(id = 77)), true)
        val r = BorderTypes.resolve(a, again)
        assertEquals(77L, r.shapeId)
        assertEquals(BorderAnnotationStatus.REPROJECTED, r.status)
    }

    @Test fun boundaryMovedTooFarIsToReview() {
        val a = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        val moved = RobotMap(listOf(zone.copy(points = listOf(Point(0.0, -1.0), Point(10.0, -1.0), Point(10.0, 10.0), Point(0.0, 10.0)))), true)
        val r = BorderTypes.resolve(a, moved)
        assertEquals(BorderAnnotationStatus.STALE, r.status)
        assertTrue(BorderTypes.portions(moved, listOf(a)).isEmpty())
        // A bulge in the middle of the portion (not only the ends): also to review.
        val bulge = RobotMap(listOf(zone.copy(points = listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(5.0, -2.0), Point(6.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0)))), true)
        assertEquals(BorderAnnotationStatus.STALE, BorderTypes.resolve(a, bulge).status)
        // Map without the zone: no effect.
        assertEquals(BorderAnnotationStatus.STALE, BorderTypes.resolve(a, RobotMap()).status)
    }

    @Test fun newestAnnotationWinsOnOverlaps() {
        val wall = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        val danger = annotate(Point(4.0, 0.0), Point(6.0, 0.0), BorderType.DANGER)
        assertEquals(listOf(BorderType.WALL to 2.0, BorderType.DANGER to 2.0, BorderType.WALL to 2.0), lengths(listOf(wall, danger)))
        assertEquals("older one on top: hidden", listOf(BorderType.WALL to 6.0), lengths(listOf(danger, wall)))
    }

    @Test fun portionAcrossTheStartOfTheRingStaysOnePiece() {
        val a = annotate(Point(0.0, 2.0), Point(2.0, 0.0), BorderType.CURB)
        assertEquals(listOf(BorderType.CURB to 4.0), lengths(listOf(a)))
        assertEquals(BorderType.CURB, BorderTypes.typeAt(map, listOf(a), zone.id, Point(0.0, 0.5)))
        assertNull(BorderTypes.typeAt(map, listOf(a), zone.id, Point(10.0, 5.0)))
    }

    @Test fun wholeBoundaryThenRetypedPortion() {
        val whole = BorderTypes.annotate(zone, BorderTypes.selectWhole(zone), BorderType.OPEN, nextId++, 1L)
        val wall = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        val portions = lengths(listOf(whole, wall))
        assertEquals(40.0, portions.sumOf { it.second }, 1e-6)
        assertEquals(6.0, portions.filter { it.first == BorderType.WALL }.sumOf { it.second }, 1e-6)
        assertEquals(34.0, portions.filter { it.first == BorderType.OPEN }.sumOf { it.second }, 1e-6)
        assertEquals(BorderType.OPEN, BorderTypes.typeAt(map, listOf(whole, wall), zone.id, Point(10.0, 5.0)))
        assertEquals(BorderType.WALL, BorderTypes.typeAt(map, listOf(whole, wall), zone.id, Point(5.0, 0.0)))
    }

    @Test fun addDropsHiddenAnnotationsButKeepsStaleOnes() {
        val wall = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        val stale = BorderAnnotation(99, 5, BorderType.CURB, listOf(Point(50.0, 50.0), Point(60.0, 50.0)), 0, 0)
        val danger = annotate(Point(1.0, 0.0), Point(9.0, 0.0), BorderType.DANGER)
        val list = BorderTypes.add(listOf(stale, wall), danger, map)
        assertEquals(listOf(99L, danger.id), list.map { it.id })
        // Retyping the same annotation (same id) replaces it.
        val retyped = BorderTypes.add(list, danger.copy(type = BorderType.OPEN), map)
        assertEquals(listOf(BorderType.CURB, BorderType.OPEN), retyped.map { it.type })
    }

    @Test fun annotationNearATap() {
        val wall = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        assertEquals(wall.id, BorderTypes.annotationNear(map, listOf(wall), Point(5.0, 0.4), 0.5)?.id)
        assertNull(BorderTypes.annotationNear(map, listOf(wall), Point(5.0, 2.0), 0.5))
        assertNull("untyped part", BorderTypes.annotationNear(map, listOf(wall), Point(10.0, 5.0), 0.5))
    }

    @Test fun noGoBoundariesCanBeTypedToo() {
        val noGo = MapShape(2, 1, listOf(Point(3.0, 3.0), Point(5.0, 3.0), Point(5.0, 5.0), Point(3.0, 5.0)))
        val passage = MapShape(3, 2, listOf(Point(0.0, 5.0), Point(-5.0, 5.0)))
        val m = RobotMap(listOf(zone, noGo, passage), true)
        assertEquals(listOf(1L, 2L), BorderTypes.contours(m).map { it.id })
        val pond = BorderTypes.annotate(noGo, BorderTypes.selectWhole(noGo), BorderType.DANGER, 5, 0)
        assertEquals(listOf(BorderType.DANGER to 8.0), lengths(listOf(pond), m))
    }

    @Test fun encodingRoundTripAndUnreadableEntries() {
        val wall = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        val whole = BorderTypes.annotate(zone, BorderTypes.selectWhole(zone), BorderType.OPEN, 42, 7)
        assertEquals(listOf(wall, whole), BorderTypes.decode(BorderTypes.encode(listOf(wall, whole))))
        val text = BorderTypes.encode(listOf(wall)).replace("\"WALL\"", "\"LAVA\"")
        assertTrue("unknown type ignored", BorderTypes.decode(text).isEmpty())
        assertTrue(BorderTypes.decode("""{"schemaVersion":9,"borders":[]}""").isEmpty())
        assertTrue(BorderTypes.decode("not json").isEmpty())
        assertTrue(BorderTypes.decode(null).isEmpty())
    }

    @Test fun repositorySurvivesARestart() {
        val dir = Files.createTempDirectory("border-types").toFile()
        val wall = annotate(Point(2.0, 0.0), Point(8.0, 0.0), BorderType.WALL)
        BorderTypeRepository.resetForTest(d = dir)
        assertTrue(BorderTypeRepository.save(listOf(wall)))
        assertEquals(listOf(wall), BorderTypeRepository.annotations)
        // "Restart": a new reading of the directory.
        assertEquals(listOf(wall), BorderTypeRepository.load(dir))
        assertEquals(listOf(BorderType.WALL), BorderTypeRepository.portions(map).map { it.type })
        assertSame("cached while nothing changes", BorderTypeRepository.portions(map), BorderTypeRepository.portions(map))
        dir.deleteRecursively()
    }
}
