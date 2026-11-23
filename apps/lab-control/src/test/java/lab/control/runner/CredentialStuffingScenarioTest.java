package lab.control.runner;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import lab.contracts.json.ContractJsonCodec;
import lab.contracts.json.ContractValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CredentialStuffingScenarioTest {
    private static final List<CredentialStuffingScenario.Actor> ACTORS = List.of(
            new CredentialStuffingScenario.Actor(3, "victim_c", "password-c"),
            new CredentialStuffingScenario.Actor(1, "victim_a", "password-a"),
            new CredentialStuffingScenario.Actor(2, "victim_b", "password-b"));

    private final StepExecutor steps = mock(StepExecutor.class);
    private final ShopContractValidator validator = mock(ShopContractValidator.class);
    private final ContractJsonCodec json = new ContractJsonCodec();
    private final CredentialStuffingScenario scenario = new CredentialStuffingScenario(steps, validator, json);
    private final UUID trialId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void generatesTwoHundredLoginsWithExactlyThreeValidPairs() {
        rejectLogins();

        var results = run(runId, GatewayTarget.VULNERABLE, 42, ACTORS);

        var requests = requests(200);
        assertThat(results).hasSize(200);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.target()).isEqualTo(GatewayTarget.VULNERABLE);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.route()).isEqualTo("/api/v1/auth/login");
            assertThat(request.headers()).containsEntry("Content-Type", "application/json")
                    .containsEntry("X-Forwarded-For", "192.0.2.1");
        });
        long valid = requests.stream().map(request -> json.jsonMapper().readTree(request.body()))
                .filter(body -> ACTORS.stream().anyMatch(actor -> actor.login().equals(body.get("login").asString())
                        && actor.password().equals(body.get("password").asString())))
                .count();
        assertThat(valid).isEqualTo(3);
        verify(validator, times(200)).validateLoginRequest(anyString());
    }

    @Test
    void repeatsTheSameLogicalStepsRegardlessOfActorOrderAndPreviousRuns() {
        rejectLogins();

        var first = run(runId, GatewayTarget.PROTECTED, 42, ACTORS);
        var second = run(UUID.randomUUID(), GatewayTarget.PROTECTED, 42,
                List.of(ACTORS.get(2), ACTORS.get(1), ACTORS.get(0)));
        run(UUID.randomUUID(), GatewayTarget.PROTECTED, 43, ACTORS);

        var requests = requests(600);
        assertThat(requests.subList(0, 200)).isEqualTo(requests.subList(200, 400));
        assertThat(requests.subList(0, 200)).isNotEqualTo(requests.subList(400, 600));
        assertThat(first).extracting(StepResult::requestId)
                .doesNotContainAnyElementsOf(second.stream().map(StepResult::requestId).toList());
    }

    @Test
    void confirmsEachSuccessfulLoginWithItsActorsProfile() {
        when(steps.execute(any(), any())).thenAnswer(call -> {
            StepRequest request = call.getArgument(1);
            if (request.method().equals("GET")) {
                return response(call, 200, StepOutcome.RESPONSE, null);
            }
            JsonNode body = json.jsonMapper().readTree(request.body());
            var actor = ACTORS.stream().filter(candidate -> candidate.login().equals(body.get("login").asString()))
                    .findFirst().orElseThrow();
            if (!actor.password().equals(body.get("password").asString())) {
                return response(call, 401, StepOutcome.RESPONSE, null);
            }
            var token = json.jsonMapper().createObjectNode().put("accessToken", "synthetic-token-" + actor.userId())
                    .put("tokenType", "Bearer").put("expiresIn", 900);
            return response(call, 200, StepOutcome.RESPONSE, token);
        });

        var results = run(runId, GatewayTarget.PROTECTED, 42, ACTORS);

        var requests = requests(203);
        assertThat(results).hasSize(203);
        assertThat(requests.stream().filter(request -> request.method().equals("POST"))).hasSize(200);
        var profiles = requests.stream().filter(request -> request.method().equals("GET")).toList();
        assertThat(profiles).hasSize(3);
        for (var actor : ACTORS) {
            assertThat(profiles).contains(new StepRequest(GatewayTarget.PROTECTED, "GET",
                    "/api/v1/users/" + actor.userId(),
                    Map.of("Authorization", "Bearer synthetic-token-" + actor.userId(),
                            "X-Forwarded-For", "192.0.2.1"), null));
            assertThat(results.toString()).doesNotContain(actor.password(), "synthetic-token-");
        }
    }

    @Test
    void doesNotConfirmAResponseWithoutAValidatedBody() {
        when(steps.execute(any(), any())).thenAnswer(call -> response(call, 200, StepOutcome.RESPONSE, null));

        assertThat(run(runId, GatewayTarget.VULNERABLE, 42, ACTORS)).hasSize(200);

        assertThat(requests(200)).allSatisfy(request -> assertThat(request.method()).isEqualTo("POST"));
    }

    @Test
    void validatesTheRequestBeforeSending() {
        doThrow(new ContractValidationException("Request rejected"))
                .when(validator).validateLoginRequest(anyString());

        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, 42, ACTORS))
                .isInstanceOf(ContractValidationException.class);

        verifyNoInteractions(steps);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 2147483648L})
    void rejectsSeedsOutsideTheControlRangeBeforeSending(long seed) {
        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, seed, ACTORS))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(steps, validator);
    }

    @ParameterizedTest
    @MethodSource("invalidActors")
    void rejectsIncompleteOrAmbiguousFixturesBeforeSending(List<CredentialStuffingScenario.Actor> actors) {
        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, 42, actors))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(steps, validator);
    }

    @Test
    void stopsAfterAnInterruptedStep() {
        when(steps.execute(any(), any()))
                .thenAnswer(call -> response(call, null, StepOutcome.INTERRUPTED, null));

        var results = run(runId, GatewayTarget.PROTECTED, 42, ACTORS);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome()).isEqualTo(StepOutcome.INTERRUPTED);
        requests(1);
    }

    @Test
    void doesNotSendWhenAlreadyInterrupted() {
        try {
            Thread.currentThread().interrupt();

            assertThat(run(runId, GatewayTarget.PROTECTED, 42, ACTORS)).isEmpty();

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
            assertThat(run(runId, GatewayTarget.PROTECTED, 42, ACTORS)).isEmpty();

            verifyNoInteractions(steps);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopsWhenAStepCannotBeSaved() {
        var failure = new IllegalStateException("Result storage unavailable");
        when(steps.execute(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, 42, ACTORS))
                .isSameAs(failure);

        requests(1);
    }

    @Test
    void hidesFixturePasswordsFromTextRepresentations() {
        for (var actor : ACTORS) {
            assertThat(actor.toString()).doesNotContain(actor.password());
        }
    }

    private List<StepResult> run(UUID id, GatewayTarget target, long seed, List<CredentialStuffingScenario.Actor> actors) {
        var run = new RunContext(trialId, id, Duration.ofMinutes(1));
        scenario.run(run, target, seed, actors);
        return run.finish().steps();
    }

    private static Stream<List<CredentialStuffingScenario.Actor>> invalidActors() {
        return Stream.of(
                ACTORS.subList(0, 2),
                List.of(ACTORS.get(0), ACTORS.get(1), ACTORS.get(2), ACTORS.get(0)),
                List.of(ACTORS.get(0), ACTORS.get(1), new CredentialStuffingScenario.Actor(1, "other", "password")),
                List.of(ACTORS.get(0), ACTORS.get(1), new CredentialStuffingScenario.Actor(4, "victim_a", "password")));
    }

    private void rejectLogins() {
        when(steps.execute(any(), any()))
                .thenAnswer(call -> response(call, 401, StepOutcome.RESPONSE, null));
    }

    private StepResponse response(InvocationOnMock call, Integer status, StepOutcome outcome, JsonNode body) {
        StepRequest request = call.getArgument(1);
        RunContext run = call.getArgument(0);
        var result = new StepResult(run.trialId(), run.runId(), UUID.randomUUID(), request.target(),
                request.method(), request.route(), status, outcome, OffsetDateTime.now(), 0);
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
