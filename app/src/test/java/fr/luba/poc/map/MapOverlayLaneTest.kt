package fr.luba.poc.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOverlayLaneTest {
    @Test fun narrowLaneStopsLeftOfSideBar() {
        val lane = MapOverlayLane.of(width = 1000, pad = 40, sideEnd = 40, sideWidth = 120, gap = 20)
        assertEquals(920, lane.full)
        assertEquals(780, lane.narrow) // 40 + 780 + 20 + 120 + 40 = 1000
    }

    @Test fun panelBelowSideBarKeepsFullWidth() {
        assertFalse(MapOverlayLane.reachesSide(bottomY = 2000, sideBottom = 800, gap = 20) { 300 })
    }

    @Test fun panelReachingSideBarIsNarrowed() {
        assertTrue(MapOverlayLane.reachesSide(bottomY = 1000, sideBottom = 800, gap = 20) { 300 })
        assertTrue(MapOverlayLane.reachesSide(bottomY = 700, sideBottom = 800, gap = 20) { error("not needed") })
    }

    @Test fun unknownHeightIsNarrowedForSafety() {
        assertTrue(MapOverlayLane.reachesSide(bottomY = 2000, sideBottom = 800, gap = 20) { null })
    }
}
