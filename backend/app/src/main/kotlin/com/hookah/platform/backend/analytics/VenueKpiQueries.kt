package com.hookah.platform.backend.analytics

internal fun KpiCollector.venueOperations(
    venueId: Long,
    request: KpiRequest,
) {
    val w = window
    val table = request.tableId
    val tableClause = if (table == null) "" else " AND o.table_id = ?"
    val scopeArgs = listOfNotNull(venueId, table)
    val scope = "o.venue_id = ?$tableClause"
    val rangeArgs = scopeArgs + listOf(w.from, w.observedTo)
    val batches = "order_batches b JOIN orders o ON o.id = b.order_id"
    if (request.section in setOf("all", "orders")) {
        val orderCohort = "$scope AND o.created_at >= ? AND o.created_at < ?"
        val batchCohort = "$scope AND b.created_at >= ? AND b.created_at < ?"
        count("orders.created", "orders o", orderCohort, rangeArgs, "ORDER_CREATED_AT")
        count("batches.created", batches, batchCohort, rangeArgs, "BATCH_CREATED_AT")
        val unknownOrders =
            c.count(
                "SELECT COUNT(*) FROM orders o WHERE $scope AND o.created_at < ? AND NOT o.acceptance_history_complete",
                scopeArgs + w.observedTo,
            )
        count(
            "orders.firstAccepted",
            "orders o",
            "$scope AND o.first_accepted_at >= ? AND o.first_accepted_at < ? AND o.acceptance_history_complete",
            rangeArgs,
            "FIRST_EVER_ACCEPTED_AT",
            unknownOrders,
        )
        val unknownBatches =
            c.count(
                "SELECT COUNT(*) FROM $batches WHERE $scope AND b.created_at < ? AND NOT b.status_history_complete",
                scopeArgs + w.observedTo,
            )
        for (status in listOf("ACCEPTED", "DELIVERED", "REJECTED")) {
            val where =
                "$scope AND EXISTS (SELECT 1 FROM order_batch_status_facts f " +
                    "WHERE f.batch_id = b.id AND f.order_id = o.id AND f.venue_id = o.venue_id " +
                    "AND f.to_status = ? AND f.occurred_at >= ? AND f.occurred_at < ?)"
            count(
                "batches.${status.lowercase()}",
                batches,
                where,
                scopeArgs + listOf(status, w.from, w.observedTo),
                "DISTINCT_BATCH_TRANSITION_AT",
                unknownBatches,
            )
        }
        val cohortSize = c.count("SELECT COUNT(*) FROM $batches WHERE $batchCohort", rangeArgs)
        val cohortUnknown =
            c.count(
                "SELECT COUNT(*) FROM $batches WHERE $batchCohort AND NOT b.status_history_complete",
                rangeArgs,
            )
        for ((name, field) in listOf("accepted" to "first_accepted_at", "delivered" to "first_delivered_at")) {
            val known =
                c.count(
                    "SELECT COUNT(*) FROM $batches WHERE $batchCohort AND b.$field < ?",
                    rangeArgs + w.asOf,
                )
            metrics["batches.cohort${name.replaceFirstChar { it.uppercase() }}Rate"] =
                kpi(ratio(known, cohortSize), "BATCH_CREATED_AT_SNAPSHOT_AS_OF", cohortSize, cohortUnknown, "RATIO")
            duration(
                "batches.${name}Delay",
                batches,
                "b.created_at",
                "b.$field",
                "$batchCohort AND b.$field < ? AND b.status_history_complete",
                rangeArgs + w.asOf,
                "BATCH_CREATED_AT_SNAPSHOT_AS_OF",
                cohortUnknown,
            )
        }
        count(
            "orders.cancelledSnapshot",
            "orders o",
            "$orderCohort AND o.status = 'CANCELLED'",
            rangeArgs,
            "ORDER_CREATED_AT_CURRENT_STATE",
        )
        count(
            "items.excludedSnapshot",
            "order_batch_items i JOIN order_batches b ON b.id = i.order_batch_id JOIN orders o ON o.id = b.order_id",
            "$batchCohort AND (i.is_excluded = TRUE OR i.item_status = 'CANCELED')",
            rangeArgs,
            "BATCH_CREATED_AT_CURRENT_ITEM_STATE",
        )
        val delivery =
            "$scope AND b.first_delivered_at >= ? AND b.first_delivered_at < ? " +
                "AND i.is_excluded = FALSE AND COALESCE(i.item_status, 'ACTIVE') = 'ACTIVE'"
        val items =
            "order_batch_items i JOIN order_batches b ON b.id = " +
                "i.order_batch_id JOIN orders o ON o.id = b.order_id"
        // A delivered free-text batch has no structured item source; an empty top is not evidence of zero items.
        val incompleteItems =
            "(NOT EXISTS (SELECT 1 FROM order_batch_items i WHERE i.order_batch_id = b.id) OR " +
                "EXISTS (SELECT 1 FROM order_batch_items i WHERE i.order_batch_id = b.id AND i.is_excluded = FALSE " +
                "AND COALESCE(i.item_status, 'ACTIVE') = 'ACTIVE' AND i.item_name_snapshot IS NULL))"
        val deliveredBatches = "$scope AND b.first_delivered_at >= ? AND b.first_delivered_at < ?"
        val missing =
            c.count(
                "SELECT COUNT(*) FROM $batches WHERE $deliveredBatches " +
                    "AND b.status_history_complete AND $incompleteItems",
                rangeArgs,
            )
        val covered =
            c.count(
                "SELECT COUNT(*) FROM $batches WHERE $deliveredBatches " +
                    "AND NOT $incompleteItems",
                rangeArgs,
            )
        metrics["items.deliveredSnapshotCoverage"] =
            kpi(
                covered, "FIRST_OBSERVED_DELIVERY_CURRENT_ITEM_STATE",
                samples = covered, unknown = missing + unknownBatches,
                reason = if (missing + unknownBatches > 0) "ITEM_OR_HISTORY_COVERAGE_INCOMPLETE" else null,
            )
        breakdowns["items.deliveredTop"] =
            c.read(
                "SELECT i.item_name_snapshot, SUM(CAST(i.qty AS BIGINT)) AS quantity FROM $items WHERE $delivery " +
                    "AND i.item_name_snapshot IS NOT NULL GROUP BY i.item_name_snapshot ORDER " +
                    "BY quantity DESC, i.item_name_snapshot LIMIT 20",
                rangeArgs,
            ) { KpiBucket(it.getString(1), it.getLong(2)) }
    }
    if (request.section in setOf("all", "bookings")) {
        val filter = "venue_id = ?" + if (table == null) "" else " AND table_id = ?"
        val created = "$filter AND created_at >= ? AND created_at < ?"
        val scheduled = "$filter AND scheduled_at >= ? AND scheduled_at < ? AND created_at < ?"
        val scheduledArgs = scopeArgs + listOf(w.from, w.to, w.asOf)
        count("bookings.created", "bookings", created, rangeArgs, "BOOKING_CREATED_AT")
        states("bookings.createdStatuses", "bookings", "status", created, rangeArgs)
        count("bookings.scheduled", "bookings", scheduled, scheduledArgs, "CURRENT_SCHEDULED_AT")
        states("bookings.scheduledStatuses", "bookings", "status", scheduled, scheduledArgs)
        for (status in listOf("SEATED", "NO_SHOW", "CANCELED", "EXPIRED")) {
            count(
                "bookings.${status.lowercase()}",
                "bookings",
                "$scheduled AND status = ?",
                scheduledArgs + status,
                "CURRENT_SCHEDULED_AT",
            )
        }
        count(
            "bookings.future",
            "bookings",
            "$scheduled AND scheduled_at >= ? AND status NOT IN ('SEATED','NO_SHOW','CANCELED','EXPIRED')",
            scheduledArgs + w.asOf,
            "CURRENT_SCHEDULED_AT",
        )
        count(
            "bookings.unresolved",
            "bookings",
            "$scheduled AND scheduled_at < ? AND status NOT IN ('SEATED','NO_SHOW','CANCELED','EXPIRED')",
            scheduledArgs + w.asOf,
            "CURRENT_SCHEDULED_AT",
        )
        val seated = metrics.getValue("bookings.seated").value!!.toLong()
        val noShow = metrics.getValue("bookings.no_show").value!!.toLong()
        metrics["bookings.attendance"] =
            kpi(
                ratio(
                    seated,
                    seated + noShow,
                ),
                "CURRENT_SCHEDULED_AT",
                seated + noShow,
                unit = "RATIO",
            )
    }
    if (request.section in setOf("all", "seatings")) {
        val where =
            "s.venue_id = ?" + (if (table == null) "" else " AND s.table_id = ?") +
                " AND s.started_at >= ? AND s.started_at < ?"
        val n =
            c.count(
                "SELECT COUNT(DISTINCT s.table_session_id) FROM table_seatings s " +
                    "JOIN table_sessions t ON t.id = s.table_session_id AND t.venue_id = s.venue_id WHERE $where",
                rangeArgs,
            )
        metrics["seatings.registeredSessions"] = kpi(n, "REGISTERED_SEATING_STARTED_AT_CURRENT_TABLE", n)
    }
    if (request.section in setOf("all", "calls")) {
        val filter = "venue_id = ?" + if (table == null) "" else " AND table_id = ?"
        val cohort = "$filter AND created_at >= ? AND created_at < ?"
        val unknown =
            c.count(
                "SELECT COUNT(*) FROM staff_calls WHERE $filter AND created_at < ? AND NOT status_history_complete",
                scopeArgs + w.observedTo,
            )
        val cohortUnknown =
            c.count(
                "SELECT COUNT(*) FROM staff_calls WHERE $cohort AND NOT status_history_complete",
                rangeArgs,
            )
        count("calls.created", "staff_calls", cohort, rangeArgs, "CALL_CREATED_AT")
        for ((name, condition) in listOf(
            "acknowledged" to "acknowledged_at >= ? AND acknowledged_at < ?",
            "operatorCompleted" to "completed_at >= ? AND completed_at < ? AND completion_kind = 'OPERATOR'",
            "autoCompleted" to "completed_at >= ? AND completed_at < ? AND completion_kind = 'ORDER_CLOSED'",
            "cancelled" to "cancelled_at >= ? AND cancelled_at < ?",
        )) count("calls.$name", "staff_calls", "$filter AND $condition", rangeArgs, "CALL_TRANSITION_AT", unknown)
        duration(
            "calls.firstResponse",
            "staff_calls",
            "created_at",
            "acknowledged_at",
            "$cohort AND acknowledged_at < ? AND status_history_complete",
            rangeArgs + w.asOf,
            "CALL_CREATED_AT_SNAPSHOT_AS_OF",
            cohortUnknown,
        )
        metrics["calls.legacyUnknown"] = kpi(cohortUnknown, "CALL_CREATED_AT_SNAPSHOT_AS_OF")
        states("calls.createdStatuses", "staff_calls", "status", cohort, rangeArgs)
    }
}
