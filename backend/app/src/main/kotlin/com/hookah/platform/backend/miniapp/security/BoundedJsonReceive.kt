package com.hookah.platform.backend.miniapp.security

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.RequestBodyTooLargeException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal suspend inline fun <reified T> ApplicationCall.receiveBoundedJson(
    json: Json,
    maxBytes: Int,
): T {
    require(maxBytes > 0) { "maxBytes must be positive" }
    request.headers[HttpHeaders.ContentLength]
        ?.toLongOrNull()
        ?.let { contentLength ->
            if (contentLength > maxBytes) {
                throw RequestBodyTooLargeException()
            }
        }
    val bytes =
        receiveChannel()
            .readRemaining(maxBytes.toLong() + 1L)
            .readByteArray()
    if (bytes.size > maxBytes) {
        throw RequestBodyTooLargeException()
    }
    return try {
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (_: SerializationException) {
        throw InvalidInputException("Invalid request body")
    } catch (_: IllegalArgumentException) {
        throw InvalidInputException("Invalid request body")
    }
}
