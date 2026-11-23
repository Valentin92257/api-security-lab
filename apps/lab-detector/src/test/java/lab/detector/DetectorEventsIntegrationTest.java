package lab.detector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import lab.contracts.detector.model.EventPage;
import lab.contracts.json.ContractJsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static lab.detector.DetectorTestData.finishBody;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DetectorEventsIntegrationTest extends AbstractDetectorApiIntegrationTest {
    private final ContractJsonCodec codec = new ContractJsonCodec();

    @Test
    void readsBothProducersAcrossRunsAndKeepsTheFullAccessLog() throws Exception {
        UUID first = prepare();
        UUID second = prepare();
        UUID request = UUID.randomUUID();
        ObjectNode entry = log(1, request);
        assertThat(ingest(first, "GATEWAY", entry).statusCode()).isEqualTo(204);
        assertThat(ingest(first, "SHOP", entry).statusCode()).isEqualTo(204);
        assertThat(ingest(second, "SHOP", entry).statusCode()).isEqualTo(204);

        JsonNode page = events("");
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor");
        assertThat(page.get("items")).hasSize(3);
        for (var item : page.get("items")) {
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("runId", "producer", "log");
            assertThat(item.get("log")).isEqualTo(entry);
        }
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(events("runId=" + first).get("items")).hasSize(2);
        assertThat(events("runId=" + second).get("items")).hasSize(1);
        assertThat(events("requestId=" + request).get("items")).hasSize(3);
    }

    @Test
    void combinesFiltersAndTreatsStatusValuesAsAlternatives() throws Exception {
        UUID first = prepare();
        UUID second = prepare();
        UUID request = UUID.randomUUID();
        ObjectNode matched = log(1, request).put("method", "POST").put("status", 401)
                .put("route", "/api/v1/auth/login").put("remote_addr", "192.0.2.7").put("user", 17);
        assertThat(ingest(first, "SHOP", matched, matched.deepCopy().put("sequence", 2).put("status", 403),
                matched.deepCopy().put("sequence", 3).put("status", 500)).statusCode()).isEqualTo(204);
        assertThat(ingest(first, "GATEWAY", matched).statusCode()).isEqualTo(204);
        assertThat(ingest(second, "SHOP", matched).statusCode()).isEqualTo(204);

        String filters = "runId=" + first + "&producer=SHOP&requestId=" + request
                + "&method=POST&route=/api/v1/auth/login&remoteAddr=192.0.2.7&userId=17";
        assertThat(events(filters + "&status=401&status=403").get("items")).hasSize(2);
        assertThat(events(filters + "&statusClass=4xx").get("items")).hasSize(2);
        assertThat(events(filters + "&status=500").get("items")).hasSize(1);
        for (String mismatch : List.of("method=GET", "route=/other", "remoteAddr=192.0.2.8", "userId=18",
                "requestId=" + UUID.randomUUID(), "status=201")) {
            assertThat(events("runId=" + first + "&" + mismatch).get("items")).isEmpty();
        }
    }

    @Test
    void appliesInclusiveFromAndExclusiveToWithoutLosingNanoseconds() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY",
                timed(1, "2026-11-16T10:00:00.123455999Z"),
                timed(2, "2026-11-16T10:00:00.123456001Z"),
                timed(3, "2026-11-16T10:00:00.123456002Z"),
                timed(4, "2026-11-16T10:00:01Z")).statusCode()).isEqualTo(204);
        var page = events("from=2026-11-16T10:00:00.123456001Z&to=2026-11-16T10:00:00.123456002Z");
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("log").get("sequence").asInt()).isEqualTo(2);
        assertThat(events("to=2026-11-16T10:00:00.123456002Z").get("items")).hasSize(2);
        assertThat(events("from=2026-11-16T10:00:00.123456002Z").get("items")).hasSize(2);
        assertThat(events("from=" + encoded("2026-11-16T13:00:00.123456001+03:00")
                + "&to=2026-11-16T10:00:00.123456002Z")).isEqualTo(page);
    }

    @Test
    void paginatesTiesAcrossRunsProducersAndSequencesWithoutDuplicatesOrGaps() throws Exception {
        UUID low = new UUID(0, 1);
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        for (UUID id : List.of(low, high)) {
            assertThat(control("PUT", path(id), null).statusCode()).isEqualTo(201);
        }
        String tie = "2026-11-16T10:00:00.123456001Z";
        assertThat(ingest(low, "GATEWAY", timed(1, tie), timed(2, "2026-11-16T10:00:00.123455999Z"))
                .statusCode()).isEqualTo(204);
        assertThat(ingest(low, "SHOP", timed(1, "2026-11-16T10:00:00.123456002Z")).statusCode()).isEqualTo(204);
        assertThat(ingest(high, "GATEWAY", timed(1, tie)).statusCode()).isEqualTo(204);
        assertThat(ingest(high, "SHOP", timed(1, tie), timed(2, tie)).statusCode()).isEqualTo(204);
        control("PUT", path(low) + "/finish", finishBody(2, 1));
        completed(low);
        control("PUT", path(high) + "/finish", finishBody(1, 2));
        completed(high);

        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            JsonNode page = events("limit=1" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.get("items")).hasSize(1);
            seen.add(key(page.get("items").get(0)));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            assertThat(seen.size()).isLessThanOrEqualTo(6);
        } while (cursor != null);
        assertThat(seen).containsExactly(low + "/SHOP/1", high + "/SHOP/2", high + "/SHOP/1",
                high + "/GATEWAY/1", low + "/GATEWAY/1", low + "/GATEWAY/2");
    }

    @Test
    void bindsCursorToNormalizedFiltersAndAllowsChangingPageSize() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id).put("status", 401),
                log(2, id).put("status", 403), log(3, id).put("status", 401)).statusCode()).isEqualTo(204);
        String filters = "runId=" + id + "&status=401&status=403&from=2026-11-16T10:00:00Z";
        String cursor = events(filters + "&limit=1").get("nextCursor").asString();
        var second = events("runId=" + id + "&status=403&status=401&status=401&from="
                + encoded("2026-11-16T13:00:00+03:00") + "&limit=2&cursor=" + cursor);
        assertThat(second.get("items")).hasSize(2);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        for (String changed : List.of("", "runId=" + id, filters.replace("403", "404"),
                filters + "&producer=GATEWAY", filters.replace(id.toString(), UUID.randomUUID().toString()))) {
            assertProblem(control("GET", "/events?" + changed + "&cursor=" + cursor, null), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void rejectsMalformedCursorContentsWithoutChangingRunState() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id), log(2, id)).statusCode()).isEqualTo(204);
        String cursor = events("limit=1").get("nextCursor").asString();
        var valid = (ObjectNode) JSON.readTree(Base64.getUrlDecoder().decode(cursor));
        var invalid = List.of(valid.deepCopy().put("version", 2), valid.deepCopy().put("sequence", 0),
                valid.deepCopy().put("sequence", 100001), valid.deepCopy().put("producer", "OTHER"),
                valid.deepCopy().putNull("producer"), valid.deepCopy().putNull("time"),
                valid.deepCopy().put("time", Instant.MIN.toString()),
                valid.deepCopy().put("time", Instant.MAX.toString()),
                valid.deepCopy().putNull("runId"), valid.deepCopy().put("filterHash", "wrong"),
                valid.deepCopy().put("extra", true));
        for (var value : invalid) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(value));
            assertProblem(control("GET", "/events?cursor=" + token, null), 400, "INVALID_REQUEST");
        }
        assertThat(state(id)).isEqualTo("ACCEPTING");
    }

    @Test
    void returnsEmptyPagesOrNotFoundAndReadsTerminalRuns() throws Exception {
        assertThat(events("").get("items")).isEmpty();
        UUID id = prepare();
        assertThat(events("runId=" + id).get("items")).isEmpty();
        assertProblem(control("GET", "/events?runId=" + UUID.randomUUID(), null), 404, "NOT_FOUND");
        assertThat(ingest(id, "GATEWAY", log(1, id)).statusCode()).isEqualTo(204);
        control("PUT", path(id) + "/finish", finishBody(1, 0));
        completed(id);
        UUID failed = prepare();
        assertThat(ingest(failed, "SHOP", log(1, failed)).statusCode()).isEqualTo(204);
        control("PUT", path(failed) + "/finish", finishBody(0, 1, "2020-01-01T00:00:00Z"));
        failed(failed);
        assertThat(events("").get("items")).hasSize(2);
        assertThat(events("runId=" + failed).get("items")).hasSize(1);
    }

    @Test
    void newEventsRequireRefreshingPagesDuringActiveDelivery() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", timed(1, "2026-11-16T10:00:01Z"),
                timed(3, "2026-11-16T10:00:03Z")).statusCode()).isEqualTo(204);
        String cursor = events("limit=1").get("nextCursor").asString();
        assertThat(ingest(id, "GATEWAY", timed(2, "2026-11-16T10:00:04Z")).statusCode()).isEqualTo(204);
        assertThat(events("limit=1&cursor=" + cursor).get("items").get(0).get("log").get("sequence").asInt())
                .isEqualTo(1);
        assertThat(events("limit=1").get("items").get(0).get("log").get("sequence").asInt()).isEqualTo(2);
        assertThat(state(id)).isEqualTo("ACCEPTING");
    }

    @Test
    void readFailureDoesNotFailTheAnalysis() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id)).statusCode()).isEqualTo(204);
        // Isolate the HTTP read failure from a background analysis read failure.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(admin.sql("""
                SELECT processed_revision = input_revision FROM detector.analysis_runs WHERE run_id = :id
                """).param("id", id).query(Boolean.class).single()).isTrue());
        admin.sql("REVOKE SELECT ON detector.access_logs FROM detector_app").update();
        try {
            assertProblem(control("GET", "/events?runId=" + id, null), 503, "UNAVAILABLE");
        } finally {
            admin.sql("GRANT SELECT ON detector.access_logs TO detector_app").update();
        }
        assertThat(state(id)).isEqualTo("ACCEPTING");
        assertThat(events("runId=" + id).get("items")).hasSize(1);
    }

    @Test
    void enforcesControlRoleAndTreatsSqlInFiltersAsData() throws Exception {
        assertProblem(send("GET", "/events", null, null), 401, "UNAUTHORIZED");
        assertProblem(send("GET", "/events", "unknown", null), 401, "UNAUTHORIZED");
        for (String token : List.of("gateway-test", "shop-test")) {
            assertProblem(send("GET", "/events", token, null), 403, "FORBIDDEN");
        }
        UUID id = prepare();
        String route = "/'; UPDATE detector.analysis_runs SET state='FAILED'; --";
        assertThat(ingest(id, "GATEWAY", log(1, id).put("route", route)).statusCode()).isEqualTo(204);
        assertThat(events("route=" + encoded(route)).get("items")).hasSize(1);
        assertThat(state(id)).isEqualTo("ACCEPTING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=101", "limit=no", "limit=", "limit=1&limit=2", "offset=1",
            "unknown=1", "runId=no", "runId=", "requestId=no", "producer=OTHER", "producer=SHOP&producer=SHOP",
            "method=get", "status=99", "status=600", "status=no", "status=", "status=200&statusClass=2xx",
            "statusClass=6xx", "route=wrong", "remoteAddr=x", "userId=0", "userId=9007199254740992",
            "from=no", "from=", "from=2026-11-16T10:00:00", "from=2026-11-16T10:00:00Z&to=2026-11-16T10:00:00Z",
            "from=2026-11-17T10:00:00Z&to=2026-11-16T10:00:00Z", "cursor=", "cursor=broken", "cursor=e30"})
    void rejectsInvalidParameters(String query) throws Exception {
        assertProblem(control("GET", "/events?" + query, null), 400, "INVALID_REQUEST");
    }

    @ParameterizedTest
    @CsvSource({
            "from=2026-11-16T10:00:00Z&to=2026-11-16T10:00:00Z, from должен быть меньше to",
            "status=401&statusClass=4xx, status и statusClass нельзя использовать одновременно",
            "offset=0, Неизвестный параметр просмотра событий",
            "limit=1&limit=2, Параметр limit нельзя повторять",
            "limit=, Параметр limit не должен быть пустым"
    })
    void explainsInvalidFilterCombinationsAndQueryParameters(String query, String reason) throws Exception {
        var response = control("GET", "/events?" + query, null);
        assertProblem(response, 400, "INVALID_REQUEST");
        assertThat(body(response).get("title").asString()).isEqualTo(reason);
    }

    private JsonNode events(String query) throws Exception {
        var response = control("GET", "/events" + (query.isEmpty() ? "" : "?" + query), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        codec.read(response.body(), EventPage.class);
        return body(response);
    }

    private static ObjectNode timed(int sequence, String time) {
        return log(sequence, UUID.randomUUID()).put("time", time);
    }

    private static String key(JsonNode item) {
        return item.get("runId").asString() + "/" + item.get("producer").asString()
                + "/" + item.get("log").get("sequence").asInt();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
