package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StaffInviteReissuePostgresTest {
    @Test
    fun `accept revoke and second reissue compete for one exact source without deadlock`() =
        runBlocking {
            for (competitor in listOf("accept", "revoke", "reissue")) {
                fixture { f ->
                    val old = f.create()
                    f.ds.connection.use { blocker ->
                        blocker.autoCommit = false
                        blocker.createStatement().use {
                            it.executeQuery(
                                "SELECT code_hash FROM venue_staff_invites FOR UPDATE",
                            ).close()
                        }
                        coroutineScope {
                            val firstPid = AtomicInteger()
                            val secondPid = AtomicInteger()
                            val firstRepo = f.observedRepository(firstPid)
                            val secondRepo = f.observedRepository(secondPid)
                            val first = async(Dispatchers.IO) { f.reissue(firstRepo, old) }
                            val second =
                                async(Dispatchers.IO) {
                                    when (competitor) {
                                        "accept" ->
                                            secondRepo.acceptInvite(old.code, INVITEE, f.audit) { c, v, r, by ->
                                                VenueStaffRepository(
                                                    f.ds,
                                                ).createMemberInTransaction(c, v, INVITEE, r, by)
                                            }
                                        "revoke" ->
                                            secondRepo.revokePendingInvite(
                                                f.venue,
                                                old.handle,
                                                OWNER,
                                                setOf("STAFF"),
                                                f.audit,
                                            )
                                        else -> f.reissue(secondRepo, old)
                                    }
                                }
                            try {
                                f.awaitBlocked(firstPid, secondPid)
                                assertEquals(1, f.count("venue_staff_invites"))
                                assertEquals(0, f.count("audit_log"))
                            } finally {
                                blocker.rollback()
                            }
                            val results = withTimeout(10_000) { listOf(first.await(), second.await()) }
                            val wonReissue = results.count { it is StaffInviteReissueResult.Success }
                            val wonAccept = results.count { it is StaffInviteAcceptResult.Success }
                            val wonRevoke = results.count { it is StaffInviteRevokeResult.Success }
                            assertEquals(1, wonReissue + wonAccept + wonRevoke, results.toString())
                            assertTrue(
                                results.none {
                                    it == StaffInviteReissueResult.DatabaseError ||
                                        it == StaffInviteAcceptResult.DatabaseError ||
                                        it == StaffInviteRevokeResult.DatabaseError
                                },
                            )
                            assertEquals(1 + wonReissue, f.count("venue_staff_invites"))
                            assertEquals(2 * wonReissue + wonAccept + wonRevoke, f.count("audit_log"))
                            assertEquals(wonAccept, f.count("venue_members", "user_id = $INVITEE"))
                            assertEquals(wonReissue, f.pending())
                            assertEquals(StaffInvitePreviewResult.InvalidOrExpired, f.repo.previewInvite(old.code))
                        }
                    }
                }
            }
        }

    @Test
    fun `two different reissues and create serialize on the same venue quota`() =
        runBlocking {
            fixture { f ->
                val old1 = f.create()
                val old2 = f.create()
                f.ds.connection.use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use {
                        it.executeQuery(
                            "SELECT id FROM venues WHERE id = ${f.venue} FOR UPDATE",
                        ).close()
                    }
                    coroutineScope {
                        val pids = List(3) { AtomicInteger() }
                        val first = async(Dispatchers.IO) { f.reissue(f.observedRepository(pids[0]), old1, cap = 2) }
                        val second = async(Dispatchers.IO) { f.reissue(f.observedRepository(pids[1]), old2, cap = 2) }
                        val create =
                            async(Dispatchers.IO) {
                                f.observedRepository(
                                    pids[2],
                                ).createBoundedInvite(f.venue, OWNER, "STAFF", 600, 2, f.audit)
                            }
                        try {
                            f.awaitBlocked(*pids.toTypedArray())
                        } finally {
                            blocker.rollback()
                        }
                        withTimeout(10_000) {
                            assertIs<StaffInviteReissueResult.Success>(first.await())
                            assertIs<StaffInviteReissueResult.Success>(second.await())
                            assertIs<StaffInviteCreateResult.RateLimited>(create.await())
                        }
                        assertEquals(2, f.pending())
                        assertEquals(4, f.count("venue_staff_invites"))
                        assertEquals(4, f.count("audit_log"))
                    }
                }
            }
        }

    @Test
    fun `role demotion and removal committed during lock wait deny reissue without writes`() =
        runBlocking {
            for (remove in listOf(false, true)) fixture { f ->
                val old = f.create()
                f.ds.connection.use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use {
                        it.executeQuery(
                            "SELECT role FROM venue_members " +
                                "WHERE venue_id = ${f.venue} AND user_id = $OWNER FOR UPDATE",
                        ).close()
                    }
                    coroutineScope {
                        val pid = AtomicInteger()
                        val result = async(Dispatchers.IO) { f.reissue(f.observedRepository(pid), old) }
                        try {
                            f.awaitBlocked(pid)
                            blocker.createStatement().use {
                                it.executeUpdate(
                                    if (remove) {
                                        "DELETE FROM venue_members WHERE user_id = $OWNER"
                                    } else {
                                        "UPDATE venue_members SET role = 'STAFF' WHERE user_id = $OWNER"
                                    },
                                )
                            }
                            blocker.commit()
                        } finally {
                            blocker.rollback()
                        }
                        assertEquals(StaffInviteReissueResult.Forbidden, withTimeout(10_000) { result.await() })
                        assertEquals(1, f.pending())
                        assertEquals(1, f.count("venue_staff_invites"))
                        assertEquals(0, f.count("audit_log"))
                    }
                }
            }
        }

    @Test
    fun `expiry after waiting and second audit failure preserve the original invitation`() =
        runBlocking {
            fixture { f ->
                val old = f.create()
                f.ds.connection.use { blocker ->
                    blocker.autoCommit = false
                    blocker.createStatement().use {
                        it.executeQuery(
                            "SELECT id FROM venues WHERE id = ${f.venue} FOR UPDATE",
                        ).close()
                    }
                    coroutineScope {
                        val pid = AtomicInteger()
                        val result = async(Dispatchers.IO) { f.reissue(f.observedRepository(pid), old) }
                        try {
                            f.awaitBlocked(pid)
                            f.now.set(f.now.get().plusSeconds(301))
                        } finally {
                            blocker.rollback()
                        }
                        assertEquals(StaffInviteReissueResult.InvalidOrExpired, withTimeout(10_000) { result.await() })
                    }
                }
                f.now.set(Instant.parse("2030-01-01T10:00:00Z"))
                val calls = AtomicInteger()
                val failing =
                    TransactionalAuditLogWriter { c, actor, action, type, id, payload ->
                        f.audit.appendJson(c, actor, action, type, id, payload)
                        if (calls.incrementAndGet() == 2) error("synthetic audit failure")
                    }
                assertEquals(
                    StaffInviteReissueResult.DatabaseError,
                    f.repo.reissuePendingInvite(f.venue, old.handle, OWNER, 600, 1, failing),
                )
                assertEquals(2, calls.get())
                assertEquals(1, f.pending())
                assertEquals(1, f.count("venue_staff_invites"))
                assertEquals(0, f.count("audit_log"))
                assertIs<StaffInvitePreviewResult.Success>(f.repo.previewInvite(old.code))
            }
        }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { ds ->
            ds.maximumPoolSize = 8
            val venue =
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeUpdate(
                            "INSERT INTO users (telegram_user_id, first_name) " +
                                "VALUES ($OWNER, 'Owner'), ($INVITEE, 'Invitee')",
                        )
                        s.executeUpdate(
                            "INSERT INTO venues (name, city, address, status) " +
                                "VALUES ('Synthetic UX07', 'City', 'Address', 'PUBLISHED')",
                            Statement.RETURN_GENERATED_KEYS,
                        )
                        val id =
                            s.generatedKeys.use { rs ->
                                assertTrue(rs.next())
                                rs.getLong(1)
                            }
                        s.executeUpdate(
                            "INSERT INTO venue_members (venue_id, user_id, role) VALUES ($id, $OWNER, 'OWNER')",
                        )
                        id
                    }
                }
            block(Fixture(ds, venue))
        }
    }

    private class Fixture(val ds: HikariDataSource, val venue: Long) {
        val now = AtomicReference(Instant.parse("2030-01-01T10:00:00Z"))
        val audit = AuditLogRepository(ds)
        val repo = StaffInviteRepository(ds, "synthetic-ux07-test-pepper", now::get)

        suspend fun create(): StaffInviteCodeResult =
            assertNotNull(
                repo.createInvite(venue, OWNER, "STAFF", 300, TransactionalAuditLogWriter { _, _, _, _, _, _ -> }),
            )

        suspend fun reissue(
            repository: StaffInviteRepository,
            old: StaffInviteCodeResult,
            cap: Int = 1,
        ) = repository.reissuePendingInvite(
            venue,
            old.handle,
            OWNER,
            600,
            cap,
            audit,
        )

        fun count(
            table: String,
            condition: String = "TRUE",
        ): Int =
            ds.connection.use { c ->
                c.createStatement().use {
                        s ->
                    s.executeQuery("SELECT COUNT(*) FROM $table WHERE $condition").use {
                            rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        suspend fun pending(): Int = assertNotNull(repo.listPendingInvites(venue, setOf("STAFF"))).size

        fun observedRepository(pid: AtomicInteger): StaffInviteRepository {
            val observed =
                object : DataSource by ds {
                    override fun getConnection(): Connection {
                        val c = ds.connection
                        c.createStatement().use {
                                s ->
                            s.execute("SET lock_timeout = '5s'")
                            s.execute("SET statement_timeout = '8s'")
                        }
                        c.createStatement().use {
                                s ->
                            s.executeQuery("SELECT pg_backend_pid()").use { rs ->
                                rs.next()
                                pid.set(rs.getInt(1))
                            }
                        }
                        return c
                    }
                }
            return StaffInviteRepository(observed, "synthetic-ux07-test-pepper", now::get)
        }

        suspend fun awaitBlocked(vararg pids: AtomicInteger) =
            withTimeout(5_000) {
                while (true) {
                    val blocked =
                        ds.connection.use { c ->
                            pids.all { pid ->
                                pid.get() > 0 &&
                                    c.prepareStatement("SELECT cardinality(pg_blocking_pids(?)) > 0").use { s ->
                                        s.setInt(1, pid.get())
                                        s.executeQuery().use { rs ->
                                            rs.next()
                                            rs.getBoolean(1)
                                        }
                                    }
                            }
                        }
                    if (blocked) break
                    delay(20)
                }
            }
    }
}

private const val OWNER = 9701L
private const val INVITEE = 9702L
