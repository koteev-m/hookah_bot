package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.TelegramOutboxMessage
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

class TelegramOutboxWorker(
    private val repository: TelegramOutboxRepository,
    private val apiClientProvider: () -> TelegramApiClient?,
    private val json: Json,
    private val rateLimiter: TelegramRateLimiter,
    private val config: TelegramOutboxConfig,
    private val scope: CoroutineScope,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(TelegramOutboxWorker::class.java)

    fun start(): Job =
        scope.launch {
            while (isActive) {
                try {
                    processOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Telegram outbox worker tick failed: {}", sanitizeTelegramForLog(e.message))
                    logger.debugTelegramException(e) { "Telegram outbox worker tick exception" }
                }
                delay(config.pollIntervalMillis)
            }
        }

    suspend fun processOnce() {
        val now = nowProvider()
        val batch =
            try {
                repository.claimBatch(
                    limit = config.batchSize,
                    now = now,
                    visibilityTimeout = Duration.ofSeconds(config.visibilityTimeoutSeconds),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Telegram outbox claim failed: {}", sanitizeTelegramForLog(e.message))
                logger.debugTelegramException(e) { "Telegram outbox claim exception" }
                return
            }
        if (batch.isEmpty()) return

        val semaphore = Semaphore(config.maxConcurrency)
        coroutineScope {
            batch.forEach { message ->
                launch {
                    try {
                        semaphore.withPermit {
                            processMessage(message)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        scheduleRetry(message, e.message, null)
                    }
                }
            }
        }
    }

    private suspend fun processMessage(message: TelegramOutboxMessage) {
        val apiClient = apiClientProvider()
        if (apiClient == null) {
            scheduleRetry(message, "Telegram API client unavailable", null)
            return
        }
        val payload =
            runCatching { json.decodeFromString<JsonElement>(message.payloadJson) }
                .getOrElse { error ->
                    markFailed(message, "Invalid payload: ${sanitizeTelegramForLog(error.message)}")
                    return
                }
        if (message.method == "sendMessage") {
            rateLimiter.awaitPermit(message.chatId)
        }

        val result =
            try {
                apiClient.callMethod(message.method, payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scheduleRetry(message, e.message, null)
                return
            }

        when (result) {
            is TelegramCallResult.Success -> markSent(message)
            is TelegramCallResult.Failure -> handleFailure(message, result)
        }
    }

    private suspend fun handleFailure(
        message: TelegramOutboxMessage,
        result: TelegramCallResult.Failure,
    ) {
        if (message.method == "answerCallbackQuery") {
            markFailed(message, result.description)
            return
        }
        if (shouldRetry(result.errorCode)) {
            scheduleRetry(
                message,
                result.description,
                result.retryAfterSeconds,
                result.errorCode,
            )
            return
        }
        markFailed(message, result.description)
    }

    private fun shouldRetry(errorCode: Int?): Boolean {
        if (errorCode == null) return true
        if (errorCode == 429) return true
        return errorCode >= 500
    }

    private suspend fun markSent(message: TelegramOutboxMessage) {
        try {
            repository.markSent(message.id, nowProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Telegram outbox mark sent failed: {}", sanitizeTelegramForLog(e.message))
            logger.debugTelegramException(e) { "Telegram outbox mark sent exception" }
        }
    }

    private suspend fun markFailed(
        message: TelegramOutboxMessage,
        reason: String?,
    ) {
        val safeReason = sanitizeTelegramForLog(reason ?: "unknown error")
        try {
            repository.markFailed(
                id = message.id,
                status = TelegramOutboxStatus.FAILED,
                lastError = safeReason,
                processedAt = nowProvider(),
                nextAttemptAt = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Telegram outbox mark failed error: {}", sanitizeTelegramForLog(e.message))
            logger.debugTelegramException(e) { "Telegram outbox mark failed exception" }
        }
    }

    private suspend fun scheduleRetry(
        message: TelegramOutboxMessage,
        reason: String?,
        retryAfterSeconds: Int?,
        errorCode: Int? = null,
    ) {
        val safeReason = sanitizeTelegramForLog(reason ?: "unknown error")
        if (message.attempts >= config.maxAttempts) {
            markFailed(message, safeReason)
            return
        }

        val backoffSeconds =
            if (errorCode == 429 && retryAfterSeconds != null) {
                maxOf(config.minBackoffSeconds, retryAfterSeconds.toLong())
            } else if (retryAfterSeconds != null) {
                maxOf(config.minBackoffSeconds, retryAfterSeconds.toLong())
            } else {
                computeBackoffSeconds(message.attempts)
            }
        val nextAttemptAt = nowProvider().plusSeconds(backoffSeconds)

        try {
            repository.markFailed(
                id = message.id,
                status = TelegramOutboxStatus.NEW,
                lastError = safeReason,
                processedAt = null,
                nextAttemptAt = nextAttemptAt,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Telegram outbox retry scheduling failed: {}", sanitizeTelegramForLog(e.message))
            logger.debugTelegramException(e) { "Telegram outbox retry scheduling exception" }
        }
    }

    private fun computeBackoffSeconds(attempts: Int): Long {
        val multiplier = 2.0.pow(min(attempts, 6))
        val backoff = (config.minBackoffSeconds * multiplier).toLong()
        return backoff.coerceAtMost(config.maxBackoffSeconds)
    }
}
