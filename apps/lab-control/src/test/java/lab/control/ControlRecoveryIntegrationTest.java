package lab.control;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ControlRecoveryIntegrationTest {
    private static final String BODY = "{\"families\":[\"BOLA\"]}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    private static final PostgreSQLContainer POSTGRES = ControlTestDatabase.container();

    @Test
    void restartPreservesTrialAndKeyAndBlocksUnsafeNewTrial() throws Exception {
        ControlTestDatabase.migrate(POSTGRES);
        UUID key = UUID.randomUUID();
        JsonNode original;
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            try (var application = startControl()) {
                var created = post(application, client, key);
                assertThat(created.statusCode()).isEqualTo(202);
                original = JSON.readTree(created.body());
                assertThat(original.get("state").asString()).isEqualTo("PREPARING");
            }
            try (var application = startControl()) {
                assertThat(application.getBean(JdbcClient.class).sql("SELECT current_user")
                        .query(String.class).single()).isEqualTo("control_app");
                var repeated = post(application, client, key);
                assertThat(repeated.statusCode()).isEqualTo(200);
                JsonNode recovered = JSON.readTree(repeated.body());
                assertThat(recovered.get("trialId")).isEqualTo(original.get("trialId"));
                assertThat(recovered.get("seed")).isEqualTo(original.get("seed"));
                assertThat(recovered.get("state").asString()).isEqualTo("INCONCLUSIVE");
                assertThat(recovered.get("cleanupSafe").asBoolean()).isFalse();
                assertThat(post(application, client, UUID.randomUUID()).statusCode()).isEqualTo(409);
            }
        }
        JdbcClient admin = ControlTestDatabase.admin(POSTGRES);
        assertThat(admin.sql("SELECT count(*) FROM control.trials").query(Long.class).single()).isEqualTo(1);
        assertThat(admin.sql("SELECT trial_id FROM control.execution_slot WHERE id = 1").query(UUID.class).single())
                .isEqualTo(UUID.fromString(original.get("trialId").asString()));
    }

    private static ConfigurableApplicationContext startControl() {
        return new SpringApplicationBuilder(ControlApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=control_app",
                "--spring.datasource.password=" + ControlTestDatabase.CONTROL_PASSWORD,
                "--lab.control.operator-token=recovery-token");
    }

    private static HttpResponse<String> post(ConfigurableApplicationContext application, HttpClient client, UUID key)
            throws Exception {
        int port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/control/v1/trials"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer recovery-token")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString(BODY)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
