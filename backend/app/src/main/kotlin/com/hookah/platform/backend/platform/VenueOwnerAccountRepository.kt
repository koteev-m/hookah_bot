package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource

class VenueOwnerAccountRepository(private val dataSource: DataSource?) {
    suspend fun getOrCreateForOwner(
        userId: Long,
        defaultLimit: Int,
        updatedByUserId: Long? = null,
    ): VenueOwnerAccount {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val account =
                            getOrCreateForOwnerInTransaction(
                                connection = connection,
                                userId = userId,
                                defaultLimit = defaultLimit,
                                updatedByUserId = updatedByUserId,
                            )
                        connection.commit()
                        account
                    } catch (e: SQLException) {
                        rollbackBestEffort(connection)
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findByOwner(userId: Long): VenueOwnerAccount? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    loadAccountByOwner(connection, userId, forUpdate = false)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun countUsedVenues(ownerAccountId: Long): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    countUsedVenues(connection, ownerAccountId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getQuotaSummary(ownerAccountId: Long): VenueOwnerQuotaSummary? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    buildQuotaSummary(connection, ownerAccountId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listVenuesByOwnerAccount(ownerAccountId: Long): List<VenueOwnerAccountVenue> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT id, name, city, status, created_at
                        FROM venues
                        WHERE owner_account_id = ?
                          AND COALESCE(status, 'DRAFT') <> 'DELETED'
                        ORDER BY id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, ownerAccountId)
                        statement.executeQuery().use { rs ->
                            val venues = mutableListOf<VenueOwnerAccountVenue>()
                            while (rs.next()) {
                                venues +=
                                    VenueOwnerAccountVenue(
                                        id = rs.getLong("id"),
                                        name = rs.getString("name"),
                                        city = rs.getString("city"),
                                        status = rs.getString("status") ?: "DRAFT",
                                        createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
                                    )
                            }
                            venues
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setAllowedVenuesCount(
        ownerAccountId: Long,
        count: Int,
        updatedByUserId: Long?,
        notes: String? = null,
        commercialNote: String? = null,
    ): VenueOwnerQuotaSummary? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val updatedRows =
                            connection.prepareStatement(
                                """
                                UPDATE venue_owner_accounts
                                SET allowed_venues_count = ?,
                                    notes = ?,
                                    commercial_note = ?,
                                    updated_at = CURRENT_TIMESTAMP,
                                    updated_by_user_id = ?
                                WHERE id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setInt(1, count)
                                statement.setString(2, notes)
                                statement.setString(3, commercialNote)
                                if (updatedByUserId != null) {
                                    statement.setLong(4, updatedByUserId)
                                } else {
                                    statement.setNull(4, Types.BIGINT)
                                }
                                statement.setLong(5, ownerAccountId)
                                statement.executeUpdate()
                            }
                        if (updatedRows == 0) {
                            connection.rollback()
                            return@withContext null
                        }
                        val summary = buildQuotaSummary(connection, ownerAccountId)
                        connection.commit()
                        summary
                    } catch (e: SQLException) {
                        rollbackBestEffort(connection)
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun ensureCanCreateVenue(ownerAccountId: Long): VenueOwnerQuotaCheckResult {
        val ds = dataSource ?: return VenueOwnerQuotaCheckResult.DatabaseError
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val summary =
                        buildQuotaSummary(connection, ownerAccountId)
                            ?: return@use VenueOwnerQuotaCheckResult.NotFound
                    if (summary.usedVenuesCount >= summary.account.allowedVenuesCount) {
                        VenueOwnerQuotaCheckResult.LimitExceeded(summary)
                    } else {
                        VenueOwnerQuotaCheckResult.Allowed(summary)
                    }
                }
            } catch (e: SQLException) {
                VenueOwnerQuotaCheckResult.DatabaseError
            }
        }
    }

    suspend fun createLimitRequest(
        ownerAccountId: Long,
        requestedExtraCount: Int,
        comment: String?,
    ): VenueOwnerLimitRequest {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO owner_venue_limit_requests (owner_account_id, requested_extra_count, comment, status)
                        VALUES (?, ?, ?, 'PENDING')
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, ownerAccountId)
                        statement.setInt(2, requestedExtraCount)
                        statement.setString(3, comment)
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            if (rs.next()) {
                                return@withContext loadLimitRequest(connection, rs.getLong(1))
                                    ?: throw DatabaseUnavailableException()
                            }
                        }
                    }
                    throw DatabaseUnavailableException()
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listPendingLimitRequests(limit: Int = 20): List<VenueOwnerLimitRequestSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT r.id
                        FROM owner_venue_limit_requests r
                        WHERE UPPER(r.status) = 'PENDING'
                        ORDER BY r.created_at ASC, r.id ASC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, limit)
                        statement.executeQuery().use { rs ->
                            val ids = mutableListOf<Long>()
                            while (rs.next()) {
                                ids += rs.getLong("id")
                            }
                            ids.mapNotNull { id -> buildLimitRequestSummary(connection, id) }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findLimitRequestSummary(requestId: Long): VenueOwnerLimitRequestSummary? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    buildLimitRequestSummary(connection, requestId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createOwnedDraftVenue(
        ownerUserId: Long,
        name: String,
        city: String,
        address: String,
        defaultLimit: Int = 1,
    ): VenueOwnerVenueCreationResult {
        val ds = dataSource ?: return VenueOwnerVenueCreationResult.DatabaseError
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val account =
                            getOrCreateForOwnerInTransaction(
                                connection = connection,
                                userId = ownerUserId,
                                defaultLimit = defaultLimit,
                                updatedByUserId = ownerUserId,
                            )
                        val summary =
                            buildQuotaSummary(connection, account.id)
                                ?: return@use rollbackAndReturn(connection) {
                                    VenueOwnerVenueCreationResult.DatabaseError
                                }
                        if (summary.usedVenuesCount >= summary.account.allowedVenuesCount) {
                            return@use rollbackAndReturn(connection) {
                                VenueOwnerVenueCreationResult.LimitExceeded(summary)
                            }
                        }
                        val venueId = insertOwnedDraftVenue(connection, name, city, address, account.id)
                        ensureOwnerMembership(connection, venueId, ownerUserId, invitedByUserId = ownerUserId)
                        val updatedSummary =
                            buildQuotaSummary(connection, account.id)
                                ?: return@use rollbackAndReturn(connection) {
                                    VenueOwnerVenueCreationResult.DatabaseError
                                }
                        connection.commit()
                        VenueOwnerVenueCreationResult.Success(
                            venueId = venueId,
                            summary = updatedSummary,
                        )
                    } catch (e: SQLException) {
                        rollbackBestEffort(connection)
                        VenueOwnerVenueCreationResult.DatabaseError
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                VenueOwnerVenueCreationResult.DatabaseError
            }
        }
    }

    suspend fun approveLimitRequest(
        requestId: Long,
        decidedByUserId: Long,
    ): VenueOwnerLimitRequestDecisionResult =
        decideLimitRequest(
            requestId = requestId,
            status = "APPROVED",
            decidedByUserId = decidedByUserId,
            approvedExtraCount = null,
        )

    suspend fun approveLimitRequestPartial(
        requestId: Long,
        approvedExtraCount: Int,
        decidedByUserId: Long,
    ): VenueOwnerLimitRequestDecisionResult =
        decideLimitRequest(
            requestId = requestId,
            status = "APPROVED",
            decidedByUserId = decidedByUserId,
            approvedExtraCount = approvedExtraCount,
        )

    suspend fun rejectLimitRequest(
        requestId: Long,
        decidedByUserId: Long,
    ): VenueOwnerLimitRequestDecisionResult =
        decideLimitRequest(
            requestId = requestId,
            status = "REJECTED",
            decidedByUserId = decidedByUserId,
            approvedExtraCount = null,
        )

    internal fun prepareOwnerAssignmentInTransaction(
        connection: Connection,
        venueId: Long,
        ownerUserId: Long,
        defaultLimit: Int,
        updatedByUserId: Long?,
    ): OwnerAccountAssignmentPreparationResult {
        val account =
            getOrCreateForOwnerInTransaction(
                connection = connection,
                userId = ownerUserId,
                defaultLimit = defaultLimit,
                updatedByUserId = updatedByUserId,
            )
        val venue =
            loadVenueOwnerAccountForUpdate(connection, venueId)
                ?: return OwnerAccountAssignmentPreparationResult.NotFound
        if (venue.ownerAccountId == account.id) {
            val summary =
                buildQuotaSummary(connection, account.id)
                    ?: return OwnerAccountAssignmentPreparationResult.DatabaseError
            return OwnerAccountAssignmentPreparationResult.Success(account, summary)
        }
        if (venue.ownerAccountId != null && venue.ownerAccountId != account.id) {
            return OwnerAccountAssignmentPreparationResult.OwnerAccountMismatch
        }
        if (venue.countsTowardsQuota) {
            val summary =
                buildQuotaSummary(connection, account.id)
                    ?: return OwnerAccountAssignmentPreparationResult.DatabaseError
            if (summary.usedVenuesCount >= summary.account.allowedVenuesCount) {
                return OwnerAccountAssignmentPreparationResult.QuotaExceeded(summary)
            }
        }
        attachOwnerAccount(connection, venueId, account.id)
        val updatedSummary =
            buildQuotaSummary(connection, account.id)
                ?: return OwnerAccountAssignmentPreparationResult.DatabaseError
        return OwnerAccountAssignmentPreparationResult.Success(account, updatedSummary)
    }

    private suspend fun decideLimitRequest(
        requestId: Long,
        status: String,
        decidedByUserId: Long,
        approvedExtraCount: Int?,
    ): VenueOwnerLimitRequestDecisionResult {
        val ds = dataSource ?: return VenueOwnerLimitRequestDecisionResult.DatabaseError
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val request =
                            connection.prepareStatement(
                                """
                                SELECT id, owner_account_id, status, requested_extra_count
                                FROM owner_venue_limit_requests
                                WHERE id = ?
                                FOR UPDATE
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setLong(1, requestId)
                                statement.executeQuery().use { rs ->
                                    if (rs.next()) {
                                        LimitRequestLock(
                                            id = rs.getLong("id"),
                                            ownerAccountId = rs.getLong("owner_account_id"),
                                            status = rs.getString("status"),
                                            requestedExtraCount = rs.getInt("requested_extra_count"),
                                        )
                                    } else {
                                        null
                                    }
                                }
                            } ?: return@use rollbackAndReturn(connection) {
                                VenueOwnerLimitRequestDecisionResult.NotFound
                            }
                        if (!request.status.equals("PENDING", ignoreCase = true)) {
                            return@use rollbackAndReturn(connection) {
                                VenueOwnerLimitRequestDecisionResult.AlreadyDecided
                            }
                        }
                        val resolvedApprovedExtraCount =
                            if (status == "APPROVED") {
                                approvedExtraCount ?: request.requestedExtraCount
                            } else {
                                null
                            }
                        if (
                            status == "APPROVED" &&
                            (
                                resolvedApprovedExtraCount == null ||
                                    resolvedApprovedExtraCount !in 1..request.requestedExtraCount
                            )
                        ) {
                            return@use rollbackAndReturn(connection) {
                                VenueOwnerLimitRequestDecisionResult.InvalidApprovedCount(request.requestedExtraCount)
                            }
                        }
                        if (status == "APPROVED" && !lockOwnerAccount(connection, request.ownerAccountId)) {
                            return@use rollbackAndReturn(connection) {
                                VenueOwnerLimitRequestDecisionResult.DatabaseError
                            }
                        }
                        connection.prepareStatement(
                            """
                            UPDATE owner_venue_limit_requests
                            SET status = ?,
                                approved_extra_count = ?,
                                decided_at = CURRENT_TIMESTAMP,
                                decided_by_user_id = ?
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status)
                            if (resolvedApprovedExtraCount != null) {
                                statement.setInt(2, resolvedApprovedExtraCount)
                            } else {
                                statement.setNull(2, Types.INTEGER)
                            }
                            statement.setLong(3, decidedByUserId)
                            statement.setLong(4, requestId)
                            statement.executeUpdate()
                        }
                        if (status == "APPROVED" && resolvedApprovedExtraCount != null) {
                            connection.prepareStatement(
                                """
                                UPDATE venue_owner_accounts
                                SET allowed_venues_count = allowed_venues_count + ?,
                                    updated_at = CURRENT_TIMESTAMP,
                                    updated_by_user_id = ?
                                WHERE id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setInt(1, resolvedApprovedExtraCount)
                                statement.setLong(2, decidedByUserId)
                                statement.setLong(3, request.ownerAccountId)
                                statement.executeUpdate()
                            }
                        }
                        val updated =
                            loadLimitRequest(connection, requestId)
                                ?: return@use rollbackAndReturn(connection) {
                                    VenueOwnerLimitRequestDecisionResult.DatabaseError
                                }
                        connection.commit()
                        VenueOwnerLimitRequestDecisionResult.Success(updated)
                    } catch (e: SQLException) {
                        rollbackBestEffort(connection)
                        VenueOwnerLimitRequestDecisionResult.DatabaseError
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                VenueOwnerLimitRequestDecisionResult.DatabaseError
            }
        }
    }

    private fun getOrCreateForOwnerInTransaction(
        connection: Connection,
        userId: Long,
        defaultLimit: Int,
        updatedByUserId: Long?,
    ): VenueOwnerAccount {
        loadAccountByOwner(connection, userId, forUpdate = true)?.let { return it }
        val existingUsedCount = countExistingOwnerVenues(connection, userId)
        val allowedCount = maxOf(defaultLimit, existingUsedCount, 1)
        connection.prepareStatement(
            """
            INSERT INTO venue_owner_accounts (
                primary_owner_user_id,
                allowed_venues_count,
                updated_by_user_id
            )
            VALUES (?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setInt(2, allowedCount)
            if (updatedByUserId != null) {
                statement.setLong(3, updatedByUserId)
            } else {
                statement.setNull(3, Types.BIGINT)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) {
                    return loadAccountById(connection, rs.getLong(1), forUpdate = true)
                        ?: throw SQLException("Inserted owner account cannot be loaded")
                }
            }
        }
        throw SQLException("Failed to insert owner account")
    }

    private fun loadAccountByOwner(
        connection: Connection,
        userId: Long,
        forUpdate: Boolean,
    ): VenueOwnerAccount? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT id,
                   primary_owner_user_id,
                   allowed_venues_count,
                   notes,
                   commercial_note,
                   created_at,
                   updated_at,
                   updated_by_user_id
            FROM venue_owner_accounts
            WHERE primary_owner_user_id = ?
            $lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapAccount(rs) else null
            }
        }
    }

    private fun loadAccountById(
        connection: Connection,
        ownerAccountId: Long,
        forUpdate: Boolean,
    ): VenueOwnerAccount? {
        val lockClause = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT id,
                   primary_owner_user_id,
                   allowed_venues_count,
                   notes,
                   commercial_note,
                   created_at,
                   updated_at,
                   updated_by_user_id
            FROM venue_owner_accounts
            WHERE id = ?
            $lockClause
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerAccountId)
            statement.executeQuery().use { rs ->
                if (rs.next()) mapAccount(rs) else null
            }
        }
    }

    private fun buildQuotaSummary(
        connection: Connection,
        ownerAccountId: Long,
    ): VenueOwnerQuotaSummary? {
        val account = loadAccountById(connection, ownerAccountId, forUpdate = false) ?: return null
        val usedCount = countUsedVenues(connection, ownerAccountId)
        return VenueOwnerQuotaSummary(
            account = account,
            usedVenuesCount = usedCount,
            availableVenuesCount = (account.allowedVenuesCount - usedCount).coerceAtLeast(0),
        )
    }

    private fun countUsedVenues(
        connection: Connection,
        ownerAccountId: Long,
    ): Int {
        return connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM venues
            WHERE owner_account_id = ?
              AND COALESCE(status, 'DRAFT') IN ('DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerAccountId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun countExistingOwnerVenues(
        connection: Connection,
        ownerUserId: Long,
    ): Int {
        return connection.prepareStatement(
            """
            SELECT COUNT(DISTINCT v.id)
            FROM venues v
            JOIN venue_members vm ON vm.venue_id = v.id
            WHERE vm.user_id = ?
              AND UPPER(vm.role) = 'OWNER'
              AND COALESCE(v.status, 'DRAFT') IN ('DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerUserId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private fun loadVenueOwnerAccountForUpdate(
        connection: Connection,
        venueId: Long,
    ): VenueOwnerAccountLink? {
        return connection.prepareStatement(
            """
            SELECT owner_account_id, status
            FROM venues
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    val ownerAccountId = rs.getLong("owner_account_id").takeIf { !rs.wasNull() }
                    val status = rs.getString("status")
                    VenueOwnerAccountLink(
                        ownerAccountId = ownerAccountId,
                        countsTowardsQuota =
                            status in setOf("DRAFT", "PUBLISHED", "HIDDEN", "PAUSED", "SUSPENDED"),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun attachOwnerAccount(
        connection: Connection,
        venueId: Long,
        ownerAccountId: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE venues
            SET owner_account_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerAccountId)
            statement.setLong(2, venueId)
            statement.executeUpdate()
        }
    }

    private fun lockOwnerAccount(
        connection: Connection,
        ownerAccountId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT id
            FROM venue_owner_accounts
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerAccountId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun loadLimitRequest(
        connection: Connection,
        requestId: Long,
    ): VenueOwnerLimitRequest? {
        return connection.prepareStatement(
            """
            SELECT id,
                   owner_account_id,
                   requested_extra_count,
                   approved_extra_count,
                   comment,
                   status,
                   created_at,
                   decided_at,
                   decided_by_user_id
            FROM owner_venue_limit_requests
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, requestId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    VenueOwnerLimitRequest(
                        id = rs.getLong("id"),
                        ownerAccountId = rs.getLong("owner_account_id"),
                        requestedExtraCount = rs.getInt("requested_extra_count"),
                        approvedExtraCount = rs.getInt("approved_extra_count").takeIf { !rs.wasNull() },
                        comment = rs.getString("comment"),
                        status = rs.getString("status"),
                        createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
                        decidedAt = rs.getTimestamp("decided_at")?.toInstant(),
                        decidedByUserId = rs.getLong("decided_by_user_id").takeIf { !rs.wasNull() },
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun buildLimitRequestSummary(
        connection: Connection,
        requestId: Long,
    ): VenueOwnerLimitRequestSummary? {
        val request = loadLimitRequest(connection, requestId) ?: return null
        val quota = buildQuotaSummary(connection, request.ownerAccountId) ?: return null
        val owner =
            connection.prepareStatement(
                """
                SELECT u.username, u.first_name, u.last_name
                FROM users u
                WHERE u.telegram_user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, quota.account.primaryOwnerUserId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        VenueOwnerLimitRequestOwner(
                            userId = quota.account.primaryOwnerUserId,
                            username = rs.getString("username"),
                            firstName = rs.getString("first_name"),
                            lastName = rs.getString("last_name"),
                        )
                    } else {
                        VenueOwnerLimitRequestOwner(
                            userId = quota.account.primaryOwnerUserId,
                            username = null,
                            firstName = null,
                            lastName = null,
                        )
                    }
                }
            }
        return VenueOwnerLimitRequestSummary(
            request = request,
            quota = quota,
            owner = owner,
        )
    }

    private fun insertOwnedDraftVenue(
        connection: Connection,
        name: String,
        city: String,
        address: String,
        ownerAccountId: Long,
    ): Long {
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status, owner_account_id)
            VALUES (?, ?, ?, 'DRAFT', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, city)
            statement.setString(3, address)
            statement.setLong(4, ownerAccountId)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                if (rs.next()) return rs.getLong(1)
            }
        }
        throw SQLException("Failed to insert owned draft venue")
    }

    private fun ensureOwnerMembership(
        connection: Connection,
        venueId: Long,
        ownerUserId: Long,
        invitedByUserId: Long?,
    ) {
        val existingRole =
            connection.prepareStatement(
                """
                SELECT role
                FROM venue_members
                WHERE venue_id = ? AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, ownerUserId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("role") else null
                }
            }
        if (existingRole != null) {
            if (!existingRole.equals("OWNER", ignoreCase = true)) {
                connection.prepareStatement(
                    """
                    UPDATE venue_members
                    SET role = ?
                    WHERE venue_id = ? AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, "OWNER")
                    statement.setLong(2, venueId)
                    statement.setLong(3, ownerUserId)
                    statement.executeUpdate()
                }
            }
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO venue_members (venue_id, user_id, role, created_at, invited_by_user_id)
            VALUES (?, ?, 'OWNER', CURRENT_TIMESTAMP, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, ownerUserId)
            if (invitedByUserId != null) {
                statement.setLong(3, invitedByUserId)
            } else {
                statement.setNull(3, Types.BIGINT)
            }
            statement.executeUpdate()
        }
    }

    private fun mapAccount(rs: ResultSet): VenueOwnerAccount =
        VenueOwnerAccount(
            id = rs.getLong("id"),
            primaryOwnerUserId = rs.getLong("primary_owner_user_id"),
            allowedVenuesCount = rs.getInt("allowed_venues_count"),
            notes = rs.getString("notes"),
            commercialNote = rs.getString("commercial_note"),
            createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
            updatedAt = rs.getTimestamp("updated_at")?.toInstant() ?: Instant.EPOCH,
            updatedByUserId = rs.getLong("updated_by_user_id").takeIf { !rs.wasNull() },
        )

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }

    private fun <T> rollbackAndReturn(
        connection: Connection,
        block: () -> T,
    ): T {
        runCatching { connection.rollback() }
        return block()
    }

    private data class VenueOwnerAccountLink(
        val ownerAccountId: Long?,
        val countsTowardsQuota: Boolean,
    )

    private data class LimitRequestLock(
        val id: Long,
        val ownerAccountId: Long,
        val status: String,
        val requestedExtraCount: Int,
    )
}

data class VenueOwnerAccount(
    val id: Long,
    val primaryOwnerUserId: Long,
    val allowedVenuesCount: Int,
    val notes: String?,
    val commercialNote: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedByUserId: Long?,
)

data class VenueOwnerQuotaSummary(
    val account: VenueOwnerAccount,
    val usedVenuesCount: Int,
    val availableVenuesCount: Int,
)

data class VenueOwnerAccountVenue(
    val id: Long,
    val name: String,
    val city: String?,
    val status: String,
    val createdAt: Instant,
)

data class VenueOwnerLimitRequest(
    val id: Long,
    val ownerAccountId: Long,
    val requestedExtraCount: Int,
    val approvedExtraCount: Int? = null,
    val comment: String?,
    val status: String,
    val createdAt: Instant,
    val decidedAt: Instant?,
    val decidedByUserId: Long?,
)

data class VenueOwnerLimitRequestOwner(
    val userId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

data class VenueOwnerLimitRequestSummary(
    val request: VenueOwnerLimitRequest,
    val quota: VenueOwnerQuotaSummary,
    val owner: VenueOwnerLimitRequestOwner,
)

sealed interface VenueOwnerVenueCreationResult {
    data class Success(
        val venueId: Long,
        val summary: VenueOwnerQuotaSummary,
    ) : VenueOwnerVenueCreationResult

    data class LimitExceeded(val summary: VenueOwnerQuotaSummary) : VenueOwnerVenueCreationResult

    data object DatabaseError : VenueOwnerVenueCreationResult
}

sealed interface VenueOwnerQuotaCheckResult {
    data class Allowed(val summary: VenueOwnerQuotaSummary) : VenueOwnerQuotaCheckResult

    data class LimitExceeded(val summary: VenueOwnerQuotaSummary) : VenueOwnerQuotaCheckResult

    data object NotFound : VenueOwnerQuotaCheckResult

    data object DatabaseError : VenueOwnerQuotaCheckResult
}

sealed interface OwnerAccountAssignmentPreparationResult {
    data class Success(
        val account: VenueOwnerAccount,
        val summary: VenueOwnerQuotaSummary,
    ) : OwnerAccountAssignmentPreparationResult

    data class QuotaExceeded(val summary: VenueOwnerQuotaSummary) : OwnerAccountAssignmentPreparationResult

    data object OwnerAccountMismatch : OwnerAccountAssignmentPreparationResult

    data object NotFound : OwnerAccountAssignmentPreparationResult

    data object DatabaseError : OwnerAccountAssignmentPreparationResult
}

sealed interface VenueOwnerLimitRequestDecisionResult {
    data class Success(val request: VenueOwnerLimitRequest) : VenueOwnerLimitRequestDecisionResult

    data object NotFound : VenueOwnerLimitRequestDecisionResult

    data object AlreadyDecided : VenueOwnerLimitRequestDecisionResult

    data class InvalidApprovedCount(val requestedExtraCount: Int) : VenueOwnerLimitRequestDecisionResult

    data object DatabaseError : VenueOwnerLimitRequestDecisionResult
}
