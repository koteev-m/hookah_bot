package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.UnauthorizedException
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal

suspend fun resolveVenueRole(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long
): VenueRole {
    val membership = venueAccessRepository.findVenueMembership(userId, venueId)
        ?: throw ForbiddenException()
    return VenueRoleMapping.fromDb(membership.role) ?: throw ForbiddenException()
}

fun ApplicationCall.requireUserId(): Long {
    val principal = principal<JWTPrincipal>() ?: throw UnauthorizedException()
    val rawSubject = principal.payload.subject ?: throw UnauthorizedException()
    return rawSubject.toLongOrNull() ?: throw UnauthorizedException()
}

fun ApplicationCall.requireVenueId(): Long {
    val rawId = parameters["venueId"]
        ?: request.queryParameters["venueId"]
        ?: parameters["id"]
        ?: request.queryParameters["id"]
    return rawId?.toLongOrNull() ?: throw InvalidInputException("venueId must be a number")
}
