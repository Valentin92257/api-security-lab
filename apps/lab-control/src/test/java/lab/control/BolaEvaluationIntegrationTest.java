package lab.control;

import lab.contracts.json.ContractJsonCodec;
import lab.control.evaluation.BolaAssessment;
import lab.control.evaluation.BolaEvaluator;
import lab.control.fixture.FixtureGenerator;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureOrder;
import lab.control.result.BolaAssessmentRepository;
import lab.control.result.StepResultRepository;
import lab.control.runner.BolaScenario;
import lab.control.runner.GatewayTarget;
import lab.control.runner.RunOutcome;
import lab.control.runner.ScenarioRunner;
import lab.control.runner.ShopContractValidator;
import lab.control.runner.StepExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringJUnitConfig(BolaEvaluationIntegrationTest.Config.class)
class BolaEvaluationIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = ControlTestDatabase.container();

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ContractJsonCodec codec;
    @Autowired
    private StepResultRepository steps;
    @Autowired
    private BolaAssessmentRepository assessments;
    @Autowired
    private BolaEvaluator evaluator;

    @ParameterizedTest
    @EnumSource(GatewayTarget.class)
    void persistsResponseEvidenceAndIndependentAssessmentsForBothTargets(GatewayTarget target) throws Exception {
        UUID trialId = createTrial();
        UUID runId = UUID.randomUUID();
        FixtureManifest manifest = new FixtureGenerator(codec).generate(42, Instant.now());
        runScenario(trialId, runId, target, manifest, false);

        var results = evaluator.evaluate(trialId, runId, target, manifest.bola());

        assertThat(new BolaAssessmentRepository(jdbc, codec).findByRun(trialId, runId, target))
                .containsExactlyElementsOf(results);
        var saved = new StepResultRepository(jdbc, codec).findByRun(trialId, runId, target);
        assertThat(saved).hasSize(7);
        assertThat(saved).filteredOn(step -> step.route().endsWith("/auth/login"))
                .singleElement().satisfies(step -> assertThat(step.orderEvidence()).isNull());
        assertThat(results).filteredOn(result -> result.kind() == BolaAssessment.CaseKind.VICTIM_ORDER)
                .allSatisfy(result -> {
                    assertThat(result.effect()).isEqualTo(target == GatewayTarget.VULNERABLE
                            ? BolaAssessment.Effect.EFFECT_OBSERVED : BolaAssessment.Effect.EFFECT_NOT_OBSERVED);
                    var actual = saved.stream().filter(step -> step.requestId().equals(result.requestId()))
                            .findFirst().orElseThrow();
                    assertThat(actual.orderEvidence()).isEqualTo(target == GatewayTarget.VULNERABLE
                            ? result.expectedEvidence() : null);
                });
        assertThat(results).filteredOn(result -> result.kind() != BolaAssessment.CaseKind.VICTIM_ORDER)
                .allSatisfy(result -> assertThat(result.effect()).isEqualTo(BolaAssessment.Effect.EFFECT_NOT_OBSERVED));
        String stored = jdbc.sql("""
                SELECT jsonb_build_object('steps',
                    (SELECT jsonb_agg(to_jsonb(s)) FROM control.step_results s WHERE trial_id = :trialId),
                    'assessments',
                    (SELECT jsonb_agg(to_jsonb(a)) FROM control.bola_assessments a WHERE trial_id = :trialId))::text
                """).param("trialId", trialId).query(String.class).single();
        assertThat(stored).doesNotContain(manifest.bola().attacker().password(), "attacker-token",
                "Authorization", "accessToken", "password", "createdAt", "items", "coupon");
        assertThat(steps.findByRun(trialId, UUID.randomUUID(), target)).isEmpty();
        assertThat(steps.findByRun(UUID.randomUUID(), runId, target)).isEmpty();
        assertThat(steps.findByRun(trialId, runId,
                target == GatewayTarget.VULNERABLE ? GatewayTarget.PROTECTED : GatewayTarget.VULNERABLE)).isEmpty();
    }

    @Test
    void doesNotAcceptAPersistedForeignResponseAsOwnOrderAccess() throws Exception {
        UUID trialId = createTrial();
        UUID runId = UUID.randomUUID();
        FixtureManifest manifest = new FixtureGenerator(codec).generate(42, Instant.now());
        runScenario(trialId, runId, GatewayTarget.PROTECTED, manifest, true);

        var results = evaluator.evaluate(trialId, runId, GatewayTarget.PROTECTED, manifest.bola());

        assertThat(results).filteredOn(result -> result.kind() == BolaAssessment.CaseKind.OWN_ORDER)
                .singleElement().satisfies(result -> {
                    assertThat(result.effect()).isEqualTo(BolaAssessment.Effect.INCONCLUSIVE);
                    assertThat(result.expectedEvidence().id()).isEqualTo(manifest.bola().attackerOwnOrder().id());
                    assertThat(steps.find(result.requestId()).orElseThrow().orderEvidence().id())
                            .isEqualTo(manifest.bola().victimOrders().getFirst().id());
                });
        assertThat(assessments.findByRun(trialId, runId, GatewayTarget.PROTECTED)).containsExactlyElementsOf(results);
    }

    @Test
    void rollsBackAllAssessmentsWhenOneCaseCannotBeSaved() {
        UUID trialId = createTrial();
        UUID runId = UUID.randomUUID();
        var baseline = new FixtureGenerator(codec).generate(42, Instant.now()).bola();
        var existing = new BolaAssessment(BolaAssessment.CaseKind.OWN_ORDER, baseline.attackerOwnOrder().id(),
                BolaAssessment.Effect.INCONCLUSIVE, null, "Existing result", null);
        assessments.save(trialId, runId, GatewayTarget.PROTECTED, List.of(existing));

        assertThatThrownBy(() -> evaluator.evaluate(trialId, runId, GatewayTarget.PROTECTED, baseline))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(assessments.findByRun(trialId, runId, GatewayTarget.PROTECTED)).containsExactly(existing);
    }

    private UUID createTrial() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO control.trials (trial_id, idempotency_key, families, seed, state)
                VALUES (:id, :key, '["BOLA"]', 42, 'RUNNING')
                """).param("id", id).param("key", UUID.randomUUID()).update();
        return id;
    }

    private void runScenario(UUID trialId, UUID runId, GatewayTarget target,
                             FixtureManifest manifest, boolean wrongOwnOrder) throws Exception {
        var client = mock(HttpClient.class);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            String route = request.uri().getPath();
            int status;
            String body;
            if (route.endsWith("/auth/login")) {
                status = 200;
                body = "{\"accessToken\":\"attacker-token\",\"tokenType\":\"Bearer\",\"expiresIn\":900}";
            } else {
                assertThat(request.headers().firstValue("Authorization")).contains("Bearer attacker-token");
                long orderId = Long.parseLong(route.substring(route.lastIndexOf('/') + 1));
                FixtureOrder order = manifest.orders().stream().filter(candidate -> candidate.id() == orderId)
                        .findFirst().orElse(null);
                if (order != null && order.id() == manifest.bola().attackerOwnOrder().id() && wrongOwnOrder) {
                    order = manifest.bola().victimOrders().getFirst();
                }
                status = order != null && (target == GatewayTarget.VULNERABLE || wrongOwnOrder
                        || order.userId() == manifest.bola().attacker().id()) ? 200 : 404;
                body = status == 200 ? order.responseBody() : """
                        {"type":"urn:lab:problem:http-error","title":"Not Found","status":404,
                         "code":"NOT_FOUND","requestId":"%s"}
                        """.formatted(request.headers().firstValue("X-Request-Id").orElseThrow());
            }
            HttpResponse<byte[]> response = mock();
            when(response.statusCode()).thenReturn(status);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of(
                    "Content-Type", List.of(status == 200 ? "application/json" : "application/problem+json"),
                    "X-Request-Id", List.of(request.headers().firstValue("X-Request-Id").orElseThrow())),
                    (name, value) -> true));
            when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });
        var contracts = new ShopContractValidator(codec);
        var scenario = new BolaScenario(new StepExecutor(client, steps, contracts), contracts, codec);
        var bola = manifest.bola();
        var plan = new BolaScenario.BolaPlan(bola.attacker().id(), bola.attacker().login(), bola.attacker().password(),
                bola.attackerOwnOrder().id(), bola.missingOrderId(), bola.victimOrders().stream().map(FixtureOrder::id).toList());
        try (var runner = new ScenarioRunner(Duration.ofMinutes(1))) {
            var result = runner.start(trialId, runId, run -> scenario.run(run, target, plan))
                    .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(RunOutcome.COMPLETED);
        }
    }

    @Configuration
    @EnableTransactionManagement
    @Import({StepResultRepository.class, BolaAssessmentRepository.class, BolaEvaluator.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            ControlTestDatabase.migrate(POSTGRES);
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "control_app", ControlTestDatabase.CONTROL_PASSWORD);
        }

        @Bean
        JdbcClient jdbc(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ContractJsonCodec codec() {
            return new ContractJsonCodec();
        }
    }
}
