package lab.detector;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static lab.detector.DetectorTestData.batch;
import static lab.detector.DetectorTestData.finishBody;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorIngestionIntegrationTest extends AbstractDetectorApiIntegrationTest {
    @Test
    void conflictingDtoDuplicateRollsBackWholeBatch() throws Exception {
        UUID id = prepare();
        ObjectNode first = log(1, UUID.randomUUID());
        assertThat(ingest(id, "GATEWAY", first, first).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", first).statusCode()).isEqualTo(204);
        var conflict = first.deepCopy().put("status", 403);
        assertProblem(ingest(id, "GATEWAY", log(2, UUID.randomUUID()), conflict), 409, "CONFLICT");
        assertThat(count("access_logs", id)).isEqualTo(1);
        assertThat(progress(id, "received_count")).isEqualTo(1);
        assertThat(progress(id, "max_sequence")).isEqualTo(1);
        assertThat(state(id)).isEqualTo("ACCEPTING");
        assertProblem(ingest(id, "GATEWAY", log(2, id), log(2, id).put("status", 403)), 409, "CONFLICT");
        assertThat(count("access_logs", id)).isEqualTo(1);
        // A changed response conflicts even though req_hash (the business request hash) is unchanged.
        assertProblem(ingest(id, "GATEWAY", first.deepCopy().put("bytes", 99)), 409, "CONFLICT");
    }

    @Test
    void numbersMappingToSameDtoAreSafeRetriesWithinBatchAfterPersistenceAndCompletion() throws Exception {
        UUID id = prepare();
        ObjectNode first = log(1, id).put("request_time", new java.math.BigDecimal("0.100000000000000000001"));
        ObjectNode roundedSame = first.deepCopy()
                .put("request_time", new java.math.BigDecimal("0.100000000000000000002"));
        assertThat(ingest(id, "GATEWAY", first, roundedSame).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", roundedSame).statusCode()).isEqualTo(204);
        assertThat(count("access_logs", id)).isEqualTo(1);
        assertThat(progress(id, "received_count")).isEqualTo(1);
        ObjectNode different = first.deepCopy().put("request_time", 0.2);
        assertProblem(ingest(id, "GATEWAY", log(2, id), different), 409, "CONFLICT");
        assertThat(count("access_logs", id)).isEqualTo(1);
        assertThat(progress(id, "max_sequence")).isEqualTo(1);
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        completed(id);
        assertThat(engine.observations.getFirst().payload().get("request_time").decimalValue())
                .isEqualByComparingTo("0.1");
        assertThat(ingest(id, "GATEWAY", first, roundedSame).statusCode()).isEqualTo(204);
        assertProblem(ingest(id, "GATEWAY", different), 409, "CONFLICT");
        assertThat(progress(id, "received_count")).isEqualTo(1);
    }

    @Test
    void signedZeroRemainsIdempotentAfterJsonbPersistence() throws Exception {
        UUID id = prepare();
        ObjectNode negative = log(1, id).put("request_time", -0.0d);
        ObjectNode positive = negative.deepCopy().put("request_time", 0.0d);
        assertThat(ingest(id, "GATEWAY", negative, positive).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", negative).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", positive).statusCode()).isEqualTo(204);
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        completed(id);
        assertThat(ingest(id, "GATEWAY", negative, positive).statusCode()).isEqualTo(204);
        assertThat(count("access_logs", id)).isEqualTo(1);
        assertThat(progress(id, "received_count")).isEqualTo(1);
    }

    @Test
    void uuidAndUtcTimeRepresentationsFollowDtoEquality() throws Exception {
        UUID id = prepare();
        UUID request = UUID.fromString("abcdef00-abcd-4000-8000-000000000001");
        ObjectNode first = log(1, request);
        ObjectNode equivalent = first.deepCopy()
                .put("requestId", request.toString().toUpperCase(java.util.Locale.ROOT))
                .put("time", "2026-11-16T10:00:00+00:00");
        assertThat(ingest(id, "GATEWAY", first, equivalent).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", equivalent).statusCode()).isEqualTo(204);
        assertThat(count("access_logs", id)).isEqualTo(1);
        assertThat(progress(id, "received_count")).isEqualTo(1);
    }

    @Test
    void jsonFormattingAndEquivalentNumbersAreSafeRetries() throws Exception {
        UUID id = prepare();
        ObjectNode entry = log(1, UUID.randomUUID()).put("request_time", 1);
        ObjectNode reordered = JSON.createObjectNode();
        var names = new ArrayList<>(entry.propertyNames());
        Collections.reverse(names);
        for (String name : names) {
            reordered.set(name, entry.get(name));
        }
        reordered.put("request_time", new java.math.BigDecimal("1.00"));
        assertThat(ingest(id, "GATEWAY", entry, reordered).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", reordered).statusCode()).isEqualTo(204);
        assertThat(count("access_logs", id)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesDoNotAdvanceProgressTwiceAndProducersHaveIndependentSequences() throws Exception {
        UUID id = prepare();
        ObjectNode entry = log(1, UUID.randomUUID());
        var responses = concurrent(Collections.nCopies(8, () -> ingest(id, "GATEWAY", entry)));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsOnly(204);
        assertThat(ingest(id, "SHOP", entry).statusCode()).isEqualTo(204);
        assertThat(count("access_logs", id)).isEqualTo(2);
        assertThat(progress(id, "received_count")).isEqualTo(1);
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 1)).statusCode()).isEqualTo(202);
        JsonNode status = completed(id);
        assertThat(status.get("observedRequestIds")).hasSize(1);
        assertThat(engine.observations).hasSize(2);
        int completedCalls = engine.calls.get();
        assertThat(completedCalls).isPositive();
        assertThat(ingest(id, "SHOP", entry).statusCode()).isEqualTo(204);
        assertThat(engine.calls.get()).isEqualTo(completedCalls);
    }

    @Test
    void storageFailureRollsBackEveryEntryAndProgressBeforeFailingRun() throws Exception {
        UUID id = prepare();
        admin.sql("""
                CREATE FUNCTION detector.reject_test_log() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.sequence = 2 THEN RAISE EXCEPTION 'test write failure'; END IF; RETURN NEW; END $$
                """).update();
        admin.sql("""
                CREATE TRIGGER reject_test_log BEFORE INSERT ON detector.access_logs
                FOR EACH ROW EXECUTE FUNCTION detector.reject_test_log()
                """).update();
        try {
            assertProblem(ingest(id, "GATEWAY", log(1, id), log(2, id)), 503, "UNAVAILABLE");
            assertThat(count("access_logs", id)).isZero();
            assertThat(progress(id, "received_count")).isZero();
            assertThat(progress(id, "max_sequence")).isZero();
            assertThat(state(id)).isEqualTo("FAILED");
            assertProblem(control("PUT", path(id) + "/finish", finishBody(0, 0)), 409, "CONFLICT");
        } finally {
            admin.sql("DROP TRIGGER reject_test_log ON detector.access_logs").update();
            admin.sql("DROP FUNCTION detector.reject_test_log()").update();
        }
    }

    @Test
    void rejectsMalformedAndOversizedPayloadsBeforeWriting() throws Exception {
        UUID id = prepare();
        String valid = batch("GATEWAY", log(1, id));
        for (String invalid : List.of(valid.replace("\"sequence\":1", "\"sequence\":\"1\""),
                valid.replace("\"sequence\":1", "\"sequence\":0"),
                valid.replace("\"producer\":\"GATEWAY\"", "\"producer\":\"GATEWAY\",\"producer\":\"SHOP\""),
                valid.replace("\"sequence\":1", "\"sequence\":1,\"unknown\":true"), valid + " {}",
                "{\"producer\":\"GATEWAY\",\"entries\":[]}")) {
            assertProblem(send("POST", path(id) + "/log-batches", "gateway-test", invalid), 400, "INVALID_REQUEST");
        }
        String tooLarge = " ".repeat(8 * 1024 * 1024 + 1);
        assertProblem(send("POST", path(id) + "/log-batches", "gateway-test", tooLarge), 413, "INVALID_REQUEST");
        assertThat(count("access_logs", id)).isZero();
        assertThat(state(id)).isEqualTo("ACCEPTING");
        assertProblem(control("PUT", path(id) + "/finish", "{\"gatewayLastSequence\":0,\"shopLastSequence\":0}"),
                400, "INVALID_REQUEST");
        ObjectNode[] tooMany = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(n -> log(n, id)).toArray(ObjectNode[]::new);
        assertProblem(ingest(id, "GATEWAY", tooMany), 400, "INVALID_REQUEST");
        assertProblem(ingest(id, "GATEWAY", log(100001, id)), 400, "INVALID_REQUEST");
        assertProblem(control("PUT", path(id) + "/finish", finishBody(100001, 0)), 400, "INVALID_REQUEST");
        assertThat(state(id)).isEqualTo("ACCEPTING");
    }
}
