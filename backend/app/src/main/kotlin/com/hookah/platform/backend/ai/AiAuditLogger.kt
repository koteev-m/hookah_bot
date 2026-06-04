package com.hookah.platform.backend.ai

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AiAssistantRole {
    OWNER,
    MANAGER,
    STAFF,
    PLATFORM_OWNER,
    GUEST,
}

data class AiAssistantPrincipal(
    val userId: Long,
    val role: AiAssistantRole,
)

data class AiAuditEvent(
    val principal: AiAssistantPrincipal,
    val venueId: Long?,
    val entityId: Long?,
    val toolName: String,
    val promptVersion: String,
    val success: Boolean,
    val failureCode: String? = null,
)

interface AiAuditLogger {
    suspend fun log(event: AiAuditEvent)
}

object NoopAiAuditLogger : AiAuditLogger {
    override suspend fun log(event: AiAuditEvent) = Unit
}

class AuditLogAiAuditLogger(
    private val auditLogRepository: AuditLogRepository,
) : AiAuditLogger {
    override suspend fun log(event: AiAuditEvent) {
        try {
            auditLogRepository.appendJson(
                actorUserId = event.principal.userId,
                action = "AI_TOOL_CALL",
                entityType = "AI_ASSISTANT",
                entityId = event.entityId,
                payload =
                    buildJsonObject {
                        put("role", event.principal.role.name)
                        event.venueId?.let { put("venueId", it) }
                        put("toolName", event.toolName)
                        put("promptVersion", event.promptVersion)
                        put("success", event.success)
                        event.failureCode?.let { put("failureCode", it) }
                    },
            )
        } catch (_: DatabaseUnavailableException) {
            // Assistant diagnostics must not fail only because metadata audit is temporarily unavailable.
        }
    }
}
