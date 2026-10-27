package fr.luba.poc.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapDisplayTest {
    @Test fun roundTripsEveryDisplay() {
        MapBackdrop.entries.forEach { backdrop ->
            val display = MapDisplay(backdrop, zoneNames = false, scaleBar = false, routeAnimation = false, patterns = false)
            assertEquals(display, MapDisplay.decode(display.encode()))
        }
    }

    @Test fun valueSavedBeforeThePatternsSwitchKeepsPatternsShown() {
        assertEquals(MapDisplay(MapBackdrop.DOTS, routeAnimation = false), MapDisplay.decode("DOTS;true;true;false"))
        assertEquals(true, MapDisplay.decode("DOTS;true;true;false").patterns)
        assertEquals(false, MapDisplay.decode("GRID;true;true;true;false").patterns)
    }

    @Test fun missingOrUnknownValuesFallBackFieldByField() {
        assertEquals(MapDisplay(), MapDisplay.decode(null))
        assertEquals(MapDisplay(), MapDisplay.decode(""))
        assertEquals(MapDisplay(zoneNames = false), MapDisplay.decode("DISPARU;false"))
        assertEquals(MapDisplay(MapBackdrop.PLAIN, scaleBar = false), MapDisplay.decode("PLAIN;;false"))
    }

    @Test fun gridKeepsFineLinesReadable() {
        // 30 px per meter, 14 dp ≈ 37 px: thin line every 2 m, strong every 10 m.
        assertEquals(2.0 to 10.0, gridSteps(30.0, 37.0))
        assertEquals(1.0 to 5.0, gridSteps(60.0, 37.0))
        assertEquals(100.0 to 500.0, gridSteps(0.01, 37.0))
    }

    @Test fun scaleBarUsesTheLongestRoundLengthThatFits() {
        assertEquals(10.0, scaleBarMeters(25.0, 260.0), 0.0)
        assertEquals(5.0, scaleBarMeters(25.0, 240.0), 0.0)
        assertEquals(0.5, scaleBarMeters(10_000.0, 260.0), 0.0)
        assertEquals(200.0, scaleBarMeters(0.5, 260.0), 0.0)
    }
}
