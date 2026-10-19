package fr.luba.poc.diagnostics

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun `the full campaign only contains stationary cases and three explicit cycles`() {
        val steps = DiagnosticPlan.steps(CampaignConfig())
        assertEquals(listOf("baseline", "zero_5hz", "cycle_1", "cycle_2", "cycle_3", "watchdog", "exception", "stability", "final_stop"), steps.map { it.id })
        assertEquals(1800L, steps.single { it.id == "stability" }.seconds)
    }
    @Test fun `minimal profile without disturbance and explicit unlimited`() {
        assertEquals(listOf("baseline", "stability", "final_stop"), DiagnosticPlan.steps(CampaignConfig(1, false, false, false, exception = false)).map { it.id })
        assertEquals(0L, DiagnosticPlan.steps(CampaignConfig(0)).single { it.id == "stability" }.seconds)
        assertThrows(IllegalArgumentException::class.java) { CampaignConfig(-1) }
    }
    @Test fun `a failure prevents running the next case`() = runBlocking {
        val executed = mutableListOf<String>(); val results = mutableListOf<TestResult>()
        val outcome = CampaignRunner.run(CampaignConfig(), { 100 }, {}, { step ->
            executed += step.id
            if (step.id == "cycle_1") error("STOP non confirmé ; ne pas reconnecter")
        }, results::add)
        assertFalse(outcome.success)
        assertEquals(listOf("baseline", "zero_5hz", "cycle_1"), executed)
        assertEquals("FAILED", results.last().outcome)
        assertFalse(executed.contains("cycle_2"))
    }
    @Test fun `user STOP cancels the plan without producing a PASS`() = runBlocking {
        val executed = mutableListOf<String>(); val results = mutableListOf<TestResult>()
        try {
            CampaignRunner.run(CampaignConfig(), { 1 }, {}, { step ->
                executed += step.id; throw CancellationException("STOP")
            }, results::add)
            fail("Annulation attendue")
        } catch (_: CancellationException) { }
        assertEquals(listOf("baseline"), executed)
        assertTrue(results.isEmpty())
    }
    @Test fun `case order and observed durations independent of the planned duration`() = runBlocking {
        var clock = 0L; val started = mutableListOf<String>(); val results = mutableListOf<TestResult>()
        val outcome = CampaignRunner.run(CampaignConfig(1, false, false, false, exception = false), { clock }, { started += it.id }, { clock += 1234 }, results::add)
        assertTrue(outcome.success)
        assertEquals(listOf("baseline", "stability", "final_stop"), started)
        assertTrue(results.all { it.durationMs == 1234L })
    }
    @Test fun `p95 stats bounded but real max kept without retaining every sample`() {
        val stats = TimingStats()
        assertNull(stats.percentile(95))
        (1L..99L).forEach(stats::add); stats.add(25000)
        assertEquals(100L, stats.count)
        assertEquals(95L, stats.percentile(95))
        assertEquals(10000L, stats.percentile(100))
        assertEquals(25000L, stats.maxMs)
        assertEquals(299.5, stats.meanMs, 0.00001)
    }
    @Test fun `fully local embedded self-check succeeds`() {
        val checks = LocalChecks.run()
        assertEquals(9, checks.size)
        assertTrue(checks.all { it.outcome == "PASS LOCAL" })
    }
}
