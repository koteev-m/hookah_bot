package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.onboarding.VenueOnboardingService
import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueOnboardingConcurrencyPostgresTest {
    @Test
    fun `same canonical Telegram and Mini App submit commits one logical request and one audit`() =
        runBlocking {
            withFixture { fixture ->
                val attempts =
                    runWhileApplicantIsBlocked(
                        fixture = fixture,
                        beforeRelease = {
                            assertEquals(0, fixture.count("venue_connection_requests"))
                            assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                        },
                        first = {
                            SubmitAttempt(
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                                venueName = "Concurrent Venue",
                                result =
                                    fixture.service.submitApplication(
                                        applicantUserId = APPLICANT_ID,
                                        venueName = "Concurrent Venue",
                                        city = "Москва",
                                        contact = "@owner",
                                        comment = "same application",
                                        source = VenueOnboardingSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                        second = {
                            SubmitAttempt(
                                source = VenueOnboardingSource.VENUE_MINI_APP,
                                venueName = " concurrent\u0085venue ",
                                result =
                                    fixture.service.submitApplication(
                                        applicantUserId = APPLICANT_ID,
                                        venueName = " concurrent\u0085venue ",
                                        city = " МОСКВА ",
                                        contact = " @OWNER ",
                                        comment = " SAME APPLICATION ",
                                        source = VenueOnboardingSource.VENUE_MINI_APP,
                                    ),
                            )
                        },
                    )
                val successfulAttempts =
                    attempts.map { attempt ->
                        attempt to assertIs<VenueConnectionRequestSubmitResult.Success>(attempt.result)
                    }
                val winner = successfulAttempts.single { (_, result) -> result.created }
                val repeated = successfulAttempts.single { (_, result) -> !result.created }

                assertEquals(winner.second.request.id, repeated.second.request.id)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                assertEquals(1, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(1, fixture.count("venue_owner_accounts"))

                val persisted = fixture.service.findApplication(winner.second.request.id)
                assertNotNull(persisted)
                assertEquals(winner.first.venueName, persisted.venueName)
                assertEquals(
                    winner.first.source.name,
                    fixture.singleAuditField(VenueConnectionRequestRepository.AUDIT_SUBMITTED, "source"),
                )
                assertEquals(VenueConnectionRequestRepository.STATUS_PENDING, persisted.status)
                assertEquals(
                    canonicalVenueConnectionApplicationTuple(
                        venueName = "Concurrent Venue",
                        city = "Москва",
                        contact = "@owner",
                        comment = "same application",
                    ),
                    canonicalVenueConnectionApplicationTuple(
                        venueName = " concurrent\u0085venue ",
                        city = " МОСКВА ",
                        contact = " @OWNER ",
                        comment = " SAME APPLICATION ",
                    ),
                )
            }
        }

    @Test
    fun `distinct concurrent Telegram and Mini App submit commits two requests and two audits`() =
        runBlocking {
            withFixture { fixture ->
                val attempts =
                    runWhileApplicantIsBlocked(
                        fixture = fixture,
                        beforeRelease = {
                            assertEquals(0, fixture.count("venue_connection_requests"))
                            assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                        },
                        first = {
                            SubmitAttempt(
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                                venueName = "Telegram Venue",
                                result =
                                    fixture.service.submitApplication(
                                        applicantUserId = APPLICANT_ID,
                                        venueName = "Telegram Venue",
                                        city = "Москва",
                                        contact = "@owner",
                                        comment = "first",
                                        source = VenueOnboardingSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                        second = {
                            SubmitAttempt(
                                source = VenueOnboardingSource.VENUE_MINI_APP,
                                venueName = "Mini App Venue",
                                result =
                                    fixture.service.submitApplication(
                                        applicantUserId = APPLICANT_ID,
                                        venueName = "Mini App Venue",
                                        city = "Сочи",
                                        contact = "@owner",
                                        comment = "second",
                                        source = VenueOnboardingSource.VENUE_MINI_APP,
                                    ),
                            )
                        },
                    )
                val results =
                    attempts.map { attempt ->
                        attempt to assertIs<VenueConnectionRequestSubmitResult.Success>(attempt.result)
                    }

                assertTrue(results.all { (_, result) -> result.created })
                assertEquals(2, results.map { (_, result) -> result.request.id }.toSet().size)
                assertEquals(2, fixture.count("venue_connection_requests"))
                assertEquals(2, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                assertEquals(
                    setOf("Telegram Venue", "Mini App Venue"),
                    fixture.service.listOwnApplications(APPLICANT_ID).map { it.venueName }.toSet(),
                )
                assertEquals(
                    setOf(VenueOnboardingSource.TELEGRAM_BOT.name, VenueOnboardingSource.VENUE_MINI_APP.name),
                    fixture.auditFields(VenueConnectionRequestRepository.AUDIT_SUBMITTED, "source").toSet(),
                )
            }
        }

    @Test
    fun `concurrent approve and reject both wait and final state matches the single winner audit`() =
        runBlocking {
            withFixture { fixture ->
                val request = fixture.submit()
                val attempts =
                    runWhileApplicantIsBlocked(
                        fixture = fixture,
                        beforeRelease = {
                            assertEquals(
                                VenueConnectionRequestRepository.STATUS_PENDING,
                                fixture.requestStatus(request.id),
                            )
                            assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_APPROVED))
                            assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_REJECTED))
                        },
                        first = {
                            DecisionAttempt(
                                targetStatus = VenueConnectionRequestRepository.STATUS_APPROVED,
                                source = VenueOnboardingSource.PLATFORM_MINI_APP,
                                result =
                                    fixture.service.decideApplication(
                                        requestId = request.id,
                                        actorUserId = PLATFORM_OWNER_ID,
                                        approved = true,
                                        source = VenueOnboardingSource.PLATFORM_MINI_APP,
                                    ),
                            )
                        },
                        second = {
                            DecisionAttempt(
                                targetStatus = VenueConnectionRequestRepository.STATUS_REJECTED,
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                                result =
                                    fixture.service.decideApplication(
                                        requestId = request.id,
                                        actorUserId = PLATFORM_OWNER_ID,
                                        approved = false,
                                        source = VenueOnboardingSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                    )

                val winner = attempts.single { it.result is VenueConnectionRequestMutationResult.Success }
                val winnerResult = assertIs<VenueConnectionRequestMutationResult.Success>(winner.result)
                val loser = attempts.single { it.result is VenueConnectionRequestMutationResult.InvalidState }
                val loserResult = assertIs<VenueConnectionRequestMutationResult.InvalidState>(loser.result)
                val winnerAction =
                    if (winner.targetStatus == VenueConnectionRequestRepository.STATUS_APPROVED) {
                        VenueConnectionRequestRepository.AUDIT_APPROVED
                    } else {
                        VenueConnectionRequestRepository.AUDIT_REJECTED
                    }
                val loserAction =
                    if (winnerAction == VenueConnectionRequestRepository.AUDIT_APPROVED) {
                        VenueConnectionRequestRepository.AUDIT_REJECTED
                    } else {
                        VenueConnectionRequestRepository.AUDIT_APPROVED
                    }

                assertEquals(winner.targetStatus, winnerResult.request.status)
                assertEquals(winner.targetStatus, loserResult.request.status)
                assertEquals(winner.targetStatus, fixture.requestStatus(request.id))
                assertEquals(1, fixture.countAudit(winnerAction))
                assertEquals(0, fixture.countAudit(loserAction))
                assertEquals(winner.targetStatus, fixture.singleAuditField(winnerAction, "status"))
                assertEquals(winner.source.name, fixture.singleAuditField(winnerAction, "source"))
            }
        }

    @Test
    fun `concurrent platform create and link both wait and audits identify the actual winner`() =
        runBlocking {
            withFixture { fixture ->
                val request = fixture.approvedWithTerms()
                val attempts =
                    runWhileApplicantIsBlocked(
                        fixture = fixture,
                        beforeRelease = {
                            assertEquals(null, fixture.linkedVenueId(request.id))
                            assertEquals(1, fixture.count("venues"))
                            assertEquals(1, fixture.count("venue_members"))
                            assertEquals(0, fixture.count("venue_settings"))
                            assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
                        },
                        first = {
                            LinkAttempt(
                                source = VenueOnboardingSource.PLATFORM_MINI_APP,
                                result =
                                    fixture.service.createDraftAndLink(
                                        request.id,
                                        PLATFORM_OWNER_ID,
                                        VenueOnboardingSource.PLATFORM_MINI_APP,
                                    ),
                            )
                        },
                        second = {
                            LinkAttempt(
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                                result =
                                    fixture.service.createDraftAndLink(
                                        request.id,
                                        PLATFORM_OWNER_ID,
                                        VenueOnboardingSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                    )
                val successfulAttempts =
                    attempts.map { attempt ->
                        attempt to assertIs<VenueConnectionRequestCreateLinkResult.Success>(attempt.result)
                    }
                val winner = successfulAttempts.single { (_, result) -> result.created }
                val retry = successfulAttempts.single { (_, result) -> !result.created }

                assertEquals(winner.second.venueId, retry.second.venueId)
                assertEquals(winner.second.venueId, fixture.linkedVenueId(request.id))
                assertEquals(VenueConnectionRequestRepository.STATUS_APPROVED, fixture.requestStatus(request.id))
                assertEquals(winner.second.venueId, winner.second.request.linkedVenueId)
                assertEquals(winner.second.venueId, retry.second.request.linkedVenueId)
                assertEquals(2, fixture.count("venues"))
                assertEquals(2, fixture.count("venue_members"))
                assertEquals(1, fixture.countOwnerMemberships(winner.second.venueId))
                assertEquals(1, fixture.count("venue_owner_accounts"))
                assertEquals(setOf(fixture.ownerAccountId()), fixture.venueOwnerAccountIds())
                assertEquals(1, fixture.count("venue_subscription_settings"))
                assertEquals(1, fixture.count("venue_subscriptions"))
                assertEquals(1, fixture.count("venue_settings"))
                assertEquals(0, fixture.count("menu_categories"))
                assertEquals(1, fixture.countAudit("VENUE_CREATE"))
                assertEquals(1, fixture.countAudit("VENUE_OWNER_ASSIGN"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
                assertEquals(winner.first.source.name, fixture.singleAuditField("VENUE_CREATE", "source"))
                assertEquals(winner.first.source.name, fixture.singleAuditField("VENUE_OWNER_ASSIGN", "source"))
                assertEquals(
                    winner.first.source.name,
                    fixture.singleAuditField(VenueConnectionRequestRepository.AUDIT_LINKED, "source"),
                )
            }
        }

    @Test
    fun `shared Telegram writer accepts authenticated applicant without operational owner membership`() =
        runBlocking {
            withFixture { fixture ->
                fixture.revokeOperationalOwner()

                val result =
                    fixture.service.submitApplication(
                        applicantUserId = APPLICANT_ID,
                        venueName = "Revoked owner venue",
                        city = "Москва",
                        contact = "@owner",
                        comment = null,
                        source = VenueOnboardingSource.TELEGRAM_BOT,
                    )

                val submitted = assertIs<VenueConnectionRequestSubmitResult.Success>(result)
                assertTrue(submitted.created)
                assertEquals(VenueConnectionRequestRepository.STATUS_PENDING, submitted.request.status)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                assertEquals(1, fixture.count("venues"))
                assertEquals(0, fixture.count("venue_members"))
            }
        }

    @Test
    fun `approved applicant without current membership links atomically and retry is idempotent`() =
        runBlocking {
            withFixture { fixture ->
                val request = fixture.approvedWithTerms()
                fixture.revokeOperationalOwner()

                val linked =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.service.createDraftAndLink(
                            requestId = request.id,
                            actorUserId = PLATFORM_OWNER_ID,
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                val repeated =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.service.createDraftAndLink(
                            requestId = request.id,
                            actorUserId = PLATFORM_OWNER_ID,
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )

                assertTrue(linked.created)
                assertFalse(repeated.created)
                assertEquals(linked.venueId, repeated.venueId)
                assertEquals(linked.venueId, fixture.linkedVenueId(request.id))
                assertEquals(2, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(1, fixture.countOwnerMemberships(linked.venueId))
                assertEquals(1, fixture.count("venue_owner_accounts"))
                assertEquals(setOf(fixture.ownerAccountId()), fixture.venueOwnerAccountIds())
                assertEquals(1, fixture.count("venue_settings"))
                assertEquals(1, fixture.count("venue_subscription_settings"))
                assertEquals(1, fixture.count("venue_subscriptions"))
                assertEquals(0, fixture.count("menu_categories"))
                assertEquals(1, fixture.countAudit("VENUE_CREATE"))
                assertEquals(1, fixture.countAudit("VENUE_OWNER_ASSIGN"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
                assertEquals("TELEGRAM_BOT", fixture.singleAuditField("VENUE_CREATE", "source"))
                assertEquals("TELEGRAM_BOT", fixture.singleAuditField("VENUE_OWNER_ASSIGN", "source"))
                assertEquals(
                    "TELEGRAM_BOT",
                    fixture.singleAuditField(VenueConnectionRequestRepository.AUDIT_LINKED, "source"),
                )
            }
        }

    @Test
    fun `first ever applicant concurrent create link initializes one default commercial account`() =
        runBlocking {
            withFirstApplicantFixture { fixture ->
                assertEquals(0, fixture.count("venue_owner_accounts"))
                assertEquals(0, fixture.count("venues"))
                assertEquals(0, fixture.count("venue_members"))
                assertEquals(0, fixture.count("venue_subscription_settings"))
                assertEquals(0, fixture.count("venue_subscriptions"))
                assertEquals(0, fixture.count("venue_settings"))
                assertEquals(0, fixture.count("venue_connection_requests"))
                assertEquals(0, fixture.count("telegram_venue_context"))
                assertEquals(0, fixture.count("menu_categories"))

                val submitted =
                    assertIs<VenueConnectionRequestSubmitResult.Success>(
                        fixture.service.submitApplication(
                            applicantUserId = APPLICANT_ID,
                            venueName = "First Applicant Venue",
                            city = "Москва",
                            contact = "@first_owner",
                            comment = "First venue",
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                assertTrue(submitted.created)
                assertEquals(VenueConnectionRequestRepository.STATUS_PENDING, submitted.request.status)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                assertEquals(0, fixture.count("venue_owner_accounts"))
                assertEquals(0, fixture.count("venues"))
                assertEquals(0, fixture.count("venue_members"))
                assertEquals(0, fixture.count("venue_subscription_settings"))
                assertEquals(0, fixture.count("venue_subscriptions"))
                assertEquals(0, fixture.count("venue_settings"))
                assertEquals(0, fixture.countLinkedRequests())
                assertEquals(0, fixture.count("telegram_venue_context"))
                assertEquals(0, fixture.count("menu_categories"))

                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.service.decideApplication(
                        requestId = submitted.request.id,
                        actorUserId = PLATFORM_OWNER_ID,
                        approved = true,
                        source = VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.service.updateCommercialTerms(
                        requestId = submitted.request.id,
                        actorUserId = PLATFORM_OWNER_ID,
                        trialConfigured = true,
                        trialEndsOn = null,
                        currentPriceRub = 10_000L,
                        futurePriceRub = null,
                        futurePriceEffectiveOn = null,
                        commercialNote = "First applicant terms",
                        source = VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )
                assertEquals(
                    VenueConnectionRequestRepository.STATUS_APPROVED,
                    fixture.requestStatus(submitted.request.id),
                )
                assertEquals(0, fixture.count("venue_owner_accounts"))
                assertEquals(0, fixture.count("venues"))
                assertEquals(0, fixture.count("venue_members"))
                assertEquals(0, fixture.countLinkedRequests())
                assertEquals(0, fixture.count("telegram_venue_context"))
                assertEquals(0, fixture.count("menu_categories"))

                val attempts =
                    runWhileApplicantIsBlocked(
                        fixture = fixture,
                        beforeRelease = {
                            assertEquals(0, fixture.count("venue_owner_accounts"))
                            assertEquals(0, fixture.count("venues"))
                            assertEquals(0, fixture.count("venue_members"))
                            assertEquals(0, fixture.countLinkedRequests())
                            assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
                        },
                        first = {
                            LinkAttempt(
                                source = VenueOnboardingSource.PLATFORM_MINI_APP,
                                result =
                                    fixture.service.createDraftAndLink(
                                        submitted.request.id,
                                        PLATFORM_OWNER_ID,
                                        VenueOnboardingSource.PLATFORM_MINI_APP,
                                    ),
                            )
                        },
                        second = {
                            LinkAttempt(
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                                result =
                                    fixture.service.createDraftAndLink(
                                        submitted.request.id,
                                        PLATFORM_OWNER_ID,
                                        VenueOnboardingSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                    )
                val successfulAttempts =
                    attempts.map { attempt ->
                        attempt to assertIs<VenueConnectionRequestCreateLinkResult.Success>(attempt.result)
                    }
                val winner = successfulAttempts.single { (_, result) -> result.created }
                val retry = successfulAttempts.single { (_, result) -> !result.created }
                val venueId = winner.second.venueId

                assertEquals(venueId, retry.second.venueId)
                assertEquals(venueId, winner.second.request.linkedVenueId)
                assertEquals(venueId, retry.second.request.linkedVenueId)
                assertEquals(venueId, fixture.linkedVenueId(submitted.request.id))
                assertEquals(1, fixture.countLinkedRequests())
                assertEquals(1, fixture.count("venue_owner_accounts"))
                assertEquals(1, fixture.ownerAccountAllowedVenuesCount())
                assertEquals(1, fixture.count("venues"))
                assertEquals("DRAFT", fixture.venueStatus(venueId))
                assertEquals(setOf(fixture.ownerAccountId()), fixture.venueOwnerAccountIds())
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(1, fixture.countOwnerMemberships(venueId))
                assertEquals(1, fixture.count("venue_settings"))
                assertEquals("Europe/Moscow", fixture.venueTimezone(venueId))
                assertEquals(1, fixture.count("venue_subscription_settings"))
                assertEquals(1, fixture.count("venue_subscriptions"))
                assertEquals("ACTIVE", fixture.subscriptionStatus(venueId))
                assertEquals(0, fixture.count("telegram_venue_context"))
                assertEquals(0, fixture.count("menu_categories"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_APPROVED))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_TERMS_UPDATED))
                assertEquals(1, fixture.countAudit("VENUE_CREATE"))
                assertEquals(1, fixture.countAudit("VENUE_OWNER_ASSIGN"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
                assertEquals(winner.first.source.name, fixture.singleAuditField("VENUE_CREATE", "source"))
                assertEquals(winner.first.source.name, fixture.singleAuditField("VENUE_OWNER_ASSIGN", "source"))
                assertEquals(
                    winner.first.source.name,
                    fixture.singleAuditField(VenueConnectionRequestRepository.AUDIT_LINKED, "source"),
                )
            }
        }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { dataSource ->
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO users (telegram_user_id, username, first_name, last_name)
                    VALUES (?, ?, 'Venue', 'Owner'), (?, ?, 'Platform', 'Owner')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, APPLICANT_ID)
                    statement.setString(2, "venue_owner")
                    statement.setLong(3, PLATFORM_OWNER_ID)
                    statement.setString(4, "platform_owner")
                    statement.executeUpdate()
                }
            }
            val audit = AuditLogRepository(dataSource)
            val repository = VenueConnectionRequestRepository(dataSource, audit)
            val fixture = Fixture(database, dataSource, VenueOnboardingService(repository))
            fixture.seedOperationalOwnerVenue()
            block(fixture)
        }
    }

    private suspend fun withFirstApplicantFixture(block: suspend (Fixture) -> Unit) {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { dataSource ->
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO users (telegram_user_id, username, first_name, last_name)
                    VALUES (?, ?, 'First', 'Applicant'), (?, ?, 'Platform', 'Owner')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, APPLICANT_ID)
                    statement.setString(2, "first_applicant")
                    statement.setLong(3, PLATFORM_OWNER_ID)
                    statement.setString(4, "platform_owner")
                    statement.executeUpdate()
                }
            }
            val audit = AuditLogRepository(dataSource)
            val repository = VenueConnectionRequestRepository(dataSource, audit)
            block(Fixture(database, dataSource, VenueOnboardingService(repository)))
        }
    }

    private data class Fixture(
        val database: PostgresTestDatabase,
        val dataSource: DataSource,
        val service: VenueOnboardingService,
    ) {
        fun seedOperationalOwnerVenue(): Long =
            dataSource.connection.use { connection ->
                val ownerAccountId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_owner_accounts (
                            primary_owner_user_id,
                            allowed_venues_count,
                            updated_by_user_id
                        )
                        VALUES (?, 2, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, APPLICANT_ID)
                        statement.setLong(2, PLATFORM_OWNER_ID)
                        assertEquals(1, statement.executeUpdate())
                        statement.generatedKeys.use { keys ->
                            assertTrue(keys.next())
                            keys.getLong(1)
                        }
                    }
                val venueId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, city, status, owner_account_id)
                        VALUES ('Operational owner venue', 'Москва', 'PUBLISHED', ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, ownerAccountId)
                        assertEquals(1, statement.executeUpdate())
                        statement.generatedKeys.use { keys ->
                            assertTrue(keys.next())
                            keys.getLong(1)
                        }
                    }
                connection.prepareStatement(
                    "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, 'OWNER')",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, APPLICANT_ID)
                    assertEquals(1, statement.executeUpdate())
                }
                venueId
            }

        suspend fun submit(): VenueConnectionRequestRecord =
            assertIs<VenueConnectionRequestSubmitResult.Success>(
                service.submitApplication(
                    applicantUserId = APPLICANT_ID,
                    venueName = "Concurrent Venue",
                    city = "Москва",
                    contact = "@owner",
                    comment = null,
                    source = VenueOnboardingSource.VENUE_MINI_APP,
                ),
            ).request

        suspend fun approvedWithTerms(): VenueConnectionRequestRecord {
            val request = submit()
            assertIs<VenueConnectionRequestMutationResult.Success>(
                service.decideApplication(
                    requestId = request.id,
                    actorUserId = PLATFORM_OWNER_ID,
                    approved = true,
                    source = VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
            )
            return assertIs<VenueConnectionRequestMutationResult.Success>(
                service.updateCommercialTerms(
                    requestId = request.id,
                    actorUserId = PLATFORM_OWNER_ID,
                    trialConfigured = true,
                    trialEndsOn = null,
                    currentPriceRub = 10_000L,
                    futurePriceRub = null,
                    futurePriceEffectiveOn = null,
                    commercialNote = null,
                    source = VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
            ).request
        }

        fun revokeOperationalOwner() {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM venue_members WHERE user_id = ? AND UPPER(role) = 'OWNER'",
                ).use { statement ->
                    statement.setLong(1, APPLICANT_ID)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun count(table: String): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        fun countAudit(action: String): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?").use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        fun countLinkedRequests(): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM venue_connection_requests WHERE linked_venue_id IS NOT NULL",
                    ).use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getInt(1)
                    }
                }
            }

        fun countOwnerMemberships(venueId: Long): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM venue_members
                    WHERE venue_id = ? AND user_id = ? AND UPPER(role) = 'OWNER'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, APPLICANT_ID)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getInt(1)
                    }
                }
            }

        fun ownerAccountId(): Long =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM venue_owner_accounts WHERE primary_owner_user_id = ?",
                ).use { statement ->
                    statement.setLong(1, APPLICANT_ID)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        val ownerAccountId = resultSet.getLong("id")
                        assertFalse(resultSet.next())
                        ownerAccountId
                    }
                }
            }

        fun ownerAccountAllowedVenuesCount(): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT allowed_venues_count FROM venue_owner_accounts WHERE primary_owner_user_id = ?",
                ).use { statement ->
                    statement.setLong(1, APPLICANT_ID)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        val allowedVenuesCount = resultSet.getInt("allowed_venues_count")
                        assertFalse(resultSet.next())
                        allowedVenuesCount
                    }
                }
            }

        fun venueOwnerAccountIds(): Set<Long> =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT owner_account_id FROM venues ORDER BY id",
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        buildSet {
                            while (resultSet.next()) {
                                val ownerAccountId = resultSet.getLong("owner_account_id")
                                assertFalse(resultSet.wasNull(), "Every fixture venue must have an owner account")
                                add(ownerAccountId)
                            }
                        }
                    }
                }
            }

        fun venueStatus(venueId: Long): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT status FROM venues WHERE id = ?").use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString("status")
                    }
                }
            }

        fun venueTimezone(venueId: Long): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT timezone FROM venue_settings WHERE venue_id = ?").use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString("timezone")
                    }
                }
            }

        fun subscriptionStatus(venueId: Long): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM venue_subscriptions WHERE venue_id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString("status")
                    }
                }
            }

        fun requestStatus(requestId: Long): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT status FROM venue_connection_requests WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, requestId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString("status")
                    }
                }
            }

        fun linkedVenueId(requestId: Long): Long? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT linked_venue_id FROM venue_connection_requests WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, requestId)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getLong("linked_venue_id").takeIf { !resultSet.wasNull() }
                    }
                }
            }

        fun singleAuditField(
            action: String,
            field: String,
        ): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT payload_json FROM audit_log WHERE action = ?").use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        val payload = Json.parseToJsonElement(resultSet.getString("payload_json")).jsonObject
                        val value = payload.getValue(field).jsonPrimitive.content
                        assertFalse(resultSet.next(), "Expected one audit row for $action")
                        value
                    }
                }
            }

        fun auditFields(
            action: String,
            field: String,
        ): List<String> =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT payload_json FROM audit_log WHERE action = ? ORDER BY id",
                ).use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                val payload =
                                    Json.parseToJsonElement(resultSet.getString("payload_json")).jsonObject
                                add(payload.getValue(field).jsonPrimitive.content)
                            }
                        }
                    }
                }
            }
    }

    private suspend fun <T> runWhileApplicantIsBlocked(
        fixture: Fixture,
        beforeRelease: () -> Unit,
        first: suspend () -> T,
        second: suspend () -> T,
    ): List<T> =
        coroutineScope {
            fixture.database.connection().use { blocker ->
                blocker.autoCommit = false
                lockApplicant(blocker)
                val blockerPid = backendPid(blocker)

                fixture.database.connection().use { observer ->
                    val observerPid = backendPid(observer)
                    val start = CyclicBarrier(3)
                    val firstResult = async(Dispatchers.IO) { start.awaitThen(first) }
                    val secondResult = async(Dispatchers.IO) { start.awaitThen(second) }
                    try {
                        awaitBarrier(start)
                        val observation =
                            awaitApplicantWaiters(
                                observer = observer,
                                blockerPid = blockerPid,
                                requests = listOf(firstResult, secondResult),
                            )
                        assertEquals(
                            2,
                            observation.waiterPids.size,
                            "Both production onboarding transactions must wait on the applicant lock. " +
                                observation.diagnostic,
                        )
                        assertFalse(blockerPid in observation.waiterPids)
                        assertFalse(observerPid in observation.waiterPids)
                        beforeRelease()
                    } finally {
                        blocker.commit()
                    }
                    listOf(firstResult.await(), secondResult.await())
                }
            }
        }

    private fun awaitApplicantWaiters(
        observer: Connection,
        blockerPid: Int,
        requests: List<Deferred<*>>,
    ): LockObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var last = LockObservation(emptySet(), "No onboarding applicant waiters observed")
        while (System.nanoTime() < deadline) {
            val waiterPids = readApplicantWaiters(observer, blockerPid)
            last =
                LockObservation(
                    waiterPids = waiterPids,
                    diagnostic =
                        "blockerPid=$blockerPid; waiters=$waiterPids; " +
                            "activity=${describeApplicantActivity(observer)}",
                )
            if (waiterPids.size == 2) return last
            if (requests.any { it.isCompleted }) {
                return last.copy(
                    diagnostic = "An onboarding transaction completed before both reached the lock. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun readApplicantWaiters(
        observer: Connection,
        blockerPid: Int,
    ): Set<Int> =
        observer.prepareStatement(
            """
            WITH RECURSIVE applicant_waiters(pid) AS (
                SELECT pid
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND lower(query) LIKE '%from users%'
                  AND lower(query) LIKE '%telegram_user_id%'
                  AND lower(query) LIKE '%for update%'
            ),
            lock_chain(root_pid, pid, path) AS (
                SELECT pid, pid, ARRAY[pid]
                FROM applicant_waiters
                UNION ALL
                SELECT lock_chain.root_pid, blocker.pid, lock_chain.path || blocker.pid
                FROM lock_chain
                CROSS JOIN LATERAL unnest(pg_blocking_pids(lock_chain.pid)) AS blocker(pid)
                WHERE NOT blocker.pid = ANY(lock_chain.path)
            )
            SELECT DISTINCT root_pid AS pid
            FROM lock_chain
            WHERE pid = ?
            ORDER BY root_pid
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, blockerPid)
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) add(resultSet.getInt("pid"))
                }
            }
        }

    private fun describeApplicantActivity(observer: Connection): String =
        observer.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND (
                      lower(query) LIKE '%venue_connection_requests%'
                      OR lower(query) LIKE '%from users%'
                  )
                ORDER BY pid
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            "pid=${resultSet.getInt("pid")}, state=${resultSet.getString("state")}, " +
                                "wait=${resultSet.getString("wait_event_type")}/" +
                                "${resultSet.getString("wait_event")}, " +
                                "blockers=${resultSet.getString("pg_blocking_pids")}, " +
                                "query=${resultSet.getString("query").normalizedSql()}",
                        )
                    }
                }.joinToString(" | ")
            }
        }

    private fun lockApplicant(connection: Connection) {
        connection.prepareStatement(
            "SELECT telegram_user_id FROM users WHERE telegram_user_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, APPLICANT_ID)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Applicant fixture must exist")
            }
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private suspend fun <T> CyclicBarrier.awaitThen(operation: suspend () -> T): T {
        awaitBarrier(this)
        return operation()
    }

    private suspend fun awaitBarrier(barrier: CyclicBarrier) {
        withContext(Dispatchers.IO) {
            barrier.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun PostgresTestDatabase.connection(): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl, user, password)

    private fun String.normalizedSql(): String =
        trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    private data class SubmitAttempt(
        val source: VenueOnboardingSource,
        val venueName: String,
        val result: VenueConnectionRequestSubmitResult,
    )

    private data class DecisionAttempt(
        val targetStatus: String,
        val source: VenueOnboardingSource,
        val result: VenueConnectionRequestMutationResult,
    )

    private data class LinkAttempt(
        val source: VenueOnboardingSource,
        val result: VenueConnectionRequestCreateLinkResult,
    )

    private data class LockObservation(
        val waiterPids: Set<Int>,
        val diagnostic: String,
    )

    companion object {
        private const val APPLICANT_ID = 72_011L
        private const val PLATFORM_OWNER_ID = 999L
        private const val WAIT_TIMEOUT_SECONDS = 10L
        private val WHITESPACE = Regex("\\s+")
    }
}
