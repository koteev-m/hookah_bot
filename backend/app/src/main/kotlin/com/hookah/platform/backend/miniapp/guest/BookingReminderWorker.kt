package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.BookingReminderDelivery
import com.hookah.platform.backend.miniapp.guest.db.BookingReminderKind
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BookingReminderWorkerResult(
    val sentCount: Int,
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
        var sent = 0
        var failed = 0
        for (reminder in due) {
            try {
                val zoneId = venueSettingsRepository.resolveZoneId(reminder.venueId, ZoneId.systemDefault())
                outboxEnqueuer.enqueueSendMessage(
                    chatId = reminder.userId,
                    text = buildReminderText(reminder, zoneId),
                    replyMarkup = TelegramKeyboards.inlineBookingReminderActions(reminder.bookingId),
                )
                repository.markReminderSent(reminder.reminderId, now)
                sent++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.markReminderFailed(reminder.reminderId, e.message)
                failed++
            }
        }
        if (sent > 0 || failed > 0) {
            logger.info("Processed booking reminders sent={} failed={}", sent, failed)
        }
        return BookingReminderWorkerResult(sentCount = sent, failedCount = failed)
    }

    private fun buildReminderText(
        reminder: BookingReminderDelivery,
        zoneId: ZoneId,
    ): String {
        val nominativeLabel = reminder.displayNumber?.let { "бронь №$it" } ?: "бронь"
        val genitiveLabel = reminder.displayNumber?.let { "брони №$it" } ?: "брони"
        val visitText = formatVisitText(reminder, zoneId)
        val deadlineText =
            reminder.arrivalDeadlineAt
                ?.let { LocalDateTime.ofInstant(it, zoneId).format(timeFormatter) }
        return buildString {
            when (reminder.kind) {
                BookingReminderKind.DAY_OF_VISIT -> append("⏰ Напоминаем о $genitiveLabel.")
                BookingReminderKind.PRE_VISIT -> append("⏰ Скоро ваша $nominativeLabel.")
            }
            append("\n\n").append(reminder.venueName)
            append('\n')
            when (reminder.kind) {
                BookingReminderKind.DAY_OF_VISIT ->
                    append(
                        "Сегодня ",
                    ).append(visitText.replaceFirstChar { it.lowercase(Locale.ROOT) })
                BookingReminderKind.PRE_VISIT -> append(visitText)
            }
            deadlineText?.let { append("\nБронь держится до ").append(it).append('.') }
        }
    }

    private fun formatVisitText(
        reminder: BookingReminderDelivery,
        zoneId: ZoneId,
    ): String {
        val serviceDate = reminder.displayDate ?: LocalDateTime.ofInstant(reminder.scheduledAt, zoneId).toLocalDate()
        val actualLocal = LocalDateTime.ofInstant(reminder.scheduledAt, zoneId)
        val weekday = formatWeekdayVisitPhrase(serviceDate.dayOfWeek)
        val time = actualLocal.format(timeFormatter)
        return if (actualLocal.toLocalDate() == serviceDate.plusDays(1)) {
            "Ждём вас $weekday ночью, в $time."
        } else {
            "Ждём вас $weekday, в $time."
        }
    }

    private fun formatWeekdayVisitPhrase(dayOfWeek: DayOfWeek): String =
        when (dayOfWeek) {
            DayOfWeek.MONDAY -> "в понедельник"
            DayOfWeek.TUESDAY -> "во вторник"
            DayOfWeek.WEDNESDAY -> "в среду"
            DayOfWeek.THURSDAY -> "в четверг"
            DayOfWeek.FRIDAY -> "в пятницу"
            DayOfWeek.SATURDAY -> "в субботу"
            DayOfWeek.SUNDAY -> "в воскресенье"
        }

    private companion object {
        val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
