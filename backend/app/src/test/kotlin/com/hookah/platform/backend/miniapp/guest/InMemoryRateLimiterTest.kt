package com.hookah.platform.backend.miniapp.guest

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryRateLimiterTest {
    @Test
    fun `evicts stale buckets during cleanup`() {
        val limiter = InMemoryRateLimiter(cleanupEvery = 1, maxBuckets = Int.MAX_VALUE)
        val window = Duration.ofSeconds(10)
        val base = Instant.parse("2026-01-01T00:00:00Z")

        val key1 =
            GuestRateLimitKey(
                venueId = 1,
                userId = 11,
                tableSessionId = 111,
                endpoint = "/miniapp/guest/add-batch",
            )
        val key2 =
            GuestRateLimitKey(
                venueId = 1,
                userId = 22,
                tableSessionId = 222,
                endpoint = "/miniapp/guest/add-batch",
            )

        assertTrue(limiter.tryAcquire(key = key1, limit = 2, window = window, now = base))
        assertEquals(1, limiter.bucketCountForTesting())

        val afterWindow = base.plus(window).plusSeconds(1)
        assertTrue(limiter.tryAcquire(key = key2, limit = 2, window = window, now = afterWindow))

        assertEquals(1, limiter.bucketCountForTesting())
    }
}
