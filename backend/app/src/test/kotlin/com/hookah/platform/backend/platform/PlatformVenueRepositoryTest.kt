package com.hookah.platform.backend.platform

import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PlatformVenueRepositoryTest {
    @Test
    fun `updateStatus returns database error when connection fails`() = runBlocking {
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } throws SQLException("connection down")

        val repository = PlatformVenueRepository(dataSource)

        val result = repository.updateStatus(1L, VenueStatusAction.PUBLISH)

        assertEquals(VenueStatusChangeResult.DatabaseError, result)
    }
}
