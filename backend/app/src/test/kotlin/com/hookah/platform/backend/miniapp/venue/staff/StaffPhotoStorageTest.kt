package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.StaffPhotoUnavailableException
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.MiniAppRateLimitDecision
import com.sun.net.httpserver.HttpServer
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StaffPhotoStorageTest {
    @Test fun `explicit staging config fails closed on missing foreign public or production settings`() {
        fun config(vararg overrides: Pair<String, String>): MapApplicationConfig {
            val values =
                mutableMapOf(
                    "enabled" to "true",
                    "endpoint" to "https://${"a".repeat(32)}.eu.r2.cloudflarestorage.com",
                    "bucket" to STAFF_PHOTO_STAGING_BUCKET,
                    "accessKey" to "synthetic-access",
                    "secretKey" to "synthetic-secret",
                    "region" to "auto",
                    "jurisdiction" to "eu",
                )
            values.putAll(overrides)
            return MapApplicationConfig(*values.map { "venue.staffPhotos.${it.key}" to it.value }.toTypedArray())
        }
        assertTrue(StaffPhotoStorageConfig.from(config(), "staging").enabled)
        assertFalse(StaffPhotoStorageConfig.from(config(), "production").enabled)
        for (change in listOf(
            "secretKey" to "",
            "enabled" to "false",
            "bucket" to "unapproved-bucket",
            "region" to "us-east-1",
            "jurisdiction" to "default",
            "endpoint" to "https://example.r2.dev",
            "endpoint" to "http://${"a".repeat(32)}.eu.r2.cloudflarestorage.com",
        )) {
            assertFalse(StaffPhotoStorageConfig.from(config(change), "staging").enabled)
        }
        assertFalse(StaffPhotoStorageConfig.from(MapApplicationConfig(), "test").enabled)
        assertFalse(StaffPhotoStorageConfig.from(config(), "staging").toString().contains("synthetic"))
    }

    @Test fun `SDK signed PUT GET HEAD DELETE use isolated keys bounded body and safe errors`() =
        runBlocking<Unit> {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val key = "staff-photos/${UUID.randomUUID()}.jpg"
            val body = syntheticPhoto("jpeg")
            var stored: ByteArray? = null
            var fail = false
            val methods = mutableListOf<String>()
            server.createContext("/") { exchange ->
                methods.add(exchange.requestMethod)
                assertEquals("/$STAFF_PHOTO_STAGING_BUCKET/$key", exchange.requestURI.path)
                assertTrue(exchange.requestHeaders.getFirst("Authorization").startsWith("AWS4-HMAC-SHA256"))
                if (fail) {
                    val error =
                        (
                            "<Error><Code>AccessDenied</Code>" +
                                "<Message>provider-private-details</Message></Error>"
                        ).toByteArray()
                    exchange.sendResponseHeaders(403, error.size.toLong())
                    exchange.responseBody.use { it.write(error) }
                } else {
                    when (exchange.requestMethod) {
                        "PUT" -> {
                            assertEquals("STANDARD", exchange.requestHeaders.getFirst("x-amz-storage-class"))
                            assertEquals("image/jpeg", exchange.requestHeaders.getFirst("Content-Type"))
                            stored = exchange.requestBody.readBytes()
                            exchange.sendResponseHeaders(200, -1)
                        }
                        "GET" -> {
                            val data = stored!!
                            exchange.sendResponseHeaders(200, data.size.toLong())
                            exchange.responseBody.use { it.write(data) }
                        }
                        "HEAD" -> {
                            exchange.responseHeaders.add("Content-Length", (stored?.size ?: 0).toString())
                            exchange.sendResponseHeaders(if (stored == null) 404 else 200, -1)
                        }
                        "DELETE" -> {
                            stored = null
                            exchange.sendResponseHeaders(204, -1)
                        }
                    }
                }
                exchange.close()
            }
            server.start()
            try {
                R2StaffPhotoStorage(
                    URI("http://127.0.0.1:${server.address.port}"),
                    STAFF_PHOTO_STAGING_BUCKET,
                    "synthetic-access",
                    "synthetic-secret",
                ).use {
                        storage ->
                    storage.put(key, body)
                    assertContentEquals(body, storage.get(key))
                    assertTrue(storage.exists(key))
                    storage.delete(key)
                    assertFalse(storage.exists(key))
                    fail = true
                    val error = assertFailsWith<StaffPhotoUnavailableException> { storage.put(key, body) }
                    assertFalse(error.toString().contains("provider-private-details"))
                    assertFalse(error.toString().contains("synthetic-secret"))
                    assertFailsWith<IllegalArgumentException> { storage.delete("../foreign") }
                }
                assertEquals(listOf("PUT", "GET", "HEAD", "DELETE", "HEAD", "PUT"), methods)
            } finally {
                server.stop(0)
            }
        }

    @Test fun `upload exhaustion does not consume privacy withdrawal quota`() {
        val abuse = MiniAppAbuseProtection()
        repeat(120) { abuse.tryStaffPhotoUpload((it / 30).toLong(), 1) }
        assertIs<MiniAppRateLimitDecision.Denied>(abuse.tryStaffPhotoUpload(999, 1))
        assertIs<MiniAppRateLimitDecision.Allowed>(abuse.tryStaffPhotoPrivacy(999))
    }
}
