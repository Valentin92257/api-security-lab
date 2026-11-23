package lab.detector;

import java.util.List;
import java.util.UUID;

import lab.contracts.detector.model.LogBatch;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.analysis.AnalysisService;
import lab.detector.storage.DetectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static lab.detector.DetectorTestData.batch;
import static lab.detector.DetectorTestData.incident;
import static lab.detector.DetectorTestData.log;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "lab.detector.control-token=storage-control", "lab.detector.gateway-token=storage-gateway",
        "lab.detector.shop-token=storage-shop", "lab.detector.worker-enabled=false", "lab.detector.recovery-enabled=false"
})
class DetectorStorageIntegrationTest extends AbstractDetectorDatabaseTest {
    @Autowired
    private AnalysisService analyses;
    @Autowired
    private DetectorRepository repository;
    @Autowired
    private ContractJsonCodec codec;

    @Test
    void publishingAnOlderSnapshotLeavesNewInputDirtyAndCannotReplaceANewerSnapshot() {
        UUID run = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        analyses.prepare(run);
        analyses.ingest(run, codec.read(batch("SHOP", log(1, first)), LogBatch.class));
        AnalysisService.Input older = analyses.input(run);
        analyses.ingest(run, codec.read(batch("SHOP", log(2, second)), LogBatch.class));
        var preview = incident(UUID.randomUUID(), first, "2026-11-24T10:00:00Z");
        analyses.publish(run, older, List.of(preview));
        assertThat(repository.find(run, false).orElseThrow().processedRevision()).isEqualTo(1);
        assertThat(repository.find(run, false).orElseThrow().inputRevision()).isEqualTo(2);
        assertThat(repository.nextReady()).contains(run);

        var newer = analyses.input(run);
        analyses.publish(run, newer, List.of(preview.description("New snapshot")));
        analyses.publish(run, older, List.of());
        assertThat(analyses.incidents(run, 100, 0).getItems()).hasSize(1);
        assertThat(analyses.incidents(run, 100, 0).getItems().getFirst().getDescription()).isEqualTo("New snapshot");
        assertThat(repository.nextReady()).isEmpty();
    }

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
