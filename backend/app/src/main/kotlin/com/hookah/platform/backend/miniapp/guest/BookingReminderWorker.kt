package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.BookingReminderDelivery
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.telegram.TelegramKeyboards
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BookingReminderWorkerResult(
    val queuedCount: Int,
    val failedCount: Int,
)

class BookingReminderWorker(
    private val repository: GuestBookingRepository,
    private val outboxEnqueuer: TelegramOutboxEnqueuer,
    private val venueSettingsRepository: VenueSettingsRepository,
    private val interval: Duration,
    private val batchSize: Int,
    private val scope: CoroutineScope,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(BookingReminderWorker::class.java)

    fun start(): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Booking reminder worker failed: {}", e.message)
                    logger.debug("Booking reminder worker failure details", e)
                }
                delay(interval.toMillis())
            }
        }

    suspend fun runOnce(now: Instant = nowProvider()): BookingReminderWorkerResult {
        val due = repository.pickDueReminders(now = now, limit = batchSize)
        var queued = 0
        var failed = 0
        for (reminder in due) {
            try {
                val zoneId = venueSettingsRepository.resolveZoneId(reminder.venueId, ZoneId.systemDefault())
                outboxEnqueuer.enqueueSendMessage(
                    chatId = reminder.userId,
                    text = buildReminderText(reminder, zoneId),
                    replyMarkup =
                        TelegramKeyboards.inlineBookingReminderActions(
                            bookingId = reminder.bookingId,
                            reminderId = reminder.reminderId,
                        ),
                    dedupeKey = reminderOutboxDedupeKey(reminder.reminderId),
                )
                if (repository.markReminderQueued(reminder.reminderId)) {
                    queued++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.markReminderFailed(reminder.reminderId, e.message)
                failed++
            }
        }
        if (queued > 0 || failed > 0) {
            logger.info("Processed booking reminders queued={} failed={}", queued, failed)
        }
        return BookingReminderWorkerResult(queuedCount = queued, failedCount = failed)
    }

    private fun buildReminderText(
        reminder: BookingReminderDelivery,
        zoneId: ZoneId,
    ): String {
        val bookingLabel = reminder.displayNumber?.let { "Бронь №$it" } ?: "Бронь"
        val visitText = LocalDateTime.ofInstant(reminder.scheduledAt, zoneId).format(dateTimeFormatter)
        val deadlineText =
            reminder.arrivalDeadlineAt
                ?.let { LocalDateTime.ofInstant(it, zoneId).format(timeFormatter) }
        return buildString {
            append("Напоминаем о брони")
            append("\n\nМесто: ").append(reminder.venueName)
            append('\n').append(bookingLabel)
            append("\nДата и время: ").append(visitText)
            append("\nГостей: ").append(reminder.partySize ?: "не указано")
            deadlineText?.let { append("\nДержим стол до ").append(it).append('.') }
        }
    }

    private fun reminderOutboxDedupeKey(reminderId: Long): String = "booking-reminder:$reminderId"

    private companion object {
        val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
