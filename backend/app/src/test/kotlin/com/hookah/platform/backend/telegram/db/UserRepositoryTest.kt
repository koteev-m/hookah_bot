package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.User
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserRepositoryTest {
    @Test
    fun `upsert does not erase guest profile fields`(): Unit =
        runBlocking {
            val repository = UserRepository(testDataSource())

            repository.upsert(User(id = 100L, username = "old", firstName = "Old", lastName = null))
            repository.saveGuestDisplayName(100L, "Алексей")
            repository.saveBirthdayOnce(100L, month = 9, day = 5)
            repository.upsert(User(id = 100L, username = "new", firstName = "New", lastName = "Name"))

            val profile = assertNotNull(repository.findGuestProfile(100L))
            assertEquals("Алексей", profile.guestDisplayName)
            assertEquals(9, profile.birthdayMonth)
            assertEquals(5, profile.birthdayDay)
            assertNotNull(profile.birthdaySetAt)
        }

    @Test
    fun `save guest display name normalizes and validates input`(): Unit =
        runBlocking {
            val repository = UserRepository(testDataSource())
            repository.upsert(User(id = 100L, username = null, firstName = null, lastName = null))

            val profile = assertNotNull(repository.saveGuestDisplayName(100L, "  Мария   Иванова  "))

            assertEquals("Мария Иванова", profile.guestDisplayName)
            assertFailsWith<IllegalArgumentException> { UserRepository.normalizeGuestDisplayName("   ") }
            assertFailsWith<IllegalArgumentException> { UserRepository.normalizeGuestDisplayName("Мария\nИванова") }
        }

    @Test
    fun `birthday can be saved only once`(): Unit =
        runBlocking {
            val repository = UserRepository(testDataSource())
            repository.upsert(User(id = 100L, username = null, firstName = null, lastName = null))

            val saved = assertNotNull(repository.saveBirthdayOnce(100L, month = 9, day = 5))
            val second = repository.saveBirthdayOnce(100L, month = 10, day = 6)
            val profile = assertNotNull(repository.findGuestProfile(100L))

            assertEquals(9, saved.birthdayMonth)
            assertNull(second)
            assertEquals(9, profile.birthdayMonth)
            assertEquals(5, profile.birthdayDay)
        }

    private fun testDataSource(): DataSource {
        val url =
            "jdbc:h2:mem:user-repository-${UUID.randomUUID()};" +
                "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        return object : DataSource {
            init {
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                            CREATE TABLE users (
                                telegram_user_id BIGINT PRIMARY KEY,
                                username CLOB NULL,
                                first_name CLOB NULL,
                                last_name CLOB NULL,
                                guest_display_name CLOB NULL,
                                birthday_month SMALLINT NULL,
                                birthday_day SMALLINT NULL,
                                birthday_set_at TIMESTAMP WITH TIME ZONE NULL,
                                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                            )
                            """.trimIndent(),
                        )
                    }
                }
            }

            override fun getConnection() = DriverManager.getConnection(url)

            override fun getConnection(
                username: String?,
                password: String?,
            ) = DriverManager.getConnection(url, username, password)

            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

            override fun isWrapperFor(iface: Class<*>?) = false

            override fun getLogWriter() = null

            override fun setLogWriter(out: java.io.PrintWriter?) = Unit

            override fun setLoginTimeout(seconds: Int) = Unit

            override fun getLoginTimeout() = 0

            override fun getParentLogger() = java.util.logging.Logger.getGlobal()
        }
    }
}
