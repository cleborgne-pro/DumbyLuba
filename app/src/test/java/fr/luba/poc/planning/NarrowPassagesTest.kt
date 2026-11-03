package fr.luba.poc.planning

import fr.luba.poc.map.*
import fr.luba.poc.obstacles.RobotEnvelope
import org.junit.Assert.*
import org.junit.Test

/**
 * Narrow passages of a zone on a recorded boundary ([NarrowPassages]): a passage too narrow for the pivot radius between
 * two parts of a zone is mowed by passes along it and counted in the target; a sharp corner and a bent passage get none.
 */
class NarrowPassagesTest {
    private val robot = RobotEnvelope.LUBA2
    private val config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.9, boundary = BoundaryMeaning.RECORDED_TRACE)
    /** Two 4 × 4 m lawns joined by a 2 m long, 1 m wide passage (y 1.5 to 2.5). */
    private val dumbbell = listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(4.0, 1.5), Point(6.0, 1.5), Point(6.0, 0.0), Point(10.0, 0.0),
        Point(10.0, 4.0), Point(6.0, 4.0), Point(6.0, 2.5), Point(4.0, 2.5), Point(4.0, 4.0), Point(0.0, 4.0))
    private val middle = Point(5.0, 2.0)

    private fun necks(points: List<Point>) = NarrowPassages.necks(points, emptyList(), emptyList(), robot, config.outerOffsetM, config.spacingM)
    private fun distance(p: Point, path: List<Point>) = path.zipWithNext().minOf { (a, b) -> Geometry.distance(p, a, b) }

    @Test fun passageBetweenTwoLawnsIsMowedAlongItsLength() {
        val necks = necks(dumbbell)
        assertEquals(1, necks.size)
        // Robot-centre corridor 1.0 - 2 × 0.34 = 0.33 m wide: rows at its two sides and between, one spacing apart at most.
        val rows = necks.single().rows
        assertEquals(3, rows.size)
        rows.zipWithNext().forEach { (a, b) -> assertTrue(distance(a.at(0.0), listOf(b.at(-10.0), b.at(10.0))) <= config.spacingM + 1e-9) }
        assertTrue(NarrowPassages.target(necks, config.cutWidthM)!!.contains(org.locationtech.jts.geom.GeometryFactory()
            .createPoint(org.locationtech.jts.geom.Coordinate(middle.x, middle.y))))
        for (mode in listOf(CoverageMode.LINES, CoverageMode.ZAMBONI, CoverageMode.SPIRAL)) {
            val selected = RoutePlanning.calculate(request(dumbbell, config = config), mode).selected
            assertTrue("$mode: ${selected.diagnostics}", selected.valid)
            assertEquals("$mode", 0, selected.metrics!!.geometricViolations)
            // The middle of the passage mowed by a pass crossing it from one lawn to the other.
            val crossing = selected.preview!!.cuttingPaths.filter { path ->
                distance(middle, path) <= config.cutWidthM / 2 && path.any { it.x < 4.0 } && path.any { it.x > 6.0 } }
            assertTrue("$mode: a pass across the passage", crossing.isNotEmpty())
            assertTrue("$mode: coverage ${selected.metrics!!.coverage}", selected.metrics!!.coverage > 0.99)
        }
    }

    @Test fun sharpCornerAndBentPassageGetNoPasses() {
        // A sharp corner touches the wide part once: it cannot be left without reversing.
        val wedge = listOf(Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 5.0), Point(0.0, 1.0))
        assertEquals(emptyList<NarrowPassages.Neck>(), necks(wedge))
        // A passage bent at right angle has no straight row from one mouth to the other.
        val bent = listOf(Point(0.0, 0.0), Point(4.0, 0.0), Point(4.0, 1.5), Point(5.5, 1.5), Point(5.5, 5.0), Point(9.5, 5.0),
            Point(9.5, 9.0), Point(5.5, 9.0), Point(5.5, 8.9), Point(4.5, 8.9), Point(4.5, 2.5), Point(4.0, 2.5), Point(4.0, 4.0), Point(0.0, 4.0))
        assertEquals(emptyList<NarrowPassages.Neck>(), necks(bent))
        assertTrue(RoutePlanning.calculate(request(bent, config = config), CoverageMode.LINES).selected.valid)
    }
}
