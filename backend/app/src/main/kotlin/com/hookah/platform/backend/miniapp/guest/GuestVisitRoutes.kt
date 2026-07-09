package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitBookingDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitDetailDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitDetailResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitFeedbackDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitFeedbackSubmitRequest
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitFeedbackSubmitResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitListItemDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitListResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitOrderDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitOrderItemDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitOrderItemOptionDto
import com.hookah.platform.backend.miniapp.guest.api.GuestVisitPromotionDiscountDto
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitBooking
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitDetail
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitHistoryItem
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrder
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrderItem
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrderItemOption
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitPromotionDiscount
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRecord
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackStatus
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

fun Route.guestVisitRoutes(
    visitRepository: VisitRepository,
    visitFeedbackRepository: VisitFeedbackRepository,
    analyticsEventRepository: AnalyticsEventRepository? = null,
    venueSettingsRepository: VenueSettingsRepository? = null,
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
            val visit =
                visitRepository.getGuestVisitDetail(userId = userId, visitId = visitId)
                    ?: throw NotFoundException()
            val feedback = visitFeedbackRepository.findGuestFeedback(visitId = visitId, userId = userId)
            val publicReviewUrl = venueSettingsRepository.publicReviewUrlForRatingFive(feedback)
            call.respond(GuestVisitDetailResponse(visit = visit.toDto(feedback.toGuestFeedbackDto(publicReviewUrl))))
        }

        post("/{visitId}/feedback") {
            val userId = call.requireUserId()
            val visitId =
                call.parameters["visitId"]?.toLongOrNull()
                    ?: throw InvalidInputException("visitId must be a number")
            visitRepository.getGuestVisitDetail(userId = userId, visitId = visitId)
                ?: throw NotFoundException()
            val request = call.receive<GuestVisitFeedbackSubmitRequest>()
            val feedback =
                try {
                    visitFeedbackRepository.submitFeedback(
                        visitId = visitId,
                        userId = userId,
                        rating = request.rating,
                        tags = request.tags,
                        comment = request.comment,
                    )
                } catch (e: IllegalArgumentException) {
                    throw InvalidInputException(e.message ?: "Invalid feedback")
                } ?: throw NotFoundException()
            analyticsEventRepository.emitFeedbackSubmitted(feedback)
            val publicReviewUrl = venueSettingsRepository.publicReviewUrlForRatingFive(feedback)
            call.respond(
                GuestVisitFeedbackSubmitResponse(
                    feedback = feedback.toGuestFeedbackDto(publicReviewUrl),
                ),
            )
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

private fun GuestVisitDetail.toDto(feedback: GuestVisitFeedbackDto? = null): GuestVisitDetailDto =
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
        feedback = feedback,
    )

private suspend fun VenueSettingsRepository?.publicReviewUrlForRatingFive(feedback: VisitFeedbackRecord?): String? {
    if (this == null || feedback?.status != VisitFeedbackStatus.SUBMITTED || feedback.rating != 5) return null
    return getPublicReviewUrl(feedback.venueId)
}

private fun VisitFeedbackRecord?.toGuestFeedbackDto(publicReviewUrl: String? = null): GuestVisitFeedbackDto =
    GuestVisitFeedbackDto(
        eligible = true,
        submitted = this?.status == VisitFeedbackStatus.SUBMITTED,
        rating = this?.takeIf { it.status == VisitFeedbackStatus.SUBMITTED }?.rating,
        tags = this?.takeIf { it.status == VisitFeedbackStatus.SUBMITTED }?.tags ?: emptyList(),
        comment = this?.takeIf { it.status == VisitFeedbackStatus.SUBMITTED }?.comment,
        publicReviewUrl = publicReviewUrl,
    )

private suspend fun AnalyticsEventRepository?.emitFeedbackSubmitted(feedback: VisitFeedbackRecord) {
    if (this == null || feedback.status != VisitFeedbackStatus.SUBMITTED) return
    runCatching {
        append(
            AnalyticsEventRecord(
                eventType = "feedback_submitted",
                venueId = feedback.venueId,
                idempotencyKey = "feedback_submitted:${feedback.id}",
                payload =
                    buildJsonObject {
                        put("venue_id", feedback.venueId)
                        put("visit_id", feedback.visitId)
                        put("source", "guest_history")
                        feedback.rating?.let { put("rating", it) }
                        put("tags", JsonArray(feedback.tags.map { JsonPrimitive(it) }))
                        put("hasComment", !feedback.comment.isNullOrBlank())
                    },
            ),
        )
    }
}

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
        selectedOption = selectedOption?.toDto(),
        preferenceNote = preferenceNote,
        priceMinor = priceMinor,
        currency = currency,
        discountPercent = discountPercent,
        totalMinor = totalMinor,
    )

private fun GuestVisitOrderItemOption.toDto(): GuestVisitOrderItemOptionDto =
    GuestVisitOrderItemOptionDto(
        name = name,
        priceDeltaMinor = priceDeltaMinor,
    )
