package fr.luba.poc.planning

import fr.luba.poc.map.Geometry
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon

/** Transits through a bottleneck keep to its middle instead of grazing one side ([fr.luba.poc.simulation.PreferredArea]). */
class NarrowPassageCentringTest {
    private val margin = RobotEnvelope.LUBA2.halfWidthM + 0.05
    private val width = 0.95
    /** Inner corner of the bend of the passage. */
    private val inner = Point(11.0 - width / 2, 4.0 + width / 2)

    /**
     * Two 8 × 8 m lawns joined by a passage under 1 m wide (no room for 50 cm on each side) that bends at right angle:
     * east from the first lawn along y = 4, then north along x = 11 up to the second lawn.
     */
    private fun bentPassage(): RobotMap {
        val f = GeometryFactory()
        fun box(x0: Double, y0: Double, x1: Double, y1: Double) = f.createPolygon(arrayOf(Coordinate(x0, y0), Coordinate(x1, y0),
            Coordinate(x1, y1), Coordinate(x0, y1), Coordinate(x0, y0)))
        val h = width / 2
        val outline = box(0.0, 0.0, 8.0, 8.0).union(box(7.9, 4.0 - h, 11.0 + h, 4.0 + h)).union(box(11.0 - h, 4.0 - h, 11.0 + h, 12.1))
            .union(box(7.0, 12.0, 15.0, 20.0)) as Polygon
        val points = outline.exteriorRing.coordinates.dropLast(1).map { Point(it.x, it.y) }
        return RobotMap(listOf(MapShape(1, 0, points, "Coude")), true, "coude")
    }

    private fun clearance(path: List<Point>, corner: Point): Double = path.zipWithNext().minOf { (a, b) -> Geometry.distance(corner, a, b) }

    @Test fun transitRoundTheBendOfANarrowPassageKeepsToItsMiddle() {
        val routes = RoutePlanning.navigation(bentPassage(), margin, emptyList())
        for ((start, end) in listOf(Point(3.0, 4.0) to Point(11.0, 17.0), Point(11.0, 17.0) to Point(3.0, 4.0))) {
            val path = routes.between(start, end)
            routes.requirePath(path)
            // Grazing the inner corner puts the centre at the margin from it; the middle of the passage is half its width away.
            assertTrue("${clearance(path, inner)} m from the inner corner (margin $margin, middle ${width / 2}): $path",
                clearance(path, inner) >= margin + 0.08)
        }
    }

    @Test fun transitThatStaysInTheOpenIsUnchanged() {
        val routes = RoutePlanning.navigation(bentPassage(), margin, emptyList())
        assertEquals(2, routes.between(Point(1.0, 1.0), Point(6.0, 7.0)).size)
    }
}
