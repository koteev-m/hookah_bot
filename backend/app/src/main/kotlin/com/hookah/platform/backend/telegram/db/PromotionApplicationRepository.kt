package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

data class PromotionAdjustmentRecord(
    val applicationId: Long,
    val orderId: Long,
    val batchId: Long?,
    val orderBatchItemId: Long,
    val menuItemId: Long,
    val ruleId: Long,
    val titleSnapshot: String,
    val discountMinor: Long,
    val discountPercent: Int,
    val originalPriceMinor: Long,
    val quantity: Int,
    val currency: String,
)

data class PromotionDiscountSummary(
    val discountMinor: Long,
    val currency: String,
)

data class PromotionApplicationInput(
    val orderId: Long,
    val batchId: Long?,
    val venueId: Long,
    val userId: Long,
    val promotionId: Long?,
    val ruleId: Long,
    val titleSnapshot: String,
    val ruleType: String,
    val targetType: String,
    val targetValue: String,
    val discountPercent: Int?,
    val discountTotalMinor: Long,
    val currency: String,
    val dedupeKey: String,
    val adjustments: List<PromotionAdjustmentInput>,
    val rewardItems: List<PromotionRewardItemInput> = emptyList(),
)

data class PromotionAdjustmentInput(
    val orderBatchItemId: Long,
    val menuItemId: Long,
    val discountMinor: Long,
    val discountPercent: Int,
    val originalPriceMinor: Long,
    val quantity: Int,
    val currency: String,
)

data class PromotionRewardItemInput(
    val triggerOrderBatchItemId: Long?,
    val rewardOrderBatchItemId: Long,
    val rewardMenuItemId: Long,
    val rewardQty: Int,
    val labelSnapshot: String,
)

class PromotionApplicationRepository(private val dataSource: DataSource?) {
    suspend fun findAdjustmentsByOrder(orderId: Long): List<PromotionAdjustmentRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> findAdjustmentsByOrder(connection, orderId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findAdjustmentsByBatch(batchId: Long): List<PromotionAdjustmentRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> findAdjustmentsByBatch(connection, batchId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findAdjustmentsByItemIds(itemIds: Set<Long>): List<PromotionAdjustmentRecord> {
        if (itemIds.isEmpty()) return emptyList()
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> findAdjustmentsByItemIds(connection, itemIds) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun summarizePromoDiscountForOrder(orderId: Long): PromotionDiscountSummary? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> summarizePromoDiscountForOrder(connection, orderId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun summarizePromoDiscountForBatch(batchId: Long): PromotionDiscountSummary? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> summarizePromoDiscountForBatch(connection, batchId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun persistApplications(
        connection: Connection,
        applications: List<PromotionApplicationInput>,
    ) {
        applications
            .filter { it.discountTotalMinor > 0L && it.adjustments.isNotEmpty() }
            .forEach { application ->
                val applicationId = findApplicationIdByDedupeKey(connection, application.dedupeKey)
                    ?: insertApplication(connection, application)
                application.adjustments
                    .filter { it.discountMinor > 0L }
                    .forEach { adjustment ->
                        if (!adjustmentExists(connection, adjustment.orderBatchItemId)) {
                            insertAdjustment(connection, applicationId, adjustment)
                        }
                    }
                application.rewardItems
                    .filter { it.rewardQty > 0 }
                    .forEach { reward ->
                        if (!rewardItemExists(connection, reward.rewardOrderBatchItemId)) {
                            insertRewardItem(connection, applicationId, reward)
                        }
                    }
            }
    }

    private fun findAdjustmentsByOrder(
        connection: Connection,
        orderId: Long,
    ): List<PromotionAdjustmentRecord> =
        selectAdjustments(connection, "opa.order_id = ?", orderId)

    private fun findAdjustmentsByBatch(
        connection: Connection,
        batchId: Long,
    ): List<PromotionAdjustmentRecord> =
        selectAdjustments(connection, "opa.batch_id = ?", batchId)

    private fun findAdjustmentsByItemIds(
        connection: Connection,
        itemIds: Set<Long>,
    ): List<PromotionAdjustmentRecord> {
        val placeholders = itemIds.joinToString(",") { "?" }
        val sql =
            """
            SELECT ${adjustmentColumns()}
            FROM order_batch_item_promotion_adjustments obipa
            JOIN order_promotion_applications opa ON opa.id = obipa.application_id
            WHERE obipa.order_batch_item_id IN ($placeholders)
            ORDER BY obipa.id
            """.trimIndent()
        return connection.prepareStatement(sql).use { statement ->
            itemIds.forEachIndexed { index, itemId -> statement.setLong(index + 1, itemId) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rsToAdjustmentRecord(rs))
                    }
                }
            }
        }
    }

    private fun selectAdjustments(
        connection: Connection,
        condition: String,
        id: Long,
    ): List<PromotionAdjustmentRecord> =
        connection.prepareStatement(
            """
            SELECT ${adjustmentColumns()}
            FROM order_batch_item_promotion_adjustments obipa
            JOIN order_promotion_applications opa ON opa.id = obipa.application_id
            WHERE $condition
            ORDER BY obipa.id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rsToAdjustmentRecord(rs))
                    }
                }
            }
        }

    private fun summarizePromoDiscountForOrder(
        connection: Connection,
        orderId: Long,
    ): PromotionDiscountSummary? =
        summarize(connection, "opa.order_id = ?", orderId)

    private fun summarizePromoDiscountForBatch(
        connection: Connection,
        batchId: Long,
    ): PromotionDiscountSummary? =
        summarize(connection, "opa.batch_id = ?", batchId)

    private fun summarize(
        connection: Connection,
        condition: String,
        id: Long,
    ): PromotionDiscountSummary? =
        connection.prepareStatement(
            """
            SELECT COALESCE(SUM(obipa.discount_minor), 0) AS discount_minor,
                   MAX(obipa.currency) AS currency
            FROM order_batch_item_promotion_adjustments obipa
            JOIN order_promotion_applications opa ON opa.id = obipa.application_id
            WHERE $condition
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val discountMinor = rs.getLong("discount_minor")
                    val currency = rs.getString("currency") ?: return null
                    if (discountMinor > 0L) PromotionDiscountSummary(discountMinor, currency) else null
                }
            }
        }

    private fun insertApplication(
        connection: Connection,
        application: PromotionApplicationInput,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_promotion_applications (
                order_id,
                batch_id,
                venue_id,
                user_id,
                promotion_id,
                rule_id,
                title_snapshot,
                rule_type,
                target_type,
                target_value,
                discount_percent,
                discount_total_minor,
                currency,
                dedupe_key
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, application.orderId)
            setNullableLong(statement, 2, application.batchId)
            statement.setLong(3, application.venueId)
            statement.setLong(4, application.userId)
            setNullableLong(statement, 5, application.promotionId)
            statement.setLong(6, application.ruleId)
            statement.setString(7, application.titleSnapshot)
            statement.setString(8, application.ruleType)
            statement.setString(9, application.targetType)
            statement.setString(10, application.targetValue)
            if (application.discountPercent == null) {
                statement.setObject(11, null)
            } else {
                statement.setInt(11, application.discountPercent)
            }
            statement.setLong(12, application.discountTotalMinor)
            statement.setString(13, application.currency)
            statement.setString(14, application.dedupeKey)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("Failed to insert promotion application")
            }
        }

    private fun insertAdjustment(
        connection: Connection,
        applicationId: Long,
        adjustment: PromotionAdjustmentInput,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_item_promotion_adjustments (
                application_id,
                order_batch_item_id,
                menu_item_id,
                discount_minor,
                discount_percent,
                original_price_minor,
                quantity,
                currency
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, applicationId)
            statement.setLong(2, adjustment.orderBatchItemId)
            statement.setLong(3, adjustment.menuItemId)
            statement.setLong(4, adjustment.discountMinor)
            statement.setInt(5, adjustment.discountPercent)
            statement.setLong(6, adjustment.originalPriceMinor)
            statement.setInt(7, adjustment.quantity)
            statement.setString(8, adjustment.currency)
            statement.executeUpdate()
        }
    }

    private fun findApplicationIdByDedupeKey(
        connection: Connection,
        dedupeKey: String,
    ): Long? =
        connection.prepareStatement(
            "SELECT id FROM order_promotion_applications WHERE dedupe_key = ?",
        ).use { statement ->
            statement.setString(1, dedupeKey)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else null }
        }

    private fun adjustmentExists(
        connection: Connection,
        orderBatchItemId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM order_batch_item_promotion_adjustments WHERE order_batch_item_id = ?",
        ).use { statement ->
            statement.setLong(1, orderBatchItemId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun rewardItemExists(
        connection: Connection,
        rewardOrderBatchItemId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM order_promotion_reward_items WHERE reward_order_batch_item_id = ?",
        ).use { statement ->
            statement.setLong(1, rewardOrderBatchItemId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun insertRewardItem(
        connection: Connection,
        applicationId: Long,
        reward: PromotionRewardItemInput,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_promotion_reward_items (
                application_id,
                trigger_order_batch_item_id,
                reward_order_batch_item_id,
                reward_menu_item_id,
                reward_qty,
                label_snapshot
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, applicationId)
            setNullableLong(statement, 2, reward.triggerOrderBatchItemId)
            statement.setLong(3, reward.rewardOrderBatchItemId)
            statement.setLong(4, reward.rewardMenuItemId)
            statement.setInt(5, reward.rewardQty)
            statement.setString(6, reward.labelSnapshot)
            statement.executeUpdate()
        }
    }

    private fun adjustmentColumns(): String =
        """
        opa.id AS application_id,
        opa.order_id,
        opa.batch_id,
        opa.rule_id,
        opa.title_snapshot,
        obipa.order_batch_item_id,
        obipa.menu_item_id,
        obipa.discount_minor,
        obipa.discount_percent,
        obipa.original_price_minor,
        obipa.quantity,
        obipa.currency
        """.trimIndent()

    private fun rsToAdjustmentRecord(rs: java.sql.ResultSet): PromotionAdjustmentRecord =
        PromotionAdjustmentRecord(
            applicationId = rs.getLong("application_id"),
            orderId = rs.getLong("order_id"),
            batchId = rs.getLong("batch_id").let { value -> if (rs.wasNull()) null else value },
            orderBatchItemId = rs.getLong("order_batch_item_id"),
            menuItemId = rs.getLong("menu_item_id"),
            ruleId = rs.getLong("rule_id"),
            titleSnapshot = rs.getString("title_snapshot"),
            discountMinor = rs.getLong("discount_minor"),
            discountPercent = rs.getInt("discount_percent"),
            originalPriceMinor = rs.getLong("original_price_minor"),
            quantity = rs.getInt("quantity"),
            currency = rs.getString("currency"),
        )

    private fun setNullableLong(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Long?,
    ) {
        if (value == null) {
            statement.setObject(index, null)
        } else {
            statement.setLong(index, value)
        }
    }
}
