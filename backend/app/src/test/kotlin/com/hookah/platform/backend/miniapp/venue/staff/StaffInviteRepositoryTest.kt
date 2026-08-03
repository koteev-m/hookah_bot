package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffInviteRepositoryTest {
    @Test
    fun `pending list is role scoped and create audit contains only safe opaque identity`() =
        withFixture {
            val staff = createInvite(role = "STAFF", audit = true)
            val manager = createInvite(role = "MANAGER", audit = true)
            createInvite(role = "OWNER", audit = false)

            val managerView = repository.listPendingInvites(venueId, setOf("STAFF"))
            assertEquals(listOf(staff.handle), managerView?.map { it.handle })
            assertEquals(listOf("STAFF"), managerView?.map { it.role })

            val ownerView = repository.listPendingInvites(venueId, setOf("STAFF", "MANAGER"))
            assertEquals(setOf(staff.handle, manager.handle), ownerView?.map { it.handle }?.toSet())
            assertTrue(repository.listPendingInvites(foreignVenueId, setOf("STAFF", "MANAGER")).isNullOrEmpty())

            assertTrue(staff.handle.startsWith("sih_"))
            assertNotEquals(staff.code, staff.handle)
            assertFalse(staff.handle.contains(staff.code))

            val audits = auditRows()
            assertEquals(2, audits.size)
            val storedHashes = storedInviteHashes()
            audits.forEach { audit ->
                assertEquals(ownerUserId, audit.actorUserId)
                assertEquals(STAFF_INVITE_CREATED_AUDIT_ACTION, audit.action)
                assertEquals("staff_invite", audit.entityType)
                assertNull(audit.entityId)
                val payload = Json.parseToJsonElement(audit.payloadJson).jsonObject
                assertEquals(setOf("venueId", "inviteHandle", "targetRole"), payload.keys)
                assertEquals(venueId, payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertTrue(payload.getValue("targetRole").jsonPrimitive.content in setOf("STAFF", "MANAGER"))
                assertTrue(payload.getValue("inviteHandle").jsonPrimitive.content.startsWith("sih_"))
                assertFalse(audit.payloadJson.contains(staff.code))
                assertFalse(audit.payloadJson.contains(manager.code))
                assertTrue(storedHashes.none { audit.payloadJson.contains(it) })
            }
        }

    @Test
    fun `revoked invite fails closed for list preview accept decline and repeated revoke`() =
        withFixture {
            val created = createInvite(role = "STAFF", audit = false)

            val revoked =
                repository.revokePendingInvite(
                    venueId = venueId,
                    handle = created.handle,
                    actorUserId = ownerUserId,
                    allowedRoles = setOf("STAFF"),
                    auditLogRepository = auditLogRepository,
                )

            val success = assertIs<StaffInviteRevokeResult.Success>(revoked)
            assertEquals(created.handle, success.invite.handle)
            assertEquals("STAFF", success.invite.role)
            assertTrue(repository.listPendingInvites(venueId, setOf("STAFF")).isNullOrEmpty())
            assertEquals(StaffInvitePreviewResult.InvalidOrExpired, repository.previewInvite(created.code))
            assertEquals(
                StaffInviteAcceptResult.InvalidOrExpired,
                repository.acceptInvite(created.code, inviteeUserId) { _, _, _, _ ->
                    error("revoked invite must not reach membership creation")
                },
            )
            assertEquals(
                StaffInviteDeclineResult.InvalidOrExpired,
                repository.declineInvite(created.code, inviteeUserId),
            )
            assertEquals(
                StaffInviteRevokeResult.InvalidOrExpired,
                repository.revokePendingInvite(
                    venueId = venueId,
                    handle = created.handle,
                    actorUserId = ownerUserId,
                    allowedRoles = setOf("STAFF"),
                    auditLogRepository = auditLogRepository,
                ),
            )

            val state = inviteState(created.handle)
            assertNotNull(state.revokedAt)
            assertEquals(ownerUserId, state.revokedByUserId)
            assertNull(state.usedAt)
            val audit = auditRows().single()
            assertEquals(STAFF_INVITE_REVOKED_AUDIT_ACTION, audit.action)
            assertEquals(
                setOf("venueId", "inviteHandle", "targetRole"),
                Json.parseToJsonElement(audit.payloadJson).jsonObject.keys,
            )
            assertFalse(audit.payloadJson.contains(created.code))
            assertTrue(storedInviteHashes().none { audit.payloadJson.contains(it) })
        }

    @Test
    fun `used and expired invites cannot be revoked`() =
        withFixture {
            val used = createInvite(role = "STAFF", audit = false)
            val accepted =
                repository.acceptInvite(used.code, inviteeUserId) { connection, targetVenueId, role, invitedBy ->
                    insertMember(connection, targetVenueId, inviteeUserId, role, invitedBy)
                }
            assertIs<StaffInviteAcceptResult.Success>(accepted)
            assertEquals(
                StaffInviteRevokeResult.InvalidOrExpired,
                repository.revokePendingInvite(
                    venueId = venueId,
                    handle = used.handle,
                    actorUserId = ownerUserId,
                    allowedRoles = setOf("STAFF"),
                    auditLogRepository = auditLogRepository,
                ),
            )
            assertNotNull(inviteState(used.handle).usedAt)

            val expired = createInvite(role = "STAFF", audit = false, ttlSeconds = 60)
            nowRef.set(nowRef.get().plusSeconds(61))
            assertEquals(StaffInvitePreviewResult.InvalidOrExpired, repository.previewInvite(expired.code))
            assertEquals(
                StaffInviteRevokeResult.InvalidOrExpired,
                repository.revokePendingInvite(
                    venueId = venueId,
                    handle = expired.handle,
                    actorUserId = ownerUserId,
                    allowedRoles = setOf("STAFF"),
                    auditLogRepository = auditLogRepository,
                ),
            )
            assertNull(inviteState(expired.handle).revokedAt)
            assertTrue(auditRows().isEmpty())
        }

    @Test
    fun `accept versus revoke race has exactly one winner without partial membership`() =
        withFixture {
            val created = createInvite(role = "STAFF", audit = false)
            val start = CyclicBarrier(2)

            val (acceptResult, revokeResult) =
                coroutineScope {
                    val accept =
                        async(Dispatchers.IO) {
                            start.await()
                            repository.acceptInvite(created.code, inviteeUserId) {
                                    connection,
                                    targetVenueId,
                                    role,
                                    invitedBy,
                                ->
                                insertMember(connection, targetVenueId, inviteeUserId, role, invitedBy)
                            }
                        }
                    val revoke =
                        async(Dispatchers.IO) {
                            start.await()
                            repository.revokePendingInvite(
                                venueId = venueId,
                                handle = created.handle,
                                actorUserId = ownerUserId,
                                allowedRoles = setOf("STAFF"),
                                auditLogRepository = auditLogRepository,
                            )
                        }
                    accept.await() to revoke.await()
                }

            val acceptWon = acceptResult is StaffInviteAcceptResult.Success
            val revokeWon = revokeResult is StaffInviteRevokeResult.Success
            assertNotEquals(acceptWon, revokeWon)
            assertEquals(acceptWon, memberExists(venueId, inviteeUserId))

            val state = inviteState(created.handle)
            assertNotEquals(state.usedAt != null, state.revokedAt != null)
            assertEquals(acceptWon, state.usedAt != null)
            assertEquals(revokeWon, state.revokedAt != null)
            assertEquals(if (revokeWon) 1 else 0, auditRows().size)
        }

    @Test
    fun `audit failure rolls back create and revoke mutations`() =
        withFixture {
            dropAuditTable()

            val failedCreate =
                repository.createInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = "STAFF",
                    ttlSeconds = 300,
                    auditLogRepository = auditLogRepository,
                )
            assertNull(failedCreate)
            assertEquals(0, inviteCount())

            val pending = createInvite(role = "STAFF", audit = false)
            assertEquals(
                StaffInviteRevokeResult.DatabaseError,
                repository.revokePendingInvite(
                    venueId = venueId,
                    handle = pending.handle,
                    actorUserId = ownerUserId,
                    allowedRoles = setOf("STAFF"),
                    auditLogRepository = auditLogRepository,
                ),
            )
            assertNull(inviteState(pending.handle).revokedAt)
            assertEquals(
                listOf(pending.handle),
                repository.listPendingInvites(
                    venueId = venueId,
                    allowedRoles = setOf("STAFF"),
                )?.map { it.handle },
            )
        }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            val fixture = Fixture.create()
            try {
                fixture.block()
            } finally {
                fixture.close()
            }
        }

    private class Fixture private constructor(
        private val dataSource: HikariDataSource,
        val nowRef: AtomicReference<Instant>,
        val repository: StaffInviteRepository,
        val auditLogRepository: AuditLogRepository,
        val ownerUserId: Long,
        val inviteeUserId: Long,
        val venueId: Long,
        val foreignVenueId: Long,
    ) {
        suspend fun createInvite(
            role: String,
            audit: Boolean,
            ttlSeconds: Long = 300,
        ): StaffInviteCodeResult =
            if (audit) {
                repository.createInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = role,
                    ttlSeconds = ttlSeconds,
                    auditLogRepository = auditLogRepository,
                )
            } else {
                repository.createInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = role,
                    ttlSeconds = ttlSeconds,
                )
            } ?: error("Failed to create invite")

        fun insertMember(
            connection: Connection,
            targetVenueId: Long,
            userId: Long,
            role: String,
            invitedByUserId: Long?,
        ): VenueStaffMember {
            val createdAt = nowRef.get()
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role, created_at, invited_by_user_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, targetVenueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.setTimestamp(4, java.sql.Timestamp.from(createdAt))
                if (invitedByUserId == null) {
                    statement.setNull(5, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(5, invitedByUserId)
                }
                statement.executeUpdate()
            }
            return VenueStaffMember(targetVenueId, userId, role, createdAt, invitedByUserId)
        }

        fun inviteState(handle: String): InviteState =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT code_hash, used_at, revoked_at, revoked_by_user_id
                    FROM venue_staff_invites
                    WHERE venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        while (rs.next()) {
                            val codeHash = rs.getString("code_hash")
                            val candidate = repositoryHandleFor(codeHash)
                            if (candidate == handle) {
                                return InviteState(
                                    usedAt = rs.getTimestamp("used_at")?.toInstant(),
                                    revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                                    revokedByUserId = rs.getLong("revoked_by_user_id").takeIf { !rs.wasNull() },
                                )
                            }
                        }
                    }
                }
                error("Invite not found")
            }

        fun auditRows(): List<AuditRow> =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT actor_user_id, action, entity_type, entity_id, payload_json
                    FROM audit_log
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    AuditRow(
                                        actorUserId = rs.getLong("actor_user_id"),
                                        action = rs.getString("action"),
                                        entityType = rs.getString("entity_type"),
                                        entityId = rs.getLong("entity_id").takeIf { !rs.wasNull() },
                                        payloadJson = rs.getString("payload_json"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

        fun storedInviteHashes(): List<String> =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT code_hash FROM venue_staff_invites WHERE venue_id = ? ORDER BY code_hash",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getString("code_hash"))
                        }
                    }
                }
            }

        fun memberExists(
            targetVenueId: Long,
            userId: Long,
        ): Boolean =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT 1 FROM venue_members WHERE venue_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, targetVenueId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { it.next() }
                }
            }

        fun inviteCount(): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM venue_staff_invites").use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        fun dropAuditTable() {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DROP TABLE audit_log").use { it.executeUpdate() }
            }
        }

        private fun repositoryHandleFor(codeHash: String): String {
            return deriveHandleForTest(codeHash, INVITE_PEPPER)
        }

        fun close() {
            dataSource.close()
        }

        companion object {
            fun create(): Fixture {
                val dbName = "staff_invite_repository_${UUID.randomUUID()}"
                val dataSource =
                    HikariDataSource(
                        HikariConfig().apply {
                            driverClassName = "org.h2.Driver"
                            jdbcUrl =
                                "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                            maximumPoolSize = 6
                        },
                    )
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/h2")
                    .load()
                    .migrate()
                val nowRef = AtomicReference(Instant.parse("2030-01-01T10:00:00Z"))
                val ownerUserId = 9_101L
                val inviteeUserId = 9_102L
                seedUser(dataSource, ownerUserId, "owner")
                seedUser(dataSource, inviteeUserId, "invitee")
                val venueId = seedVenue(dataSource, "Invite Venue")
                val foreignVenueId = seedVenue(dataSource, "Foreign Venue")
                return Fixture(
                    dataSource = dataSource,
                    nowRef = nowRef,
                    repository = StaffInviteRepository(dataSource, INVITE_PEPPER, now = { nowRef.get() }),
                    auditLogRepository = AuditLogRepository(dataSource),
                    ownerUserId = ownerUserId,
                    inviteeUserId = inviteeUserId,
                    venueId = venueId,
                    foreignVenueId = foreignVenueId,
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

            private fun seedVenue(
                dataSource: HikariDataSource,
                name: String,
            ): Long =
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO venues (name, city, address, status)
                        VALUES (?, 'City', 'Address', 'PUBLISHED')
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setString(1, name)
                        statement.executeUpdate()
                        statement.generatedKeys.use { rs ->
                            assertTrue(rs.next())
                            rs.getLong(1)
                        }
                    }
                }
        }
    }

    private data class AuditRow(
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long?,
        val payloadJson: String,
    )

    private data class InviteState(
        val usedAt: Instant?,
        val revokedAt: Instant?,
        val revokedByUserId: Long?,
    )
}

private const val INVITE_PEPPER = "repository-test-invite-pepper"

private fun deriveHandleForTest(
    codeHash: String,
    pepper: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val bytes = mac.doFinal("staff-invite-handle-v1:$codeHash".toByteArray(Charsets.UTF_8))
    return "sih_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
