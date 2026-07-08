package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.CatalogResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionsResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestVenueRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `catalog without auth returns unauthorized`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to "",
                    )
            }

            application { module() }

            val response = client.get("/api/guest/catalog")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `catalog returns only published venues`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-catalog")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venues = seedVenues(jdbcUrl)
            val token = issueToken(config)

            val response =
                client.get("/api/guest/catalog") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(CatalogResponse.serializer(), response.bodyAsText())
            assertEquals(1, payload.venues.size)
            assertEquals(venues.publishedId, payload.venues.first().id)
            assertEquals(false, payload.venues.any { it.id == venues.deletedId })
        }

    @Test
    fun `catalog hides blocked subscriptions`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-catalog-blocked")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val publishedId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Open", "City", "Address", VenueStatus.PUBLISHED.dbValue)
                }
            val blockedId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Blocked", "City", "Address", VenueStatus.PUBLISHED.dbValue)
                }
            seedSubscription(jdbcUrl, publishedId, "ACTIVE")
            seedSubscription(jdbcUrl, blockedId, "SUSPENDED_BY_PLATFORM")
            val token = issueToken(config)

            val response =
                client.get("/api/guest/catalog") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(CatalogResponse.serializer(), response.bodyAsText())
            assertEquals(listOf(publishedId), payload.venues.map { it.id })
        }

    @Test
    fun `catalog hides blocked subscriptions with lowercase status`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-catalog-blocked-lower")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val publishedId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Open", "City", "Address", VenueStatus.PUBLISHED.dbValue)
                }
            val blockedId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Blocked", "City", "Address", VenueStatus.PUBLISHED.dbValue)
                }
            dropSubscriptionStatusCheck(jdbcUrl)
            seedSubscription(jdbcUrl, publishedId, "active")
            seedSubscription(jdbcUrl, blockedId, "suspended_by_platform")
            val token = issueToken(config)

            val response =
                client.get("/api/guest/catalog") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(CatalogResponse.serializer(), response.bodyAsText())
            assertEquals(listOf(publishedId), payload.venues.map { it.id })
        }

    @Test
    fun `venue by id respects visibility rules`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venues = seedVenues(jdbcUrl)
            val token = issueToken(config)

            val publishedResponse =
                client.get("/api/guest/venue/${venues.publishedId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, publishedResponse.status)
            val publishedPayload = json.decodeFromString(VenueResponse.serializer(), publishedResponse.bodyAsText())
            assertEquals(VenueStatus.PUBLISHED.dbValue, publishedPayload.venue.status)

            val hiddenResponse =
                client.get("/api/guest/venue/${venues.hiddenId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, hiddenResponse.status)
            assertApiErrorEnvelope(hiddenResponse, ApiErrorCodes.NOT_FOUND)

            val suspendedResponse =
                client.get("/api/guest/venue/${venues.suspendedId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, suspendedResponse.status)
            assertApiErrorEnvelope(suspendedResponse, ApiErrorCodes.NOT_FOUND)

            val deletedResponse =
                client.get("/api/guest/venue/${venues.deletedId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, deletedResponse.status)
            assertApiErrorEnvelope(deletedResponse, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `venue by id with suspended subscription returns not found`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-blocked")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    insertVenue(connection, "Blocked", "City", "Address", VenueStatus.PUBLISHED.dbValue)
                }
            seedSubscription(jdbcUrl, venueId, "SUSPENDED_BY_PLATFORM")
            val token = issueToken(config)

            val response =
                client.get("/api/guest/venue/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `venue by id returns guest contact and card description`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-profile")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val id = insertVenue(connection, "Mix", "Москва", "Новый Арбат, 24", VenueStatus.PUBLISHED.dbValue)
                    connection.prepareStatement(
                        """
                        UPDATE venues
                        SET guest_contact = ?,
                            card_description = ?,
                            country_code = ?,
                            formatted_address = ?,
                            latitude = ?,
                            longitude = ?
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, "+7 999 000-00-00")
                        statement.setString(2, "Авторские чаши и спокойная посадка.")
                        statement.setString(3, "RU")
                        statement.setString(4, "Россия, Москва, Новый Арбат, 24")
                        statement.setDouble(5, 55.7522)
                        statement.setDouble(6, 37.6156)
                        statement.setLong(7, id)
                        statement.executeUpdate()
                    }
                    id
                }
            val token = issueToken(config)

            val response =
                client.get("/api/guest/venue/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueResponse.serializer(), response.bodyAsText())
            assertEquals("+7 999 000-00-00", payload.venue.guestContact)
            assertEquals("Авторские чаши и спокойная посадка.", payload.venue.cardDescription)
            assertEquals("Москва, Новый Арбат, 24", payload.venue.displayAddress)
            assertEquals("https://yandex.ru/maps/?rtext=~55.7522,37.6156&rtt=auto", payload.venue.routeUrl)
        }

    @Test
    fun `venue by id returns encoded text route without coordinates`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-text-route")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val id = insertVenue(connection, "Mix", "Казань", "Баумана, 7", VenueStatus.PUBLISHED.dbValue)
                    connection.prepareStatement(
                        """
                        UPDATE venues
                        SET country_code = ?
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, "RU")
                        statement.setLong(2, id)
                        statement.executeUpdate()
                    }
                    id
                }
            val token = issueToken(config)

            val response =
                client.get("/api/guest/venue/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueResponse.serializer(), response.bodyAsText())
            assertEquals("Казань, Баумана, 7", payload.venue.displayAddress)
            assertEquals(
                "https://yandex.ru/maps/?text=" +
                    URLEncoder.encode("Mix, Россия, Казань, Баумана, 7", StandardCharsets.UTF_8),
                payload.venue.routeUrl,
            )
        }

    @Test
    fun `catalog and venue by id expose safe today schedule`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-today-schedule")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val today = LocalDate.now(ZoneId.of("UTC"))
            val venueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val id = insertVenue(connection, "Mix", "Москва", "Новый Арбат, 24", VenueStatus.PUBLISHED.dbValue)
                    insertVenueTimezone(connection, id, "UTC")
                    insertWeeklyHours(
                        connection = connection,
                        venueId = id,
                        weekday = today.dayOfWeek.value,
                        opensAt = "00:00:00",
                        closesAt = "00:00:00",
                        isClosed = false,
                    )
                    id
                }
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val token = issueToken(config)

            val catalogResponse =
                client.get("/api/guest/catalog") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, catalogResponse.status)
            val catalogPayload = json.decodeFromString(CatalogResponse.serializer(), catalogResponse.bodyAsText())
            val catalogSchedule = catalogPayload.venues.single().todaySchedule
            check(catalogSchedule != null)
            assertEquals(today.toString(), catalogSchedule.date)
            assertEquals("Круглосуточно", catalogSchedule.timeLabel)
            assertEquals("Открыто сейчас", catalogSchedule.statusLabel)
            assertEquals(true, catalogSchedule.isConfigured)
            assertEquals(true, catalogSchedule.isOpenNow)

            val venueResponse =
                client.get("/api/guest/venue/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, venueResponse.status)
            val venuePayload = json.decodeFromString(VenueResponse.serializer(), venueResponse.bodyAsText())
            val venueSchedule = venuePayload.venue.todaySchedule
            check(venueSchedule != null)
            assertEquals(today.toString(), venueSchedule.date)
            assertEquals("00:00", venueSchedule.opensAt)
            assertEquals("00:00", venueSchedule.closesAt)
            assertEquals(true, venueSchedule.isConfigured)
            assertEquals(false, venueSchedule.isClosed)
        }

    @Test
    fun `catalog and venue by id expose unconfigured schedule without closed state`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-missing-schedule")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val id = insertVenue(connection, "Mix", "Москва", "Новый Арбат, 24", VenueStatus.PUBLISHED.dbValue)
                    insertVenueTimezone(connection, id, "UTC")
                    id
                }
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val token = issueToken(config)

            val catalogResponse =
                client.get("/api/guest/catalog") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, catalogResponse.status)
            val catalogPayload = json.decodeFromString(CatalogResponse.serializer(), catalogResponse.bodyAsText())
            val catalogSchedule = catalogPayload.venues.single().todaySchedule
            check(catalogSchedule != null)
            assertEquals("График не указан", catalogSchedule.statusLabel)
            assertEquals(false, catalogSchedule.isConfigured)
            assertEquals(false, catalogSchedule.isClosed)
            assertEquals(false, catalogSchedule.isOpenNow)
            assertEquals(null, catalogSchedule.timeLabel)

            val venueResponse =
                client.get("/api/guest/venue/$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, venueResponse.status)
            val venuePayload = json.decodeFromString(VenueResponse.serializer(), venueResponse.bodyAsText())
            val venueSchedule = venuePayload.venue.todaySchedule
            check(venueSchedule != null)
            assertEquals("График не указан", venueSchedule.statusLabel)
            assertEquals(false, venueSchedule.isConfigured)
            assertEquals(false, venueSchedule.isClosed)
            assertEquals(false, venueSchedule.isOpenNow)
            assertEquals(null, venueSchedule.timeLabel)
        }

    @Test
    fun `venue by id exposes only safe public today staff`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-today-staff")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val today = LocalDate.now(ZoneId.of("UTC"))
            val seeded =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val venueId =
                        insertVenue(
                            connection,
                            "Mix",
                            "Москва",
                            "Новый Арбат, 24",
                            VenueStatus.PUBLISHED.dbValue,
                        )
                    val otherVenueId =
                        insertVenue(
                            connection,
                            "Other",
                            "Москва",
                            "Тверская, 1",
                            VenueStatus.PUBLISHED.dbValue,
                        )
                    insertVenueTimezone(connection, venueId, "UTC")
                    insertVenueTimezone(connection, otherVenueId, "UTC")
                    seedUser(connection, 9101L)
                    seedUser(connection, 9102L)
                    val visibleProfile =
                        insertStaffProfile(
                            connection = connection,
                            venueId = venueId,
                            displayName = "Иван",
                            subtype = "hookah_master",
                            linkedUserId = 9102L,
                            isGuestVisible = true,
                            published = true,
                            disabled = false,
                            tags = """["крепко"]""",
                        )
                    val hiddenProfile =
                        insertStaffProfile(
                            connection = connection,
                            venueId = venueId,
                            displayName = "Скрыт",
                            subtype = "waiter",
                            isGuestVisible = false,
                            published = false,
                            disabled = false,
                        )
                    val canceledProfile =
                        insertStaffProfile(
                            connection = connection,
                            venueId = venueId,
                            displayName = "Завершил",
                            subtype = "admin",
                            isGuestVisible = true,
                            published = true,
                            disabled = false,
                        )
                    val privateShiftProfile =
                        insertStaffProfile(
                            connection = connection,
                            venueId = venueId,
                            displayName = "Приватная смена",
                            subtype = "other",
                            isGuestVisible = true,
                            published = true,
                            disabled = false,
                        )
                    val otherVenueProfile =
                        insertStaffProfile(
                            connection = connection,
                            venueId = otherVenueId,
                            displayName = "Чужой",
                            subtype = "other",
                            isGuestVisible = true,
                            published = true,
                            disabled = false,
                        )
                    insertStaffShift(connection, venueId, visibleProfile, today, "active", true)
                    insertStaffShift(connection, venueId, hiddenProfile, today, "active", true)
                    insertStaffShift(connection, venueId, canceledProfile, today, "canceled", true)
                    insertStaffShift(connection, venueId, privateShiftProfile, today, "active", false)
                    insertStaffShift(connection, otherVenueId, otherVenueProfile, today, "active", true)
                    SeededTodayStaff(venueId = venueId)
                }
            val token = issueToken(config)

            val response =
                client.get("/api/guest/venue/${seeded.venueId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertFalse(responseBody.contains("linkedUserId"))
            assertFalse(responseBody.contains("telegram", ignoreCase = true))
            val payload = json.decodeFromString(VenueResponse.serializer(), responseBody)
            assertEquals(listOf("Иван"), payload.venue.todayStaff.map { it.displayName })
            assertEquals("hookah_master", payload.venue.todayStaff.single().subtype)
            assertEquals(listOf("крепко"), payload.venue.todayStaff.single().tags)

            val endpointResponse =
                client.get("/api/guest/venue/${seeded.venueId}/today-staff") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, endpointResponse.status)
            assertFalse(endpointResponse.bodyAsText().contains("linkedUserId"))
            assertFalse(endpointResponse.bodyAsText().contains("telegram", ignoreCase = true))
        }

    @Test
    fun `venue info sections returns only visible filled sections with safe media metadata`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-info-sections")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val seeded =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val venueId =
                        insertVenue(connection, "Mix", "Москва", "Новый Арбат, 24", VenueStatus.PUBLISHED.dbValue)
                    val aboutId =
                        insertInfoSection(
                            connection = connection,
                            venueId = venueId,
                            title = "О заведении",
                            sectionType = "about",
                            sortOrder = 10,
                            isVisible = true,
                            textContent = "Авторские чаши",
                        )
                    insertInfoSection(
                        connection = connection,
                        venueId = venueId,
                        title = "Пустой раздел",
                        sectionType = "rules",
                        sortOrder = 20,
                        isVisible = true,
                        textContent = "   ",
                    )
                    insertInfoSection(
                        connection = connection,
                        venueId = venueId,
                        title = "Скрытый FAQ",
                        sectionType = "faq",
                        sortOrder = 30,
                        isVisible = false,
                        textContent = "Есть текст",
                    )
                    val menuId =
                        insertInfoSection(
                            connection = connection,
                            venueId = venueId,
                            title = "Меню",
                            sectionType = "menu",
                            sortOrder = 40,
                            isVisible = true,
                            textContent = null,
                        )
                    insertInfoMedia(connection, menuId, "pdf", "telegram-file-id", 0)
                    SeededInfoSections(venueId = venueId, aboutId = aboutId, menuId = menuId)
                }
            val token = issueToken(config)

            val response =
                client.get("/api/guest/venue/${seeded.venueId}/info-sections") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            val payload =
                json.decodeFromString(VenueInfoSectionsResponse.serializer(), responseBody)
            assertEquals(seeded.venueId, payload.venueId)
            assertEquals(listOf(seeded.aboutId, seeded.menuId), payload.sections.map { it.id })
            val about = payload.sections[0]
            assertEquals("О заведении", about.displayTitle)
            assertEquals("Авторские чаши", about.text)
            assertEquals(0, about.mediaCount)
            assertTrue(responseBody.contains("\"media\":[]"))
            val menu = payload.sections[1]
            assertEquals("menu", menu.type)
            assertEquals("📖 Фото-меню", menu.displayTitle)
            assertEquals(null, menu.text)
            assertEquals(1, menu.mediaCount)
            assertEquals("pdf", menu.media.single().mediaType)
            assertEquals(
                "/api/guest/venue/${seeded.venueId}/info-sections/${seeded.menuId}/media/${menu.media.single().id}",
                menu.media.single().url,
            )
        }

    @Test
    fun `venue info media endpoint streams visible image media without auth`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-info-media")
            val config = buildConfig(jdbcUrl)
            var requestedFileId: String? = null

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        telegramFileDownloader = { fileId ->
                            requestedFileId = fileId
                            TelegramDownloadedFile(
                                bytes = "image-bytes".toByteArray(),
                                contentType = ContentType.Image.PNG,
                            )
                        },
                    ),
                )
            }

            client.get("/health")

            val seeded =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val venueId =
                        insertVenue(connection, "Mix", "Москва", "Новый Арбат, 24", VenueStatus.PUBLISHED.dbValue)
                    val sectionId =
                        insertInfoSection(
                            connection = connection,
                            venueId = venueId,
                            title = "Фото",
                            sectionType = "custom",
                            sortOrder = 10,
                            isVisible = true,
                            textContent = null,
                        )
                    val mediaId = insertInfoMedia(connection, sectionId, "image", "telegram-image-file-id", 0)
                    SeededInfoMedia(venueId = venueId, sectionId = sectionId, mediaId = mediaId)
                }
            val response =
                client.get(
                    "/api/guest/venue/${seeded.venueId}/info-sections/${seeded.sectionId}/media/${seeded.mediaId}",
                )

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("telegram-image-file-id", requestedFileId)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
            assertEquals("image-bytes", response.bodyAsText())
        }

    @Test
    fun `venue info media endpoint rejects hidden section and media mismatch`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-venue-info-media-hidden")
            val config = buildConfig(jdbcUrl)
            var downloadAttempts = 0

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        telegramFileDownloader = {
                            downloadAttempts += 1
                            TelegramDownloadedFile(
                                bytes = "unexpected".toByteArray(),
                                contentType = ContentType.Text.Plain,
                            )
                        },
                    ),
                )
            }

            client.get("/health")

            val seeded =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    val venueId =
                        insertVenue(connection, "Mix", "Москва", "Новый Арбат, 24", VenueStatus.PUBLISHED.dbValue)
                    val visibleSectionId =
                        insertInfoSection(
                            connection = connection,
                            venueId = venueId,
                            title = "Фото",
                            sectionType = "custom",
                            sortOrder = 10,
                            isVisible = true,
                            textContent = null,
                        )
                    val hiddenSectionId =
                        insertInfoSection(
                            connection = connection,
                            venueId = venueId,
                            title = "Скрыто",
                            sectionType = "custom",
                            sortOrder = 20,
                            isVisible = false,
                            textContent = null,
                        )
                    val visibleMediaId = insertInfoMedia(connection, visibleSectionId, "image", "visible-file-id", 0)
                    val hiddenMediaId = insertInfoMedia(connection, hiddenSectionId, "image", "hidden-file-id", 0)
                    SeededHiddenInfoMedia(
                        venueId = venueId,
                        visibleSectionId = visibleSectionId,
                        visibleMediaId = visibleMediaId,
                        hiddenSectionId = hiddenSectionId,
                        hiddenMediaId = hiddenMediaId,
                    )
                }
            val hiddenResponse =
                client.get(
                    "/api/guest/venue/${seeded.venueId}/info-sections/${seeded.hiddenSectionId}" +
                        "/media/${seeded.hiddenMediaId}",
                )
            val mismatchResponse =
                client.get(
                    "/api/guest/venue/${seeded.venueId}/info-sections/${seeded.visibleSectionId}" +
                        "/media/${seeded.hiddenMediaId}",
                )

            assertEquals(HttpStatusCode.NotFound, hiddenResponse.status)
            assertEquals(HttpStatusCode.NotFound, mismatchResponse.status)
            assertEquals(0, downloadAttempts)
        }

    @Test
    fun `venue by id with unknown id returns not found`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-unknown")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venues = seedVenues(jdbcUrl)
            val token = issueToken(config)
            val missingId = maxOf(venues.publishedId, venues.hiddenId, venues.suspendedId) + 100

            val response =
                client.get("/api/guest/venue/$missingId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `venue by id without auth returns unauthorized`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "api.session.jwtSecret" to "test-secret",
                        "db.jdbcUrl" to "",
                    )
            }

            application { module() }

            val response = client.get("/api/guest/venue/1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `venue by id with invalid id returns invalid input`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val token = issueToken(config)

            val response =
                client.get("/api/guest/venue/not-a-number") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `catalog with db disabled returns database unavailable`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val token = issueToken(config)

            val response =
                client.get("/api/guest/catalog") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        val entries =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
            )
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenues(jdbcUrl: String): SeededVenues {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val publishedId = insertVenue(connection, "Published", "City", "Address", VenueStatus.PUBLISHED.dbValue)
            val hiddenId = insertVenue(connection, "Hidden", "City", "Address", VenueStatus.HIDDEN.dbValue)
            val suspendedId = insertVenue(connection, "Suspended", "City", "Address", VenueStatus.SUSPENDED.dbValue)
            val deletedId = insertVenue(connection, "Deleted", "City", "Address", VenueStatus.DELETED.dbValue)
            return SeededVenues(publishedId, hiddenId, suspendedId, deletedId)
        }
    }

    private fun insertVenue(
        connection: java.sql.Connection,
        name: String,
        city: String,
        address: String,
        status: String,
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, city)
            statement.setString(3, address)
            statement.setString(4, status)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun insertVenueTimezone(
        connection: java.sql.Connection,
        venueId: Long,
        timezone: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_settings (venue_id, timezone)
            VALUES (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, timezone)
            statement.executeUpdate()
        }
    }

    private fun insertWeeklyHours(
        connection: java.sql.Connection,
        venueId: Long,
        weekday: Int,
        opensAt: String,
        closesAt: String,
        isClosed: Boolean,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setInt(2, weekday)
            statement.setTime(3, java.sql.Time.valueOf(opensAt))
            statement.setTime(4, java.sql.Time.valueOf(closesAt))
            statement.setBoolean(5, isClosed)
            statement.executeUpdate()
        }
    }

    private fun insertInfoSection(
        connection: java.sql.Connection,
        venueId: Long,
        title: String,
        sectionType: String,
        sortOrder: Int,
        isVisible: Boolean,
        textContent: String?,
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO venue_info_sections (venue_id, title, section_type, sort_order, is_visible, text_content)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, title)
            statement.setString(3, sectionType)
            statement.setInt(4, sortOrder)
            statement.setBoolean(5, isVisible)
            statement.setString(6, textContent)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue info section")
    }

    private fun insertInfoMedia(
        connection: java.sql.Connection,
        sectionId: Long,
        mediaType: String,
        telegramFileId: String,
        sortOrder: Int,
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO venue_info_section_media (section_id, media_type, telegram_file_id, sort_order)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, sectionId)
            statement.setString(2, mediaType)
            statement.setString(3, telegramFileId)
            statement.setInt(4, sortOrder)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue info media")
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status)
                statement.executeUpdate()
            }
        }
    }

    private fun seedUser(
        connection: java.sql.Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO users (telegram_user_id, username, first_name, last_name)
            KEY (telegram_user_id)
            VALUES (?, 'staff', 'Staff', 'User')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeUpdate()
        }
    }

    private fun insertStaffProfile(
        connection: java.sql.Connection,
        venueId: Long,
        displayName: String,
        subtype: String,
        isGuestVisible: Boolean,
        published: Boolean,
        disabled: Boolean,
        linkedUserId: Long? = null,
        tags: String? = null,
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO staff_profiles (
                venue_id,
                linked_user_id,
                display_name,
                subtype,
                tags,
                is_guest_visible,
                created_by_user_id,
                published_at,
                disabled_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            if (linkedUserId == null) {
                statement.setNull(2, java.sql.Types.BIGINT)
            } else {
                statement.setLong(2, linkedUserId)
            }
            statement.setString(3, displayName)
            statement.setString(4, subtype)
            statement.setString(5, tags)
            statement.setBoolean(6, isGuestVisible)
            statement.setLong(7, 9101L)
            if (published) {
                statement.setTimestamp(8, java.sql.Timestamp.from(java.time.Instant.now()))
            } else {
                statement.setNull(8, java.sql.Types.TIMESTAMP)
            }
            if (disabled) {
                statement.setTimestamp(9, java.sql.Timestamp.from(java.time.Instant.now()))
            } else {
                statement.setNull(9, java.sql.Types.TIMESTAMP)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert staff profile")
    }

    private fun insertStaffShift(
        connection: java.sql.Connection,
        venueId: Long,
        profileId: Long,
        shiftDate: LocalDate,
        status: String,
        isGuestVisible: Boolean,
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO staff_shifts (
                venue_id,
                staff_profile_id,
                shift_date,
                status,
                is_guest_visible,
                manually_marked_active,
                created_by_user_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, profileId)
            statement.setDate(3, java.sql.Date.valueOf(shiftDate))
            statement.setString(4, status)
            statement.setBoolean(5, isGuestVisible)
            statement.setBoolean(6, status == "active")
            statement.setLong(7, 9101L)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return rs.getLong(1)
                }
            }
        }
        error("Failed to insert staff shift")
    }

    private fun dropSubscriptionStatusCheck(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE venue_subscriptions DROP CONSTRAINT IF EXISTS venue_subscriptions_status_check",
                )
            }
        }
    }

    private data class SeededVenues(
        val publishedId: Long,
        val hiddenId: Long,
        val suspendedId: Long,
        val deletedId: Long,
    )

    private data class SeededInfoSections(
        val venueId: Long,
        val aboutId: Long,
        val menuId: Long,
    )

    private data class SeededInfoMedia(
        val venueId: Long,
        val sectionId: Long,
        val mediaId: Long,
    )

    private data class SeededHiddenInfoMedia(
        val venueId: Long,
        val visibleSectionId: Long,
        val visibleMediaId: Long,
        val hiddenSectionId: Long,
        val hiddenMediaId: Long,
    )

    private data class SeededTodayStaff(
        val venueId: Long,
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 777L
    }
}
