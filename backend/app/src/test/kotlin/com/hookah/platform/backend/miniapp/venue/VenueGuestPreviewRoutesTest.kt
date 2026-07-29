package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.VenueInfoSectionsResponse
import com.hookah.platform.backend.miniapp.guest.api.VenueResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Time
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueGuestPreviewRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `owner and manager preview exactly matches published guest state`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-guest-preview-published")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val fixture = seedPublishedPreview(jdbcUrl)
            val token = issueToken(config, ACTOR_USER_ID)

            val guestVenueResponse =
                client.getAuthenticated("/api/guest/venue/${fixture.venueId}", token)
            val guestSectionsResponse =
                client.getAuthenticated("/api/guest/venue/${fixture.venueId}/info-sections", token)
            assertEquals(HttpStatusCode.OK, guestVenueResponse.status)
            assertEquals(HttpStatusCode.OK, guestSectionsResponse.status)
            val guestVenue =
                json.decodeFromString(VenueResponse.serializer(), guestVenueResponse.bodyAsText())
            val guestSections =
                json.decodeFromString(
                    VenueInfoSectionsResponse.serializer(),
                    guestSectionsResponse.bodyAsText(),
                )

            val ownerPreviewResponse =
                client.getAuthenticated("/api/venue/${fixture.venueId}/guest-preview", token)
            val ownerSectionsResponse =
                client.getAuthenticated(
                    "/api/venue/${fixture.venueId}/guest-preview/info-sections",
                    token,
                )
            assertEquals(HttpStatusCode.OK, ownerPreviewResponse.status)
            assertEquals(HttpStatusCode.OK, ownerSectionsResponse.status)
            val ownerPreviewBody = ownerPreviewResponse.bodyAsText()
            val ownerSectionsBody = ownerSectionsResponse.bodyAsText()
            assertEquals(
                guestVenue,
                json.decodeFromString(VenueResponse.serializer(), ownerPreviewBody),
            )
            assertEquals(
                guestSections,
                json.decodeFromString(VenueInfoSectionsResponse.serializer(), ownerSectionsBody),
            )

            val venue = guestVenue.venue
            assertEquals((1..7).toList(), venue.weeklyHours.map { it.weekday })
            assertEquals(7, venue.weeklyHours.size)
            assertEquals(true, venue.weeklyHours.single { it.weekday == 7 }.isClosed)
            assertEquals(
                fixture.exceptionDates.map(LocalDate::toString),
                venue.dateExceptions.map { it.serviceDate },
            )
            assertEquals(
                listOf("Особый вечер", "Санитарный день"),
                venue.dateExceptions.map { it.guestNote },
            )
            assertEquals(listOf(PUBLIC_STAFF_NAME), venue.todayStaff.map { it.displayName })
            assertEquals(listOf(ACTIVE_PROMOTION_TITLE), venue.promotions.map { it.title })
            assertEquals(listOf(PUBLIC_SECTION_TITLE), guestSections.sections.map { it.title })
            assertEquals(1, guestSections.sections.single().media.size)
            assertTrue(guestSections.sections.single().media.single().url.startsWith("/api/guest/venue/"))
            assertPublicOnly(ownerPreviewBody)
            assertPublicOnly(ownerSectionsBody)

            updateMembershipRole(jdbcUrl, fixture.venueId, ACTOR_USER_ID, "MANAGER")
            val managerPreviewResponse =
                client.getAuthenticated("/api/venue/${fixture.venueId}/guest-preview", token)
            val managerSectionsResponse =
                client.getAuthenticated(
                    "/api/venue/${fixture.venueId}/guest-preview/info-sections",
                    token,
                )
            assertEquals(HttpStatusCode.OK, managerPreviewResponse.status)
            assertEquals(HttpStatusCode.OK, managerSectionsResponse.status)
            assertEquals(
                guestVenue,
                json.decodeFromString(VenueResponse.serializer(), managerPreviewResponse.bodyAsText()),
            )
            assertEquals(
                guestSections,
                json.decodeFromString(
                    VenueInfoSectionsResponse.serializer(),
                    managerSectionsResponse.bodyAsText(),
                ),
            )
        }

    @Test
    fun `staff and foreign venue are denied for every preview read`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-guest-preview-rbac")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val staffVenueId =
                seedVenueWithMembership(
                    jdbcUrl = jdbcUrl,
                    userId = ACTOR_USER_ID,
                    role = "STAFF",
                    status = VenueStatus.PUBLISHED,
                    marker = "staff-owned",
                )
            val foreignVenueId =
                seedVenueWithMembership(
                    jdbcUrl = jdbcUrl,
                    userId = FOREIGN_USER_ID,
                    role = "OWNER",
                    status = VenueStatus.PUBLISHED,
                    marker = "foreign-owned",
                )
            val token = issueToken(config, ACTOR_USER_ID)

            listOf(staffVenueId, foreignVenueId).forEach { venueId ->
                previewPaths(venueId).forEach { path ->
                    val response = client.getAuthenticated(path, token)
                    assertEquals(HttpStatusCode.Forbidden, response.status)
                    assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                }
            }
        }

    @Test
    fun `unavailable venues return the exact safe message without private state`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-guest-preview-unavailable")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val unavailableVenues =
                listOf(
                    VenueStatus.DRAFT,
                    VenueStatus.HIDDEN,
                    VenueStatus.PAUSED,
                    VenueStatus.SUSPENDED,
                    VenueStatus.ARCHIVED,
                ).associateWith { status ->
                    seedVenueWithMembership(
                        jdbcUrl = jdbcUrl,
                        userId = ACTOR_USER_ID,
                        role = "OWNER",
                        status = status,
                        marker = "private-${status.name.lowercase()}",
                    )
                }
            val blockedVenueId =
                seedVenueWithMembership(
                    jdbcUrl = jdbcUrl,
                    userId = ACTOR_USER_ID,
                    role = "OWNER",
                    status = VenueStatus.PUBLISHED,
                    marker = "private-blocked-subscription",
                    subscriptionStatus = "SUSPENDED_BY_PLATFORM",
                )
            val token = issueToken(config, ACTOR_USER_ID)

            (unavailableVenues.values + blockedVenueId).forEach { venueId ->
                previewPaths(venueId).forEach { path ->
                    val response = client.getAuthenticated(path, token)
                    assertEquals(HttpStatusCode.NotFound, response.status)
                    val error = assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
                    assertEquals(UNAVAILABLE_MESSAGE, error.error.message)
                    val body = response.bodyAsText()
                    assertFalse(body.contains("private-", ignoreCase = true))
                    assertFalse(body.contains("draft", ignoreCase = true))
                }
            }
        }

    @Test
    fun `deleted venue returns safe unavailable preview without private state`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-guest-preview-deleted")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val deletedVenueId =
                seedVenueWithMembership(
                    jdbcUrl = jdbcUrl,
                    userId = ACTOR_USER_ID,
                    role = "OWNER",
                    status = VenueStatus.DELETED,
                    marker = "private-deleted",
                )
            val token = issueToken(config, ACTOR_USER_ID)

            previewPaths(deletedVenueId).forEach { path ->
                val response = client.getAuthenticated(path, token)
                assertEquals(HttpStatusCode.NotFound, response.status)
                val error = assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
                assertEquals(UNAVAILABLE_MESSAGE, error.error.message)
                assertFalse(response.bodyAsText().contains("private-deleted"))
            }
        }

    private fun seedPublishedPreview(jdbcUrl: String): PublishedPreviewFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, ACTOR_USER_ID)
            val venueId =
                insertVenue(
                    connection = connection,
                    status = VenueStatus.PUBLISHED,
                    marker = "published",
                    name = "Опубликованная карточка",
                )
            insertMembership(connection, venueId, ACTOR_USER_ID, "OWNER")
            insertSubscription(connection, venueId, "ACTIVE")
            insertVenueSettings(connection, venueId, "UTC")

            (1..7).forEach { weekday ->
                insertWeeklyHours(
                    connection = connection,
                    venueId = venueId,
                    weekday = weekday,
                    opensAt = if (weekday == 7) "00:00:00" else "12:00:00",
                    closesAt = if (weekday == 7) "00:00:00" else "23:00:00",
                    isClosed = weekday == 7,
                )
            }
            val today = LocalDate.now(ZoneId.of("UTC"))
            val exceptionDates = listOf(today.plusDays(3), today.plusDays(10))
            insertDateException(
                connection = connection,
                venueId = venueId,
                serviceDate = exceptionDates[0],
                opensAt = "14:00:00",
                closesAt = "22:00:00",
                isClosed = false,
                guestNote = "Особый вечер",
            )
            insertDateException(
                connection = connection,
                venueId = venueId,
                serviceDate = exceptionDates[1],
                opensAt = "00:00:00",
                closesAt = "00:00:00",
                isClosed = true,
                guestNote = "Санитарный день",
            )

            insertStaffWithTodayShift(
                connection = connection,
                venueId = venueId,
                displayName = PUBLIC_STAFF_NAME,
                isGuestVisible = true,
                shiftDate = today,
            )
            insertStaffWithTodayShift(
                connection = connection,
                venueId = venueId,
                displayName = PRIVATE_STAFF_MARKER,
                isGuestVisible = false,
                shiftDate = today,
            )

            insertPromotion(
                connection = connection,
                venueId = venueId,
                title = ACTIVE_PROMOTION_TITLE,
                status = "ACTIVE",
                startsAt = Instant.now().minusSeconds(86_400),
                endsAt = Instant.now().plusSeconds(86_400),
            )
            insertPromotion(
                connection = connection,
                venueId = venueId,
                title = PRIVATE_PROMOTION_MARKER,
                status = "DRAFT",
                startsAt = Instant.now().minusSeconds(86_400),
                endsAt = Instant.now().plusSeconds(86_400),
            )

            val publicSectionId =
                insertInfoSection(
                    connection = connection,
                    venueId = venueId,
                    title = PUBLIC_SECTION_TITLE,
                    text = "Только опубликованная информация.",
                    isVisible = true,
                )
            insertInfoMedia(connection, publicSectionId, "public-telegram-file")
            val privateSectionId =
                insertInfoSection(
                    connection = connection,
                    venueId = venueId,
                    title = PRIVATE_SECTION_MARKER,
                    text = PRIVATE_SECTION_MARKER,
                    isVisible = false,
                )
            insertInfoMedia(connection, privateSectionId, PRIVATE_MEDIA_MARKER)

            PublishedPreviewFixture(venueId = venueId, exceptionDates = exceptionDates)
        }

    private fun seedVenueWithMembership(
        jdbcUrl: String,
        userId: Long,
        role: String,
        status: VenueStatus,
        marker: String,
        subscriptionStatus: String = "ACTIVE",
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, userId)
            val venueId =
                insertVenue(
                    connection = connection,
                    status = status,
                    marker = marker,
                    name = "Venue $marker",
                )
            insertMembership(connection, venueId, userId, role)
            insertSubscription(connection, venueId, subscriptionStatus)
            insertInfoSection(
                connection = connection,
                venueId = venueId,
                title = marker,
                text = marker,
                isVisible = true,
            )
            venueId
        }

    private fun insertVenue(
        connection: Connection,
        status: VenueStatus,
        marker: String,
        name: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (
                name,
                city,
                address,
                status,
                country_code,
                formatted_address,
                guest_contact,
                card_description
            )
            VALUES (?, 'Москва', 'Новый Арбат, 24', ?, 'RU', 'Россия, Москва, Новый Арбат, 24', ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, status.dbValue)
            statement.setString(3, "+7 999 000-00-00")
            statement.setString(4, "Описание $marker")
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("Failed to insert venue")
            }
        }

    private fun seedUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO users (telegram_user_id, username, first_name, last_name)
            KEY (telegram_user_id)
            VALUES (?, ?, 'Test', 'User')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "user$userId")
            statement.executeUpdate()
        }
    }

    private fun insertMembership(
        connection: Connection,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setString(3, role)
            statement.executeUpdate()
        }
    }

    private fun insertSubscription(
        connection: Connection,
        venueId: Long,
        status: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO venue_subscriptions (venue_id, status) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, status)
            statement.executeUpdate()
        }
    }

    private fun insertVenueSettings(
        connection: Connection,
        venueId: Long,
        timezone: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, timezone)
            statement.executeUpdate()
        }
    }

    private fun insertWeeklyHours(
        connection: Connection,
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
            statement.setTime(3, Time.valueOf(opensAt))
            statement.setTime(4, Time.valueOf(closesAt))
            statement.setBoolean(5, isClosed)
            statement.executeUpdate()
        }
    }

    private fun insertDateException(
        connection: Connection,
        venueId: Long,
        serviceDate: LocalDate,
        opensAt: String,
        closesAt: String,
        isClosed: Boolean,
        guestNote: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_booking_hours_overrides (
                venue_id,
                service_date,
                opens_at,
                closes_at,
                is_closed,
                guest_note
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, java.sql.Date.valueOf(serviceDate))
            statement.setTime(3, Time.valueOf(opensAt))
            statement.setTime(4, Time.valueOf(closesAt))
            statement.setBoolean(5, isClosed)
            statement.setString(6, guestNote)
            statement.executeUpdate()
        }
    }

    private fun insertStaffWithTodayShift(
        connection: Connection,
        venueId: Long,
        displayName: String,
        isGuestVisible: Boolean,
        shiftDate: LocalDate,
    ) {
        val profileId =
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id,
                    display_name,
                    role_label,
                    subtype,
                    photo_ref,
                    bio,
                    tags,
                    is_guest_visible,
                    created_by_user_id,
                    published_at
                )
                VALUES (?, ?, 'Мастер', 'hookah_master', 'photo-ref', 'Публичное био', '["mint"]', ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, displayName)
                statement.setBoolean(3, isGuestVisible)
                statement.setLong(4, ACTOR_USER_ID)
                statement.setTimestamp(5, Timestamp.from(Instant.now()))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else error("Failed to insert staff profile")
                }
            }
        connection.prepareStatement(
            """
            INSERT INTO staff_shifts (
                venue_id,
                staff_profile_id,
                shift_date,
                starts_at,
                ends_at,
                status,
                is_guest_visible,
                manually_marked_active,
                created_by_user_id
            )
            VALUES (?, ?, ?, '12:00:00', '23:00:00', 'active', true, true, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, profileId)
            statement.setDate(3, java.sql.Date.valueOf(shiftDate))
            statement.setLong(4, ACTOR_USER_ID)
            statement.executeUpdate()
        }
    }

    private fun insertPromotion(
        connection: Connection,
        venueId: Long,
        title: String,
        status: String,
        startsAt: Instant,
        endsAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_promotions (
                venue_id,
                title,
                description,
                terms,
                starts_at,
                ends_at,
                status,
                template_type,
                created_by_user_id
            )
            VALUES (?, ?, 'Описание акции', 'Условия акции', ?, ?, ?, 'TEXT_ONLY', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, title)
            statement.setTimestamp(3, Timestamp.from(startsAt))
            statement.setTimestamp(4, Timestamp.from(endsAt))
            statement.setString(5, status)
            statement.setLong(6, ACTOR_USER_ID)
            statement.executeUpdate()
        }
    }

    private fun insertInfoSection(
        connection: Connection,
        venueId: Long,
        title: String,
        text: String,
        isVisible: Boolean,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venue_info_sections (
                venue_id,
                title,
                section_type,
                sort_order,
                is_visible,
                text_content
            )
            VALUES (?, ?, 'about', 0, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, title)
            statement.setBoolean(3, isVisible)
            statement.setString(4, text)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("Failed to insert info section")
            }
        }

    private fun insertInfoMedia(
        connection: Connection,
        sectionId: Long,
        telegramFileId: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_info_section_media (section_id, media_type, telegram_file_id, sort_order)
            VALUES (?, 'image', ?, 0)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, sectionId)
            statement.setString(2, telegramFileId)
            statement.executeUpdate()
        }
    }

    private fun updateMembershipRole(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE venue_members SET role = ? WHERE venue_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setString(1, role)
                statement.setLong(2, venueId)
                statement.setLong(3, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to "test",
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(userId).token

    private suspend fun HttpClient.getAuthenticated(
        path: String,
        token: String,
    ): HttpResponse =
        get(path) {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

    private fun previewPaths(venueId: Long): List<String> =
        listOf(
            "/api/venue/$venueId/guest-preview",
            "/api/venue/$venueId/guest-preview/info-sections",
        )

    private fun assertPublicOnly(body: String) {
        assertFalse(body.contains(PRIVATE_STAFF_MARKER))
        assertFalse(body.contains(PRIVATE_PROMOTION_MARKER))
        assertFalse(body.contains(PRIVATE_SECTION_MARKER))
        assertFalse(body.contains(PRIVATE_MEDIA_MARKER))
        assertFalse(body.contains("telegram_file_id"))
        assertFalse(body.contains("created_by_user_id"))
    }

    private data class PublishedPreviewFixture(
        val venueId: Long,
        val exceptionDates: List<LocalDate>,
    )

    private companion object {
        const val ACTOR_USER_ID = 80_101L
        const val FOREIGN_USER_ID = 80_102L
        const val UNAVAILABLE_MESSAGE = "Заведение недоступно для гостевого просмотра."
        const val PUBLIC_STAFF_NAME = "Алексей"
        const val ACTIVE_PROMOTION_TITLE = "Опубликованная акция"
        const val PUBLIC_SECTION_TITLE = "О заведении"
        const val PRIVATE_STAFF_MARKER = "private-staff-marker"
        const val PRIVATE_PROMOTION_MARKER = "private-promotion-marker"
        const val PRIVATE_SECTION_MARKER = "private-section-marker"
        const val PRIVATE_MEDIA_MARKER = "private-media-marker"
    }
}
