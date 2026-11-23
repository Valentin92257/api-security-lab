package lab.detector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lab.contracts.json.ContractJsonSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static lab.detector.DetectorTestData.batch;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** HTTP setup and helpers; each concrete class owns its Spring context and HTTP client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lab.detector.control-token=control-test", "lab.detector.gateway-token=gateway-test",
        "lab.detector.shop-token=shop-test", "lab.detector.poll-interval-ms=20", "lab.detector.max-incidents=5"
})
@Import(AbstractDetectorApiIntegrationTest.ApiTestConfiguration.class)
public abstract class AbstractDetectorApiIntegrationTest extends AbstractDetectorDatabaseTest {
    protected static final JsonMapper JSON = ContractJsonSupport.createJsonMapper();

    @LocalServerPort
    protected int port;
    @Autowired
    protected HttpClient http;
    @Autowired
    protected DetectorTestEngine engine;

    @BeforeEach
    protected final void resetEngine() {
        engine.reset();
    }

    @AfterEach
    protected final void releaseEngine() {
        engine.release.countDown();
    }

    protected UUID prepare() throws Exception {
        UUID id = UUID.randomUUID();
        assertThat(control("PUT", path(id), null).statusCode()).isEqualTo(201);
        return id;
    }

    protected String state(UUID id) throws Exception {
        return body(control("GET", path(id), null)).get("state").asString();
    }

    protected JsonNode completed(UUID id) {
        return terminal(id, "COMPLETED");
    }

    protected JsonNode failed(UUID id) {
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

    protected long count(String table, UUID id) {
        return admin.sql("SELECT count(*) FROM detector." + table + " WHERE run_id = :id")
                .param("id", id).query(Long.class).single();
    }

    protected int progress(UUID id, String field) {
        return admin.sql("SELECT " + field + " FROM detector.producer_progress WHERE run_id = :id AND producer = 'GATEWAY'")
                .param("id", id).query(Integer.class).single();
    }

    protected HttpResponse<String> ingest(UUID id, String producer, ObjectNode... logs) throws Exception {
        return send("POST", path(id) + "/log-batches", producer.equals("GATEWAY") ? "gateway-test" : "shop-test",
                batch(producer, logs));
    }

    protected HttpResponse<String> control(String method, String path, String content) throws Exception {
        return send(method, path, "control-test", content);
    }

    protected HttpResponse<String> send(String method, String path, String token, String content) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (content != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    protected static void assertProblem(HttpResponse<String> response, int status, String code) {
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

    protected static List<HttpResponse<String>> concurrent(List<Callable<HttpResponse<String>>> operations) throws Exception {
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

    @TestConfiguration(proxyBeanMethods = false)
    static class ApiTestConfiguration {
        @Bean(destroyMethod = "close")
        HttpClient detectorTestHttpClient() {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }

        @Bean
        @Primary
        DetectorTestEngine testEngine() {
            return new DetectorTestEngine();
        }
    }
}
