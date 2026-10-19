package fr.luba.poc.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishPolicyTest {
    @Test fun neededEnergyIncludesTheMargin() {
        // 6 min out of 120 min of autonomy = 5 %, increased by 25 %.
        assertEquals(6.25, FinishPolicy.neededPercent(360.0, 120.0), 1e-9)
    }

    @Test fun finishesBelowTheLowThresholdOnlyWithAnArrivalReserve() {
        assertTrue(FinishPolicy.finishWithoutRecharge(20.0, 10.0))
        assertFalse(FinishPolicy.finishWithoutRecharge(20.0, 10.1))
    }

    @Test fun leavesTheBaseEarlyWhenTheRestIsCovered() {
        assertTrue(FinishPolicy.earlyDeparture(45.0, 25.0, 20.0))
        assertFalse(FinishPolicy.earlyDeparture(44.0, 25.0, 20.0))
        assertEquals(45.0, FinishPolicy.chargeTarget(25.0, 20.0, 90.0), 1e-9)
        assertEquals(90.0, FinishPolicy.chargeTarget(95.0, 20.0, 90.0), 1e-9)
    }

    @Test fun restDurationUsesTheTimeFactorAndDocking() {
        assertEquals(30.0 / 0.3 * 1.5 + FinishPolicy.DOCKING_SECONDS, FinishPolicy.restSeconds(30.0, 0.3, 1.5), 1e-9)
    }

    /** Decision of 2026-10-15 seen afterwards: 20 % at the return threshold, measured autonomy 82 min, factor 1.64. */
    @Test fun realisticLowThresholdDecision() {
        // 50 m: 273 s + 60 s of docking ≈ 8.5 % margin included, estimated arrival at 11.5 %.
        val shortRest = FinishPolicy.neededPercent(FinishPolicy.restSeconds(50.0, 0.3, 1.64), 82.0)
        assertTrue("rest of 50 m: $shortRest %", FinishPolicy.finishWithoutRecharge(20.0, shortRest))
        val longRest = FinishPolicy.neededPercent(FinishPolicy.restSeconds(400.0, 0.3, 1.64), 82.0)
        assertFalse("rest of 400 m: $longRest %", FinishPolicy.finishWithoutRecharge(20.0, longRest))
    }
}
