package lab.control;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

final class ControlTestDatabase {
    static final String CONTROL_PASSWORD = "control-test";

    static PostgreSQLContainer container() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("lab_control").withUsername("lab_admin").withPassword("postgres-test")
                .withEnv("LAB_CONTROL_DB_PASSWORD", CONTROL_PASSWORD)
                .withCopyFileToContainer(MountableFile.forClasspathResource("db/control/role.sql"),
                        "/docker-entrypoint-initdb.d/10-control.sql");
    }

    static void migrate(PostgreSQLContainer postgres) {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("control").locations("classpath:db/control/migrations").load().migrate();
    }

    static JdbcClient admin(PostgreSQLContainer postgres) {
        return JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private ControlTestDatabase() {
    }
}
