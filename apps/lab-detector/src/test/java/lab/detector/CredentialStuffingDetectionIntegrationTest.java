package lab.detector;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import lab.contracts.detector.model.AnalysisStatus;
import lab.contracts.detector.model.Incident;
import lab.contracts.detector.model.LogBatch;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.analysis.AnalysisService;
import lab.detector.analysis.FinishSpec;
import lab.detector.engine.AnalysisEngine;
import lab.detector.engine.RuleBasedAnalysisEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.node.ObjectNode;

import static lab.detector.DetectorTestData.batch;
import static lab.detector.DetectorTestData.log;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "lab.detector.control-token=rules-control", "lab.detector.gateway-token=rules-gateway",
        "lab.detector.shop-token=rules-shop"
})
class CredentialStuffingDetectionIntegrationTest extends AbstractDetectorDatabaseTest {
    @Autowired
    private AnalysisService analyses;
    @Autowired
    private AnalysisEngine engine;
    @Autowired
    private ContractJsonCodec codec;

    @Test
    void previewsSurviveLateEarlierEvidenceAndBecomeImmutableAfterBothRangesAreComplete() {
        assertThat(engine).isInstanceOf(RuleBasedAnalysisEngine.class);
        UUID run = UUID.randomUUID();
        analyses.prepare(run);
        var logs = loginLogs(11);
        var success = log(12, UUID.randomUUID()).put("method", "POST").put("path", "/api/v1/auth/login")
                .put("time", "2026-11-16T10:00:20Z");
        logs.add(success);
        var incomplete = new ArrayList<>(logs);
        ObjectNode late = incomplete.removeFirst();
        ingest(run, "SHOP", incomplete);
        Incident preview = preview(run, 1).getFirst();
        assertThat(analyses.get(run).getState()).isEqualTo(AnalysisStatus.StateEnum.ACCEPTING);
        assertThat(preview.getRequestIds()).hasSize(11);
        ingest(run, "GATEWAY", logs);
        ingest(run, "SHOP", incomplete);
        var finish = new FinishSpec(12, 12, Instant.now().plusSeconds(30));
        analyses.finish(run, finish);
        assertThat(analyses.get(run).getState()).isEqualTo(AnalysisStatus.StateEnum.FINISHING);
        assertThat(analyses.incidents(run, 100, 0).getItems()).hasSize(1);
        assertThat(runtime.sql("SELECT count(*) FROM detector.incidents WHERE run_id = :run")
                .param("run", run).query(Long.class).single()).isZero();

        ingest(run, "SHOP", List.of(late));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(analyses.get(run).getState()).isEqualTo(AnalysisStatus.StateEnum.COMPLETED));
        var page = analyses.incidents(run, 100, 0);
        assertThat(page.getTotal()).isEqualTo(1);
        var incident = page.getItems().getFirst();
        assertThat(incident.getIncidentId()).isEqualTo(preview.getIncidentId());
        assertThat(incident.getDetectedAt()).isEqualTo(preview.getDetectedAt());
        assertThat(incident.getDescription()).contains("правила CS-01, CS-03;", "отказов 401: 11",
                "успешных входов 200: 1");
        assertThat(incident.getRequestIds()).containsExactlyInAnyOrderElementsOf(logs.stream()
                .map(entry -> UUID.fromString(entry.get("requestId").asString())).toList());
        codec.validate(incident);
        assertThat(runtime.sql("SELECT count(*) FROM detector.incidents WHERE run_id = :run")
                .param("run", run).query(Long.class).single()).isEqualTo(1);
        ingest(run, "SHOP", logs);
        analyses.finish(run, finish);
        assertThat(analyses.incidents(run, 100, 0)).isEqualTo(page);
    }

    @Test
    void successUpdatesAnExistingIncidentBeforeFinish() {
        UUID run = UUID.randomUUID();
        analyses.prepare(run);
        ingest(run, "SHOP", loginLogs(10));
        Incident initial = preview(run, 1).getFirst();
        assertThat(initial.getDescription()).contains("CS-01").doesNotContain("CS-03");
        var success = log(11, UUID.randomUUID()).put("method", "POST").put("path", "/api/v1/auth/login")
                .put("time", "2026-11-16T10:00:20Z");
        ingest(run, "SHOP", List.of(success));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Incident updated = analyses.incidents(run, 100, 0).getItems().getFirst();
            assertThat(updated.getIncidentId()).isEqualTo(initial.getIncidentId());
            assertThat(updated.getDetectedAt()).isEqualTo(initial.getDetectedAt());
            assertThat(updated.getRequestIds()).hasSize(11);
            assertThat(updated.getDescription()).contains("CS-03");
        });
        assertThat(analyses.get(run).getState()).isEqualTo(AnalysisStatus.StateEnum.ACCEPTING);
        assertThat(analyses.get(run).getObservedRequestIds()).isNull();
    }

    @Test
    void lateIntermediateLogsMergePreviouslyPublishedIncidents() {
        UUID run = UUID.randomUUID();
        analyses.prepare(run);
        var logs = new ArrayList<ObjectNode>();
        for (int i = 0; i < 20; i++) {
            logs.add(loginLog(i + 1, i < 10 ? i : 180 + i - 10));
        }
        ingest(run, "SHOP", logs);
        var separate = preview(run, 2);
        Incident oldest = separate.stream().min(Comparator
                .comparing((Incident incident) -> incident.getDetectedAt().toInstant())
                .thenComparing(Incident::getIncidentId)).orElseThrow();
        var intermediate = new ArrayList<ObjectNode>();
        for (int i = 0; i < 10; i++) {
            intermediate.add(loginLog(21 + i, 90 + i));
        }
        ingest(run, "SHOP", intermediate);
        Incident merged = preview(run, 1).getFirst();
        assertThat(merged.getIncidentId()).isEqualTo(oldest.getIncidentId());
        assertThat(merged.getDetectedAt()).isEqualTo(oldest.getDetectedAt());
        assertThat(merged.getRequestIds()).hasSize(30);
        assertThat(merged.getDescription()).contains("CS-01, CS-05");
    }

    @Test
    void benignRunCompletesWithAnEmptyIncidentList() {
        UUID run = UUID.randomUUID();
        analyses.prepare(run);
        var logs = loginLogs(9);
        ingest(run, "GATEWAY", logs);
        ingest(run, "SHOP", logs);
        analyses.finish(run, new FinishSpec(9, 9, Instant.now().plusSeconds(30)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(analyses.get(run).getState()).isEqualTo(AnalysisStatus.StateEnum.COMPLETED));
        assertThat(analyses.incidents(run, 100, 0).getItems()).isEmpty();
    }

    private List<ObjectNode> loginLogs(int count) {
        var logs = new ArrayList<ObjectNode>();
        for (int i = 1; i <= count; i++) {
            logs.add(loginLog(i, i));
        }
        return logs;
    }

    private ObjectNode loginLog(int sequence, int seconds) {
        return log(sequence, UUID.randomUUID()).put("method", "POST").put("path", "/api/v1/auth/login")
                .put("status", 401).put("time", Instant.parse("2026-11-16T10:00:00Z").plusSeconds(seconds).toString());
    }

    private List<Incident> preview(UUID run, int count) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(analyses.incidents(run, 100, 0).getItems()).hasSize(count));
        return analyses.incidents(run, 100, 0).getItems();
    }

    private void ingest(UUID run, String producer, List<ObjectNode> logs) {
        analyses.ingest(run, codec.read(batch(producer, logs.toArray(ObjectNode[]::new)), LogBatch.class));
    }
}
