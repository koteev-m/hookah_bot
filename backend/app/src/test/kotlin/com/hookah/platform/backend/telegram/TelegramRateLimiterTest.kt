package com.hookah.platform.backend.telegram

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramRateLimiterTest {
    @Test
    fun `paces repeated sends to the same tracked chat`() =
        runBlocking {
            val waits = mutableListOf<Long>()
            val now = Instant.parse("2026-08-29T00:00:00Z")
            val limiter =
                InMemoryTelegramRateLimiter(
                    minInterval = Duration.ofSeconds(1),
                    clock = { now },
                    sleeper = waits::add,
                )

            limiter.awaitPermit(10L)
            limiter.awaitPermit(10L)
            limiter.awaitPermit(10L)

            assertEquals(listOf(1_000L, 2_000L), waits)
            assertEquals(1, limiter.trackedChatCount())
        }

    @Test
    fun `bounded overflow state still paces untracked chats and expired capacity recovers`() =
        runBlocking {
            val waits = mutableListOf<Long>()
            var now = Instant.parse("2026-08-29T00:00:00Z")
            val limiter =
                InMemoryTelegramRateLimiter(
                    minInterval = Duration.ofSeconds(1),
                    clock = { now },
                    maxTrackedChats = 1,
                    stateRetention = Duration.ofSeconds(10),
                    sleeper = waits::add,
                )

            limiter.awaitPermit(10L)
            limiter.awaitPermit(20L)
            limiter.awaitPermit(20L)

            assertEquals(1, limiter.trackedChatCount())
            assertEquals(listOf(1_000L), waits)

            now = now.plusSeconds(10)
            limiter.awaitPermit(30L)

            assertEquals(1, limiter.trackedChatCount())
            assertEquals(listOf(1_000L), waits)
        }
}
