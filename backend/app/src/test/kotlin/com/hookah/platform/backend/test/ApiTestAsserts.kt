package com.hookah.platform.backend.test

import com.hookah.platform.backend.api.ApiErrorEnvelope
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

suspend fun assertApiErrorEnvelope(response: HttpResponse, expectedCode: String): ApiErrorEnvelope {
    val body = response.bodyAsText()
    val envelope = Json { ignoreUnknownKeys = true }.decodeFromString<ApiErrorEnvelope>(body)

    assertTrue(!envelope.requestId.isNullOrBlank(), "requestId must be present in API error envelope")
    assertEquals(expectedCode, envelope.error.code)
    assertTrue(envelope.error.message.isNotBlank(), "error message must be present in API error envelope")

    val headerRequestId = response.headers["X-Request-Id"]
    assertTrue(!headerRequestId.isNullOrBlank(), "X-Request-Id header must be present in API error envelope")
    assertEquals(envelope.requestId, headerRequestId)

    return envelope
}
