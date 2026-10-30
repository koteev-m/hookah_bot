package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.staff.photoTestDataSource
import com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueInfoMediaConcurrencyPostgresTest {
    @Test fun `cleanup blocked on finalization commit rechecks status and preserves the committed object`() =
        runBlocking<Unit> {
            MediaFixture(photoTestDataSource(true)).use { f ->
                f.init()
                val armed = AtomicBoolean(false)
                val atCommit = CountDownLatch(1)
                val release = CountDownLatch(1)
                val wrapped =
                    object : DataSource by f.ds {
                        override fun getConnection(): Connection {
                            val connection = f.ds.connection
                            return Proxy.newProxyInstance(
                                Connection::class.java.classLoader,
                                arrayOf(Connection::class.java),
                            ) { _, method, args ->
                                if (method.name == "commit" && armed.compareAndSet(true, false)) {
                                    atCommit.countDown()
                                    check(release.await(10, TimeUnit.SECONDS))
                                }
                                try {
                                    method.invoke(connection, *(args ?: emptyArray()))
                                } catch (
                                    e: InvocationTargetException,
                                ) {
                                    throw e.targetException
                                }
                            } as Connection
                        }
                    }
                val repo = VenueInfoMediaRepository(wrapped, AuditLogRepository(wrapped), f.clock)
                val file = VenueInfoMediaValidation.validate(syntheticPhoto(), "image/png")
                val reservation =
                    repo.reserve(
                        f.venue,
                        f.section,
                        101,
                        0,
                        UUID.randomUUID().toString(),
                        "input",
                        file,
                        null,
                    )
                f.storage.put(reservation.key, file)
                f.clock.now = f.clock.now.plusSeconds(601)
                withTimeout(15000) {
                    coroutineScope {
                        armed.set(true)
                        val finalize =
                            async(Dispatchers.IO) { repo.finalizeUpload(f.venue, f.section, 101, reservation.id) }
                        assertTrue(withContext(Dispatchers.IO) { atCommit.await(5, TimeUnit.SECONDS) })
                        val cleanup = async(Dispatchers.IO) { repo.cleanupBatch() }
                        try {
                            withTimeout(5000) {
                                while (f.count(
                                        """SELECT COUNT(*) FROM pg_stat_activity
                                WHERE wait_event_type='Lock' AND query LIKE '%venue_info_section_media%'""",
                                    ) == 0L
                                    ) delay(10)
                            }
                            assertFalse(cleanup.isCompleted)
                        } finally {
                            release.countDown()
                        }
                        finalize.await()
                        assertTrue(cleanup.await().isEmpty())
                    }
                }
                assertEquals("READY", f.current().media.single().status)
                assertTrue(f.storage.exists(reservation.key))
            }
        }
}
