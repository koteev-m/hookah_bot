package com.hookah.platform.backend.telegram

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
