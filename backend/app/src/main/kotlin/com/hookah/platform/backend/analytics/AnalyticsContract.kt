package com.hookah.platform.backend.analytics

import java.sql.Connection
import java.time.Instant

/** Supplied by a trusted server entry point, never inferred from order_batches.source. */
enum class AnalyticsChannel { UNKNOWN, MINIAPP, TELEGRAM }

/** A read-time alias of one physical row, not an additional event. */
fun canonicalAnalyticsEventType(physicalType: String): String =
    when (physicalType) {
        "batch_created" -> "order_batch_created"
        "batch_status_changed" -> "order_batch_status_changed"
        else -> physicalType
    }

fun analyticsContractV2(
    physicalType: String,
    occurredAt: Instant,
    producer: String,
    channel: AnalyticsChannel,
): Map<String, String> =
    mapOf(
        "contractVersion" to "2",
        "canonicalEventType" to canonicalAnalyticsEventType(physicalType),
        "occurredAt" to occurredAt.toString(),
        "producer" to producer,
        "channel" to channel.name,
    )

internal fun batchCreationContract(
    connection: Connection,
    batchId: Long,
    channel: AnalyticsChannel,
): Map<String, String> =
    connection.prepareStatement("SELECT created_at FROM order_batches WHERE id = ?").use {
        it.setLong(1, batchId)
        it.executeQuery().use { rs ->
            check(rs.next())
            analyticsContractV2("batch_created", rs.getTimestamp(1).toInstant(), "OrdersRepository", channel)
        }
    }
