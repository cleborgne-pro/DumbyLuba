package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.protocol.NativeTaskStop
import org.junit.Assert.*
import org.junit.Test

class TrajectoryFollowerTest /* point-to-point tracking, now through PathFollower */ {
    private val start=Point(0.0,0.0)
    private val target=Point(2.0,0.0)
    private fun command(heading: Double, linear: Int, angular: Int, from: Point = start) =
        fr.luba.poc.diagnostics.MapNavFollower.command(from,heading,target,linear,angular)
    @Test fun rotationSlowsNearAlignmentWithoutStallingBelowUsefulPivot() {
        val far=command(90.0,100,450)
        val near=command(30.0,100,450)
        assertEquals(0,far.first); assertEquals(0,near.first)
        assertTrue(kotlin.math.abs(near.second)<kotlin.math.abs(far.second))
        assertTrue(kotlin.math.abs(near.second)>=PathFollower.MIN_PIVOT)
        // Small deviation: advance while correcting, no more stopping to pivot.
        assertTrue(command(8.0,100,450).first>0)
    }
    @Test fun liveSpeedChangesWithoutRestartAndArrivalAlwaysStops() {
        assertEquals(90 to 0,command(0.0,90,30))
        assertEquals(300 to 0,command(0.0,300,30))
        assertEquals(30 to 0,command(0.0,30,30))
        assertEquals(0 to 0,command(0.0,300,30,target))
    }
    @Test fun taskStopRejectsOldReportsUnwrittenCommandsAndBladesOn() {
        assertTrue(NativeTaskStop.present(13)); assertTrue(NativeTaskStop.present(14))
        assertFalse(NativeTaskStop.present(11))
        assertFalse(NativeTaskStop.confirmed(0,200,0,11,0))
        assertFalse(NativeTaskStop.confirmed(200,200,0,11,0))
        assertFalse(NativeTaskStop.confirmed(100,200,2001,11,0))
        assertFalse(NativeTaskStop.confirmed(100,200,0,11,512))
        assertFalse(NativeTaskStop.confirmed(100,200,0,13,0))
        assertTrue(NativeTaskStop.confirmed(100,200,0,11,0))
    }
}
