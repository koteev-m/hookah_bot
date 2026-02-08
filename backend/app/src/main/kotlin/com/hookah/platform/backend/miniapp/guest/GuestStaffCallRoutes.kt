package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.StaffCallRequest
import com.hookah.platform.backend.miniapp.guest.api.StaffCallResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.StaffCallReason
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.Locale

private const val REASON_MAX_LENGTH = 32
private const val COMMENT_MAX_LENGTH = 500
private val REASON_REGEX = Regex("^[A-Z0-9_]{1,32}$")

fun Route.guestStaffCallRoutes(
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    staffCallRepository: StaffCallRepository,
) {
    post("/staff-call") {
        val request = call.receive<StaffCallRequest>()
        val token = validateTableToken(request.tableToken)
        val reason = normalizeReason(request.reason)
        val comment = normalizeComment(request.comment)

        val table = tableTokenResolver(token) ?: throw NotFoundException()
        ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository)

        val created =
            staffCallRepository.createGuestStaffCall(
                venueId = table.venueId,
                tableId = table.tableId,
                reason = reason,
                comment = comment,
            )

        call.respond(
            StaffCallResponse(
                staffCallId = created.id,
                createdAtEpochSeconds = created.createdAt.epochSecond,
            ),
        )
    }
}

private fun normalizeReason(rawReason: String): StaffCallReason {
    val trimmed = rawReason.trim()
    if (trimmed.isEmpty()) {
        throw InvalidInputException("reason is required")
    }
    val normalized = trimmed.uppercase(Locale.ROOT)
    if (normalized.length !in 1..REASON_MAX_LENGTH) {
        throw InvalidInputException("reason length must be <= $REASON_MAX_LENGTH")
    }
    if (!REASON_REGEX.matches(normalized)) {
        throw InvalidInputException("reason must match ${REASON_REGEX.pattern}")
    }
    return runCatching { StaffCallReason.valueOf(normalized) }
        .getOrElse {
            throw InvalidInputException(
                "reason must be one of ${StaffCallReason.values().joinToString { it.name }}",
            )
        }
}

private fun normalizeComment(comment: String?): String? {
    val trimmed = comment?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return null
    }
    if (trimmed.length > COMMENT_MAX_LENGTH) {
        throw InvalidInputException("comment length must be <= $COMMENT_MAX_LENGTH")
    }
    return trimmed
}
