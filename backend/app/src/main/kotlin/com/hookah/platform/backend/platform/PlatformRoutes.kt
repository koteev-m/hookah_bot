package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class PlatformMeResponse(
    val ok: Boolean,
    val ownerUserId: Long
)

fun ApplicationCall.requirePlatformOwner(platformConfig: PlatformConfig): Long {
    val userId = requireUserId()
    val ownerId = platformConfig.ownerUserId ?: throw ForbiddenException()
    if (userId != ownerId) {
        throw ForbiddenException()
    }
    return userId
}

fun Route.platformRoutes(platformConfig: PlatformConfig) {
    route("/platform") {
        get("/me") {
            call.requirePlatformOwner(platformConfig)
            call.respond(PlatformMeResponse(ok = true, ownerUserId = platformConfig.ownerUserId!!))
        }
    }
}
