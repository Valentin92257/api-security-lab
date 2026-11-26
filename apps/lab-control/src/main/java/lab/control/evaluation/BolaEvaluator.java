package lab.control.evaluation;

import lab.contracts.json.ContractJsonCodec;
import lab.control.fixture.model.BolaFixture;
import lab.control.fixture.model.FixtureOrder;
import lab.control.result.BolaAssessmentRepository;
import lab.control.result.StepResultRepository;
import lab.control.runner.GatewayTarget;
import lab.control.runner.OrderEvidence;
import lab.control.runner.StepOutcome;
import lab.control.runner.StepResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lab.control.evaluation.BolaAssessment.CaseKind;
import lab.control.evaluation.BolaAssessment.Effect;

@Component
public class BolaEvaluator {
    private static final String ORDER_ROUTE = "/api/v1/orders/";

    private final StepResultRepository steps;
    private final BolaAssessmentRepository assessments;
    private final JsonMapper json;

    public BolaEvaluator(StepResultRepository steps, BolaAssessmentRepository assessments, ContractJsonCodec codec) {
        this.steps = steps;
        this.assessments = assessments;
        this.json = codec.jsonMapper();
    }

    @Transactional
    public List<BolaAssessment> evaluate(UUID trialId, UUID runId, GatewayTarget target, BolaFixture baseline) {
        Map<Long, StepResult> responses = new HashMap<>();
        for (StepResult step : steps.findByRun(trialId, runId, target)) {
            Long orderId = orderIdOf(step);
            if (orderId != null) {
                responses.putIfAbsent(orderId, step);
            }
        }
        List<BolaAssessment> result = new ArrayList<>();
        for (FixtureOrder order : baseline.victimOrders()) {
            result.add(assessOrder(CaseKind.VICTIM_ORDER, order, responses.get(order.id())));
        }
        FixtureOrder ownOrder = baseline.attackerOwnOrder();
        result.add(assessOrder(CaseKind.OWN_ORDER, ownOrder, responses.get(ownOrder.id())));
        result.add(assessMissingOrder(baseline.missingOrderId(), responses.get(baseline.missingOrderId())));
        assessments.save(trialId, runId, target, result);
        return List.copyOf(result);
    }

    private BolaAssessment assessOrder(CaseKind kind, FixtureOrder order, StepResult response) {
        OrderEvidence expected = OrderEvidence.from(json.readTree(order.responseBody()));
        if (response == null) {
            return new BolaAssessment(kind, order.id(), Effect.INCONCLUSIVE, null,
                    "Order step was not completed", expected);
        }
        if (response.outcome() != StepOutcome.RESPONSE) {
            return new BolaAssessment(kind, order.id(), Effect.INCONCLUSIVE, response.requestId(),
                    "No usable response evidence: " + response.outcome(), expected);
        }
        if (Integer.valueOf(200).equals(response.status())) {
            if (!expected.equals(response.orderEvidence())) {
                return new BolaAssessment(kind, order.id(), Effect.INCONCLUSIVE, response.requestId(),
                        "Response does not match the private baseline", expected);
            }
            return new BolaAssessment(kind, order.id(), kind == CaseKind.VICTIM_ORDER
                    ? Effect.EFFECT_OBSERVED : Effect.EFFECT_NOT_OBSERVED, response.requestId(),
                    kind == CaseKind.VICTIM_ORDER ? "Foreign order fields match the private baseline"
                            : "Own order fields match the private baseline", expected);
        }
        if (Integer.valueOf(404).equals(response.status())) {
            return new BolaAssessment(kind, order.id(), kind == CaseKind.VICTIM_ORDER
                    ? Effect.EFFECT_NOT_OBSERVED : Effect.EFFECT_OBSERVED, response.requestId(),
                    kind == CaseKind.VICTIM_ORDER ? "Owner check returned a strict 404"
                            : "Own order access was blocked", expected);
        }
        return new BolaAssessment(kind, order.id(), Effect.INCONCLUSIVE, response.requestId(),
                "Status " + response.status() + " proves neither access nor a block", expected);
    }

    private BolaAssessment assessMissingOrder(long orderId, StepResult response) {
        if (response == null) {
            return new BolaAssessment(CaseKind.MISSING_ORDER, orderId, Effect.INCONCLUSIVE, null,
                    "Missing order control step was not completed", null);
        }
        if (response.outcome() != StepOutcome.RESPONSE) {
            return new BolaAssessment(CaseKind.MISSING_ORDER, orderId, Effect.INCONCLUSIVE, response.requestId(),
                    "No usable response evidence: " + response.outcome(), null);
        }
        boolean notFound = Integer.valueOf(404).equals(response.status());
        return new BolaAssessment(CaseKind.MISSING_ORDER, orderId,
                notFound ? Effect.EFFECT_NOT_OBSERVED : Effect.EFFECT_OBSERVED, response.requestId(),
                notFound ? "Missing order is reported as a strict 404" : "Missing order produced a non-404 response", null);
    }

    private Long orderIdOf(StepResult result) {
        if (!"GET".equals(result.method()) || !result.route().startsWith(ORDER_ROUTE)) {
            return null;
        }
        try {
            return Long.parseLong(result.route().substring(ORDER_ROUTE.length()));
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
