package com.hookah.platform.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(dbConfig: DbConfig): DataSource? {
        if (!dbConfig.isEnabled) {
            logger.info("Database is disabled: jdbcUrl is not configured")
            return null
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = dbConfig.jdbcUrl
            dbConfig.user?.let { username = it }
            dbConfig.password?.let { password = it }
            dbConfig.maxPoolSize?.let { maximumPoolSize = it }
            dbConfig.connectionTimeoutMs?.let { connectionTimeout = it }
        }

        val dataSource = HikariDataSource(hikariConfig)

        try {
            val locations = resolveFlywayLocations(dbConfig.jdbcUrl.orEmpty())
            Flyway.configure()
                .dataSource(dataSource)
                .locations(*locations)
                .load()
                .migrate()
        } catch (e: Exception) {
            val safeMessage = (e.message ?: "unknown error")
                .replace(Regex("[\\r\\n\\t]"), " ")
                .take(200)
            logger.error("Flyway migration failed: {} {}", e::class.simpleName, safeMessage)
            dataSource.close()
            throw e
        }

        logger.info("Flyway migrations completed")

        return dataSource
    }

    fun close(dataSource: DataSource?) {
        if (dataSource is HikariDataSource) {
            logger.info("Closing HikariDataSource")
            dataSource.close()
        }
    }

    private fun resolveFlywayLocations(jdbcUrl: String): Array<String> {
        return if (jdbcUrl.startsWith("jdbc:h2:", ignoreCase = true)) {
            arrayOf("classpath:db/migration/h2")
        } else {
            arrayOf("classpath:db/migration")
        }
    }
}
