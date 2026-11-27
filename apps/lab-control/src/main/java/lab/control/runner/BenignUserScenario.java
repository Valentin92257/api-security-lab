package lab.control.runner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Сценарий легитимного пользователя: выполняет план сессий и возвращает evidence по эпизодам.
 */
@Component
public class BenignUserScenario {
    private static final String PRODUCTS_ROUTE = "/api/v1/products";
    private static final String CART_ITEM_ROUTE = "/api/v1/cart/items/";

    private final StepExecutor steps;
    private final ShopRequests requests;

    @Autowired
    BenignUserScenario(StepExecutor steps, ShopRequests requests) {
        this.steps = steps;
        this.requests = requests;
    }

    public List<SessionEvidence> run(RunContext run, GatewayTarget target, List<BenignSession> sessions) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(target);
        List<BenignSession> plan = validateSessions(sessions);
        List<SessionEvidence> evidence = new ArrayList<>(plan.size());
        for (BenignSession session : plan) {
            if (run.isStopped()) {
                break;
            }
            evidence.add(runSession(run, target, session));
        }
        return List.copyOf(evidence);
    }

    private SessionEvidence runSession(RunContext run, GatewayTarget target, BenignSession session) {
        List<StepResponse> executed = new ArrayList<>(session.actions().size());
        SessionState state = new SessionState(session.clientIp());
        for (BenignAction action : session.actions()) {
            if (run.isStopped()) {
                break;
            }
            StepResponse response = execute(run, target, state, action);
            if (response == null) {
                break;
            }
            executed.add(response);
            if (run.isStopped() || !advance(action, state, response)) {
                break;
            }
        }
        return new SessionEvidence(session, executed);
    }

    private StepResponse execute(RunContext run, GatewayTarget target, SessionState state, BenignAction action) {
        return switch (action) {
            case BenignAction.Login login -> login(run, target, state, login);
            case BenignAction.BrowseCatalog browse -> steps.execute(run, ShopRequests.authorized(
                    target, state.clientIp, state.token, "GET", catalogRoute(browse.query()), null));
            case BenignAction.SetCartItem item -> steps.execute(run, ShopRequests.authorized(
                    target, state.clientIp, state.token, "PUT",
                    CART_ITEM_ROUTE + item.productId(), "{\"quantity\":" + item.quantity() + "}"));
        };
    }

    private StepResponse login(RunContext run, GatewayTarget target, SessionState state, BenignAction.Login login) {
        state.token = null;
        StepRequest request = requests.login(target, state.clientIp, login.login(), login.password());
        if (run.isStopped()) {
            return null;
        }
        return steps.execute(run, request);
    }

    private static boolean advance(BenignAction action, SessionState state, StepResponse response) {
        return switch (action) {
            case BenignAction.Login _ -> {
                String token = ShopRequests.accessToken(response);
                state.token = token;
                yield token != null;
            }
            case BenignAction.BrowseCatalog _, BenignAction.SetCartItem _ -> ShopRequests.succeeded(response, 200);
        };
    }

    private static String catalogRoute(String query) {
        if (query == null || query.isEmpty()) {
            return PRODUCTS_ROUTE;
        }
        return UriComponentsBuilder.fromPath(PRODUCTS_ROUTE)
                .queryParam("q", "{q}")
                .encode()
                .buildAndExpand(query)
                .toUriString();
    }

    private static List<BenignSession> validateSessions(List<BenignSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            throw new IllegalArgumentException("Benign plan requires at least one session");
        }
        long totalSteps = 0;
        for (BenignSession session : sessions) {
            if (session == null) {
                throw new IllegalArgumentException("Benign session must not be null");
            }
            totalSteps += session.actions().size();
        }
        if (totalSteps > RunContext.MAX_STEPS) {
            throw new IllegalArgumentException("Benign plan exceeds the run step budget");
        }
        return List.copyOf(sessions);
    }

    private static final class SessionState {
        private final String clientIp;
        private String token;

        private SessionState(String clientIp) {
            this.clientIp = clientIp;
        }
    }

    public record SessionEvidence(BenignSession session, List<StepResponse> steps) {
        public SessionEvidence {
            steps = List.copyOf(steps);
        }
    }
}
