package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
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

class StaffPhotoConcurrencyPostgresTest {
    @Test fun `cleanup waiting on finalization commit rechecks predicate and preserves candidate`() =
        runBlocking<Unit> {
            photoTestDataSource(true).use { ds ->
                val f = PhotoFixture(ds)
                val armed = AtomicBoolean(false)
                val atCommit = CountDownLatch(1)
                val release = CountDownLatch(1)
                val wrapped =
                    object : DataSource by ds {
                        override fun getConnection(): Connection {
                            val connection = ds.connection
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
                val repo =
                    StaffPhotoRepository(
                        wrapped,
                        VenueStaffProfileRepository(wrapped),
                        AuditLogRepository(wrapped),
                        f.clock,
                    )
                val image = StaffPhotoImage.normalize(syntheticPhoto(), "image/png")
                val reservation = repo.reserve(f.venue, f.display, 101, 0, UUID.randomUUID().toString(), "input", image)
                f.storage.put(reservation.photo.key, image.bytes)
                f.clock.now = f.clock.now.plusSeconds(601)
                withTimeout(15_000) {
                    coroutineScope {
                        armed.set(true)
                        val finalize = async(Dispatchers.IO) { repo.finalizeUpload(reservation.photo) }
                        assertTrue(withContext(Dispatchers.IO) { atCommit.await(5, TimeUnit.SECONDS) })
                        val cleanup = async(Dispatchers.IO) { repo.claimCleanup() }
                        try {
                            withTimeout(5_000) {
                                while (f.count(
                                        """
                                                                                SELECT COUNT(*) FROM pg_stat_activity
                                        WHERE wait_event_type='Lock' AND query LIKE '%staff_profile_photos%'
                                        """.trimIndent(),
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
                val snapshot = repo.snapshot(f.venue, f.display, 101)
                assertEquals(reservation.photo.id, snapshot.candidate?.id)
                assertEquals("PENDING", snapshot.candidate?.state)
                assertTrue(f.storage.exists(reservation.photo.key))
            }
        }
}
