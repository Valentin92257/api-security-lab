package lab.control;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "lab.control.operator-token=integration-token")
@Testcontainers
class ControlApiIntegrationTest {
    private static final String BODY = "{\"families\":[\"BOLA\",\"REPLAY\"],\"seed\":42}";

    @Container
    private static final PostgreSQLContainer POSTGRES = ControlTestDatabase.container();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JdbcClient admin;

    @BeforeAll
    static void prepareDatabase() {
        ControlTestDatabase.migrate(POSTGRES);
        admin = ControlTestDatabase.admin(POSTGRES);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient runtime;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.password", () -> ControlTestDatabase.CONTROL_PASSWORD);
    }

    @BeforeEach
    void emptyDatabase() {
        admin.sql("TRUNCATE control.execution_slot, control.trials").update();
        admin.sql("INSERT INTO control.execution_slot (id) VALUES (1)").update();
    }

    @AfterAll
    static void closeHttpClient() {
        HTTP.close();
    }

    @Test
    void createsReadsAndListsTrial() throws Exception {
        var response = post(UUID.randomUUID(), BODY);
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        JsonNode trial = body(response);
        String id = trial.get("trialId").asString();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(trial.get("seed").asInt()).isEqualTo(42);
        assertThat(trial.get("state").asString()).isEqualTo("PREPARING");
        assertThat(trial.get("cleanupSafe").asBoolean()).isFalse();
        assertThat(trial.get("plannedSteps").asLong()).isZero();
        assertThat(trial.get("finishedSteps").asLong()).isZero();
        assertThat(body(get("/trials/" + id))).isEqualTo(trial);
        JsonNode page = body(get("/trials?limit=1&offset=0"));
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(body(get("/trials?offset=1")).get("items").size()).isZero();
        assertThat(body(get("/scenarios")).size()).isEqualTo(5);
        assertThat(get("/trials/" + id + "/report").statusCode()).isEqualTo(409);
        assertThat(send("POST", "/trials/" + id + "/cancel", null, "integration-token", "").statusCode())
                .isEqualTo(503);
    }

    @Test
    void replayPrecedesBusySlotAndKeepsTheChosenSeed() throws Exception {
        UUID key = UUID.randomUUID();
        String original = "{\"families\":[\"REPLAY\",\"BOLA\"]}";
        JsonNode first = body(post(key, original));
        var repeated = post(key, "{\"families\":[\"BOLA\",\"REPLAY\"]}");
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(body(repeated)).isEqualTo(first);
        assertThat(first.get("seed").asInt()).isNotNegative();
        assertThat(post(key, "{\"families\":[\"BOLA\",\"REPLAY\"],\"seed\":" + first.get("seed").asInt() + "}")
                .statusCode()).isEqualTo(409);
        assertThat(post(UUID.randomUUID(), original).statusCode()).isEqualTo(409);
        assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateOneTrial() throws Exception {
        var results = concurrentPosts(true);
        assertThat(results.stream().filter(r -> r.statusCode() == 202).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(7);
        assertThat(results.stream().map(r -> body(r).get("trialId").asString()).distinct().count()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRequestsReserveOnlyOneSlot() throws Exception {
        var results = concurrentPosts(false);
        assertThat(results.stream().filter(r -> r.statusCode() == 202).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.statusCode() == 409).count()).isEqualTo(7);
        assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void persistenceFailureRollsBackTrialKeyAndSlot() throws Exception {
        admin.sql("""
                CREATE FUNCTION control.reject_slot() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test persistence failure'; END $$
                """).update();
        admin.sql("""
                CREATE TRIGGER reject_slot BEFORE UPDATE ON control.execution_slot
                FOR EACH ROW EXECUTE FUNCTION control.reject_slot()
                """).update();
        try {
            var response = post(UUID.randomUUID(), BODY);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(body(response).get("code").asString()).isEqualTo("UNAVAILABLE");
            assertThat(response.body()).doesNotContain("test persistence failure");
            assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isZero();
            assertThat(admin.sql("SELECT trial_id IS NULL FROM control.execution_slot").query(Boolean.class).single()).isTrue();
        } finally {
            admin.sql("DROP TRIGGER reject_slot ON control.execution_slot").update();
            admin.sql("DROP FUNCTION control.reject_slot()").update();
        }
        assertThat(post(UUID.randomUUID(), BODY).statusCode()).isEqualTo(202);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]", "{\"families\":null}", "{\"families\":[]}",
            "{\"families\":[\"BOLA\",\"BOLA\"]}", "{\"families\":[null]}",
            "{\"families\":[\"UNKNOWN\"]}", "{\"families\":[1]}",
            "{\"families\":[\"BOLA\"],\"seed\":null}", "{\"families\":[\"BOLA\"],\"seed\":-1}",
            "{\"families\":[\"BOLA\"],\"seed\":2147483648}", "{\"families\":[\"BOLA\"],\"seed\":1.5}",
            "{\"families\":[\"BOLA\"],\"seed\":1.000000000000000001}",
            "{\"families\":[\"BOLA\"],\"seed\":2147483647.000000001}",
            "{\"families\":[\"BOLA\"],\"seed\":\"42\"}", "{\"families\":[\"BOLA\"],\"target\":\"url\"}",
            "{\"families\":[\"BOLA\"],\"seed\":1,\"seed\":2}", "{\"families\":[\"BOLA\"]} {}"
    })
    void rejectsInvalidInputBeforeReservingSlot(String input) throws Exception {
        var response = post(UUID.randomUUID(), input);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1.0", "1e2", "2147483647.0"})
    void acceptsIntegerSeedRegardlessOfJsonNumberNotation(String seed) throws Exception {
        UUID key = UUID.randomUUID();
        int expected = new BigDecimal(seed).intValueExact();
        var created = post(key, "{\"families\":[\"BOLA\"],\"seed\":" + seed + "}");
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(body(created).get("seed").asInt()).isEqualTo(expected);
        var replay = post(key, "{\"seed\":" + expected + ",\"families\":[\"BOLA\"]}");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(body(replay)).isEqualTo(body(created));
    }

    @Test
    void rejectsInvalidHeadersPagingAndOversizedBody() throws Exception {
        assertThat(send("POST", "/trials", null, "integration-token", BODY).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/trials", "1-1-1-1-1", "integration-token", BODY).statusCode()).isEqualTo(400);
        assertThat(get("/trials?limit=0").statusCode()).isEqualTo(400);
        assertThat(get("/trials?limit=101").statusCode()).isEqualTo(400);
        assertThat(get("/trials?offset=-1").statusCode()).isEqualTo(400);
        assertThat(get("/trials/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(post(UUID.randomUUID(), " ".repeat(8193) + BODY).statusCode()).isEqualTo(413);
        assertThat(post(UUID.randomUUID(), "{\"families\":[\"BOLA\"],\"seed\":2147483647}").statusCode()).isEqualTo(202);
    }

    @Test
    void requiresOperatorBearerAndKeepsHealthPublic() throws Exception {
        for (String token : new String[]{null, "shop-token"}) {
            var response = send("GET", "/trials", null, token, null);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(body(response).get("requestId").asString()).isNotBlank();
            assertThat(response.headers().firstValue("www-authenticate")).contains("Bearer");
        }
        var health = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "bEaReR"})
    void acceptsBearerSchemeWithoutChangingTokenCase(String scheme) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/control/v1/trials"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", scheme + "  integration-token").GET().build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/trials", null, "INTEGRATION-TOKEN", null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsRepeatedCredentialsAndIdempotencyHeaders() throws Exception {
        URI uri = URI.create("http://localhost:" + port + "/control/v1/trials");
        var authorization = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer integration-token")
                .header("Authorization", "Bearer integration-token").GET().build();
        assertProblem(HTTP.send(authorization, HttpResponse.BodyHandlers.ofString()), 401, "UNAUTHORIZED");
        String key = UUID.randomUUID().toString();
        var idempotency = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer integration-token").header("Content-Type", "application/json")
                .header("Idempotency-Key", key).header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertProblem(HTTP.send(idempotency, HttpResponse.BodyHandlers.ofString()), 400, "INVALID_REQUEST");
        assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isZero();
    }

    @Test
    void returnsContractProblemForFrameworkErrors() throws Exception {
        assertProblem(send("PUT", "/trials", null, "integration-token", null), 405, "INVALID_REQUEST");
        assertProblem(get("/missing"), 404, "NOT_FOUND");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/control/v1/trials"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer integration-token")
                .header("Content-Type", "text/plain").header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertProblem(HTTP.send(request, HttpResponse.BodyHandlers.ofString()), 415, "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/problem+json", "text/plain",
            "application/json;q=0,*/*;q=1", "application/*;q=0,*/*;q=1"
    })
    void rejectsUnsupportedAcceptBeforeCreatingTrial(String accept) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/control/v1/trials"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer integration-token")
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        assertProblem(HTTP.send(request, HttpResponse.BodyHandlers.ofString()), 406, "INVALID_REQUEST");
        assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isZero();
        assertThat(admin.sql("SELECT trial_id IS NULL FROM control.execution_slot WHERE id = 1")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void returnsSuccessAsJsonWhenClientAlsoAcceptsProblem() throws Exception {
        String base = "http://localhost:" + port + "/control/v1";
        var request = HttpRequest.newBuilder(URI.create(base + "/trials")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer integration-token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/problem+json, application/json;q=0.5")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        var created = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(created.headers().firstValue("Content-Type")).contains("application/json");
        String id = body(created).get("trialId").asString();
        for (String path : List.of("/trials", "/trials/" + id, "/scenarios")) {
            var get = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer integration-token")
                    .header("Accept", "application/problem+json, application/json;q=0.5").GET().build();
            var response = HTTP.send(get, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        }
    }

    @Test
    void migrationsRepeatSafelyAndRuntimeCannotModifySchemaOrKeys() {
        ControlTestDatabase.migrate(POSTGRES);
        assertThat(runtime.sql("SELECT current_user").query(String.class).single()).isEqualTo("control_app");
        assertThat(admin.sql("SELECT has_schema_privilege('control_app', 'control', 'CREATE')")
                .query(Boolean.class).single()).isFalse();
        assertThat(admin.sql("SELECT has_column_privilege('control_app', 'control.trials', 'idempotency_key', 'UPDATE')")
                .query(Boolean.class).single()).isFalse();
        assertThat(admin.sql("SELECT has_column_privilege('control_app', 'control.trials', 'families', 'UPDATE')")
                .query(Boolean.class).single()).isFalse();
        assertThat(admin.sql("SELECT has_table_privilege('control_app', 'control.flyway_schema_history', 'SELECT')")
                .query(Boolean.class).single()).isFalse();
    }

    private List<HttpResponse<String>> concurrentPosts(boolean sameKey) throws Exception {
        UUID key = UUID.randomUUID();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<HttpResponse<String>>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test start barrier timed out");
                    }
                    return post(sameKey ? key : UUID.randomUUID(), BODY);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<HttpResponse<String>>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private HttpResponse<String> post(UUID key, String body) throws Exception {
        return send("POST", "/trials", key.toString(), "integration-token", body);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send("GET", path, null, "integration-token", null);
    }

    private HttpResponse<String> send(String method, String path, String key, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/control/v1" + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode problem = body(response);
        assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "code", "requestId");
        assertThat(problem.get("type").asString()).isEqualTo("urn:lab:problem:http-error");
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("title").asString()).isNotBlank().hasSizeLessThanOrEqualTo(240);
        assertThat(UUID.fromString(problem.get("requestId").asString())).isNotNull();
    }
}
