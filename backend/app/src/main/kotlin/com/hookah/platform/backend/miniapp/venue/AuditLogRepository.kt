package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.sql.Types
import javax.sql.DataSource

class AuditLogRepository(private val dataSource: DataSource?, private val json: Json = Json) {
    private val logger = LoggerFactory.getLogger(AuditLogRepository::class.java)

    suspend fun append(
        actorUserId: Long,
        action: String,
        entityType: String,
        entityId: Long?,
        payloadJson: String,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val payloadSize = payloadJson.length
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO audit_log (actor_user_id, action, entity_type, entity_id, payload_json)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, actorUserId)
                        statement.setString(2, action)
                        statement.setString(3, entityType)
                        if (entityId == null) {
                            statement.setNull(4, Types.BIGINT)
                        } else {
                            statement.setLong(4, entityId)
                        }
                        statement.setString(5, payloadJson)
                        statement.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                logger.warn(
                    "Failed to append audit log actorUserId={} action={} entityType={} entityId={} payloadBytes={}: {}",
                    actorUserId,
                    action,
                    entityType,
                    entityId,
                    payloadSize,
                    sanitizeTelegramForLog(e.message),
                )
                logger.debugTelegramException(e) {
                    "appendAuditLog exception actorUserId=$actorUserId action=$action " +
                        "entityType=$entityType entityId=$entityId"
                }
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun appendJson(
        actorUserId: Long,
        action: String,
        entityType: String,
        entityId: Long?,
        payload: JsonObject,
    ) {
        val payloadJson = json.encodeToString(JsonObject.serializer(), payload)
        append(actorUserId, action, entityType, entityId, payloadJson)
    }
}
