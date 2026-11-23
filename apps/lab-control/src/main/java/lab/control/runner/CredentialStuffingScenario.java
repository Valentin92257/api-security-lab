package lab.control.runner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

import lab.contracts.json.ContractJsonCodec;
import org.springframework.stereotype.Component;

@Component
public class CredentialStuffingScenario {
    private static final int LOGIN_ATTEMPTS = 200;
    private static final String CLIENT_IP = "192.0.2.1";

    private final StepExecutor steps;
    private final ShopContractValidator validator;
    private final ContractJsonCodec json;

    public CredentialStuffingScenario(StepExecutor steps, ShopContractValidator validator, ContractJsonCodec json) {
        this.steps = steps;
        this.validator = validator;
        this.json = json;
    }

    public List<StepResult> run(UUID trialId, UUID runId, GatewayTarget target, long seed, List<Actor> actors) {
        Objects.requireNonNull(trialId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(target);
        if (seed < 0 || seed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Seed must be between 0 and 2147483647");
        }
        List<LoginAttempt> attempts = prepare(seed, actors);
        List<StepResult> results = new ArrayList<>();
        for (LoginAttempt attempt : attempts) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            var body = json.jsonMapper().createObjectNode()
                    .put("login", attempt.actor().login()).put("password", attempt.password());
            String payload = json.jsonMapper().writeValueAsString(body);
            validator.validateRequest(ShopRoute.LOGIN, payload);
            var login = steps.execute(trialId, runId, new StepRequest(target, "POST", "/api/v1/auth/login",
                    Map.of("Content-Type", "application/json", "X-Forwarded-For", CLIENT_IP), payload));
            results.add(login.result());
            if (login.result().outcome() == StepOutcome.INTERRUPTED || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (login.result().outcome() == StepOutcome.RESPONSE
                    && Integer.valueOf(200).equals(login.result().status()) && login.body() != null) {
                String token = login.body().get("accessToken").asString();
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                var profile = steps.execute(trialId, runId, new StepRequest(target, "GET",
                        "/api/v1/users/" + attempt.actor().userId(),
                        Map.of("Authorization", "Bearer " + token, "X-Forwarded-For", CLIENT_IP), null));
                results.add(profile.result());
                if (profile.result().outcome() == StepOutcome.INTERRUPTED) {
                    break;
                }
            }
        }
        return List.copyOf(results);
    }

    private List<LoginAttempt> prepare(long seed, List<Actor> actors) {
        List<Actor> ordered = actors.stream().sorted(Comparator.comparingLong(Actor::userId)).toList();
        if (ordered.size() != 3 || ordered.stream().map(Actor::userId).distinct().count() != 3
                || ordered.stream().map(Actor::login).distinct().count() != 3) {
            throw new IllegalArgumentException("Expected three distinct fixture actors");
        }
        Random random = new Random(seed);
        List<LoginAttempt> attempts = new ArrayList<>(LOGIN_ATTEMPTS);
        ordered.forEach(actor -> attempts.add(new LoginAttempt(actor, actor.password())));
        while (attempts.size() < LOGIN_ATTEMPTS) {
            Actor actor = ordered.get(random.nextInt(ordered.size()));
            String password = "invalid_" + Long.toUnsignedString(random.nextLong(), 36);
            if (password.equals(actor.password())) {
                password += "_wrong";
            }
            attempts.add(new LoginAttempt(actor, password));
        }
        Collections.shuffle(attempts, random);
        return attempts;
    }

    public record Actor(long userId, String login, String password) {
        private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,32}");

        public Actor {
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
            return "Actor[userId=" + userId + ", login=" + login + "]";
        }
    }

    private record LoginAttempt(Actor actor, String password) {
        @Override
        public String toString() {
            return "LoginAttempt[userId=" + actor.userId() + "]";
        }
    }
}
