package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.platform.VenueOwnerAccountRepository
import com.hookah.platform.backend.test.migrateH2OnboardingFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueConnectionRequestRepositoryTest {
    @Test
    fun `exact canonical cross surface retry returns authoritative request and one privacy safe audit`() =
        withFixture("submit") { fixture ->
            runBlocking {
                val first =
                    assertIs<VenueConnectionRequestSubmitResult.Success>(
                        fixture.repository.createOrReturnActive(
                            telegramUserId = APPLICANT_ID,
                            venueName = "Smoke One",
                            city = "Москва",
                            contact = "private@example.test",
                            comment = "private comment",
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                val repeated =
                    assertIs<VenueConnectionRequestSubmitResult.Success>(
                        fixture.repository.createOrReturnActive(
                            telegramUserId = APPLICANT_ID,
                            venueName = "Smoke One",
                            city = "Москва",
                            contact = "private@example.test",
                            comment = "private comment",
                            source = VenueOnboardingSource.VENUE_MINI_APP,
                        ),
                    )

                assertTrue(first.created)
                assertEquals(false, repeated.created)
                assertEquals(first.request.id, repeated.request.id)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                val auditPayload = fixture.singleAuditPayload(VenueConnectionRequestRepository.AUDIT_SUBMITTED)
                assertTrue(auditPayload.contains("TELEGRAM_BOT"))
                assertTrue(!auditPayload.contains("private@example.test"))
                assertTrue(!auditPayload.contains("private comment"))
            }
        }

    @Test
    fun `NEL exact retry returns authoritative request and one submit audit`() =
        withFixture("canonical-nel") { fixture ->
            runBlocking {
                val first =
                    fixture.submitResult(
                        name = "Smoke One",
                        city = "Moscow",
                        contact = "@owner",
                        comment = "same",
                        source = VenueOnboardingSource.TELEGRAM_BOT,
                    )
                val retry =
                    fixture.submitResult(
                        name = "Smoke\u0085One",
                        city = "Moscow",
                        contact = "@owner",
                        comment = "same",
                        source = VenueOnboardingSource.VENUE_MINI_APP,
                    )

                assertTrue(first.created)
                assertFalse(retry.created)
                assertEquals(first.request.id, retry.request.id)
                assertEquals(first.request, retry.request)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `Unicode White Space NFKC and blank optional comment use production canonical tuple`() =
        withFixture("canonical-normalization") { fixture ->
            runBlocking {
                val unicodeWhiteSpaceCases =
                    listOf(
                        "U+0009 CHARACTER TABULATION" to "\u0009",
                        "U+000A LINE FEED" to "\u000A",
                        "U+000B LINE TABULATION" to "\u000B",
                        "U+000C FORM FEED" to "\u000C",
                        "U+000D CARRIAGE RETURN" to "\u000D",
                        "U+0020 SPACE" to "\u0020",
                        "U+0085 NEXT LINE" to "\u0085",
                        "U+00A0 NO-BREAK SPACE" to "\u00A0",
                        "U+1680 OGHAM SPACE MARK" to "\u1680",
                        "U+2000 EN QUAD" to "\u2000",
                        "U+2001 EM QUAD" to "\u2001",
                        "U+2002 EN SPACE" to "\u2002",
                        "U+2003 EM SPACE" to "\u2003",
                        "U+2004 THREE-PER-EM SPACE" to "\u2004",
                        "U+2005 FOUR-PER-EM SPACE" to "\u2005",
                        "U+2006 SIX-PER-EM SPACE" to "\u2006",
                        "U+2007 FIGURE SPACE" to "\u2007",
                        "U+2008 PUNCTUATION SPACE" to "\u2008",
                        "U+2009 THIN SPACE" to "\u2009",
                        "U+200A HAIR SPACE" to "\u200A",
                        "U+2028 LINE SEPARATOR" to "\u2028",
                        "U+2029 PARAGRAPH SEPARATOR" to "\u2029",
                        "U+202F NARROW NO-BREAK SPACE" to "\u202F",
                        "U+205F MEDIUM MATHEMATICAL SPACE" to "\u205F",
                        "U+3000 IDEOGRAPHIC SPACE" to "\u3000",
                    )
                val whitespaceOnlyComment = unicodeWhiteSpaceCases.joinToString("") { it.second }
                assertNull(
                    canonicalVenueConnectionApplicationTuple(
                        venueName = "Smoke One",
                        city = "Moscow",
                        contact = "@owner",
                        comment = whitespaceOnlyComment,
                    ).comment,
                )

                val first =
                    fixture.submitResult(
                        name = "Smoke One",
                        city = "Moscow",
                        contact = "@owner",
                        comment = whitespaceOnlyComment,
                        source = VenueOnboardingSource.TELEGRAM_BOT,
                    )
                val nullCommentRetry =
                    fixture.submitResult(
                        name = "smoke one",
                        city = "moscow",
                        contact = "@OWNER",
                        comment = null,
                        source = VenueOnboardingSource.VENUE_MINI_APP,
                    )
                unicodeWhiteSpaceCases.forEach { (label, whitespace) ->
                    val retry =
                        fixture.submitResult(
                            name = "${whitespace}Smoke${whitespace}One$whitespace",
                            city = "${whitespace}Moscow$whitespace",
                            contact = "$whitespace@OWNER$whitespace",
                            comment = null,
                            source = VenueOnboardingSource.VENUE_MINI_APP,
                        )
                    assertFalse(retry.created, "$label must be collapsed as Unicode White_Space")
                    assertEquals(first.request.id, retry.request.id, label)
                }
                val nfkcCompatibilityRetry =
                    fixture.submitResult(
                        name = "\uFF33\uFF4D\uFF4F\uFF4B\uFF45\u3000\uFF2F\uFF4E\uFF45",
                        city = "\uFF2D\uFF2F\uFF33\uFF23\uFF2F\uFF37",
                        contact = "\uFF20\uFF2F\uFF37\uFF2E\uFF25\uFF32",
                        comment = null,
                        source = VenueOnboardingSource.VENUE_MINI_APP,
                    )

                assertTrue(first.created)
                assertFalse(nullCommentRetry.created)
                assertEquals(first.request.id, nullCommentRetry.request.id)
                assertFalse(nfkcCompatibilityRetry.created)
                assertEquals(first.request.id, nfkcCompatibilityRetry.request.id)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `non White Space character creates a distinct pending request and submit audit`() =
        withFixture("canonical-distinct-non-whitespace") { fixture ->
            runBlocking {
                val first = fixture.submitResult(name = "Smoke One")
                val distinct = fixture.submitResult(name = "Smoke\u200BOne")

                assertTrue(first.created)
                assertTrue(distinct.created)
                assertNotEquals(first.request.id, distinct.request.id)
                assertEquals(VenueConnectionRequestRepository.STATUS_PENDING, distinct.request.status)
                assertEquals(2, fixture.count("venue_connection_requests"))
                assertEquals(2, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `each distinct canonical field creates an independent pending application`() =
        withFixture("distinct-fields") { fixture ->
            runBlocking {
                val baseline =
                    fixture.submitResult(
                        name = "Smoke One",
                        city = "Москва",
                        contact = "@owner",
                        comment = "first comment",
                    )
                val differentName =
                    fixture.submitResult(
                        name = "Smoke Two",
                        city = "Москва",
                        contact = "@owner",
                        comment = "first comment",
                    )
                val differentCity =
                    fixture.submitResult(
                        name = "Smoke One",
                        city = "Казань",
                        contact = "@owner",
                        comment = "first comment",
                    )
                val differentContact =
                    fixture.submitResult(
                        name = "Smoke One",
                        city = "Москва",
                        contact = "@second_owner",
                        comment = "first comment",
                    )
                val differentComment =
                    fixture.submitResult(
                        name = "Smoke One",
                        city = "Москва",
                        contact = "@owner",
                        comment = "second comment",
                    )
                val requests = listOf(baseline, differentName, differentCity, differentContact, differentComment)

                assertTrue(requests.all { it.created })
                assertEquals(5, requests.map { it.request.id }.toSet().size)
                assertTrue(requests.all { it.request.status == VenueConnectionRequestRepository.STATUS_PENDING })
                assertEquals(
                    requests.map { it.request.id }.toSet(),
                    fixture.repository.listByApplicant(APPLICANT_ID).map { it.id }.toSet(),
                )
                assertEquals(5, fixture.count("venue_connection_requests"))
                assertEquals(5, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `approved unlinked exact retry returns existing while distinct payload creates another request`() =
        withFixture("approved-retry") { fixture ->
            runBlocking {
                val first = fixture.submitResult(comment = "same")
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.repository.decide(
                        first.request.id,
                        PLATFORM_OWNER_ID,
                        VenueConnectionRequestRepository.STATUS_APPROVED,
                        VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )

                val exactRetry =
                    fixture.submitResult(
                        name = " smoke one ",
                        city = " МОСКВА ",
                        contact = " @OWNER ",
                        comment = " SAME ",
                        source = VenueOnboardingSource.TELEGRAM_BOT,
                    )
                val distinct = fixture.submitResult(name = "Smoke Two", comment = "same")

                assertFalse(exactRetry.created)
                assertEquals(first.request.id, exactRetry.request.id)
                assertEquals(VenueConnectionRequestRepository.STATUS_APPROVED, exactRetry.request.status)
                assertTrue(distinct.created)
                assertNotEquals(first.request.id, distinct.request.id)
                assertEquals(2, fixture.count("venue_connection_requests"))
                assertEquals(2, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `rejected and cancelled rows do not block the same canonical application`() =
        withFixture("closed-retry") { fixture ->
            runBlocking {
                val rejected = fixture.submitResult()
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.repository.decide(
                        rejected.request.id,
                        PLATFORM_OWNER_ID,
                        VenueConnectionRequestRepository.STATUS_REJECTED,
                        VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )
                val afterRejected = fixture.submitResult(source = VenueOnboardingSource.TELEGRAM_BOT)
                assertTrue(afterRejected.created)
                assertNotEquals(rejected.request.id, afterRejected.request.id)

                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.repository.cancel(
                        afterRejected.request.id,
                        APPLICANT_ID,
                        VenueOnboardingSource.TELEGRAM_BOT,
                    ),
                )
                val afterCancelled = fixture.submitResult(source = VenueOnboardingSource.VENUE_MINI_APP)

                assertTrue(afterCancelled.created)
                assertNotEquals(afterRejected.request.id, afterCancelled.request.id)
                assertEquals(3, fixture.count("venue_connection_requests"))
                assertEquals(3, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `simultaneous same cross surface payload inserts once and distinct payloads both commit`() =
        withFixture("concurrent-submit") { fixture ->
            runBlocking {
                val same =
                    runConcurrently(
                        {
                            fixture.submitResult(
                                name = "Concurrent Venue",
                                city = "Москва",
                                contact = "@owner",
                                comment = "same",
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                            )
                        },
                        {
                            fixture.submitResult(
                                name = " concurrent   venue ",
                                city = " МОСКВА ",
                                contact = " @OWNER ",
                                comment = " SAME ",
                                source = VenueOnboardingSource.VENUE_MINI_APP,
                            )
                        },
                    )
                assertEquals(1, same.count { it.created })
                assertEquals(1, same.map { it.request.id }.toSet().size)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))

                val distinct =
                    runConcurrently(
                        {
                            fixture.submitResult(
                                name = "Concurrent Venue Two",
                                source = VenueOnboardingSource.TELEGRAM_BOT,
                            )
                        },
                        {
                            fixture.submitResult(
                                name = "Concurrent Venue Three",
                                source = VenueOnboardingSource.VENUE_MINI_APP,
                            )
                        },
                    )
                assertTrue(distinct.all { it.created })
                assertEquals(2, distinct.map { it.request.id }.toSet().size)
                assertEquals(3, fixture.count("venue_connection_requests"))
                assertEquals(3, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
            }
        }

    @Test
    fun `pending no op writes no false audit and cancelled request allows a new application`() =
        withFixture("pending-cancel") { fixture ->
            runBlocking {
                val first = fixture.submit()
                val noOp =
                    fixture.repository.updatePending(
                        requestId = first.id,
                        telegramUserId = APPLICANT_ID,
                        venueName = first.venueName,
                        city = first.city,
                        contact = first.contact,
                        comment = first.comment,
                        source = VenueOnboardingSource.VENUE_MINI_APP,
                    )
                assertIs<VenueConnectionRequestMutationResult.Success>(noOp)
                assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_UPDATED))

                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.repository.cancel(
                        first.id,
                        APPLICANT_ID,
                        VenueOnboardingSource.VENUE_MINI_APP,
                    ),
                )
                val second = fixture.submit(name = "Smoke Two")
                assertNotEquals(first.id, second.id)
                assertEquals(VenueConnectionRequestRepository.STATUS_CANCELLED, fixture.find(first.id).status)
                assertEquals(2, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_CANCELLED))
            }
        }

    @Test
    fun `create and link is retry safe atomic and creates zero menu categories`() =
        withFixture("create-link") { fixture ->
            runBlocking {
                val approved = fixture.approvedWithTerms(city = "Пермь")

                val first =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.repository.createDraftAndLink(
                            approved.id,
                            PLATFORM_OWNER_ID,
                            VenueOnboardingSource.PLATFORM_MINI_APP,
                        ),
                    )
                val retry =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.repository.createDraftAndLink(
                            approved.id,
                            PLATFORM_OWNER_ID,
                            VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )

                assertTrue(first.created)
                assertEquals(false, retry.created)
                assertEquals(first.venueId, retry.venueId)
                assertEquals(2, fixture.count("venues"))
                assertEquals(2, fixture.count("venue_members"))
                assertEquals(1, fixture.count("venue_subscription_settings"))
                assertEquals(1, fixture.count("venue_subscriptions"))
                assertEquals("Asia/Yekaterinburg", fixture.venueTimezone(first.venueId))
                assertEquals(0, fixture.count("menu_categories"))
                assertEquals(first.venueId, fixture.find(approved.id).linkedVenueId)
                assertEquals(1, fixture.countAudit("VENUE_CREATE"))
                assertEquals(1, fixture.countAudit("VENUE_OWNER_ASSIGN"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
            }
        }

    @Test
    fun `exhausted quota and injected audit failure leave zero partial venue state then retry succeeds`() =
        withFixture("rollback") { fixture ->
            runBlocking {
                VenueOwnerAccountRepository(fixture.dataSource).setAllowedVenuesCount(
                    fixture.ownerAccountId,
                    count = 1,
                    updatedByUserId = PLATFORM_OWNER_ID,
                )
                val approved = fixture.approvedWithTerms()
                assertEquals(1, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(fixture.ownerAccountId, fixture.venueOwnerAccountId(fixture.existingVenueId))
                assertTrue(fixture.hasOwnerMembership(fixture.existingVenueId, APPLICANT_ID))

                val exhausted =
                    fixture.repository.createDraftAndLink(
                        approved.id,
                        PLATFORM_OWNER_ID,
                        VenueOnboardingSource.PLATFORM_MINI_APP,
                    )
                assertIs<VenueConnectionRequestCreateLinkResult.QuotaExceeded>(exhausted)
                assertEquals(1, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(0, fixture.count("venue_settings"))
                assertNull(fixture.find(approved.id).linkedVenueId)

                VenueOwnerAccountRepository(fixture.dataSource).setAllowedVenuesCount(
                    fixture.ownerAccountId,
                    count = 2,
                    updatedByUserId = PLATFORM_OWNER_ID,
                )
                val failingWriter =
                    TransactionalAuditLogWriter { connection, actor, action, entityType, entityId, payload ->
                        if (action == "VENUE_OWNER_ASSIGN") error("injected audit failure")
                        fixture.audit.appendJson(connection, actor, action, entityType, entityId, payload)
                    }
                val failingRepository = VenueConnectionRequestRepository(fixture.dataSource, failingWriter)
                assertFailsWith<IllegalStateException> {
                    failingRepository.createDraftAndLink(
                        approved.id,
                        PLATFORM_OWNER_ID,
                        VenueOnboardingSource.PLATFORM_MINI_APP,
                    )
                }
                assertEquals(1, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(0, fixture.count("venue_settings"))
                assertNull(fixture.find(approved.id).linkedVenueId)
                assertEquals(0, fixture.countAudit("VENUE_CREATE"))
                assertEquals(0, fixture.countAudit("VENUE_OWNER_ASSIGN"))

                val retry =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.repository.createDraftAndLink(
                            approved.id,
                            PLATFORM_OWNER_ID,
                            VenueOnboardingSource.PLATFORM_MINI_APP,
                        ),
                    )
                assertTrue(retry.created)
                assertEquals(2, fixture.count("venues"))
                assertEquals(2, fixture.count("venue_members"))
                assertEquals(1, fixture.count("venue_settings"))
                assertEquals(retry.venueId, fixture.find(approved.id).linkedVenueId)
                assertEquals(fixture.ownerAccountId, fixture.venueOwnerAccountId(fixture.existingVenueId))
                assertTrue(fixture.hasOwnerMembership(fixture.existingVenueId, APPLICANT_ID))
            }
        }

    @Test
    fun `first applicant shared writer submits edits and cancels with zero operational writes`() =
        withFirstApplicantFixture("first-submit") { fixture ->
            runBlocking {
                val submitted =
                    assertIs<VenueConnectionRequestSubmitResult.Success>(
                        fixture.repository.createOrReturnActive(
                            telegramUserId = APPLICANT_ID,
                            venueName = "First Venue",
                            city = "Москва",
                            contact = "@first_owner",
                            comment = null,
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                assertTrue(submitted.created)
                assertEquals(VenueConnectionRequestRepository.STATUS_PENDING, submitted.request.status)
                assertNull(submitted.request.linkedVenueId)
                assertEquals(1, fixture.count("venue_connection_requests"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_SUBMITTED))
                assertEquals(0, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
                assertEquals(0, fixture.countAudit("VENUE_CREATE"))
                assertEquals(0, fixture.countAudit("VENUE_OWNER_ASSIGN"))
                fixture.assertZeroOperationalWrites()

                val edited =
                    assertIs<VenueConnectionRequestMutationResult.Success>(
                        fixture.repository.updatePending(
                            requestId = submitted.request.id,
                            telegramUserId = APPLICANT_ID,
                            venueName = "First Venue Edited",
                            city = "Москва",
                            contact = "@first_owner",
                            comment = "updated",
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                assertEquals("First Venue Edited", edited.request.venueName)
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_UPDATED))
                fixture.assertZeroOperationalWrites()

                val cancelled =
                    assertIs<VenueConnectionRequestMutationResult.Success>(
                        fixture.repository.cancel(
                            submitted.request.id,
                            APPLICANT_ID,
                            VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                assertEquals(VenueConnectionRequestRepository.STATUS_CANCELLED, cancelled.request.status)
                fixture.assertZeroOperationalWrites()
            }
        }

    @Test
    fun `platform create and link initializes first applicant account quota and owner exactly once`() =
        withFirstApplicantFixture("first-link") { fixture ->
            runBlocking {
                val submitted =
                    assertIs<VenueConnectionRequestSubmitResult.Success>(
                        fixture.repository.createOrReturnActive(
                            telegramUserId = APPLICANT_ID,
                            venueName = "First Venue",
                            city = "Пермь",
                            contact = "@first_owner",
                            comment = null,
                            source = VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.repository.decide(
                        submitted.request.id,
                        PLATFORM_OWNER_ID,
                        VenueConnectionRequestRepository.STATUS_APPROVED,
                        VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    fixture.repository.updateTerms(
                        requestId = submitted.request.id,
                        actorUserId = PLATFORM_OWNER_ID,
                        trialConfigured = true,
                        trialEndsOn = null,
                        currentPriceRub = 10_000L,
                        futurePriceRub = null,
                        futurePriceEffectiveOn = null,
                        commercialNote = null,
                        source = VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )
                fixture.assertZeroOperationalWrites()

                val linked =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.repository.createDraftAndLink(
                            submitted.request.id,
                            PLATFORM_OWNER_ID,
                            VenueOnboardingSource.PLATFORM_MINI_APP,
                        ),
                    )
                val retry =
                    assertIs<VenueConnectionRequestCreateLinkResult.Success>(
                        fixture.repository.createDraftAndLink(
                            submitted.request.id,
                            PLATFORM_OWNER_ID,
                            VenueOnboardingSource.TELEGRAM_BOT,
                        ),
                    )

                assertTrue(linked.created)
                assertFalse(retry.created)
                assertEquals(linked.venueId, retry.venueId)
                assertEquals(1, fixture.count("venues"))
                assertEquals(1, fixture.count("venue_members"))
                assertEquals(1, fixture.count("venue_owner_accounts"))
                assertEquals(1, fixture.count("venue_settings"))
                assertEquals(1, fixture.count("venue_subscription_settings"))
                assertEquals(1, fixture.count("venue_subscriptions"))
                assertEquals(0, fixture.count("menu_categories"))
                assertEquals(
                    OwnerAccountSnapshot(
                        primaryOwnerUserId = APPLICANT_ID,
                        allowedVenuesCount = 1,
                    ),
                    fixture.singleOwnerAccount(),
                )
                assertEquals(1, fixture.countOwnerMemberships(linked.venueId, APPLICANT_ID))
                assertEquals("DRAFT", fixture.venueStatus(linked.venueId))
                assertEquals(linked.venueId, fixture.find(submitted.request.id).linkedVenueId)
                assertEquals(1, fixture.countAudit("VENUE_CREATE"))
                assertEquals(1, fixture.countAudit("VENUE_OWNER_ASSIGN"))
                assertEquals(1, fixture.countAudit(VenueConnectionRequestRepository.AUDIT_LINKED))
            }
        }

    private fun withFixture(
        suffix: String,
        block: (Fixture) -> Unit,
    ) {
        val jdbcUrl =
            "jdbc:h2:mem:onboarding-$suffix-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val dataSource =
            JdbcDataSource().apply {
                setURL(jdbcUrl)
                user = "sa"
                password = ""
            }
        migrateH2OnboardingFixture(dataSource)
        dataSource.connection.use { connection ->
            seedUser(connection, APPLICANT_ID, "venue_owner")
            seedUser(connection, PLATFORM_OWNER_ID, "platform_owner")
        }
        val ownerAccountId =
            runBlocking {
                VenueOwnerAccountRepository(dataSource)
                    .getOrCreateForOwner(APPLICANT_ID, defaultLimit = 2, updatedByUserId = PLATFORM_OWNER_ID)
                    .id
            }
        val existingVenueId = seedExistingOwnedVenue(dataSource, ownerAccountId)
        val audit = AuditLogRepository(dataSource)
        block(
            Fixture(
                dataSource = dataSource,
                audit = audit,
                repository = VenueConnectionRequestRepository(dataSource, audit),
                ownerAccountId = ownerAccountId,
                existingVenueId = existingVenueId,
            ),
        )
    }

    private fun withFirstApplicantFixture(
        suffix: String,
        block: (FirstApplicantFixture) -> Unit,
    ) {
        val jdbcUrl =
            "jdbc:h2:mem:onboarding-$suffix-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val dataSource =
            JdbcDataSource().apply {
                setURL(jdbcUrl)
                user = "sa"
                password = ""
            }
        migrateH2OnboardingFixture(dataSource)
        dataSource.connection.use { connection ->
            seedUser(connection, APPLICANT_ID, "first_applicant")
            seedUser(connection, PLATFORM_OWNER_ID, "platform_owner")
        }
        val audit = AuditLogRepository(dataSource)
        block(
            FirstApplicantFixture(
                dataSource = dataSource,
                repository = VenueConnectionRequestRepository(dataSource, audit),
            ),
        )
    }

    private suspend fun <T> runConcurrently(
        first: suspend () -> T,
        second: suspend () -> T,
    ): List<T> =
        coroutineScope {
            val start = CyclicBarrier(3)
            val firstResult = async(Dispatchers.IO) { start.awaitThen(first) }
            val secondResult = async(Dispatchers.IO) { start.awaitThen(second) }
            withContext(Dispatchers.IO) {
                start.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            listOf(firstResult.await(), secondResult.await())
        }

    private suspend fun <T> CyclicBarrier.awaitThen(operation: suspend () -> T): T {
        withContext(Dispatchers.IO) {
            await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return operation()
    }

    private data class Fixture(
        val dataSource: JdbcDataSource,
        val audit: AuditLogRepository,
        val repository: VenueConnectionRequestRepository,
        val ownerAccountId: Long,
        val existingVenueId: Long,
    ) {
        suspend fun submitResult(
            name: String = "Smoke One",
            city: String = "Москва",
            contact: String = "@owner",
            comment: String? = null,
            source: VenueOnboardingSource = VenueOnboardingSource.VENUE_MINI_APP,
        ): VenueConnectionRequestSubmitResult.Success =
            assertIs<VenueConnectionRequestSubmitResult.Success>(
                repository.createOrReturnActive(
                    telegramUserId = APPLICANT_ID,
                    venueName = name,
                    city = city,
                    contact = contact,
                    comment = comment,
                    source = source,
                ),
            )

        suspend fun submit(
            name: String = "Smoke One",
            city: String = "Москва",
        ): VenueConnectionRequestRecord = submitResult(name = name, city = city).request

        suspend fun approvedWithTerms(city: String = "Москва"): VenueConnectionRequestRecord {
            val request = submit(city = city)
            assertIs<VenueConnectionRequestMutationResult.Success>(
                repository.decide(
                    request.id,
                    PLATFORM_OWNER_ID,
                    VenueConnectionRequestRepository.STATUS_APPROVED,
                    VenueOnboardingSource.PLATFORM_MINI_APP,
                ),
            )
            val terms =
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    repository.updateTerms(
                        requestId = request.id,
                        actorUserId = PLATFORM_OWNER_ID,
                        trialConfigured = true,
                        trialEndsOn = null,
                        currentPriceRub = 10_000L,
                        futurePriceRub = null,
                        futurePriceEffectiveOn = null,
                        commercialNote = "safe note",
                        source = VenueOnboardingSource.PLATFORM_MINI_APP,
                    ),
                )
            val termsNoOp =
                assertIs<VenueConnectionRequestMutationResult.Success>(
                    repository.updateTerms(
                        requestId = request.id,
                        actorUserId = PLATFORM_OWNER_ID,
                        trialConfigured = true,
                        trialEndsOn = null,
                        currentPriceRub = 10_000L,
                        futurePriceRub = null,
                        futurePriceEffectiveOn = null,
                        commercialNote = "safe note",
                        source = VenueOnboardingSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(terms.request, termsNoOp.request)
            assertEquals(1, countAudit(VenueConnectionRequestRepository.AUDIT_TERMS_UPDATED))
            return terms.request
        }

        fun venueOwnerAccountId(venueId: Long): Long? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT owner_account_id FROM venues WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getLong(1).takeIf { !rs.wasNull() }
                    }
                }
            }

        fun venueTimezone(venueId: Long): String? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT timezone FROM venue_settings WHERE venue_id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("timezone") else null
                    }
                }
            }

        fun hasOwnerMembership(
            venueId: Long,
            userId: Long,
        ): Boolean =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM venue_members WHERE venue_id = ? AND user_id = ? AND role = 'OWNER'",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rs -> rs.next() }
                }
            }

        fun find(requestId: Long): VenueConnectionRequestRecord =
            runBlocking { repository.findById(requestId) } ?: error("request missing")

        fun count(table: String): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                        assertTrue(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun countAudit(action: String): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?").use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun singleAuditPayload(action: String): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT payload_json FROM audit_log WHERE action = ?").use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getString(1)
                    }
                }
            }
    }

    private data class FirstApplicantFixture(
        val dataSource: JdbcDataSource,
        val repository: VenueConnectionRequestRepository,
    ) {
        fun find(requestId: Long): VenueConnectionRequestRecord =
            runBlocking { repository.findById(requestId) } ?: error("request missing")

        fun count(table: String): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                        assertTrue(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun countAudit(action: String): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?").use { statement ->
                    statement.setString(1, action)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun assertZeroOperationalWrites() {
            assertEquals(0, count("venues"))
            assertEquals(0, count("venue_members"))
            assertEquals(0, count("venue_owner_accounts"))
            assertEquals(0, count("venue_settings"))
            assertEquals(0, count("venue_subscription_settings"))
            assertEquals(0, count("venue_subscriptions"))
            assertEquals(0, count("menu_categories"))
        }

        fun singleOwnerAccount(): OwnerAccountSnapshot =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT primary_owner_user_id, allowed_venues_count FROM venue_owner_accounts",
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        val snapshot =
                            OwnerAccountSnapshot(
                                primaryOwnerUserId = rs.getLong("primary_owner_user_id"),
                                allowedVenuesCount = rs.getInt("allowed_venues_count"),
                            )
                        assertFalse(rs.next())
                        snapshot
                    }
                }
            }

        fun countOwnerMemberships(
            venueId: Long,
            ownerUserId: Long,
        ): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM venue_members
                    WHERE venue_id = ? AND user_id = ? AND UPPER(role) = 'OWNER'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, ownerUserId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getInt(1)
                    }
                }
            }

        fun venueStatus(venueId: Long): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT status FROM venues WHERE id = ?").use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        rs.getString("status")
                    }
                }
            }
    }

    private data class OwnerAccountSnapshot(
        val primaryOwnerUserId: Long,
        val allowedVenuesCount: Int,
    )

    companion object {
        private const val APPLICANT_ID = 7011L
        private const val PLATFORM_OWNER_ID = 999L
        private const val CONCURRENCY_TIMEOUT_SECONDS = 10L

        private fun seedUser(
            connection: Connection,
            userId: Long,
            username: String,
        ) {
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, last_name)
                VALUES (?, ?, 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, username)
                statement.executeUpdate()
            }
        }

        private fun seedExistingOwnedVenue(
            dataSource: JdbcDataSource,
            ownerAccountId: Long,
        ): Long =
            dataSource.connection.use { connection ->
                val venueId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, status, owner_account_id)
                        VALUES ('Existing pilot venue', 'DRAFT', ?)
                        """.trimIndent(),
                        java.sql.Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, ownerAccountId)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            assertTrue(keys.next())
                            keys.getLong(1)
                        }
                    }
                connection.prepareStatement(
                    """
                    INSERT INTO venue_members (venue_id, user_id, role, invited_by_user_id)
                    VALUES (?, ?, 'OWNER', ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, APPLICANT_ID)
                    statement.setLong(3, PLATFORM_OWNER_ID)
                    statement.executeUpdate()
                }
                venueId
            }
    }
}
