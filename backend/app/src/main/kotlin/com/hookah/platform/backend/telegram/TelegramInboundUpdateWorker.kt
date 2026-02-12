package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.metrics.AppMetrics
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdate
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.math.min

class TelegramInboundUpdateWorker(
    private val repository: TelegramInboundUpdateQueueRepository,
    private val router: TelegramBotRouter,
    private val json: Json,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = Duration.ofMillis(500),
    private val batchSize: Int = 10,
    private val maxAttempts: Int = 5,
    private val visibilityTimeout: Duration = Duration.ofMinutes(2),
    private val metrics: AppMetrics? = null,
) {
    private val logger = LoggerFactory.getLogger(TelegramInboundUpdateWorker::class.java)

    fun start(): Job {
        return scope.launch {
            while (isActive) {
                val didWork =
                    try {
                        processOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(
                            "Telegram webhook worker tick failed: {}",
                            sanitizeTelegramForLog(e.message),
                        )
                        logger.debugTelegramException(e) { "Telegram webhook worker exception" }
                        false
                    }
                if (!didWork) {
                    delay(pollInterval.toMillis())
                }
            }
        }
    }

    suspend fun processOnce(now: Instant = Instant.now()): Boolean {
        val batch = repository.claimBatch(batchSize, now, visibilityTimeout)
        if (batch.isEmpty()) {
            return false
        }
        for (update in batch) {
            processUpdate(update, now)
        }
        return true
    }

    private suspend fun processUpdate(
        update: TelegramInboundUpdate,
        now: Instant,
    ) {
        metrics?.recordWebhookProcessingLag(Duration.between(update.receivedAt, now))
        val parsed =
            try {
                json.decodeFromString(TelegramUpdate.serializer(), update.payloadJson)
            } catch (e: SerializationException) {
                val safeMessage = sanitizeTelegramForLog(e.message)
                logger.warn("Invalid telegram update payload id={}: {}", update.updateId, safeMessage)
                repository.markFailed(
                    id = update.id,
                    status = TelegramInboundUpdateStatus.FAILED,
                    lastError = safeMessage?.take(500),
                    processedAt = now,
                    nextAttemptAt = null,
                )
                return
            }

        try {
            router.process(parsed)
            repository.markProcessed(update.id, Instant.now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val safeMessage = sanitizeTelegramForLog(e.message)
            val attempts = update.attempts
            val status =
                if (attempts >= maxAttempts) {
                    TelegramInboundUpdateStatus.FAILED
                } else {
                    TelegramInboundUpdateStatus.RETRY
                }
            val nextAttemptAt =
                if (status == TelegramInboundUpdateStatus.RETRY) {
                    Instant.now().plusMillis(backoffDelayMillis(attempts))
                } else {
                    null
                }
            logger.warn(
                "Telegram update processing failed id={} attempt={}: {}",
                update.updateId,
                attempts,
                safeMessage,
            )
            logger.debugTelegramException(e) { "Telegram update processing exception id=${update.updateId}" }
            repository.markFailed(
                id = update.id,
                status = status,
                lastError = safeMessage?.take(500),
                processedAt = if (status == TelegramInboundUpdateStatus.FAILED) Instant.now() else null,
                nextAttemptAt = nextAttemptAt,
            )
        }
    }

    private fun backoffDelayMillis(attempts: Int): Long {
        val base = 500L
        val exponential = base * (1L shl min(attempts.coerceAtLeast(1), 6))
        return min(exponential, 60_000L)
    }
}
