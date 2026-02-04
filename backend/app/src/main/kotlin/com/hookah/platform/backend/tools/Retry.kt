package com.hookah.platform.backend.tools

import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

suspend fun <T> retryWithBackoff(
    maxAttempts: Int,
    maxDelayMillis: Long,
    jitterRatio: Double = 0.2,
    baseDelayMillis: Long = 100,
    shouldRetry: (Throwable) -> Boolean,
    block: suspend (attempt: Int) -> T
): T {
    require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    require(maxDelayMillis >= 0) { "maxDelayMillis must be >= 0" }
    require(baseDelayMillis >= 0) { "baseDelayMillis must be >= 0" }
    require(jitterRatio >= 0) { "jitterRatio must be >= 0" }

    var attempt = 1
    var delayMillis = baseDelayMillis.coerceAtMost(maxDelayMillis)
    while (true) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (attempt >= maxAttempts || !shouldRetry(e)) {
                throw e
            }
            val cappedDelay = min(maxDelayMillis, delayMillis)
            val jitterMultiplier = if (jitterRatio == 0.0) 1.0 else {
                1.0 + Random.nextDouble(-jitterRatio, jitterRatio)
            }
            val sleepMillis = (cappedDelay * jitterMultiplier).toLong().coerceAtLeast(0L)
            if (sleepMillis > 0) {
                delay(sleepMillis)
            }
            delayMillis = min(maxDelayMillis, delayMillis * 2)
            attempt += 1
        }
    }
}
