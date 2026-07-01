package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.StaffCallRequest
import com.hookah.platform.backend.miniapp.guest.api.StaffCallResponse
import com.hookah.platform.backend.miniapp.guest.api.StaffCallStatusDto
import com.hookah.platform.backend.miniapp.guest.api.StaffCallStatusResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.StaffCallNotification
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallQueueItem
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffCallStatus
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.isOrderLinkEligibleStaffCall
import com.hookah.platform.backend.telegram.staffCallReasonLabel
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val GUEST_STAFF_CALL_STATUS_LIMIT = 5

fun Route.guestStaffCallRoutes(
    guestRateLimitConfig: GuestRateLimitConfig,
    rateLimiter: RateLimiter,
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    staffCallRepository: StaffCallRepository,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    staffChatNotifier: StaffChatNotifier? = null,
    userRepository: UserRepository = UserRepository(null),
    ordersRepository: OrdersRepository = OrdersRepository(null),
) {
    route("/staff-call") {
        installGuestStaffCallRateLimit(
            endpoint = "guest.staff-call",
            policy = guestRateLimitConfig.staffCall,
            rateLimiter = rateLimiter,
            tableTokenResolver = tableTokenResolver,
            resolvedTableAttribute = staffCallResolvedTableAttribute,
        )

        post {
            val request = call.receive<StaffCallRequest>()
            val token = validateTableToken(request.tableToken)
            val reason = normalizeStaffCallReason(request.reason)
            val comment = normalizeStaffCallComment(request.comment)

            val table =
                call.rateLimitResolvedTableOrNull(staffCallResolvedTableAttribute)
                    ?: (tableTokenResolver(token) ?: throw NotFoundException())
            val tableSession =
                tableSessionRepository.touchActiveSession(
                    tableSessionId = request.tableSessionId,
                    venueId = table.venueId,
                    tableId = table.tableId,
                    ttl = tableSessionConfig.ttl,
                ) ?: throw NotFoundException()
            ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)
            val userId = call.requireUserId()
            val linkedOrderId =
                resolveSafeStaffCallOrderId(
                    ordersRepository = ordersRepository,
                    staffCallRepository = staffCallRepository,
                    venueId = table.venueId,
                    tableSessionId = tableSession.id,
                    userId = userId,
                    reason = reason,
                    comment = comment,
                )

            val created =
                if (linkedOrderId != null) {
                    staffCallRepository.createGuestStaffCall(
                        venueId = table.venueId,
                        tableId = table.tableId,
                        tableSessionId = tableSession.id,
                        createdByUserId = userId,
                        reason = reason,
                        comment = comment,
                        orderId = linkedOrderId,
                    )
                } else {
                    staffCallRepository.createGuestStaffCall(
                        venueId = table.venueId,
                        tableId = table.tableId,
                        tableSessionId = tableSession.id,
                        createdByUserId = userId,
                        reason = reason,
                        comment = comment,
                    )
                }
            staffChatNotifier?.notifyStaffCallNow(
                StaffCallNotification(
                    venueId = table.venueId,
                    staffCallId = created.id,
                    tableLabel = table.tableNumber.toString(),
                    reason = reason,
                    comment = comment,
                    tableSessionId = tableSession.id,
                    orderId = linkedOrderId,
                    guestDisplayName =
                        runCatching { userRepository.findGuestProfile(userId)?.guestDisplayName }
                            .getOrNull(),
                ),
            )

            call.respond(
                StaffCallResponse(
                    staffCallId = created.id,
                    createdAtEpochSeconds = created.createdAt.epochSecond,
                    status = StaffCallStatus.NEW.dbValue,
                    statusLabel = guestStaffCallStatusLabel(StaffCallStatus.NEW),
                ),
            )
        }

        get("/status") {
            val rawToken = call.request.queryParameters["tableToken"]
            val token = validateTableToken(rawToken)
            val tableSessionId =
                call.request.queryParameters["tableSessionId"]?.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidInputException("tableSessionId must be a positive number")
            val table = tableTokenResolver(token) ?: throw NotFoundException()
            val tableSession =
                tableSessionRepository.touchActiveSession(
                    tableSessionId = tableSessionId,
                    venueId = table.venueId,
                    tableId = table.tableId,
                    ttl = tableSessionConfig.ttl,
                ) ?: throw NotFoundException()
            ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)
            val userId = call.requireUserId()
            val calls =
                staffCallRepository.listByGuestTableSession(
                    venueId = table.venueId,
                    tableId = table.tableId,
                    tableSessionId = tableSession.id,
                    userId = userId,
                    limit = GUEST_STAFF_CALL_STATUS_LIMIT,
                )
            call.respond(StaffCallStatusResponse(items = calls.map { it.toGuestStatusDto() }))
        }
    }
}

private suspend fun resolveSafeStaffCallOrderId(
    ordersRepository: OrdersRepository,
    staffCallRepository: StaffCallRepository,
    venueId: Long,
    tableSessionId: Long,
    userId: Long,
    reason: StaffCallReason,
    comment: String?,
): Long? {
    if (!isOrderLinkEligibleStaffCall(reason = reason, comment = comment)) {
        return null
    }
    val orderId =
        runCatching {
            ordersRepository.findSafelyLinkableStaffCallOrderId(
                venueId = venueId,
                tableSessionId = tableSessionId,
                userId = userId,
            )
        }.getOrNull() ?: return null
    val activeActivityCount =
        runCatching {
            staffCallRepository.countActiveOrderActivity(
                venueId = venueId,
                orderId = orderId,
            )
        }.getOrElse { Int.MAX_VALUE }
    return orderId.takeIf { activeActivityCount == 0 }
}

private fun StaffCallQueueItem.toGuestStatusDto(): StaffCallStatusDto {
    val parsedStatus = StaffCallStatus.fromDb(status)
    val parsedReason = runCatching { StaffCallReason.valueOf(reason) }.getOrDefault(StaffCallReason.OTHER)
    return StaffCallStatusDto(
        staffCallId = id,
        status = parsedStatus?.dbValue ?: status,
        statusLabel = guestStaffCallStatusLabel(parsedStatus),
        createdAtEpochSeconds = createdAt.epochSecond,
        reason = parsedReason.name,
        reasonLabel = staffCallReasonLabel(parsedReason),
        comment = comment,
    )
}

private fun guestStaffCallStatusLabel(status: StaffCallStatus?): String =
    when (status) {
        StaffCallStatus.NEW -> "Вызов отправлен"
        StaffCallStatus.ACK -> "Персонал принял вызов"
        StaffCallStatus.DONE -> "Вызов закрыт"
        StaffCallStatus.CANCELLED -> "Вызов отменён"
        null -> "Статус вызова обновлён"
    }
