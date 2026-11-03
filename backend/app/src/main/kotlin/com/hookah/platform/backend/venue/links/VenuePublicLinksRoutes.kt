package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.ensureGuestBrowseAvailableReadOnly
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.CancellationException
import java.net.URI

data class VenuePublicLinkConfig(val origin: String, val botUsername: String, val mainMiniAppConfigured: Boolean) {
    fun publicUrl(slug: String): String = "$origin/v/$slug"

    fun telegramUrl(
        venueId: Long,
        main: Boolean = mainMiniAppConfigured,
    ): String = "https://t.me/$botUsername?${if (main) "startapp" else "start"}=${venuePayload(venueId)}"

    companion object {
        fun from(
            webAppUrl: String?,
            username: String?,
            mainMiniAppConfigured: Boolean = false,
            publicOrigin: String? = null,
            appEnv: String = "dev",
        ): VenuePublicLinkConfig? {
            val brandedOrigin =
                publicOrigin?.takeIf(String::isNotEmpty)?.let { value ->
                    require(appEnv == "prod" || appEnv == "production") {
                        "VENUE_PUBLIC_LINK_ORIGIN is only supported in production."
                    }
                    requireNotNull(originOf(value, strict = true)) {
                        "VENUE_PUBLIC_LINK_ORIGIN must be an HTTPS origin without a path, query or fragment."
                    }
                }
            val miniAppOrigin = originOf(webAppUrl ?: return null, strict = false) ?: return null
            val bot = username?.removePrefix("@")?.takeIf { Regex("[A-Za-z0-9_]{5,32}").matches(it) } ?: return null
            return VenuePublicLinkConfig(brandedOrigin ?: miniAppOrigin, bot, mainMiniAppConfigured)
        }

        private fun originOf(
            value: String,
            strict: Boolean,
        ): String? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                uri.port !in -1..65535 || uri.port == 0
            ) {
                return null
            }
            if (strict &&
                (
                    uri.rawPath !in listOf("", "/") || uri.rawQuery != null || uri.rawFragment != null ||
                        uri.rawAuthority.endsWith(':')
                )
            ) {
                return null
            }
            return URI("https", null, uri.host, uri.port, null, null, null).toASCIIString()
        }
    }
}

// Generated table tokens are 43 characters; venue entry payloads are at most 19.
fun isVenuePayload(payload: String?): Boolean = payload?.startsWith("venue_") == true && payload.length <= 19

fun venuePayload(venueId: Long): String = "venue_${venueId.toString(36)}"

fun parseVenuePayload(payload: String?): Long? =
    payload?.takeIf { Regex("venue_[1-9a-z][0-9a-z]{0,12}").matches(it) }
        ?.removePrefix("venue_")?.toLongOrNull(36)?.takeIf { it > 0 }

fun Route.venuePublicEntryRoutes(
    links: VenuePublicLinksRepository,
    venues: GuestVenueRepository,
    subscriptions: SubscriptionRepository,
    config: VenuePublicLinkConfig?,
    maintenanceActive: Boolean = false,
) {
    get("/v/{slug}") {
        call.response.header("Cache-Control", "no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; " +
                "frame-ancestors 'none'; form-action 'none'",
        )
        val venue =
            try {
                val id = links.resolveSlug(call.parameters["slug"].orEmpty()) ?: throw NotFoundException()
                if (maintenanceActive) throw NotFoundException()
                ensureGuestBrowseAvailableReadOnly(id, venues, subscriptions)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error !is NotFoundException && error !is DatabaseUnavailableException) throw error
                null
            }
        if (venue == null || config == null) {
            call.respondText(
                """
                <!doctype html><html lang="ru"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Заведение недоступно</title></head><body><main>
                <h1>Заведение недоступно</h1><p>Проверьте ссылку или попробуйте позже.</p>
                </main></body></html>
                """.trimIndent(),
                ContentType.Text.Html,
                HttpStatusCode.NotFound,
            )
        } else {
            val address =
                venue.formattedAddress?.takeIf(String::isNotBlank)
                    ?: listOfNotNull(venue.city, venue.address).joinToString(", ").ifBlank { "Адрес пока не указан" }
            val target = html(config.telegramUrl(venue.id))
            call.respondText(
                """
                <!doctype html><html lang="ru"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta http-equiv="refresh" content="1;url=$target"><title>${html(venue.name)}</title>
                <style>
                body { font: 18px system-ui; background: #f6f5f2; color: #212121; padding: 24px; }
                main { max-width: 440px; margin: 12vh auto; } h1 { overflow-wrap: anywhere; }
                a { display: inline-block; padding: 12px 0; color: #245c52; }
                </style></head><body><main><h1>${html(venue.name)}</h1><p>${html(address)}</p>
                <p><a href="$target">Открыть в Telegram</a></p>
                <p>Если приложение не открылось:
                <a href="${html(config.telegramUrl(venue.id, false))}">Открыть через чат бота</a></p>
                </main></body></html>
                """.trimIndent(),
                ContentType.Text.Html,
            )
        }
    }
}

fun Route.venuePublicLinkApiRoutes(
    links: VenuePublicLinksRepository,
    venues: GuestVenueRepository,
    subscriptions: SubscriptionRepository,
    config: VenuePublicLinkConfig?,
    maintenanceActive: Boolean = false,
) {
    get("/guest/venue-entry/{payload}") {
        call.requireUserId()
        val id = parseVenuePayload(call.parameters["payload"]) ?: throw NotFoundException()
        ensureGuestBrowseAvailableReadOnly(id, venues, subscriptions)
        call.respond(mapOf("venueId" to id))
    }
    get("/venue/{venueId}/public-link") {
        val id = call.parameters["venueId"]?.toLongOrNull() ?: throw NotFoundException()
        val settings = links.settings(id, call.requireUserId())
        val available = !maintenanceActive && isPublicLinkGuestAvailable(id, venues, subscriptions)
        call.respond(settings.copy(guestAvailable = available, url = settings.slug?.let { config?.publicUrl(it) }))
    }
    put("/venue/{venueId}/public-link") {
        val id = call.parameters["venueId"]?.toLongOrNull() ?: throw NotFoundException()
        val settings = links.update(id, call.requireUserId(), call.receive())
        val available = !maintenanceActive && isPublicLinkGuestAvailable(id, venues, subscriptions)
        call.respond(settings.copy(guestAvailable = available, url = settings.slug?.let { config?.publicUrl(it) }))
    }
}

private suspend fun isPublicLinkGuestAvailable(
    id: Long,
    venues: GuestVenueRepository,
    subscriptions: SubscriptionRepository,
): Boolean =
    try {
        ensureGuestBrowseAvailableReadOnly(id, venues, subscriptions)
        true
    } catch (_: NotFoundException) {
        false
    } catch (_: DatabaseUnavailableException) {
        false
    }

private fun html(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
