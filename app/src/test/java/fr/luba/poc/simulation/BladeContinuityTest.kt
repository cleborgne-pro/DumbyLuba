package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import fr.luba.poc.planning.MissionLeg
import org.junit.Assert.assertEquals
import org.junit.Test

class BladeContinuityTest {
    private val a = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0))
    private val b = listOf(Point(10.0, 0.0), Point(20.0, 0.0), Point(20.0, 10.0), Point(10.0, 10.0))
    private fun cut(vararg p: Pair<Double, Double>) = MissionLeg(p.map { Point(it.first, it.second) }, true)
    private fun link(vararg p: Pair<Double, Double>) = MissionLeg(p.map { Point(it.first, it.second) }, false)

    @Test fun bladesStayOnInsideAZoneAndStopToChangeZone() {
        val mission = listOf(
            cut(1.0 to 1.0, 9.0 to 1.0, 9.0 to 9.0, 1.0 to 9.0),   // border A
            link(1.0 to 9.0, 2.0 to 5.0),                           // border → zigzag in A: blades running
            cut(2.0 to 5.0, 8.0 to 5.0),
            link(8.0 to 5.0, 12.0 to 5.0),                          // A → B: stop
            cut(12.0 to 5.0, 18.0 to 5.0),
            link(18.0 to 5.0, 15.0 to 12.0, 12.0 to 8.0),          // leaves B: stop
            cut(12.0 to 8.0, 18.0 to 8.0),
            link(18.0 to 8.0, 19.0 to 2.0),                         // inside B: blades running
            cut(19.0 to 2.0, 12.0 to 2.0),
        )
        assertEquals(listOf(true, true, true, false, true, false, true, true, true), BladeContinuity.legs(mission, listOf(a, b)))
    }

    @Test fun linksAtTheEndsOrWithoutZoneKeepBladesOff() {
        val mission = listOf(link(-5.0 to 5.0, 1.0 to 5.0), cut(1.0 to 5.0, 9.0 to 5.0), link(9.0 to 5.0, 5.0 to 5.0), cut(5.0 to 5.0, 5.0 to 9.0), link(5.0 to 9.0, -5.0 to 9.0))
        assertEquals(listOf(false, true, true, true, false), BladeContinuity.legs(mission, listOf(a)))
        assertEquals(listOf(false, true, false, true, false), BladeContinuity.legs(mission, emptyList()))
    }
}
