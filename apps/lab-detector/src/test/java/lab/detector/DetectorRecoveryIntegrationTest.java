package lab.detector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static lab.detector.DetectorApiIntegrationTest.batch;
import static lab.detector.DetectorApiIntegrationTest.finishBody;
import static lab.detector.DetectorApiIntegrationTest.log;
import static lab.detector.DetectorApiIntegrationTest.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class DetectorRecoveryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = DetectorTestDatabase.container();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void restartFailsUnfinishedRunsWithoutResumingAndPreservesCompletedData() throws Exception {
        UUID accepting = UUID.randomUUID();
        UUID finishing = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        String logs = batch("GATEWAY", log(1, request));
        String boundaries = finishBody(2, 0);
        String originalInstance;
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            try (var app = start()) {
                var prepared = send(app, client, "PUT", path(accepting), "control", null);
                assertThat(prepared.statusCode()).isEqualTo(201);
                originalInstance = body(prepared).get("instanceId").asString();
                assertThat(send(app, client, "PUT", path(finishing), "control", null).statusCode()).isEqualTo(201);
                assertThat(send(app, client, "POST", path(finishing) + "/log-batches", "gateway", logs).statusCode()).isEqualTo(204);
                assertThat(send(app, client, "PUT", path(finishing) + "/finish", "control", boundaries).statusCode()).isEqualTo(202);
                assertThat(send(app, client, "PUT", path(completed), "control", null).statusCode()).isEqualTo(201);
                assertThat(send(app, client, "POST", path(completed) + "/log-batches", "gateway", logs).statusCode()).isEqualTo(204);
                send(app, client, "PUT", path(completed) + "/finish", "control", finishBody(1, 0));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                        body(send(app, client, "GET", path(completed), "control", null)).get("state").asString())
                        .isEqualTo("COMPLETED"));
            }
            try (var app = start()) {
                for (UUID id : new UUID[] {accepting, finishing}) {
                    JsonNode status = body(send(app, client, "GET", path(id), "control", null));
                    assertThat(status.get("state").asString()).isEqualTo("FAILED");
                    assertThat(status.get("observedRequestIds").isNull()).isTrue();
                    assertThat(status.get("instanceId").asString()).isNotEqualTo(originalInstance);
                    assertThat(status.get("error").asString()).contains("restarted");
                    assertThat(body(send(app, client, "PUT", path(id), "control", null)).get("state").asString())
                            .isEqualTo("FAILED");
                }
                assertThat(send(app, client, "POST", path(finishing) + "/log-batches", "gateway", logs).statusCode()).isEqualTo(204);
                assertThat(send(app, client, "POST", path(finishing) + "/log-batches", "gateway",
                        batch("GATEWAY", log(2, request))).statusCode()).isEqualTo(409);
                assertThat(body(send(app, client, "PUT", path(finishing) + "/finish", "control", boundaries))
                        .get("state").asString()).isEqualTo("FAILED");
                JsonNode preserved = body(send(app, client, "GET", path(completed), "control", null));
                assertThat(preserved.get("state").asString()).isEqualTo("COMPLETED");
                assertThat(preserved.get("observedRequestIds").get(0).asString()).isEqualTo(request.toString());
                assertThat(send(app, client, "GET", path(completed) + "/incidents", "control", null).statusCode()).isEqualTo(200);
            }
        }
        assertThat(DetectorTestDatabase.admin(POSTGRES).sql("SELECT count(*) FROM detector.access_logs")
                .query(Long.class).single()).isEqualTo(2);
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(DetectorApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + DetectorTestDatabase.url(POSTGRES),
                "--spring.datasource.password=" + DetectorTestDatabase.APP_PASSWORD,
                "--spring.flyway.password=" + DetectorTestDatabase.MIGRATION_PASSWORD,
                "--lab.detector.control-token=control", "--lab.detector.gateway-token=gateway",
                "--lab.detector.shop-token=shop", "--lab.detector.poll-interval-ms=20");
    }

    private static HttpResponse<String> send(ConfigurableApplicationContext app, HttpClient client,
                                              String method, String path, String token, String content) throws Exception {
        int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/internal/v1" + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token);
        if (content != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
