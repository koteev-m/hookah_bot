package com.hookah.platform.backend.telegram

data class TableContext(
    val venueId: Long,
    val venueName: String,
    val tableId: Long,
    val tableNumber: Int,
    val tableToken: String,
    val staffChatId: Long?,
)

enum class StaffCallReason {
    COALS,
    BILL,
    COME,
    OTHER,
}

enum class DialogStateType {
    NONE,
    QUICK_ORDER_WAIT_TEXT,
    QUICK_ORDER_WAIT_CONFIRM,
    STAFF_CALL_WAIT_COMMENT,
}

data class DialogState(
    val state: DialogStateType,
    val payload: Map<String, String> = emptyMap(),
)

data class ResolvedChatContext(
    val table: TableContext,
    val userId: Long,
)

data class ActiveOrderSummary(
    val id: Long,
    val status: String,
)
