package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.sql.DataSource

internal data class VenueConnectionApplicationCanonicalTuple(
    val venueName: String,
    val city: String,
    val contact: String,
    val comment: String?,
)

internal fun canonicalVenueConnectionApplicationTuple(
    venueName: String,
    city: String,
    contact: String,
    comment: String?,
): VenueConnectionApplicationCanonicalTuple =
    VenueConnectionApplicationCanonicalTuple(
        venueName = canonicalVenueConnectionApplicationText(venueName),
        city = canonicalVenueConnectionApplicationText(city),
        contact = canonicalVenueConnectionApplicationText(contact),
        comment =
            comment
                ?.let(::canonicalVenueConnectionApplicationText)
                ?.takeIf(String::isNotBlank),
    )

private fun canonicalVenueConnectionApplicationText(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .replace(VENUE_CONNECTION_APPLICATION_WHITESPACE, " ")
        .trim()
        .lowercase(Locale.ROOT)

private val VENUE_CONNECTION_APPLICATION_WHITESPACE = Regex("(?U)\\s+")

data class VenueConnectionRequestRecord(
    val id: Long,
    val telegramUserId: Long,
    val venueName: String,
    val city: String,
    val contact: String,
    val comment: String?,
    val status: String,
    val createdAt: Instant,
    val linkedVenueId: Long? = null,
    val trialConfigured: Boolean = false,
    val trialEndsOn: LocalDate? = null,
    val currentPriceRub: Long? = null,
    val futurePriceRub: Long? = null,
    val futurePriceEffectiveOn: LocalDate? = null,
    val commercialNote: String? = null,
)

data class VenueConnectionRequestApplicantRecord(
    val request: VenueConnectionRequestRecord,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

enum class VenueOnboardingSource {
    TELEGRAM_BOT,
    VENUE_MINI_APP,
    PLATFORM_MINI_APP,
}

sealed interface VenueConnectionRequestSubmitResult {
    data class Success(
        val request: VenueConnectionRequestRecord,
        val created: Boolean,
    ) : VenueConnectionRequestSubmitResult

    data object ApplicantNotOperationalOwner : VenueConnectionRequestSubmitResult
}

sealed interface VenueConnectionRequestMutationResult {
    data class Success(val request: VenueConnectionRequestRecord) : VenueConnectionRequestMutationResult

    data class InvalidState(val request: VenueConnectionRequestRecord) : VenueConnectionRequestMutationResult

    data object ApplicantNotOperationalOwner : VenueConnectionRequestMutationResult

    data object NotFound : VenueConnectionRequestMutationResult
}

sealed interface VenueConnectionRequestCreateLinkResult {
    data class Success(
        val request: VenueConnectionRequestRecord,
        val venueId: Long,
        val created: Boolean,
    ) : VenueConnectionRequestCreateLinkResult

    data class QuotaExceeded(
        val usedVenuesCount: Int,
        val allowedVenuesCount: Int,
    ) : VenueConnectionRequestCreateLinkResult

    data class InvalidState(val request: VenueConnectionRequestRecord) : VenueConnectionRequestCreateLinkResult

    data class CommercialTermsMissing(val request: VenueConnectionRequestRecord) :
        VenueConnectionRequestCreateLinkResult

    data object ApplicantNotOperationalOwner : VenueConnectionRequestCreateLinkResult

    data object NotFound : VenueConnectionRequestCreateLinkResult
}

class VenueConnectionRequestRepository(
    private val dataSource: DataSource?,
    private val auditLogWriter: TransactionalAuditLogWriter? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val AUDIT_SUBMITTED = "VENUE_CONNECTION_REQUEST_SUBMITTED"
        const val AUDIT_UPDATED = "VENUE_CONNECTION_REQUEST_UPDATED"
        const val AUDIT_CANCELLED = "VENUE_CONNECTION_REQUEST_CANCELLED"
        const val AUDIT_APPROVED = "VENUE_CONNECTION_REQUEST_APPROVED"
        const val AUDIT_REJECTED = "VENUE_CONNECTION_REQUEST_REJECTED"
        const val AUDIT_TERMS_UPDATED = "VENUE_CONNECTION_REQUEST_TERMS_UPDATED"
        const val AUDIT_LINKED = "VENUE_CONNECTION_REQUEST_LINKED"
        private const val REQUEST_COLUMNS =
            "id, telegram_user_id, venue_name, city, contact, comment, status, created_at, " +
                "linked_venue_id, trial_configured, trial_ends_on, current_price_rub, future_price_rub, " +
                "future_price_effective_on, commercial_note"
        private const val REQUEST_COLUMNS_WITH_ALIAS =
            "r.id AS id, r.telegram_user_id AS telegram_user_id, r.venue_name AS venue_name, " +
                "r.city AS city, r.contact AS contact, r.comment AS comment, r.status AS status, " +
                "r.created_at AS created_at, r.linked_venue_id AS linked_venue_id, " +
                "r.trial_configured AS trial_configured, r.trial_ends_on AS trial_ends_on, " +
                "r.current_price_rub AS current_price_rub, r.future_price_rub AS future_price_rub, " +
                "r.future_price_effective_on AS future_price_effective_on, " +
                "r.commercial_note AS commercial_note"
    }

    suspend fun createOrReturnActive(
        telegramUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestSubmitResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            transaction(ds) { connection ->
                lockApplicant(connection, telegramUserId)
                val submittedTuple =
                    canonicalVenueConnectionApplicationTuple(
                        venueName = venueName,
                        city = city,
                        contact = contact,
                        comment = comment,
                    )
                val equivalent =
                    findRetryableActiveByUser(connection, telegramUserId).firstOrNull { request ->
                        canonicalVenueConnectionApplicationTuple(
                            venueName = request.venueName,
                            city = request.city,
                            contact = request.contact,
                            comment = request.comment,
                        ) == submittedTuple
                    }
                if (equivalent != null) {
                    return@transaction VenueConnectionRequestSubmitResult.Success(equivalent, created = false)
                }
                val requestId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_connection_requests (
                            telegram_user_id, venue_name, city, contact, comment, status
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, telegramUserId)
                        statement.setString(2, venueName)
                        statement.setString(3, city)
                        statement.setString(4, contact)
                        statement.setString(5, comment)
                        statement.setString(6, STATUS_PENDING)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            if (!keys.next()) throw SQLException("Failed to create venue connection request")
                            keys.getLong(1)
                        }
                    }
                appendRequestAudit(
                    connection = connection,
                    actorUserId = telegramUserId,
                    action = AUDIT_SUBMITTED,
                    requestId = requestId,
                    source = source,
                    status = STATUS_PENDING,
                )
                val created = findById(connection, requestId) ?: throw SQLException("Created request is missing")
                VenueConnectionRequestSubmitResult.Success(created, created = true)
            }
        }
    }

    suspend fun listByApplicant(telegramUserId: Long): List<VenueConnectionRequestRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT $REQUEST_COLUMNS
                        FROM venue_connection_requests
                        WHERE telegram_user_id = ?
                        ORDER BY created_at DESC, id DESC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, telegramUserId)
                        statement.executeQuery().use(::mapRecords)
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findByIdForApplicant(
        requestId: Long,
        telegramUserId: Long,
    ): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> findByIdForApplicant(connection, requestId, telegramUserId) }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listForPlatform(
        statuses: Set<String>?,
        query: String?,
        limit: Int,
        offset: Int,
    ): List<VenueConnectionRequestApplicantRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        StringBuilder(
                            """
                            SELECT ${REQUEST_COLUMNS_WITH_ALIAS},
                                   u.username,
                                   u.first_name,
                                   u.last_name
                            FROM venue_connection_requests r
                            LEFT JOIN users u ON u.telegram_user_id = r.telegram_user_id
                            """.trimIndent(),
                        )
                    val conditions = mutableListOf<String>()
                    val params = mutableListOf<Any>()
                    val normalizedStatuses = statuses?.map { it.uppercase() }?.toSet().orEmpty()
                    if (normalizedStatuses.isNotEmpty()) {
                        conditions += "r.status IN (${normalizedStatuses.joinToString(",") { "?" }})"
                        params.addAll(normalizedStatuses.sorted())
                    }
                    query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { search ->
                        conditions +=
                            "(LOWER(r.venue_name) LIKE ? OR LOWER(r.city) LIKE ? OR " +
                            "LOWER(COALESCE(u.username, '')) LIKE ? OR CAST(r.id AS VARCHAR) = ?)"
                        val like = "%$search%"
                        params.addAll(listOf(like, like, like, search))
                    }
                    if (conditions.isNotEmpty()) sql.append(" WHERE ").append(conditions.joinToString(" AND "))
                    sql.append(" ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?")
                    params += limit.coerceIn(1, 200)
                    params += offset.coerceAtLeast(0)
                    connection.prepareStatement(sql.toString()).use { statement ->
                        params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        VenueConnectionRequestApplicantRecord(
                                            request = mapRecord(rs),
                                            username = rs.getString("username"),
                                            firstName = rs.getString("first_name"),
                                            lastName = rs.getString("last_name"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findForPlatform(requestId: Long): VenueConnectionRequestApplicantRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${REQUEST_COLUMNS_WITH_ALIAS},
                               u.username,
                               u.first_name,
                               u.last_name
                        FROM venue_connection_requests r
                        LEFT JOIN users u ON u.telegram_user_id = r.telegram_user_id
                        WHERE r.id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, requestId)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) return@withContext null
                            VenueConnectionRequestApplicantRecord(
                                request = mapRecord(rs),
                                username = rs.getString("username"),
                                firstName = rs.getString("first_name"),
                                lastName = rs.getString("last_name"),
                            )
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updatePending(
        requestId: Long,
        telegramUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        mutateApplicantRequest(requestId, telegramUserId) { connection, current ->
            if (current.status != STATUS_PENDING) {
                return@mutateApplicantRequest VenueConnectionRequestMutationResult.InvalidState(current)
            }
            if (
                current.venueName == venueName &&
                current.city == city &&
                current.contact == contact &&
                current.comment == comment
            ) {
                return@mutateApplicantRequest VenueConnectionRequestMutationResult.Success(current)
            }
            connection.prepareStatement(
                """
                UPDATE venue_connection_requests
                SET venue_name = ?, city = ?, contact = ?, comment = ?
                WHERE id = ? AND telegram_user_id = ? AND status = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, venueName)
                statement.setString(2, city)
                statement.setString(3, contact)
                statement.setString(4, comment)
                statement.setLong(5, requestId)
                statement.setLong(6, telegramUserId)
                statement.setString(7, STATUS_PENDING)
                if (statement.executeUpdate() != 1) throw SQLException("Pending request update lost lock")
            }
            appendRequestAudit(connection, telegramUserId, AUDIT_UPDATED, requestId, source, STATUS_PENDING)
            VenueConnectionRequestMutationResult.Success(
                findById(connection, requestId) ?: throw SQLException("Updated request is missing"),
            )
        }

    suspend fun cancel(
        requestId: Long,
        telegramUserId: Long,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        mutateApplicantRequest(requestId, telegramUserId) { connection, current ->
            if (current.status != STATUS_PENDING) {
                return@mutateApplicantRequest VenueConnectionRequestMutationResult.InvalidState(current)
            }
            updateStatus(connection, requestId, STATUS_PENDING, STATUS_CANCELLED)
            appendRequestAudit(connection, telegramUserId, AUDIT_CANCELLED, requestId, source, STATUS_CANCELLED)
            VenueConnectionRequestMutationResult.Success(
                findById(connection, requestId) ?: throw SQLException("Cancelled request is missing"),
            )
        }

    suspend fun decide(
        requestId: Long,
        actorUserId: Long,
        targetStatus: String,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult {
        require(targetStatus == STATUS_APPROVED || targetStatus == STATUS_REJECTED)
        return mutatePlatformRequest(requestId) { connection, current ->
            if (current.status != STATUS_PENDING) {
                return@mutatePlatformRequest VenueConnectionRequestMutationResult.InvalidState(current)
            }
            updateStatus(connection, requestId, STATUS_PENDING, targetStatus)
            appendRequestAudit(
                connection,
                actorUserId,
                if (targetStatus == STATUS_APPROVED) AUDIT_APPROVED else AUDIT_REJECTED,
                requestId,
                source,
                targetStatus,
            )
            VenueConnectionRequestMutationResult.Success(
                findById(connection, requestId) ?: throw SQLException("Decided request is missing"),
            )
        }
    }

    suspend fun closeApprovedUnlinked(
        requestId: Long,
        actorUserId: Long,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        mutatePlatformRequest(requestId) { connection, current ->
            if (current.status != STATUS_APPROVED || current.linkedVenueId != null) {
                return@mutatePlatformRequest VenueConnectionRequestMutationResult.InvalidState(current)
            }
            updateStatus(connection, requestId, STATUS_APPROVED, STATUS_CANCELLED)
            appendRequestAudit(connection, actorUserId, AUDIT_CANCELLED, requestId, source, STATUS_CANCELLED)
            VenueConnectionRequestMutationResult.Success(
                findById(connection, requestId) ?: throw SQLException("Closed request is missing"),
            )
        }

    suspend fun updateTerms(
        requestId: Long,
        actorUserId: Long,
        trialConfigured: Boolean,
        trialEndsOn: LocalDate?,
        currentPriceRub: Long,
        futurePriceRub: Long?,
        futurePriceEffectiveOn: LocalDate?,
        commercialNote: String?,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        mutatePlatformRequest(requestId) { connection, current ->
            if (current.status != STATUS_APPROVED || current.linkedVenueId != null) {
                return@mutatePlatformRequest VenueConnectionRequestMutationResult.InvalidState(current)
            }
            if (
                current.trialConfigured == trialConfigured &&
                current.trialEndsOn == trialEndsOn &&
                current.currentPriceRub == currentPriceRub &&
                current.futurePriceRub == futurePriceRub &&
                current.futurePriceEffectiveOn == futurePriceEffectiveOn &&
                current.commercialNote == commercialNote
            ) {
                return@mutatePlatformRequest VenueConnectionRequestMutationResult.Success(current)
            }
            connection.prepareStatement(
                """
                UPDATE venue_connection_requests
                SET trial_configured = ?, trial_ends_on = ?, current_price_rub = ?, future_price_rub = ?,
                    future_price_effective_on = ?, commercial_note = ?
                WHERE id = ? AND status = ? AND linked_venue_id IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setBoolean(1, trialConfigured)
                setDate(statement, 2, trialEndsOn)
                statement.setLong(3, currentPriceRub)
                setLong(statement, 4, futurePriceRub)
                setDate(statement, 5, futurePriceEffectiveOn)
                statement.setString(6, commercialNote)
                statement.setLong(7, requestId)
                statement.setString(8, STATUS_APPROVED)
                if (statement.executeUpdate() != 1) throw SQLException("Commercial terms update lost lock")
            }
            appendRequestAudit(connection, actorUserId, AUDIT_TERMS_UPDATED, requestId, source, STATUS_APPROVED)
            VenueConnectionRequestMutationResult.Success(
                findById(connection, requestId) ?: throw SQLException("Updated request is missing"),
            )
        }

    suspend fun createDraftAndLink(
        requestId: Long,
        actorUserId: Long,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestCreateLinkResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            transaction(ds) { connection ->
                val applicantId =
                    findApplicantId(connection, requestId)
                        ?: return@transaction VenueConnectionRequestCreateLinkResult.NotFound
                lockApplicant(connection, applicantId)
                val request =
                    findByIdForUpdate(connection, requestId)
                        ?: return@transaction VenueConnectionRequestCreateLinkResult.NotFound
                request.linkedVenueId?.let { linkedVenueId ->
                    return@transaction VenueConnectionRequestCreateLinkResult.Success(
                        request = request,
                        venueId = linkedVenueId,
                        created = false,
                    )
                }
                if (request.status != STATUS_APPROVED) {
                    return@transaction VenueConnectionRequestCreateLinkResult.InvalidState(request)
                }
                if (!commercialTermsReady(request)) {
                    return@transaction VenueConnectionRequestCreateLinkResult.CommercialTermsMissing(request)
                }

                val ownerAccount = getOrCreateOwnerAccount(connection, applicantId, actorUserId)
                val usedVenuesCount = countUsedVenues(connection, ownerAccount.id)
                if (usedVenuesCount >= ownerAccount.allowedVenuesCount) {
                    return@transaction VenueConnectionRequestCreateLinkResult.QuotaExceeded(
                        usedVenuesCount = usedVenuesCount,
                        allowedVenuesCount = ownerAccount.allowedVenuesCount,
                    )
                }

                val venueId = insertDraftVenue(connection, request, ownerAccount.id)
                insertVenueSettings(connection, venueId, request.city)
                insertOwnerMembership(connection, venueId, applicantId, actorUserId)
                applyCommercialTerms(connection, venueId, request, actorUserId)
                connection.prepareStatement(
                    """
                    UPDATE venue_connection_requests
                    SET linked_venue_id = ?
                    WHERE id = ? AND status = ? AND linked_venue_id IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, requestId)
                    statement.setString(3, STATUS_APPROVED)
                    if (statement.executeUpdate() != 1) throw SQLException("Request link lost lock")
                }

                appendVenueCreateAudit(connection, actorUserId, requestId, venueId, source)
                appendOwnerAssignedAudit(connection, actorUserId, requestId, venueId, applicantId, source)
                appendRequestAudit(
                    connection,
                    actorUserId,
                    AUDIT_LINKED,
                    requestId,
                    source,
                    STATUS_APPROVED,
                    venueId,
                )
                val linkedRequest =
                    findById(connection, requestId)
                        ?: throw SQLException("Linked request is missing")
                VenueConnectionRequestCreateLinkResult.Success(
                    request = linkedRequest,
                    venueId = venueId,
                    created = true,
                )
            }
        }
    }

    suspend fun findActiveUnlinkedByUser(telegramUserId: Long): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE telegram_user_id = ?
                          AND (
                              status = ?
                              OR (status = ? AND linked_venue_id IS NULL)
                          )
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, telegramUserId)
                        statement.setString(2, STATUS_PENDING)
                        statement.setString(3, STATUS_APPROVED)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                mapRecord(rs)
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findById(requestId: Long): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE id = ?
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, requestId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapRecord(rs) else null
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findApprovedByLinkedVenue(venueId: Long): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE linked_venue_id = ? AND status = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, STATUS_APPROVED)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapRecord(rs) else null
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listActionableRequests(limit: Int): List<VenueConnectionRequestRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE status = ?
                           OR (status = ? AND linked_venue_id IS NULL)
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, STATUS_PENDING)
                        statement.setString(2, STATUS_APPROVED)
                        statement.setInt(3, limit.coerceAtLeast(1))
                        statement.executeQuery().use { rs ->
                            val items = mutableListOf<VenueConnectionRequestRecord>()
                            while (rs.next()) {
                                items.add(mapRecord(rs))
                            }
                            items
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private suspend fun mutateApplicantRequest(
        requestId: Long,
        telegramUserId: Long,
        block: (Connection, VenueConnectionRequestRecord) -> VenueConnectionRequestMutationResult,
    ): VenueConnectionRequestMutationResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            transaction(ds) { connection ->
                lockApplicant(connection, telegramUserId)
                val current =
                    findByIdForApplicantForUpdate(connection, requestId, telegramUserId)
                        ?: return@transaction VenueConnectionRequestMutationResult.NotFound
                block(connection, current)
            }
        }
    }

    private suspend fun mutatePlatformRequest(
        requestId: Long,
        block: (Connection, VenueConnectionRequestRecord) -> VenueConnectionRequestMutationResult,
    ): VenueConnectionRequestMutationResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            transaction(ds) { connection ->
                val applicantId =
                    findApplicantId(connection, requestId)
                        ?: return@transaction VenueConnectionRequestMutationResult.NotFound
                lockApplicant(connection, applicantId)
                val current =
                    findByIdForUpdate(connection, requestId)
                        ?: return@transaction VenueConnectionRequestMutationResult.NotFound
                block(connection, current)
            }
        }
    }

    private fun lockApplicant(
        connection: Connection,
        telegramUserId: Long,
    ) {
        val exists =
            connection.prepareStatement(
                "SELECT telegram_user_id FROM users WHERE telegram_user_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setLong(1, telegramUserId)
                statement.executeQuery().use { it.next() }
            }
        if (!exists) throw SQLException("Venue onboarding applicant is missing")
    }

    private fun findApplicantId(
        connection: Connection,
        requestId: Long,
    ): Long? =
        connection.prepareStatement(
            "SELECT telegram_user_id FROM venue_connection_requests WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, requestId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun findRetryableActiveByUser(
        connection: Connection,
        telegramUserId: Long,
    ): List<VenueConnectionRequestRecord> =
        connection.prepareStatement(
            """
            SELECT $REQUEST_COLUMNS
            FROM venue_connection_requests
            WHERE telegram_user_id = ?
              AND (status = ? OR (status = ? AND linked_venue_id IS NULL))
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, telegramUserId)
            statement.setString(2, STATUS_PENDING)
            statement.setString(3, STATUS_APPROVED)
            statement.executeQuery().use(::mapRecords)
        }

    private fun findById(
        connection: Connection,
        requestId: Long,
    ): VenueConnectionRequestRecord? =
        connection.prepareStatement(
            "SELECT $REQUEST_COLUMNS FROM venue_connection_requests WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, requestId)
            statement.executeQuery().use { rs -> if (rs.next()) mapRecord(rs) else null }
        }

    private fun findByIdForUpdate(
        connection: Connection,
        requestId: Long,
    ): VenueConnectionRequestRecord? =
        connection.prepareStatement(
            "SELECT $REQUEST_COLUMNS FROM venue_connection_requests WHERE id = ? FOR UPDATE",
        ).use { statement ->
            statement.setLong(1, requestId)
            statement.executeQuery().use { rs -> if (rs.next()) mapRecord(rs) else null }
        }

    private fun findByIdForApplicant(
        connection: Connection,
        requestId: Long,
        telegramUserId: Long,
    ): VenueConnectionRequestRecord? =
        connection.prepareStatement(
            "SELECT $REQUEST_COLUMNS FROM venue_connection_requests WHERE id = ? AND telegram_user_id = ?",
        ).use { statement ->
            statement.setLong(1, requestId)
            statement.setLong(2, telegramUserId)
            statement.executeQuery().use { rs -> if (rs.next()) mapRecord(rs) else null }
        }

    private fun findByIdForApplicantForUpdate(
        connection: Connection,
        requestId: Long,
        telegramUserId: Long,
    ): VenueConnectionRequestRecord? =
        connection.prepareStatement(
            """
            SELECT $REQUEST_COLUMNS
            FROM venue_connection_requests
            WHERE id = ? AND telegram_user_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, requestId)
            statement.setLong(2, telegramUserId)
            statement.executeQuery().use { rs -> if (rs.next()) mapRecord(rs) else null }
        }

    private fun updateStatus(
        connection: Connection,
        requestId: Long,
        expectedStatus: String,
        targetStatus: String,
    ) {
        connection.prepareStatement(
            "UPDATE venue_connection_requests SET status = ? WHERE id = ? AND status = ?",
        ).use { statement ->
            statement.setString(1, targetStatus)
            statement.setLong(2, requestId)
            statement.setString(3, expectedStatus)
            if (statement.executeUpdate() != 1) throw SQLException("Request status update lost lock")
        }
    }

    private fun commercialTermsReady(request: VenueConnectionRequestRecord): Boolean {
        if (!request.trialConfigured) return false
        val current = request.currentPriceRub ?: return false
        if (rubToMinor(current, allowZero = true) == null) return false
        val futurePrice = request.futurePriceRub
        val futureDate = request.futurePriceEffectiveOn
        if ((futurePrice == null) != (futureDate == null)) return false
        return futurePrice == null || rubToMinor(futurePrice, allowZero = false) != null
    }

    private fun rubToMinor(
        rub: Long,
        allowZero: Boolean,
    ): Int? {
        if (rub < 0 || (!allowZero && rub == 0L)) return null
        val minor = runCatching { Math.multiplyExact(rub, 100L) }.getOrNull() ?: return null
        return minor.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private data class LockedOwnerAccount(
        val id: Long,
        val allowedVenuesCount: Int,
    )

    private fun getOrCreateOwnerAccount(
        connection: Connection,
        ownerUserId: Long,
        actorUserId: Long,
    ): LockedOwnerAccount {
        loadOwnerAccount(connection, ownerUserId)?.let { return it }
        val existingOwnedVenues =
            connection.prepareStatement(
                """
                SELECT COUNT(DISTINCT v.id)
                FROM venues v
                JOIN venue_members vm ON vm.venue_id = v.id
                WHERE vm.user_id = ? AND UPPER(vm.role) = 'OWNER'
                  AND COALESCE(v.status, 'DRAFT') IN ('DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ownerUserId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        val allowed = maxOf(1, existingOwnedVenues)
        connection.prepareStatement(
            """
            INSERT INTO venue_owner_accounts (
                primary_owner_user_id, allowed_venues_count, updated_by_user_id
            )
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerUserId)
            statement.setInt(2, allowed)
            statement.setLong(3, actorUserId)
            statement.executeUpdate()
        }
        return loadOwnerAccount(connection, ownerUserId)
            ?: throw SQLException("Created owner account is missing")
    }

    private fun loadOwnerAccount(
        connection: Connection,
        ownerUserId: Long,
    ): LockedOwnerAccount? =
        connection.prepareStatement(
            """
            SELECT id, allowed_venues_count
            FROM venue_owner_accounts
            WHERE primary_owner_user_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ownerUserId)
            statement.executeQuery().use { rs ->
                if (rs.next()) LockedOwnerAccount(rs.getLong("id"), rs.getInt("allowed_venues_count")) else null
            }
        }

    private fun countUsedVenues(
        connection: Connection,
        ownerAccountId: Long,
    ): Int =
        connection.prepareStatement(
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

    private fun insertDraftVenue(
        connection: Connection,
        request: VenueConnectionRequestRecord,
        ownerAccountId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status, owner_account_id)
            VALUES (?, ?, NULL, 'DRAFT', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, request.venueName)
            statement.setString(2, request.city)
            statement.setLong(3, ownerAccountId)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (!keys.next()) throw SQLException("Failed to create onboarding venue")
                keys.getLong(1)
            }
        }

    private fun insertOwnerMembership(
        connection: Connection,
        venueId: Long,
        ownerUserId: Long,
        actorUserId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_members (venue_id, user_id, role, created_at, invited_by_user_id)
            VALUES (?, ?, 'OWNER', CURRENT_TIMESTAMP, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, ownerUserId)
            statement.setLong(3, actorUserId)
            statement.executeUpdate()
        }
    }

    private fun insertVenueSettings(
        connection: Connection,
        venueId: Long,
        city: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO venue_settings (
                venue_id,
                notify_orders_enabled,
                notify_staff_calls_enabled,
                notify_cancellations_enabled,
                timezone
            )
            VALUES (?, TRUE, TRUE, TRUE, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(
                2,
                VenueSettingsRepository.resolveInferredVenueTimezone(city = city, address = null),
            )
            statement.executeUpdate()
        }
    }

    private fun applyCommercialTerms(
        connection: Connection,
        venueId: Long,
        request: VenueConnectionRequestRecord,
        actorUserId: Long,
    ) {
        val basePriceMinor =
            rubToMinor(request.currentPriceRub!!, allowZero = true)
                ?: throw SQLException("Invalid current commercial price")
        val paidStart = request.trialEndsOn ?: LocalDate.now()
        connection.prepareStatement(
            """
            INSERT INTO venue_subscription_settings (
                venue_id, trial_end_date, paid_start_date, base_price_minor, price_override_minor,
                currency, updated_at, updated_by_user_id
            )
            VALUES (?, ?, ?, ?, NULL, 'RUB', CURRENT_TIMESTAMP, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            setDate(statement, 2, request.trialEndsOn)
            statement.setDate(3, java.sql.Date.valueOf(paidStart))
            statement.setInt(4, basePriceMinor)
            statement.setLong(5, actorUserId)
            statement.executeUpdate()
        }
        if (request.futurePriceRub != null && request.futurePriceEffectiveOn != null) {
            val futurePriceMinor =
                rubToMinor(request.futurePriceRub, allowZero = false)
                    ?: throw SQLException("Invalid future commercial price")
            connection.prepareStatement(
                """
                INSERT INTO venue_price_schedule (
                    venue_id, effective_from, price_minor, currency, updated_at, updated_by_user_id
                )
                VALUES (?, ?, ?, 'RUB', CURRENT_TIMESTAMP, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setDate(2, java.sql.Date.valueOf(request.futurePriceEffectiveOn))
                statement.setInt(3, futurePriceMinor)
                statement.setLong(4, actorUserId)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement(
            """
            INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, if (request.trialEndsOn == null) "ACTIVE" else "TRIAL")
            if (request.trialEndsOn != null) {
                statement.setTimestamp(3, java.sql.Timestamp.valueOf(request.trialEndsOn.atStartOfDay()))
            } else {
                statement.setNull(3, Types.TIMESTAMP)
            }
            statement.setTimestamp(4, java.sql.Timestamp.valueOf(paidStart.atStartOfDay()))
            statement.executeUpdate()
        }
    }

    private fun appendRequestAudit(
        connection: Connection,
        actorUserId: Long,
        action: String,
        requestId: Long,
        source: VenueOnboardingSource,
        status: String,
        venueId: Long? = null,
    ) {
        val writer = auditLogWriter ?: throw SQLException("Venue onboarding audit writer is unavailable")
        writer.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = action,
            entityType = "venue_connection_request",
            entityId = requestId,
            payload =
                buildJsonObject {
                    put("requestId", requestId)
                    put("status", status)
                    put("source", source.name)
                    venueId?.let { put("venueId", it) }
                },
        )
    }

    private fun appendVenueCreateAudit(
        connection: Connection,
        actorUserId: Long,
        requestId: Long,
        venueId: Long,
        source: VenueOnboardingSource,
    ) {
        val writer = auditLogWriter ?: throw SQLException("Venue onboarding audit writer is unavailable")
        writer.appendJson(
            connection,
            actorUserId,
            "VENUE_CREATE",
            "venue",
            venueId,
            buildJsonObject {
                put("venueId", venueId)
                put("requestId", requestId)
                put("status", "DRAFT")
                put("source", source.name)
            },
        )
    }

    private fun appendOwnerAssignedAudit(
        connection: Connection,
        actorUserId: Long,
        requestId: Long,
        venueId: Long,
        ownerUserId: Long,
        source: VenueOnboardingSource,
    ) {
        val writer = auditLogWriter ?: throw SQLException("Venue onboarding audit writer is unavailable")
        writer.appendJson(
            connection,
            actorUserId,
            "VENUE_OWNER_ASSIGN",
            "venue",
            venueId,
            buildJsonObject {
                put("venueId", venueId)
                put("requestId", requestId)
                put("userId", ownerUserId)
                put("role", "OWNER")
                put("source", source.name)
            },
        )
    }

    private fun setDate(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: LocalDate?,
    ) {
        if (value == null) {
            statement.setNull(index, Types.DATE)
        } else {
            statement.setDate(index, java.sql.Date.valueOf(value))
        }
    }

    private fun setLong(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) statement.setNull(index, Types.BIGINT) else statement.setLong(index, value)
    }

    private fun mapRecords(rs: ResultSet): List<VenueConnectionRequestRecord> =
        buildList {
            while (rs.next()) add(mapRecord(rs))
        }

    private fun <T> transaction(
        dataSource: DataSource,
        block: (Connection) -> T,
    ): T {
        try {
            dataSource.connection.use { connection ->
                val initialAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    return result
                } catch (error: Throwable) {
                    runCatching { connection.rollback() }
                    throw error
                } finally {
                    runCatching { connection.autoCommit = initialAutoCommit }
                }
            }
        } catch (error: DatabaseUnavailableException) {
            throw error
        } catch (error: SQLException) {
            throw DatabaseUnavailableException().also { it.addSuppressed(error) }
        }
    }

    private fun mapRecord(rs: ResultSet): VenueConnectionRequestRecord =
        VenueConnectionRequestRecord(
            id = rs.getLong("id"),
            telegramUserId = rs.getLong("telegram_user_id"),
            venueName = rs.getString("venue_name"),
            city = rs.getString("city"),
            contact = rs.getString("contact"),
            comment = rs.getString("comment"),
            status = rs.getString("status"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            linkedVenueId = rs.getLong("linked_venue_id").takeIf { !rs.wasNull() },
            trialConfigured = rs.getBoolean("trial_configured").takeIf { !rs.wasNull() } ?: false,
            trialEndsOn = rs.getDate("trial_ends_on")?.toLocalDate(),
            currentPriceRub = rs.getLong("current_price_rub").takeIf { !rs.wasNull() },
            futurePriceRub = rs.getLong("future_price_rub").takeIf { !rs.wasNull() },
            futurePriceEffectiveOn = rs.getDate("future_price_effective_on")?.toLocalDate(),
            commercialNote = rs.getString("commercial_note"),
        )
}
