package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.SQLException
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriptionBillingVenueRepository(private val dataSource: DataSource?) {
    suspend fun listVenueIds(): List<Long> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                            SELECT id
                            FROM venues
                            ORDER BY id ASC
                        """.trimIndent()
                    ).use { statement ->
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.getLong("id"))
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }
}
