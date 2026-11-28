package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.maintenance.StagingMaintenancePolicy
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException

class TelegramLongPollingWorker(
    private val getUpdates: suspend (offset: Long?, timeoutSeconds: Int) -> List<TelegramUpdate>,
    private val getWebhookUrl: suspend () -> String,
    private val processUpdate: suspend (TelegramUpdate) -> Unit,
    private val trafficPolicy: TelegramTrafficPolicy,
    private val maintenancePolicy: StagingMaintenancePolicy = StagingMaintenancePolicy.off(),
    private val timeoutSeconds: Int,
    private val scope: CoroutineScope,
    private val onPollingSuccess: () -> Unit = {},
    private val errorDelayMillis: Long = 1_000,
    private val canDispatch: () -> Boolean = { true },
    private val canLongPoll: () -> Boolean = canDispatch,
    private val onProductionFailure: ((PollingFailure) -> Unit)? = null,
) {
    private val logger = LoggerFactory.getLogger(TelegramLongPollingWorker::class.java)
    private var offset: Long? = null

    suspend fun start(): Job {
        if (!pollerLease.compareAndSet(false, true)) {
            onProductionFailure?.invoke(PollingFailure.CONFLICT)
            error("A Telegram long poller is already running in this process")
        }
        return try {
            // Production can cancel the leased worker even during a network outage in preflight.
            if (onProductionFailure == null) verifyWebhookIsDisabled()
            scope.launch {
                var verified = onProductionFailure == null
                var backoff = errorDelayMillis
                while (isActive) {
                    val longPoll = canLongPoll()
                    val updates =
                        try {
                            if (!verified) {
                                verifyWebhookIsDisabled()
                                verified = true
                            }
                            if (longPoll && canLongPoll()) {
                                getUpdates(offset, timeoutSeconds)
                            } else {
                                getUpdates(null, 0)
                                emptyList()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            val failure = classify(e)
                            onProductionFailure?.invoke(failure)
                            if (onProductionFailure != null && !failure.retryable) return@launch
                            if (onProductionFailure == null) {
                                logger.warn("Telegram long polling request failed errorType={}", e::class.simpleName)
                            }
                            val retryAfter = (e as? TelegramApiException)?.retryAfterSeconds ?: 0
                            delay(maxOf(backoff, retryAfter.coerceIn(0, Int.MAX_VALUE.toLong()) * 1_000))
                            backoff = (backoff * 2).coerceAtMost(30_000)
                            continue
                        }
                    backoff = errorDelayMillis
                    onPollingSuccess()
                    if (longPoll && canDispatch()) processBatch(updates) else delay(200)
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
        if (getWebhookUrl().isNotBlank()) throw TelegramApiException("Webhook configured", 409)
    }

    internal suspend fun processBatch(updates: List<TelegramUpdate>) {
        updates.sortedBy { it.updateId }.forEach { update ->
            if (!canDispatch()) return
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

    enum class PollingFailure(val retryable: Boolean) {
        NETWORK(true),
        RATE_LIMIT(true),
        UPSTREAM(true),
        INVALID_RESPONSE(true),
        AUTHORIZATION(false),
        CONFLICT(false),
        PROTOCOL(false),
    }

    private fun classify(error: Exception): PollingFailure {
        val causes = generateSequence<Throwable>(error) { it.cause }
        return when {
            error is TelegramApiException ->
                when (error.code) {
                    null -> PollingFailure.INVALID_RESPONSE
                    401, 403, 404 -> PollingFailure.AUTHORIZATION
                    409 -> PollingFailure.CONFLICT
                    429 -> PollingFailure.RATE_LIMIT
                    408, in 500..599 -> PollingFailure.UPSTREAM
                    else -> PollingFailure.PROTOCOL
                }
            causes.any { it is CertificateException || it is SSLPeerUnverifiedException } ->
                PollingFailure.AUTHORIZATION
            causes.any { it is IOException || it is UnresolvedAddressException } ->
                PollingFailure.NETWORK
            error is ContentConvertException || error is SerializationException -> PollingFailure.INVALID_RESPONSE
            error is NoTransformationFoundException -> PollingFailure.INVALID_RESPONSE
            else -> PollingFailure.PROTOCOL
        }
    }

    private companion object {
        val pollerLease = AtomicBoolean(false)
    }
}
