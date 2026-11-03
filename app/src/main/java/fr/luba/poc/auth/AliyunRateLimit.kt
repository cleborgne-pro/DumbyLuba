package fr.luba.poc.auth

import fr.luba.poc.i18n.tr

/**
 * Pace of the commands sent to the Aliyun cloud (`/thing/service/invoke`), whose API gateway answers HTTP 429 to a
 * pace the Mammotion cloud accepts (DumbyLuba#3, 2026-11-02: map reading of a LUBA 1 stopped at the first 429).
 *
 * Two rules: one command every [spacingMs] at most, each caller reserving its turn ([reserve]); and after a 429 a
 * pause during which nothing is sent ([limited], [pauseMs]), 60 s doubled at each 429 in a row up to [maxBackoffMs], as
 * PyMammotion (`CloudIOTGateway.send_cloud_command`). An accepted command resets the backoff ([accepted]).
 * Times in ms, given by the caller.
 */
class AliyunRateLimit(
    private val spacingMs: Long = SPACING_MS,
    private val firstBackoffMs: Long = FIRST_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    private var nextSlotAt = 0L
    private var pausedUntil = 0L
    private var backoffMs = firstBackoffMs

    /** Wait before sending a command at [now], its turn reserved: the next caller waits one more spacing. */
    @Synchronized fun reserve(now: Long): Long {
        val at = maxOf(now, nextSlotAt, pausedUntil)
        nextSlotAt = at + spacingMs
        return at - now
    }

    /** Remaining pause after a 429, 0 when commands may be sent. */
    @Synchronized fun pauseMs(now: Long): Long = (pausedUntil - now).coerceAtLeast(0)

    /** A 429 received at [now]: pause armed, returned, and doubled for the next one in a row. */
    @Synchronized fun limited(now: Long): Long {
        val pause = backoffMs
        pausedUntil = now + pause
        backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
        return pause
    }

    @Synchronized fun accepted() { backoffMs = firstBackoffMs }

    companion object {
        /** One command every 2 s: 30 a minute at most, enough for the map elements and the 15 s keep-alive. */
        const val SPACING_MS = 2_000L
        const val FIRST_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 240_000L
        /** HTTP status (or API code) of the gateway when the pace is exceeded. */
        const val TOO_MANY_REQUESTS = 429
    }
}

/** Command not sent or refused by the Aliyun gateway (429): nothing is sent before [retryInMs]. */
class AliyunRateLimitedException(val retryInMs: Long) : IllegalStateException(
    tr("Aliyun cloud: too many requests, next try in ${(retryInMs + 999) / 1000} s",
        "Cloud Aliyun : trop de requêtes, nouvel essai dans ${(retryInMs + 999) / 1000} s"))
