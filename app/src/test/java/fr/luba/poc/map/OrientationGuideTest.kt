package fr.luba.poc.map

import androidx.compose.ui.geometry.Offset
import fr.luba.poc.planning.PlanningConfig
import fr.luba.poc.planning.PlanningRequest
import fr.luba.poc.planning.RoutePlanning
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Guide lines of the manual direction ([OrientationGuide]): same direction as the planner's passes, clipped to the zones. */
class OrientationGuideTest {
    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(14.0, 0.0), Point(14.0, 9.0), Point(0.0, 9.0)), "Pelouse")
    private val hole = MapShape(2, 1, listOf(Point(5.0, 3.0), Point(8.0, 3.0), Point(8.0, 6.0), Point(5.0, 6.0)), "Massif")
    private val map = RobotMap(listOf(zone, hole), true, "test")

    /** Direction 0..179 of the segment [a]–[b], counter-clockwise from the map's x axis. */
    private fun direction(a: Point, b: Point) = foldAngle(Math.toDegrees(atan2(b.y - a.y, b.x - a.x)).roundToInt())

    @Test fun linesAtTheAngleInsideTheZoneOutsideTheNoGoZone() {
        val area = OrientationGuide.area(map, setOf(1L))!!
        val factory = GeometryFactory()
        val holePolygon = factory.createPolygon((hole.points + hole.points.first()).map { Coordinate(it.x, it.y) }.toTypedArray())
        for (angle in listOf(0, 35, 90, 145, 175)) {
            val lines = OrientationGuide.lines(area, angle)
            assertTrue("$angle°: ${lines.size} lines", lines.size >= OrientationGuide.LINES_ACROSS / 2)
            for (line in lines) {
                assertTrue("$angle°", OrientationRule.distance(direction(line.first(), line.last()), angle) <= 1)
                val geometry = factory.createLineString(line.map { Coordinate(it.x, it.y) }.toTypedArray())
                assertTrue("$angle° inside the zone", area.buffer(1e-6).covers(geometry))
                assertFalse("$angle° across the no-go zone", holePolygon.buffer(-1e-6).intersects(geometry))
            }
        }
    }

    @Test fun sameDirectionAsThePassesOfTheRealPreview() {
        // 35° and 145° are mirror images: a y axis taken upside down would swap them.
        for (mode in listOf(CoverageMode.LINES, CoverageMode.CHECKERBOARD)) for (angle in listOf(35, 145)) {
            val config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65, orientation = OrientationMode.MANUAL, manualAngleDeg = angle)
            val preview = RoutePlanning.calculate(PlanningRequest(map, listOf(1L), Point(1.0, 1.0), config = config), mode).selected.preview!!
            val passes = preview.passes.filter { it.size >= 2 && it.first().distance(it.last()) > 0.3 }.map { direction(it.first(), it.last()) }.toSet()
            val guide = OrientationGuide.lines(map, setOf(1L), angle, mode).flatten().map { direction(it.first(), it.last()) }.toSet()
            assertTrue("$mode $angle°: passes $passes", passes.isNotEmpty())
            for (d in passes) assertTrue("$mode $angle°: pass $d°, guide $guide", guide.any { OrientationRule.distance(it, d) <= 1 })
            for (d in guide) assertTrue("$mode $angle°: guide $d°, passes $passes", passes.any { OrientationRule.distance(it, d) <= 1 })
        }
    }

    @Test fun checkerboardShowsBothFamilies() {
        val families = OrientationGuide.lines(map, setOf(1L), 30, CoverageMode.CHECKERBOARD)
        assertEquals(2, families.size)
        assertEquals(setOf(30), families[0].map { direction(it.first(), it.last()) }.toSet())
        assertEquals(setOf(120), families[1].map { direction(it.first(), it.last()) }.toSet())
        assertEquals(1, OrientationGuide.lines(map, setOf(1L), 30, CoverageMode.ZAMBONI).size)
        assertTrue(OrientationGuide.lines(map, setOf(9L), 30, CoverageMode.LINES).isEmpty())
    }

    @Test fun fingerDirectionInMapCoordinates() {
        val o = Point(0.0, 0.0)
        assertEquals(45, OrientationGuide.angleOf(o, Point(1.0, 1.0)))
        assertEquals(135, OrientationGuide.angleOf(o, Point(-1.0, 1.0)))
        // Either way along the line: same passes.
        assertEquals(135, OrientationGuide.angleOf(o, Point(1.0, -1.0)))
        assertEquals(0, OrientationGuide.angleOf(o, Point(-1.0, 0.0)))
        // 5° steps, 178° is 0° (180).
        val a37 = Math.toRadians(37.0)
        assertEquals(35, OrientationGuide.angleOf(o, Point(cos(a37), sin(a37))))
        val a178 = Math.toRadians(178.0)
        assertEquals(0, OrientationGuide.angleOf(o, Point(cos(a178), sin(a178))))
    }

    @Test fun fingerMovingUpRightOnScreenIs45Degrees() {
        // Screen y points down: the projection of the map views turns it back into the map's y axis.
        val projection = fitProjection(map.shapes.flatMap { it.points }, 400f, 300f)
        val from = projection.map(Offset(100f, 250f)); val to = projection.map(Offset(200f, 150f))
        assertEquals(45, OrientationGuide.angleOf(from, to))
        // And a guide line at 45° is drawn going up to the right.
        val line = OrientationGuide.lines(OrientationGuide.area(map, setOf(1L))!!, 45).first()
        val a = projection.screen(line.first()); val b = projection.screen(line.last())
        assertTrue((b.x - a.x) * (b.y - a.y) < 0)
    }

    @Test fun guideMapKeepsTheChosenZonesAndTheirNoGoZones() {
        val other = MapShape(3, 0, listOf(Point(30.0, 0.0), Point(40.0, 0.0), Point(40.0, 5.0)), "Loin")
        val farHole = MapShape(4, 1, listOf(Point(32.0, 1.0), Point(33.0, 1.0), Point(33.0, 2.0)), "Loin")
        val view = guideMap(map.copy(shapes = map.shapes + other + farHole), setOf(1L))
        assertEquals(listOf(1L, 2L), view.shapes.map { it.id })
    }
}
