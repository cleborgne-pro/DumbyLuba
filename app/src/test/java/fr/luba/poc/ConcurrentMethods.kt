package fr.luba.poc

import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.RunnerScheduler

/**
 * Runs the test methods of a class at the same time, each on its own instance as usual. Reserved for real-time classes
 * whose methods mostly wait (simulated robot at 5 Hz behind a loopback socket) and share no state: run one after
 * another, GatewayAutonomyTest alone kept a test process busy for about two minutes while the other processes had
 * finished. Not for Robolectric classes (one sandbox per class, not thread-safe) nor for tests that replace [Clock].
 */
class ConcurrentMethods(klass: Class<*>) : BlockJUnit4ClassRunner(klass) {
    init {
        setScheduler(object : RunnerScheduler {
            private val pool = Executors.newCachedThreadPool { task -> Thread(task, "concurrent-test-method").apply { isDaemon = true } }
            private val started = mutableListOf<Future<*>>()

            override fun schedule(childStatement: Runnable) { started += pool.submit(childStatement) }

            // Each method reports its own failure to the notifier; get() only waits and rethrows a runner error.
            override fun finished() {
                try { started.forEach { it.get() } } finally { pool.shutdown() }
            }
        })
    }
}
