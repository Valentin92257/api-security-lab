package lab.detector;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static lab.detector.DetectorTestData.finishBody;
import static lab.detector.DetectorTestData.incident;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DetectorLiveAnalysisIntegrationTest extends AbstractDetectorApiIntegrationTest {
    @Test
    void identicalRetriesDoNotRecomputeButLateSequenceBelowTheMaximumDoes() throws Exception {
        UUID run = prepare();
        var one = log(1, UUID.randomUUID());
        var two = log(2, UUID.randomUUID());
        var three = log(3, UUID.randomUUID());
        ingest(run, "GATEWAY", one, three);
        processed(run, 1);
        int initialCalls = engine.calls.get();
        assertThat(initialCalls).isEqualTo(1);
        assertThat(state(run)).isEqualTo("ACCEPTING");

        assertThat(ingest(run, "GATEWAY", one, three).statusCode()).isEqualTo(204);
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(revision(run, "input_revision")).isEqualTo(1);
            assertThat(engine.calls.get()).isEqualTo(initialCalls);
        });
        assertThat(ingest(run, "GATEWAY", two).statusCode()).isEqualTo(204);
        processed(run, 2);
        assertThat(progress(run, "max_sequence")).isEqualTo(3);
        assertThat(engine.calls.get()).isEqualTo(initialCalls + 1);

        control("PUT", path(run) + "/finish", finishBody(3, 0));
        completed(run);
        assertThat(engine.calls.get()).isEqualTo(initialCalls + 2);
        assertThat(revision(run, "input_revision")).isEqualTo(2);
        assertThat(count("live_incidents", run)).isZero();
    }

    @Test
    void snapshotDoesNotBlockIngestAndFinishAndTheFinalPassIncludesConcurrentWrites() throws Exception {
        UUID run = prepare();
        UUID firstRequest = UUID.randomUUID();
        UUID secondRequest = UUID.randomUUID();
        var original = incident(UUID.randomUUID(), firstRequest, "2026-11-24T10:00:00Z");
        engine.results = List.of(original);
        engine.block = true;
        ingest(run, "SHOP", log(1, firstRequest));
        assertThat(engine.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.observations).hasSize(1);

        // These writes must complete while the repeatable-read analysis is blocked.
        assertThat(ingest(run, "SHOP", log(2, secondRequest)).statusCode()).isEqualTo(204);
        assertThat(control("PUT", path(run) + "/finish", finishBody(0, 2)).statusCode()).isEqualTo(202);
        assertThat(state(run)).isEqualTo("FINISHING");
        assertThat(count("incidents", run)).isZero();
        engine.results = List.of(incident(UUID.randomUUID(), firstRequest, "2026-11-24T10:01:00Z")
                .requestIds(Set.of(firstRequest, secondRequest)).description("Final snapshot"));
        engine.block = false;
        engine.release.countDown();

        completed(run);
        var page = body(control("GET", path(run) + "/incidents", null));
        var result = page.get("items").get(0);
        assertThat(result.get("incidentId").asString()).isEqualTo(original.getIncidentId().toString());
        assertThat(result.get("detectedAt").asString()).startsWith("2026-11-24T10:00:00");
        assertThat(result.get("description").asString()).isEqualTo("Final snapshot");
        assertThat(result.get("requestIds")).hasSize(2);
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(engine.observations).hasSize(2);
        assertThat(revision(run, "processed_revision")).isEqualTo(2);
        assertThat(count("live_incidents", run)).isZero();
    }

    private void processed(UUID run, long expectedRevision) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(revision(run, "processed_revision")).isEqualTo(expectedRevision));
    }

    private long revision(UUID run, String field) {
        return admin.sql("SELECT " + field + " FROM detector.analysis_runs WHERE run_id = :id")
                .param("id", run).query(Long.class).single();
    }
}
