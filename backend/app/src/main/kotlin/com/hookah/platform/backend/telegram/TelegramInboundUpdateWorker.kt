package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
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
    private val processUpdate: suspend (TelegramUpdate) -> Unit,
    private val json: Json,
    private val scope: CoroutineScope,
    private val trafficPolicy: TelegramTrafficPolicy,
    private val maintenancePolicy: StagingMaintenancePolicy = StagingMaintenancePolicy.off(),
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
                            "Telegram webhook worker tick failed errorType={}",
                            e::class.simpleName ?: "unknown",
                        )
                        false
                    }
                if (!didWork) {
                    delay(pollInterval.toMillis())
                }
            }
        }
    }

    suspend fun processOnce(now: Instant = Instant.now()): Boolean {
        val batch =
            if (maintenancePolicy.active) {
                claimMaintenanceEligibleBatch(now)
            } else {
                repository.claimBatch(batchSize, now, visibilityTimeout)
            }
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
            } catch (_: SerializationException) {
                val safeMessage = "invalid telegram update payload"
                logger.warn("Invalid telegram update payload source=webhook_queue")
                repository.markFailed(
                    id = update.id,
                    status = TelegramInboundUpdateStatus.FAILED,
                    lastError = safeMessage?.take(500),
                    processedAt = now,
                    nextAttemptAt = null,
                )
                return
            }

        when (val decision = maintenancePolicy.evaluateInbound(parsed)) {
            StagingMaintenancePolicy.InboundDecision.Allowed -> Unit
            is StagingMaintenancePolicy.InboundDecision.Denied -> {
                logger.info(
                    "Telegram inbound update denied source=webhook_queue reason=MAINTENANCE_{}",
                    decision.reason,
                )
                return
            }
        }

        when (val decision = trafficPolicy.evaluateInbound(parsed)) {
            TelegramTrafficPolicy.InboundDecision.Allowed -> Unit
            is TelegramTrafficPolicy.InboundDecision.Denied -> {
                logger.info(
                    "Telegram inbound update denied source=webhook_queue reason={}",
                    decision.reason,
                )
                repository.markProcessed(update.id, Instant.now())
                return
            }
        }

        try {
            processUpdate(parsed)
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
                "Telegram update processing failed source=webhook_queue attempt={} errorType={}",
                attempts,
                e::class.simpleName ?: "unknown",
            )
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

    private suspend fun claimMaintenanceEligibleBatch(now: Instant): List<TelegramInboundUpdate> {
        val eligibleIds = mutableListOf<Long>()
        var afterId = 0L
        while (eligibleIds.size < batchSize) {
            val candidates = repository.listReadyAfterId(afterId, MAINTENANCE_SCAN_PAGE_SIZE, now)
            if (candidates.isEmpty()) break
            candidates.forEach { candidate ->
                afterId = candidate.id
                val parsed =
                    try {
                        json.decodeFromString(TelegramUpdate.serializer(), candidate.payloadJson)
                    } catch (_: SerializationException) {
                        logger.info(
                            "Telegram inbound update denied source=webhook_queue_scan " +
                                "reason=MAINTENANCE_INVALID_PAYLOAD",
                        )
                        return@forEach
                    }
                val maintenanceDecision = maintenancePolicy.evaluateInbound(parsed)
                if (maintenanceDecision is StagingMaintenancePolicy.InboundDecision.Denied) {
                    logger.info(
                        "Telegram inbound update denied source=webhook_queue_scan reason=MAINTENANCE_{}",
                        maintenanceDecision.reason,
                    )
                    return@forEach
                }
                val productDecision = trafficPolicy.evaluateInbound(parsed)
                if (productDecision is TelegramTrafficPolicy.InboundDecision.Denied) {
                    logger.info(
                        "Telegram inbound update denied source=webhook_queue_scan reason={}",
                        productDecision.reason,
                    )
                    return@forEach
                }
                if (eligibleIds.size < batchSize) eligibleIds += candidate.id
            }
            if (candidates.size < MAINTENANCE_SCAN_PAGE_SIZE) break
        }
        return repository.claimReadyIds(eligibleIds, now, visibilityTimeout)
    }

    private companion object {
        const val MAINTENANCE_SCAN_PAGE_SIZE = 100
    }
}
