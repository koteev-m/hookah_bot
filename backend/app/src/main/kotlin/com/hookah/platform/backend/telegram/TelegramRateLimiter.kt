package com.hookah.platform.backend.telegram

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

fun interface TelegramRateLimiter {
    suspend fun awaitPermit(chatId: Long)
}

class InMemoryTelegramRateLimiter(
    private val minInterval: Duration,
    private val clock: () -> Instant = Instant::now,
) : TelegramRateLimiter {
    private val mutex = Mutex()
    private val lastSentAt = mutableMapOf<Long, Instant>()

    override suspend fun awaitPermit(chatId: Long) {
        val delayMillis =
            mutex.withLock {
                val now = clock()
                val last = lastSentAt[chatId]
                val waitMillis =
                    if (last == null) {
                        0L
                    } else {
                        val elapsed = Duration.between(last, now)
                        (minInterval.minus(elapsed)).coerceAtLeast(Duration.ZERO).toMillis()
                    }
                if (waitMillis == 0L) {
                    lastSentAt[chatId] = now
                } else {
                    lastSentAt[chatId] = now.plusMillis(waitMillis)
                }
                waitMillis
            }
        if (delayMillis > 0) {
            delay(delayMillis)
        }
    }
}
