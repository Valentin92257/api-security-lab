package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        for (long orderId : plan.victimOrderIds()) {
            if (run.isStopped()) {
                break;
            }
            evidence.add(steps.execute(run, orderRequest(target, token, orderId)));
        }
        if (!run.isStopped()) {
            evidence.add(steps.execute(run, orderRequest(target, token, plan.ownOrderId())));
        }
        if (!run.isStopped()) {
            evidence.add(steps.execute(run, orderRequest(target, token, plan.missingOrderId())));
        }
        return evidence;
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
            List<Long> victimOrderIds
    ) {
        private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,32}");
        private static final long MAX_ID = 9007199254740991L;

        public BolaPlan {
            Objects.requireNonNull(login);
            Objects.requireNonNull(password);
            if (victimOrderIds == null || victimOrderIds.isEmpty()) {
                throw new IllegalArgumentException("BOLA plan requires victim orders");
            }
            if (victimOrderIds.size() > RunContext.MAX_STEPS - 3) {
                throw new IllegalArgumentException("BOLA plan exceeds the run step budget");
            }
            victimOrderIds = List.copyOf(victimOrderIds);
            int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (attackerUserId < 1 || attackerUserId > MAX_ID || !LOGIN.matcher(login).matches()
                    || passwordBytes < 8 || passwordBytes > 72
                    || ownOrderId < 1 || ownOrderId > MAX_ID
                    || missingOrderId < 1 || missingOrderId > MAX_ID
                    || ownOrderId == missingOrderId) {
                throw new IllegalArgumentException("Invalid BOLA plan");
            }
            Set<Long> orderIds = new HashSet<>();
            for (long orderId : victimOrderIds) {
                if (orderId < 1 || orderId > MAX_ID
                        || orderId == ownOrderId || orderId == missingOrderId
                        || !orderIds.add(orderId)) {
                    throw new IllegalArgumentException("Invalid BOLA victim order");
                }
            }
        }

        @Override
        public String toString() {
            return "BolaPlan[attackerUserId=" + attackerUserId + ", login=" + login
                    + ", ownOrderId=" + ownOrderId + ", missingOrderId=" + missingOrderId
                    + ", victimOrderIds=" + victimOrderIds + "]";
        }
    }
}
