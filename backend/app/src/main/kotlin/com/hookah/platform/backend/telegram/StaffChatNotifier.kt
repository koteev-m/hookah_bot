package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.VenueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

data class NewBatchNotification(
    val venueId: Long,
    val orderId: Long,
    val batchId: Long,
    val tableLabel: String,
    val itemsSummary: String?
)

class StaffChatNotifier(
    private val venueRepository: VenueRepository,
    private val apiClientProvider: () -> TelegramApiClient?,
    private val scope: CoroutineScope
) {
    private val logger = LoggerFactory.getLogger(StaffChatNotifier::class.java)

    fun notifyNewBatch(event: NewBatchNotification) {
        scope.launch {
            val apiClient = apiClientProvider() ?: return@launch
            try {
                val venue = venueRepository.findVenueById(event.venueId) ?: return@launch
                val chatId = venue.staffChatId ?: return@launch
                val summary = event.itemsSummary?.takeIf { it.isNotBlank() } ?: "без деталей"
                val message = buildString {
                    append("🆕 Новый заказ\n")
                    append("Заведение: ").append(venue.name).append('\n')
                    append("Стол: ").append(event.tableLabel).append('\n')
                    append("Заказ: #").append(event.orderId).append(" / партия #").append(event.batchId).append('\n')
                    append("Состав: ").append(summary)
                }
                apiClient.sendMessage(chatId, message)
            } catch (e: Exception) {
                val safeMessage = sanitizeTelegramForLog(e.message)
                logger.warn("Failed to notify staff chat for new batch: {}", safeMessage)
                logger.debugTelegramException(e) { "notifyNewBatch exception" }
            }
        }
    }
}
