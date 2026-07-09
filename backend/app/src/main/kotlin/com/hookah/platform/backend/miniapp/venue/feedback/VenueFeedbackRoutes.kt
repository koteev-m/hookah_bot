package com.hookah.platform.backend.miniapp.venue.feedback

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueAggregate
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueDetail
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueFilter
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackVenueSummary
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.support.SupportMessageAuthorRole
import com.hookah.platform.backend.support.SupportMessageSource
import com.hookah.platform.backend.support.SupportThreadRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class VenueFeedbackResponse(
    val venueId: Long,
    val filter: String,
    val summary: VenueFeedbackSummaryDto,
    val items: List<VenueFeedbackItemDto>,
)

@Serializable
data class VenueFeedbackSummaryDto(
    val count: Long,
    val averageRating: Double? = null,
    val lowCount: Long,
)

@Serializable
data class VenueFeedbackItemDto(
    val feedbackId: Long,
    val visitId: Long,
    val occurredAt: String,
    val serviceDate: String? = null,
    val rating: Int? = null,
    val tags: List<String> = emptyList(),
    val comment: String? = null,
    val guestLabel: String,
    val createdAt: String? = null,
)

@Serializable
data class VenueFeedbackFollowUpResponse(
    val threadId: Long,
    val threadType: String,
    val message: String,
)

fun Route.venueFeedbackRoutes(
    venueAccessRepository: VenueAccessRepository,
    visitFeedbackRepository: VisitFeedbackRepository,
    supportThreadRepository: SupportThreadRepository,
) {
    route("/venue") {
        get("/{venueId}/feedback") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (VenuePermission.FEEDBACK_VIEW !in role.permissions()) {
                throw ForbiddenException()
            }
            val filter = parseFeedbackFilter(call.request.queryParameters["filter"])
            val limit = parseLimit(call.request.queryParameters["limit"])
            val offset = parseOffset(call.request.queryParameters["offset"])
            val aggregate = visitFeedbackRepository.loadVenueFeedbackAggregate(venueId)
            val items =
                visitFeedbackRepository.listVenueFeedback(
                    venueId = venueId,
                    filter = filter.repositoryFilter,
                    limit = limit,
                    offset = offset,
                )
            call.respond(
                VenueFeedbackResponse(
                    venueId = venueId,
                    filter = filter.code,
                    summary = aggregate.toDto(),
                    items = items.map { it.toDto() },
                ),
            )
        }

        post("/{venueId}/feedback/{feedbackId}/follow-up") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val feedbackId =
                call.parameters["feedbackId"]?.toLongOrNull()
                    ?: throw InvalidInputException("feedbackId is required")
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (VenuePermission.FEEDBACK_VIEW !in role.permissions()) {
                throw ForbiddenException()
            }
            val feedback =
                visitFeedbackRepository.getVenueFeedbackDetail(venueId, feedbackId)
                    ?: throw NotFoundException()
            if (!feedback.guestExists) {
                throw NotFoundException()
            }
            val rating = feedback.rating ?: throw InvalidInputException("Only low feedback can be followed up")
            if (rating !in 1..3) {
                throw InvalidInputException("Only low feedback can be followed up")
            }
            val feedbackContext = feedback.toFollowUpContextMessage()
            val thread =
                supportThreadRepository.createOrFindVenueChat(
                    venueId = venueId,
                    guestUserId = feedback.guestUserId,
                    title = "Отзыв после визита",
                )
            if (
                thread.messages.none {
                    it.authorRole == SupportMessageAuthorRole.SYSTEM &&
                        it.source == SupportMessageSource.SYSTEM &&
                        it.text == feedbackContext
                }
            ) {
                supportThreadRepository.addMessage(
                    threadId = thread.thread.id,
                    authorUserId = null,
                    authorRole = SupportMessageAuthorRole.SYSTEM,
                    source = SupportMessageSource.SYSTEM,
                    text = feedbackContext,
                    statusAfterInsert = null,
                )
            }
            call.respond(
                VenueFeedbackFollowUpResponse(
                    threadId = thread.thread.id,
                    threadType = "VENUE_CHAT",
                    message = "Чат с гостем открыт.",
                ),
            )
        }
    }
}

private data class FeedbackFilter(
    val code: String,
    val repositoryFilter: VisitFeedbackVenueFilter,
)

private fun parseFeedbackFilter(raw: String?): FeedbackFilter =
    when (raw?.trim()?.lowercase() ?: "all") {
        "all" -> FeedbackFilter(code = "all", repositoryFilter = VisitFeedbackVenueFilter.ALL)
        "low" -> FeedbackFilter(code = "low", repositoryFilter = VisitFeedbackVenueFilter.LOW)
        else -> throw InvalidInputException("Unsupported feedback filter")
    }

private fun parseLimit(raw: String?): Int =
    raw
        ?.toIntOrNull()
        ?.takeIf { it in 1..50 }
        ?: 20

private fun parseOffset(raw: String?): Int =
    raw
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
        ?: 0

private fun com.hookah.platform.backend.miniapp.venue.VenueRole.permissions(): Set<VenuePermission> =
    com.hookah.platform.backend.miniapp.venue.VenuePermissions.forRole(this)

private fun VisitFeedbackVenueAggregate.toDto(): VenueFeedbackSummaryDto =
    VenueFeedbackSummaryDto(
        count = count,
        averageRating = averageRating,
        lowCount = lowCount,
    )

private fun VisitFeedbackVenueSummary.toDto(): VenueFeedbackItemDto =
    VenueFeedbackItemDto(
        feedbackId = feedbackId,
        visitId = visitId,
        occurredAt = occurredAt.toString(),
        serviceDate = serviceDate?.toString(),
        rating = rating,
        tags = tags,
        comment = comment,
        guestLabel = guestDisplayName?.takeIf { it.isNotBlank() } ?: "Гость #$guestUserId",
        createdAt = createdAt?.toString(),
    )

private fun VisitFeedbackVenueDetail.toFollowUpContextMessage(): String {
    val lines =
        buildList {
            add("Отзыв после визита")
            rating?.let { add("Оценка: $it/5") }
            if (tags.isNotEmpty()) {
                add("Теги: ${tags.joinToString(", ") { tag -> tag.feedbackTagLabel() }}")
            }
            comment?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Комментарий: $it") }
            add("Дата визита: ${serviceDate?.toString() ?: occurredAt.toString()}")
        }
    return lines.joinToString("\n")
}

private fun String.feedbackTagLabel(): String =
    when (this) {
        "service" -> "сервис"
        "hookah_quality" -> "кальян"
        "taste" -> "вкус"
        "speed" -> "скорость"
        "atmosphere" -> "атмосфера"
        "cleanliness" -> "чистота"
        "booking" -> "бронь"
        "price" -> "цена"
        else -> this
    }
