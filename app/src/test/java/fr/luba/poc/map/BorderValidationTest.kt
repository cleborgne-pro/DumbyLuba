package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Test

/** Borders validated by border following: offset kept per boundary, redrawn boundary to validate again, storage read back. */
class BorderValidationTest {
    private val zone = MapShape(1L, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 8.0), Point(0.0, 8.0)), "Pelouse")
    private val noGo = MapShape(2L, 1, listOf(Point(4.0, 3.0), Point(6.0, 3.0), Point(6.0, 5.0), Point(4.0, 5.0)))
    private val map = RobotMap(listOf(zone, noGo), true)

    private fun entry(shape: MapShape, offset: Double = 0.12) =
        BorderValidationEntry(shape.id, offset, offset + 0.02, 1_760_000_000_000L, BorderValidation.fingerprint(shape), 0.09, 0.13)

    @Test fun aSavedBorderIsValidatedWithItsOffset() {
        val saved = mapOf(zone.id to entry(zone))
        assertEquals(BorderValidationStatus.VALIDATED, BorderValidation.status(zone, saved))
        assertEquals(0.12, BorderValidation.validFor(zone, saved)!!.offsetM, 1e-9)
        assertEquals(BorderValidationStatus.NOT_VALIDATED, BorderValidation.status(noGo, saved))
        assertNull(BorderValidation.validFor(noGo, saved))
        assertEquals(1, BorderValidation.validatedCount(BorderFollow.choices(map), saved))
    }

    @Test fun aRetracedContourMustBeValidatedAgain() {
        val saved = mapOf(zone.id to entry(zone))
        val retraced = zone.copy(points = zone.points.map { if (it.x == 10.0) Point(10.4, it.y) else it })
        assertEquals(BorderValidationStatus.CHANGED, BorderValidation.status(retraced, saved))
        assertNull(BorderValidation.validFor(retraced, saved))
        assertEquals(0, BorderValidation.validatedCount(BorderFollow.choices(map.copy(shapes = listOf(retraced, noGo))), saved))
    }

    @Test fun subCentimetreNoiseKeepsTheValidation() {
        val saved = mapOf(zone.id to entry(zone))
        val reread = zone.copy(points = zone.points.map { Point(it.x + 0.001, it.y - 0.001) })
        assertEquals(BorderValidationStatus.VALIDATED, BorderValidation.status(reread, saved))
    }

    @Test fun encodingRoundTripsAndSkipsUnreadableLines() {
        val saved = mapOf(zone.id to entry(zone), noGo.id to entry(noGo, 0.3).copy(measuredMinM = null, measuredMedianM = null))
        val text = BorderValidation.encode(saved)
        assertEquals(saved, BorderValidation.decode(text))
        val noisy = text + "\nabc|1|2\n3|NaN|0.1|0|0||\n4|2.5|0.1|0|0||"
        assertEquals(saved, BorderValidation.decode(noisy))
        assertTrue(BorderValidation.decode(null).isEmpty())
    }
}
