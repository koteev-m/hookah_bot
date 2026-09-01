package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import com.hookah.platform.backend.tools.retryWithBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class TelegramLongPollingWorker(
    private val getUpdates: suspend (offset: Long?, timeoutSeconds: Int) -> List<TelegramUpdate>,
    private val getWebhookUrl: suspend () -> String,
    private val processUpdate: suspend (TelegramUpdate) -> Unit,
    private val trafficPolicy: TelegramTrafficPolicy,
    private val maintenancePolicy: StagingMaintenancePolicy = StagingMaintenancePolicy.off(),
    private val timeoutSeconds: Int,
    private val scope: CoroutineScope,
    private val errorDelayMillis: Long = 1_000,
) {
    private val logger = LoggerFactory.getLogger(TelegramLongPollingWorker::class.java)
    private var offset: Long? = null

    suspend fun start(): Job {
        check(pollerLease.compareAndSet(false, true)) {
            "A Telegram long poller is already running in this process"
        }
        return try {
            verifyWebhookIsDisabled()
            scope.launch {
                while (isActive) {
                    val updates =
                        try {
                            retryWithBackoff(
                                maxAttempts = 3,
                                maxDelayMillis = 2_000,
                                jitterRatio = 0.2,
                                shouldRetry = { error -> error !is CancellationException },
                            ) {
                                getUpdates(offset, timeoutSeconds)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(
                                "Telegram long polling request failed errorType={}",
                                e::class.simpleName ?: "unknown",
                            )
                            delay(errorDelayMillis)
                            continue
                        }
                    processBatch(updates)
                }
            }.also { job ->
                job.invokeOnCompletion { pollerLease.set(false) }
            }
        } catch (e: CancellationException) {
            pollerLease.set(false)
            throw e
        } catch (e: Throwable) {
            pollerLease.set(false)
            logger.error(
                "Telegram long polling preflight failed errorType={}",
                e::class.simpleName ?: "unknown",
            )
            throw IllegalStateException("Telegram long polling preflight failed")
        }
    }

    internal suspend fun verifyWebhookIsDisabled() {
        check(getWebhookUrl().isBlank()) {
            "Telegram webhook must be disabled before long polling starts"
        }
    }

    internal suspend fun processBatch(updates: List<TelegramUpdate>) {
        updates.sortedBy { it.updateId }.forEach { update ->
            try {
                when (val maintenanceDecision = maintenancePolicy.evaluateInbound(update)) {
                    StagingMaintenancePolicy.InboundDecision.Allowed -> Unit
                    is StagingMaintenancePolicy.InboundDecision.Denied -> {
                        logger.info(
                            "Telegram inbound update denied source=long_polling reason=MAINTENANCE_{}",
                            maintenanceDecision.reason,
                        )
                        offset = nextOffset(update.updateId)
                        return@forEach
                    }
                }
                when (val decision = trafficPolicy.evaluateInbound(update)) {
                    TelegramTrafficPolicy.InboundDecision.Allowed -> processUpdate(update)
                    is TelegramTrafficPolicy.InboundDecision.Denied ->
                        logger.info(
                            "Telegram inbound update denied source=long_polling reason={}",
                            decision.reason,
                        )
                }
                offset = nextOffset(update.updateId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Telegram update processing failed source=long_polling errorType={}",
                    e::class.simpleName ?: "unknown",
                )
                return
            }
        }
    }

    internal fun currentOffset(): Long? = offset

    private fun nextOffset(updateId: Long): Long =
        try {
            Math.addExact(updateId, 1L)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("Telegram update ID cannot be advanced")
        }

    private companion object {
        val pollerLease = AtomicBoolean(false)
    }
}
