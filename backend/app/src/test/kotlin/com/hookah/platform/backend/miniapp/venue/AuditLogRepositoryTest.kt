package com.hookah.platform.backend.miniapp.venue

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject

class AuditLogRepositoryTest {
    @Test
    fun `record uses jsonb binding for postgres`() = runBlocking {
        val dataSource = mockk<DataSource>()
        val connection = mockk<Connection>(relaxed = true)
        val metaData = mockk<DatabaseMetaData>()
        val statement = mockk<PreparedStatement>(relaxed = true)
        val payload = buildJsonObject { put("key", "value") }
        val payloadJson = Json.encodeToString(JsonObject.serializer(), payload)

        every { dataSource.connection } returns connection
        every { connection.metaData } returns metaData
        every { metaData.databaseProductName } returns "PostgreSQL"
        every { connection.prepareStatement(any<String>()) } returns statement
        every { statement.executeUpdate() } returns 1

        val repository = AuditLogRepository(dataSource, Json)

        repository.record(venueId = 1, actorUserId = 2, action = "ACTION", payload = payload)

        verify(exactly = 1) {
            statement.setObject(
                4,
                match {
                    it is PGobject && it.type == "jsonb" && it.value == payloadJson
                }
            )
        }
    }
}
