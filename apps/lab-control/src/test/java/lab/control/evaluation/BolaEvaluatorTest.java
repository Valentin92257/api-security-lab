package lab.control.evaluation;

import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.FixtureGenerator;
import lab.control.fixture.model.FixtureOrder;
import lab.control.fixture.model.BolaFixture;
import lab.control.result.BolaAssessmentRepository;
import lab.control.result.StepResultRepository;
import lab.control.runner.GatewayTarget;
import lab.control.runner.OrderEvidence;
import lab.control.runner.StepOutcome;
import lab.control.runner.StepResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import lab.control.evaluation.BolaAssessment.CaseKind;
import lab.control.evaluation.BolaAssessment.Effect;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BolaEvaluatorTest {
    private final ContractJsonCodec codec = new ContractJsonCodec();
    private final StepResultRepository steps = mock(StepResultRepository.class);
    private final BolaAssessmentRepository assessments = mock(BolaAssessmentRepository.class);
    private final BolaEvaluator evaluator = new BolaEvaluator(steps, assessments, codec);
    private final UUID trialId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final GatewayTarget target = GatewayTarget.VULNERABLE;
    private final BolaFixture baseline = new FixtureGenerator(codec)
            .generate(42, Instant.parse("2026-10-25T12:00:00Z")).bola();

    @Test
    void evaluatesPersistedDisclosureAndSavesTheExpectedEvidence() {
        FixtureOrder victim = baseline.victimOrders().getFirst();
        StepResult response = response(victim.id(), 200, StepOutcome.RESPONSE, evidence(victim));

        var results = evaluate(List.of(response));

        assertThat(results).filteredOn(result -> result.orderId() == victim.id())
                .singleElement().satisfies(result -> {
                    assertThat(result.effect()).isEqualTo(Effect.EFFECT_OBSERVED);
                    assertThat(result.requestId()).isEqualTo(response.requestId());
                    assertThat(result.expectedEvidence()).isEqualTo(evidence(victim));
                });
        verify(assessments).save(trialId, runId, target, results);
    }

    @Test
    void acceptsOwnOrderOnlyWhenItMatchesItsBaseline() {
        FixtureOrder own = baseline.attackerOwnOrder();

        var results = evaluate(List.of(response(own.id(), 200, StepOutcome.RESPONSE, evidence(own))));

        assertThat(results).filteredOn(result -> result.kind() == CaseKind.OWN_ORDER)
                .singleElement().satisfies(result ->
                        assertThat(result.effect()).isEqualTo(Effect.EFFECT_NOT_OBSERVED));
    }

    @Test
    void rejectsForeignOrderReturnedForTheOwnOrderRequest() {
        FixtureOrder victim = baseline.victimOrders().getFirst();

        var results = evaluate(List.of(response(baseline.attackerOwnOrder().id(), 200,
                StepOutcome.RESPONSE, evidence(victim))));

        assertThat(results).filteredOn(result -> result.kind() == CaseKind.OWN_ORDER)
                .singleElement().satisfies(result -> {
                    assertThat(result.effect()).isEqualTo(Effect.INCONCLUSIVE);
                    assertThat(result.reason()).contains("does not match");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "userId", "cartId", "cartRevision", "subtotal", "discount", "total", "currency", "items"})
    void rejectsEveryMismatchedOwnOrderEvidenceField(String field) {
        FixtureOrder own = baseline.attackerOwnOrder();
        var body = codec.jsonMapper().readTree(own.responseBody()).deepCopy();
        var object = (tools.jackson.databind.node.ObjectNode) body;
        if (field.equals("currency")) {
            object.put(field, "USD");
        } else if (field.equals("items")) {
            object.putArray(field);
        } else {
            object.put(field, body.get(field).asLong() + 1);
        }

        var results = evaluate(List.of(response(own.id(), 200, StepOutcome.RESPONSE, OrderEvidence.from(body))));

        assertThat(results).filteredOn(result -> result.kind() == CaseKind.OWN_ORDER)
                .singleElement().satisfies(result -> assertThat(result.effect()).isEqualTo(Effect.INCONCLUSIVE));
    }

    @Test
    void distinguishesProtectedVictimOrdersAndBlockedOwnOrder() {
        FixtureOrder victim = baseline.victimOrders().getFirst();
        var results = evaluate(List.of(response(victim.id(), 404, StepOutcome.RESPONSE, null),
                response(baseline.attackerOwnOrder().id(), 404, StepOutcome.RESPONSE, null),
                response(baseline.missingOrderId(), 404, StepOutcome.RESPONSE, null)));

        assertThat(results).filteredOn(result -> result.orderId() == victim.id())
                .singleElement().satisfies(result ->
                        assertThat(result.effect()).isEqualTo(Effect.EFFECT_NOT_OBSERVED));
        assertThat(results).filteredOn(result -> result.kind() == CaseKind.OWN_ORDER)
                .singleElement().satisfies(result -> assertThat(result.effect()).isEqualTo(Effect.EFFECT_OBSERVED));
        assertThat(results).filteredOn(result -> result.kind() == CaseKind.MISSING_ORDER)
                .singleElement().satisfies(result ->
                        assertThat(result.effect()).isEqualTo(Effect.EFFECT_NOT_OBSERVED));
    }

    @Test
    void keepsMissingStepsAndMissingResponseEvidenceInconclusive() {
        FixtureOrder victim = baseline.victimOrders().getFirst();
        var results = evaluate(List.of(response(victim.id(), 200, StepOutcome.RESPONSE, null)));

        assertThat(results).hasSize(baseline.victimOrders().size() + 2)
                .allSatisfy(result -> assertThat(result.effect()).isEqualTo(Effect.INCONCLUSIVE));
    }

    @Test
    void keepsTransportContractAndServerFailuresInconclusive() {
        var victims = baseline.victimOrders();
        var results = evaluate(List.of(response(victims.get(0).id(), null, StepOutcome.TIMEOUT, null),
                response(victims.get(1).id(), 200, StepOutcome.CONTRACT_VIOLATION, null),
                response(victims.get(2).id(), 500, StepOutcome.RESPONSE, null)));

        assertThat(results).allSatisfy(result -> assertThat(result.effect()).isEqualTo(Effect.INCONCLUSIVE));
    }

    @Test
    void doesNotConfirmDisclosureWhenTheResponseContainsTheAttackersOrder() {
        FixtureOrder victim = baseline.victimOrders().getFirst();
        var results = evaluate(List.of(response(victim.id(), 200, StepOutcome.RESPONSE,
                evidence(baseline.attackerOwnOrder()))));

        assertThat(results).filteredOn(result -> result.orderId() == victim.id())
                .singleElement().satisfies(result -> assertThat(result.effect()).isEqualTo(Effect.INCONCLUSIVE));
    }

    @Test
    void propagatesAssessmentStorageFailure() {
        when(steps.findByRun(trialId, runId, target)).thenReturn(List.of());
        var failure = new IllegalStateException("Assessment storage unavailable");
        doThrow(failure).when(assessments).save(eq(trialId), eq(runId), eq(target), any());

        assertThatThrownBy(() -> evaluator.evaluate(trialId, runId, target, baseline)).isSameAs(failure);
    }

    private List<BolaAssessment> evaluate(List<StepResult> evidence) {
        when(steps.findByRun(trialId, runId, target)).thenReturn(evidence);
        return evaluator.evaluate(trialId, runId, target, baseline);
    }

    private OrderEvidence evidence(FixtureOrder order) {
        return OrderEvidence.from(codec.jsonMapper().readTree(order.responseBody()));
    }

    private StepResult response(long orderId, Integer status, StepOutcome outcome, OrderEvidence evidence) {
        return new StepResult(trialId, runId, UUID.randomUUID(), target, "GET", "/api/v1/orders/" + orderId,
                status, outcome, OffsetDateTime.now(), 0, evidence);
    }
}
