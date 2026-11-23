package lab.detector;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL lifecycle and cleanup shared by Spring-based Detector integration tests.
 * Closing each class's context prevents reuse of a JDBC URL from a stopped container.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractDetectorDatabaseTest {
    @Container
    protected static final PostgreSQLContainer POSTGRES = DetectorTestDatabase.container();

    @Autowired
    protected JdbcClient runtime;
    protected JdbcClient admin;

    @DynamicPropertySource
    protected static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DetectorTestDatabase.url(POSTGRES));
        properties.add("spring.datasource.password", () -> DetectorTestDatabase.APP_PASSWORD);
        properties.add("spring.flyway.password", () -> DetectorTestDatabase.MIGRATION_PASSWORD);
    }

    @BeforeEach
    protected final void resetDatabase() {
        admin = DetectorTestDatabase.admin(POSTGRES);
        // Match the worker's lock order: runs before progress, logs, and incidents.
        admin.sql("""
                TRUNCATE detector.analysis_runs, detector.producer_progress, detector.access_logs,
                    detector.incidents, detector.live_incidents
                """)
                .update();
    }
}
