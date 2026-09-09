import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;

/** Test-only launcher using the image's actual Flyway and PostgreSQL driver. */
public final class V126RuntimeFixture {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("DB_JDBC_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");
        if (args.length == 1 && args[0].equals("migrate125")) {
            Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration/postgresql").target("125").load().migrate();
            System.out.println("FIXTURE_MIGRATED_TO_125");
        } else if (args.length == 2 && args[0].equals("identity")) {
            // Execute the production identity SQL unchanged through actual backend JDBC.
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(10);
                boolean result = statement.execute(Files.readString(Path.of(args[1])));
                do {
                    if (result) {
                        try (ResultSet rows = statement.getResultSet()) {
                            while (rows.next()) System.out.println(rows.getString(1));
                        }
                    } else if (statement.getUpdateCount() == -1) {
                        break;
                    }
                    result = statement.getMoreResults();
                } while (true);
            }
        } else {
            throw new IllegalArgumentException("Unknown test-only operation");
        }
    }
}
