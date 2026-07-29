package com.hookah.platform.backend.miniapp.venue

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.venueDraftPreviewRoutes(venueDraftPreviewReadService: VenueDraftPreviewReadService) {
    route("/venue/{venueId}/draft-preview") {
        get {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                venueDraftPreviewReadService.getDraftPreview(
                    userId = call.requireUserId(),
                    venueId = call.requireVenueId(),
                ),
            )
        }
    }
}
