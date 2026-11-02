package fr.luba.poc.ble

import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A screen that keeps the corrected account map across emissions publishes the same state as a fresh correction. */
class RobotUiMapTest {
    private val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 10.0), Point(0.0, 10.0)), "Pelouse")
    private val raw = RobotMap(listOf(zone), true, "test")
    private val offset = ShapeOffset(id = 7, createdAtMs = 0, label = "Bord", target = 1, baseFingerprint = null, distanceM = 0.5)
    private val ui = RobotUi(mapCorrections = listOf(offset))

    @Test fun `corrected map kept by the screen equals a fresh correction`() {
        val kept = MapCorrections.apply(raw, ui.mapCorrections)
        val fresh = ui.withMammotionMap(raw)
        assertEquals(fresh, ui.withCorrectedMap(raw, kept))
        assertEquals(raw, fresh.mammotionMap)
        assertNotEquals("the offset applies to the published map", raw, fresh.robotMap)
    }
}
