package com.hookah.platform.backend.telegram

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun buildWebAppUrl(
    baseUrl: String,
    params: Map<String, String>,
): String {
    if (params.isEmpty()) return baseUrl
    val (pathWithQuery, fragment) =
        baseUrl.split("#", limit = 2).let { parts ->
            parts.first() to parts.getOrNull(1)
        }
    val prefix = if (pathWithQuery.contains("?")) "&" else "?"
    val encodedParams =
        params.map { (key, value) ->
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8)
            "$encodedKey=$encodedValue"
        }.joinToString("&")
    val baseWithParams = pathWithQuery + prefix + encodedParams
    return if (fragment == null) baseWithParams else baseWithParams + "#" + fragment
}

fun buildTelegramStartUrl(
    botUsername: String,
    startParam: String,
): String {
    val username = botUsername.trim().removePrefix("@")
    val encodedParam = URLEncoder.encode(startParam, StandardCharsets.UTF_8)
    return "https://t.me/$username?start=$encodedParam"
}

/** A global entry must not inherit a role, table, or screen from the configured base URL. */
fun buildHomeWebAppUrl(
    baseUrl: String?,
    enabled: Boolean = true,
): String? {
    if (!enabled || baseUrl.isNullOrBlank()) return null
    return runCatching {
        val uri = URI(baseUrl.trim())
        if (
            !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0
        ) {
            return null
        }
        val navigationKeys =
            setOf(
                "mode", "screen", "venueId", "venue_id", "tableToken", "table_token",
                "tableSessionId", "table_session_id", "tgWebAppStartParam", "startapp", "start_param", "entry",
            )
        val query =
            uri.rawQuery?.split("&")?.filter { part ->
                URLDecoder.decode(part.substringBefore("="), StandardCharsets.UTF_8) !in navigationKeys
            }?.filter { it.isNotBlank() }?.joinToString("&")
        val base = "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.orEmpty()}"
        buildWebAppUrl(base + if (query.isNullOrBlank()) "" else "?$query", mapOf("mode" to "home"))
    }.getOrNull()
}
