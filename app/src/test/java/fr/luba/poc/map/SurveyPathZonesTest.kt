package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

/** Survey routes run on merged outlines, not on map zones: their paths carry no zone ([Preview.pathZoneIds]). */
class SurveyPathZonesTest {
    @Test fun mergedSurveyOutlinesGiveNoZoneToThePaths() {
        val a = MapShape(11, 0, listOf(Point(0.0, 0.0), Point(8.0, 0.0), Point(8.0, 6.0), Point(0.0, 6.0)), "A")
        val b = MapShape(12, 0, listOf(Point(8.0, 0.0), Point(14.0, 0.0), Point(14.0, 6.0), Point(8.0, 6.0)), "B")
        for (mode in listOf(CoverageMode.TOUR, CoverageMode.SURVEY_GRID)) {
            val preview = SurveyPlanner.plan(listOf(a, b), emptyList(), 2.0, 0.6, mode, emptyList(),
                fr.luba.poc.obstacles.RobotEnvelope.LUBA2, false, Point(1.0, 1.0), { _, _, _ -> }, {})
            assertEquals(mode.name, listOf(11L, 12L), preview.zoneIds)
            assertTrue(mode.name, preview.cuttingPaths.isNotEmpty())
            assertTrue("$mode: ${preview.pathZoneIds}", preview.pathZoneIds.isEmpty())
            assertNull(mode.name, preview.cuttingPathZones)
        }
    }
}
