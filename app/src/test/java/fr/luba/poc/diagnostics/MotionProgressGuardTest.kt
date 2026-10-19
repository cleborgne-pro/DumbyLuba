package fr.luba.poc.diagnostics

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

class MotionProgressGuardTest {
    @Test fun continuousForwardOrReverseWithoutTranslationStopsAtThreeSeconds() {
        listOf(20,-10).forEach { speed ->
            val guard=MotionProgressGuard()
            assertFalse(guard.blocked(Point(0.0,0.0),100,100,speed))
            assertFalse(guard.blocked(Point(0.01,0.0),3000,3000,speed))
            assertTrue(guard.blocked(Point(0.01,0.0),3100,3100,speed))
        }
    }
    @Test fun stalePositionsCannotPretendToConfirmPhysicalBlocking() {
        val guard=MotionProgressGuard()
        assertFalse(guard.blocked(Point(0.0,0.0),100,100,20))
        assertFalse(guard.blocked(Point(0.0,0.0),100,4000,20))
    }
    @Test fun progressionAndPivotResetTheWindow() {
        val guard=MotionProgressGuard()
        assertFalse(guard.blocked(Point(0.0,0.0),100,100,20))
        assertFalse(guard.blocked(Point(0.04,0.0),3000,3000,20))
        assertFalse(guard.blocked(Point(0.04,0.0),5000,5000,20))
        assertFalse(guard.blocked(Point(0.04,0.0),5100,5100,0))
        assertFalse(guard.blocked(Point(0.04,0.0),9000,9000,20))
        assertTrue(guard.blocked(Point(0.04,0.0),12000,12000,20))
    }
    @Test fun rtkJitterDoesNotAccumulateIntoFakeProgress() {
        val guard=MotionProgressGuard()
        assertFalse(guard.blocked(Point(0.0,0.0),100,100,20))
        assertFalse(guard.blocked(Point(0.01,0.0),1000,1000,20))
        assertFalse(guard.blocked(Point(-0.01,0.0),2000,2000,20))
        assertTrue(guard.blocked(Point(0.01,0.0),3100,3100,20))
    }
}
