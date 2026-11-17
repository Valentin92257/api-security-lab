package lab.detector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import lab.contracts.json.ContractJsonSupport;
import lab.detector.engine.AnalysisEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lab.detector.control-token=control-test", "lab.detector.gateway-token=gateway-test",
        "lab.detector.shop-token=shop-test", "lab.detector.poll-interval-ms=20", "lab.detector.max-incidents=5"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DetectorApiIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = DetectorTestDatabase.container();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = ContractJsonSupport.createJsonMapper();
    private static JdbcClient admin;

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcClient runtime;
    @Autowired
    private TestEngine engine;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> DetectorTestDatabase.url(POSTGRES));
        properties.add("spring.datasource.password", () -> DetectorTestDatabase.APP_PASSWORD);
        properties.add("spring.flyway.password", () -> DetectorTestDatabase.MIGRATION_PASSWORD);
    }

    @BeforeAll
    static void admin() {
        admin = DetectorTestDatabase.admin(POSTGRES);
    }

    @BeforeEach
    void reset() {
        engine.reset();
        admin.sql("TRUNCATE detector.incidents, detector.access_logs, detector.producer_progress, detector.analysis_runs")
                .update();
    }

    @AfterEach
    void releaseEngine() {
        engine.release.countDown();
    }

    @AfterAll
    static void closeClient() {
        HTTP.close();
    }

    @Test
    void preparesOnceAndKeepsStatusPrivateUntilCompletion() throws Exception {
        UUID id = UUID.randomUUID();
        var created = control("PUT", path(id), null);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created).get("state").asString()).isEqualTo("ACCEPTING");
        assertThat(body(created).get("observedRequestIds").isNull()).isTrue();
        var repeated = control("PUT", path(id), null);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(body(repeated)).isEqualTo(body(created));
        assertThat(body(control("GET", path(id), null))).isEqualTo(body(created));
        assertThat(count("producer_progress", id)).isEqualTo(2);
        assertProblem(control("GET", path(id) + "/incidents", null), 409, "CONFLICT");
        assertThat(engine.calls.get()).isZero();
    }

    @Test
    void concurrentPrepareCreatesExactlyOneRun() throws Exception {
        UUID id = UUID.randomUUID();
        var responses = concurrent(Collections.nCopies(8, () -> control("PUT", path(id), null)));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsOnly(200, 201).filteredOn(code -> code == 201).hasSize(1);
        assertThat(responses.stream().map(response -> body(response).get("instanceId")).distinct().count()).isEqualTo(1);
        assertThat(count("analysis_runs", id)).isEqualTo(1);
        assertThat(count("producer_progress", id)).isEqualTo(2);
    }

    @Test
    void enforcesTokensAndProducerBeforeLookingUpRun() throws Exception {
        UUID id = UUID.randomUUID();
        assertProblem(send("GET", path(id), null, null), 401, "UNAUTHORIZED");
        assertProblem(send("GET", path(id), "unknown", null), 401, "UNAUTHORIZED");
        for (String producerToken : List.of("gateway-test", "shop-test")) {
            assertProblem(send("PUT", path(id), producerToken, null), 403, "FORBIDDEN");
            assertProblem(send("GET", path(id), producerToken, null), 403, "FORBIDDEN");
            assertProblem(send("PUT", path(id) + "/finish", producerToken, finishBody(0, 0)), 403, "FORBIDDEN");
            assertProblem(send("GET", path(id) + "/incidents", producerToken, null), 403, "FORBIDDEN");
        }
        var gatewayBatch = batch("GATEWAY", log(1, UUID.randomUUID()));
        assertProblem(send("POST", path(id) + "/log-batches", "control-test", gatewayBatch), 403, "FORBIDDEN");
        assertProblem(send("POST", path(id) + "/log-batches", "shop-test", gatewayBatch), 403, "FORBIDDEN");
        assertProblem(send("POST", path(id) + "/log-batches", "gateway-test", batch("SHOP", log(1, id))), 403, "FORBIDDEN");
        assertProblem(control("GET", path(id), null), 404, "NOT_FOUND");
        assertProblem(control("PUT", path(id) + "/finish", finishBody(0, 0)), 404, "NOT_FOUND");
        assertProblem(control("GET", path(id) + "/incidents", null), 404, "NOT_FOUND");
        assertProblem(ingest(id, "GATEWAY", log(1, id)), 404, "NOT_FOUND");
    }

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
        assertThat(engine.calls.get()).isEqualTo(1);
        assertThat(ingest(id, "SHOP", entry).statusCode()).isEqualTo(204);
        assertThat(engine.calls.get()).isEqualTo(1);
    }

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
    void publishesEngineResultsAtomicallyWithStablePaginationAndPreciseTimeOrdering() throws Exception {
        UUID id = prepare();
        UUID request = UUID.randomUUID();
        assertThat(ingest(id, "GATEWAY", log(1, request)).statusCode()).isEqualTo(204);
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        engine.results = List.of(incident(c, request, "2026-11-16T10:00:00.123456002Z"),
                incident(b, request, "2026-11-16T10:00:00.123456001Z"),
                incident(a, request, "2026-11-16T10:00:00.123456001Z"));
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        completed(id);
        for (int offset = 0; offset < 3; offset++) {
            var page = body(control("GET", path(id) + "/incidents?limit=1&offset=" + offset, null));
            assertThat(page.get("total").asLong()).isEqualTo(3);
            assertThat(page.get("items")).hasSize(1);
            assertThat(page.get("items").get(0).get("incidentId").asString())
                    .isEqualTo(List.of(a, b, c).get(offset).toString());
            assertThat(page.get("items").get(0).get("detectedAt").asString())
                    .contains(offset == 2 ? ".123456002" : ".123456001");
        }
        var finalPage = body(control("GET", path(id) + "/incidents?offset=3", null));
        assertThat(finalPage.get("items")).isEmpty();
        assertThat(finalPage.get("total").asLong()).isEqualTo(3);
    }

    @Test
    void engineFailureAndInvalidEvidenceNeverPublishPartialResults() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id)).statusCode()).isEqualTo(204);
        engine.results = List.of(incident(UUID.randomUUID(), id, "2026-11-16T10:00:00Z"),
                incident(UUID.randomUUID(), UUID.randomUUID(), "2026-11-16T10:00:00Z"));
        control("PUT", path(id) + "/finish", finishBody(1, 0));
        failed(id);
        assertThat(count("incidents", id)).isZero();
        UUID other = prepare();
        engine.throwError = true;
        control("PUT", path(other) + "/finish", finishBody(0, 0));
        failed(other);
        assertThat(count("incidents", other)).isZero();
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

    @Test
    void resultLimitsFailWithoutTruncatingIncidentsOrObservedIds() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id)).statusCode()).isEqualTo(204);
        engine.results = java.util.stream.IntStream.range(0, 6)
                .mapToObj(n -> incident(UUID.randomUUID(), id, "2026-11-16T10:00:00Z")).toList();
        control("PUT", path(id) + "/finish", finishBody(1, 0));
        failed(id);
        assertThat(count("incidents", id)).isZero();

        UUID tooManyIds = prepare();
        // Populate a full, valid 100000-entry producer efficiently; the disjoint Shop ID
        // violates the protocol's maximum union of 100000 unique requestIds.
        admin.sql("""
                INSERT INTO detector.access_logs (run_id, producer, sequence, payload, request_id, event_time, event_nanos)
                SELECT :id, 'GATEWAY', n,
                    CAST(:base AS jsonb) || jsonb_build_object('sequence', n, 'requestId', md5(n::text)::uuid),
                    md5(n::text)::uuid, '2026-11-16T10:00:00Z'::timestamptz, 0 FROM generate_series(1, 100000) n
                """).param("id", tooManyIds).param("base", log(1, tooManyIds).toString()).update();
        admin.sql("""
                UPDATE detector.producer_progress SET received_count = 100000, max_sequence = 100000
                WHERE run_id = :id AND producer = 'GATEWAY'
                """).param("id", tooManyIds).update();
        assertThat(ingest(tooManyIds, "SHOP", log(1, UUID.randomUUID())).statusCode()).isEqualTo(204);
        control("PUT", path(tooManyIds) + "/finish", finishBody(100000, 1));
        failed(tooManyIds);
        assertThat(count("access_logs", tooManyIds)).isEqualTo(100001);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=101", "?offset=-1", "?offset=100001"})
    void validatesPagination(String query) throws Exception {
        UUID id = prepare();
        assertProblem(control("GET", path(id) + "/incidents" + query, null), 400, "INVALID_REQUEST");
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

    @Test
    void frameworkErrorsUseProblemAndUnsupportedAcceptDoesNotCreateRun() throws Exception {
        UUID id = UUID.randomUUID();
        assertProblem(control("DELETE", path(id), null), 405, "INVALID_REQUEST");
        assertProblem(control("GET", "/missing", null), 404, "NOT_FOUND");
        var unsupported = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path(id)))
                .header("Authorization", "Bearer control-test").header("Accept", "application/problem+json")
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        assertProblem(HTTP.send(unsupported, HttpResponse.BodyHandlers.ofString()), 406, "INVALID_REQUEST");
        assertThat(count("analysis_runs", id)).isZero();
        UUID prepared = prepare();
        var text = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path(prepared) + "/log-batches"))
                .header("Authorization", "Bearer gateway-test").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(batch("GATEWAY", log(1, id)))).build();
        assertProblem(HTTP.send(text, HttpResponse.BodyHandlers.ofString()), 415, "INVALID_REQUEST");
        assertThat(count("access_logs", prepared)).isZero();
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

    private static Incident incident(UUID id, UUID request, String time) {
        return new Incident(id, OffsetDateTime.parse(time), Set.of(request), "Test engine result");
    }

    private UUID prepare() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(control("PUT", path(id), null).statusCode()).isEqualTo(201);
        return id;
    }

    private String state(UUID id) throws Exception {
        return body(control("GET", path(id), null)).get("state").asString();
    }

    private JsonNode completed(UUID id) {
        return terminal(id, "COMPLETED");
    }

    private JsonNode failed(UUID id) {
        JsonNode result = terminal(id, "FAILED");
        assertThat(result.get("observedRequestIds").isNull()).isTrue();
        assertThat(result.get("error").asString()).isNotBlank();
        return result;
    }

    private JsonNode terminal(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
                .untilAsserted(() -> assertThat(state(id)).isEqualTo(expected));
        try {
            return body(control("GET", path(id), null));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private long count(String table, UUID id) {
        return admin.sql("SELECT count(*) FROM detector." + table + " WHERE run_id = :id")
                .param("id", id).query(Long.class).single();
    }

    private int progress(UUID id, String field) {
        return admin.sql("SELECT " + field + " FROM detector.producer_progress WHERE run_id = :id AND producer = 'GATEWAY'")
                .param("id", id).query(Integer.class).single();
    }

    private HttpResponse<String> ingest(UUID id, String producer, ObjectNode... logs) throws Exception {
        return send("POST", path(id) + "/log-batches", producer.equals("GATEWAY") ? "gateway-test" : "shop-test",
                batch(producer, logs));
    }

    private HttpResponse<String> control(String method, String path, String content) throws Exception {
        return send(method, path, "control-test", content);
    }

    private HttpResponse<String> send(String method, String path, String token, String content) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (content != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String path(UUID id) {
        return "/runs/" + id;
    }

    static String finishBody(int gateway, int shop) {
        return finishBody(gateway, shop, Instant.now().plusSeconds(30).toString());
    }

    static String finishBody(int gateway, int shop, String deadline) {
        return "{\"gatewayLastSequence\":" + gateway + ",\"shopLastSequence\":" + shop + ",\"deadlineAt\":\"" + deadline + "\"}";
    }

    static ObjectNode log(int sequence, UUID request) {
        return (ObjectNode) JSON.readTree("""
                {"sequence":%d,"requestId":"%s","time":"2026-11-16T10:00:00Z","remote_addr":"192.0.2.1",
                 "user":null,"method":"GET","path":"/api/v1/products?limit=2","route":null,"status":200,
                 "bytes":10,"request_time":0.1,"user_agent":null,"req_hash":"%s"}
                """.formatted(sequence, request, "a".repeat(64)));
    }

    static String batch(String producer, ObjectNode... entries) {
        var array = JSON.createArrayNode();
        for (ObjectNode entry : entries) {
            array.add(entry);
        }
        return JSON.createObjectNode().put("producer", producer).set("entries", array).toString();
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        var problem = body(response);
        assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "code", "requestId");
        assertThat(problem.get("type").asString()).isEqualTo("urn:lab:problem:http-error");
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(UUID.fromString(problem.get("requestId").asString())).isNotNull();
    }

    private static List<HttpResponse<String>> concurrent(List<Callable<HttpResponse<String>>> operations) throws Exception {
        var ready = new CountDownLatch(operations.size());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(operations.size())) {
            var futures = operations.stream().map(operation -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Barrier timed out");
                }
                return operation.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var responses = new ArrayList<HttpResponse<String>>();
            for (var future : futures) {
                responses.add(future.get(20, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    @TestConfiguration
    static class Engines {
        @Bean
        @Primary
        TestEngine testEngine() {
            return new TestEngine();
        }
    }

    static class TestEngine implements AnalysisEngine {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<Incident> results = List.of();
        volatile List<Observation> observations = List.of();
        volatile boolean block;
        volatile boolean throwError;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile CountDownLatch exited = new CountDownLatch(1);

        void reset() {
            calls.set(0);
            results = List.of();
            observations = List.of();
            block = false;
            throwError = false;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            exited = new CountDownLatch(1);
        }

        @Override
        public List<Incident> analyze(UUID id, Stream<Observation> logs) {
            calls.incrementAndGet();
            observations = logs.toList();
            entered.countDown();
            try {
                if (block && !release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Engine test latch timed out");
                }
                if (throwError) {
                    throw new IllegalStateException("Test engine failure");
                }
                return results;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            } finally {
                exited.countDown();
            }
        }
    }
}
