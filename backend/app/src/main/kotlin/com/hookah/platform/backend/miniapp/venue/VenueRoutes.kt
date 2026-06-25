package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.telegram.StaffChatNotificationResult
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenuePublicCardSettings
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("VenueRoutes")
private const val PUBLIC_CARD_CITY_MAX_LENGTH = 120
private const val PUBLIC_CARD_ADDRESS_MAX_LENGTH = 300
private const val PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH = 300
private const val PUBLIC_CARD_DESCRIPTION_MAX_LENGTH = 500

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
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

@Serializable
data class VenuePublicCardSettingsUpdateRequest(
    val city: String? = null,
    val address: String? = null,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

fun Route.venueRoutes(
    venueAccessRepository: VenueAccessRepository,
    staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
    venueRepository: VenueRepository,
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
                    guestContact = guestContact,
                    cardDescription = cardDescription,
                ) ?: throw NotFoundException()
            call.respond(settings.toResponse())
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

private fun VenuePublicCardSettings.toResponse(): VenuePublicCardSettingsResponse =
    VenuePublicCardSettingsResponse(
        venueId = venueId,
        name = name,
        city = city,
        address = address,
        guestContact = guestContact,
        cardDescription = cardDescription,
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
