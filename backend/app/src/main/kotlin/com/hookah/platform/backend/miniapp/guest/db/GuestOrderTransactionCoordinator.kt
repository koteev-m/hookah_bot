package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.SubscriptionBlockedException
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

enum class GuestOrderContextCheckpoint {
    AFTER_SESSION_TOUCH,
    AFTER_PERSONAL_TAB_ENSURE,
}

class GuestOrderTransactionCoordinator(
    private val dataSource: DataSource?,
    private val tableTokenRepository: TableTokenRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val tableSessionRepository: TableSessionRepository,
) {
    suspend fun <T> executeAuthorized(
        actorUserId: Long,
        tableToken: String,
        expectedVenueId: Long,
        expectedTableId: Long,
        expectedTableSessionId: Long,
        operation: (Connection, TableSessionRecord) -> T,
    ): T =
        execute { connection ->
            val tableState =
                tableTokenRepository.resolveForUpdate(
                    connection = connection,
                    token = tableToken,
                    expectedVenueId = expectedVenueId,
                    expectedTableId = expectedTableId,
                ) ?: throw NotFoundException()
            if (
                !tableState.tokenActive ||
                !tableState.tableActive ||
                tableState.venueStatus != VenueStatus.PUBLISHED
            ) {
                throw NotFoundException()
            }
            if (
                subscriptionRepository.getSubscriptionStatus(
                    connection = connection,
                    venueId = expectedVenueId,
                    forUpdate = true,
                ).isBlockedForGuest()
            ) {
                throw SubscriptionBlockedException()
            }
            val tableSession =
                tableSessionRepository.findSessionForTable(
                    connection = connection,
                    tableSessionId = expectedTableSessionId,
                    venueId = expectedVenueId,
                    tableId = expectedTableId,
                    forUpdate = true,
                )?.takeIf { session ->
                    session.status == TableSessionStatus.ACTIVE &&
                        session.endedAt == null &&
                        session.expiresAt.isAfter(java.time.Instant.now())
                } ?: throw NotFoundException()
            if (tableSessionRepository.hasUserExit(connection, actorUserId, tableSession.id)) {
                throw NotFoundException()
            }
            operation(connection, tableSession)
        }

    suspend fun <T> execute(operation: (Connection) -> T): T {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            val connection =
                try {
                    ds.connection
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SQLException) {
                    throw DatabaseUnavailableException()
                }
            try {
                connection.autoCommit = false
                try {
                    operation(connection).also { connection.commit() }
                } catch (e: CancellationException) {
                    runCatching { connection.rollback() }
                    throw e
                } catch (e: SQLException) {
                    runCatching { connection.rollback() }
                    throw DatabaseUnavailableException()
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    throw e
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                throw e
            } catch (_: SQLException) {
                runCatching { connection.rollback() }
                throw DatabaseUnavailableException()
            } finally {
                runCatching { connection.close() }
            }
        }
    }
}
