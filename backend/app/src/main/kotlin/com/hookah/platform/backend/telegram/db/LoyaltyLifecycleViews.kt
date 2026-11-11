package com.hookah.platform.backend.telegram.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.sql.Connection
import java.time.Instant

@Serializable
data class LoyaltyCycleSummary(
    val nthValue: Int,
    val progressCount: Int,
    val earnTargets: List<String>,
    val origin: String,
    val rewardTargets: List<String> = emptyList(),
)

@Serializable
data class LoyaltyRewardCondition(
    val nthValue: Int,
    val count: Int,
    val rewardTargets: List<String>,
    val origin: String,
)

@Serializable
data class OrderLoyaltyReward(
    val id: Long,
    val batchItemId: Long,
    val status: String,
    val origin: String,
    val correctionAllowed: Boolean,
    val venueFunded: Boolean = false,
)

@Serializable
data class OrderLoyaltyHistoryEntry(
    val id: String,
    val action: String,
    val result: String,
    val actorUserId: Long,
    val occurredAt: String,
    val reason: String?,
    val redemptionId: Long,
    val batchItemId: Long,
    val itemName: String,
    val compensationCancellation: Boolean,
)

@Serializable
data class OrderLoyaltyExpense(
    val amountMinor: Long,
    val reason: String,
    val resolved: Boolean,
    val id: Long = 0,
    val recordedAt: String? = null,
    val actorUserId: Long? = null,
    val issuedAt: String? = null,
    val correctionId: Long? = null,
)

@Serializable
data class OrderLoyaltyUnit(
    val id: Long,
    val batchId: Long,
    val batchItemId: Long,
    val unitIndex: Int,
    val qty: Int,
    val name: String,
    val priceMinor: Long?,
    val guestUserId: Long,
    val paymentFact: String,
    val earnedAt: String?,
    val canCorrect: Boolean,
    val correctedAt: String?,
    val correctedBy: Long?,
    val correctedByLabel: String?,
    val correctionReason: String?,
)

internal fun Connection.loyaltyOrderRewards(
    venue: Long,
    order: Long,
): List<OrderLoyaltyReward> =
    lq(
        "SELECT r.id, r.order_batch_item_id, " +
            "r.status, r.origin, r.funding_basis FROM " +
            "loyalty_redemptions r WHERE r.venue_id = ? " +
            "AND r.order_id = ? ORDER BY r.id",
        venue,
        order,
    ) { r ->
        OrderLoyaltyReward(
            r.getLong(1),
            r.getLong(2),
            r.getString(3),
            r.getString(4),
            r.getString(3) == "APPLIED" && r.getString(4) == "PAYMENT_POLICY",
            r.getString(5) == "VENUE_COMPENSATION",
        )
    }

internal fun Connection.loyaltyOrderUnits(
    venue: Long,
    order: Long,
): List<OrderLoyaltyUnit> =
    lq(
        "SELECT u.id, u.batch_id, u.order_batch_item_id, u.unit_index, i.qty, i.item_name_snapshot, " +
            "i.base_unit_price_minor_snapshot + (SELECT " +
            "COALESCE(SUM(opt.price_delta_minor_snapshot)," +
            "0) FROM order_batch_item_options opt WHERE " +
            "opt.order_batch_item_id = i.id), " +
            "cy.user_id, COALESCE(x.payment_fact_snapshot,u.payment_fact), " +
            "(SELECT MIN(l.created_at) FROM " +
            "guest_loyalty_ledger l WHERE l.paid_unit_id " +
            "= u.id AND l.event_type = 'EARN'), " +
            "u.active, x.id, x.created_at, x.actor_user_id, x.actor_label_snapshot, x.reason " +
            "FROM loyalty_paid_units u JOIN orders o ON " +
            "o.id = u.order_id JOIN " +
            "loyalty_accumulation_cycles cy ON cy.id = " +
            "u.cycle_id " +
            "JOIN order_batch_items i ON i.id = " +
            "u.order_batch_item_id LEFT JOIN " +
            "loyalty_unit_corrections x ON x.paid_unit_id " +
            "= u.id " +
            "WHERE o.id = ? AND o.venue_id = ? AND cy.venue_id = ? ORDER BY u.order_batch_item_id,u.unit_index",
        order,
        venue,
        venue,
    ) { r ->
        val price = r.getObject(7)?.let { r.getLong(7) }
        OrderLoyaltyUnit(
            r.getLong(1), r.getLong(2), r.getLong(3), r.getInt(4), r.getInt(5), r.getString(6) ?: "Кальян",
            price, r.getLong(8), r.getString(9), r.getTimestamp(10)?.toInstant()?.toString(),
            r.getBoolean(11) && r.getObject(12) == null && price != null && price > 0 && r.getInt(4) in 1..r.getInt(5),
            r.getTimestamp(
                13,
            )?.toInstant()?.toString(),
            r.getObject(14)?.let { r.getLong(14) }, r.getString(15), r.getString(16),
        )
    }

internal fun Connection.loyaltyOrderExpenses(
    venue: Long,
    order: Long,
): List<OrderLoyaltyExpense> =
    lq(
        "SELECT e.amount_minor, e.reason, " +
            "e.resolved_at, e.id, e.created_at, " +
            "e.recorded_by_user_id, e.issued_at_snapshot, " +
            "e.correction_id FROM " +
            "loyalty_correction_expenses e " +
            "JOIN loyalty_redemptions r ON r.id = e.redemption_id " +
            "WHERE e.venue_id = ? AND (e.order_id = ? OR r.order_id = ?) ORDER BY e.created_at,e.id",
        venue,
        order,
        order,
    ) {
        OrderLoyaltyExpense(
            it.getLong(1),
            it.getString(2),
            it.getObject(3) != null,
            it.getLong(4),
            it.getTimestamp(5)?.toInstant()?.toString(),
            it.getObject(6)?.let {
                    _ ->
                it.getLong(6)
            },
            it.getTimestamp(7)?.toInstant()?.toString(),
            it.getObject(8)?.let {
                    _ ->
                it.getLong(8)
            },
        )
    }

internal fun Connection.loyaltyOrderHistory(
    venue: Long,
    order: Long,
): List<OrderLoyaltyHistoryEntry> {
    // Issue/correction audit survives later changes; current issued_at is not operation history.
    val deliveries =
        lq(
            """
            SELECT a.id, a.actor_user_id, a.created_at, a.payload_json,
                   r.id, r.order_batch_item_id, i.item_name_snapshot, cc.redemption_id, a.action
            FROM audit_log a
            JOIN loyalty_redemptions r ON r.id = a.entity_id
            JOIN orders o ON o.id = r.order_id AND o.venue_id = r.venue_id
            JOIN order_batches b ON b.id = r.batch_id AND b.order_id = r.order_id
            JOIN order_batch_items i ON i.id = r.order_batch_item_id AND i.order_batch_id = b.id
            LEFT JOIN loyalty_compensation_cancellations cc ON cc.redemption_id = r.id AND cc.venue_id = r.venue_id
            WHERE a.entity_type = 'loyalty'
              AND a.action IN ('LOYALTY_REWARD_APPLIED', 'LOYALTY_DELIVERY_CORRECTED')
              AND r.venue_id = ? AND r.order_id = ? AND r.origin = 'PAYMENT_POLICY'
            """.trimIndent(),
            venue,
            order,
        ) { r ->
            val payload = Json.parseToJsonElement(r.getString(4)).jsonObject
            val reason = payload["reason"]?.jsonPrimitive?.contentOrNull
            val confirmed = r.getString(9) == "LOYALTY_REWARD_APPLIED"
            if (payload["venueId"]?.jsonPrimitive?.longOrNull != venue || (!confirmed && reason == null)) {
                null
            } else {
                val prefix = if (confirmed) "delivery-confirmation" else "delivery-correction"
                OrderLoyaltyHistoryEntry(
                    "$prefix:${r.getLong(1)}",
                    if (confirmed) "DELIVERY_CONFIRMED" else "DELIVERY_CORRECTED",
                    if (confirmed) "REWARD_APPLIED" else "DELIVERY_MARK_CORRECTED",
                    r.getLong(2), r.getTimestamp(3).toInstant().toString(), reason,
                    r.getLong(5), r.getLong(6), r.getString(7) ?: "Кальян", r.getObject(8) != null,
                )
            }
        }.filterNotNull()
    val cancellations =
        lq(
            """
            SELECT cc.redemption_id, cc.actor_user_id, cc.created_at, cc.reason,
                   r.order_batch_item_id, i.item_name_snapshot
            FROM loyalty_compensation_cancellations cc
            JOIN loyalty_redemptions r ON r.id = cc.redemption_id AND r.venue_id = cc.venue_id
            JOIN orders o ON o.id = r.order_id AND o.venue_id = r.venue_id
            JOIN order_batches b ON b.id = r.batch_id AND b.order_id = r.order_id
            JOIN order_batch_items i ON i.id = r.order_batch_item_id AND i.order_batch_id = b.id
            WHERE cc.venue_id = ? AND r.order_id = ?
            """.trimIndent(),
            venue,
            order,
        ) { r ->
            OrderLoyaltyHistoryEntry(
                "compensation-cancellation:${r.getLong(1)}", "COMPENSATION_CANCELED", "UNISSUED_COMPENSATION_CANCELED",
                r.getLong(2), r.getTimestamp(3).toInstant().toString(), r.getString(4),
                r.getLong(1), r.getLong(5), r.getString(6) ?: "Кальян", true,
            )
        }
    return (deliveries + cancellations).sortedWith(
        compareBy<OrderLoyaltyHistoryEntry> {
            Instant.parse(it.occurredAt)
        }.thenBy { it.id },
    )
}
