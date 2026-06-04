package com.hookah.platform.backend.platform

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueOwnerAccountRepositoryTest {
    @Test
    fun `migration backfills owner account and links existing owner venues`() {
        val jdbcUrl = freshJdbcUrl("owner-account-backfill")
        migrate(jdbcUrl, target = "67")
        seedUser(jdbcUrl, OWNER_USER_ID)
        val draftVenueId = seedVenue(jdbcUrl, "DRAFT")
        val publishedVenueId = seedVenue(jdbcUrl, "PUBLISHED")
        val archivedVenueId = seedVenue(jdbcUrl, "ARCHIVED")
        seedOwner(jdbcUrl, draftVenueId, OWNER_USER_ID)
        seedOwner(jdbcUrl, publishedVenueId, OWNER_USER_ID)
        seedOwner(jdbcUrl, archivedVenueId, OWNER_USER_ID)

        migrate(jdbcUrl)

        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val accountId =
                connection.prepareStatement(
                    """
                    SELECT id, allowed_venues_count
                    FROM venue_owner_accounts
                    WHERE primary_owner_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, OWNER_USER_ID)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getInt("allowed_venues_count") >= 2)
                        rs.getLong("id")
                    }
                }
            listOf(draftVenueId, publishedVenueId, archivedVenueId).forEach { venueId ->
                connection.prepareStatement(
                    "SELECT owner_account_id FROM venues WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(accountId, rs.getLong("owner_account_id"))
                    }
                }
            }
        }
    }

    @Test
    fun `used venues count includes operational statuses and excludes archived and deleted`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-used-count")
            seedUser(jdbcUrl, OWNER_USER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 10)
            listOf("DRAFT", "PUBLISHED", "HIDDEN", "PAUSED", "SUSPENDED", "ARCHIVED", "DELETED")
                .forEach { status ->
                    seedVenue(jdbcUrl, status, ownerAccountId = account.id)
                }

            val summary = assertNotNull(repository.getQuotaSummary(account.id))

            assertEquals(5, summary.usedVenuesCount)
            assertEquals(5, summary.availableVenuesCount)
        }

    @Test
    fun `owner account venues list includes linked non deleted venues`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-venues-list")
            seedUser(jdbcUrl, OWNER_USER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 10)
            seedVenue(jdbcUrl, "DRAFT", ownerAccountId = account.id)
            seedVenue(jdbcUrl, "PUBLISHED", ownerAccountId = account.id)
            seedVenue(jdbcUrl, "DELETED", ownerAccountId = account.id)

            val venues = repository.listVenuesByOwnerAccount(account.id)

            assertEquals(listOf("DRAFT", "PUBLISHED"), venues.map { it.status })
            assertEquals(listOf("Venue", "Venue"), venues.map { it.name })
        }

    @Test
    fun `quota blocks creation when limit is exhausted and allows when capacity remains`() {
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-quota")
            seedUser(jdbcUrl, OWNER_USER_ID)
            seedUser(jdbcUrl, PLATFORM_OWNER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 1)
            seedVenue(jdbcUrl, "DRAFT", ownerAccountId = account.id)

            val exhausted = repository.ensureCanCreateVenue(account.id)
            assertIs<VenueOwnerQuotaCheckResult.LimitExceeded>(exhausted)

            repository.setAllowedVenuesCount(account.id, count = 2, updatedByUserId = PLATFORM_OWNER_ID)
            val allowed = repository.ensureCanCreateVenue(account.id)
            assertIs<VenueOwnerQuotaCheckResult.Allowed>(allowed)
        }
    }

    @Test
    fun `create owned draft venue links owner account and owner membership within quota`() {
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-create-draft")
            seedUser(jdbcUrl, OWNER_USER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 2)

            val result =
                repository.createOwnedDraftVenue(
                    ownerUserId = OWNER_USER_ID,
                    name = "Second Venue",
                    city = "Moscow",
                    address = "Arbat",
                )

            val success = assertIs<VenueOwnerVenueCreationResult.Success>(result)
            assertEquals(1, success.summary.usedVenuesCount)
            assertEquals(1, success.summary.availableVenuesCount)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    "SELECT owner_account_id, status FROM venues WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, success.venueId)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals(account.id, rs.getLong("owner_account_id"))
                        assertEquals("DRAFT", rs.getString("status"))
                    }
                }
                connection.prepareStatement(
                    "SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, success.venueId)
                    statement.setLong(2, OWNER_USER_ID)
                    statement.executeQuery().use { rs ->
                        assertTrue(rs.next())
                        assertEquals("OWNER", rs.getString("role"))
                    }
                }
            }
        }
    }

    @Test
    fun `create owned draft venue is blocked when quota is exhausted`() {
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-create-exhausted")
            seedUser(jdbcUrl, OWNER_USER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 1)
            seedVenue(jdbcUrl, "DRAFT", ownerAccountId = account.id)

            val result =
                repository.createOwnedDraftVenue(
                    ownerUserId = OWNER_USER_ID,
                    name = "Extra Venue",
                    city = "Moscow",
                    address = "Arbat",
                )

            assertIs<VenueOwnerVenueCreationResult.LimitExceeded>(result)
        }
    }

    @Test
    fun `limit request full approval stores approved count and rejection keeps count`() {
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-limit-requests")
            seedUser(jdbcUrl, OWNER_USER_ID)
            seedUser(jdbcUrl, PLATFORM_OWNER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 1)
            val approveRequest =
                repository.createLimitRequest(
                    account.id,
                    requestedExtraCount = 2,
                    comment = "Need growth",
                )
            val rejectRequest = repository.createLimitRequest(account.id, requestedExtraCount = 3, comment = "Later")

            val pending = repository.listPendingLimitRequests()
            assertEquals(listOf(approveRequest.id, rejectRequest.id), pending.map { it.request.id })

            val approved = repository.approveLimitRequest(approveRequest.id, PLATFORM_OWNER_ID)
            val approvedSuccess = assertIs<VenueOwnerLimitRequestDecisionResult.Success>(approved)
            assertEquals(2, approvedSuccess.request.approvedExtraCount)
            assertEquals(3, assertNotNull(repository.getQuotaSummary(account.id)).account.allowedVenuesCount)

            val rejected = repository.rejectLimitRequest(rejectRequest.id, PLATFORM_OWNER_ID)
            val rejectedSuccess = assertIs<VenueOwnerLimitRequestDecisionResult.Success>(rejected)
            assertEquals(null, rejectedSuccess.request.approvedExtraCount)
            assertEquals(3, assertNotNull(repository.getQuotaSummary(account.id)).account.allowedVenuesCount)
        }
    }

    @Test
    fun `limit request partial approval increases allowed venues by approved count`() {
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-limit-partial")
            seedUser(jdbcUrl, OWNER_USER_ID)
            seedUser(jdbcUrl, PLATFORM_OWNER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 1)
            val request = repository.createLimitRequest(account.id, requestedExtraCount = 3, comment = "Need growth")

            val approved =
                repository.approveLimitRequestPartial(
                    request.id,
                    approvedExtraCount = 2,
                    decidedByUserId = PLATFORM_OWNER_ID,
                )
            val approvedSuccess = assertIs<VenueOwnerLimitRequestDecisionResult.Success>(approved)

            assertEquals(2, approvedSuccess.request.approvedExtraCount)
            assertEquals(3, assertNotNull(repository.getQuotaSummary(account.id)).account.allowedVenuesCount)
        }
    }

    @Test
    fun `limit request partial approval rejects invalid count and already decided request`() {
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("owner-account-limit-partial-invalid")
            seedUser(jdbcUrl, OWNER_USER_ID)
            seedUser(jdbcUrl, PLATFORM_OWNER_ID)
            val repository = VenueOwnerAccountRepository(dataSource(jdbcUrl))
            val account = repository.getOrCreateForOwner(OWNER_USER_ID, defaultLimit = 1)
            val request = repository.createLimitRequest(account.id, requestedExtraCount = 3, comment = "Need growth")

            assertIs<VenueOwnerLimitRequestDecisionResult.InvalidApprovedCount>(
                repository.approveLimitRequestPartial(
                    request.id,
                    approvedExtraCount = 0,
                    decidedByUserId = PLATFORM_OWNER_ID,
                ),
            )
            assertIs<VenueOwnerLimitRequestDecisionResult.InvalidApprovedCount>(
                repository.approveLimitRequestPartial(
                    request.id,
                    approvedExtraCount = 4,
                    decidedByUserId = PLATFORM_OWNER_ID,
                ),
            )
            assertEquals(1, assertNotNull(repository.getQuotaSummary(account.id)).account.allowedVenuesCount)

            assertIs<VenueOwnerLimitRequestDecisionResult.Success>(
                repository.approveLimitRequestPartial(
                    request.id,
                    approvedExtraCount = 2,
                    decidedByUserId = PLATFORM_OWNER_ID,
                ),
            )
            assertIs<VenueOwnerLimitRequestDecisionResult.AlreadyDecided>(
                repository.approveLimitRequestPartial(
                    request.id,
                    approvedExtraCount = 1,
                    decidedByUserId = PLATFORM_OWNER_ID,
                ),
            )
        }
    }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl = freshJdbcUrl(prefix)
        migrate(jdbcUrl)
        return jdbcUrl
    }

    private fun freshJdbcUrl(prefix: String): String =
        "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    private fun migrate(
        jdbcUrl: String,
        target: String? = null,
    ) {
        val config =
            Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration/h2")
        if (target != null) {
            config.target(target)
        }
        config.load().migrate()
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, ?, 'Owner', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "owner$userId")
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: String,
        ownerAccountId: Long? = null,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val sql =
                if (ownerAccountId == null) {
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent()
                } else {
                    """
                    INSERT INTO venues (name, city, address, status, owner_account_id)
                    VALUES ('Venue', 'City', 'Address', ?, ?)
                    """.trimIndent()
                }
            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
                statement.setString(1, status)
                if (ownerAccountId != null) {
                    statement.setLong(2, ownerAccountId)
                }
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedOwner(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role, created_at)
                VALUES (?, ?, 'OWNER', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val OWNER_USER_ID = 71001L
        const val PLATFORM_OWNER_ID = 99001L
    }
}
