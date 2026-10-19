package fr.luba.poc.simulation

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PreparationPoolTest {
    @Test(timeout = 20_000) fun nestedCallsKeepOrderWithoutDeadlock() {
        // Each task restarts the pool: nested sequential execution, never a circular wait.
        val result = PreparationPool.map(64, {}, minParallel = 2) { i ->
            PreparationPool.map(16, {}, minParallel = 2) { j -> i * 100 + j }.sum()
        }
        assertEquals(List(64) { i -> (0 until 16).sumOf { j -> i * 100 + j } }, result)
    }

    @Test(timeout = 20_000) fun failureStopsRemainingWorkAndKeepsTheCause() {
        val started = AtomicInteger()
        val error = assertThrows(IllegalArgumentException::class.java) {
            PreparationPool.map(4000, {}) { i ->
                started.incrementAndGet()
                require(i != 3) { "échec $i" }
                Thread.sleep(1)
            }
        }
        assertEquals("échec 3", error.message)
        // The remaining chunks stop at the next element instead of computing everything.
        assertTrue(started.get() < 4000)
    }
}
