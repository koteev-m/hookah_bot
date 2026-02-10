package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

data class NewBatchNotification(
    val venueId: Long,
    val orderId: Long,
    val batchId: Long,
    val tableLabel: String,
    val itemsSummary: String?,
    val comment: String?,
)

class StaffChatNotifier(
    private val venueRepository: VenueRepository,
    private val notificationRepository: StaffChatNotificationRepository,
    private val outboxEnqueuer: TelegramOutboxEnqueuer,
    private val isTelegramActive: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(StaffChatNotifier::class.java)

    fun notifyNewBatch(event: NewBatchNotification) {
        scope.launch {
            if (!isTelegramActive()) {
                return@launch
            }
            try {
                val venue = venueRepository.findVenueById(event.venueId) ?: return@launch
                val chatId = venue.staffChatId ?: return@launch
                val claimResult = notificationRepository.tryClaim(event.batchId, chatId)
                if (claimResult == StaffChatNotificationClaim.ALREADY) {
                    return@launch
                }
                val summary = event.itemsSummary?.takeIf { it.isNotBlank() } ?: "без деталей"
                val comment = event.comment?.takeIf { it.isNotBlank() }
                val links = comment?.let { extractLinks(it) }.orEmpty()
                val message =
                    buildString {
                        append("🆕 Новый заказ\n")
                        append("Заведение: ").append(venue.name).append('\n')
                        append("Стол: ").append(event.tableLabel).append('\n')
                        append(
                            "Заказ: #",
                        ).append(event.orderId).append(" / партия #").append(event.batchId).append('\n')
                        append("Состав: ").append(summary)
                        if (!comment.isNullOrBlank()) {
                            append('\n').append("Комментарий: ").append(comment)
                        }
                        if (links.isNotEmpty()) {
                            append('\n').append("Ссылки: ").append(links.joinToString(" "))
                        }
                    }
                try {
                    outboxEnqueuer.enqueueSendMessage(chatId, message)
                } catch (e: CancellationException) {
                    if (claimResult == StaffChatNotificationClaim.CLAIMED) {
                        notificationRepository.releaseClaim(event.batchId)
                    }
                    throw e
                } catch (e: Exception) {
                    if (claimResult == StaffChatNotificationClaim.CLAIMED) {
                        notificationRepository.releaseClaim(event.batchId)
                    }
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val safeMessage = sanitizeTelegramForLog(e.message)
                logger.warn("Failed to notify staff chat for new batch: {}", safeMessage)
                logger.debugTelegramException(e) { "notifyNewBatch exception" }
            }
        }
    }
}

private fun extractLinks(text: String): List<String> {
    val regex = Regex("(https?://\\S+)")
    return regex.findAll(text)
        .map { it.value.trimEnd(',', '.', ';') }
        .filter { it.isNotBlank() }
        .take(5)
        .toList()
}
