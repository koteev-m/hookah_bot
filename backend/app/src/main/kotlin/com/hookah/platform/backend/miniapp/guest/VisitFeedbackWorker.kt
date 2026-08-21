package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.telegram.TelegramKeyboards
import com.hookah.platform.backend.telegram.TelegramOutboundClaimScope
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueueOutcome
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

data class VisitFeedbackWorkerResult(
    val sentCount: Int,
    val failedCount: Int,
)

class VisitFeedbackWorker(
    private val repository: VisitFeedbackRepository,
    private val outboxEnqueuer: TelegramOutboxEnqueuer,
    private val outboundClaimScope: TelegramOutboundClaimScope,
    private val interval: Duration,
    private val batchSize: Int,
    private val scope: CoroutineScope,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(VisitFeedbackWorker::class.java)

    fun start(): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(
                        "Visit feedback worker failed error_type={}",
                        e::class.simpleName ?: "unknown",
                    )
                }
                delay(interval.toMillis())
            }
        }

    suspend fun runOnce(now: Instant = nowProvider()): VisitFeedbackWorkerResult {
        val due =
            repository.pickDueRequests(
                now = now,
                limit = batchSize,
                outboundClaimScope = outboundClaimScope,
            )
        var sent = 0
        var failed = 0
        for (request in due) {
            try {
                when (
                    outboxEnqueuer.enqueueSendMessage(
                        chatId = request.userId,
                        text = buildFeedbackRequestText(request.venueName),
                        replyMarkup = TelegramKeyboards.inlineVisitFeedbackActions(request.visitId),
                    )
                ) {
                    TelegramOutboxEnqueueOutcome.ENQUEUED -> {
                        if (repository.markRequestSent(request.requestId, now)) {
                            sent++
                        }
                    }
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (repository.markRequestFailed(request.requestId, e.message)) {
                    failed++
                }
            }
        }
        if (sent > 0 || failed > 0) {
            logger.info("Processed visit feedback requests sent={} failed={}", sent, failed)
        }
        return VisitFeedbackWorkerResult(sentCount = sent, failedCount = failed)
    }

    private fun buildFeedbackRequestText(venueName: String): String = "Спасибо за визит в $venueName.\nКак всё прошло?"
}
