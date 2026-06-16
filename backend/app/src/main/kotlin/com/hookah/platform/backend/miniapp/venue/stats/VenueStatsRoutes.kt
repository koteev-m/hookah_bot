package com.hookah.platform.backend.miniapp.venue.stats

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.db.VenueStatsReport
import com.hookah.platform.backend.telegram.db.VenueStatsRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class VenueStatsResponse(
    val venueId: Long,
    val period: String,
    val periodTitle: String,
    val periodStart: String,
    val ordersCount: Long,
    val revenueMinor: Long,
    val averageCheckMinor: Long,
    val discountMinor: Long,
    val cancelledItemsCount: Long,
    val currency: String,
    val topItems: List<VenueStatsTopItemDto>,
)

@Serializable
data class VenueStatsTopItemDto(
    val itemName: String,
    val qty: Long,
)

fun Route.venueStatsRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueStatsRepository: VenueStatsRepository,
    venueSettingsRepository: VenueSettingsRepository,
) {
    route("/venue") {
        get("/{venueId}/stats") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            if (role !in setOf(VenueRole.OWNER, VenueRole.MANAGER)) {
                throw ForbiddenException()
            }
            val zoneId =
                venueSettingsRepository.resolveZoneId(
                    venueId = venueId,
                    fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
                )
            val period = resolveVenueStatsPeriod(call.request.queryParameters["period"], zoneId)
            val report =
                venueStatsRepository.loadVenueStats(
                    venueId = venueId,
                    periodStart = period.periodStart,
                )
            call.respond(report.toResponse(venueId, period))
        }
    }
}

private data class VenueStatsPeriod(
    val code: String,
    val title: String,
    val periodStart: java.time.Instant,
)

private fun resolveVenueStatsPeriod(
    rawPeriod: String?,
    zoneId: ZoneId,
): VenueStatsPeriod {
    val today = LocalDate.now(zoneId)
    return when (rawPeriod ?: "today") {
        "today" ->
            VenueStatsPeriod(
                code = "today",
                title = "Сегодня",
                periodStart = today.atStartOfDay(zoneId).toInstant(),
            )
        "7d" ->
            VenueStatsPeriod(
                code = "7d",
                title = "7 дней",
                periodStart = today.minusDays(7).atStartOfDay(zoneId).toInstant(),
            )
        "30d" ->
            VenueStatsPeriod(
                code = "30d",
                title = "30 дней",
                periodStart = today.minusDays(30).atStartOfDay(zoneId).toInstant(),
            )
        else -> throw InvalidInputException("Unsupported stats period")
    }
}

private fun VenueStatsReport.toResponse(
    venueId: Long,
    period: VenueStatsPeriod,
): VenueStatsResponse =
    VenueStatsResponse(
        venueId = venueId,
        period = period.code,
        periodTitle = period.title,
        periodStart = period.periodStart.toString(),
        ordersCount = ordersCount,
        revenueMinor = revenueMinor,
        averageCheckMinor = averageCheckMinor,
        discountMinor = discountMinor,
        cancelledItemsCount = cancelledItemsCount,
        currency = currency,
        topItems = topItems.map { item -> VenueStatsTopItemDto(itemName = item.itemName, qty = item.qty) },
    )
