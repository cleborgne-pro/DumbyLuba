package fr.luba.poc.planning

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/** Border lap and passes adapted to the border types ([BorderStrategy]). */
class BorderStrategyTest {
    private val setting = 0.08
    private val zone = MapShape(1, 0, rectangle(w = 12.0, h = 8.0), "Pelouse")
    private val map = RobotMap(listOf(zone), true, "types")
    private val bottom = listOf(Point(0.0, 0.0), Point(12.0, 0.0))

    private fun portions(): List<BorderPortion> {
        val danger = BorderTypes.annotate(zone, requireNotNull(BorderTypes.select(zone, Point(0.0, 0.0), Point(12.0, 0.0))), BorderType.DANGER, 1, 0)
        val open = BorderTypes.annotate(zone, requireNotNull(BorderTypes.select(zone, Point(12.0, 8.0), Point(0.0, 8.0))), BorderType.OPEN, 2, 0)
        val wall = BorderTypes.annotate(zone, requireNotNull(BorderTypes.select(zone, Point(12.0, 1.0), Point(12.0, 7.0))), BorderType.WALL, 3, 0)
        return BorderTypes.portions(map, listOf(danger, open, wall))
    }

    @Test fun eachTypeHasItsOwnLapMargin() {
        assertEquals(BorderStrategy.OPEN_OFFSET_M, BorderStrategy.sideOffsetM(BorderType.OPEN, setting), 1e-9)
        assertEquals(setting, BorderStrategy.sideOffsetM(BorderType.CURB, setting), 1e-9)
        assertEquals(setting, BorderStrategy.sideOffsetM(null, setting), 1e-9)
        assertEquals(BorderStrategy.WALL_OFFSET_M, BorderStrategy.sideOffsetM(BorderType.WALL, setting), 1e-9)
        assertEquals(0.5, BorderStrategy.sideOffsetM(BorderType.WALL, 0.5), 1e-9)
        assertEquals(BorderStrategy.DANGER_MARGIN_M + RecordedBoundary.PHYSICAL_TOLERANCE_M,
            BorderStrategy.sideOffsetM(BorderType.DANGER, setting), 1e-9)
        val lines = BorderStrategy.summary(portions(), setOf(1L), setting)
        assertEquals(3, lines.size)
        assertTrue(lines.toString(), lines.any { it.startsWith(BorderType.DANGER.label) && it.contains("12 m") })
    }

    @Test fun plannerOutlinesMoveAlongTheTypedPortions() {
        val moved = BorderStrategy.effectiveMap(map, portions(), setting).shapes.single().points
        val danger = BorderStrategy.sideOffsetM(BorderType.DANGER, setting) - setting
        assertEquals("Danger edge pulled in", danger, moved.minOf { it.y }, 0.01)
        assertEquals("open lawn edge pushed out", 8.0 + setting - BorderStrategy.OPEN_OFFSET_M, moved.maxOf { it.y }, 0.01)
        val wallX = 12.0 - (BorderStrategy.WALL_OFFSET_M - setting)
        assertTrue("wall pulled in", Geometry.inside(Point(wallX - 0.01, 4.0), moved) && !Geometry.inside(Point(wallX + 0.01, 4.0), moved))
        // Without a moved portion, the map is unchanged.
        val curb = BorderTypes.portions(map, listOf(BorderTypes.annotate(zone, BorderTypes.selectWhole(zone), BorderType.CURB, 4, 0)))
        assertSame(map, BorderStrategy.effectiveMap(map, curb, setting))
    }

    @Test fun passesNeverComeCloserThanTheDangerMarginAndTheOpenLapCutsCloser() {
        val config = PlanningConfig(spacingM = 0.35, minimumCoverage = 0.65, boundary = BoundaryMeaning.RECORDED_TRACE, outerOffsetM = setting)
        fun plan(borders: List<BorderPortion>) = RoutePlanning.coverage(PlanningRequest(map, listOf(1L), Point(6.0, 4.0), config = config, borders = borders))
        val typed = plan(portions())
        val plain = plan(emptyList())
        assertFalse(typed.preview.localOnly)
        val factory = GeometryFactory()
        val band = factory.createLineString(bottom.map { Coordinate(it.x, it.y) }.toTypedArray()).buffer(BorderStrategy.DANGER_MARGIN_M, 16)
        // Chassis footprint (pivots included) at least DANGER_MARGIN_M from the Danger portion.
        val envelope = TrajectoryEnvelope(RecordedBoundary.physicalArea(zone.points, fr.luba.poc.obstacles.RobotEnvelope.LUBA2).difference(band))
        typed.preview.cuttingPaths.forEach { assertNull("pass $it", envelope.pathCollision(it)) }
        assertTrue("without border types the lap runs along the Danger edge",
            plain.preview.cuttingPaths.any { envelope.pathCollision(it) != null })
        val topTyped = typed.preview.cuttingPaths.flatten().maxOf { it.y }
        val topPlain = plain.preview.cuttingPaths.flatten().maxOf { it.y }
        assertEquals("open lawn lap closer to its edge", setting - BorderStrategy.OPEN_OFFSET_M, topTyped - topPlain, 0.01)
    }
}
