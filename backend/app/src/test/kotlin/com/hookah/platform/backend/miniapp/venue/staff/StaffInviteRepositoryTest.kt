package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.TransactionalTargetedAuditLogWriter
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
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
            val owner = createInvite(role = "OWNER", audit = true)

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
            assertEquals(3, audits.size)
            val storedHashes = storedInviteHashes()
            audits.forEach { audit ->
                assertEquals(ownerUserId, audit.actorUserId)
                assertNull(audit.targetUserId)
                val payload = Json.parseToJsonElement(audit.payloadJson).jsonObject
                assertEquals(setOf("venueId", "inviteHandle", "targetRole"), payload.keys)
                assertEquals(venueId, payload.getValue("venueId").jsonPrimitive.content.toLong())
                val targetRole = payload.getValue("targetRole").jsonPrimitive.content
                assertTrue(targetRole in setOf("STAFF", "MANAGER", "OWNER"))
                assertEquals(if (targetRole == "OWNER") "venue" else "staff_invite", audit.entityType)
                assertEquals(venueId.takeIf { targetRole == "OWNER" }, audit.entityId)
                assertTrue(payload.getValue("inviteHandle").jsonPrimitive.content.startsWith("sih_"))
                assertFalse(audit.payloadJson.contains(staff.code))
                assertFalse(audit.payloadJson.contains(manager.code))
                assertFalse(audit.payloadJson.contains(owner.code))
                assertTrue(storedHashes.none { audit.payloadJson.contains(it) })
            }
            assertEquals(
                listOf(
                    STAFF_INVITE_CREATED_AUDIT_ACTION,
                    STAFF_INVITE_CREATED_AUDIT_ACTION,
                    VENUE_OWNER_INVITE_CREATE_AUDIT_ACTION,
                ),
                audits.map { it.action },
            )
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
                repository.acceptInvite(created.code, inviteeUserId, auditLogRepository) { _, _, _, _ ->
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
                repository.acceptInvite(
                    used.code,
                    inviteeUserId,
                    auditLogRepository,
                ) { connection, targetVenueId, role, invitedBy ->
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
            assertEquals(listOf(STAFF_INVITE_ACCEPTED_AUDIT_ACTION), auditRows().map { it.action })
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
                            repository.acceptInvite(created.code, inviteeUserId, auditLogRepository) {
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
            assertEquals(
                if (acceptWon) STAFF_INVITE_ACCEPTED_AUDIT_ACTION else STAFF_INVITE_REVOKED_AUDIT_ACTION,
                auditRows().single().action,
            )
        }

    @Test
    fun `concurrent double accept commits one staff membership and one invite use`() =
        withFixture {
            val created = createInvite(role = "STAFF", audit = true)
            val start = CyclicBarrier(2)
            val createMemberCalls = AtomicInteger()

            val results =
                coroutineScope {
                    List(2) {
                        async(Dispatchers.IO) {
                            start.await()
                            repository.acceptInvite(created.code, inviteeUserId, auditLogRepository) {
                                    connection,
                                    targetVenueId,
                                    role,
                                    invitedBy,
                                ->
                                createMemberCalls.incrementAndGet()
                                insertMember(connection, targetVenueId, inviteeUserId, role, invitedBy)
                            }
                        }
                    }.awaitAll()
                }

            assertEquals(1, results.count { it is StaffInviteAcceptResult.Success })
            assertEquals(1, results.count { it == StaffInviteAcceptResult.InvalidOrExpired })
            val success =
                assertIs<StaffInviteAcceptResult.Success>(
                    results.single { it is StaffInviteAcceptResult.Success },
                )
            assertFalse(success.alreadyMember)
            assertEquals("STAFF", success.invitedRole)
            assertEquals("STAFF", success.member.role)
            assertEquals(1, createMemberCalls.get())
            assertEquals(1, memberCount(venueId, inviteeUserId))

            val state = inviteState(created.handle)
            assertNotNull(state.usedAt)
            assertEquals(inviteeUserId, state.usedByUserId)
            assertNull(state.revokedAt)
            assertEquals(1, inviteCount())
            assertEquals(
                listOf(STAFF_INVITE_CREATED_AUDIT_ACTION, STAFF_INVITE_ACCEPTED_AUDIT_ACTION),
                auditRows().map { it.action },
            )
        }

    @Test
    fun `staff manager and owner acceptance write one exact safe targeted audit each`() =
        withFixture {
            val createdByRole =
                listOf("STAFF", "MANAGER", "OWNER").associateWith { role ->
                    createInvite(role = role, audit = false)
                }
            val preparedRoles = mutableListOf<String>()

            createdByRole.forEach { (role, created) ->
                val accepted =
                    repository.acceptInvite(
                        code = created.code,
                        userId = inviteeUserId,
                        auditLogWriter = auditLogRepository,
                        prepareMember = { _, targetVenueId, invitedRole, _ ->
                            assertEquals(venueId, targetVenueId)
                            preparedRoles += invitedRole
                            true
                        },
                        createMember = {
                                connection,
                                targetVenueId,
                                invitedRole,
                                invitedBy,
                            ->
                            insertMember(connection, targetVenueId, inviteeUserId, invitedRole, invitedBy)
                        },
                    )
                val success = assertIs<StaffInviteAcceptResult.Success>(accepted)
                assertEquals(role, success.invitedRole)
            }

            assertEquals(listOf("STAFF", "MANAGER", "OWNER"), preparedRoles)
            val audits = auditRows()
            assertEquals(3, audits.size)
            assertEquals(
                listOf(
                    STAFF_INVITE_ACCEPTED_AUDIT_ACTION,
                    STAFF_INVITE_ACCEPTED_AUDIT_ACTION,
                    VENUE_OWNER_INVITE_ACCEPT_AUDIT_ACTION,
                ),
                audits.map { it.action },
            )
            audits.forEachIndexed { index, audit ->
                val role = listOf("STAFF", "MANAGER", "OWNER")[index]
                val created = createdByRole.getValue(role)
                assertEquals(inviteeUserId, audit.actorUserId)
                assertEquals(inviteeUserId, audit.targetUserId)
                assertEquals(if (role == "OWNER") "venue" else "staff_invite", audit.entityType)
                assertEquals(venueId.takeIf { role == "OWNER" }, audit.entityId)
                val payload = Json.parseToJsonElement(audit.payloadJson).jsonObject
                assertEquals(
                    setOf(
                        "venueId",
                        "inviteHandle",
                        "targetRole",
                        "alreadyMember",
                        "roleChanged",
                        "keptHigherRole",
                    ),
                    payload.keys,
                )
                assertEquals(venueId, payload.getValue("venueId").jsonPrimitive.content.toLong())
                assertEquals(created.handle, payload.getValue("inviteHandle").jsonPrimitive.content)
                assertEquals(role, payload.getValue("targetRole").jsonPrimitive.content)
                assertEquals(index > 0, payload.getValue("alreadyMember").jsonPrimitive.content.toBoolean())
                assertEquals(index > 0, payload.getValue("roleChanged").jsonPrimitive.content.toBoolean())
                assertFalse(payload.getValue("keptHigherRole").jsonPrimitive.content.toBoolean())
                assertFalse(audit.payloadJson.contains(created.code))
                assertTrue(storedInviteHashes().none { audit.payloadJson.contains(it) })
                assertFalse(payload.containsKey("acceptedUserId"))
                assertFalse(payload.containsKey("inviteCreatedByUserId"))
                assertFalse(payload.containsKey("actorUserId"))
                assertFalse(payload.containsKey("targetUserId"))
            }
        }

    @Test
    fun `accept audit failure rolls back membership and invite use`() =
        withFixture {
            val created = createInvite(role = "STAFF", audit = false)
            val failingAuditWriter =
                TransactionalTargetedAuditLogWriter { _, _, _, _, _, _, _ ->
                    throw SQLException("forced acceptance audit failure")
                }

            val result =
                repository.acceptInvite(created.code, inviteeUserId, failingAuditWriter) {
                        connection,
                        targetVenueId,
                        role,
                        invitedBy,
                    ->
                    insertMember(connection, targetVenueId, inviteeUserId, role, invitedBy)
                }

            assertEquals(StaffInviteAcceptResult.DatabaseError, result)
            assertEquals(0, memberCount(venueId, inviteeUserId))
            val state = inviteState(created.handle)
            assertNull(state.usedAt)
            assertNull(state.usedByUserId)
            assertTrue(auditRows().isEmpty())
            assertEquals(
                listOf(created.handle),
                repository.listPendingInvites(venueId, setOf("STAFF"))?.map { it.handle },
            )
        }

    @Test
    fun `malformed invite codes are state free for preview accept and decline`() =
        withFixture {
            val pending = createInvite(role = "STAFF", audit = true)
            val malformedCodes = listOf("", "INVALID0", "A".repeat(65), "staff_invite_token", "INVITE TOKEN")

            malformedCodes.forEach { malformedCode ->
                assertEquals(StaffInvitePreviewResult.InvalidOrExpired, repository.previewInvite(malformedCode))
                assertEquals(
                    StaffInviteAcceptResult.InvalidOrExpired,
                    repository.acceptInvite(malformedCode, inviteeUserId, auditLogRepository) { _, _, _, _ ->
                        error("malformed invite must not reach membership creation")
                    },
                )
                assertEquals(
                    StaffInviteDeclineResult.InvalidOrExpired,
                    repository.declineInvite(malformedCode, inviteeUserId),
                )
            }

            assertEquals(0, memberCount(venueId, inviteeUserId))
            val state = inviteState(pending.handle)
            assertNull(state.usedAt)
            assertNull(state.usedByUserId)
            assertNull(state.revokedAt)
            assertEquals(
                listOf(pending.handle),
                repository.listPendingInvites(venueId, setOf("STAFF"))?.map { it.handle },
            )
            assertEquals(listOf(STAFF_INVITE_CREATED_AUDIT_ACTION), auditRows().map { it.action })
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

    @Test
    fun `bounded create caps active pending invites per venue and role`() =
        withFixture {
            val first =
                repository.createBoundedInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = "STAFF",
                    ttlSeconds = 300,
                    maxActivePendingPerVenueRole = 1,
                    auditLogRepository = auditLogRepository,
                )
            assertIs<StaffInviteCreateResult.Success>(first)

            val capped =
                repository.createBoundedInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = "STAFF",
                    ttlSeconds = 300,
                    maxActivePendingPerVenueRole = 1,
                    auditLogRepository = auditLogRepository,
                )
            assertEquals(300L, assertIs<StaffInviteCreateResult.RateLimited>(capped).retryAfterSeconds)

            assertIs<StaffInviteCreateResult.Success>(
                repository.createBoundedInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = "MANAGER",
                    ttlSeconds = 300,
                    maxActivePendingPerVenueRole = 1,
                    auditLogRepository = auditLogRepository,
                ),
            )
            assertEquals(2, inviteCount())
            assertEquals(2, auditRows().size)

            nowRef.set(nowRef.get().plusSeconds(301))
            assertIs<StaffInviteCreateResult.Success>(
                repository.createBoundedInvite(
                    venueId = venueId,
                    createdByUserId = ownerUserId,
                    role = "STAFF",
                    ttlSeconds = 300,
                    maxActivePendingPerVenueRole = 1,
                    auditLogRepository = auditLogRepository,
                ),
            )
            assertEquals(3, inviteCount())
            assertEquals(3, auditRows().size)
        }

    @Test
    fun `bounded create serializes concurrent attempts at the active cap`() =
        withFixture {
            val start = CyclicBarrier(2)
            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.IO) {
                            start.await()
                            repository.createBoundedInvite(
                                venueId = venueId,
                                createdByUserId = ownerUserId,
                                role = "STAFF",
                                ttlSeconds = 300,
                                maxActivePendingPerVenueRole = 1,
                                auditLogRepository = auditLogRepository,
                            )
                        },
                        async(Dispatchers.IO) {
                            start.await()
                            repository.createBoundedInvite(
                                venueId = venueId,
                                createdByUserId = ownerUserId,
                                role = "STAFF",
                                ttlSeconds = 300,
                                maxActivePendingPerVenueRole = 1,
                                auditLogRepository = auditLogRepository,
                            )
                        },
                    ).awaitAll()
                }

            assertEquals(1, results.count { it is StaffInviteCreateResult.Success })
            assertEquals(1, results.count { it is StaffInviteCreateResult.RateLimited })
            assertEquals(1, inviteCount())
            assertEquals(1, auditRows().size)
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
                    auditLogRepository = NO_OP_AUDIT_LOG_WRITER,
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
                    SELECT code_hash, used_at, used_by_user_id, revoked_at, revoked_by_user_id
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
                                    usedByUserId = rs.getLong("used_by_user_id").takeIf { !rs.wasNull() },
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
                    SELECT actor_user_id, target_user_id, action, entity_type, entity_id, payload_json
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
                                        targetUserId = rs.getLong("target_user_id").takeIf { !rs.wasNull() },
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

        fun memberCount(
            targetVenueId: Long,
            userId: Long,
        ): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM venue_members WHERE venue_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, targetVenueId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
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
        val targetUserId: Long?,
        val action: String,
        val entityType: String,
        val entityId: Long?,
        val payloadJson: String,
    )

    private data class InviteState(
        val usedAt: Instant?,
        val usedByUserId: Long?,
        val revokedAt: Instant?,
        val revokedByUserId: Long?,
    )
}

private const val INVITE_PEPPER = "repository-test-invite-pepper"

private val NO_OP_AUDIT_LOG_WRITER =
    TransactionalAuditLogWriter { _, _, _, _, _, _ -> Unit }

private fun deriveHandleForTest(
    codeHash: String,
    pepper: String,
): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val bytes = mac.doFinal("staff-invite-handle-v1:$codeHash".toByteArray(Charsets.UTF_8))
    return "sih_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
