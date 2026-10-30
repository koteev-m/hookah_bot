package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import com.hookah.platform.backend.miniapp.venue.staff.STAFF_PHOTO_STAGING_BUCKET
import com.hookah.platform.backend.miniapp.venue.staff.StaffPhotoStorageConfig
import com.hookah.platform.backend.miniapp.venue.staff.privateR2Client
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
import kotlin.test.assertTrue

class VenueInfoMediaStorageTest {
    @Test fun `independent opt in uses existing private staging connection and production remains disabled`() {
        val config =
            MapApplicationConfig(
                "venue.infoMedia.enabled" to "true",
                "venue.staffPhotos.enabled" to "false",
                "venue.staffPhotos.endpoint" to "https://${"a".repeat(32)}.eu.r2.cloudflarestorage.com",
                "venue.staffPhotos.bucket" to STAFF_PHOTO_STAGING_BUCKET,
                "venue.staffPhotos.accessKey" to "synthetic-access",
                "venue.staffPhotos.secretKey" to "synthetic-secret",
                "venue.staffPhotos.region" to "auto",
                "venue.staffPhotos.jurisdiction" to "eu",
            )
        assertFalse(StaffPhotoStorageConfig.from(config, "staging").enabled)
        assertTrue(StaffPhotoStorageConfig.from(config, "staging", "venue.infoMedia.enabled").enabled)
        assertFalse(StaffPhotoStorageConfig.from(config, "production", "venue.infoMedia.enabled").enabled)
        assertFalse(StaffPhotoStorageConfig.from(MapApplicationConfig(), "staging", "venue.infoMedia.enabled").enabled)
    }

    @Test fun `media upload exhaustion leaves media deletion and staff privacy independent`() {
        val abuse = com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection()
        repeat(5) { abuse.tryInfoMediaUpload(101, 1) }
        assertTrue(
            abuse.tryInfoMediaUpload(
                101,
                1,
            ) is com.hookah.platform.backend.miniapp.security.MiniAppRateLimitDecision.Denied,
        )
        assertTrue(
            abuse.tryInfoMediaPrivacy(
                101,
            ) is com.hookah.platform.backend.miniapp.security.MiniAppRateLimitDecision.Allowed,
        )
        assertTrue(
            abuse.tryStaffPhotoPrivacy(
                101,
            ) is com.hookah.platform.backend.miniapp.security.MiniAppRateLimitDecision.Allowed,
        )
    }

    @Test fun `SDK sends normalized MIME Standard private keys and confirms deletion without provider leaks`() =
        runBlocking<Unit> {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val objects = mutableMapOf<String, ByteArray>()
            var expected = "application/pdf"
            var fail = false
            server.createContext("/") { exchange ->
                assertTrue(exchange.requestURI.path.startsWith("/$STAFF_PHOTO_STAGING_BUCKET/venue-info/"))
                assertTrue(exchange.requestHeaders.getFirst("Authorization").startsWith("AWS4-HMAC-SHA256"))
                if (fail) {
                    val error =
                        (
                            "<Error><Code>AccessDenied</Code>" +
                                "<Message>private-provider-details</Message></Error>"
                        ).toByteArray()
                    exchange.sendResponseHeaders(403, error.size.toLong())
                    exchange.responseBody.use { it.write(error) }
                } else {
                    when (exchange.requestMethod) {
                        "PUT" -> {
                            assertEquals(expected, exchange.requestHeaders.getFirst("Content-Type"))
                            assertEquals("STANDARD", exchange.requestHeaders.getFirst("x-amz-storage-class"))
                            objects[exchange.requestURI.path] = exchange.requestBody.readBytes()
                            exchange.sendResponseHeaders(200, -1)
                        }
                        "GET" -> {
                            val bytes = objects.getValue(exchange.requestURI.path)
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.use { it.write(bytes) }
                        }
                        "DELETE" -> {
                            objects.remove(exchange.requestURI.path)
                            exchange.sendResponseHeaders(204, -1)
                        }
                        "HEAD" ->
                            exchange.sendResponseHeaders(
                                if (objects.containsKey(exchange.requestURI.path)) 200 else 404,
                                -1,
                            )
                    }
                }
                exchange.close()
            }
            server.start()
            try {
                R2VenueInfoMediaStorage(
                    privateR2Client(
                        URI("http://127.0.0.1:${server.address.port}"),
                        "synthetic-access",
                        "synthetic-secret",
                    ),
                    STAFF_PHOTO_STAGING_BUCKET,
                ).use {
                        storage ->
                    for (mime in listOf("image/jpeg", "image/png", "application/pdf")) {
                        expected = mime
                        val key = "venue-info/${UUID.randomUUID()}.${if (mime == "application/pdf") {
                            "pdf"
                        } else if (mime == "image/png") {
                            "png"
                        } else {
                            "jpg"
                        }}"
                        val file = ValidatedVenueInfoMedia("synthetic-$mime".toByteArray(), mime)
                        storage.put(key, file)
                        assertContentEquals(file.bytes, storage.get(key))
                        assertTrue(storage.exists(key))
                        storage.delete(key)
                        assertFalse(storage.exists(key))
                    }
                    fail = true
                    val error =
                        assertFailsWith<VenueInfoMediaUnavailableException> {
                            storage.get(
                                "venue-info/${UUID.randomUUID()}.pdf",
                            )
                        }
                    assertFalse(error.toString().contains("private-provider-details"))
                    assertFailsWith<IllegalArgumentException> {
                        storage.delete(
                            "staff-photos/${UUID.randomUUID()}.jpg",
                        )
                    }
                }
            } finally {
                server.stop(0)
            }
        }
}
