package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.ensureGuestBrowseAvailableReadOnly
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.requireUserId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import java.util.Locale

data class VenuePublicLinkConfig(
    val origin: String,
    val botUsername: String,
    val mainMiniAppConfigured: Boolean,
    val canResolveSubdomains: Boolean = false,
    private val issueSubdomains: Boolean = false,
) {
    fun publicUrl(
        venueId: Long,
        slug: String,
    ): String =
        if (issueSubdomains) {
            "https://${VenueSlugs.dnsLabel(slug, venueId)}.$VENUE_ZONE"
        } else {
            "$origin/v/$slug"
        }

    fun labelFromHost(authority: String?): String? {
        if (!canResolveSubdomains || authority == null || authority.any { it.code !in 33..126 }) return null
        val host = authority.lowercase(Locale.ROOT)
        val suffix = ".$VENUE_ZONE"
        if (!host.endsWith(suffix)) return null
        return host.removeSuffix(suffix).takeIf {
            VenueSlugs.validSubdomain(it) || VenueSlugs.legacyDnsVenueId(it) != null
        }
    }

    fun telegramUrl(
        venueId: Long,
        main: Boolean = mainMiniAppConfigured,
    ): String = "https://t.me/$botUsername?${if (main) "startapp" else "start"}=${venuePayload(venueId)}"

    companion object {
        private const val VENUE_ZONE = "hookahtootah.club"
        private const val STAGING_BRANDED_ORIGIN = "https://$VENUE_ZONE"
        private const val STAGING_MINIAPP_ORIGIN = "https://staging.$VENUE_ZONE"

        fun from(
            webAppUrl: String?,
            username: String?,
            mainMiniAppConfigured: Boolean = false,
            publicOrigin: String? = null,
            appEnv: String = "dev",
            publicLinkFormat: String? = null,
        ): VenuePublicLinkConfig? {
            require(publicLinkFormat == null || publicLinkFormat == "path" || publicLinkFormat == "subdomain") {
                "VENUE_PUBLIC_LINK_FORMAT must be path or subdomain."
            }
            val brandedOrigin =
                publicOrigin?.takeIf(String::isNotEmpty)?.let { value ->
                    require(appEnv == "staging" || appEnv == "prod" || appEnv == "production") {
                        "VENUE_PUBLIC_LINK_ORIGIN is only supported in staging and production."
                    }
                    require(appEnv != "staging" || value == STAGING_BRANDED_ORIGIN) {
                        "Staging VENUE_PUBLIC_LINK_ORIGIN must be $STAGING_BRANDED_ORIGIN."
                    }
                    requireNotNull(originOf(value, strict = true)) {
                        "VENUE_PUBLIC_LINK_ORIGIN must be an HTTPS origin without a path, query or fragment."
                    }
                }
            val miniAppOrigin = webAppUrl?.let { originOf(it, strict = false) }
            val canResolveSubdomains = appEnv == "staging" && miniAppOrigin == STAGING_MINIAPP_ORIGIN
            val issueSubdomains = publicLinkFormat == "subdomain"
            require(!issueSubdomains || canResolveSubdomains) {
                "Subdomain links require Hookah staging and its existing Mini App origin."
            }
            if (miniAppOrigin == null) return null
            val bot = username?.removePrefix("@")?.takeIf { Regex("[A-Za-z0-9_]{5,32}").matches(it) } ?: return null
            return VenuePublicLinkConfig(
                brandedOrigin ?: miniAppOrigin,
                bot,
                mainMiniAppConfigured,
                canResolveSubdomains,
                issueSubdomains,
            )
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
    suspend fun ApplicationCall.entry(resolveVenueId: suspend () -> Long?) {
        response.header("Cache-Control", "no-store")
        response.header("X-Content-Type-Options", "nosniff")
        response.header("Referrer-Policy", "no-referrer")
        response.header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; " +
                "frame-ancestors 'none'; form-action 'none'",
        )
        val venue =
            try {
                if (maintenanceActive) throw NotFoundException()
                val id = resolveVenueId() ?: throw NotFoundException()
                ensureGuestBrowseAvailableReadOnly(id, venues, subscriptions)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error !is NotFoundException && error !is DatabaseUnavailableException) throw error
                null
            }
        if (venue == null || config == null) {
            respondText(
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
            respondText(
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

    get("/v/{slug}") {
        call.entry { call.parameters["slug"]?.let { links.resolveSlug(it) } }
    }
    if (config?.canResolveSubdomains == true) {
        get("/") {
            // The edge preserves Host. Forwarded values and query parameters cannot select a venue.
            val host = call.request.headers.getAll(HttpHeaders.Host)?.singleOrNull()
            call.entry { config.labelFromHost(host)?.let { links.resolveSubdomain(it) } }
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
        val url = settings.slug?.let { config?.publicUrl(settings.venueId, it) }
        call.respond(settings.copy(guestAvailable = available, url = url))
    }
    put("/venue/{venueId}/public-link") {
        val id = call.parameters["venueId"]?.toLongOrNull() ?: throw NotFoundException()
        val settings = links.update(id, call.requireUserId(), call.receive())
        val available = !maintenanceActive && isPublicLinkGuestAvailable(id, venues, subscriptions)
        val url = settings.slug?.let { config?.publicUrl(settings.venueId, it) }
        call.respond(settings.copy(guestAvailable = available, url = url))
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
