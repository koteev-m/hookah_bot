package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.postgresql.util.PGobject

class AuditLogRepository(private val dataSource: DataSource?, private val json: Json = Json) {
    suspend fun record(venueId: Long, actorUserId: Long, action: String, payload: JsonObject) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val payloadJson = json.encodeToString(JsonObject.serializer(), payload)
                    connection.prepareStatement(
                        """
                            INSERT INTO audit_log (actor_user_id, venue_id, action, payload)
                            VALUES (?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, actorUserId)
                        statement.setLong(2, venueId)
                        statement.setString(3, action)
                        if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
                            val jsonb = PGobject().apply {
                                type = "jsonb"
                                value = payloadJson
                            }
                            statement.setObject(4, jsonb)
                        } else {
                            statement.setString(4, payloadJson)
                        }
                        statement.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }
}
