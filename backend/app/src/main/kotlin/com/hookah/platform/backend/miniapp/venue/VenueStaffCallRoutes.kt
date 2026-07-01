package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.billPaymentMethodLabel
import com.hookah.platform.backend.telegram.db.StaffCallQueueItem
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffCallStatus
import com.hookah.platform.backend.telegram.db.StaffCallStatusUpdateResult
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.util.Locale

private const val DEFAULT_STAFF_CALL_LIMIT = 20
private const val MAX_STAFF_CALL_LIMIT = 100
private val staffCallAuditLogger = LoggerFactory.getLogger("StaffCallAudit")

@kotlinx.serialization.Serializable
data class VenueStaffCallDto(
    val id: Long,
    val tableId: Long? = null,
    val tableNumber: Int,
    val reason: String,
    val reasonLabel: String,
    val comment: String? = null,
    val orderId: Long? = null,
    val tabId: Long? = null,
    val paymentMethod: String? = null,
    val paymentMethodLabel: String? = null,
    val orderDisplayLabel: String? = null,
    val tabDisplayLabel: String? = null,
    val status: String,
    val statusLabel: String,
    val createdAt: String? = null,
    val guestDisplayName: String? = null,
)

@kotlinx.serialization.Serializable
data class VenueStaffCallsResponse(
    val items: List<VenueStaffCallDto>,
)

@kotlinx.serialization.Serializable
data class VenueStaffCallActionResponse(
    val call: VenueStaffCallDto,
    val applied: Boolean,
)

fun Route.venueStaffCallRoutes(
    venueAccessRepository: VenueAccessRepository,
    staffCallRepository: StaffCallRepository,
    auditLogRepository: AuditLogRepository,
) {
    route("/venue") {
        get("/{venueId}/staff-calls") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureStaffCallAccess(
                venueAccessRepository = venueAccessRepository,
                userId = userId,
                venueId = venueId,
                requiredPermission = VenuePermission.ORDER_QUEUE_VIEW,
            )
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_STAFF_CALL_LIMIT
            if (limit <= 0 || limit > MAX_STAFF_CALL_LIMIT) {
                throw InvalidInputException("limit must be between 1 and $MAX_STAFF_CALL_LIMIT")
            }
            val calls = staffCallRepository.listActiveByVenue(venueId = venueId, limit = limit)
            call.respond(VenueStaffCallsResponse(items = calls.map { it.toDto() }))
        }

        post("/{venueId}/staff-calls/{staffCallId}/ack") {
            call.updateStaffCall(
                venueAccessRepository = venueAccessRepository,
                staffCallRepository = staffCallRepository,
                auditLogRepository = auditLogRepository,
                action = StaffCallAction.ACK,
            )
        }

        post("/{venueId}/staff-calls/{staffCallId}/done") {
            call.updateStaffCall(
                venueAccessRepository = venueAccessRepository,
                staffCallRepository = staffCallRepository,
                auditLogRepository = auditLogRepository,
                action = StaffCallAction.DONE,
            )
        }
    }
}

private enum class StaffCallAction(
    val expectedStatus: StaffCallStatus,
) {
    ACK(StaffCallStatus.NEW),
    DONE(StaffCallStatus.ACK),
}

private suspend fun io.ktor.server.application.ApplicationCall.updateStaffCall(
    venueAccessRepository: VenueAccessRepository,
    staffCallRepository: StaffCallRepository,
    auditLogRepository: AuditLogRepository,
    action: StaffCallAction,
) {
    val actorUserId = requireUserId()
    val venueId = requireVenueId()
    ensureStaffCallAccess(
        venueAccessRepository = venueAccessRepository,
        userId = actorUserId,
        venueId = venueId,
        requiredPermission = VenuePermission.ORDER_STATUS_UPDATE,
    )
    val staffCallId =
        parameters["staffCallId"]?.toLongOrNull()
            ?: throw InvalidInputException("staffCallId must be a number")
    val result =
        when (action) {
            StaffCallAction.ACK ->
                staffCallRepository.ackStaffCall(
                    venueId = venueId,
                    staffCallId = staffCallId,
                    actorUserId = actorUserId,
                )
            StaffCallAction.DONE ->
                staffCallRepository.doneStaffCall(
                    venueId = venueId,
                    staffCallId = staffCallId,
                    actorUserId = actorUserId,
                )
        } ?: throw NotFoundException()
    appendStaffCallStatusAuditBestEffort(
        auditLogRepository = auditLogRepository,
        actorUserId = actorUserId,
        venueId = venueId,
        source = STAFF_CALL_AUDIT_SOURCE_VENUE_MINIAPP,
        fromStatus = action.expectedStatus,
        result = result,
        logger = staffCallAuditLogger,
    )
    respond(VenueStaffCallActionResponse(call = result.toDto(), applied = result.applied))
}

private suspend fun ensureStaffCallAccess(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
    requiredPermission: VenuePermission,
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(requiredPermission)) {
        throw ForbiddenException()
    }
}

private fun StaffCallQueueItem.toDto(): VenueStaffCallDto =
    VenueStaffCallDto(
        id = id,
        tableId = tableId,
        tableNumber = tableNumber,
        reason = reason,
        reasonLabel = staffCallReasonLabel(reason),
        comment = comment,
        orderId = orderId,
        tabId = tabId,
        paymentMethod = paymentMethod?.name,
        paymentMethodLabel = paymentMethodLabel,
        orderDisplayLabel = orderDisplayLabel,
        tabDisplayLabel = tabDisplayLabel,
        status = status,
        statusLabel = staffCallStatusLabel(status),
        createdAt = createdAt.toString(),
        guestDisplayName = guestDisplayName,
    )

private fun StaffCallStatusUpdateResult.toDto(): VenueStaffCallDto =
    VenueStaffCallDto(
        id = staffCallId,
        tableNumber = tableNumber,
        reason = reason.name,
        reasonLabel = staffCallReasonLabel(reason),
        comment = comment,
        orderId = orderId,
        tabId = tabId,
        paymentMethod = paymentMethod?.name,
        paymentMethodLabel = paymentMethod?.let(::billPaymentMethodLabel),
        orderDisplayLabel = orderDisplayLabel,
        tabDisplayLabel = tabDisplayLabel,
        status = status.dbValue,
        statusLabel = staffCallStatusLabel(status),
        guestDisplayName = guestDisplayName,
    )

private fun staffCallReasonLabel(reason: String): String =
    runCatching { StaffCallReason.valueOf(reason.uppercase(Locale.ROOT)) }
        .getOrNull()
        ?.let(::staffCallReasonLabel)
        ?: reason

private fun staffCallReasonLabel(reason: StaffCallReason): String =
    when (reason) {
        StaffCallReason.COALS -> "Заменить угли"
        StaffCallReason.BILL -> "Запрос счёта"
        StaffCallReason.COME -> "Консультация"
        StaffCallReason.OTHER -> "Другое"
    }

private fun staffCallStatusLabel(status: String): String =
    StaffCallStatus.fromDb(status)?.let(::staffCallStatusLabel) ?: status

private fun staffCallStatusLabel(status: StaffCallStatus): String =
    when (status) {
        StaffCallStatus.NEW -> "Новый"
        StaffCallStatus.ACK -> "В работе"
        StaffCallStatus.DONE -> "Выполнен"
        StaffCallStatus.CANCELLED -> "Отменён"
    }
