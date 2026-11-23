package lab.detector;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static lab.detector.DetectorTestData.batch;
import static lab.detector.DetectorTestData.finishBody;
import static lab.detector.DetectorTestData.incident;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorSecurityIntegrationTest extends AbstractDetectorApiIntegrationTest {
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

    @ParameterizedTest
    @ValueSource(strings = {"gateway-test", "shop-test"})
    void producerCannotReadOrManageKnownRunOrReplayAnotherProducer(String token) throws Exception {
        UUID id = prepare();
        ObjectNode entry = log(1, UUID.randomUUID());
        assertThat(ingest(id, "GATEWAY", entry).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "SHOP", entry).statusCode()).isEqualTo(204);
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 1)).statusCode()).isEqualTo(202);
        completed(id);

        assertProblem(send("GET", path(id), token, null), 403, "FORBIDDEN");
        assertProblem(send("GET", path(id) + "/incidents", token, null), 403, "FORBIDDEN");
        assertProblem(send("PUT", path(id), token, null), 403, "FORBIDDEN");
        assertProblem(send("PUT", path(id) + "/finish", token, finishBody(1, 1)), 403, "FORBIDDEN");
        UUID unknown = UUID.randomUUID();
        assertProblem(send("PUT", path(unknown), token, null), 403, "FORBIDDEN");
        String otherProducer = token.equals("gateway-test") ? "SHOP" : "GATEWAY";
        // Even an identical saved replay must pass producer authorization.
        assertProblem(send("POST", path(id) + "/log-batches", token, batch(otherProducer, entry)), 403, "FORBIDDEN");
        assertProblem(send("POST", path(id) + "/log-batches", "control-test", batch("GATEWAY", entry)), 403, "FORBIDDEN");
        assertThat(state(id)).isEqualTo("COMPLETED");
        assertThat(count("access_logs", id)).isEqualTo(2);
        assertThat(count("analysis_runs", unknown)).isZero();
    }

    @Test
    void spoofedRoleHeadersAndRepeatedAuthorizationCannotGrantAccess() throws Exception {
        UUID id = prepare();
        var spoofed = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path(id)))
                .header("Authorization", "Bearer gateway-test").header("X-Role", "CONTROL")
                .header("X-Producer", "CONTROL").GET().build();
        assertProblem(http.send(spoofed, HttpResponse.BodyHandlers.ofString()), 403, "FORBIDDEN");
        var repeated = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path(id)))
                .header("Authorization", "Bearer control-test").header("Authorization", "Bearer gateway-test")
                .GET().build();
        assertProblem(http.send(repeated, HttpResponse.BodyHandlers.ofString()), 401, "UNAUTHORIZED");
        assertProblem(send("GET", path(id) + "?token=control-test", null, null), 401, "UNAUTHORIZED");
    }

    @Test
    void bearerTokensAreAbsentFromResponsesStoredRunDataAndOperationalLogs() throws Exception {
        var events = new CopyOnWriteArrayList<ILoggingEvent>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        String[] secrets = {"control-test", "gateway-test", "shop-test", "unknown-secret-" + UUID.randomUUID()};
        try {
            UUID id = prepare();
            var responses = new ArrayList<HttpResponse<String>>();
            responses.add(control("GET", path(id), null));
            responses.add(ingest(id, "GATEWAY", log(1, id)));
            assertThat(responses.getLast().statusCode()).isEqualTo(204);
            responses.add(send("GET", path(id), secrets[3], null));
            assertProblem(responses.getLast(), 401, "UNAUTHORIZED");
            responses.add(send("GET", path(id), "shop-test", null));
            assertProblem(responses.getLast(), 403, "FORBIDDEN");
            responses.add(send("POST", path(id) + "/log-batches", "gateway-test", "{\"producer\":"));
            assertProblem(responses.getLast(), 400, "INVALID_REQUEST");
            responses.add(control("GET", path(id) + "/incidents", null));
            assertThat(responses.getLast().statusCode()).isEqualTo(200);
            for (String endpoint : List.of("/env", "/configprops")) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + endpoint))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer control-test").GET().build();
                responses.add(http.send(request, HttpResponse.BodyHandlers.ofString()));
                assertThat(responses.getLast().statusCode()).isEqualTo(404);
            }

            // A private exception message must not be copied into logs, status or persisted error.
            engine.failureMessage = String.join("|", secrets);
            engine.throwError = true;
            responses.add(control("PUT", path(id) + "/finish", finishBody(1, 0)));
            assertThat(responses.getLast().statusCode()).isEqualTo(202);
            failed(id);
            responses.add(control("GET", path(id), null));
            for (var response : responses) {
                assertThat(response.body() + response.headers().map()).doesNotContain(secrets);
            }
            List<String> stored = admin.sql("""
                    SELECT row_to_json(r)::text FROM detector.analysis_runs r WHERE run_id = :id
                    UNION ALL SELECT payload::text FROM detector.access_logs WHERE run_id = :id
                    """).param("id", id).query(String.class).list();
            assertThat(String.join("\n", stored)).doesNotContain(secrets);
            assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("Detector analysis failed for run " + id));
            for (var event : events) {
                String throwable = event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
                assertThat(event.getFormattedMessage() + event.getKeyValuePairs() + event.getMDCPropertyMap()
                        + java.util.Arrays.deepToString(event.getArgumentArray()) + throwable).doesNotContain(secrets);
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void runIdsScopeLogsProgressObservedIdsAndIncidents() throws Exception {
        UUID first = prepare();
        UUID second = prepare();
        UUID firstRequest = UUID.randomUUID();
        UUID secondRequest = UUID.randomUUID();
        ObjectNode firstLog = log(1, firstRequest);
        ObjectNode secondLog = log(1, secondRequest);
        UUID incidentId = UUID.randomUUID();
        engine.resultsByRun.put(first, List.of(incident(incidentId, firstRequest, "2026-11-16T10:00:00Z")
                .description("First run")));
        engine.resultsByRun.put(second, List.of());
        assertThat(ingest(first, "GATEWAY", firstLog).statusCode()).isEqualTo(204);
        assertThat(ingest(second, "GATEWAY", secondLog).statusCode()).isEqualTo(204);
        assertThat(ingest(first, "GATEWAY", firstLog).statusCode()).isEqualTo(204);
        assertThat(progress(first, "received_count")).isEqualTo(1);
        assertThat(progress(second, "received_count")).isEqualTo(1);

        assertThat(control("PUT", path(first) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        assertThat(completed(first).get("observedRequestIds").get(0).asString()).isEqualTo(firstRequest.toString());
        assertThat(state(second)).isEqualTo("ACCEPTING");
        assertThat(control("GET", path(second) + "/incidents", null).statusCode()).isEqualTo(200);
        JsonNode firstPage = body(control("GET", path(first) + "/incidents", null));
        assertThat(firstPage.get("total").asLong()).isEqualTo(1);
        assertThat(firstPage.get("items").get(0).get("description").asString()).isEqualTo("First run");

        // The same sequence and incidentId in another run must stay scoped to that run.
        engine.resultsByRun.put(second, List.of(incident(incidentId, secondRequest, "2026-11-16T10:00:00Z")
                .description("Second run")));
        assertThat(control("PUT", path(second) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        assertThat(completed(second).get("observedRequestIds").get(0).asString()).isEqualTo(secondRequest.toString());
        JsonNode secondPage = body(control("GET", path(second) + "/incidents", null));
        assertThat(secondPage.get("total").asLong()).isEqualTo(1);
        assertThat(secondPage.get("items").get(0).get("description").asString()).isEqualTo("Second run");
        assertThat(secondPage.get("items").get(0).get("requestIds").get(0).asString()).isEqualTo(secondRequest.toString());
        assertThat(body(control("GET", path(first) + "/incidents", null))).isEqualTo(firstPage);
        assertThat(body(control("GET", path(first) + "/incidents?runId=" + second, null))).isEqualTo(firstPage);
        assertThat(ingest(second, "GATEWAY", secondLog).statusCode()).isEqualTo(204);
    }

    @Test
    void incidentCannotReferenceEvidenceFromAnotherRun() throws Exception {
        UUID first = prepare();
        UUID second = prepare();
        UUID firstRequest = UUID.randomUUID();
        UUID secondRequest = UUID.randomUUID();
        assertThat(ingest(first, "GATEWAY", log(1, firstRequest)).statusCode()).isEqualTo(204);
        assertThat(ingest(second, "GATEWAY", log(1, secondRequest)).statusCode()).isEqualTo(204);
        engine.results = List.of(incident(UUID.randomUUID(), secondRequest, "2026-11-16T10:00:00Z"));
        control("PUT", path(first) + "/finish", finishBody(1, 0));
        failed(first);
        assertThat(count("incidents", first)).isZero();
        assertThat(state(second)).isEqualTo("ACCEPTING");
        assertThat(count("access_logs", second)).isEqualTo(1);
    }

    @Test
    void sqlStringsInLogsAndIncidentDescriptionRemainData() throws Exception {
        UUID id = prepare();
        UUID other = prepare();
        UUID request = UUID.randomUUID();
        String attack = "'; UPDATE detector.analysis_runs SET state='FAILED' WHERE run_id='" + other + "'; --";
        ObjectNode entry = log(1, request).put("path", "/api/v1/products?q=" + attack)
                .put("route", "/products/" + attack).put("user_agent", attack);
        assertThat(ingest(id, "GATEWAY", entry).statusCode()).isEqualTo(204);
        assertThat(ingest(id, "GATEWAY", entry).statusCode()).isEqualTo(204);
        assertThat(state(other)).isEqualTo("ACCEPTING");
        assertThat(count("access_logs", other)).isZero();
        String stored = admin.sql("SELECT payload FROM detector.access_logs WHERE run_id = :id")
                .param("id", id).query(String.class).single();
        assertThat(JSON.readTree(stored).get("path").asString()).isEqualTo(entry.get("path").asString());

        engine.results = List.of(incident(UUID.randomUUID(), request, "2026-11-16T10:00:00Z").description(attack));
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        completed(id);
        JsonNode page = body(control("GET", path(id) + "/incidents", null));
        assertThat(page.get("items").get(0).get("description").asString()).isEqualTo(attack);
        assertThat(engine.observations.getFirst().payload().get("user_agent").asString()).isEqualTo(attack);
        assertThat(state(other)).isEqualTo("ACCEPTING");
        assertThat(count("producer_progress", other)).isEqualTo(2);
        assertThat(count("incidents", other)).isZero();
    }

    @Test
    void sqlFragmentsInIdentifiersPaginationAndTypedFieldsAreRejectedBeforeStorage() throws Exception {
        UUID id = prepare();
        String attack = "' OR 1=1; UPDATE detector.analysis_runs SET state='FAILED'; --";
        String encoded = java.net.URLEncoder.encode(attack, java.nio.charset.StandardCharsets.UTF_8);
        for (String method : List.of("GET", "PUT")) {
            assertProblem(control(method, path(id) + encoded, null), 400, "INVALID_REQUEST");
        }
        for (String query : List.of("?limit=" + encoded, "?offset=" + encoded)) {
            assertProblem(control("GET", path(id) + "/incidents" + query, null), 400, "INVALID_REQUEST");
        }
        ObjectNode entry = log(1, UUID.randomUUID());
        for (String field : List.of("sequence", "requestId", "user")) {
            assertProblem(ingest(id, "GATEWAY", entry.deepCopy().put(field, attack)), 400, "INVALID_REQUEST");
        }
        ObjectNode badProducer = (ObjectNode) JSON.readTree(batch("GATEWAY", entry));
        badProducer.put("producer", "GATEWAY" + attack);
        assertProblem(send("POST", path(id) + "/log-batches", "gateway-test", badProducer.toString()), 400, "INVALID_REQUEST");
        ObjectNode spoofedRun = (ObjectNode) JSON.readTree(batch("GATEWAY", entry));
        spoofedRun.put("runId", UUID.randomUUID().toString());
        assertProblem(send("POST", path(id) + "/log-batches", "gateway-test", spoofedRun.toString()), 400, "INVALID_REQUEST");
        assertThat(state(id)).isEqualTo("ACCEPTING");
        assertThat(count("access_logs", id)).isZero();
        assertThat(progress(id, "received_count")).isZero();
    }
}
