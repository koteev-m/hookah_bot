package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.metrics.AppMetrics
import com.hookah.platform.backend.telegram.db.TelegramOutboxMessage
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    private val metrics: AppMetrics? = null,
    private val recipientLocks: TelegramRecipientLockRegistry = TelegramRecipientLockRegistry(),
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
                    logWorkerException("tick", e)
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
                logWorkerException("claim", e)
                return
            }
        if (batch.isEmpty()) return

        for (message in batch) {
            try {
                recipientLocks.withRecipientLock(message.chatId) {
                    processMessage(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scheduleRetry(message, "Telegram outbox processing exception", null)
            }
        }
    }

    private suspend fun processMessage(message: TelegramOutboxMessage) {
        val payload =
            runCatching { json.decodeFromString<JsonElement>(message.payloadJson) }
                .getOrElse {
                    rejectLocalValidation(message, LocalValidationFailure.MALFORMED_JSON)
                    return
                }
        val apiClient = apiClientProvider()
        if (apiClient == null) {
            scheduleRetry(message, "Telegram API client unavailable", null)
            return
        }
        if (message.method == "sendMessage") {
            rateLimiter.awaitPermit(message.chatId)
        }
        if (!repository.isRecipientAuthorized(message.chatId, message.staffLiveOrderId)) {
            rejectLocalValidation(message, LocalValidationFailure.RECIPIENT_NOT_AUTHORIZED)
            return
        }

        val result =
            try {
                apiClient.dispatchOutbox(message.chatId, message.method, payload)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                scheduleRetry(message, "Telegram API dispatch exception", null)
                return
            }

        when (result) {
            is TelegramCallResult.Success -> markSent(message, result)
            is TelegramCallResult.Failure ->
                if (result.origin == TelegramCallResult.Failure.Origin.LOCAL_ENVELOPE_VALIDATION) {
                    rejectLocalValidation(message, LocalValidationFailure.INVALID_ENVELOPE)
                } else {
                    handleFailure(message, result)
                }
            TelegramCallResult.TrafficDenied ->
                rejectLocalValidation(message, LocalValidationFailure.TRAFFIC_POLICY_DENIED)
        }
    }

    private suspend fun rejectLocalValidation(
        message: TelegramOutboxMessage,
        failure: LocalValidationFailure,
    ) {
        logger.warn("Telegram outbox local validation failed reason_code={}", failure.name)
        markFailed(
            message = message,
            reason = failure.persistedReason,
            incrementMetric = false,
        )
    }

    private suspend fun handleFailure(
        message: TelegramOutboxMessage,
        result: TelegramCallResult.Failure,
    ) {
        if (message.method == "answerCallbackQuery") {
            markFailed(message, telegramFailureReason(result))
            return
        }
        if (message.method == "editMessageText" && isMessageNotModified(result.description)) {
            markSent(message, TelegramCallResult.Success(responseJson = null))
            return
        }
        if (shouldRetry(result.errorCode)) {
            scheduleRetry(
                message,
                telegramFailureReason(result),
                result.retryAfterSeconds,
                result.errorCode,
            )
            return
        }
        if (message.method == "editMessageText" && message.staffLiveOrderId != null) {
            enqueueLiveOrderFallback(message)
        }
        logger.warn(
            "Telegram outbox permanent failure method={} error_code={} retry_after_seconds={}",
            outboxMethodCategory(message.method),
            result.errorCode,
            result.retryAfterSeconds,
        )
        markFailed(message, telegramFailureReason(result))
    }

    private fun shouldRetry(errorCode: Int?): Boolean {
        if (errorCode == null) return true
        if (errorCode == 429) return true
        return errorCode >= 500
    }

    private suspend fun markSent(
        message: TelegramOutboxMessage,
        result: TelegramCallResult.Success,
    ) {
        metrics?.incrementOutboundSendSuccess()
        if (message.method == "sendMessage" && message.staffLiveOrderId != null && result.responseJson != null) {
            rememberLiveOrderMessageId(message, result.responseJson)
        }
        try {
            repository.markSent(message.id, nowProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWorkerException("mark sent", e)
        }
    }

    private suspend fun rememberLiveOrderMessageId(
        message: TelegramOutboxMessage,
        responseJson: JsonElement,
    ) {
        val orderId = message.staffLiveOrderId ?: return
        val messageId =
            runCatching { json.decodeFromJsonElement(MessageId.serializer(), responseJson).messageId }
                .onFailure { throwable ->
                    logWorkerException("live order response decode", throwable)
                }.getOrNull() ?: return
        try {
            repository.updateStaffChatOrderMessageId(
                orderId = orderId,
                chatId = message.chatId,
                messageId = messageId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWorkerException("live order message save", e)
        }
    }

    private suspend fun enqueueLiveOrderFallback(message: TelegramOutboxMessage) {
        val orderId = message.staffLiveOrderId ?: return
        val payload =
            runCatching { json.decodeFromString<EditMessageTextPayload>(message.payloadJson) }
                .onFailure { throwable ->
                    logWorkerException("live order fallback decode", throwable)
                }.getOrNull() ?: return
        val fallbackPayload =
            SendMessagePayload(
                chatId = payload.chatId,
                text = payload.text,
                replyMarkup = payload.replyMarkup,
            )
        try {
            repository.enqueueStaffChatOrderFallback(
                orderId = orderId,
                chatId = payload.chatId,
                payloadJson = json.encodeToString(SendMessagePayload.serializer(), fallbackPayload),
            )
            logger.warn("Telegram outbox queued live order fallback message")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWorkerException("live order fallback enqueue", e)
        }
    }

    private fun isMessageNotModified(description: String?): Boolean =
        description
            ?.contains("message is not modified", ignoreCase = true)
            ?: false

    private suspend fun markFailed(
        message: TelegramOutboxMessage,
        reason: String?,
        incrementMetric: Boolean = true,
    ) {
        if (incrementMetric) {
            metrics?.incrementOutboundSendFailed()
        }
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
            logWorkerException("mark failed", e)
        }
    }

    private suspend fun scheduleRetry(
        message: TelegramOutboxMessage,
        reason: String?,
        retryAfterSeconds: Int?,
        errorCode: Int? = null,
    ) {
        val safeReason = sanitizeTelegramForLog(reason ?: "unknown error")
        if (errorCode == 429) {
            metrics?.incrementOutbound429()
        }
        metrics?.incrementOutboundSendFailed()
        if (message.attempts >= config.maxAttempts) {
            markFailed(message, safeReason, incrementMetric = false)
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
            logWorkerException("retry scheduling", e)
        }
    }

    private fun computeBackoffSeconds(attempts: Int): Long {
        val multiplier = 2.0.pow(min(attempts, 6))
        val backoff = (config.minBackoffSeconds * multiplier).toLong()
        return backoff.coerceAtMost(config.maxBackoffSeconds)
    }

    private fun telegramFailureReason(result: TelegramCallResult.Failure): String =
        result.errorCode?.let { "Telegram API error code $it" } ?: "Telegram API error"

    private fun outboxMethodCategory(method: String): String =
        method.takeIf {
            it == "sendMessage" ||
                it == "editMessageText" ||
                it == "sendPhoto" ||
                it == "sendDocument" ||
                it == "answerCallbackQuery"
        } ?: "UNKNOWN"

    private fun logWorkerException(
        operation: String,
        throwable: Throwable,
    ) {
        logger.warn(
            "Telegram outbox operation failed operation={} error_type={}",
            operation,
            throwable::class.java.simpleName,
        )
    }

    private enum class LocalValidationFailure(
        val persistedReason: String,
    ) {
        MALFORMED_JSON("Invalid Telegram outbox payload"),
        INVALID_ENVELOPE("Telegram outbox envelope rejected locally"),
        RECIPIENT_NOT_AUTHORIZED("Telegram outbox recipient no longer authorized"),
        TRAFFIC_POLICY_DENIED("Telegram outbox dispatch denied by traffic policy"),
    }
}
