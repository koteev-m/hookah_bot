package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.booking.formatBookingDisplayLabel
import com.hookah.platform.backend.booking.resolveBookingDisplayZoneId
import com.hookah.platform.backend.support.SupportThreadRecord
import com.hookah.platform.backend.support.SupportThreadType
import java.sql.Connection

private const val BOOKING_MESSAGE_STAFF_ALERT_DEDUPE_SUFFIX = "venue-staff-alert"

class BookingMessageStaffChatNotifier(
    private val outboxEnqueuer: TelegramOutboxEnqueuer,
    private val isTelegramActive: () -> Boolean,
    private val webAppPublicUrl: () -> String?,
) {
    fun enqueueGuestMessageAlertInTransaction(
        connection: Connection,
        thread: SupportThreadRecord,
        messageId: Long,
    ) {
        if (!isTelegramActive()) return
        if (thread.threadType != SupportThreadType.BOOKING_THREAD) return
        val venueId = thread.venueId ?: return
        val bookingId = thread.bookingId ?: return
        val booking = thread.booking?.takeIf { it.bookingId == bookingId } ?: return
        val baseUrl = webAppPublicUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val target = findTarget(connection, venueId) ?: return
        val bookingLabel =
            formatBookingDisplayLabel(
                bookingId = bookingId,
                displayNumber = booking.displayNumber,
                scheduledAt = booking.scheduledAt,
                venueZoneId = resolveBookingDisplayZoneId(target.timezone),
            )
        val conversationUrl = buildVenueBookingConversationWebAppUrl(baseUrl, venueId, thread.id)
        val replyMarkup =
            InlineKeyboardMarkup(
                inlineKeyboard =
                    listOf(
                        listOf(
                            InlineKeyboardButton(
                                text = "Открыть переписку",
                                url = conversationUrl,
                            ),
                        ),
                    ),
            )
        outboxEnqueuer.enqueueVenueBookingSendMessageInTransaction(
            connection = connection,
            venueId = venueId,
            chatId = target.chatId,
            text = buildBookingMessageStaffChatAlertText(bookingLabel, thread.guestDisplayName),
            replyMarkup = replyMarkup,
            dedupeKey = bookingMessageStaffAlertDedupeKey(messageId),
        )
    }

    private fun findTarget(
        connection: Connection,
        venueId: Long,
    ): BookingMessageStaffChatTarget? {
        val chatId =
            connection.prepareStatement(
                "SELECT staff_chat_id FROM venues WHERE id = ? FOR UPDATE",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    rows.getLong("staff_chat_id").takeUnless { rows.wasNull() }
                }
            } ?: return null
        val timezone =
            connection.prepareStatement(
                "SELECT timezone FROM venue_settings WHERE venue_id = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString("timezone") else null }
            }
        return BookingMessageStaffChatTarget(chatId = chatId, timezone = timezone)
    }
}

private data class BookingMessageStaffChatTarget(
    val chatId: Long,
    val timezone: String?,
)

internal fun bookingMessageStaffAlertDedupeKey(messageId: Long): String =
    "booking-thread-message:$messageId:$BOOKING_MESSAGE_STAFF_ALERT_DEDUPE_SUFFIX"

internal fun buildBookingMessageStaffChatAlertText(
    bookingLabel: String,
    guestDisplayName: String?,
): String =
    buildString {
        append("💬 Новое сообщение по брони")
        append('\n').append(bookingLabel)
        append('\n').append("Гость: ").append(safeBookingGuestDisplayName(guestDisplayName))
        append("\nОткройте переписку в Venue Mode.")
    }

internal fun buildVenueBookingConversationWebAppUrl(
    baseUrl: String,
    venueId: Long,
    threadId: Long,
): String {
    val baseWithoutFragment = baseUrl.substringBefore('#')
    return buildWebAppUrl(
        baseWithoutFragment,
        mapOf(
            "mode" to "venue",
            "venueId" to venueId.toString(),
        ),
    ) + "#/messages?threadId=$threadId"
}

private fun safeBookingGuestDisplayName(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() } ?: "Гость"
