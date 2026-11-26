package lab.control;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import lab.contracts.json.ContractJsonCodec;
import lab.control.result.StepResultRepository;
import lab.control.runner.GatewayTarget;
import lab.control.runner.RunContext;
import lab.control.runner.StepExecutor;
import lab.control.runner.ShopContractValidator;
import lab.control.runner.StepOutcome;
import lab.control.runner.StepRequest;
import lab.control.runner.StepResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class StepResultRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = ControlTestDatabase.container();

    @Test
    void upgradePreservesTrialAndStoresHttpAndTransportResultsAsRuntimeRole() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("control").locations("classpath:db/control/migrations")
                .target("2").load().migrate();
        JdbcClient runtime = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "control_app", ControlTestDatabase.CONTROL_PASSWORD));
        UUID trialId = UUID.randomUUID();
        runtime.sql("""
                INSERT INTO control.trials (trial_id, idempotency_key, families, seed, state)
                VALUES (:trialId, :key, '["BOLA"]', 42, 'RUNNING')
                """).param("trialId", trialId).param("key", UUID.randomUUID()).update();

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("control").locations("classpath:db/control/migrations")
                .target("3").load().migrate();
        StepResult legacy = new StepResult(trialId, UUID.randomUUID(), UUID.randomUUID(), GatewayTarget.VULNERABLE,
                "GET", "/api/v1/products", 200, StepOutcome.RESPONSE, OffsetDateTime.now(), 1, null);
        runtime.sql("""
                INSERT INTO control.step_results (
                    trial_id, run_id, request_id, target, method, route, status, outcome, started_at, duration_millis
                ) VALUES (:trialId, :runId, :requestId, 'VULNERABLE', 'GET', '/api/v1/products', 200, 'RESPONSE', :startedAt, 1)
                """)
                .param("trialId", trialId).param("runId", legacy.runId()).param("requestId", legacy.requestId())
                .param("startedAt", legacy.startedAt()).update();

        ControlTestDatabase.migrate(POSTGRES);

        assertThat(runtime.sql("SELECT current_user").query(String.class).single()).isEqualTo("control_app");
        assertThat(runtime.sql("SELECT trial_id FROM control.trials").query(UUID.class).single()).isEqualTo(trialId);
        StepResultRepository repository = new StepResultRepository(runtime, new ContractJsonCodec());
        assertThat(repository.find(legacy.requestId())).isPresent();
        UUID runId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-11-16T10:00:00.123456Z");
        StepResult response = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.VULNERABLE,
                "GET", "/api/v1/users/7", 200, StepOutcome.RESPONSE, startedAt, 15, null);
        StepResult redirect = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.PROTECTED,
                "GET", "/api/v1/users/7", 302, StepOutcome.REDIRECT_REJECTED, startedAt.plusSeconds(1), 3, null);
        StepResult timeout = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.PROTECTED,
                "GET", "/api/v1/users/7", null, StepOutcome.TIMEOUT, startedAt.plusSeconds(2), 1000, null);
        StepResult violation = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.PROTECTED,
                "GET", "/api/v1/users/7", 200, StepOutcome.CONTRACT_VIOLATION, startedAt.plusSeconds(3), 2, null);

        repository.save(response);
        repository.save(redirect);
        repository.save(timeout);
        repository.save(violation);

        assertThat(repository.find(response.requestId())).contains(response);
        assertThat(repository.find(redirect.requestId())).contains(redirect);
        assertThat(repository.find(timeout.requestId())).contains(timeout);
        assertThat(repository.find(violation.requestId())).contains(violation);
        assertThat(repository.find(UUID.randomUUID())).isEmpty();
        for (String outcome : List.of("RESPONSE", "CONTRACT_VIOLATION", "UNKNOWN")) {
            assertThatThrownBy(() -> runtime.sql("""
                    INSERT INTO control.step_results (
                        trial_id, run_id, request_id, target, method, route, status, outcome, started_at, duration_millis
                    ) VALUES (:trialId, :runId, :requestId, 'PROTECTED', 'GET', '/api/v1/users/7', NULL, :outcome, :startedAt, 0)
                    """).param("trialId", trialId).param("runId", runId).param("requestId", UUID.randomUUID())
                    .param("outcome", outcome).param("startedAt", startedAt).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        executeAndReadMetadata(runtime, repository, trialId, runId);
    }

    private static void executeAndReadMetadata(JdbcClient runtime, StepResultRepository repository,
                                              UUID trialId, UUID runId) throws Exception {
        String password = "password-secret";
        String jwt = "request-jwt-secret";
        String query = "query-secret";
        String exception = "exception-secret";
        HttpClient client = mock(HttpClient.class);
        var requestId = new AtomicReference<String>();
        HttpResponse<byte[]> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenAnswer(invocation -> HttpHeaders.of(Map.of(
                "Content-Type", List.of("application/json"), "X-Request-Id", List.of(requestId.get())),
                (name, value) -> true));
        when(response.body()).thenReturn(("{\"accessToken\":\"" + jwt
                + "\",\"tokenType\":\"Bearer\",\"expiresIn\":900}").getBytes(StandardCharsets.UTF_8));
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requestId.set(request.headers().firstValue("X-Request-Id").orElseThrow());
                    return CompletableFuture.completedFuture(response);
                })
                .thenReturn(CompletableFuture.failedFuture(new IOException(exception)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requestId.set(request.headers().firstValue("X-Request-Id").orElseThrow());
                    HttpResponse<byte[]> malformed = mock();
                    when(malformed.statusCode()).thenReturn(200);
                    when(malformed.headers()).thenAnswer(ignored -> response.headers());
                    when(malformed.body()).thenReturn(("{\"password\":\"" + password + "\"}")
                            .getBytes(StandardCharsets.UTF_8));
                    return CompletableFuture.completedFuture(malformed);
                });
        StepExecutor executor = new StepExecutor(client, repository, new ShopContractValidator(new ContractJsonCodec()));
        String body = "{\"login\":\"alice\",\"password\":\"" + password + "\"}";
        var run = new RunContext(trialId, runId, Duration.ofMinutes(1));
        StepResult login = executor.execute(run, new StepRequest(GatewayTarget.VULNERABLE,
                "POST", "/api/v1/auth/login", Map.of("Authorization", "Bearer " + jwt,
                "Content-Type", "application/json"), body)).result();
        StepResult failed = executor.execute(run, new StepRequest(GatewayTarget.PROTECTED,
                "GET", "/api/v1/products?q=" + query, Map.of("Authorization", "Bearer " + jwt), null)).result();
        var invalid = executor.execute(run, new StepRequest(GatewayTarget.PROTECTED,
                "GET", "/api/v1/products", Map.of(), null));

        assertThat(repository.find(login.requestId())).contains(login);
        assertThat(login.route()).isEqualTo("/api/v1/auth/login");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.outcome()).isEqualTo(StepOutcome.RESPONSE);
        assertThat(repository.find(failed.requestId())).contains(failed);
        assertThat(failed.route()).isEqualTo("/api/v1/products");
        assertThat(failed.status()).isNull();
        assertThat(failed.outcome()).isEqualTo(StepOutcome.IO_ERROR);
        assertThat(invalid.result().outcome()).isEqualTo(StepOutcome.CONTRACT_VIOLATION);
        assertThat(invalid.body()).isNull();
        assertThat(repository.find(invalid.result().requestId())).contains(invalid.result());
        for (StepResult result : List.of(login, failed, invalid.result())) {
            String stored = runtime.sql("""
                    SELECT to_jsonb(result)::text FROM control.step_results result WHERE request_id = :requestId
                    """).param("requestId", result.requestId()).query(String.class).single();
            assertThat(stored).doesNotContain(password, jwt, query, exception,
                    "password", "Authorization", "?q=");
        }
    }
}
