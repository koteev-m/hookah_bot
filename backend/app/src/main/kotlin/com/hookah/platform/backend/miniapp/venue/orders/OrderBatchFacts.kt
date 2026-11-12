package com.hookah.platform.backend.miniapp.venue.orders

import com.hookah.platform.backend.analytics.AnalyticsEventRecord
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.analytics.analyticsContractV2
import com.hookah.platform.backend.analytics.analyticsCorrelationPayload
import java.sql.Connection
import java.time.OffsetDateTime

/** Called only for an actual transition while the venue/order/batch locks are held. */
internal fun recordOrderBatchTransition(
    connection: Connection,
    analytics: AnalyticsEventRepository?,
    venueId: Long,
    orderId: Long,
    batchId: Long,
    from: OrderBatchStatus,
    to: OrderBatchStatus,
    occurredAt: OffsetDateTime,
    actor: OrderActionActor,
    legacyFrom: OrderWorkflowStatus,
    legacyTo: OrderWorkflowStatus,
) {
    check(!connection.autoCommit)
    if (from == to) return
    connection.prepareStatement(
        """
        UPDATE order_batches SET transition_sequence = transition_sequence + 1,
            first_accepted_at = CASE WHEN ? = 'ACCEPTED' THEN COALESCE(first_accepted_at, ?) ELSE first_accepted_at END,
            first_delivered_at = CASE WHEN ? = 'DELIVERED' THEN COALESCE(first_delivered_at, ?) ELSE first_delivered_at END
        WHERE id = ? AND order_id = ? AND status = ?
        """.trimIndent(),
    ).use {
        it.setString(1, to.dbValue)
        it.setObject(2, occurredAt)
        it.setString(3, to.dbValue)
        it.setObject(4, occurredAt)
        it.setLong(5, batchId)
        it.setLong(6, orderId)
        it.setString(7, to.dbValue)
        check(it.executeUpdate() == 1)
    }
    val sequence =
        connection.prepareStatement("SELECT transition_sequence FROM order_batches WHERE id = ?").use {
            it.setLong(1, batchId)
            it.executeQuery().use { rs ->
                check(rs.next())
                rs.getLong(1)
            }
        }
    if (to == OrderBatchStatus.ACCEPTED) {
        connection.prepareStatement(
            "UPDATE orders SET first_accepted_at = COALESCE(first_accepted_at, ?) WHERE id = ? AND venue_id = ?",
        ).use {
            it.setObject(1, occurredAt)
            it.setLong(2, orderId)
            it.setLong(3, venueId)
            check(it.executeUpdate() == 1)
        }
    }
    val producer = "VenueOrdersRepository"
    connection.prepareStatement(
        """
        INSERT INTO order_batch_status_facts
            (batch_id, transition_sequence, order_id, venue_id, from_status, to_status, occurred_at, producer, channel)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use {
        it.setLong(1, batchId)
        it.setLong(2, sequence)
        it.setLong(3, orderId)
        it.setLong(4, venueId)
        it.setString(5, from.dbValue)
        it.setString(6, to.dbValue)
        it.setObject(7, occurredAt)
        it.setString(8, producer)
        it.setString(9, actor.channel.name)
        it.executeUpdate()
    }
    analytics?.append(
        connection,
        AnalyticsEventRecord(
            eventType = "batch_status_changed",
            payload =
                analyticsCorrelationPayload(
                    venueId = venueId,
                    orderId = orderId,
                    batchId = batchId,
                    extra =
                        analyticsContractV2("batch_status_changed", occurredAt.toInstant(), producer, actor.channel) +
                            mapOf(
                                "fromStatus" to legacyFrom.toApi(),
                                "toStatus" to legacyTo.toApi(),
                                "batchFromStatus" to from.dbValue,
                                "batchToStatus" to to.dbValue,
                                "transitionSequence" to sequence.toString(),
                            ),
                ),
            venueId = venueId,
            orderId = orderId,
            batchId = batchId,
            idempotencyKey = "batch_status_changed:v2:$venueId:$orderId:$batchId:$sequence",
        ),
    )
}
