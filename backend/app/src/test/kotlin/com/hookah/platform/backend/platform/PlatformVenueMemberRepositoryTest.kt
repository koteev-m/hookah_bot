package com.hookah.platform.backend.platform

import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PlatformVenueMemberRepositoryTest {
    @Test
    fun `assignOwner returns database error when connection fails`() = runBlocking {
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } throws SQLException("connection down")

        val repository = PlatformVenueMemberRepository(dataSource)

        val result = repository.assignOwner(1L, 2L, "owner", null)

        assertEquals(PlatformOwnerAssignmentResult.DatabaseError, result)
    }
}
