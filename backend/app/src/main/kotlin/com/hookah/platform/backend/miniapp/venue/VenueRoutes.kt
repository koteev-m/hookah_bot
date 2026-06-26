package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.location.VenueLocationDisplay
import com.hookah.platform.backend.location.buildYandexVenueRouteUrl
import com.hookah.platform.backend.location.formatVenueDisplayAddress
import com.hookah.platform.backend.miniapp.venue.location.DisabledVenueLocationProvider
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationProvider
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationResolveProviderRequest
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationResolvedItem
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationSuggestProviderRequest
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationSuggestionItem
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationSuggestionKind
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingDateOverride
import com.hookah.platform.backend.telegram.db.VenueBookingHours
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenuePublicCardSettings
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

private val logger = LoggerFactory.getLogger("VenueRoutes")
private const val PUBLIC_CARD_CITY_MAX_LENGTH = 120
private const val PUBLIC_CARD_ADDRESS_MAX_LENGTH = 300
private const val PUBLIC_CARD_FORMATTED_ADDRESS_MAX_LENGTH = 500
private const val PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH = 300
private const val PUBLIC_CARD_DESCRIPTION_MAX_LENGTH = 500
private const val LOCATION_QUERY_MAX_LENGTH = 120
private const val LOCATION_CITY_MAX_LENGTH = 120
private const val LOCATION_SESSION_TOKEN_MAX_LENGTH = 120
private const val SCHEDULE_GUEST_NOTE_MAX_LENGTH = 240
private const val MAX_SCHEDULE_RANGE_DAYS = 370L
private val DEFAULT_SCHEDULE_OPENS_AT: LocalTime = LocalTime.of(18, 0)
private val DEFAULT_SCHEDULE_CLOSES_AT: LocalTime = LocalTime.MIDNIGHT

@Serializable
data class VenueMeResponse(
    val userId: Long,
    val venues: List<VenueAccessDto>,
)

@Serializable
data class VenueAccessDto(
    val venueId: Long,
    val venueName: String? = null,
    val venueCity: String? = null,
    val venueStatus: String? = null,
    val role: String,
    val permissions: List<String>,
)

@Serializable
data class StaffChatLinkCodeResponse(
    val code: String,
    val expiresAt: String,
    val ttlSeconds: Long,
    val linkCommand: String? = null,
    val testCommand: String? = null,
)

@Serializable
data class StaffChatStatusResponse(
    val venueId: Long,
    val isLinked: Boolean,
    val chatId: Long? = null,
    val maskedChatId: String? = null,
    val linkedAt: String? = null,
    val linkedByUserId: Long? = null,
    val activeCodeHint: String? = null,
    val activeCodeExpiresAt: String? = null,
    val testCommand: String? = null,
)

@Serializable
data class StaffChatTestResponse(
    val result: String,
    val queued: Boolean,
    val message: String,
)

@Serializable
data class VenuePublicCardSettingsResponse(
    val venueId: Long,
    val name: String,
    val city: String? = null,
    val address: String? = null,
    val countryCode: String? = null,
    val formattedAddress: String? = null,
    val displayAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val routeUrl: String? = null,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

@Serializable
data class VenuePublicCardSettingsUpdateRequest(
    val city: String? = null,
    val address: String? = null,
    val countryCode: String? = null,
    val formattedAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

@Serializable
data class VenueLocationSuggestionsResponse(
    val items: List<VenueLocationSuggestionItem>,
    val unavailable: Boolean = false,
    val message: String? = null,
)

@Serializable
data class VenueLocationResolveRequest(
    val providerUri: String? = null,
    val query: String? = null,
    val countryCode: String? = null,
    val city: String? = null,
)

@Serializable
data class VenueLocationResolveResponse(
    val location: VenueLocationResolvedItem? = null,
    val unavailable: Boolean = false,
    val message: String? = null,
)

@Serializable
data class VenueScheduleSettingsResponse(
    val venueId: Long,
    val weeklyHours: List<VenueScheduleDayDto>,
    val dateOverrides: List<VenueScheduleOverrideDto>,
)

@Serializable
data class VenueScheduleDayDto(
    val weekday: Int,
    val opensAt: String,
    val closesAt: String,
    val isClosed: Boolean,
    val configured: Boolean,
)

@Serializable
data class VenueScheduleDayUpdateRequest(
    val opensAt: String? = null,
    val closesAt: String? = null,
    val isClosed: Boolean = false,
)

@Serializable
data class VenueScheduleOverrideDto(
    val serviceDate: String,
    val opensAt: String,
    val closesAt: String,
    val isClosed: Boolean,
    val guestNote: String? = null,
)

@Serializable
data class VenueScheduleOverrideUpdateRequest(
    val opensAt: String? = null,
    val closesAt: String? = null,
    val isClosed: Boolean = false,
    val guestNote: String? = null,
)

@Serializable
data class VenueScheduleOverrideRangeUpdateRequest(
    val fromDate: String,
    val toDate: String,
    val opensAt: String? = null,
    val closesAt: String? = null,
    val isClosed: Boolean = false,
    val guestNote: String? = null,
)

fun Route.venueRoutes(
    venueAccessRepository: VenueAccessRepository,
    staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
    venueRepository: VenueRepository,
    venueBookingHoursRepository: VenueBookingHoursRepository,
    venueLocationProvider: VenueLocationProvider = DisabledVenueLocationProvider(),
    staffChatNotifier: StaffChatNotifier? = null,
    telegramBotUsername: String? = null,
) {
    route("/venue") {
        get("/me") {
            val userId = call.requireUserId()
            val memberships = venueAccessRepository.listVenueMemberships(userId)
            val venues =
                memberships.mapNotNull { membership ->
                    val role = VenueRoleMapping.fromDb(membership.role)
                    if (role == null) {
                        logger.warn(
                            "Unknown venue role {} for userId={} venueId={}",
                            membership.role,
                            userId,
                            membership.venueId,
                        )
                        return@mapNotNull null
                    }
                    val permissions = VenuePermissions.forRole(role)
                    VenueAccessDto(
                        venueId = membership.venueId,
                        venueName = membership.venueName,
                        venueCity = membership.venueCity,
                        venueStatus = membership.venueStatus,
                        role = role.name,
                        permissions = permissions.map { it.name },
                    )
                }
            if (venues.isEmpty()) {
                throw ForbiddenException()
            }
            call.respond(VenueMeResponse(userId = userId, venues = venues))
        }

        get("/{venueId}/public-card") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePublicCardSettingsPermission(venueAccessRepository, userId, venueId)
            val settings = venueRepository.findPublicCardSettings(venueId) ?: throw NotFoundException()
            call.respond(settings.toResponse())
        }

        put("/{venueId}/public-card") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePublicCardSettingsPermission(venueAccessRepository, userId, venueId)
            val request = call.receive<VenuePublicCardSettingsUpdateRequest>()
            val city = normalizePublicCardText(request.city, PUBLIC_CARD_CITY_MAX_LENGTH, "city")
            val address = normalizePublicCardText(request.address, PUBLIC_CARD_ADDRESS_MAX_LENGTH, "address")
            val countryCode = normalizeCountryCode(request.countryCode)
            val formattedAddress =
                normalizePublicCardText(
                    request.formattedAddress,
                    PUBLIC_CARD_FORMATTED_ADDRESS_MAX_LENGTH,
                    "formattedAddress",
                )
            val coordinates = normalizeCoordinates(request.latitude, request.longitude, formattedAddress)
            val guestContact =
                normalizePublicCardText(request.guestContact, PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH, "guestContact")
            val cardDescription =
                normalizePublicCardText(
                    request.cardDescription,
                    PUBLIC_CARD_DESCRIPTION_MAX_LENGTH,
                    "cardDescription",
                )
            val settings =
                venueRepository.updatePublicCardSettings(
                    venueId = venueId,
                    city = city,
                    address = address,
                    countryCode = countryCode,
                    formattedAddress = formattedAddress,
                    latitude = coordinates.first,
                    longitude = coordinates.second,
                    guestContact = guestContact,
                    cardDescription = cardDescription,
                ) ?: throw NotFoundException()
            call.respond(settings.toResponse())
        }

        get("/{venueId}/schedule") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            ensureVenueExists(venueRepository, venueId)
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        put("/{venueId}/schedule/weekly/{weekday}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val weekday =
                call.parameters["weekday"]?.toIntOrNull()
                    ?: throw InvalidInputException("weekday is required")
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            val request = call.receive<VenueScheduleDayUpdateRequest>()
            val update = normalizeScheduleUpdate(request.opensAt, request.closesAt, request.isClosed)
            val updated =
                venueBookingHoursRepository.upsertWeekdayHours(
                    venueId = venueId,
                    weekday = weekday,
                    opensAt = update.opensAt,
                    closesAt = update.closesAt,
                    isClosed = update.isClosed,
                )
            if (!updated) {
                throw NotFoundException()
            }
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        put("/{venueId}/schedule/overrides/{serviceDate}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val serviceDate = parseScheduleDate(call.parameters["serviceDate"])
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            val request = call.receive<VenueScheduleOverrideUpdateRequest>()
            val update = normalizeScheduleUpdate(request.opensAt, request.closesAt, request.isClosed, request.guestNote)
            val updated =
                venueBookingHoursRepository.upsertDateOverride(
                    venueId = venueId,
                    serviceDate = serviceDate,
                    opensAt = update.opensAt,
                    closesAt = update.closesAt,
                    isClosed = update.isClosed,
                    guestNote = update.guestNote,
                )
            if (!updated) {
                throw NotFoundException()
            }
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        post("/{venueId}/schedule/override-ranges") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            val request = call.receive<VenueScheduleOverrideRangeUpdateRequest>()
            val (fromDate, toDate) = normalizeScheduleDateRange(request.fromDate, request.toDate)
            val update = normalizeScheduleUpdate(request.opensAt, request.closesAt, request.isClosed, request.guestNote)
            val updated =
                venueBookingHoursRepository.upsertDateOverrideRange(
                    venueId = venueId,
                    fromDate = fromDate,
                    toDate = toDate,
                    opensAt = update.opensAt,
                    closesAt = update.closesAt,
                    isClosed = update.isClosed,
                    guestNote = update.guestNote,
                )
            if (!updated) {
                throw NotFoundException()
            }
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        put("/{venueId}/schedule/override-ranges/{fromDate}/{toDate}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val originalFromDate = parseScheduleDate(call.parameters["fromDate"])
            val originalToDate = parseScheduleDate(call.parameters["toDate"])
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            val (normalizedOriginalFromDate, normalizedOriginalToDate) =
                normalizeScheduleDateRange(originalFromDate.toString(), originalToDate.toString())
            val request = call.receive<VenueScheduleOverrideRangeUpdateRequest>()
            val (fromDate, toDate) = normalizeScheduleDateRange(request.fromDate, request.toDate)
            val update = normalizeScheduleUpdate(request.opensAt, request.closesAt, request.isClosed, request.guestNote)
            val updated =
                venueBookingHoursRepository.replaceDateOverrideRange(
                    venueId = venueId,
                    originalFromDate = normalizedOriginalFromDate,
                    originalToDate = normalizedOriginalToDate,
                    fromDate = fromDate,
                    toDate = toDate,
                    opensAt = update.opensAt,
                    closesAt = update.closesAt,
                    isClosed = update.isClosed,
                    guestNote = update.guestNote,
                )
            if (!updated) {
                throw NotFoundException()
            }
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        delete("/{venueId}/schedule/overrides/{serviceDate}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val serviceDate = parseScheduleDate(call.parameters["serviceDate"])
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            ensureVenueExists(venueRepository, venueId)
            venueBookingHoursRepository.deleteDateOverride(venueId, serviceDate)
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        delete("/{venueId}/schedule/override-ranges/{fromDate}/{toDate}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val fromDate = parseScheduleDate(call.parameters["fromDate"])
            val toDate = parseScheduleDate(call.parameters["toDate"])
            requireScheduleSettingsPermission(venueAccessRepository, userId, venueId)
            ensureVenueExists(venueRepository, venueId)
            val normalizedRange = normalizeScheduleDateRange(fromDate.toString(), toDate.toString())
            venueBookingHoursRepository.deleteDateOverrideRange(
                venueId = venueId,
                fromDate = normalizedRange.first,
                toDate = normalizedRange.second,
            )
            call.respond(buildVenueScheduleSettingsResponse(venueId, venueBookingHoursRepository))
        }

        get("/{venueId}/location/suggestions") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePublicCardSettingsPermission(venueAccessRepository, userId, venueId)
            if (venueRepository.findVenueById(venueId) == null) {
                throw NotFoundException()
            }
            val params = call.request.queryParameters
            val kind =
                when (params["kind"]?.trim()?.lowercase()) {
                    "city" -> VenueLocationSuggestionKind.CITY
                    "address" -> VenueLocationSuggestionKind.ADDRESS
                    else -> throw InvalidInputException("kind must be city or address")
                }
            val query = normalizeLocationQuery(params["query"], "query")
            val countryCode =
                normalizeCountryCode(params["countryCode"])
                    ?: throw InvalidInputException("countryCode is required")
            val city =
                normalizeOptionalLocationText(params["city"], LOCATION_CITY_MAX_LENGTH, "city").also {
                    if (kind == VenueLocationSuggestionKind.ADDRESS && it == null) {
                        throw InvalidInputException("city is required for address suggestions")
                    }
                }
            val sessionToken =
                normalizeOptionalLocationText(
                    params["sessionToken"],
                    LOCATION_SESSION_TOKEN_MAX_LENGTH,
                    "sessionToken",
                )
            val result =
                venueLocationProvider.suggest(
                    VenueLocationSuggestProviderRequest(
                        kind = kind,
                        query = query,
                        countryCode = countryCode,
                        city = city,
                        sessionToken = sessionToken,
                    ),
                )
            call.respond(
                VenueLocationSuggestionsResponse(
                    items = result.items,
                    unavailable = result.unavailable,
                    message = if (result.unavailable) "Подсказки временно недоступны." else null,
                ),
            )
        }

        post("/{venueId}/location/resolve") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requirePublicCardSettingsPermission(venueAccessRepository, userId, venueId)
            if (venueRepository.findVenueById(venueId) == null) {
                throw NotFoundException()
            }
            val request = call.receive<VenueLocationResolveRequest>()
            val providerUri =
                normalizeOptionalLocationText(request.providerUri, 600, "providerUri")
            val query =
                normalizeOptionalLocationText(request.query, LOCATION_QUERY_MAX_LENGTH, "query")
            if (providerUri == null && query == null) {
                throw InvalidInputException("providerUri or query is required")
            }
            val countryCode = normalizeCountryCode(request.countryCode)
            val city = normalizeOptionalLocationText(request.city, LOCATION_CITY_MAX_LENGTH, "city")
            val result =
                venueLocationProvider.resolve(
                    VenueLocationResolveProviderRequest(
                        providerUri = providerUri,
                        query = query,
                        countryCode = countryCode,
                        city = city,
                    ),
                )
            call.respond(
                VenueLocationResolveResponse(
                    location = result.location,
                    unavailable = result.unavailable,
                    message =
                        if (result.unavailable) {
                            "Не удалось уточнить координаты. Адрес можно сохранить вручную."
                        } else {
                            null
                        },
                ),
            )
        }

        post("/{venueId}/staff-chat/link-code") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role =
                resolveVenueRole(
                    venueAccessRepository = venueAccessRepository,
                    userId = userId,
                    venueId = venueId,
                )
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.STAFF_CHAT_LINK)) {
                throw ForbiddenException()
            }
            val venueExists = venueRepository.findVenueById(venueId) != null
            if (!venueExists) {
                throw NotFoundException()
            }
            val created =
                staffChatLinkCodeRepository.createLinkCode(venueId, userId)
                    ?: throw DatabaseUnavailableException()
            logger.info(
                "Generated staff chat link code venueId={} by userId={} expiresAt={}",
                venueId,
                userId,
                created.expiresAt,
            )
            call.respond(
                StaffChatLinkCodeResponse(
                    code = created.code,
                    expiresAt = created.expiresAt.toString(),
                    ttlSeconds = created.ttlSeconds,
                    linkCommand = buildStaffChatTelegramCommand(telegramBotUsername, "link", created.code),
                    testCommand = buildStaffChatTelegramCommand(telegramBotUsername, "link_test"),
                ),
            )
        }

        get("/{venueId}/staff-chat") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            resolveVenueRole(
                venueAccessRepository = venueAccessRepository,
                userId = userId,
                venueId = venueId,
            ).let { role ->
                val permissions = VenuePermissions.forRole(role)
                if (!permissions.contains(VenuePermission.STAFF_CHAT_LINK)) {
                    throw ForbiddenException()
                }
            }
            val status = venueRepository.findStaffChatStatus(venueId) ?: throw NotFoundException()
            val activeCode = staffChatLinkCodeRepository.findActiveCodeForVenue(venueId)
            call.respond(
                StaffChatStatusResponse(
                    venueId = status.venueId,
                    isLinked = status.staffChatId != null,
                    chatId = null,
                    maskedChatId = maskStaffChatId(status.staffChatId),
                    linkedAt = status.linkedAt?.toString(),
                    linkedByUserId = status.linkedByUserId,
                    activeCodeHint = activeCode?.codeHint,
                    activeCodeExpiresAt = activeCode?.expiresAt?.toString(),
                    testCommand = buildStaffChatTelegramCommand(telegramBotUsername, "link_test"),
                ),
            )
        }

        post("/{venueId}/staff-chat/test") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role =
                resolveVenueRole(
                    venueAccessRepository = venueAccessRepository,
                    userId = userId,
                    venueId = venueId,
                )
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.STAFF_CHAT_LINK)) {
                throw ForbiddenException()
            }
            val notifier = staffChatNotifier ?: throw DatabaseUnavailableException()
            val result = notifier.notifyTestMessageNow(venueId)
            call.respond(result.toStaffChatTestResponse())
        }

        post("/{venueId}/staff-chat/unlink") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role =
                resolveVenueRole(
                    venueAccessRepository = venueAccessRepository,
                    userId = userId,
                    venueId = venueId,
                )
            if (role != VenueRole.OWNER) {
                throw ForbiddenException()
            }
            val venueExists = venueRepository.findVenueById(venueId) != null
            if (!venueExists) {
                throw NotFoundException()
            }
            when (venueRepository.unlinkStaffChatByVenueId(venueId, userId)) {
                is com.hookah.platform.backend.telegram.db.UnlinkResult.Success -> {
                    call.respond(mapOf("ok" to true))
                }
                com.hookah.platform.backend.telegram.db.UnlinkResult.NotLinked -> {
                    call.respond(mapOf("ok" to true))
                }
                com.hookah.platform.backend.telegram.db.UnlinkResult.DatabaseError -> {
                    throw DatabaseUnavailableException()
                }
            }
        }
    }
}

private suspend fun requirePublicCardSettingsPermission(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) {
    val role =
        resolveVenueRole(
            venueAccessRepository = venueAccessRepository,
            userId = userId,
            venueId = venueId,
        )
    if (role != VenueRole.OWNER && role != VenueRole.MANAGER) {
        throw ForbiddenException()
    }
}

private suspend fun requireScheduleSettingsPermission(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
) = requirePublicCardSettingsPermission(venueAccessRepository, userId, venueId)

private suspend fun ensureVenueExists(
    venueRepository: VenueRepository,
    venueId: Long,
) {
    if (venueRepository.findVenueById(venueId) == null) {
        throw NotFoundException()
    }
}

private data class NormalizedScheduleUpdate(
    val opensAt: LocalTime,
    val closesAt: LocalTime,
    val isClosed: Boolean,
    val guestNote: String?,
)

private fun normalizeScheduleUpdate(
    opensAt: String?,
    closesAt: String?,
    isClosed: Boolean,
    guestNote: String? = null,
): NormalizedScheduleUpdate {
    val normalizedGuestNote = normalizeScheduleGuestNote(guestNote)
    if (isClosed) {
        return NormalizedScheduleUpdate(
            opensAt = LocalTime.MIDNIGHT,
            closesAt = LocalTime.MIDNIGHT,
            isClosed = true,
            guestNote = normalizedGuestNote,
        )
    }
    val normalizedOpensAt =
        parseScheduleTime(opensAt)
            ?: throw InvalidInputException("opensAt must be HH:mm")
    val normalizedClosesAt =
        parseScheduleTime(closesAt)
            ?: throw InvalidInputException("closesAt must be HH:mm")
    return NormalizedScheduleUpdate(
        opensAt = normalizedOpensAt,
        closesAt = normalizedClosesAt,
        isClosed = false,
        guestNote = normalizedGuestNote,
    )
}

private fun parseScheduleDate(value: String?): LocalDate =
    runCatching { LocalDate.parse(value?.trim().orEmpty()) }.getOrElse {
        throw InvalidInputException("serviceDate must be ISO date")
    }

private fun normalizeScheduleDateRange(
    fromDate: String?,
    toDate: String?,
): Pair<LocalDate, LocalDate> {
    val parsedFromDate = parseScheduleDate(fromDate)
    val parsedToDate = parseScheduleDate(toDate)
    if (parsedToDate.isBefore(parsedFromDate)) {
        throw InvalidInputException("toDate must be on or after fromDate")
    }
    val days = ChronoUnit.DAYS.between(parsedFromDate, parsedToDate) + 1
    if (days > MAX_SCHEDULE_RANGE_DAYS) {
        throw InvalidInputException("date range must be <= $MAX_SCHEDULE_RANGE_DAYS days")
    }
    return parsedFromDate to parsedToDate
}

private fun normalizeScheduleGuestNote(value: String?): String? {
    val normalized = value?.trim().orEmpty()
    if (normalized.length > SCHEDULE_GUEST_NOTE_MAX_LENGTH) {
        throw InvalidInputException("guestNote length must be <= $SCHEDULE_GUEST_NOTE_MAX_LENGTH")
    }
    return normalized.ifBlank { null }
}

private suspend fun buildVenueScheduleSettingsResponse(
    venueId: Long,
    venueBookingHoursRepository: VenueBookingHoursRepository,
): VenueScheduleSettingsResponse {
    val weeklyHours = venueBookingHoursRepository.listWeeklyHours(venueId).associateBy { it.weekday }
    val dateOverrides = venueBookingHoursRepository.listDateOverrides(venueId, limit = 100)
    return VenueScheduleSettingsResponse(
        venueId = venueId,
        weeklyHours =
            (1..7).map { weekday ->
                weeklyHours[weekday]?.toDto(configured = true)
                    ?: VenueScheduleDayDto(
                        weekday = weekday,
                        opensAt = formatScheduleTime(DEFAULT_SCHEDULE_OPENS_AT),
                        closesAt = formatScheduleTime(DEFAULT_SCHEDULE_CLOSES_AT),
                        isClosed = false,
                        configured = false,
                    )
            },
        dateOverrides = dateOverrides.map { it.toDto() },
    )
}

private fun VenueBookingHours.toDto(configured: Boolean): VenueScheduleDayDto =
    VenueScheduleDayDto(
        weekday = weekday,
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        isClosed = isClosed,
        configured = configured,
    )

private fun VenueBookingDateOverride.toDto(): VenueScheduleOverrideDto =
    VenueScheduleOverrideDto(
        serviceDate = serviceDate.toString(),
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        isClosed = isClosed,
        guestNote = guestNote,
    )

private fun normalizePublicCardText(
    raw: String?,
    maxLength: Int,
    fieldName: String,
): String? {
    val normalized = raw?.trim().orEmpty()
    if (normalized.length > maxLength) {
        throw InvalidInputException("$fieldName length must be <= $maxLength")
    }
    return normalized.ifBlank { null }
}

private fun normalizeOptionalLocationText(
    raw: String?,
    maxLength: Int,
    fieldName: String,
): String? = normalizePublicCardText(raw, maxLength, fieldName)

private fun normalizeLocationQuery(
    raw: String?,
    fieldName: String,
): String {
    val normalized = raw?.trim().orEmpty()
    if (normalized.length < 2) {
        throw InvalidInputException("$fieldName length must be >= 2")
    }
    if (normalized.length > LOCATION_QUERY_MAX_LENGTH) {
        throw InvalidInputException("$fieldName length must be <= $LOCATION_QUERY_MAX_LENGTH")
    }
    return normalized
}

private fun normalizeCountryCode(raw: String?): String? {
    val normalized = raw?.trim().orEmpty().uppercase()
    if (normalized.isBlank()) return null
    if (!Regex("^[A-Z]{2}$").matches(normalized)) {
        throw InvalidInputException("countryCode must be ISO 3166-1 alpha-2")
    }
    return normalized
}

private fun normalizeCoordinates(
    latitude: Double?,
    longitude: Double?,
    formattedAddress: String?,
): Pair<Double?, Double?> {
    if (latitude == null && longitude == null) return null to null
    if (latitude == null || longitude == null) {
        throw InvalidInputException("latitude and longitude must be provided together")
    }
    if (latitude !in -90.0..90.0) {
        throw InvalidInputException("latitude must be between -90 and 90")
    }
    if (longitude !in -180.0..180.0) {
        throw InvalidInputException("longitude must be between -180 and 180")
    }
    if (formattedAddress == null) {
        throw InvalidInputException("formattedAddress is required when coordinates are provided")
    }
    return latitude to longitude
}

private fun VenuePublicCardSettings.toResponse(): VenuePublicCardSettingsResponse =
    locationDisplay().let { location ->
        VenuePublicCardSettingsResponse(
            venueId = venueId,
            name = name,
            city = city,
            address = address,
            countryCode = countryCode,
            formattedAddress = formattedAddress,
            displayAddress = formatVenueDisplayAddress(location),
            latitude = latitude,
            longitude = longitude,
            routeUrl = buildYandexVenueRouteUrl(location),
            guestContact = guestContact,
            cardDescription = cardDescription,
        )
    }

private fun VenuePublicCardSettings.locationDisplay(): VenueLocationDisplay =
    VenueLocationDisplay(
        name = name,
        countryCode = countryCode,
        city = city,
        address = address,
        formattedAddress = formattedAddress,
        latitude = latitude,
        longitude = longitude,
    )

private fun buildStaffChatTelegramCommand(
    botUsername: String?,
    command: String,
    argument: String? = null,
): String {
    val username = botUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    return buildString {
        append('/').append(command)
        if (username != null) {
            append('@').append(username)
        }
        if (!argument.isNullOrBlank()) {
            append(' ').append(argument)
        }
    }
}

private fun maskStaffChatId(chatId: Long?): String? {
    if (chatId == null) return null
    val text = chatId.toString()
    val tail = text.takeLast(4)
    return if (text.startsWith("-100") && text.length > 8) {
        "-100...$tail"
    } else {
        "...$tail"
    }
}

private fun StaffChatNotificationResult.toStaffChatTestResponse(): StaffChatTestResponse =
    when (this) {
        StaffChatNotificationResult.SENT_OR_QUEUED ->
            StaffChatTestResponse(
                result = "QUEUED",
                queued = true,
                message = "Тестовое сообщение поставлено в отправку.",
            )
        StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT ->
            StaffChatTestResponse(
                result = "NO_STAFF_CHAT",
                queued = false,
                message = "Чат не подключён.",
            )
        StaffChatNotificationResult.SKIPPED_INACTIVE ->
            StaffChatTestResponse(
                result = "TELEGRAM_INACTIVE",
                queued = false,
                message = "Telegram-бот не активен, тестовое сообщение не отправлено.",
            )
        StaffChatNotificationResult.FAILED_ENQUEUE ->
            StaffChatTestResponse(
                result = "FAILED",
                queued = false,
                message = "Не удалось поставить тестовое сообщение в отправку.",
            )
        StaffChatNotificationResult.SKIPPED_DISABLED,
        StaffChatNotificationResult.SKIPPED_DUPLICATE,
        ->
            StaffChatTestResponse(
                result = name,
                queued = false,
                message = "Тестовое сообщение не было поставлено в отправку.",
            )
    }
