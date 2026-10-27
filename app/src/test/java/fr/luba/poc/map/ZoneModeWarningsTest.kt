package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

/** Preview warnings of the patterns not applied as requested ([zoneModeWarnings]); unit tests run in French. */
class ZoneModeWarningsTest {
    private val square = listOf(Point(0.0, 0.0), Point(1.0, 0.0), Point(1.0, 1.0), Point(0.0, 1.0))
    private val map = RobotMap(listOf(MapShape(7, 0, square, "Piscine"), MapShape(9, 0, square, "Figuier")), true)
    private val run = listOf(listOf(Point(0.0, 0.0), Point(1.0, 0.0)))

    @Test fun aSpiralZoneMowedInZigzagIsNamed() {
        val p = Preview(run, 1.0, 0, 0, CoverageMode.SPIRAL, zoneIds = listOf(7L, 9L), zoneAngles = listOf(0, 35),
            zoneModes = listOf(CoverageMode.SPIRAL, CoverageMode.LINES))
        assertEquals(listOf("Spirale impossible sur Figuier : zigzag utilisé"), zoneModeWarnings(map, p))
        assertTrue(zoneModeWarnings(map, p.copy(zoneModes = listOf(CoverageMode.SPIRAL, CoverageMode.SPIRAL))).isEmpty())
        // Unknown modes (older save): no warning.
        assertTrue(zoneModeWarnings(map, p.copy(zoneModes = emptyList())).isEmpty())
        // The direction line is only for the modes whose direction is chosen.
        assertNull(directionLine(map, p, 0.25))
    }

    @Test fun aZamboniZoneWithoutReorderedCellIsNamed() {
        val p = Preview(run, 1.0, 0, 0, CoverageMode.ZAMBONI, zoneIds = listOf(7L, 9L), zoneAngles = listOf(0, 35),
            zoneModes = listOf(CoverageMode.ZAMBONI, CoverageMode.ZAMBONI), zoneReorderedCells = listOf(0, 4))
        assertEquals(listOf("Zamboni sur Piscine : aucune cellule régulière, ordre du zigzag"), zoneModeWarnings(map, p))
        assertTrue(zoneModeWarnings(map, p.copy(zoneReorderedCells = listOf(1, 4))).isEmpty())
        // Zone ordered as in Zigzag because its Zamboni order had no safe link.
        assertEquals(listOf("Zamboni sur Figuier : ordre Zamboni sans raccord sûr, ordre du zigzag"),
            zoneModeWarnings(map, p.copy(zoneReorderedCells = listOf(3, -1))))
        assertTrue(zoneModeWarnings(map, p.copy(zoneReorderedCells = emptyList())).isEmpty())
        // Only for Zamboni: the other modes never fill it.
        assertTrue(zoneModeWarnings(map, p.copy(mode = CoverageMode.LINES, zoneModes = listOf(CoverageMode.LINES, CoverageMode.LINES))).isEmpty())
    }

    @Test fun zamboniPlansCountTheirReorderedCells() {
        val zone = listOf(Point(0.0, 0.0), Point(12.0, 0.0), Point(12.0, 9.0), Point(0.0, 9.0))
        val zamboni = CoveragePlanner.plan(zone, emptyList(), 0.25, 0.6, CoverageMode.ZAMBONI, true, recordedBoundary = true,
            sweepOptions = SweepOptions(listOf(0)), continuous = true, cutWidthM = 0.4)
        assertTrue("${zamboni.zoneReorderedCells}", zamboni.zoneReorderedCells.single() >= 1)
        val zigzag = CoveragePlanner.plan(zone, emptyList(), 0.25, 0.6, CoverageMode.LINES, true, recordedBoundary = true,
            sweepOptions = SweepOptions(listOf(0)), continuous = true, cutWidthM = 0.4)
        assertTrue(zigzag.zoneReorderedCells.isEmpty())
        val merged = MultiZonePlanner.combine(listOf(MapShape(1, 0, zone), MapShape(2, 0, zone)), listOf(zamboni, zamboni), CoverageMode.ZAMBONI)
        assertEquals(zamboni.zoneReorderedCells + zamboni.zoneReorderedCells, merged.zoneReorderedCells)
    }
}
