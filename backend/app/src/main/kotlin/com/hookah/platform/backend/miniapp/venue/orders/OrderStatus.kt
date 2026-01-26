package com.hookah.platform.backend.miniapp.venue.orders

import java.util.Locale

enum class OrderWorkflowStatus {
    NEW,
    ACCEPTED,
    COOKING,
    DELIVERING,
    DELIVERED,
    CLOSED;

    fun toApi(): String = name.lowercase(Locale.ROOT)

    companion object {
        fun fromApi(raw: String?): OrderWorkflowStatus? {
            if (raw.isNullOrBlank()) {
                return null
            }
            return when (raw.trim().uppercase(Locale.ROOT)) {
                "NEW" -> NEW
                "ACCEPTED" -> ACCEPTED
                "COOKING" -> COOKING
                "DELIVERING" -> DELIVERING
                "DELIVERED" -> DELIVERED
                "CLOSED" -> CLOSED
                else -> null
            }
        }
    }
}

enum class OrderBatchStatus(
    val dbValue: String
) {
    NEW("NEW"),
    ACCEPTED("ACCEPTED"),
    PREPARING("PREPARING"),
    DELIVERING("DELIVERING"),
    DELIVERED("DELIVERED"),
    REJECTED("REJECTED");

    fun toWorkflow(): OrderWorkflowStatus = when (this) {
        NEW -> OrderWorkflowStatus.NEW
        ACCEPTED -> OrderWorkflowStatus.ACCEPTED
        PREPARING -> OrderWorkflowStatus.COOKING
        DELIVERING -> OrderWorkflowStatus.DELIVERING
        DELIVERED -> OrderWorkflowStatus.DELIVERED
        REJECTED -> OrderWorkflowStatus.CLOSED
    }

    companion object {
        fun fromDb(raw: String?): OrderBatchStatus? {
            if (raw.isNullOrBlank()) {
                return null
            }
            return values().firstOrNull { it.dbValue.equals(raw.trim(), ignoreCase = true) }
        }

        fun fromWorkflow(status: OrderWorkflowStatus): OrderBatchStatus? {
            return when (status) {
                OrderWorkflowStatus.NEW -> NEW
                OrderWorkflowStatus.ACCEPTED -> ACCEPTED
                OrderWorkflowStatus.COOKING -> PREPARING
                OrderWorkflowStatus.DELIVERING -> DELIVERING
                OrderWorkflowStatus.DELIVERED -> DELIVERED
                OrderWorkflowStatus.CLOSED -> null
            }
        }
    }
}

fun allowedNextStatuses(current: OrderWorkflowStatus): Set<OrderWorkflowStatus> {
    return when (current) {
        OrderWorkflowStatus.NEW -> setOf(OrderWorkflowStatus.ACCEPTED)
        OrderWorkflowStatus.ACCEPTED -> setOf(OrderWorkflowStatus.COOKING)
        OrderWorkflowStatus.COOKING -> setOf(OrderWorkflowStatus.DELIVERING)
        OrderWorkflowStatus.DELIVERING -> setOf(OrderWorkflowStatus.DELIVERED)
        OrderWorkflowStatus.DELIVERED -> setOf(OrderWorkflowStatus.CLOSED)
        OrderWorkflowStatus.CLOSED -> emptySet()
    }
}
