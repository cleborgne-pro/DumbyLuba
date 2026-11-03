package fr.luba.poc.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pace of the Aliyun commands (DumbyLuba#3): spacing reserved per caller, pause after a 429 doubled then reset. */
class AliyunRateLimitTest {
    @Test fun `commands sent together are spaced one after the other`() {
        val limit = AliyunRateLimit(spacingMs = 2_000)
        assertEquals(listOf(0L, 2_000L, 4_000L), List(3) { limit.reserve(10_000) })
        assertEquals("a late command waits for nobody", 0L, limit.reserve(20_000))
    }

    @Test fun `a 429 pauses every command, longer at each 429 in a row, reset by an accepted command`() {
        val limit = AliyunRateLimit(spacingMs = 2_000, firstBackoffMs = 60_000, maxBackoffMs = 240_000)
        assertEquals(60_000L, limit.limited(0))
        assertEquals(50_000L, limit.pauseMs(10_000))
        assertEquals("the next command waits for the end of the pause", 50_000L, limit.reserve(10_000))
        assertEquals(listOf(120_000L, 240_000L, 240_000L), List(3) { limit.limited(70_000) })
        assertEquals(0L, limit.pauseMs(400_000))
        limit.accepted()
        assertEquals(60_000L, limit.limited(400_000))
    }
}
