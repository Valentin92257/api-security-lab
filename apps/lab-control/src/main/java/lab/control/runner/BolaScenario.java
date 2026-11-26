package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class BolaScenario {
    private static final String CLIENT_IP = "192.0.2.1";
    private static final String ORDER_ROUTE = "/api/v1/orders/";

    private final StepExecutor steps;
    private final ShopContractValidator validator;
    private final ContractJsonCodec json;

    @Autowired
    public BolaScenario(StepExecutor steps, ShopContractValidator validator, ContractJsonCodec json) {
        this.steps = steps;
        this.validator = validator;
        this.json = json;
    }

    public List<StepResponse> run(RunContext run, GatewayTarget target, BolaPlan plan) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(target);
        Objects.requireNonNull(plan);
        List<StepResponse> evidence = new ArrayList<>();
        if (run.isStopped()) {
            return evidence;
        }
        StepRequest login = loginRequest(target, plan);
        if (run.isStopped()) {
            return evidence;
        }
        evidence.add(steps.execute(run, login));
        String token = accessToken(evidence.getLast());
        if (token == null || run.isStopped()) {
            return evidence;
        }
        for (BolaPlan.VictimOrder victim : plan.victimOrders()) {
            if (run.isStopped()) {
                break;
            }
            evidence.add(steps.execute(run, orderRequest(target, token, victim.orderId())));
        }
        if (!run.isStopped()) {
            evidence.add(steps.execute(run, orderRequest(target, token, plan.ownOrderId())));
        }
        if (!run.isStopped()) {
            evidence.add(steps.execute(run, orderRequest(target, token, plan.missingOrderId())));
        }
        return evidence;
    }

    public List<BolaAssessment> evaluate(BolaPlan plan, List<StepResponse> evidence) {
        Objects.requireNonNull(plan);
        Map<Long, StepResponse> orderResponses = new HashMap<>();
        for (StepResponse step : evidence == null ? List.<StepResponse>of() : evidence) {
            if (step == null || step.result() == null) {
                continue;
            }
            Long orderId = orderIdOf(step.result());
            if (orderId != null) {
                orderResponses.putIfAbsent(orderId, step);
            }
        }
        List<BolaAssessment> assessments = new ArrayList<>();
        for (BolaPlan.VictimOrder victim : plan.victimOrders()) {
            assessments.add(assessVictimOrder(victim, orderResponses.get(victim.orderId())));
        }
        assessments.add(assessOwnOrder(plan, orderResponses.get(plan.ownOrderId())));
        assessments.add(assessMissingOrder(plan, orderResponses.get(plan.missingOrderId())));
        return assessments;
    }

    private BolaAssessment assessVictimOrder(BolaPlan.VictimOrder victim, StepResponse attack) {
        if (attack == null) {
            return new BolaAssessment(BolaAssessment.CaseKind.VICTIM_ORDER, victim.orderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, null, "Attack step was not completed");
        }
        if (attack.result().outcome() != StepOutcome.RESPONSE) {
            return new BolaAssessment(BolaAssessment.CaseKind.VICTIM_ORDER, victim.orderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, attack.result().requestId(),
                    "No usable response evidence: " + attack.result().outcome());
        }
        Integer status = attack.result().status();
        if (Integer.valueOf(200).equals(status)) {
            if (attack.body() != null && disclosesForeignOrder(attack.body(), victim.expectedBody())) {
                return new BolaAssessment(BolaAssessment.CaseKind.VICTIM_ORDER, victim.orderId(),
                        BolaAssessment.Effect.EFFECT_OBSERVED, attack.result().requestId(),
                        "Foreign order fields match the private baseline");
            }
            return new BolaAssessment(BolaAssessment.CaseKind.VICTIM_ORDER, victim.orderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, attack.result().requestId(),
                    "Response does not match the private baseline");
        }
        if (Integer.valueOf(404).equals(status)) {
            return new BolaAssessment(BolaAssessment.CaseKind.VICTIM_ORDER, victim.orderId(),
                    BolaAssessment.Effect.EFFECT_NOT_OBSERVED, attack.result().requestId(),
                    "Owner check returned a strict 404");
        }
        return new BolaAssessment(BolaAssessment.CaseKind.VICTIM_ORDER, victim.orderId(),
                BolaAssessment.Effect.INCONCLUSIVE, attack.result().requestId(),
                "Status " + status + " proves neither disclosure nor protection");
    }

    private BolaAssessment assessOwnOrder(BolaPlan plan, StepResponse control) {
        if (control == null) {
            return new BolaAssessment(BolaAssessment.CaseKind.OWN_ORDER, plan.ownOrderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, null, "Own order control step was not completed");
        }
        if (control.result().outcome() != StepOutcome.RESPONSE) {
            return new BolaAssessment(BolaAssessment.CaseKind.OWN_ORDER, plan.ownOrderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, control.result().requestId(),
                    "No usable response evidence: " + control.result().outcome());
        }
        if (Integer.valueOf(200).equals(control.result().status())) {
            return new BolaAssessment(BolaAssessment.CaseKind.OWN_ORDER, plan.ownOrderId(),
                    BolaAssessment.Effect.EFFECT_NOT_OBSERVED, control.result().requestId(),
                    "Own order access preserved");
        }
        if (Integer.valueOf(404).equals(control.result().status())) {
            return new BolaAssessment(BolaAssessment.CaseKind.OWN_ORDER, plan.ownOrderId(),
                    BolaAssessment.Effect.EFFECT_OBSERVED, control.result().requestId(),
                    "Own order access was blocked");
        }
        return new BolaAssessment(BolaAssessment.CaseKind.OWN_ORDER, plan.ownOrderId(),
                BolaAssessment.Effect.INCONCLUSIVE, control.result().requestId(),
                "Status " + control.result().status() + " proves neither access nor a block");
    }

    private BolaAssessment assessMissingOrder(BolaPlan plan, StepResponse control) {
        if (control == null) {
            return new BolaAssessment(BolaAssessment.CaseKind.MISSING_ORDER, plan.missingOrderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, null, "Missing order control step was not completed");
        }
        if (control.result().outcome() != StepOutcome.RESPONSE) {
            return new BolaAssessment(BolaAssessment.CaseKind.MISSING_ORDER, plan.missingOrderId(),
                    BolaAssessment.Effect.INCONCLUSIVE, control.result().requestId(),
                    "No usable response evidence: " + control.result().outcome());
        }
        if (Integer.valueOf(404).equals(control.result().status())) {
            return new BolaAssessment(BolaAssessment.CaseKind.MISSING_ORDER, plan.missingOrderId(),
                    BolaAssessment.Effect.EFFECT_NOT_OBSERVED, control.result().requestId(),
                    "Missing order is reported as a strict 404");
        }
        return new BolaAssessment(BolaAssessment.CaseKind.MISSING_ORDER, plan.missingOrderId(),
                BolaAssessment.Effect.EFFECT_OBSERVED, control.result().requestId(),
                "Missing order produced a non-404 response");
    }

    private boolean disclosesForeignOrder(JsonNode actual, String expectedBody) {
        JsonNode baseline = json.jsonMapper().readTree(expectedBody);
        return actual.get("id").asLong() == baseline.get("id").asLong()
                && actual.get("userId").asLong() == baseline.get("userId").asLong()
                && actual.get("cartId").asLong() == baseline.get("cartId").asLong()
                && actual.get("cartRevision").asLong() == baseline.get("cartRevision").asLong()
                && actual.get("subtotal").asLong() == baseline.get("subtotal").asLong()
                && actual.get("discount").asLong() == baseline.get("discount").asLong()
                && actual.get("total").asLong() == baseline.get("total").asLong()
                && actual.get("currency").asString().equals(baseline.get("currency").asString())
                && actual.get("items").size() == baseline.get("items").size();
    }

    private Long orderIdOf(StepResult result) {
        if (!"GET".equals(result.method()) || !result.route().startsWith(ORDER_ROUTE)) {
            return null;
        }
        String id = result.route().substring(ORDER_ROUTE.length());
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String accessToken(StepResponse login) {
        if (login.result().outcome() != StepOutcome.RESPONSE
                || !Integer.valueOf(200).equals(login.result().status()) || login.body() == null) {
            return null;
        }
        return login.body().get("accessToken").asString();
    }

    private StepRequest loginRequest(GatewayTarget target, BolaPlan plan) {
        var body = json.jsonMapper().createObjectNode()
                .put("login", plan.login()).put("password", plan.password());
        String payload = json.jsonMapper().writeValueAsString(body);
        validator.validateLoginRequest(payload);
        return new StepRequest(target, "POST", "/api/v1/auth/login",
                Map.of("Content-Type", "application/json", "X-Forwarded-For", CLIENT_IP), payload);
    }

    private StepRequest orderRequest(GatewayTarget target, String token, long orderId) {
        return new StepRequest(target, "GET", ORDER_ROUTE + orderId,
                Map.of("Authorization", "Bearer " + token, "X-Forwarded-For", CLIENT_IP), null);
    }

    public record BolaPlan(
            long attackerUserId,
            String login,
            String password,
            long ownOrderId,
            long missingOrderId,
            List<VictimOrder> victimOrders
    ) {
        private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,32}");
        private static final long MAX_ID = 9007199254740991L;

        public BolaPlan {
            Objects.requireNonNull(login);
            Objects.requireNonNull(password);
            if (victimOrders == null || victimOrders.isEmpty()) {
                throw new IllegalArgumentException("BOLA plan requires victim orders");
            }
            victimOrders = List.copyOf(victimOrders);
            int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (attackerUserId < 1 || attackerUserId > MAX_ID || !LOGIN.matcher(login).matches()
                    || passwordBytes < 8 || passwordBytes > 72
                    || ownOrderId < 1 || ownOrderId > MAX_ID
                    || missingOrderId < 1 || missingOrderId > MAX_ID
                    || ownOrderId == missingOrderId) {
                throw new IllegalArgumentException("Invalid BOLA plan");
            }
            Set<Long> orderIds = new HashSet<>();
            for (VictimOrder victim : victimOrders) {
                if (victim == null || victim.orderId() < 1 || victim.orderId() > MAX_ID
                        || victim.expectedBody() == null || victim.expectedBody().isBlank()
                        || victim.orderId() == ownOrderId || victim.orderId() == missingOrderId
                        || !orderIds.add(victim.orderId())) {
                    throw new IllegalArgumentException("Invalid BOLA victim order");
                }
            }
        }

        @Override
        public String toString() {
            return "BolaPlan[attackerUserId=" + attackerUserId + ", login=" + login
                    + ", ownOrderId=" + ownOrderId + ", missingOrderId=" + missingOrderId
                    + ", victimOrders=" + victimOrders.stream().map(VictimOrder::toString).toList() + "]";
        }

        public record VictimOrder(long orderId, String expectedBody) {
            @Override
            public String toString() {
                return "VictimOrder[orderId=" + orderId + "]";
            }
        }
    }

    public record BolaAssessment(
            CaseKind kind,
            long orderId,
            Effect effect,
            UUID requestId,
            String reason
    ) {
        public enum CaseKind {
            VICTIM_ORDER,
            OWN_ORDER,
            MISSING_ORDER
        }

        public enum Effect {
            EFFECT_OBSERVED,
            EFFECT_NOT_OBSERVED,
            INCONCLUSIVE
        }
    }
}
