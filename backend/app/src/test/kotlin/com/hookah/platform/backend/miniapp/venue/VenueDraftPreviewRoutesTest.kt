package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueDraftPreviewRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `owner and manager receive one safe draft projection with no-store`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-draft-preview-projection")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val fixture = seedRichDraftPreview(jdbcUrl)
            val ownerResponse =
                client.getAuthenticated(
                    draftPreviewPath(fixture.venueId),
                    issueToken(config, OWNER_USER_ID),
                )
            val managerResponse =
                client.getAuthenticated(
                    draftPreviewPath(fixture.venueId),
                    issueToken(config, MANAGER_USER_ID),
                )

            assertEquals(HttpStatusCode.OK, ownerResponse.status)
            assertEquals("no-store", ownerResponse.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.OK, managerResponse.status)
            assertEquals("no-store", managerResponse.headers[HttpHeaders.CacheControl])

            val ownerBody = ownerResponse.bodyAsText()
            val preview =
                json.decodeFromString(
                    VenueDraftPreviewResponse.serializer(),
                    ownerBody,
                )
            assertEquals("DRAFT", preview.previewMode)
            assertEquals("DRAFT", preview.venueStatus)
            assertFalse(preview.guestAvailable)
            assertEquals("VENUE_NOT_PUBLISHED", preview.unavailableReason)
            assertTrue(preview.mediaAvailableAfterPublication)

            val venue = preview.publicCandidate
            assertEquals(fixture.venueId, venue.id)
            assertEquals(PUBLIC_VENUE_NAME, venue.name)
            assertEquals("Москва", venue.city)
            assertEquals("Новый Арбат, 24", venue.address)
            assertEquals("RU", venue.countryCode)
            assertEquals("Россия, Москва, Новый Арбат, 24", venue.formattedAddress)
            assertEquals("+7 999 000-00-00", venue.guestContact)
            assertEquals(PUBLIC_CARD_DESCRIPTION, venue.cardDescription)
            assertNotNull(venue.displayAddress)
            assertNotNull(venue.routeUrl)
            assertEquals("UTC", venue.timezone)
            assertEquals((1..7).toList(), venue.weeklyHours.map { it.weekday })
            assertEquals(
                listOf(fixture.futureExceptionDate.toString()),
                venue.dateExceptions.map { it.serviceDate },
            )
            assertEquals(listOf(PUBLIC_EXCEPTION_NOTE), venue.dateExceptions.map { it.guestNote })
            assertNotNull(venue.todaySchedule)
            assertEquals(listOf(PUBLIC_STAFF_NAME), venue.todayStaff.map { it.displayName })
            assertEquals(listOf("Кальянный мастер"), venue.todayStaff.map { it.roleLabel })
            assertEquals(listOf(listOf("мята", "цитрус")), venue.todayStaff.map { it.tags })
            assertEquals(listOf(ACTIVE_PROMOTION_TITLE), venue.promotions.map { it.title })
            assertEquals(
                listOf(PUBLIC_SECTION_TITLE),
                preview.infoSections.map { it.displayTitle },
            )
            assertEquals(
                listOf(PUBLIC_SECTION_TEXT),
                preview.infoSections.map { it.text },
            )

            assertDraftProjectionContainsNoPrivateData(ownerBody)
            val managerPreview =
                json.decodeFromString(
                    VenueDraftPreviewResponse.serializer(),
                    managerResponse.bodyAsText(),
                )
            assertEquals("DRAFT", managerPreview.previewMode)
            assertFalse(managerPreview.guestAvailable)
            assertEquals(PUBLIC_VENUE_NAME, managerPreview.publicCandidate.name)
            assertEquals(
                listOf(PUBLIC_STAFF_NAME),
                managerPreview.publicCandidate.todayStaff.map { it.displayName },
            )
            assertDraftProjectionContainsNoPrivateData(managerResponse.bodyAsText())
        }

    @Test
    fun `staff and foreign users are denied without draft projection`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-draft-preview-rbac")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val staffVenueId =
                seedVenueWithMembership(
                    jdbcUrl = jdbcUrl,
                    memberUserId = ACTOR_USER_ID,
                    role = "STAFF",
                    status = VenueStatus.DRAFT,
                    marker = "staff-private-marker",
                )
            val foreignVenueId =
                seedVenueWithMembership(
                    jdbcUrl = jdbcUrl,
                    memberUserId = FOREIGN_USER_ID,
                    role = "OWNER",
                    status = VenueStatus.DRAFT,
                    marker = "foreign-private-marker",
                )
            val token = issueToken(config, ACTOR_USER_ID)

            listOf(staffVenueId, foreignVenueId).forEach { venueId ->
                val response = client.getAuthenticated(draftPreviewPath(venueId), token)
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                assertFalse(response.bodyAsText().contains("private-marker"))
                assertFalse(response.bodyAsText().contains("publicCandidate"))
            }
        }

    @Test
    fun `missing deleted and non-draft venues return safe not found without projection`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-draft-preview-status")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueIds =
                buildList {
                    add(MISSING_VENUE_ID)
                    add(
                        seedVenueWithMembership(
                            jdbcUrl = jdbcUrl,
                            memberUserId = ACTOR_USER_ID,
                            role = "OWNER",
                            status = VenueStatus.DELETED,
                            marker = "deleted-private-marker",
                        ),
                    )
                    listOf(
                        VenueStatus.PUBLISHED,
                        VenueStatus.HIDDEN,
                        VenueStatus.PAUSED,
                        VenueStatus.SUSPENDED,
                        VenueStatus.ARCHIVED,
                    ).forEach { status ->
                        add(
                            seedVenueWithMembership(
                                jdbcUrl = jdbcUrl,
                                memberUserId = ACTOR_USER_ID,
                                role = "OWNER",
                                status = status,
                                marker = "${status.name.lowercase()}-private-marker",
                            ),
                        )
                    }
                }
            val token = issueToken(config, ACTOR_USER_ID)

            venueIds.forEach { venueId ->
                val response = client.getAuthenticated(draftPreviewPath(venueId), token)
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val error = assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
                assertEquals(DRAFT_PREVIEW_UNAVAILABLE_MESSAGE, error.error.message)
                val body = response.bodyAsText()
                assertFalse(body.contains("private-marker"))
                assertFalse(body.contains("publicCandidate"))
                assertFalse(body.contains("\"previewMode\""))
            }
        }

    @Test
    fun `draft preview is GET-only and guest media remains unavailable`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-draft-preview-media")
            val config = buildConfig(jdbcUrl)
            var mediaDownloadAttempts = 0

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        telegramFileDownloader = {
                            mediaDownloadAttempts += 1
                            null
                        },
                    ),
                )
            }
            client.get("/health")

            val fixture = seedRichDraftPreview(jdbcUrl)
            val token = issueToken(config, OWNER_USER_ID)
            val guestMediaResponse =
                client.get(
                    "/api/guest/venue/${fixture.venueId}/info-sections/" +
                        "${fixture.visibleSectionId}/media/${fixture.visibleMediaId}",
                )
            val postResponse =
                client.post(draftPreviewPath(fixture.venueId)) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, guestMediaResponse.status)
            assertEquals(0, mediaDownloadAttempts)
            assertEquals(HttpStatusCode.NotFound, postResponse.status)
        }

    private fun seedRichDraftPreview(jdbcUrl: String): DraftPreviewFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            listOf(OWNER_USER_ID, MANAGER_USER_ID, STAFF_LINKED_USER_ID).forEach {
                seedUser(connection, it, "private-user-$it")
            }
            val venueId =
                insertVenue(
                    connection = connection,
                    status = VenueStatus.DRAFT,
                    marker = "draft",
                    name = PUBLIC_VENUE_NAME,
                )
            insertMembership(connection, venueId, OWNER_USER_ID, "OWNER")
            insertMembership(connection, venueId, MANAGER_USER_ID, "MANAGER")
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
            val futureExceptionDate = today.plusDays(4)
            insertDateException(
                connection = connection,
                venueId = venueId,
                serviceDate = futureExceptionDate,
                guestNote = PUBLIC_EXCEPTION_NOTE,
            )
            insertDateException(
                connection = connection,
                venueId = venueId,
                serviceDate = today.minusDays(2),
                guestNote = "past-private-exception-marker",
            )

            insertStaffWithTodayShift(
                connection = connection,
                venueId = venueId,
                displayName = PUBLIC_STAFF_NAME,
                isGuestVisible = true,
                isPublished = true,
                isShiftVisible = true,
                shiftDate = today,
                photoRef = PRIVATE_PHOTO_REF,
            )
            insertStaffWithTodayShift(
                connection = connection,
                venueId = venueId,
                displayName = HIDDEN_STAFF_MARKER,
                isGuestVisible = false,
                isPublished = true,
                isShiftVisible = true,
                shiftDate = today,
                photoRef = "hidden-photo-ref",
            )
            insertStaffWithTodayShift(
                connection = connection,
                venueId = venueId,
                displayName = UNPUBLISHED_STAFF_MARKER,
                isGuestVisible = true,
                isPublished = false,
                isShiftVisible = true,
                shiftDate = today,
                photoRef = "unpublished-photo-ref",
            )
            insertStaffWithTodayShift(
                connection = connection,
                venueId = venueId,
                displayName = HIDDEN_SHIFT_MARKER,
                isGuestVisible = true,
                isPublished = true,
                isShiftVisible = false,
                shiftDate = today,
                photoRef = "hidden-shift-photo-ref",
            )

            val activePromotionId =
                insertPromotion(
                    connection = connection,
                    venueId = venueId,
                    title = ACTIVE_PROMOTION_TITLE,
                    status = "ACTIVE",
                    startsAt = Instant.parse("2020-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2100-01-01T00:00:00Z"),
                )
            listOf(
                "DRAFT" to DRAFT_PROMOTION_MARKER,
                "PAUSED" to PAUSED_PROMOTION_MARKER,
                "ARCHIVED" to ARCHIVED_PROMOTION_MARKER,
            ).forEach { (status, title) ->
                insertPromotion(
                    connection = connection,
                    venueId = venueId,
                    title = title,
                    status = status,
                    startsAt = Instant.parse("2020-01-01T00:00:00Z"),
                    endsAt = Instant.parse("2100-01-01T00:00:00Z"),
                )
            }
            insertPromotion(
                connection = connection,
                venueId = venueId,
                title = FUTURE_PROMOTION_MARKER,
                status = "ACTIVE",
                startsAt = Instant.parse("2100-01-01T00:00:00Z"),
                endsAt = null,
            )
            insertPromotion(
                connection = connection,
                venueId = venueId,
                title = PAST_PROMOTION_MARKER,
                status = "ACTIVE",
                startsAt = null,
                endsAt = Instant.parse("2020-01-01T00:00:00Z"),
            )

            val visibleSectionId =
                insertInfoSection(
                    connection = connection,
                    venueId = venueId,
                    title = PUBLIC_SECTION_TITLE,
                    text = PUBLIC_SECTION_TEXT,
                    isVisible = true,
                )
            val visibleMediaId =
                insertInfoMedia(
                    connection = connection,
                    sectionId = visibleSectionId,
                    telegramFileId = PRIVATE_MEDIA_REF,
                )
            val hiddenSectionId =
                insertInfoSection(
                    connection = connection,
                    venueId = venueId,
                    title = HIDDEN_SECTION_MARKER,
                    text = HIDDEN_SECTION_MARKER,
                    isVisible = false,
                )
            insertInfoMedia(
                connection = connection,
                sectionId = hiddenSectionId,
                telegramFileId = HIDDEN_MEDIA_REF,
            )
            val mediaOnlySectionId =
                insertInfoSection(
                    connection = connection,
                    venueId = venueId,
                    title = "Фото",
                    text = null,
                    isVisible = true,
                )
            insertInfoMedia(
                connection = connection,
                sectionId = mediaOnlySectionId,
                telegramFileId = MEDIA_ONLY_REF,
            )

            assertTrue(activePromotionId > 0)
            DraftPreviewFixture(
                venueId = venueId,
                futureExceptionDate = futureExceptionDate,
                visibleSectionId = visibleSectionId,
                visibleMediaId = visibleMediaId,
            )
        }

    private fun seedVenueWithMembership(
        jdbcUrl: String,
        memberUserId: Long,
        role: String,
        status: VenueStatus,
        marker: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            seedUser(connection, memberUserId, "user-$memberUserId")
            val venueId =
                insertVenue(
                    connection = connection,
                    status = status,
                    marker = marker,
                    name = "Venue $marker",
                )
            insertMembership(connection, venueId, memberUserId, role)
            insertSubscription(connection, venueId, "ACTIVE")
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
                latitude,
                longitude,
                guest_contact,
                card_description
            )
            VALUES (?, 'Москва', 'Новый Арбат, 24', ?, 'RU',
                    'Россия, Москва, Новый Арбат, 24', 55.7522, 37.6156, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, status.dbValue)
            statement.setString(3, "+7 999 000-00-00")
            statement.setString(
                4,
                if (marker == "draft") PUBLIC_CARD_DESCRIPTION else "Описание $marker",
            )
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("Failed to insert venue")
            }
        }

    private fun seedUser(
        connection: Connection,
        userId: Long,
        username: String,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO users (telegram_user_id, username, first_name, last_name)
            KEY (telegram_user_id)
            VALUES (?, ?, 'Private', 'User')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, username)
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
            VALUES (?, ?, '14:00:00', '22:00:00', FALSE, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setDate(2, java.sql.Date.valueOf(serviceDate))
            statement.setString(3, guestNote)
            statement.executeUpdate()
        }
    }

    private fun insertStaffWithTodayShift(
        connection: Connection,
        venueId: Long,
        displayName: String,
        isGuestVisible: Boolean,
        isPublished: Boolean,
        isShiftVisible: Boolean,
        shiftDate: LocalDate,
        photoRef: String,
    ) {
        val profileId =
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id,
                    linked_user_id,
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
                VALUES (?, ?, ?, 'Кальянный мастер', 'hookah_master', ?,
                        'Публичное био', '["мята","цитрус"]', ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, STAFF_LINKED_USER_ID)
                statement.setString(3, displayName)
                statement.setString(4, photoRef)
                statement.setBoolean(5, isGuestVisible)
                statement.setLong(6, OWNER_USER_ID)
                if (isPublished) {
                    statement.setTimestamp(7, Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")))
                } else {
                    statement.setTimestamp(7, null)
                }
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
            VALUES (?, ?, ?, '12:00:00', '23:00:00', 'active', ?, TRUE, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, profileId)
            statement.setDate(3, java.sql.Date.valueOf(shiftDate))
            statement.setBoolean(4, isShiftVisible)
            statement.setLong(5, OWNER_USER_ID)
            statement.executeUpdate()
        }
    }

    private fun insertPromotion(
        connection: Connection,
        venueId: Long,
        title: String,
        status: String,
        startsAt: Instant?,
        endsAt: Instant?,
    ): Long =
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
            VALUES (?, ?, 'Публичное описание акции', 'Публичные условия', ?, ?, ?, 'TEXT_ONLY', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, title)
            statement.setTimestamp(3, startsAt?.let(Timestamp::from))
            statement.setTimestamp(4, endsAt?.let(Timestamp::from))
            statement.setString(5, status)
            statement.setLong(6, OWNER_USER_ID)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("Failed to insert promotion")
            }
        }

    private fun insertInfoSection(
        connection: Connection,
        venueId: Long,
        title: String,
        text: String?,
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
            VALUES (?, ?, 'about', 10, ?, ?)
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
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venue_info_section_media (section_id, media_type, telegram_file_id, sort_order)
            VALUES (?, 'image', ?, 0)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, sectionId)
            statement.setString(2, telegramFileId)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("Failed to insert info media")
            }
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
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

    private fun draftPreviewPath(venueId: Long): String = "/api/venue/$venueId/draft-preview"

    private fun assertDraftProjectionContainsNoPrivateData(body: String) {
        listOf(
            PRIVATE_PHOTO_REF,
            PRIVATE_MEDIA_REF,
            HIDDEN_MEDIA_REF,
            MEDIA_ONLY_REF,
            HIDDEN_STAFF_MARKER,
            UNPUBLISHED_STAFF_MARKER,
            HIDDEN_SHIFT_MARKER,
            DRAFT_PROMOTION_MARKER,
            PAUSED_PROMOTION_MARKER,
            ARCHIVED_PROMOTION_MARKER,
            FUTURE_PROMOTION_MARKER,
            PAST_PROMOTION_MARKER,
            HIDDEN_SECTION_MARKER,
            "past-private-exception-marker",
            "private-user-",
            "linkedUserId",
            "telegramFileId",
            "createdByUserId",
            "subscription",
            "\"media\"",
            "\"phone\"",
            "\"email\"",
            "\"internalNotes\"",
            "\"providerPayload\"",
            "\"audit\"",
            "\"billing\"",
            "\"actions\"",
            "\"permissions\"",
            "\"shareToken\"",
        ).forEach { marker ->
            assertFalse(body.contains(marker, ignoreCase = true), "Unexpected private marker: $marker")
        }
    }

    private data class DraftPreviewFixture(
        val venueId: Long,
        val futureExceptionDate: LocalDate,
        val visibleSectionId: Long,
        val visibleMediaId: Long,
    )

    private companion object {
        const val OWNER_USER_ID = 91_101L
        const val MANAGER_USER_ID = 91_102L
        const val STAFF_LINKED_USER_ID = 91_103L
        const val ACTOR_USER_ID = 91_104L
        const val FOREIGN_USER_ID = 91_105L
        const val MISSING_VENUE_ID = 9_999_999L
        const val PUBLIC_VENUE_NAME = "Черновая карточка"
        const val PUBLIC_CARD_DESCRIPTION = "Публичное описание будущей карточки"
        const val PUBLIC_EXCEPTION_NOTE = "Особый вечер"
        const val PUBLIC_STAFF_NAME = "Алексей"
        const val PUBLIC_SECTION_TITLE = "О заведении"
        const val PUBLIC_SECTION_TEXT = "Публичный текст раздела"
        const val ACTIVE_PROMOTION_TITLE = "Активная акция"
        const val PRIVATE_PHOTO_REF = "private-photo-ref"
        const val PRIVATE_MEDIA_REF = "private-telegram-media-ref"
        const val HIDDEN_MEDIA_REF = "hidden-telegram-media-ref"
        const val MEDIA_ONLY_REF = "media-only-telegram-ref"
        const val HIDDEN_STAFF_MARKER = "hidden-staff-marker"
        const val UNPUBLISHED_STAFF_MARKER = "unpublished-staff-marker"
        const val HIDDEN_SHIFT_MARKER = "hidden-shift-marker"
        const val DRAFT_PROMOTION_MARKER = "draft-promotion-marker"
        const val PAUSED_PROMOTION_MARKER = "paused-promotion-marker"
        const val ARCHIVED_PROMOTION_MARKER = "archived-promotion-marker"
        const val FUTURE_PROMOTION_MARKER = "future-promotion-marker"
        const val PAST_PROMOTION_MARKER = "past-promotion-marker"
        const val HIDDEN_SECTION_MARKER = "hidden-section-marker"
    }
}
