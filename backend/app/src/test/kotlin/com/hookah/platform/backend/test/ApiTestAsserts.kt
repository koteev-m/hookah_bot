package com.hookah.platform.backend.test

import com.hookah.platform.backend.api.ApiErrorEnvelope
import com.hookah.platform.backend.api.ApiHeaders
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true }

suspend fun assertApiErrorEnvelope(response: HttpResponse, expectedCode: String): ApiErrorEnvelope {
    val body = response.bodyAsText()
    val envelope = json.decodeFromString<ApiErrorEnvelope>(body)

    assertTrue(!envelope.requestId.isNullOrBlank(), "requestId must be present in API error envelope")
    assertEquals(expectedCode, envelope.error.code)
    assertTrue(envelope.error.message.isNotBlank(), "error message must be present in API error envelope")

    val headerRequestId = response.headers[ApiHeaders.REQUEST_ID]
    assertTrue(
        !headerRequestId.isNullOrBlank(),
        "${ApiHeaders.REQUEST_ID} header must be present in API error envelope"
    )
    assertEquals(envelope.requestId, headerRequestId)

    val contentTypeHeader = response.headers[HttpHeaders.ContentType]
    assertTrue(
        !contentTypeHeader.isNullOrBlank(),
        "${HttpHeaders.ContentType} header must be present in API error envelope"
    )

    val parsed = runCatching { ContentType.parse(contentTypeHeader) }.getOrNull()
    assertTrue(
        parsed?.match(ContentType.Application.Json) == true,
        "Content-Type must be application/json (got: $contentTypeHeader)"
    )

    return envelope
}
