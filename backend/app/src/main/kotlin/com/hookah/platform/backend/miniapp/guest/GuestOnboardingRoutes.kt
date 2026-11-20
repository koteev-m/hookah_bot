package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.enforceMiniAppRateLimit
import com.hookah.platform.backend.miniapp.security.receiveBoundedJson
import com.hookah.platform.backend.miniapp.venue.VenueConnectionApplicationWriteRequest
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.submitFromMiniApp
import com.hookah.platform.backend.miniapp.venue.toVenueApplicationDto
import com.hookah.platform.backend.onboarding.VenueOnboardingService
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRecord
import com.hookah.platform.backend.telegram.db.VenueOnboardingSource
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

internal fun Route.guestOnboardingRoutes(
    onboarding: VenueOnboardingService,
    abuse: MiniAppAbuseProtection,
) {
    route("/venue-applications") {
        get {
            val applicant = call.requireUserId()
            call.response.header(HttpHeaders.CacheControl, "no-store, private")
            enforceMiniAppRateLimit(call, abuse.tryGuestApplicationRead(applicant))
            val applications = onboarding.listOwnApplications(applicant)
            call.respond(applications.map(VenueConnectionRequestRecord::toVenueApplicationDto))
        }
        post {
            val applicant = call.requireUserId()
            call.response.header(HttpHeaders.CacheControl, "no-store, private")
            enforceMiniAppRateLimit(call, abuse.tryGuestApplicationSubmit(applicant))
            val request = call.receiveBoundedJson<VenueConnectionApplicationWriteRequest>(Json, 8192)
            call.respond(onboarding.submitFromMiniApp(applicant, request, VenueOnboardingSource.GUEST_MINI_APP))
        }
    }
}
