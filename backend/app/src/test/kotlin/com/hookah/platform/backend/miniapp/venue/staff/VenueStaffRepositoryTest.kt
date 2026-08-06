package com.hookah.platform.backend.miniapp.venue.staff

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalTargetedAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VenueStaffRepositoryTest {
    @Test
    fun `role change commits one exact targeted audit`() =
        withFixture {
            val result =
                repository.updateRoleWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    newRole = VenueRole.MANAGER,
                    source = VenueStaffMutationSource.VENUE_MINI_APP,
                )

            val success = assertIs<VenueStaffUpdateResult.Success>(result)
            assertEquals(VenueStaffMutationOutcome.APPLIED, success.outcome)
            assertEquals("MANAGER", success.member.role)
            assertEquals("MANAGER", membershipRole(targetUserId))

            val audit = auditRows().single()
            assertEquals(ownerUserId, audit.actorUserId)
            assertEquals(targetUserId, audit.targetUserId)
            assertEquals(VENUE_STAFF_ROLE_CHANGED_ACTION, audit.action)
            assertEquals("venue", audit.entityType)
            assertEquals(venueId, audit.entityId)
            assertEquals(
                mapOf(
                    "oldRole" to "STAFF",
                    "newRole" to "MANAGER",
                    "source" to "VENUE_MINI_APP",
                ),
                audit.payload.stringValues(),
            )
            assertAuditPayloadIsIdentityFree(audit.payload)
        }

    @Test
    fun `same role is no op and writes no audit`() =
        withFixture {
            val result =
                repository.updateRoleWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    newRole = VenueRole.STAFF,
                    source = VenueStaffMutationSource.TELEGRAM_BOT,
                )

            val success = assertIs<VenueStaffUpdateResult.Success>(result)
            assertEquals(VenueStaffMutationOutcome.NO_OP, success.outcome)
            assertEquals("STAFF", success.member.role)
            assertEquals("STAFF", membershipRole(targetUserId))
            assertTrue(auditRows().isEmpty())
        }

    @Test
    fun `removal commits one exact targeted audit and repeated or missing removal writes none`() =
        withFixture {
            val result =
                repository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    source = VenueStaffMutationSource.TELEGRAM_BOT,
                )

            assertEquals(VenueStaffRemoveResult.Success, result)
            assertFalse(membershipExists(targetUserId))
            assertTrue(userExists(targetUserId))

            val audit = auditRows().single()
            assertEquals(ownerUserId, audit.actorUserId)
            assertEquals(targetUserId, audit.targetUserId)
            assertEquals(VENUE_STAFF_MEMBER_REMOVED_ACTION, audit.action)
            assertEquals("venue", audit.entityType)
            assertEquals(venueId, audit.entityId)
            assertEquals(
                mapOf(
                    "oldRole" to "STAFF",
                    "source" to "TELEGRAM_BOT",
                ),
                audit.payload.stringValues(),
            )
            assertAuditPayloadIsIdentityFree(audit.payload)

            assertEquals(
                VenueStaffRemoveResult.NotFound,
                repository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    source = VenueStaffMutationSource.TELEGRAM_BOT,
                ),
            )
            assertEquals(
                VenueStaffRemoveResult.NotFound,
                repository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = missingTargetUserId,
                    source = VenueStaffMutationSource.VENUE_MINI_APP,
                ),
            )
            assertEquals(listOf(audit), auditRows())
        }

    @Test
    fun `last owner demotion and removal leave membership unchanged without audit`() =
        withFixture {
            val demotion =
                repository.updateRoleWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = ownerUserId,
                    newRole = VenueRole.STAFF,
                    source = VenueStaffMutationSource.VENUE_MINI_APP,
                )
            val removal =
                repository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = ownerUserId,
                    source = VenueStaffMutationSource.TELEGRAM_BOT,
                )

            assertEquals(VenueStaffUpdateResult.LastOwner, demotion)
            assertEquals(VenueStaffRemoveResult.LastOwner, removal)
            assertEquals("OWNER", membershipRole(ownerUserId))
            assertTrue(auditRows().isEmpty())
        }

    @Test
    fun `demoted or removed actor is denied without mutation or audit`() =
        withFixture {
            setMembershipRole(ownerUserId, "MANAGER")
            val demotedActorResult =
                repository.updateRoleWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    newRole = VenueRole.MANAGER,
                    source = VenueStaffMutationSource.VENUE_MINI_APP,
                )

            assertEquals(VenueStaffUpdateResult.Forbidden, demotedActorResult)
            assertEquals("STAFF", membershipRole(targetUserId))
            assertTrue(auditRows().isEmpty())

            setMembershipRole(ownerUserId, "OWNER")
            deleteMembership(ownerUserId)
            val removedActorResult =
                repository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    source = VenueStaffMutationSource.TELEGRAM_BOT,
                )

            assertEquals(VenueStaffRemoveResult.Forbidden, removedActorResult)
            assertEquals("STAFF", membershipRole(targetUserId))
            assertTrue(auditRows().isEmpty())
        }

    @Test
    fun `role change rolls back on audit failure without leaking target in repository logs`() =
        withFixture {
            val injectedMessage = "forced audit failure for target $targetUserId"
            val failingRepository = repositoryWithFailingAudit(injectedMessage)
            val appender = attachRepositoryLogAppender()
            try {
                val result =
                    failingRepository.updateRoleWithOwnerGuard(
                        venueId = venueId,
                        actorUserId = ownerUserId,
                        targetUserId = targetUserId,
                        newRole = VenueRole.MANAGER,
                        source = VenueStaffMutationSource.VENUE_MINI_APP,
                    )

                assertEquals(VenueStaffUpdateResult.DatabaseError, result)
                assertEquals("STAFF", membershipRole(targetUserId))
                assertTrue(auditRows().isEmpty())
                val capturedLogs = appender.list.joinToString("\n") { it.formattedMessage }
                assertFalse(capturedLogs.contains(targetUserId.toString()))
                assertFalse(capturedLogs.contains(injectedMessage))
                assertTrue(capturedLogs.contains(VENUE_STAFF_ROLE_CHANGED_ACTION))
            } finally {
                detachRepositoryLogAppender(appender)
            }
        }

    @Test
    fun `removal rolls back on audit failure and restores membership`() =
        withFixture {
            val failingRepository = repositoryWithFailingAudit("forced removal audit failure for target $targetUserId")

            val result =
                failingRepository.removeMemberWithOwnerGuard(
                    venueId = venueId,
                    actorUserId = ownerUserId,
                    targetUserId = targetUserId,
                    source = VenueStaffMutationSource.TELEGRAM_BOT,
                )

            assertEquals(VenueStaffRemoveResult.DatabaseError, result)
            assertEquals("STAFF", membershipRole(targetUserId))
            assertTrue(auditRows().isEmpty())
        }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            Fixture.create().use { fixture -> fixture.block() }
        }

    private class Fixture private constructor(
        private val dataSource: HikariDataSource,
        val repository: VenueStaffRepository,
        val ownerUserId: Long,
        val targetUserId: Long,
        val missingTargetUserId: Long,
        val venueId: Long,
    ) : AutoCloseable {
        fun repositoryWithFailingAudit(message: String): VenueStaffRepository {
            val delegate = AuditLogRepository(dataSource)
            return VenueStaffRepository(
                dataSource = dataSource,
                auditLogWriter =
                    object : TransactionalTargetedAuditLogWriter {
                        override fun appendTargetedJson(
                            connection: Connection,
                            actorUserId: Long,
                            targetUserId: Long,
                            action: String,
                            entityType: String,
                            entityId: Long?,
                            payload: JsonObject,
                        ) {
                            delegate.appendTargetedJson(
                                connection = connection,
                                actorUserId = actorUserId,
                                targetUserId = targetUserId,
                                action = action,
                                entityType = entityType,
                                entityId = entityId,
                                payload = payload,
                            )
                            throw SQLException(message)
                        }
                    },
            )
        }

        fun membershipRole(userId: Long): String? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rs -> if (rs.next()) rs.getString("role") else null }
                }
            }

        fun membershipExists(userId: Long): Boolean = membershipRole(userId) != null

        fun userExists(userId: Long): Boolean =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM users WHERE telegram_user_id = ?",
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.executeQuery().use { it.next() }
                }
            }

        fun setMembershipRole(
            userId: Long,
            role: String,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE venue_members SET role = ? WHERE venue_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setString(1, role)
                    statement.setLong(2, venueId)
                    statement.setLong(3, userId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun deleteMembership(userId: Long) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "DELETE FROM venue_members WHERE venue_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun auditRows(): List<AuditRow> =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT actor_user_id, target_user_id, action, entity_type, entity_id, payload_json
                    FROM audit_log
                    WHERE action IN (?, ?)
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, VENUE_STAFF_ROLE_CHANGED_ACTION)
                    statement.setString(2, VENUE_STAFF_MEMBER_REMOVED_ACTION)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    AuditRow(
                                        actorUserId = rs.getLong("actor_user_id"),
                                        targetUserId = rs.getLong("target_user_id"),
                                        action = rs.getString("action"),
                                        entityType = rs.getString("entity_type"),
                                        entityId = rs.getLong("entity_id").takeIf { !rs.wasNull() },
                                        payload = Json.parseToJsonElement(rs.getString("payload_json")).jsonObject,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

        fun assertAuditPayloadIsIdentityFree(payload: JsonObject) {
            val payloadText = payload.toString()
            assertFalse(payloadText.contains(ownerUserId.toString()))
            assertFalse(payloadText.contains(targetUserId.toString()))
            assertFalse("venueId" in payload)
            assertFalse("actorUserId" in payload)
            assertFalse("targetUserId" in payload)
        }

        override fun close() {
            dataSource.close()
        }

        companion object {
            fun create(): Fixture {
                val dataSource =
                    HikariDataSource(
                        HikariConfig().apply {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl =
                                "jdbc:h2:mem:venue_staff_repository_${UUID.randomUUID()};MODE=PostgreSQL;" +
                                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                            username = "sa"
                            password = ""
                            maximumPoolSize = 4
                        },
                    )
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/h2")
                    .load()
                    .migrate()

                val ownerUserId = 8_701_000_001L
                val targetUserId = 8_701_000_002L
                val missingTargetUserId = 8_701_000_099L
                seedUser(dataSource, ownerUserId, "owner")
                seedUser(dataSource, targetUserId, "target")
                val venueId = seedVenue(dataSource)
                seedMembership(dataSource, venueId, ownerUserId, "OWNER")
                seedMembership(dataSource, venueId, targetUserId, "STAFF")

                return Fixture(
                    dataSource = dataSource,
                    repository = VenueStaffRepository(dataSource, AuditLogRepository(dataSource)),
                    ownerUserId = ownerUserId,
                    targetUserId = targetUserId,
                    missingTargetUserId = missingTargetUserId,
                    venueId = venueId,
                )
            }

            private fun seedUser(
                dataSource: HikariDataSource,
                userId: Long,
                username: String,
            ) {
                dataSource.connection.use { connection ->
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
            }

            private fun seedVenue(dataSource: HikariDataSource): Long =
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, city, address, status)
                        VALUES ('Audit Venue', 'City', 'Address', 'PUBLISHED')
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            assertTrue(rs.next())
                            rs.getLong(1)
                        }
                    }
                }

            private fun seedMembership(
                dataSource: HikariDataSource,
                venueId: Long,
                userId: Long,
                role: String,
            ) {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, ?)",
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, userId)
                        statement.setString(3, role)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private data class AuditRow(
        val actorUserId: Long,
        val targetUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long?,
        val payload: JsonObject,
    )

    private fun JsonObject.stringValues(): Map<String, String> =
        entries.associate { (key, value) -> key to value.jsonPrimitive.content }

    private fun attachRepositoryLogAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        repositoryLogger.addAppender(appender)
        return appender
    }

    private fun detachRepositoryLogAppender(appender: ListAppender<ILoggingEvent>) {
        repositoryLogger.detachAppender(appender)
        appender.stop()
    }

    private companion object {
        val repositoryLogger = LoggerFactory.getLogger(VenueStaffRepository::class.java) as Logger
    }
}
