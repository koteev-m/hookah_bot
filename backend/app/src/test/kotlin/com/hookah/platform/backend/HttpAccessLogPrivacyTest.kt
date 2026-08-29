package com.hookah.platform.backend

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.api.ApiHeaders
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HttpAccessLogPrivacyTest {
    @Test
    fun `product access logs use safe categories and canonical request ids only`() {
        val accessLogger = LoggerFactory.getLogger("HttpAccess") as Logger
        val previousLevel = accessLogger.level
        val previousAdditive = accessLogger.isAdditive
        val accessAppender = PreparedListAppender().apply { start() }
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val previousRootLevel = rootLogger.level
        val rootAppender = PreparedListAppender().apply { start() }
        val testEngineLogger = LoggerFactory.getLogger("io.ktor.test") as Logger
        val previousTestEngineLevel = testEngineLogger.level
        accessLogger.level = Level.TRACE
        accessLogger.isAdditive = false
        accessLogger.addAppender(accessAppender)
        rootLogger.level = Level.TRACE
        rootLogger.addAppender(rootAppender)
        // Test-host request summaries are not part of the production logger graph captured below.
        testEngineLogger.level = Level.ERROR
        try {
            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.env" to "test",
                            "db.jdbcUrl" to "",
                            "api.session.jwtSecret" to "test-session-secret",
                            "api.session.issuer" to "test-issuer",
                            "api.session.audience" to "test-audience",
                            "telegram.enabled" to "false",
                            "telegram.trafficPolicy" to "PRODUCT",
                            "venue.staffInviteSecretPepper" to "test-product-invite-pepper",
                        )
                }
                application { module() }

                val sensitiveResponse =
                    client.post(
                        "/api/venue/$TELEGRAM_ID_SENTINEL/staff/invites/$INVITE_TOKEN_SENTINEL/revoke",
                    ) {
                        header(ApiHeaders.REQUEST_ID, MALICIOUS_REQUEST_ID)
                        url {
                            parameters.append("initData", INIT_DATA_SENTINEL)
                            parameters.append("privateName", PRIVATE_NAME_SENTINEL)
                        }
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"inviteToken":"$INVITE_TOKEN_SENTINEL","message":"$MESSAGE_SENTINEL"}""",
                        )
                    }

                assertEquals(HttpStatusCode.Unauthorized, sensitiveResponse.status)
                val generatedRequestId = sensitiveResponse.headers[ApiHeaders.REQUEST_ID].orEmpty()
                assertTrue(isCanonicalRequestId(generatedRequestId))
                assertNotEquals(MALICIOUS_REQUEST_ID, generatedRequestId)

                val canonicalResponse =
                    client.get("/health") {
                        header(ApiHeaders.REQUEST_ID, CANONICAL_REQUEST_ID)
                    }
                assertEquals(HttpStatusCode.OK, canonicalResponse.status)
                assertEquals(CANONICAL_REQUEST_ID, canonicalResponse.headers[ApiHeaders.REQUEST_ID])

                val accessEvents = accessAppender.list.toList()
                assertTrue(
                    accessEvents.any {
                        it.formattedMessage == "HTTP access method=POST status=401 category=VENUE_API"
                    },
                )
                assertTrue(
                    accessEvents.any {
                        it.formattedMessage == "HTTP access method=GET status=200 category=HEALTH"
                    },
                )
                accessEvents.forEach { event ->
                    assertTrue(isCanonicalRequestId(event.mdcPropertyMap["requestId"].orEmpty()))
                }
                val sentinels =
                    listOf(
                        TELEGRAM_ID_SENTINEL,
                        INVITE_TOKEN_SENTINEL,
                        INIT_DATA_SENTINEL,
                        INIT_DATA_QUERY_SENTINEL,
                        INIT_DATA_USER_SENTINEL,
                        INIT_DATA_HASH_SENTINEL,
                        PRIVATE_NAME_SENTINEL,
                        MESSAGE_SENTINEL,
                        MALICIOUS_REQUEST_ID,
                    )
                (accessEvents + rootAppender.list).forEach { event ->
                    val privacySurface = event.privacySurface()
                    sentinels.forEach { sentinel ->
                        assertFalse(privacySurface.contains(sentinel), "access log exposed a protected sentinel")
                    }
                    val requestId = event.mdcPropertyMap["requestId"]
                    if (requestId != null) {
                        assertTrue(isCanonicalRequestId(requestId))
                    }
                }
            }
        } finally {
            accessLogger.detachAppender(accessAppender)
            accessLogger.level = previousLevel
            accessLogger.isAdditive = previousAdditive
            accessAppender.stop()
            rootLogger.detachAppender(rootAppender)
            rootLogger.level = previousRootLevel
            rootAppender.stop()
            testEngineLogger.level = previousTestEngineLevel
        }
    }

    private fun ILoggingEvent.privacySurface(): String =
        buildString {
            append(message)
            append('\n')
            append(formattedMessage)
            append('\n')
            append(argumentArray?.joinToString(separator = "|").orEmpty())
            append('\n')
            append(mdcPropertyMap.entries.joinToString(separator = "|") { "${it.key}=${it.value}" })
            append('\n')
            append(throwableProxy?.message.orEmpty())
        }

    private class PreparedListAppender : ListAppender<ILoggingEvent>() {
        override fun append(eventObject: ILoggingEvent) {
            eventObject.prepareForDeferredProcessing()
            super.append(eventObject)
        }
    }

    private companion object {
        const val TELEGRAM_ID_SENTINEL = "711111111111111111"
        const val INVITE_TOKEN_SENTINEL = "staff_invite_SECRET_TOKEN_SENTINEL"
        const val INIT_DATA_QUERY_SENTINEL = "SECRET_QUERY_SENTINEL"
        const val INIT_DATA_USER_SENTINEL = "SECRET_USER_SENTINEL"
        const val INIT_DATA_HASH_SENTINEL = "SECRET_HASH_SENTINEL"
        const val INIT_DATA_SENTINEL =
            "query_id=$INIT_DATA_QUERY_SENTINEL&user=$INIT_DATA_USER_SENTINEL&hash=$INIT_DATA_HASH_SENTINEL"
        const val PRIVATE_NAME_SENTINEL = "SecretPrivateNameSentinel"
        const val MESSAGE_SENTINEL = "SECRET_PRIVATE_MESSAGE_BODY"
        const val MALICIOUS_REQUEST_ID = "malicious-request-$TELEGRAM_ID_SENTINEL-$INVITE_TOKEN_SENTINEL"
        const val CANONICAL_REQUEST_ID = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
