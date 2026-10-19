package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

/** Return to base: drift tolerated up to 1 m inside the safe zone, outside the base zone. */
class ReturnDriftTest {
    private val approach = Point(10.0, 0.0)
    private val zone = ReturnDrift.BaseZone(Point(11.5, 0.0), 1.05)
    private fun e(ax: Double, bx: Double, cutting: Boolean) = SimEdge(Point(ax, 0.0), Point(bx, 0.0), cutting)

    @Test fun returnStartsAfterTheLastCuttingPassWhenThePathEndsAtTheBase() {
        val mission = listOf(e(0.0, 2.0, false), e(2.0, 4.0, true), e(4.0, 6.0, false), e(6.0, 8.0, true), e(8.0, 9.0, false), e(9.0, 10.0, false))
        assertEquals(4, ReturnDrift.returnFrom(mission, approach))
        // "Recharge" or return during mowing: the whole route is a return.
        assertEquals(0, ReturnDrift.returnFrom(listOf(e(0.0, 5.0, false), e(5.0, 10.0, false)), approach))
        // Resume link or route not reaching the base: no return.
        assertEquals(2, ReturnDrift.returnFrom(listOf(e(10.0, 5.0, false), e(5.0, 3.0, false)), approach))
        assertEquals(1, ReturnDrift.returnFrom(listOf(e(0.0, 10.0, false)), null))
        assertEquals(0, ReturnDrift.returnFrom(emptyList(), approach))
    }

    @Test fun driftIsToleratedOnlyDuringTheReturnInsideTheSafeArea() {
        val far = Point(3.0, 0.6)
        assertNull(ReturnDrift.failure(0.25, 3, returning = false, far, zone) { false })
        assertNull(ReturnDrift.failure(0.6, 3, returning = true, far, zone) { true })
        assertNull(ReturnDrift.failure(0.99, 3, returning = true, far, zone) { true })
        assertTrue(ReturnDrift.failure(0.6, 3, returning = false, far, zone) { true }!!.startsWith("Écart au trajet >30 cm"))
        assertTrue(ReturnDrift.failure(1.2, 3, returning = true, far, zone) { true }!!.contains("pendant le retour"))
        assertTrue(ReturnDrift.failure(0.6, 3, returning = true, far, zone) { false }!!.contains("hors de la zone sûre"))
        assertTrue(ReturnDrift.failure(0.6, 3, returning = true, Point(11.0, 0.6), zone) { true }!!.contains("zone de sécurité de la base"))
        // Base zone unknown (old pilot): no tolerance.
        assertTrue(ReturnDrift.failure(0.6, 3, returning = true, far, null) { true }!!.startsWith("Écart au trajet >30 cm"))
    }
}
