package lab.detector;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import lab.detector.engine.AnalysisEngine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static lab.detector.DetectorTestData.finishBody;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorFinalizationIntegrationTest extends AbstractDetectorApiIntegrationTest {
    @Test
    void waitsForEverySequenceAndPublishesOnlyAfterLateGapIsFilled() throws Exception {
        UUID id = prepare();
        ObjectNode one = log(1, UUID.randomUUID());
        ObjectNode two = log(2, UUID.randomUUID());
        ObjectNode three = log(3, UUID.randomUUID());
        assertThat(ingest(id, "GATEWAY", three, one).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "SHOP", one).statusCode()).isEqualTo(204);
        String finish = finishBody(3, 1);
        assertThat(control("PUT", path(id) + "/finish", finish).statusCode()).isEqualTo(202);
        assertThat(state(id)).isEqualTo("FINISHING");
        assertThat(body(control("GET", path(id), null)).get("observedRequestIds").isNull()).isTrue();
        assertProblem(control("GET", path(id) + "/incidents", null), 409, "CONFLICT");
        assertProblem(ingest(id, "GATEWAY", two, log(4, id)), 409, "CONFLICT");
        assertThat(count("access_logs", id)).isEqualTo(3);
        assertThat(ingest(id, "GATEWAY", two).statusCode()).isEqualTo(204);
        assertThat(completed(id).get("observedRequestIds")).hasSize(3);
        assertThat(engine.observations.stream().map(AnalysisEngine.Observation::sequence).toList()).contains(1, 2, 3);
        var incidents = control("GET", path(id) + "/incidents", null);
        assertThat(incidents.statusCode()).isEqualTo(200);
        assertThat(body(incidents).get("items")).isEmpty();
        assertThat(body(incidents).get("total").asLong()).isZero();
        assertThat(ingest(id, "GATEWAY", one, two, three).statusCode()).isEqualTo(204);
        assertProblem(ingest(id, "GATEWAY", log(4, id)), 409, "CONFLICT");
        assertProblem(ingest(id, "GATEWAY", one.deepCopy().put("status", 403)), 409, "CONFLICT");
        assertThat(body(control("PUT", path(id) + "/finish", finish)).get("state").asString()).isEqualTo("COMPLETED");
        assertProblem(control("PUT", path(id) + "/finish", finishBody(3, 1)), 409, "CONFLICT");
        assertThat(control("PUT", path(id), null).statusCode()).isEqualTo(200);
        assertThat(state(id)).isEqualTo("COMPLETED");
    }

    @Test
    void finishBelowSavedMaximumDoesNotChangeStateOrInstallBounds() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(3, id)).statusCode()).isEqualTo(204);
        assertProblem(control("PUT", path(id) + "/finish", finishBody(2, 0)), 409, "CONFLICT");
        assertThat(state(id)).isEqualTo("ACCEPTING");
        assertThat(admin.sql("SELECT finish_request IS NULL FROM detector.analysis_runs WHERE run_id = :id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(ingest(id, "GATEWAY", log(4, id)).statusCode()).isEqualTo(204);
    }

    @Test
    void emptyRangesCompleteAndFinishComparisonPreservesNanosecondsAndInstantSemantics() throws Exception {
        UUID id = prepare();
        String deadline = Instant.now().plusSeconds(30).toString();
        String finish = finishBody(0, 0, deadline);
        assertThat(control("PUT", path(id) + "/finish", finish).statusCode()).isEqualTo(202);
        assertThat(completed(id).get("observedRequestIds")).isEmpty();
        String equivalent = OffsetDateTime.parse(deadline).withOffsetSameInstant(java.time.ZoneOffset.ofHours(3)).toString();
        assertThat(control("PUT", path(id) + "/finish", finishBody(0, 0, equivalent)).statusCode()).isEqualTo(202);
        String changed = Instant.parse(deadline).plusNanos(1).toString();
        assertProblem(control("PUT", path(id) + "/finish", finishBody(0, 0, changed)), 409, "CONFLICT");
        assertProblem(ingest(id, "GATEWAY", log(1, id)), 409, "CONFLICT");
    }

    @Test
    void deadlineMakesMissingRangeFailedAndOnlySavedIdenticalRetriesRemainAllowed() throws Exception {
        UUID id = prepare();
        ObjectNode one = log(1, id);
        assertThat(ingest(id, "GATEWAY", one).statusCode()).isEqualTo(204);
        String finish = finishBody(2, 0, Instant.now().plusMillis(250).toString());
        assertThat(control("PUT", path(id) + "/finish", finish).statusCode()).isEqualTo(202);
        failed(id);
        assertThat(engine.calls.get()).isZero();
        assertThat(ingest(id, "GATEWAY", one).statusCode()).isEqualTo(204);
        assertProblem(ingest(id, "GATEWAY", one, log(2, id)), 409, "CONFLICT");
        assertProblem(ingest(id, "GATEWAY", one.deepCopy().put("status", 500)), 409, "CONFLICT");
        assertThat(body(control("PUT", path(id) + "/finish", finish)).get("state").asString()).isEqualTo("FAILED");
        assertProblem(control("PUT", path(id) + "/finish", finishBody(2, 0)), 409, "CONFLICT");
        assertProblem(control("GET", path(id) + "/incidents", null), 409, "CONFLICT");
        assertThat(count("access_logs", id)).isEqualTo(1);
    }

    @Test
    void watchdogExpiresSlowEngineAndRejectsItsLateResult() throws Exception {
        UUID id = prepare();
        engine.block = true;
        assertThat(control("PUT", path(id) + "/finish",
                finishBody(0, 0, Instant.now().plusSeconds(1).toString())).statusCode()).isEqualTo(202);
        assertThat(engine.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state(id)).isEqualTo("FINISHING");
        assertProblem(control("GET", path(id) + "/incidents", null), 409, "CONFLICT");
        failed(id); // The engine is still blocked: deadline checking must use another thread.
        engine.release.countDown();
        assertThat(engine.exited.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(count("incidents", id)).isZero();
    }

    @Test
    void rejectsPastDeadlineEvenForEmptyRanges() throws Exception {
        UUID id = prepare();
        assertThat(control("PUT", path(id) + "/finish",
                finishBody(0, 0, Instant.now().minusSeconds(1).toString())).statusCode()).isEqualTo(202);
        failed(id);
        assertThat(engine.calls.get()).isZero();
    }

    @Test
    void finishRacingWithIngestEitherIncludesTheWriteOrRejectsItAtomically() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id), log(2, id), log(3, id)).statusCode()).isEqualTo(204);
        var results = concurrent(List.of(
                () -> control("PUT", path(id) + "/finish", finishBody(3, 0)),
                () -> ingest(id, "GATEWAY", log(4, id))));
        int finishStatus = results.get(0).statusCode();
        int ingestStatus = results.get(1).statusCode();
        if (finishStatus == 202) {
            assertThat(ingestStatus).isEqualTo(409);
            completed(id);
            assertThat(count("access_logs", id)).isEqualTo(3);
        } else {
            assertThat(finishStatus).isEqualTo(409);
            assertThat(ingestStatus).isEqualTo(204);
            assertThat(state(id)).isEqualTo("ACCEPTING");
            assertThat(count("access_logs", id)).isEqualTo(4);
        }
    }

    @Test
    void observationsUseEventTimeThenProducerAndSequenceRegardlessOfArrivalOrder() throws Exception {
        UUID id = prepare();
        ObjectNode later = log(1, id).put("time", "2026-11-16T10:00:00.123456002Z");
        ObjectNode earlier = log(2, id).put("time", "2026-11-16T10:00:00.123456001Z");
        assertThat(ingest(id, "GATEWAY", later, earlier).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "SHOP", earlier.deepCopy().put("sequence", 1)).statusCode()).isEqualTo(204);
        control("PUT", path(id) + "/finish", finishBody(2, 1));
        completed(id);
        assertThat(engine.observations.stream().map(log -> log.producer() + "/" + log.sequence()).toList())
                .containsExactly("GATEWAY/2", "SHOP/1", "GATEWAY/1");
    }
}
