package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.telegram.db.StaffCallStatus
import com.hookah.platform.backend.telegram.db.StaffCallStatusUpdateResult
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.Logger

const val STAFF_CALL_ACK_AUDIT_ACTION = "STAFF_CALL_ACK"
const val STAFF_CALL_DONE_AUDIT_ACTION = "STAFF_CALL_DONE"
const val STAFF_CALL_AUDIT_SOURCE_VENUE_MINIAPP = "venue_miniapp"
const val STAFF_CALL_AUDIT_SOURCE_TELEGRAM_STAFF_CHAT = "telegram_staff_chat"

suspend fun appendStaffCallStatusAuditBestEffort(
    auditLogRepository: AuditLogRepository,
    actorUserId: Long,
    venueId: Long,
    source: String,
    fromStatus: StaffCallStatus,
    result: StaffCallStatusUpdateResult,
    logger: Logger,
) {
    if (!result.applied) {
        return
    }
    val action =
        when (result.status) {
            StaffCallStatus.ACK -> STAFF_CALL_ACK_AUDIT_ACTION
            StaffCallStatus.DONE -> STAFF_CALL_DONE_AUDIT_ACTION
            StaffCallStatus.NEW, StaffCallStatus.CANCELLED -> return
        }
    runCatching {
        auditLogRepository.appendJson(
            actorUserId = actorUserId,
            action = action,
            entityType = "venue",
            entityId = venueId,
            payload =
                buildJsonObject {
                    put("venueId", venueId)
                    put("staffCallId", result.staffCallId)
                    put("fromStatus", fromStatus.dbValue)
                    put("toStatus", result.status.dbValue)
                    put("source", source)
                    put("tableNumber", result.tableNumber)
                },
        )
    }.onFailure { error ->
        logger.warn(
            "Failed to append staff call audit venueId={} staffCallId={} action={} actorUserId={} source={}: {}",
            venueId,
            result.staffCallId,
            action,
            actorUserId,
            source,
            sanitizeTelegramForLog(error.message),
        )
    }
}
