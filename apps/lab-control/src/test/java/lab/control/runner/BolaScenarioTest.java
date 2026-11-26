package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import lab.contracts.json.ContractValidationException;
import lab.control.fixture.FixtureGenerator;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BolaScenarioTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00Z");

    private final StepExecutor steps = mock(StepExecutor.class);
    private final ShopContractValidator validator = mock(ShopContractValidator.class);
    private final ContractJsonCodec json = new ContractJsonCodec();
    private final FixtureGenerator fixtures = new FixtureGenerator(json);
    private final BolaScenario scenario = new BolaScenario(steps, validator, json);
    private final UUID trialId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final FixtureManifest manifest = fixtures.generate(42, BASE_TIME);

    @Test
    void executesLoginAttackAndBenignControlsFromThePrivateManifest() {
        serveOrdersStrictly();
        var plan = toPlan(manifest);

        var evidence = run(runId, GatewayTarget.PROTECTED, plan);

        var requests = requests(7);
        assertThat(evidence).hasSize(7);
        var login = requests.get(0);
        assertThat(login.target()).isEqualTo(GatewayTarget.PROTECTED);
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.route()).isEqualTo("/api/v1/auth/login");
        assertThat(login.headers()).containsEntry("Content-Type", "application/json")
                .containsEntry("X-Forwarded-For", "192.0.2.1");
        assertThat(json.jsonMapper().readTree(login.body()).get("login").asString())
                .isEqualTo(manifest.bola().attacker().login());
        verify(validator, times(1)).validateLoginRequest(anyString());
        var orderReads = requests.subList(1, requests.size());
        assertThat(orderReads).allSatisfy(request -> {
            assertThat(request.target()).isEqualTo(GatewayTarget.PROTECTED);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers()).containsEntry("Authorization", "Bearer attacker-token")
                    .containsEntry("X-Forwarded-For", "192.0.2.1");
            assertThat(request.body()).isNull();
        });
        List<Long> expectedRoutes = new ArrayList<>();
        manifest.bola().victimOrders().forEach(order -> expectedRoutes.add(order.id()));
        expectedRoutes.add(manifest.bola().attackerOwnOrder().id());
        expectedRoutes.add(manifest.bola().missingOrderId());
        assertThat(orderReads).extracting(StepRequest::route)
                .containsExactlyElementsOf(expectedRoutes.stream()
                        .map(id -> "/api/v1/orders/" + id).toList());
    }

    @Test
    void skipsOrderStepsWhenTheAttackerLoginIsRejected() {
        when(steps.execute(any(), any()))
                .thenAnswer(call -> response(call, 401, StepOutcome.RESPONSE, null));

        var evidence = run(runId, GatewayTarget.VULNERABLE, toPlan(manifest));

        assertThat(evidence).hasSize(1);
        var requests = requests(1);
        assertThat(requests.getFirst().method()).isEqualTo("POST");
    }

    @Test
    void validatesTheRequestBeforeSending() {
        doThrow(new ContractValidationException("Request rejected"))
                .when(validator).validateLoginRequest(anyString());

        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, toPlan(manifest)))
                .isInstanceOf(ContractValidationException.class);

        verifyNoInteractions(steps);
    }

    @ParameterizedTest
    @MethodSource("invalidPlans")
    void rejectsIncompleteOrAmbiguousPlansBeforeSending(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable plan) {
        assertThatThrownBy(plan).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(steps, validator);
    }

    static Stream<org.assertj.core.api.ThrowableAssert.ThrowingCallable> invalidPlans() {
        return Stream.of(
                () -> new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 999_001L, List.of()),
                () -> new BolaScenario.BolaPlan(0, "attacker", "password123", 1, 999_001L, List.of(2L)),
                () -> new BolaScenario.BolaPlan(1, "AT", "password123", 1, 999_001L, List.of(2L)),
                () -> new BolaScenario.BolaPlan(1, "attacker", "short", 1, 999_001L, List.of(2L)),
                () -> new BolaScenario.BolaPlan(1, "attacker", "password123", 0, 999_001L, List.of(2L)),
                () -> new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 999_001L, List.of(1L)),
                () -> new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 1, List.of(2L)),
                () -> new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 999_001L, List.of(0L)),
                () -> new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 999_001L, List.of(2L, 2L))
        );
    }

    @Test
    void completesTheLargestPlanWithinTheStepBudget() {
        serveOrdersStrictly();
        var plan = new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 999_001L,
                LongStream.rangeClosed(2, 398).boxed().toList());

        assertThat(run(runId, GatewayTarget.PROTECTED, plan)).hasSize(400);
        requests(400);
    }

    @Test
    void rejectsPlansExceedingTheStepBudgetBeforeSending() {
        assertThatThrownBy(() -> new BolaScenario.BolaPlan(1, "attacker", "password123", 1, 999_001L,
                LongStream.rangeClosed(2, 399).boxed().toList()))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(steps, validator);
    }

    @Test
    void rejectsNullPlanBeforeSending() {
        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(steps, validator);
    }

    @Test
    void stopsAfterAnInterruptedStep() {
        when(steps.execute(any(), any()))
                .thenAnswer(call -> response(call, null, StepOutcome.INTERRUPTED, null));

        var evidence = run(runId, GatewayTarget.PROTECTED, toPlan(manifest));

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().result().outcome()).isEqualTo(StepOutcome.INTERRUPTED);
        requests(1);
    }

    @Test
    void doesNotSendWhenAlreadyInterrupted() {
        try {
            Thread.currentThread().interrupt();

            assertThat(run(runId, GatewayTarget.PROTECTED, toPlan(manifest))).isEmpty();

            verifyNoInteractions(steps, validator);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void doesNotSendWhenInterruptedDuringRequestValidation() {
        doAnswer(call -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(validator).validateLoginRequest(anyString());

        try {
            assertThat(run(runId, GatewayTarget.PROTECTED, toPlan(manifest))).isEmpty();

            verifyNoInteractions(steps);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopsWhenAStepCannotBeSaved() {
        var failure = new IllegalStateException("Result storage unavailable");
        when(steps.execute(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, toPlan(manifest)))
                .isSameAs(failure);

        requests(1);
    }

    @Test
    void hidesFixturePasswordsAndBaselinesFromTextRepresentations() {
        var plan = toPlan(manifest);

        assertThat(plan.toString()).doesNotContain(manifest.bola().attacker().password());
        for (FixtureOrder order : manifest.bola().victimOrders()) {
            assertThat(plan.toString()).doesNotContain(order.responseBody());
        }
    }

    private List<StepResponse> run(UUID id, GatewayTarget target, BolaScenario.BolaPlan plan) {
        var run = new RunContext(trialId, id, Duration.ofMinutes(1));
        var evidence = scenario.run(run, target, plan);
        assertThat(evidence).hasSize(run.finish().steps().size());
        return evidence;
    }

    private static BolaScenario.BolaPlan toPlan(FixtureManifest manifest) {
        var bola = manifest.bola();
        return new BolaScenario.BolaPlan(
                bola.attacker().id(),
                bola.attacker().login(),
                bola.attacker().password(),
                bola.attackerOwnOrder().id(),
                bola.missingOrderId(),
                bola.victimOrders().stream().map(FixtureOrder::id).toList()
        );
    }

    private void serveOrdersStrictly() {
        when(steps.execute(any(), any())).thenAnswer(call -> {
            StepRequest request = call.getArgument(1);
            if (request.method().equals("POST")) {
                return response(call, 200, StepOutcome.RESPONSE, token());
            }
            return response(call, 404, StepOutcome.RESPONSE, null);
        });
    }

    private JsonNode token() {
        return json.jsonMapper().createObjectNode()
                .put("accessToken", "attacker-token")
                .put("tokenType", "Bearer")
                .put("expiresIn", 900);
    }

    private StepResponse response(InvocationOnMock call, Integer status, StepOutcome outcome, JsonNode body) {
        StepRequest request = call.getArgument(1);
        RunContext run = call.getArgument(0);
        var result = new StepResult(run.trialId(), run.runId(), UUID.randomUUID(), request.target(),
                request.method(), request.route(), status, outcome, OffsetDateTime.now(), 0, null);
        var pending = run.dispatch(() -> CompletableFuture.completedFuture(null));
        run.record(result);
        run.release(pending);
        if (outcome == StepOutcome.INTERRUPTED) {
            run.cancel();
        }
        return new StepResponse(result, body);
    }

    private List<StepRequest> requests(int count) {
        var requests = ArgumentCaptor.forClass(StepRequest.class);
        verify(steps, times(count)).execute(any(), requests.capture());
        return requests.getAllValues();
    }
}
