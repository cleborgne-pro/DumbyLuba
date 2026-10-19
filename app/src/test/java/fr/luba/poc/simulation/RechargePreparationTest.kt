package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Test

class RechargePreparationTest {
    @Test fun longOpenPassKeepsSafeRechargeRoutesWithoutLiveGeometry() {
        val zone=MapShape(1,0,listOf(Point(0.0,0.0),Point(40.0,0.0),Point(40.0,20.0),Point(0.0,20.0)))
        val map=RobotMap(listOf(zone),true)
        val run=listOf(Point(2.0,10.0),Point(38.0,10.0))
        val routes=TrajectoryPlanner(map,0.6,emptyList())
        val started=System.nanoTime()
        val prepared=MowingSimulation(routes,Point(2.0,2.0),Preview(listOf(run),36.0,0,0),map,
            // Short autonomy: the rest cannot be finished below the return threshold ([FinishPolicy]), the robot returns.
            SimConfig(baseEntryHeadingDegrees=180.0,instantCharging=true,autonomyMinutes=3.0))
        val queries=routes.geometryQueries
        println("Recharge preparation: $queries geometry queries, ${(System.nanoTime()-started)/1_000_000} ms")
        assertTrue("The open pass must reuse the checked links: $queries computations",queries<300)
        for (progress in listOf(5.0,25.0,50.0,75.0,95.0)) {
            val beforePlayback=routes.geometryQueries
            val engine=prepared.withConfig(prepared.config.copy(initialProgress=progress))
            val state=engine.start()
            val position=state.transit.last()
            val returning=engine.advance(state.copy(position=position,phase=SimPhase.MOWING,heading=0.0,battery=20.0),1.0)
            assertEquals(SimPhase.RETURNING,returning.phase)
            assertEquals(beforePlayback,routes.geometryQueries)
            routes.requirePath(state.transit,0.0,0.0)
            routes.requirePath(returning.transit,0.0,0.0)
        }
    }
}
