package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import lab.contracts.json.ContractValidationException;
import lab.control.fixture.FixtureGenerator;
import lab.control.fixture.model.FixtureManifest;
import lab.control.fixture.model.FixtureUser;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CredentialStuffingScenarioTest {
    private static final Instant BASE_TIME = Instant.parse("2026-10-25T12:00:00Z");

    private final StepExecutor steps = mock(StepExecutor.class);
    private final ShopContractValidator validator = mock(ShopContractValidator.class);
    private final ContractJsonCodec json = new ContractJsonCodec();
    private final FixtureGenerator fixtures = new FixtureGenerator(json);
    private final CredentialStuffingScenario scenario = new CredentialStuffingScenario(steps, validator, json);
    private final UUID trialId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @Test
    void executesTwoHundredPreparedAttemptsWithExactlyThreeValidPairs() {
        rejectLogins();
        FixtureManifest manifest = fixtures.generate(42, BASE_TIME);
        List<CredentialStuffingScenario.LoginAttempt> attempts = toAttempts(manifest);

        var results = run(runId, GatewayTarget.VULNERABLE, attempts);

        var requests = requests(200);
        assertThat(results).hasSize(200);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.target()).isEqualTo(GatewayTarget.VULNERABLE);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.route()).isEqualTo("/api/v1/auth/login");
            assertThat(request.headers()).containsEntry("Content-Type", "application/json")
                    .containsEntry("X-Forwarded-For", "192.0.2.1");
        });
        List<FixtureUser> victims = manifest.credentialStuffing().victims();
        long valid = requests.stream().map(request -> json.jsonMapper().readTree(request.body()))
                .filter(body -> victims.stream().anyMatch(victim -> victim.login().equals(body.get("login").asString())
                        && victim.password().equals(body.get("password").asString())))
                .count();
        assertThat(valid).isEqualTo(3);
        verify(validator, times(200)).validateLoginRequest(anyString());
    }

    @Test
    void repeatsTheSameLogicalStepsForTheSameSeedAndDiffersForAnotherSeed() {
        rejectLogins();
        List<CredentialStuffingScenario.LoginAttempt> seed42First = toAttempts(fixtures.generate(42, BASE_TIME));
        List<CredentialStuffingScenario.LoginAttempt> seed42Second = toAttempts(fixtures.generate(42, BASE_TIME));
        List<CredentialStuffingScenario.LoginAttempt> seed43 = toAttempts(fixtures.generate(43, BASE_TIME));

        var first = run(runId, GatewayTarget.PROTECTED, seed42First);
        var second = run(UUID.randomUUID(), GatewayTarget.PROTECTED, seed42Second);
        run(UUID.randomUUID(), GatewayTarget.PROTECTED, seed43);

        var requests = requests(600);
        assertThat(requests.subList(0, 200)).isEqualTo(requests.subList(200, 400));
        assertThat(requests.subList(0, 200)).isNotEqualTo(requests.subList(400, 600));
        assertThat(first).extracting(StepResult::requestId)
                .doesNotContainAnyElementsOf(second.stream().map(StepResult::requestId).toList());
    }

    @Test
    void confirmsEachSuccessfulLoginWithItsActorsProfile() {
        FixtureManifest manifest = fixtures.generate(42, BASE_TIME);
        List<FixtureUser> victims = manifest.credentialStuffing().victims();
        when(steps.execute(any(), any())).thenAnswer(call -> {
            StepRequest request = call.getArgument(1);
            if (request.method().equals("GET")) {
                return response(call, 200, StepOutcome.RESPONSE, null);
            }
            JsonNode body = json.jsonMapper().readTree(request.body());
            var victim = victims.stream().filter(candidate -> candidate.login().equals(body.get("login").asString()))
                    .findFirst().orElseThrow();
            if (!victim.password().equals(body.get("password").asString())) {
                return response(call, 401, StepOutcome.RESPONSE, null);
            }
            var token = json.jsonMapper().createObjectNode().put("accessToken", "synthetic-token-" + victim.id())
                    .put("tokenType", "Bearer").put("expiresIn", 900);
            return response(call, 200, StepOutcome.RESPONSE, token);
        });

        var results = run(runId, GatewayTarget.PROTECTED, toAttempts(manifest));

        var requests = requests(203);
        assertThat(results).hasSize(203);
        assertThat(requests.stream().filter(request -> request.method().equals("POST"))).hasSize(200);
        var profiles = requests.stream().filter(request -> request.method().equals("GET")).toList();
        assertThat(profiles).hasSize(3);
        for (FixtureUser victim : victims) {
            assertThat(profiles).contains(new StepRequest(GatewayTarget.PROTECTED, "GET",
                    "/api/v1/users/" + victim.id(),
                    Map.of("Authorization", "Bearer synthetic-token-" + victim.id(),
                            "X-Forwarded-For", "192.0.2.1"), null));
            assertThat(results.toString()).doesNotContain(victim.password(), "synthetic-token-");
        }
    }

    @Test
    void doesNotConfirmAResponseWithoutAValidatedBody() {
        when(steps.execute(any(), any())).thenAnswer(call -> response(call, 200, StepOutcome.RESPONSE, null));

        assertThat(run(runId, GatewayTarget.VULNERABLE, toAttempts(fixtures.generate(42, BASE_TIME)))).hasSize(200);

        assertThat(requests(200)).allSatisfy(request -> assertThat(request.method()).isEqualTo("POST"));
    }

    @Test
    void validatesTheRequestBeforeSending() {
        doThrow(new ContractValidationException("Request rejected"))
                .when(validator).validateLoginRequest(anyString());

        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, toAttempts(fixtures.generate(42, BASE_TIME))))
                .isInstanceOf(ContractValidationException.class);

        verifyNoInteractions(steps);
    }

    @ParameterizedTest
    @MethodSource("invalidAttemptLists")
    void rejectsIncompleteOrAmbiguousAttemptsBeforeSending(List<CredentialStuffingScenario.LoginAttempt> attempts) {
        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, attempts))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(steps, validator);
    }

    @Test
    void stopsAfterAnInterruptedStep() {
        when(steps.execute(any(), any()))
                .thenAnswer(call -> response(call, null, StepOutcome.INTERRUPTED, null));

        var results = run(runId, GatewayTarget.PROTECTED, toAttempts(fixtures.generate(42, BASE_TIME)));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().outcome()).isEqualTo(StepOutcome.INTERRUPTED);
        requests(1);
    }

    @Test
    void doesNotSendWhenAlreadyInterrupted() {
        try {
            Thread.currentThread().interrupt();

            assertThat(run(runId, GatewayTarget.PROTECTED, toAttempts(fixtures.generate(42, BASE_TIME)))).isEmpty();

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
            assertThat(run(runId, GatewayTarget.PROTECTED, toAttempts(fixtures.generate(42, BASE_TIME)))).isEmpty();

            verifyNoInteractions(steps);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopsWhenAStepCannotBeSaved() {
        var failure = new IllegalStateException("Result storage unavailable");
        when(steps.execute(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> run(runId, GatewayTarget.PROTECTED, toAttempts(fixtures.generate(42, BASE_TIME))))
                .isSameAs(failure);

        requests(1);
    }

    @Test
    void hidesFixturePasswordsFromTextRepresentations() {
        var attempts = toAttempts(fixtures.generate(42, BASE_TIME));
        for (var attempt : attempts) {
            assertThat(attempt.toString()).doesNotContain(attempt.password());
        }
    }

    private List<StepResult> run(UUID id, GatewayTarget target, List<CredentialStuffingScenario.LoginAttempt> attempts) {
        var run = new RunContext(trialId, id, Duration.ofMinutes(1));
        scenario.run(run, target, attempts);
        return run.finish().steps();
    }

    private static List<CredentialStuffingScenario.LoginAttempt> toAttempts(FixtureManifest manifest) {
        return manifest.credentialStuffing().attempts().stream()
                .map(pair -> new CredentialStuffingScenario.LoginAttempt(pair.userId(), pair.login(), pair.password()))
                .toList();
    }

    private static Stream<List<CredentialStuffingScenario.LoginAttempt>> invalidAttemptLists() {
        var valid = toAttempts(new FixtureGenerator(new ContractJsonCodec()).generate(42, BASE_TIME));
        List<CredentialStuffingScenario.LoginAttempt> withNull = new ArrayList<>(valid);
        withNull.set(10, null);
        List<CredentialStuffingScenario.LoginAttempt> conflictingLogin = new ArrayList<>(valid);
        conflictingLogin.set(0, new CredentialStuffingScenario.LoginAttempt(1, "other_login", "password123"));
        List<CredentialStuffingScenario.LoginAttempt> twoAccountsOnly = new ArrayList<>(200);
        for (int i = 0; i < 200; i++) {
            twoAccountsOnly.add(i % 2 == 0
                    ? new CredentialStuffingScenario.LoginAttempt(1, "victim_a", "password123")
                    : new CredentialStuffingScenario.LoginAttempt(2, "victim_b", "password123"));
        }
        return Stream.of(
                null,
                valid.subList(0, 199),
                withNull,
                conflictingLogin,
                twoAccountsOnly
        );
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
