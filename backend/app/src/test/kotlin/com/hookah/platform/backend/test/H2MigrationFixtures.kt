package com.hookah.platform.backend.test

import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * Migrates an H2 test database around the legacy unnamed V36 status check.
 *
 * Packaged H2 still needs a forward-only migration repair: V36 created an
 * auto-named check, while V38 drops only the PostgreSQL constraint name. Tests
 * use this explicit fixture repair so they do not add a test-only migration to
 * the application classpath or claim that a fresh packaged H2 migration works.
 */
internal fun migrateH2OnboardingFixture(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/h2")
        .target("37")
        .load()
        .migrate()

    dataSource.connection.use { connection ->
        val constraintNames =
            connection.prepareStatement(
                """
                SELECT tc.constraint_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog = tc.constraint_catalog
                 AND cc.constraint_schema = tc.constraint_schema
                 AND cc.constraint_name = tc.constraint_name
                WHERE LOWER(tc.table_name) = 'venue_connection_requests'
                  AND tc.constraint_type = 'CHECK'
                  AND LOWER(cc.check_clause) LIKE '%status%'
                  AND LOWER(cc.check_clause) LIKE '%new%'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
            }
        check(constraintNames.size == 1) {
            "Expected one legacy venue request status check, found ${constraintNames.size}"
        }
        val constraintName = constraintNames.single()
        check(constraintName.matches(Regex("[A-Za-z0-9_]+"))) {
            "Unexpected H2 constraint identifier"
        }
        connection.createStatement().use { statement ->
            statement.execute(
                "ALTER TABLE venue_connection_requests DROP CONSTRAINT \"$constraintName\"",
            )
        }
    }

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration/h2")
        .load()
        .migrate()
}
