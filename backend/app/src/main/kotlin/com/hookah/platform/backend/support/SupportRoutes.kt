package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.TooManyRequestsException
import com.hookah.platform.backend.booking.formatBookingDisplayLabel
import com.hookah.platform.backend.booking.resolveBookingDisplayZoneId
import com.hookah.platform.backend.miniapp.guest.GuestRateLimitConfig
import com.hookah.platform.backend.miniapp.guest.GuestRateLimitKey
import com.hookah.platform.backend.miniapp.guest.GuestRateLimitPolicy
import com.hookah.platform.backend.miniapp.guest.PLATFORM_GUEST_RECONFIRM_MESSAGE
import com.hookah.platform.backend.miniapp.guest.RateLimiter
import com.hookah.platform.backend.miniapp.guest.TableSessionConfig
import com.hookah.platform.backend.miniapp.guest.db.ConfirmedPlatformGuestMutationContext
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.requireConfirmedPlatformGuestMutation
import com.hookah.platform.backend.miniapp.guest.validateTableToken
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.platform.PlatformConfig
import com.hookah.platform.backend.platform.requirePlatformOwner
import com.hookah.platform.backend.telegram.BookingMessageStaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.TelegramKeyboards
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.UUID

@Serializable
data class SupportBookingContextDto(
    val bookingId: Long,
    val displayNumber: Int? = null,
    val displayLabel: String,
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
data class VenueConversationUnreadCountResponse(
    val unreadCount: Int,
)

@Serializable
enum class BookingThreadLookupStatus {
    WITH_THREAD,
    NO_THREAD,
}

@Serializable
data class BookingThreadLookupItemDto(
    val bookingId: Long,
    val status: BookingThreadLookupStatus,
    val thread: SupportThreadDto? = null,
)

@Serializable
data class BookingThreadLookupResponse(
    val items: List<BookingThreadLookupItemDto>,
)

@Serializable
data class SupportThreadDetailResponse(
    val thread: SupportThreadDto,
    val messages: List<SupportMessageDto>,
)

@Serializable
data class SupportMessageCreateRequest(
    val message: String? = null,
    val clientMessageId: String? = null,
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
data class VenueChatCreateRequest(
    val venueId: Long? = null,
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
    bookingMessageStaffChatNotifier: BookingMessageStaffChatNotifier,
    tableTokenResolver: suspend (String) -> TableContext?,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestBookingRepository: GuestBookingRepository,
    auditLogRepository: AuditLogRepository? = null,
    guestRateLimitConfig: GuestRateLimitConfig? = null,
    rateLimiter: RateLimiter? = null,
    platformOwnerUserId: Long? = null,
    guestTableContextLifecycleRepository: GuestTableContextLifecycleRepository? = null,
) {
    route("/support/booking-threads") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val bookingIds = call.parseBookingThreadLookupIds()
            val lookup =
                supportThreadRepository.lookupGuestBookingThreads(
                    userId = userId,
                    bookingIds = bookingIds,
                ) ?: throw NotFoundException()
            call.respond(lookup.toLookupResponse())
        }

        post("{bookingId}") {
            val userId = call.requireUserId()
            val bookingId =
                call.parameters["bookingId"]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidInputException("bookingId must be a positive number")
            val booking =
                guestBookingRepository.findActiveByGuest(bookingId = bookingId, userId = userId)
                    ?: throw NotFoundException()
            val title = booking.displayNumber?.let { "Бронь №$it" } ?: "Бронь #${booking.id}"
            val thread =
                supportThreadRepository.createOrFindBookingThread(
                    bookingId = booking.id,
                    title = title,
                ) ?: throw NotFoundException()
            val detail =
                supportThreadRepository.getGuestThreadAndMarkRead(
                    userId = userId,
                    threadId = thread.id,
                    surface = GuestThreadSurface.CONVERSATIONS,
                )
                    ?: throw NotFoundException()
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }
    }

    route("/support/venue-chats") {
        post {
            val userId = call.requireUserId()
            val request = call.receive<VenueChatCreateRequest>()
            val venueId =
                request.venueId?.takeIf { it > 0 }
                    ?: throw InvalidInputException("venueId is required")
            val venue = venueRepository.findCatalogVenueByIdForGuest(venueId) ?: throw NotFoundException()
            val existing =
                supportThreadRepository.findVenueChat(
                    venueId = venue.id,
                    guestUserId = userId,
                )
            if (existing != null) {
                val detail =
                    supportThreadRepository.getGuestThreadAndMarkRead(
                        userId = userId,
                        threadId = existing.thread.id,
                        surface = GuestThreadSurface.CONVERSATIONS,
                    )
                        ?: throw NotFoundException()
                call.respond(detail.toResponse(unreadCountOverride = 0))
                return@post
            }
            enforceGuestSupportRateLimit(
                guestRateLimitConfig = guestRateLimitConfig,
                rateLimiter = rateLimiter,
                userId = userId,
                venueId = venue.id,
                tableSessionId = null,
                endpoint = "venue-chat-create",
                selector = { venueChat },
            )
            val detail =
                supportThreadRepository.createOrFindVenueChat(
                    venueId = venue.id,
                    guestUserId = userId,
                    title = "Чат с ${venue.name}",
                )
            val opened =
                supportThreadRepository.getGuestThreadAndMarkRead(
                    userId = userId,
                    threadId = detail.thread.id,
                    surface = GuestThreadSurface.CONVERSATIONS,
                )
                    ?: throw NotFoundException()
            call.respond(opened.toResponse(unreadCountOverride = 0))
        }
    }

    route("/support/threads") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val filter = parseSupportInboxFilter(call.request.queryParameters["filter"])
            val surface = parseGuestThreadSurface(call.request.queryParameters["surface"])
            val threads =
                supportThreadRepository.listGuestThreads(
                    userId = userId,
                    filter = filter,
                    threadTypes = surface.expectedThreadTypes,
                )
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        post {
            val userId = call.requireUserId()
            val request = call.receive<SupportThreadCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val category = normalizeSupportCategory(request.category, default = SupportThreadCategory.OTHER)
            val detail =
                if (userId == platformOwnerUserId && !request.tableToken.isNullOrBlank()) {
                    val tableToken = validateTableToken(request.tableToken)
                    val referenceContext =
                        runCatching {
                            verifyPlatformGuestTicketReferences(
                                request = request,
                                userId = userId,
                                guestBookingRepository = guestBookingRepository,
                                venueRepository = venueRepository,
                            )
                        }
                    createConfirmedPlatformGuestTicket(
                        userId = userId,
                        platformOwnerUserId = platformOwnerUserId,
                        lifecycleRepository = guestTableContextLifecycleRepository,
                        supportThreadRepository = supportThreadRepository,
                        tableSessionConfig = tableSessionConfig,
                        request = request,
                        category = category,
                        messageText = messageText,
                        referenceContext = { referenceContext.getOrThrow() },
                        tableToken = tableToken,
                        expectedVenueId = null,
                        expectedTableId = null,
                        expectedTableSessionId = request.tableSessionId,
                        guestRateLimitConfig = guestRateLimitConfig,
                        rateLimiter = rateLimiter,
                    )
                } else {
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
                            venueRepository = venueRepository,
                        )
                    if (
                        userId == platformOwnerUserId &&
                        (verified.tableId != null || verified.tableSessionId != null)
                    ) {
                        createConfirmedPlatformGuestTicket(
                            userId = userId,
                            platformOwnerUserId = platformOwnerUserId,
                            lifecycleRepository = guestTableContextLifecycleRepository,
                            supportThreadRepository = supportThreadRepository,
                            tableSessionConfig = tableSessionConfig,
                            request = request,
                            category = category,
                            messageText = messageText,
                            referenceContext = { verified },
                            tableToken = null,
                            expectedVenueId = verified.venueId,
                            expectedTableId = verified.tableId,
                            expectedTableSessionId = verified.tableSessionId,
                            guestRateLimitConfig = guestRateLimitConfig,
                            rateLimiter = rateLimiter,
                        )
                    } else {
                        enforceGuestSupportRateLimit(
                            guestRateLimitConfig = guestRateLimitConfig,
                            rateLimiter = rateLimiter,
                            userId = userId,
                            venueId = verified.venueId,
                            tableSessionId = verified.tableSessionId,
                            endpoint = "support-ticket-create",
                            selector = { supportTicket },
                        )
                        supportThreadRepository.createTicket(
                            buildGuestSupportTicketInput(
                                request = request,
                                userId = userId,
                                category = category,
                                messageText = messageText,
                                verified = verified,
                            ),
                        )
                    }
                }
            call.respond(
                SupportThreadCreateResponse(
                    thread = detail.thread.toDto(unreadCountOverride = 0),
                    message = detail.messages.last().toDto(),
                    queued = false,
                ),
            )
        }

        get("{threadId}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val surface = parseGuestThreadSurface(call.request.queryParameters["surface"])
            val detail =
                getGuestThreadAndMarkRead(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    tableSessionConfig = tableSessionConfig,
                    supportThreadRepository = supportThreadRepository,
                    threadId = threadId,
                    surface = surface,
                )
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/resolve") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            val updated =
                changeGuestThreadStatus(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    tableSessionConfig = tableSessionConfig,
                    supportThreadRepository = supportThreadRepository,
                    auditLogRepository = auditLogRepository,
                    detail = detail,
                    newStatus = SupportThreadStatus.RESOLVED,
                )
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/reopen") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            val updated =
                changeGuestThreadStatus(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    tableSessionConfig = tableSessionConfig,
                    supportThreadRepository = supportThreadRepository,
                    auditLogRepository = auditLogRepository,
                    detail = detail,
                    newStatus = SupportThreadStatus.IN_PROGRESS,
                )
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requireUserId()
            val threadId = call.parseThreadId()
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getGuestThread(userId, threadId) ?: throw NotFoundException()
            val result =
                addGuestThreadMessage(
                    userId = userId,
                    platformOwnerUserId = platformOwnerUserId,
                    lifecycleRepository = guestTableContextLifecycleRepository,
                    tableSessionConfig = tableSessionConfig,
                    supportThreadRepository = supportThreadRepository,
                    outboxEnqueuer = outboxEnqueuer,
                    bookingMessageStaffChatNotifier = bookingMessageStaffChatNotifier,
                    auditLogRepository = auditLogRepository,
                    guestRateLimitConfig = guestRateLimitConfig,
                    rateLimiter = rateLimiter,
                    detail = detail,
                    messageText = messageText,
                    clientMessageId = request.clientMessageId,
                )
            call.respond(
                SupportMessageCreateResponse(
                    thread = result.thread.toDto(unreadCountOverride = 0),
                    message = result.message.toDto(),
                    queued = result.queued,
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
    get("/venue/{venueId}/support/unread-count") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val userId = call.requireUserId()
        val venueId = call.requireVenueId()
        requireSupportManage(venueAccessRepository, userId, venueId)
        call.respond(
            VenueConversationUnreadCountResponse(
                unreadCount =
                    supportThreadRepository.countVenueConversationUnread(
                        venueId = venueId,
                        viewerUserId = userId,
                    ),
            ),
        )
    }

    route("/venue/{venueId}/support/booking-threads") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val bookingIds = call.parseBookingThreadLookupIds()
            val lookup =
                supportThreadRepository.lookupVenueBookingThreads(
                    venueId = venueId,
                    viewerUserId = userId,
                    bookingIds = bookingIds,
                ) ?: throw NotFoundException()
            call.respond(lookup.toLookupResponse())
        }
    }

    route("/venue/{venueId}/support/threads") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val bookingId = call.request.queryParameters["bookingId"]?.toLongOrNull()
            val filter = parseSupportInboxFilter(call.request.queryParameters["filter"])
            val threadTypes =
                parseOptionalThreadTypes(
                    threadType = call.request.queryParameters["threadType"],
                    threadTypes = call.request.queryParameters["threadTypes"],
                )
            val threads =
                supportThreadRepository.listVenueThreads(
                    venueId = venueId,
                    viewerUserId = userId,
                    bookingId = bookingId,
                    filter = filter,
                    threadTypes = threadTypes,
                )
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        get("{threadId}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val threadTypes =
                parseOptionalThreadTypes(
                    threadType = call.request.queryParameters["threadType"],
                    threadTypes = call.request.queryParameters["threadTypes"],
                )
            val detail =
                supportThreadRepository.getVenueThreadAndMarkRead(
                    venueId = venueId,
                    threadId = threadId,
                    viewerUserId = userId,
                    allowedThreadTypes = threadTypes,
                ) ?: throw NotFoundException()
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
            val updated =
                supportThreadRepository.getVenueThreadAndMarkRead(
                    venueId = venueId,
                    threadId = detail.thread.id,
                    viewerUserId = userId,
                ) ?: throw NotFoundException()
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
            val updated =
                supportThreadRepository.getVenueThreadAndMarkRead(
                    venueId = venueId,
                    threadId = detail.thread.id,
                    viewerUserId = userId,
                ) ?: throw NotFoundException()
            call.respond(updated.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/escalate") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireSupportManage(venueAccessRepository, userId, venueId)
            val threadId = call.parseThreadId()
            val detail = supportThreadRepository.getVenueThread(venueId, threadId) ?: throw NotFoundException()
            if (detail.thread.threadType != SupportThreadType.SUPPORT_TICKET) {
                throw NotFoundException()
            }
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
            val updated =
                supportThreadRepository.getVenueThreadAndMarkRead(
                    venueId = venueId,
                    threadId = detail.thread.id,
                    viewerUserId = userId,
                    allowedThreadTypes = setOf(SupportThreadType.SUPPORT_TICKET),
                ) ?: throw NotFoundException()
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
            if (detail.thread.threadType != SupportThreadType.BOOKING_THREAD) {
                requireThreadMessageAllowed(detail.thread)
            }
            val bookingWrite =
                if (detail.thread.threadType == SupportThreadType.BOOKING_THREAD) {
                    val clientMessageId = normalizeBookingClientMessageId(request.clientMessageId)
                    supportThreadRepository.addBookingMessage(
                        bookingId = detail.thread.bookingId ?: throw NotFoundException(),
                        authorUserId = userId,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = messageText,
                        expectedThreadId = detail.thread.id,
                        expectedVenueId = venueId,
                        clientMessageId = clientMessageId,
                        notificationWriter = { connection, notification ->
                            check(notification.kind == BookingMessageNotificationKind.GUEST_NOTIFICATION)
                            outboxEnqueuer.enqueueBookingSendMessageInTransaction(
                                connection = connection,
                                chatId = notification.recipientChatId,
                                text =
                                    buildSupportReplyMessageForGuest(
                                        notification.thread,
                                        notification.message.text,
                                        "заведения",
                                    ),
                                replyMarkup =
                                    notification.thread.bookingId?.let { bookingId ->
                                        notification.thread.venueId?.let { venue ->
                                            TelegramKeyboards.inlineGuestBookingReplyActions(venue, bookingId)
                                        }
                                    },
                                dedupeKey = notification.dedupeKey,
                            )
                        },
                    ) ?: throw NotFoundException()
                } else {
                    null
                }
            val message =
                bookingWrite?.message
                    ?: supportThreadRepository.addMessage(
                        threadId = detail.thread.id,
                        authorUserId = userId,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = messageText,
                    )
            val refreshedThread =
                supportThreadRepository.getVenueThread(venueId, detail.thread.id)?.thread
                    ?: bookingWrite?.thread
                    ?: detail.thread
            if (bookingWrite?.created != false) {
                appendSupportReplyAuditBestEffort(
                    auditLogRepository = auditLogRepository,
                    actorUserId = userId,
                    oldThread = detail.thread,
                    refreshedThread = refreshedThread,
                    source = "VENUE_MINIAPP",
                )
            }
            if (bookingWrite == null) {
                outboxEnqueuer.enqueueSendMessage(
                    chatId = refreshedThread.guestUserId,
                    text = buildSupportReplyMessageForGuest(refreshedThread, messageText, "заведения"),
                    replyMarkup =
                        refreshedThread.bookingId?.let { bookingId ->
                            refreshedThread.venueId?.let { venue ->
                                TelegramKeyboards.inlineGuestBookingReplyActions(venue, bookingId)
                            }
                        },
                )
            }
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
            val requestedThreadTypes =
                parseOptionalThreadTypes(
                    threadType = call.request.queryParameters["threadType"],
                    threadTypes = call.request.queryParameters["threadTypes"],
                )
            if (requestedThreadTypes != null && requestedThreadTypes.any { it != SupportThreadType.SUPPORT_TICKET }) {
                call.respond(SupportThreadListResponse(items = emptyList()))
                return@get
            }
            val threadTypes =
                requestedThreadTypes
                    ?: setOf(SupportThreadType.SUPPORT_TICKET)
            val threads =
                supportThreadRepository.listPlatformThreads(
                    viewerUserId = userId,
                    filter = filter,
                    assigneeScope = assigneeScope,
                    venueId = venueId,
                    threadTypes = threadTypes,
                )
            call.respond(SupportThreadListResponse(items = threads.map { it.toDto() }))
        }

        get("{threadId}") {
            val userId = call.requirePlatformOwner(platformConfig)
            val threadId = call.parseThreadId()
            val detail =
                supportThreadRepository.getPlatformThreadAndMarkRead(
                    threadId = threadId,
                    viewerUserId = userId,
                    platformOwnerUserId = platformConfig.ownerUserId,
                ) ?: throw NotFoundException()
            call.respond(detail.toResponse(unreadCountOverride = 0))
        }

        post("{threadId}/messages") {
            val userId = call.requirePlatformOwner(platformConfig)
            val threadId = call.parseThreadId()
            val request = call.receive<SupportMessageCreateRequest>()
            val messageText = normalizeSupportMessage(request.message)
            val detail = supportThreadRepository.getPlatformThread(threadId) ?: throw NotFoundException()
            if (detail.thread.threadType != SupportThreadType.SUPPORT_TICKET) {
                throw NotFoundException()
            }
            requireThreadMessageAllowed(detail.thread)
            val message =
                supportThreadRepository.addMessage(
                    threadId = detail.thread.id,
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.PLATFORM,
                    source = SupportMessageSource.PLATFORM_MINIAPP,
                    text = messageText,
                )
            val refreshedThread =
                supportThreadRepository.getPlatformThread(detail.thread.id)?.thread ?: detail.thread
            appendSupportReplyAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                oldThread = detail.thread,
                refreshedThread = refreshedThread,
                source = "PLATFORM_MINIAPP",
            )
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
            if (detail.thread.threadType != SupportThreadType.SUPPORT_TICKET) {
                throw NotFoundException()
            }
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
            if (detail.thread.threadType != SupportThreadType.SUPPORT_TICKET) {
                throw NotFoundException()
            }
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

private suspend fun verifyPlatformGuestTicketReferences(
    request: SupportThreadCreateRequest,
    userId: Long,
    guestBookingRepository: GuestBookingRepository,
    venueRepository: VenueRepository,
): VerifiedTicketContext {
    var venueId: Long? = null
    if (request.venueId != null) {
        val requestedVenueId =
            request.venueId.takeIf { it > 0 }
                ?: throw InvalidInputException("venueId must be a positive number")
        venueId = venueRepository.findCatalogVenueByIdForGuest(requestedVenueId)?.id ?: throw NotFoundException()
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
    }
    return VerifiedTicketContext(
        venueId = venueId,
        bookingId = bookingId,
        bookingDisplayNumber = bookingDisplayNumber,
    )
}

private suspend fun createConfirmedPlatformGuestTicket(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    supportThreadRepository: SupportThreadRepository,
    tableSessionConfig: TableSessionConfig,
    request: SupportThreadCreateRequest,
    category: SupportThreadCategory,
    messageText: String,
    referenceContext: () -> VerifiedTicketContext,
    tableToken: String?,
    expectedVenueId: Long?,
    expectedTableId: Long?,
    expectedTableSessionId: Long?,
    guestRateLimitConfig: GuestRateLimitConfig?,
    rateLimiter: RateLimiter?,
): SupportThreadDetailRecord =
    requireConfirmedPlatformGuestMutation(
        userId = userId,
        platformOwnerUserId = platformOwnerUserId,
        lifecycleRepository = lifecycleRepository,
        tableToken = tableToken,
        expectedVenueId = expectedVenueId,
        expectedTableId = expectedTableId,
        expectedTableSessionId = expectedTableSessionId,
        ttl = tableSessionConfig.ttl,
    ) { connection, confirmed ->
        val verified =
            verifyConfirmedPlatformGuestTicketContext(
                connection = connection,
                request = request,
                userId = userId,
                supportThreadRepository = supportThreadRepository,
                reference = referenceContext(),
                confirmed = confirmed,
            )
        enforceGuestSupportRateLimit(
            guestRateLimitConfig = guestRateLimitConfig,
            rateLimiter = rateLimiter,
            userId = userId,
            venueId = verified.venueId,
            tableSessionId = verified.tableSessionId,
            endpoint = "support-ticket-create",
            selector = { supportTicket },
        )
        supportThreadRepository.createTicket(
            connection = connection,
            input =
                buildGuestSupportTicketInput(
                    request = request,
                    userId = userId,
                    category = category,
                    messageText = messageText,
                    verified = verified,
                ),
        )
    }

private fun verifyConfirmedPlatformGuestTicketContext(
    connection: Connection,
    request: SupportThreadCreateRequest,
    userId: Long,
    supportThreadRepository: SupportThreadRepository,
    reference: VerifiedTicketContext,
    confirmed: ConfirmedPlatformGuestMutationContext,
): VerifiedTicketContext {
    val venueId = confirmed.context.venueId
    val tableId = confirmed.context.tableId
    val tableSessionId = confirmed.tableSession.id
    if (
        (reference.venueId != null && reference.venueId != venueId) ||
        (reference.tableId != null && reference.tableId != tableId) ||
        (reference.tableSessionId != null && reference.tableSessionId != tableSessionId)
    ) {
        throw InvalidInputException("verified support context does not match confirmed table context")
    }
    val order =
        request.orderId?.let { orderId ->
            supportThreadRepository.findOrderContextForGuest(
                connection = connection,
                orderId = orderId,
                userId = userId,
                venueId = venueId,
                tableSessionId = tableSessionId,
            ) ?: throw NotFoundException()
        }
    if (order != null && order.tableId != tableId) {
        throw InvalidInputException("order does not match confirmed table context")
    }
    return VerifiedTicketContext(
        venueId = venueId,
        tableId = tableId,
        tableSessionId = tableSessionId,
        orderId = order?.orderId,
        orderDisplayLabel = order?.displayLabel,
        bookingId = reference.bookingId,
        bookingDisplayNumber = reference.bookingDisplayNumber,
    )
}

private fun buildGuestSupportTicketInput(
    request: SupportThreadCreateRequest,
    userId: Long,
    category: SupportThreadCategory,
    messageText: String,
    verified: VerifiedTicketContext,
): SupportTicketCreateInput =
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
        assigneeScope = defaultAssigneeScope(category, verified.venueId),
        createdSource = SupportThreadCreatedSource.GUEST_MINIAPP,
        messageSource = SupportMessageSource.GUEST_MINIAPP,
        appVersion = normalizeOptionalMetadata(request.appVersion, 80),
        correlationId = normalizeOptionalMetadata(request.correlationId, 120),
    )

private suspend fun getGuestThreadAndMarkRead(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    tableSessionConfig: TableSessionConfig,
    supportThreadRepository: SupportThreadRepository,
    threadId: Long,
    surface: GuestThreadSurface,
): SupportThreadDetailRecord {
    if (userId != platformOwnerUserId) {
        return supportThreadRepository.getGuestThreadAndMarkRead(userId, threadId, surface)
            ?: throw NotFoundException()
    }
    val openContext =
        supportThreadRepository.getGuestThreadOpenContext(userId, threadId)
            ?: throw NotFoundException()
    if (!requiresConfirmedPlatformGuestThreadMutation(userId, platformOwnerUserId, openContext)) {
        return supportThreadRepository.getGuestThreadAndMarkRead(userId, threadId, surface)
            ?: throw NotFoundException()
    }

    return requireConfirmedPlatformGuestMutation(
        userId = userId,
        platformOwnerUserId = platformOwnerUserId,
        lifecycleRepository = lifecycleRepository,
        expectedVenueId = openContext.venueId,
        expectedTableId = openContext.tableId,
        expectedTableSessionId = openContext.tableSessionId,
        ttl = tableSessionConfig.ttl,
        touchSessionBeforeMutation = false,
    ) { connection, confirmed ->
        supportThreadRepository.getGuestThreadAndMarkRead(
            connection,
            userId = userId,
            threadId = threadId,
            surface = surface,
            lockedThreadValidator = { locked ->
                requireConfirmedPlatformGuestThread(locked, openContext, confirmed)
            },
        ) ?: throw NotFoundException()
    }
}

private suspend fun changeGuestThreadStatus(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    tableSessionConfig: TableSessionConfig,
    supportThreadRepository: SupportThreadRepository,
    auditLogRepository: AuditLogRepository?,
    detail: SupportThreadDetailRecord,
    newStatus: SupportThreadStatus,
): SupportThreadDetailRecord {
    if (!requiresConfirmedPlatformGuestThreadMutation(userId, platformOwnerUserId, detail.thread)) {
        requireThreadStatusChangeAllowed(detail.thread)
        changeThreadStatus(
            supportThreadRepository = supportThreadRepository,
            auditLogRepository = auditLogRepository,
            actorUserId = userId,
            thread = detail.thread,
            newStatus = newStatus,
            source = "GUEST_MINIAPP",
        )
        return supportThreadRepository.getGuestThreadAndMarkRead(
            userId = userId,
            threadId = detail.thread.id,
            surface =
                if (detail.thread.threadType == SupportThreadType.SUPPORT_TICKET) {
                    GuestThreadSurface.SUPPORT
                } else {
                    GuestThreadSurface.CONVERSATIONS
                },
        )
            ?: throw NotFoundException()
    }

    val updated =
        requireConfirmedPlatformGuestMutation(
            userId = userId,
            platformOwnerUserId = platformOwnerUserId,
            lifecycleRepository = lifecycleRepository,
            expectedVenueId = detail.thread.venueId,
            expectedTableId = detail.thread.tableId,
            expectedTableSessionId = detail.thread.tableSessionId,
            ttl = tableSessionConfig.ttl,
        ) { connection, confirmed ->
            val locked =
                supportThreadRepository.lockGuestThread(connection, userId, detail.thread.id)
                    ?: throw NotFoundException()
            requireConfirmedPlatformGuestThread(locked.thread, detail.thread, confirmed)
            requireThreadStatusChangeAllowed(locked.thread)
            if (locked.thread.status != newStatus) {
                supportThreadRepository.updateThreadStatus(connection, locked.thread.id, newStatus)
            }
            markThreadReadAuthorized(
                supportThreadRepository,
                connection,
                locked.thread.id,
                SupportThreadReadAccess.Guest(userId),
            )
            supportThreadRepository.getGuestThread(connection, userId, locked.thread.id) ?: throw NotFoundException()
        }
    if (detail.thread.status != updated.thread.status) {
        appendSupportAuditBestEffort(
            auditLogRepository = auditLogRepository,
            actorUserId = userId,
            action = SUPPORT_TICKET_STATUS_CHANGED,
            thread = detail.thread,
            source = "GUEST_MINIAPP",
            oldStatus = detail.thread.status,
            newStatus = updated.thread.status,
        )
    }
    return updated
}

private data class GuestThreadMessageResult(
    val thread: SupportThreadRecord,
    val message: SupportMessageRecord,
    val queued: Boolean,
)

private suspend fun addGuestThreadMessage(
    userId: Long,
    platformOwnerUserId: Long?,
    lifecycleRepository: GuestTableContextLifecycleRepository?,
    tableSessionConfig: TableSessionConfig,
    supportThreadRepository: SupportThreadRepository,
    outboxEnqueuer: TelegramOutboxEnqueuer,
    bookingMessageStaffChatNotifier: BookingMessageStaffChatNotifier,
    auditLogRepository: AuditLogRepository?,
    guestRateLimitConfig: GuestRateLimitConfig?,
    rateLimiter: RateLimiter?,
    detail: SupportThreadDetailRecord,
    messageText: String,
    clientMessageId: String?,
): GuestThreadMessageResult {
    if (!requiresConfirmedPlatformGuestThreadMutation(userId, platformOwnerUserId, detail.thread)) {
        if (detail.thread.threadType != SupportThreadType.BOOKING_THREAD) {
            requireThreadMessageAllowed(detail.thread)
        }
        val bookingWrite =
            if (detail.thread.threadType == SupportThreadType.BOOKING_THREAD) {
                val clientMessageId = normalizeBookingClientMessageId(clientMessageId)
                supportThreadRepository.addBookingMessage(
                    bookingId = detail.thread.bookingId ?: throw NotFoundException(),
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = messageText,
                    expectedThreadId = detail.thread.id,
                    expectedGuestUserId = userId,
                    clientMessageId = clientMessageId,
                    beforeInsert = {
                        enforceGuestSupportRateLimit(
                            guestRateLimitConfig = guestRateLimitConfig,
                            rateLimiter = rateLimiter,
                            userId = userId,
                            venueId = detail.thread.venueId,
                            tableSessionId = detail.thread.tableSessionId,
                            endpoint = "support-message",
                            selector = { supportMessage },
                        )
                    },
                    notificationWriter = { connection, notification ->
                        check(notification.kind == BookingMessageNotificationKind.GUEST_ACK)
                        outboxEnqueuer.enqueueBookingSendMessageInTransaction(
                            connection = connection,
                            chatId = notification.recipientChatId,
                            text = "✅ Ответ отправлен заведению.",
                            dedupeKey = notification.dedupeKey,
                        )
                        bookingMessageStaffChatNotifier.enqueueGuestMessageAlertInTransaction(
                            connection = connection,
                            thread = notification.thread,
                            messageId = notification.message.id,
                        )
                    },
                ) ?: throw NotFoundException()
            } else {
                enforceGuestSupportRateLimit(
                    guestRateLimitConfig = guestRateLimitConfig,
                    rateLimiter = rateLimiter,
                    userId = userId,
                    venueId = detail.thread.venueId,
                    tableSessionId = detail.thread.tableSessionId,
                    endpoint = "support-message",
                    selector = { supportMessage },
                )
                null
            }
        val message =
            bookingWrite?.message
                ?: supportThreadRepository.addMessage(
                    threadId = detail.thread.id,
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = messageText,
                )
        val refreshedThread =
            supportThreadRepository.getGuestThread(userId, detail.thread.id)?.thread
                ?: bookingWrite?.thread
                ?: detail.thread
        if (bookingWrite?.created != false) {
            appendSupportReplyAuditBestEffort(
                auditLogRepository = auditLogRepository,
                actorUserId = userId,
                oldThread = detail.thread,
                refreshedThread = refreshedThread,
                source = "GUEST_MINIAPP",
            )
        }
        return GuestThreadMessageResult(
            thread = refreshedThread,
            message = message,
            queued = bookingWrite != null,
        )
    }

    val result =
        requireConfirmedPlatformGuestMutation(
            userId = userId,
            platformOwnerUserId = platformOwnerUserId,
            lifecycleRepository = lifecycleRepository,
            expectedVenueId = detail.thread.venueId,
            expectedTableId = detail.thread.tableId,
            expectedTableSessionId = detail.thread.tableSessionId,
            ttl = tableSessionConfig.ttl,
        ) { connection, confirmed ->
            val locked =
                supportThreadRepository.lockGuestThread(connection, userId, detail.thread.id)
                    ?: throw NotFoundException()
            requireConfirmedPlatformGuestThread(locked.thread, detail.thread, confirmed)
            requireThreadMessageAllowed(locked.thread)
            enforceGuestSupportRateLimit(
                guestRateLimitConfig = guestRateLimitConfig,
                rateLimiter = rateLimiter,
                userId = userId,
                venueId = locked.thread.venueId,
                tableSessionId = locked.thread.tableSessionId,
                endpoint = "support-message",
                selector = { supportMessage },
            )
            val message =
                supportThreadRepository.addMessage(
                    connection = connection,
                    threadId = locked.thread.id,
                    authorUserId = userId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = messageText,
                )
            val refreshedThread =
                supportThreadRepository.getGuestThread(connection, userId, locked.thread.id)?.thread
                    ?: throw NotFoundException()
            GuestThreadMessageResult(thread = refreshedThread, message = message, queued = false)
        }
    appendSupportReplyAuditBestEffort(
        auditLogRepository = auditLogRepository,
        actorUserId = userId,
        oldThread = detail.thread,
        refreshedThread = result.thread,
        source = "GUEST_MINIAPP",
    )
    return result
}

private fun requiresConfirmedPlatformGuestThreadMutation(
    userId: Long,
    platformOwnerUserId: Long?,
    thread: SupportThreadRecord,
): Boolean =
    userId == platformOwnerUserId &&
        thread.threadType == SupportThreadType.SUPPORT_TICKET &&
        (thread.tableId != null || thread.tableSessionId != null)

private fun requiresConfirmedPlatformGuestThreadMutation(
    userId: Long,
    platformOwnerUserId: Long?,
    thread: GuestThreadOpenContextRecord,
): Boolean =
    userId == platformOwnerUserId &&
        thread.threadType == SupportThreadType.SUPPORT_TICKET &&
        (thread.tableId != null || thread.tableSessionId != null)

private fun requireConfirmedPlatformGuestThread(
    locked: SupportThreadRecord,
    expected: SupportThreadRecord,
    confirmed: ConfirmedPlatformGuestMutationContext,
) {
    if (
        locked.threadType != SupportThreadType.SUPPORT_TICKET ||
        locked.venueId != expected.venueId ||
        locked.tableId != expected.tableId ||
        locked.tableSessionId != expected.tableSessionId ||
        locked.venueId != confirmed.context.venueId ||
        locked.tableId != confirmed.context.tableId ||
        (locked.tableSessionId != null && locked.tableSessionId != confirmed.tableSession.id)
    ) {
        throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
    }
}

private fun requireConfirmedPlatformGuestThread(
    locked: GuestThreadOpenContextRecord,
    expected: GuestThreadOpenContextRecord,
    confirmed: ConfirmedPlatformGuestMutationContext,
) {
    if (
        locked.threadType != SupportThreadType.SUPPORT_TICKET ||
        locked.threadId != expected.threadId ||
        locked.venueId != expected.venueId ||
        locked.tableId != expected.tableId ||
        locked.tableSessionId != expected.tableSessionId ||
        locked.venueId != confirmed.context.venueId ||
        locked.tableId != confirmed.context.tableId ||
        (locked.tableSessionId != null && locked.tableSessionId != confirmed.tableSession.id)
    ) {
        throw ForbiddenException(PLATFORM_GUEST_RECONFIRM_MESSAGE)
    }
}

private fun enforceGuestSupportRateLimit(
    guestRateLimitConfig: GuestRateLimitConfig?,
    rateLimiter: RateLimiter?,
    userId: Long,
    venueId: Long?,
    tableSessionId: Long?,
    endpoint: String,
    selector: GuestRateLimitConfig.() -> GuestRateLimitPolicy,
) {
    if (guestRateLimitConfig == null || rateLimiter == null) {
        return
    }
    val policy = guestRateLimitConfig.selector()
    val allowed =
        rateLimiter.tryAcquire(
            key =
                GuestRateLimitKey(
                    venueId = venueId ?: 0L,
                    userId = userId,
                    tableSessionId = tableSessionId ?: 0L,
                    endpoint = endpoint,
                ),
            limit = policy.maxRequests,
            window = policy.window,
        )
    if (!allowed) {
        throw TooManyRequestsException(message = "Too many requests. Please try again later.")
    }
}

private suspend fun verifyGuestTicketContext(
    request: SupportThreadCreateRequest,
    userId: Long,
    category: SupportThreadCategory,
    tableTokenResolver: suspend (String) -> TableContext?,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestBookingRepository: GuestBookingRepository,
    supportThreadRepository: SupportThreadRepository,
    venueRepository: VenueRepository,
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

    if (request.venueId != null) {
        val requestedVenueId =
            request.venueId.takeIf { it > 0 }
                ?: throw InvalidInputException("venueId must be a positive number")
        val venue = venueRepository.findCatalogVenueByIdForGuest(requestedVenueId) ?: throw NotFoundException()
        if (venueId != null && venueId != venue.id) {
            throw InvalidInputException("venue does not match verified context")
        }
        venueId = venue.id
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
    } else if (category == SupportThreadCategory.BOOKING && venueId == null) {
        throw InvalidInputException("Выберите бронь или заведение для обращения по брони.")
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

    if (category == SupportThreadCategory.ORDER_SERVICE && venueId == null) {
        throw InvalidInputException("Выберите заведение для обращения по заказу или обслуживанию.")
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

private fun markThreadReadAuthorized(
    supportThreadRepository: SupportThreadRepository,
    connection: Connection,
    threadId: Long,
    access: SupportThreadReadAccess,
) {
    requireThreadReadAuthorized(
        supportThreadRepository.markNonBookingThreadReadAfterThreadLock(connection, threadId, access),
    )
}

private fun requireThreadReadAuthorized(result: SupportThreadReadResult) {
    when (result) {
        SupportThreadReadResult.MARKED -> Unit
        SupportThreadReadResult.NOT_FOUND -> throw NotFoundException()
        SupportThreadReadResult.FORBIDDEN -> throw ForbiddenException()
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

private fun List<BookingThreadLookupRecord>.toLookupResponse(): BookingThreadLookupResponse =
    BookingThreadLookupResponse(
        items =
            map { lookup ->
                BookingThreadLookupItemDto(
                    bookingId = lookup.bookingId,
                    status =
                        if (lookup.thread == null) {
                            BookingThreadLookupStatus.NO_THREAD
                        } else {
                            BookingThreadLookupStatus.WITH_THREAD
                        },
                    thread = lookup.thread?.toDto(),
                )
            },
    )

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
                    displayLabel =
                        formatBookingDisplayLabel(
                            bookingId = it.bookingId,
                            displayNumber = it.displayNumber,
                            scheduledAt = it.scheduledAt,
                            venueZoneId = resolveBookingDisplayZoneId(venueTimezone),
                        ),
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

fun normalizeBookingClientMessageId(value: String?): String {
    val candidate = value ?: throw InvalidInputException("clientMessageId is required")
    if (candidate.length > MAX_BOOKING_CLIENT_MESSAGE_ID_LENGTH) {
        throw InvalidInputException("clientMessageId must be at most $MAX_BOOKING_CLIENT_MESSAGE_ID_LENGTH characters")
    }
    val parsed = runCatching { UUID.fromString(candidate) }.getOrNull()
    if (parsed == null || parsed.toString() != candidate) {
        throw InvalidInputException("clientMessageId must be a canonical UUID")
    }
    return candidate
}

fun buildSupportReplyMessageForGuest(
    thread: SupportThreadRecord,
    messageText: String,
    authorLabel: String,
): String {
    val venueSuffix = thread.venueName?.let { " в «$it»" }.orEmpty()
    val subject =
        when (thread.threadType) {
            SupportThreadType.BOOKING_THREAD -> "по брони"
            SupportThreadType.VENUE_CHAT -> "по чату"
            SupportThreadType.SUPPORT_TICKET -> "по обращению"
        }
    return "Сообщение от $authorLabel $subject «${thread.formatSupportThreadLabel()}»$venueSuffix:\n\n$messageText"
}

fun buildVenueSupportMessageForGuest(
    thread: SupportThreadRecord,
    messageText: String,
): String = buildSupportReplyMessageForGuest(thread, messageText, "заведения")

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

private suspend fun appendSupportReplyAuditBestEffort(
    auditLogRepository: AuditLogRepository?,
    actorUserId: Long,
    oldThread: SupportThreadRecord,
    refreshedThread: SupportThreadRecord,
    source: String,
) {
    if (oldThread.threadType != SupportThreadType.SUPPORT_TICKET) return
    appendSupportAuditBestEffort(
        auditLogRepository = auditLogRepository,
        actorUserId = actorUserId,
        action = SUPPORT_TICKET_MESSAGE_ADDED,
        thread = refreshedThread,
        source = source,
    )
    if (oldThread.status != refreshedThread.status) {
        appendSupportAuditBestEffort(
            auditLogRepository = auditLogRepository,
            actorUserId = actorUserId,
            action = SUPPORT_TICKET_STATUS_CHANGED,
            thread = refreshedThread,
            source = source,
            oldStatus = oldThread.status,
            newStatus = refreshedThread.status,
        )
    }
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
            booking?.let {
                formatBookingDisplayLabel(
                    bookingId = it.bookingId,
                    displayNumber = it.displayNumber,
                    scheduledAt = it.scheduledAt,
                    venueZoneId = resolveBookingDisplayZoneId(venueTimezone),
                )
            } ?: bookingId?.let { "Бронь #$it" }
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

private fun io.ktor.server.application.ApplicationCall.parseBookingThreadLookupIds(): List<Long> {
    val values = request.queryParameters.getAll("bookingIds").orEmpty()
    if (values.size != 1) {
        throw InvalidInputException("bookingIds must be provided once as a comma-separated list")
    }
    val tokens = values.single().split(',')
    if (tokens.isEmpty() || tokens.size > MAX_BOOKING_THREAD_LOOKUP_IDS) {
        throw InvalidInputException("bookingIds must contain between 1 and $MAX_BOOKING_THREAD_LOOKUP_IDS ids")
    }
    val bookingIds =
        tokens.map { token ->
            val normalized = token.trim()
            if (normalized.isEmpty() || normalized.any { !it.isDigit() }) {
                throw InvalidInputException("bookingIds must contain positive numbers")
            }
            normalized.toLongOrNull()?.takeIf { it > 0 }
                ?: throw InvalidInputException("bookingIds must contain positive numbers")
        }
    if (bookingIds.distinct().size != bookingIds.size) {
        throw InvalidInputException("bookingIds must be unique")
    }
    return bookingIds
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

private fun parseOptionalThreadType(value: String?): SupportThreadType? =
    value?.trim()?.takeIf { it.isNotBlank() }?.uppercase()?.let { raw ->
        runCatching { SupportThreadType.valueOf(raw) }.getOrNull()
            ?: throw InvalidInputException("threadType must be BOOKING_THREAD, VENUE_CHAT or SUPPORT_TICKET")
    }

private fun parseOptionalThreadTypes(
    threadType: String?,
    threadTypes: String?,
): Set<SupportThreadType>? {
    val values =
        buildList {
            threadType?.takeIf { it.isNotBlank() }?.let { add(it) }
            threadTypes
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.let { addAll(it) }
        }
    if (values.isEmpty()) return null
    return values.map { parseOptionalThreadType(it) ?: throw InvalidInputException("threadType is required") }.toSet()
}

private fun parseGuestThreadSurface(value: String?): GuestThreadSurface =
    value?.trim()?.uppercase()?.let { raw ->
        runCatching { GuestThreadSurface.valueOf(raw) }.getOrNull()
    } ?: throw InvalidInputException("surface must be CONVERSATIONS or SUPPORT")

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
private const val MAX_BOOKING_CLIENT_MESSAGE_ID_LENGTH = 64
private const val MAX_SUPPORT_TITLE_LENGTH = 120
private const val SUPPORT_TICKET_STATUS_CHANGED = "SUPPORT_TICKET_STATUS_CHANGED"
private const val SUPPORT_TICKET_SCOPE_CHANGED = "SUPPORT_TICKET_SCOPE_CHANGED"
private const val SUPPORT_TICKET_ESCALATED = "SUPPORT_TICKET_ESCALATED"
private const val SUPPORT_TICKET_ASSIGNED = "SUPPORT_TICKET_ASSIGNED"
private const val SUPPORT_TICKET_MESSAGE_ADDED = "SUPPORT_TICKET_MESSAGE_ADDED"
