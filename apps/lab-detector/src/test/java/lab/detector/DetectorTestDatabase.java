package lab.detector;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

final class DetectorTestDatabase {
    static final String APP_PASSWORD = "detector-app-test";
    static final String MIGRATION_PASSWORD = "detector-migration-test";

    static PostgreSQLContainer container() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("postgres").withUsername("lab_admin").withPassword("postgres-test")
                .withEnv("LAB_DETECTOR_DB_PASSWORD", APP_PASSWORD)
                .withEnv("LAB_DETECTOR_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
                .withCopyFileToContainer(MountableFile.forClasspathResource("db/detector/bootstrap.sql"),
                        "/docker-entrypoint-initdb.d/15-detector.sql");
    }

    static String url(PostgreSQLContainer postgres) {
        return postgres.getJdbcUrl().replace("/postgres", "/lab_detector");
    }

    static void migrate(PostgreSQLContainer postgres) {
        Flyway.configure().dataSource(url(postgres), "detector_migrator", MIGRATION_PASSWORD)
                .defaultSchema("detector").locations("classpath:db/detector/migrations").load().migrate();
    }

    static JdbcClient admin(PostgreSQLContainer postgres) {
        return JdbcClient.create(new DriverManagerDataSource(url(postgres), postgres.getUsername(), postgres.getPassword()));
    }

    private DetectorTestDatabase() {
    }
}
