package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitBookingDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitDetailDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitDetailResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitListItemDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitListResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitOrderDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitOrderItemDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitPromotionDiscountDto
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitBooking
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitDetail
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitHistoryItem
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrder
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrderItem
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitPromotionDiscount
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.util.Locale

fun Route.guestVisitRoutes(
    visitRepository: VisitRepository,
) {
    route("/visits") {
        get {
            val userId = call.requireUserId()
            val limit = parseLimit(call.request.queryParameters["limit"])
            val cursor = parseOptionalLong(call.request.queryParameters["cursor"], "cursor")
            val visits = visitRepository.listGuestVisitHistory(userId = userId, limit = limit, cursor = cursor)
            call.respond(GuestVisitListResponse(items = visits.map { it.toDto() }))
        }

        get("/{visitId}") {
            val userId = call.requireUserId()
            val visitId =
                call.parameters["visitId"]?.toLongOrNull()
                    ?: throw InvalidInputException("visitId must be a number")
            val visit = visitRepository.getGuestVisitDetail(userId = userId, visitId = visitId)
                ?: throw NotFoundException()
            call.respond(GuestVisitDetailResponse(visit = visit.toDto()))
        }
    }
}

private fun parseLimit(raw: String?): Int =
    raw
        ?.toIntOrNull()
        ?.takeIf { it in 1..50 }
        ?: 20

private fun parseOptionalLong(
    raw: String?,
    fieldName: String,
): Long? {
    if (raw.isNullOrBlank()) return null
    return raw.toLongOrNull() ?: throw InvalidInputException("$fieldName must be a number")
}

private fun GuestVisitHistoryItem.toDto(): GuestVisitListItemDto =
    GuestVisitListItemDto(
        visitId = visitId,
        venueId = venueId,
        venueName = venueName,
        venueCity = venueCity,
        occurredAt = occurredAt.toString(),
        serviceDate = serviceDate?.toString(),
        source = source.name.lowercase(Locale.ROOT),
        totalMinor = totalMinor,
        currency = currency,
        hasBooking = hasBooking,
        orderLabels = orderLabels,
    )

private fun GuestVisitDetail.toDto(): GuestVisitDetailDto =
    GuestVisitDetailDto(
        visitId = visitId,
        venueId = venueId,
        venueName = venueName,
        venueCity = venueCity,
        occurredAt = occurredAt.toString(),
        serviceDate = serviceDate?.toString(),
        source = source.name.lowercase(Locale.ROOT),
        booking = booking?.toDto(),
        orders = orders.map { it.toDto() },
        totalMinor = totalMinor,
        currency = currency,
    )

private fun GuestVisitBooking.toDto(): GuestVisitBookingDto =
    GuestVisitBookingDto(
        bookingId = bookingId,
        displayNumber = displayNumber,
        partySize = partySize,
        status = status.lowercase(Locale.ROOT),
    )

private fun GuestVisitOrder.toDto(): GuestVisitOrderDto =
    GuestVisitOrderDto(
        orderId = orderId,
        displayNumber = displayNumber,
        displayDate = displayDate?.toString(),
        items = items.map { it.toDto() },
        totalMinor = totalMinor,
        currency = currency,
        promotionDiscounts = promotionDiscounts.map { it.toDto() },
    )

private fun GuestVisitPromotionDiscount.toDto(): GuestVisitPromotionDiscountDto =
    GuestVisitPromotionDiscountDto(
        label = label,
        discountMinor = discountMinor,
        currency = currency,
        ruleType = ruleType,
    )

private fun GuestVisitOrderItem.toDto(): GuestVisitOrderItemDto =
    GuestVisitOrderItemDto(
        itemId = itemId,
        itemName = itemName,
        qty = qty,
        priceMinor = priceMinor,
        currency = currency,
        discountPercent = discountPercent,
        totalMinor = totalMinor,
    )
