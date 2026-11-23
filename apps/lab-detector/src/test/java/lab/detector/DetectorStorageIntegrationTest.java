package lab.detector;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "lab.detector.control-token=storage-control", "lab.detector.gateway-token=storage-gateway",
        "lab.detector.shop-token=storage-shop", "lab.detector.worker-enabled=false", "lab.detector.recovery-enabled=false"
})
class DetectorStorageIntegrationTest extends AbstractDetectorDatabaseTest {
    @Test
    void migrationsAreRepeatableAndRuntimeCannotModifyImmutableDataOrSchema() throws Exception {
        assertThat(POSTGRES.execInContainer("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", POSTGRES.getUsername(),
                "-d", "postgres", "-f", "/docker-entrypoint-initdb.d/15-detector.sql").getExitCode()).isZero();
        DetectorTestDatabase.migrate(POSTGRES);
        assertThat(runtime.sql("SELECT current_user").query(String.class).single()).isEqualTo("detector_app");
        assertThat(admin.sql("SELECT has_schema_privilege('detector_app', 'detector', 'CREATE')")
                .query(Boolean.class).single()).isFalse();
        for (String table : List.of("access_logs", "incidents")) {
            for (String privilege : List.of("UPDATE", "DELETE")) {
                assertThat(admin.sql("SELECT has_table_privilege('detector_app', :table, :privilege)")
                        .param("table", "detector." + table).param("privilege", privilege)
                        .query(Boolean.class).single()).isFalse();
            }
        }
        assertThat(admin.sql("SELECT has_table_privilege('detector_app', 'detector.flyway_schema_history', 'SELECT')")
                .query(Boolean.class).single()).isFalse();
        assertThat(admin.sql("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = 'detector_migrator'")
                .query(Boolean.class).single()).isFalse();
    }
}
