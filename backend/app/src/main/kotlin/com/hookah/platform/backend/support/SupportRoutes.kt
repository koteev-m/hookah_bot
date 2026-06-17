package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.TelegramKeyboards
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class SupportBookingContextDto(
    val bookingId: Long,
    val displayNumber: Int? = null,
    val scheduledAt: String? = null,
    val partySize: Int? = null,
    val status: String? = null,
)

@Serializable
data class SupportThreadDto(
    val threadId: Long,
    val venueId: Long,
    val venueName: String? = null,
    val guestDisplayName: String? = null,
    val category: String,
    val contextLabel: String,
    val status: String,
    val bookingId: Long? = null,
    val orderId: Long? = null,
    val tableSessionId: Long? = null,
    val title: String,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val booking: SupportBookingContextDto? = null,
)

@Serializable
data class SupportMessageDto(
    val messageId: Long,
    val threadId: Long,
    val authorRole: String,
    val source: String,
    val text: String,
    val createdAt: String,
)

@Serializable
data class SupportThreadListResponse(
    val items: List<SupportThreadDto>,
)

@Serializable
data class SupportThreadDetailResponse(
    val thread: SupportThreadDto,
    val messages: List<SupportMessageDto>,
)

@Serializable
data class SupportMessageCreateRequest(
    val message: String? = null,
)

@Serializable
data class SupportMessageCreateResponse(
    val thread: SupportThreadDto,
    val message: SupportMessageDto,
    val queued: Boolean = false,
)

fun Route.guestSupportRoutes(
    supportThreadRepository: SupportThreadRepository,
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
) {
    route("/support/threads") {
        get {
            val userId = call.requireUserId()
            val filter = parseSupportInboxFilter(call.request.queryParameters["filter"])
            val threads = supportThreadRepository.listGuestThreads(userId = userId, filter = filter)
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        get("{threadId}") {
            val userId = call.requireUserId()
            val threadId =
                call.parameters["threadId"]?.toLongOrNull()
                    ?: throw InvalidInputException("threadId must be a number")
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requireUserId()
            val threadId =
                call.parameters["threadId"]?.toLongOrNull()
                    ?: throw InvalidInputException("threadId must be a number")
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            val message =
                supportThreadRepository.addMessage(
                    threadId = detail.thread.id,
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = messageText,
                )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val refreshedThread =
                supportThreadRepository.getGuestThread(userId, detail.thread.id)?.thread ?: detail.thread
            val staffChatQueued =
                notifyStaffChatAboutGuestSupportMessage(
                    venueRepository = venueRepository,
                    outboxEnqueuer = outboxEnqueuer,
                    thread = detail.thread,
                    text = messageText,
                )
            call.respond(
                SupportMessageCreateResponse(
                    thread = refreshedThread.toDto(unreadCountOverride = 0),
                    message = message.toDto(),
                    queued = staffChatQueued,
                ),
            )
        }
    }
}

fun Route.venueSupportRoutes(
    venueAccessRepository: VenueAccessRepository,
    supportThreadRepository: SupportThreadRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
) {
    route("/venue/{venueId}/support/threads") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireBookingManage(venueAccessRepository, userId, venueId)
            val bookingId = call.request.queryParameters["bookingId"]?.toLongOrNull()
            val filter = parseSupportInboxFilter(call.request.queryParameters["filter"])
            val threads =
                supportThreadRepository.listVenueThreads(
                    venueId = venueId,
                    viewerUserId = userId,
                    bookingId = bookingId,
                    filter = filter,
                )
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        get("{threadId}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireBookingManage(venueAccessRepository, userId, venueId)
            val threadId =
                call.parameters["threadId"]?.toLongOrNull()
                    ?: throw InvalidInputException("threadId must be a number")
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireBookingManage(venueAccessRepository, userId, venueId)
            val threadId =
                call.parameters["threadId"]?.toLongOrNull()
                    ?: throw InvalidInputException("threadId must be a number")
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            val message =
                supportThreadRepository.addMessage(
                    threadId = detail.thread.id,
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.VENUE,
                    source = SupportMessageSource.VENUE_MINIAPP,
                    text = messageText,
                )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val refreshedThread =
                supportThreadRepository.getVenueThread(venueId, detail.thread.id)?.thread ?: detail.thread
            outboxEnqueuer.enqueueSendMessage(
                chatId = detail.thread.guestUserId,
                text = buildVenueSupportMessageForGuest(detail.thread, messageText),
                replyMarkup =
                    detail.thread.bookingId?.let { bookingId ->
                        TelegramKeyboards.inlineGuestBookingReplyActions(detail.thread.venueId, bookingId)
                    },
            )
            call.respond(
                SupportMessageCreateResponse(
                    thread = refreshedThread.toDto(unreadCountOverride = 0),
                    message = message.toDto(),
                    queued = true,
                ),
            )
        }
    }
}

private suspend fun requireBookingManage(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(VenuePermission.BOOKING_MANAGE)) {
        throw ForbiddenException()
    }
}

fun SupportThreadRecord.toDto(unreadCountOverride: Int? = null): SupportThreadDto =
    SupportThreadDto(
        threadId = id,
        venueId = venueId,
        venueName = venueName,
        guestDisplayName = guestDisplayName,
        category = category.name,
        contextLabel = formatSupportThreadLabel(),
        status = status.name,
        bookingId = bookingId,
        orderId = orderId,
        tableSessionId = tableSessionId,
        title = title,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt?.toString(),
        unreadCount = unreadCountOverride ?: unreadCount,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        booking =
            booking?.let {
                SupportBookingContextDto(
                    bookingId = it.bookingId,
                    displayNumber = it.displayNumber,
                    scheduledAt = it.scheduledAt?.toString(),
                    partySize = it.partySize,
                    status = it.status,
                )
            },
    )

fun SupportMessageRecord.toDto(): SupportMessageDto =
    SupportMessageDto(
        messageId = id,
        threadId = threadId,
        authorRole = authorRole.name,
        source = source.name,
        text = text,
        createdAt = createdAt.toString(),
    )

fun SupportThreadDetailRecord.toResponse(unreadCountOverride: Int? = null): SupportThreadDetailResponse =
    SupportThreadDetailResponse(
        thread = thread.toDto(unreadCountOverride = unreadCountOverride),
        messages = messages.map { it.toDto() },
    )

fun normalizeSupportMessage(value: String?): String {
    val trimmed =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw InvalidInputException("message must not be blank")
    if (trimmed.length > MAX_SUPPORT_MESSAGE_LENGTH) {
        throw InvalidInputException("message must be at most $MAX_SUPPORT_MESSAGE_LENGTH characters")
    }
    return trimmed
}

fun buildVenueSupportMessageForGuest(
    thread: SupportThreadRecord,
    messageText: String,
): String {
    val venueName = thread.venueName ?: "заведение"
    return "Сообщение по вашей ${thread.formatSupportThreadLabelGenitive()} в «$venueName»:\n\n$messageText"
}

private suspend fun notifyStaffChatAboutGuestSupportMessage(
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    thread: SupportThreadRecord,
    text: String,
): Boolean {
    val venue = venueRepository.findVenueById(thread.venueId)
    val staffChatId = venue?.staffChatId ?: return false
    val venueName = venue.name.takeIf { it.isNotBlank() } ?: thread.venueName ?: "Заведение"
    outboxEnqueuer.enqueueSendMessage(
        chatId = staffChatId,
        text =
            buildString {
                append("💬 Ответ гостя по ").append(thread.formatSupportThreadLabel())
                append('\n').append("Заведение: ").append(venueName)
                append('\n').append("Текст: ").append(text)
            },
    )
    return true
}

private fun SupportThreadRecord.formatSupportThreadLabel(): String =
    when (category) {
        SupportThreadCategory.BOOKING ->
            booking?.displayNumber?.let { "Бронь №$it" }
                ?: bookingId?.let { "Бронь #$it" }
                ?: title
        SupportThreadCategory.ORDER -> orderId?.let { "Заказ №$it" } ?: title
        SupportThreadCategory.TABLE -> tableSessionId?.let { "Стол №$it" } ?: title
        SupportThreadCategory.GENERAL -> "Общий вопрос"
        SupportThreadCategory.PLATFORM -> "Проблема"
    }

private fun SupportThreadRecord.formatSupportThreadLabelGenitive(): String =
    booking?.displayNumber?.let { "брони №$it" }
        ?: bookingId?.let { "брони" }
        ?: title

private fun parseSupportInboxFilter(value: String?): SupportInboxFilter? =
    when (value?.trim()?.lowercase()) {
        null, "", "all" -> null
        "active" -> SupportInboxFilter.ACTIVE
        "resolved", "finished", "closed" -> SupportInboxFilter.RESOLVED
        else -> throw InvalidInputException("filter must be active or resolved")
    }

private const val MAX_SUPPORT_MESSAGE_LENGTH = 1000
