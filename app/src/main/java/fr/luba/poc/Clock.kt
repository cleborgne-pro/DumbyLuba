package fr.luba.poc

import android.os.SystemClock

/**
 * Wall-clock and monotonic time of the business logic, in one place instead of calls to `System.currentTimeMillis()`
 * scattered through the code (audit of 2026-10-30). Production uses the system clocks; a test replaces [wall] and
 * [monotonic] to make time deterministic, then calls [reset] so that the next test starts from the real clocks.
 */
object Clock {
    @Volatile var wall: () -> Long = { System.currentTimeMillis() }
    @Volatile var monotonic: () -> Long = { SystemClock.elapsedRealtime() }

    fun now(): Long = wall()
    fun elapsed(): Long = monotonic()

    /** Restores the real clocks (test tear-down). */
    fun reset() {
        wall = { System.currentTimeMillis() }
        monotonic = { SystemClock.elapsedRealtime() }
    }
}
