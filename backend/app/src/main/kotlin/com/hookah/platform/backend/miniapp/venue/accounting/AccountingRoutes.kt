package com.hookah.platform.backend.miniapp.venue.accounting
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private val accountingResponseJson = Json { encodeDefaults = true }

fun Route.accountingRoutes(repository: AccountingRepository) {
    route("/venue/{venueId}/accounting") {
        get {
            val before =
                call.request.queryParameters["beforeShiftId"]?.let {
                    it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw InvalidInputException()
                }
            val state = repository.state(call.requireVenueId(), call.requireUserId(), before)
            call.respond(accountingResponseJson.encodeToJsonElement(state))
        }
        get("/{shiftId}") {
            val shiftId = call.parameters["shiftId"]?.toLongOrNull() ?: throw InvalidInputException()
            val report = repository.detail(call.requireVenueId(), call.requireUserId(), shiftId)
            call.respond(accountingResponseJson.encodeToJsonElement(report))
        }
        post("/commands") {
            val report = repository.execute(call.requireVenueId(), call.requireUserId(), call.receive())
            call.respond(accountingResponseJson.encodeToJsonElement(report))
        }
    }
}
