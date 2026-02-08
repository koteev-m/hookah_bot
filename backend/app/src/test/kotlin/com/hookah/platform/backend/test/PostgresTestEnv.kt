package com.hookah.platform.backend.test

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.MapApplicationConfig
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

data class PostgresTestDatabase(
    val schema: String,
    val jdbcUrl: String,
    val user: String,
    val password: String,
)

object PostgresTestEnv {
    private val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }
            .getOrElse { false }
    }

    private val container: PostgreSQLContainer<*> by lazy {
        val image = DockerImageName.parse("postgres:16-alpine")
        PostgreSQLContainer(image).apply {
            withDatabaseName("hookah_test")
            withUsername("hookah")
            withPassword("hookah")
            withReuse(true)
        }
    }

    fun createDatabase(): PostgresTestDatabase {
        Assumptions.assumeTrue(
            dockerAvailable,
            "Docker is required to run Postgres Testcontainers tests",
        )
        if (!container.isRunning) {
            container.start()
        }
        val schema = "test_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
            }
        }
        val jdbcUrl = buildJdbcUrlWithSchema(container.jdbcUrl, schema)
        return PostgresTestDatabase(
            schema = schema,
            jdbcUrl = jdbcUrl,
            user = container.username,
            password = container.password,
        )
    }

    fun buildConfig(database: PostgresTestDatabase): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to "test",
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to database.jdbcUrl,
            "db.user" to database.user,
            "db.password" to database.password,
            "db.maxPoolSize" to "3",
            "telegram.enabled" to "false",
        )
    }

    fun createDataSource(
        database: PostgresTestDatabase,
        migrate: Boolean = true,
    ): HikariDataSource {
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = database.jdbcUrl
                    username = database.user
                    password = database.password
                    maximumPoolSize = 3
                },
            )
        if (migrate) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
        return dataSource
    }

    private fun buildJdbcUrlWithSchema(
        jdbcUrl: String,
        schema: String,
    ): String {
        val delimiter = if (jdbcUrl.contains("?")) "&" else "?"
        return "$jdbcUrl${delimiter}currentSchema=$schema"
    }
}
