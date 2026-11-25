package lab.control.runner;

import lab.contracts.json.ContractJsonCodec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class CredentialStuffingScenario {
    public static final int LOGIN_ATTEMPTS = 200;
    private static final int DISTINCT_ACCOUNTS = 3;
    private static final String CLIENT_IP = "192.0.2.1";

    private final StepExecutor steps;
    private final ShopContractValidator validator;
    private final ContractJsonCodec json;

    public CredentialStuffingScenario(StepExecutor steps, ShopContractValidator validator, ContractJsonCodec json) {
        this.steps = steps;
        this.validator = validator;
        this.json = json;
    }

    public void run(RunContext run, GatewayTarget target, List<LoginAttempt> loginAttempts) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(target);
        List<LoginAttempt> attempts = validateAttempts(loginAttempts);
        for (LoginAttempt attempt : attempts) {
            if (run.isStopped()) {
                break;
            }
            StepRequest request = loginRequest(target, attempt);
            if (run.isStopped()) {
                break;
            }
            var login = steps.execute(run, request);
            if (run.isStopped()) {
                break;
            }
            if (login.result().outcome() != StepOutcome.RESPONSE
                    || !Integer.valueOf(200).equals(login.result().status()) || login.body() == null) {
                continue;
            }
            String token = login.body().get("accessToken").asString();
            if (run.isStopped()) {
                break;
            }
            steps.execute(run, new StepRequest(target, "GET",
                    "/api/v1/users/" + attempt.userId(),
                    Map.of("Authorization", "Bearer " + token, "X-Forwarded-For", CLIENT_IP), null));
        }
    }

    private StepRequest loginRequest(GatewayTarget target, LoginAttempt attempt) {
        var body = json.jsonMapper().createObjectNode()
                .put("login", attempt.login()).put("password", attempt.password());
        String payload = json.jsonMapper().writeValueAsString(body);
        validator.validateLoginRequest(payload);
        return new StepRequest(target, "POST", "/api/v1/auth/login",
                Map.of("Content-Type", "application/json", "X-Forwarded-For", CLIENT_IP), payload);
    }

    private List<LoginAttempt> validateAttempts(List<LoginAttempt> attempts) {
        if (attempts == null || attempts.size() != LOGIN_ATTEMPTS) {
            throw new IllegalArgumentException("Expected exactly " + LOGIN_ATTEMPTS + " login attempts");
        }
        Map<Long, String> loginByUserId = new HashMap<>();
        Set<String> logins = new HashSet<>();
        for (LoginAttempt attempt : attempts) {
            if (attempt == null) {
                throw new IllegalArgumentException("Login attempt must not be null");
            }
            String previous = loginByUserId.putIfAbsent(attempt.userId(), attempt.login());
            if (previous != null && !previous.equals(attempt.login())) {
                throw new IllegalArgumentException("Conflicting login for userId=" + attempt.userId());
            }
            logins.add(attempt.login());
        }
        if (loginByUserId.size() != DISTINCT_ACCOUNTS || logins.size() != DISTINCT_ACCOUNTS) {
            throw new IllegalArgumentException("Expected attempts targeting three distinct accounts");
        }
        return List.copyOf(attempts);
    }

    public record LoginAttempt(long userId, String login, String password) {
        private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,32}");

        public LoginAttempt {
            Objects.requireNonNull(login);
            Objects.requireNonNull(password);
            int bytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (userId < 1 || userId > 9007199254740991L || !LOGIN.matcher(login).matches()
                    || bytes < 8 || bytes > 72) {
                throw new IllegalArgumentException("Invalid fixture actor");
            }
        }

        @Override
        public String toString() {
            return "LoginAttempt[userId=" + userId + ", login=" + login + "]";
        }
    }
}
