package com.hookah.platform.backend.test

import com.hookah.platform.backend.api.ApiErrorEnvelope
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val REQUEST_ID_HEADER = "X-Request-Id"
private val json = Json { ignoreUnknownKeys = true }

suspend fun assertApiErrorEnvelope(response: HttpResponse, expectedCode: String): ApiErrorEnvelope {
    val body = response.bodyAsText()
    val envelope = json.decodeFromString<ApiErrorEnvelope>(body)

    assertTrue(!envelope.requestId.isNullOrBlank(), "requestId must be present in API error envelope")
    assertEquals(expectedCode, envelope.error.code)
    assertTrue(envelope.error.message.isNotBlank(), "error message must be present in API error envelope")

    val headerRequestId = response.headers[REQUEST_ID_HEADER]
    assertTrue(!headerRequestId.isNullOrBlank(), "$REQUEST_ID_HEADER header must be present in API error envelope")
    assertEquals(envelope.requestId, headerRequestId)

    val contentType = response.headers["Content-Type"]
    assertTrue(!contentType.isNullOrBlank(), "Content-Type header must be present in API error envelope")
    assertTrue(
        contentType.contains("application/json", ignoreCase = true),
        "Content-Type must include application/json in API error envelope"
    )

    return envelope
}
