package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.TableSessionConfig
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.validateTableToken
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.platform.PlatformConfig
import com.hookah.platform.backend.platform.requirePlatformOwner
import com.hookah.platform.backend.telegram.TableContext
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val venueId: Long? = null,
    val venueName: String? = null,
    val guestDisplayName: String? = null,
    val threadType: String,
    val assigneeScope: String,
    val category: String,
    val contextLabel: String,
    val status: String,
    val statusLabel: String,
    val bookingId: Long? = null,
    val orderId: Long? = null,
    val orderDisplayLabel: String? = null,
    val tableId: Long? = null,
    val tableSessionId: Long? = null,
    val tableLabel: String? = null,
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
data class SupportThreadCreateRequest(
    val category: String? = null,
    val title: String? = null,
    val message: String? = null,
    val venueId: Long? = null,
    val tableToken: String? = null,
    val tableSessionId: Long? = null,
    val orderId: Long? = null,
    val bookingId: Long? = null,
    val appVersion: String? = null,
    val correlationId: String? = null,
)

@Serializable
data class SupportMessageCreateResponse(
    val thread: SupportThreadDto,
    val message: SupportMessageDto,
    val queued: Boolean = false,
)

@Serializable
data class SupportThreadCreateResponse(
    val thread: SupportThreadDto,
    val message: SupportMessageDto,
    val queued: Boolean = false,
)

@Serializable
data class SupportAssigneeScopeRequest(
    val assigneeScope: String? = null,
)

@Serializable
data class SupportStatusChangeRequest(
    val status: String? = null,
)

fun Route.guestSupportRoutes(
    supportThreadRepository: SupportThreadRepository,
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    tableTokenResolver: suspend (String) -> TableContext?,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestBookingRepository: GuestBookingRepository,
    auditLogRepository: AuditLogRepository? = null,
) {
    route("/support/threads") {
        get {
            val userId = call.requireUserId()
            val filter = parseSupportInboxFilter(call.request.queryParameters["filter"])
            val threads = supportThreadRepository.listGuestThreads(userId = userId, filter = filter)
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        post {
            val userId = call.requireUserId()
            val request = call.receive<SupportThreadCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val category = normalizeSupportCategory(request.category, default = SupportThreadCategory.OTHER)
            val verified =
                verifyGuestTicketContext(
                    request = request,
                    userId = userId,
                    category = category,
                    tableTokenResolver = tableTokenResolver,
                    tableSessionRepository = tableSessionRepository,
                    tableSessionConfig = tableSessionConfig,
                    guestBookingRepository = guestBookingRepository,
                    supportThreadRepository = supportThreadRepository,
                )
            val assigneeScope = defaultAssigneeScope(category, verified.venueId)
            val detail =
                supportThreadRepository.createTicket(
                    SupportTicketCreateInput(
                        guestUserId = userId,
                        category = category,
                        title = normalizeSupportTitle(request.title, category, verified),
                        message = messageText,
                        venueId = verified.venueId,
                        tableId = verified.tableId,
                        tableSessionId = verified.tableSessionId,
                        orderId = verified.orderId,
                        bookingId = verified.bookingId,
                        assigneeScope = assigneeScope,
                        createdSource = SupportThreadCreatedSource.GUEST_MINIAPP,
                        messageSource = SupportMessageSource.GUEST_MINIAPP,
                        appVersion = normalizeOptionalMetadata(request.appVersion, 80),
                        correlationId = normalizeOptionalMetadata(request.correlationId, 120),
                    ),
                )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val staffChatQueued =
                notifyStaffChatAboutGuestCreatedTicket(
                    venueRepository = venueRepository,
                    outboxEnqueuer = outboxEnqueuer,
                    thread = detail.thread,
                    text = messageText,
                )
            call.respond(
                SupportThreadCreateResponse(
                    thread = detail.thread.toDto(unreadCountOverride = 0),
                    message = detail.messages.last().toDto(),
                    queued = staffChatQueued,
                ),
            )
        }

        get("{threadId}") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/resolve") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            requireThreadStatusChangeAllowed(detail.thread)
            changeThreadStatus(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newStatus = SupportThreadStatus.RESOLVED,
                source = "GUEST_MINIAPP",
            )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val updated = supportThreadRepository.getGuestThread(userId, detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/reopen") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            requireThreadStatusChangeAllowed(detail.thread)
            changeThreadStatus(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newStatus = SupportThreadStatus.IN_PROGRESS,
                source = "GUEST_MINIAPP",
            )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val updated = supportThreadRepository.getGuestThread(userId, detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            requireThreadMessageAllowed(detail.thread)
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
                    thread = refreshedThread,
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
    auditLogRepository: AuditLogRepository? = null,
) {
    route("/venue/{venueId}/support/threads") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
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
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/resolve") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            requireVenueCanActOnThread(detail.thread)
            requireThreadStatusChangeAllowed(detail.thread)
            changeThreadStatus(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newStatus = SupportThreadStatus.RESOLVED,
                source = "VENUE_MINIAPP",
            )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val updated = supportThreadRepository.getVenueThread(venueId, detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/reopen") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            requireVenueCanActOnThread(detail.thread)
            requireThreadStatusChangeAllowed(detail.thread)
            changeThreadStatus(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newStatus = SupportThreadStatus.IN_PROGRESS,
                source = "VENUE_MINIAPP",
            )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val updated = supportThreadRepository.getVenueThread(venueId, detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/escalate") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            requireThreadStatusChangeAllowed(detail.thread)
            changeThreadScope(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newScope = SupportAssigneeScope.PLATFORM,
                source = "VENUE_MINIAPP",
                action = SUPPORT_TICKET_ESCALATED,
            )
            if (detail.thread.status == SupportThreadStatus.NEW || detail.thread.status == SupportThreadStatus.OPEN) {
                changeThreadStatus(
                    supportThreadRepository = supportThreadRepository,
                    auditLogRepository = auditLogRepository,
                    actorUserId = userId,
                    thread = detail.thread,
                    newStatus = SupportThreadStatus.IN_PROGRESS,
                    source = "VENUE_MINIAPP",
                )
            }
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val updated = supportThreadRepository.getVenueThread(venueId, detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            requireVenueCanActOnThread(detail.thread)
            requireThreadMessageAllowed(detail.thread)
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
                text = buildSupportReplyMessageForGuest(refreshedThread, messageText, "заведения"),
                replyMarkup =
                    detail.thread.bookingId?.let { bookingId ->
                        refreshedThread.venueId?.let { venue ->
                            TelegramKeyboards.inlineGuestBookingReplyActions(venue, bookingId)
                        }
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

fun Route.platformSupportRoutes(
    platformConfig: PlatformConfig,
    supportThreadRepository: SupportThreadRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    auditLogRepository: AuditLogRepository,
) {
    route("/platform/support/threads") {
        get {
            val userId = call.requirePlatformOwner(platformConfig)
            val filter = parseSupportInboxFilter(call.request.queryParameters["filter"])
            val assigneeScope = parseOptionalAssigneeScope(call.request.queryParameters["assigneeScope"])
            val venueId = call.request.queryParameters["venueId"]?.toLongOrNull()
            val threads =
                supportThreadRepository.listPlatformThreads(
                    viewerUserId = userId,
                    filter = filter,
                    assigneeScope = assigneeScope,
                    venueId = venueId,
                )
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        get("{threadId}") {
            val userId = call.requirePlatformOwner(platformConfig)
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getPlatformThread(threadId) ?: throw NotFoundException()
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requirePlatformOwner(platformConfig)
            val threadId = call.parseThreadId()
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getPlatformThread(threadId) ?: throw NotFoundException()
            requireThreadMessageAllowed(detail.thread)
            val message =
                supportThreadRepository.addMessage(
                    threadId = detail.thread.id,
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.PLATFORM,
                    source = SupportMessageSource.PLATFORM_MINIAPP,
                    text = messageText,
                )
            supportThreadRepository.markThreadRead(threadId = detail.thread.id, userId = userId)
            val refreshedThread =
                supportThreadRepository.getPlatformThread(detail.thread.id)?.thread ?: detail.thread
            outboxEnqueuer.enqueueSendMessage(
                chatId = detail.thread.guestUserId,
                text = buildSupportReplyMessageForGuest(refreshedThread, messageText, "поддержки платформы"),
            )
            call.respond(
                SupportMessageCreateResponse(
                    thread = refreshedThread.toDto(unreadCountOverride = 0),
                    message = message.toDto(),
                    queued = true,
                ),
            )
        }

        post("{threadId}/assign") {
            val userId = call.requirePlatformOwner(platformConfig)
            val threadId = call.parseThreadId()
            val request = call.receive<SupportAssigneeScopeRequest>()
            val newScope = parseRequiredAssigneeScope(request.assigneeScope)
            val detail = supportThreadRepository.getPlatformThread(threadId) ?: throw NotFoundException()
            if (newScope == SupportAssigneeScope.VENUE && detail.thread.venueId == null) {
                throw InvalidInputException("venue-scoped assignment requires venue context")
            }
            changeThreadScope(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newScope = newScope,
                source = "PLATFORM_MINIAPP",
                action = SUPPORT_TICKET_ASSIGNED,
            )
            val updated = supportThreadRepository.getPlatformThread(detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/status") {
            val userId = call.requirePlatformOwner(platformConfig)
            val threadId = call.parseThreadId()
            val request = call.receive<SupportStatusChangeRequest>()
            val newStatus = parseWritableStatus(request.status)
            val detail = supportThreadRepository.getPlatformThread(threadId) ?: throw NotFoundException()
            changeThreadStatus(
                supportThreadRepository = supportThreadRepository,
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                thread = detail.thread,
                newStatus = newStatus,
                source = "PLATFORM_MINIAPP",
            )
            val updated = supportThreadRepository.getPlatformThread(detail.thread.id) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }
    }
}

private data class VerifiedTicketContext(
    val venueId: Long? = null,
    val tableId: Long? = null,
    val tableSessionId: Long? = null,
    val orderId: Long? = null,
    val orderDisplayLabel: String? = null,
    val bookingId: Long? = null,
    val bookingDisplayNumber: Int? = null,
)

private suspend fun verifyGuestTicketContext(
    request: SupportThreadCreateRequest,
    userId: Long,
    category: SupportThreadCategory,
    tableTokenResolver: suspend (String) -> TableContext?,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestBookingRepository: GuestBookingRepository,
    supportThreadRepository: SupportThreadRepository,
): VerifiedTicketContext {
    var venueId: Long? = null
    var tableId: Long? = null
    var tableSessionId: Long? = null
    if (!request.tableToken.isNullOrBlank()) {
        val token = validateTableToken(request.tableToken)
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        venueId = table.venueId
        tableId = table.tableId
        if (request.tableSessionId != null) {
            val session =
                tableSessionRepository.touchActiveSession(
                    tableSessionId = request.tableSessionId,
                    venueId = table.venueId,
                    tableId = table.tableId,
                    ttl = tableSessionConfig.ttl,
                ) ?: throw InvalidInputException("table context is no longer active")
            tableSessionId = session.id
        }
    }

    var bookingId: Long? = null
    var bookingDisplayNumber: Int? = null
    if (request.bookingId != null) {
        val booking = guestBookingRepository.findActiveByGuest(request.bookingId, userId) ?: throw NotFoundException()
        if (venueId != null && venueId != booking.venueId) {
            throw InvalidInputException("booking does not match table context")
        }
        venueId = booking.venueId
        bookingId = booking.id
        bookingDisplayNumber = booking.displayNumber
    } else if (category == SupportThreadCategory.BOOKING) {
        throw InvalidInputException("bookingId is required for booking support")
    }

    var orderId: Long? = null
    var orderDisplayLabel: String? = null
    if (request.orderId != null) {
        val order =
            supportThreadRepository.findOrderContextForGuest(
                orderId = request.orderId,
                userId = userId,
                venueId = venueId,
                tableSessionId = tableSessionId,
            ) ?: throw NotFoundException()
        venueId = order.venueId
        tableId = tableId ?: order.tableId
        tableSessionId = tableSessionId ?: order.tableSessionId
        orderId = order.orderId
        orderDisplayLabel = order.displayLabel
    }

    return VerifiedTicketContext(
        venueId = venueId,
        tableId = tableId,
        tableSessionId = tableSessionId,
        orderId = orderId,
        orderDisplayLabel = orderDisplayLabel,
        bookingId = bookingId,
        bookingDisplayNumber = bookingDisplayNumber,
    )
}

private fun requireThreadStatusChangeAllowed(thread: SupportThreadRecord) {
    if (thread.status == SupportThreadStatus.CLOSED) {
        throw InvalidInputException("closed thread cannot be changed")
    }
}

private fun requireThreadMessageAllowed(thread: SupportThreadRecord) {
    if (thread.status == SupportThreadStatus.CLOSED) {
        throw InvalidInputException("closed thread cannot be changed")
    }
}

private fun requireVenueCanActOnThread(thread: SupportThreadRecord) {
    if (thread.assigneeScope == SupportAssigneeScope.PLATFORM) {
        throw ForbiddenException()
    }
}

private suspend fun requireSupportManage(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(VenuePermission.SUPPORT_MANAGE)) {
        throw ForbiddenException()
    }
}

fun SupportThreadRecord.toDto(unreadCountOverride: Int? = null): SupportThreadDto =
    SupportThreadDto(
        threadId = id,
        venueId = venueId,
        venueName = venueName,
        guestDisplayName = guestDisplayName,
        threadType = threadType.name,
        assigneeScope = assigneeScope.name,
        category = normalizedCategory().name,
        contextLabel = formatSupportThreadLabel(),
        status = status.name,
        statusLabel = status.toHumanLabel(),
        bookingId = bookingId,
        orderId = orderId,
        orderDisplayLabel = orderDisplayLabel,
        tableId = tableId,
        tableSessionId = tableSessionId,
        tableLabel = tableLabel,
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

fun buildSupportReplyMessageForGuest(
    thread: SupportThreadRecord,
    messageText: String,
    authorLabel: String,
): String {
    val venueSuffix = thread.venueName?.let { " в «$it»" }.orEmpty()
    return "Сообщение от $authorLabel по обращению «${thread.formatSupportThreadLabel()}»$venueSuffix:\n\n$messageText"
}

fun buildVenueSupportMessageForGuest(
    thread: SupportThreadRecord,
    messageText: String,
): String = buildSupportReplyMessageForGuest(thread, messageText, "заведения")

private suspend fun notifyStaffChatAboutGuestCreatedTicket(
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    thread: SupportThreadRecord,
    text: String,
): Boolean {
    if (thread.assigneeScope != SupportAssigneeScope.VENUE) return false
    val venueId = thread.venueId ?: return false
    val venue = venueRepository.findVenueById(venueId)
    val staffChatId = venue?.staffChatId ?: return false
    val venueName = venue.name.takeIf { it.isNotBlank() } ?: thread.venueName ?: "Заведение"
    outboxEnqueuer.enqueueSendMessage(
        chatId = staffChatId,
        text =
            buildString {
                append("💬 Новое обращение гостя #").append(thread.id)
                append('\n').append("Заведение: ").append(venueName)
                thread.tableLabel?.let { append('\n').append("Стол: ").append(it) }
                thread.orderDisplayLabel?.let { append('\n').append("Заказ: ").append(it) }
                append('\n').append("Категория: ").append(thread.normalizedCategory().name)
                append('\n').append("Откройте раздел «Обращения» в Mini App.")
                append('\n').append("Текст: ").append(text)
            },
    )
    return true
}

private suspend fun notifyStaffChatAboutGuestSupportMessage(
    venueRepository: VenueRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    thread: SupportThreadRecord,
    text: String,
): Boolean {
    if (thread.assigneeScope != SupportAssigneeScope.VENUE) return false
    val venueId = thread.venueId ?: return false
    val venue = venueRepository.findVenueById(venueId)
    val staffChatId = venue?.staffChatId ?: return false
    val venueName = venue.name.takeIf { it.isNotBlank() } ?: thread.venueName ?: "Заведение"
    outboxEnqueuer.enqueueSendMessage(
        chatId = staffChatId,
        text =
            buildString {
                append("💬 Ответ гостя по ").append(thread.formatSupportThreadLabel())
                append('\n').append("Заведение: ").append(venueName)
                append('\n').append("Откройте раздел «Обращения» в Mini App.")
                append('\n').append("Текст: ").append(text)
            },
    )
    return true
}

private suspend fun changeThreadStatus(
    supportThreadRepository: SupportThreadRepository,
    auditLogRepository: AuditLogRepository?,
    actorUserId: Long,
    thread: SupportThreadRecord,
    newStatus: SupportThreadStatus,
    source: String,
) {
    if (thread.status == newStatus) return
    supportThreadRepository.updateThreadStatus(thread.id, newStatus)
    appendSupportAuditBestEffort(
        auditLogRepository = auditLogRepository,
        actorUserId = actorUserId,
        action = SUPPORT_TICKET_STATUS_CHANGED,
        thread = thread,
        source = source,
        oldStatus = thread.status,
        newStatus = newStatus,
    )
}

private suspend fun changeThreadScope(
    supportThreadRepository: SupportThreadRepository,
    auditLogRepository: AuditLogRepository?,
    actorUserId: Long,
    thread: SupportThreadRecord,
    newScope: SupportAssigneeScope,
    source: String,
    action: String,
) {
    if (thread.assigneeScope == newScope) return
    supportThreadRepository.updateThreadAssigneeScope(thread.id, newScope)
    appendSupportAuditBestEffort(
        auditLogRepository = auditLogRepository,
        actorUserId = actorUserId,
        action = SUPPORT_TICKET_SCOPE_CHANGED,
        thread = thread,
        source = source,
        oldScope = thread.assigneeScope,
        newScope = newScope,
    )
    appendSupportAuditBestEffort(
        auditLogRepository = auditLogRepository,
        actorUserId = actorUserId,
        action = action,
        thread = thread,
        source = source,
        oldScope = thread.assigneeScope,
        newScope = newScope,
    )
}

private suspend fun appendSupportAuditBestEffort(
    auditLogRepository: AuditLogRepository?,
    actorUserId: Long,
    action: String,
    thread: SupportThreadRecord,
    source: String,
    oldStatus: SupportThreadStatus? = null,
    newStatus: SupportThreadStatus? = null,
    oldScope: SupportAssigneeScope? = null,
    newScope: SupportAssigneeScope? = null,
) {
    if (auditLogRepository == null) return
    runCatching {
        auditLogRepository.appendJson(
            actorUserId = actorUserId,
            action = action,
            entityType = "support_ticket",
            entityId = thread.id,
            payload =
                buildJsonObject {
                    put("actorUserId", actorUserId)
                    put("ticketId", thread.id)
                    thread.venueId?.let { put("venueId", it) }
                    oldStatus?.let { put("oldStatus", it.name) }
                    newStatus?.let { put("newStatus", it.name) }
                    oldScope?.let { put("oldScope", it.name) }
                    newScope?.let { put("newScope", it.name) }
                    put("source", source)
                },
        )
    }
}

private fun SupportThreadRecord.formatSupportThreadLabel(): String =
    when (normalizedCategory()) {
        SupportThreadCategory.BOOKING ->
            booking?.displayNumber?.let { "Бронь №$it" }
                ?: bookingId?.let { "Бронь #$it" }
                ?: title
        SupportThreadCategory.ORDER_SERVICE ->
            orderDisplayLabel ?: orderId?.let { "Заказ #$it" } ?: tableLabel ?: title
        SupportThreadCategory.MINIAPP_TECHNICAL -> "Техническая проблема"
        SupportThreadCategory.BILLING -> "Биллинг"
        SupportThreadCategory.OTHER -> title
        SupportThreadCategory.GENERAL,
        SupportThreadCategory.ORDER,
        SupportThreadCategory.TABLE,
        SupportThreadCategory.PLATFORM,
        -> title
    }

private fun SupportThreadRecord.normalizedCategory(): SupportThreadCategory =
    when (category) {
        SupportThreadCategory.GENERAL -> SupportThreadCategory.OTHER
        SupportThreadCategory.ORDER,
        SupportThreadCategory.TABLE,
        -> SupportThreadCategory.ORDER_SERVICE
        SupportThreadCategory.PLATFORM -> SupportThreadCategory.MINIAPP_TECHNICAL
        else -> category
    }

private fun SupportThreadStatus.toHumanLabel(): String =
    when (this) {
        SupportThreadStatus.NEW -> "Новый"
        SupportThreadStatus.OPEN,
        SupportThreadStatus.IN_PROGRESS,
        -> "В работе"
        SupportThreadStatus.WAITING_USER -> "Ждём ответа"
        SupportThreadStatus.RESOLVED -> "Решено"
        SupportThreadStatus.CLOSED -> "Закрыто"
    }

private fun parseSupportInboxFilter(value: String?): SupportInboxFilter? =
    when (value?.trim()?.lowercase()) {
        null, "", "all" -> null
        "active" -> SupportInboxFilter.ACTIVE
        "resolved", "finished", "closed" -> SupportInboxFilter.RESOLVED
        else -> throw InvalidInputException("filter must be active or resolved")
    }

private fun normalizeSupportCategory(
    value: String?,
    default: SupportThreadCategory,
): SupportThreadCategory {
    val raw = value?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return default
    val normalized =
        when (raw) {
            "ORDER", "TABLE" -> "ORDER_SERVICE"
            "GENERAL" -> "OTHER"
            "PLATFORM" -> "MINIAPP_TECHNICAL"
            else -> raw
        }
    return runCatching { SupportThreadCategory.valueOf(normalized) }.getOrNull()
        ?: throw InvalidInputException("unsupported support category")
}

private fun defaultAssigneeScope(
    category: SupportThreadCategory,
    venueId: Long?,
): SupportAssigneeScope =
    if (
        venueId == null ||
        category == SupportThreadCategory.MINIAPP_TECHNICAL ||
        category == SupportThreadCategory.BILLING
    ) {
        SupportAssigneeScope.PLATFORM
    } else {
        SupportAssigneeScope.VENUE
    }

private fun normalizeSupportTitle(
    value: String?,
    category: SupportThreadCategory,
    context: VerifiedTicketContext,
): String {
    val explicit = value?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_SUPPORT_TITLE_LENGTH)
    if (explicit != null) return explicit
    return when (category) {
        SupportThreadCategory.BOOKING ->
            context.bookingDisplayNumber?.let { "Бронь №$it" }
                ?: context.bookingId?.let { "Бронь #$it" }
                ?: "Бронь"
        SupportThreadCategory.ORDER_SERVICE ->
            context.orderDisplayLabel ?: context.tableSessionId?.let { "Обслуживание за столом" } ?: "Обслуживание"
        SupportThreadCategory.MINIAPP_TECHNICAL -> "Техническая проблема"
        SupportThreadCategory.BILLING -> "Биллинг"
        SupportThreadCategory.OTHER -> "Обращение"
        SupportThreadCategory.GENERAL,
        SupportThreadCategory.ORDER,
        SupportThreadCategory.TABLE,
        SupportThreadCategory.PLATFORM,
        -> "Обращение"
    }
}

private fun normalizeOptionalMetadata(
    value: String?,
    maxLength: Int,
): String? = value?.trim()?.takeIf { it.isNotBlank() }?.take(maxLength)

private fun parseOptionalAssigneeScope(value: String?): SupportAssigneeScope? =
    value?.trim()?.takeIf { it.isNotBlank() }?.let { parseRequiredAssigneeScope(it) }

private fun parseRequiredAssigneeScope(value: String?): SupportAssigneeScope =
    value?.trim()?.uppercase()?.let { raw ->
        runCatching { SupportAssigneeScope.valueOf(raw) }.getOrNull()
    } ?: throw InvalidInputException("assigneeScope must be VENUE or PLATFORM")

private fun parseWritableStatus(value: String?): SupportThreadStatus {
    val status =
        value?.trim()?.uppercase()?.let { raw ->
            runCatching { SupportThreadStatus.valueOf(raw) }.getOrNull()
        } ?: throw InvalidInputException("status is required")
    if (status == SupportThreadStatus.OPEN) {
        throw InvalidInputException("OPEN is a legacy status alias and cannot be written")
    }
    return status
}

private fun io.ktor.server.application.ApplicationCall.parseThreadId(): Long =
    parameters["threadId"]?.toLongOrNull()
        ?: throw InvalidInputException("threadId must be a number")

private const val MAX_SUPPORT_MESSAGE_LENGTH = 1000
private const val MAX_SUPPORT_TITLE_LENGTH = 120
private const val SUPPORT_TICKET_STATUS_CHANGED = "SUPPORT_TICKET_STATUS_CHANGED"
private const val SUPPORT_TICKET_SCOPE_CHANGED = "SUPPORT_TICKET_SCOPE_CHANGED"
private const val SUPPORT_TICKET_ESCALATED = "SUPPORT_TICKET_ESCALATED"
private const val SUPPORT_TICKET_ASSIGNED = "SUPPORT_TICKET_ASSIGNED"
