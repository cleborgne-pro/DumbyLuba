package fr.luba.poc.simulation

import fr.luba.poc.map.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Shared pool for route preparations: parallel computation off the calling thread,
 * which remains the only one publishing the progress (and hence the cancellation requested by the interface).
 */
internal object PreparationPool {
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private class PoolThread(task: Runnable) : Thread(task, "luba-preparation") { init { isDaemon = true } }
    private val executor by lazy { Executors.newFixedThreadPool(threads) { PoolThread(it) } }
    fun <T> map(count: Int, progress: (Int) -> Unit, minParallel: Int = 32, task: (Int) -> T): List<T> {
        // Nested call from a pool task: sequential, otherwise the threads busy
        // waiting for their sub-tasks could block the whole pool.
        if (threads == 1 || count < minParallel || Thread.currentThread() is PoolThread)
            return List(count) { if (it % 50 == 0) progress(it); task(it) }
        val results = arrayOfNulls<Any?>(count)
        val chunk = if (count < 64) 1 else max(4, count / (threads * 8))
        // Cancellation, error or interrupted progress: the remaining tasks stop
        // at the next element instead of occupying the pool for an abandoned result.
        val aborted = AtomicBoolean()
        val futures = (0 until count step chunk).map { start ->
            executor.submit {
                for (i in start until min(count, start + chunk)) { if (aborted.get()) return@submit; results[i] = task(i) }
            }
        }
        var completed = false
        try {
            futures.forEachIndexed { index, future ->
                while (true) {
                    try { future.get(100, TimeUnit.MILLISECONDS); break }
                    catch (_: TimeoutException) { progress(min(count, index * chunk)) }
                }
                progress(min(count, (index + 1) * chunk))
            }
            completed = true
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (!completed) { aborted.set(true); futures.forEach { it.cancel(false) } }
        }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<T>
    }
}
