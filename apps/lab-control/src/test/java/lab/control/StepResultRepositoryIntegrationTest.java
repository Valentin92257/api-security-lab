package lab.control;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import lab.control.result.StepResultRepository;
import lab.control.runner.GatewayTarget;
import lab.control.runner.StepExecutor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        ControlTestDatabase.migrate(POSTGRES);

        assertThat(runtime.sql("SELECT current_user").query(String.class).single()).isEqualTo("control_app");
        assertThat(runtime.sql("SELECT trial_id FROM control.trials").query(UUID.class).single()).isEqualTo(trialId);
        StepResultRepository repository = new StepResultRepository(runtime);
        UUID runId = UUID.randomUUID();
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-11-16T10:00:00.123456Z");
        StepResult response = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.VULNERABLE,
                "GET", "/api/v1/users/7", 200, StepOutcome.RESPONSE, startedAt, 15);
        StepResult redirect = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.PROTECTED,
                "GET", "/api/v1/users/7", 302, StepOutcome.REDIRECT_REJECTED, startedAt.plusSeconds(1), 3);
        StepResult timeout = new StepResult(trialId, runId, UUID.randomUUID(), GatewayTarget.PROTECTED,
                "GET", "/api/v1/users/7", null, StepOutcome.TIMEOUT, startedAt.plusSeconds(2), 1000);

        repository.save(response);
        repository.save(redirect);
        repository.save(timeout);

        assertThat(repository.find(response.requestId())).contains(response);
        assertThat(repository.find(redirect.requestId())).contains(redirect);
        assertThat(repository.find(timeout.requestId())).contains(timeout);
        assertThat(repository.find(UUID.randomUUID())).isEmpty();
        for (String outcome : List.of("RESPONSE", "UNKNOWN")) {
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
        HttpResponse<Void> response = mock();
        when(response.statusCode()).thenReturn(200);
        when(client.<Void>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(CompletableFuture.completedFuture(response))
                .thenReturn(CompletableFuture.failedFuture(new IOException(exception)));
        StepExecutor executor = new StepExecutor(client, repository);
        String body = "{\"email\":\"user@example.test\",\"password\":\"" + password + "\"}";
        StepResult login = executor.execute(trialId, runId, new StepRequest(GatewayTarget.VULNERABLE,
                "POST", "/api/v1/auth/login", Map.of("Authorization", "Bearer " + jwt,
                "Content-Type", "application/json"), body));
        StepResult failed = executor.execute(trialId, runId, new StepRequest(GatewayTarget.PROTECTED,
                "GET", "/api/v1/products?q=" + query, Map.of("Authorization", "Bearer " + jwt), null));

        assertThat(repository.find(login.requestId())).contains(login);
        assertThat(login.route()).isEqualTo("/api/v1/auth/login");
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.outcome()).isEqualTo(StepOutcome.RESPONSE);
        assertThat(repository.find(failed.requestId())).contains(failed);
        assertThat(failed.route()).isEqualTo("/api/v1/products");
        assertThat(failed.status()).isNull();
        assertThat(failed.outcome()).isEqualTo(StepOutcome.IO_ERROR);
        for (StepResult result : List.of(login, failed)) {
            String stored = runtime.sql("""
                    SELECT to_jsonb(result)::text FROM control.step_results result WHERE request_id = :requestId
                    """).param("requestId", result.requestId()).query(String.class).single();
            assertThat(stored).doesNotContain(password, jwt, query, exception,
                    "password", "Authorization", "?q=");
        }
        verify(response, never()).body();
        verify(response, never()).headers();
    }
}
