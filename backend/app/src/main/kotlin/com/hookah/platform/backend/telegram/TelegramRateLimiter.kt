package com.hookah.platform.backend.telegram

import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

fun interface TelegramRateLimiter {
    suspend fun awaitPermit(chatId: Long)
}

class InMemoryTelegramRateLimiter(
    private val minInterval: Duration,
    private val clock: () -> Instant = Instant::now,
    private val maxTrackedChats: Int = DEFAULT_MAX_TRACKED_CHATS,
    private val stateRetention: Duration = DEFAULT_STATE_RETENTION,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : TelegramRateLimiter {
    private val lock = Any()
    private val lastSentAt = mutableMapOf<Long, Instant>()
    private var overflowLastSentAt: Instant? = null

    init {
        require(!minInterval.isNegative) { "minInterval must not be negative" }
        require(maxTrackedChats > 0) { "maxTrackedChats must be positive" }
        require(!stateRetention.isNegative && !stateRetention.isZero) { "stateRetention must be positive" }
    }

    override suspend fun awaitPermit(chatId: Long) {
        require(chatId != 0L) { "chatId must not be zero" }
        val delayMillis =
            synchronized(lock) {
                val now = clock()
                val existing = lastSentAt[chatId]
                val last =
                    if (existing != null && isExpired(existing, now)) {
                        lastSentAt.remove(chatId)
                        null
                    } else {
                        existing
                    }
                val scheduledAt: Instant
                if (last != null || lastSentAt.size < maxTrackedChats || makeTrackedCapacity(now)) {
                    scheduledAt = nextSlot(last, now)
                    lastSentAt[chatId] = scheduledAt
                } else {
                    val overflowLast = overflowLastSentAt?.takeUnless { isExpired(it, now) }
                    scheduledAt = nextSlot(overflowLast, now)
                    overflowLastSentAt = scheduledAt
                }
                Duration.between(now, scheduledAt).coerceAtLeast(Duration.ZERO).toMillis()
            }
        if (delayMillis > 0) {
            sleeper(delayMillis)
        }
    }

    internal fun trackedChatCount(): Int = synchronized(lock) { lastSentAt.size }

    private fun makeTrackedCapacity(now: Instant): Boolean {
        lastSentAt.entries.removeIf { (_, last) -> isExpired(last, now) }
        return lastSentAt.size < maxTrackedChats
    }

    private fun isExpired(
        last: Instant,
        now: Instant,
    ): Boolean = !now.isBefore(last.plus(stateRetention))

    private fun nextSlot(
        last: Instant?,
        now: Instant,
    ): Instant {
        if (last == null) return now
        val next = last.plus(minInterval)
        return if (next.isAfter(now)) next else now
    }

    private companion object {
        const val DEFAULT_MAX_TRACKED_CHATS = 20_000
        val DEFAULT_STATE_RETENTION: Duration = Duration.ofMinutes(10)
    }
}
