package lab.detector.engine;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Recomputes one run snapshot; all mutable rule state belongs to this invocation. */
@Component
public class RuleBasedAnalysisEngine implements AnalysisEngine {
    static final String RULESET_VERSION = "credential-stuffing-v1";
    private static final String LOGIN_ROUTE = "/api/v1/auth/login";
    private static final Comparator<LoginAttempt> ORDER = Comparator.comparing(LoginAttempt::time)
            .thenComparingInt(LoginAttempt::sequence).thenComparing(LoginAttempt::requestId);

    private final Clock clock;

    public RuleBasedAnalysisEngine(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Incident> analyze(UUID runId, Stream<Observation> observations) {
        // A Shop response is the authentication result. Gateway observations must not
        // double the attempt count or turn proxy failures into invalid credentials.
        List<LoginAttempt> attempts = observations.filter(RuleBasedAnalysisEngine::isLogin)
                .map(RuleBasedAnalysisEngine::attempt).sorted(ORDER).toList();
        var seen = new HashSet<UUID>();
        Map<String, List<LoginAttempt>> byAddress = new TreeMap<>();
        for (LoginAttempt attempt : attempts) {
            if (seen.add(attempt.requestId())) {
                byAddress.computeIfAbsent(attempt.address(), ignored -> new ArrayList<>()).add(attempt);
            }
        }

        var incidents = new ArrayList<Incident>();
        for (var entry : byAddress.entrySet()) {
            var episodes = new IncidentEpisodes(entry.getValue());
            CredentialStuffingRules.evaluate(entry.getValue(), episodes);
            incidents.addAll(episodes.incidents(runId, entry.getKey(), OffsetDateTime.now(clock)));
        }
        return List.copyOf(incidents);
    }

    private static boolean isLogin(Observation observation) {
        if (!observation.producer().equals("SHOP")) {
            return false;
        }
        JsonNode payload = observation.payload();
        if (!payload.get("method").asString().equals("POST")) {
            return false;
        }
        String route = nullableText(payload, "route");
        String path = payload.get("path").asString();
        int query = path.indexOf('?');
        String effectiveRoute = route != null ? route : query < 0 ? path : path.substring(0, query);
        int status = payload.get("status").asInt();
        return LOGIN_ROUTE.equals(effectiveRoute) && (status == 200 || status == 401 || status == 429);
    }

    private static LoginAttempt attempt(Observation observation) {
        JsonNode payload = observation.payload();
        return new LoginAttempt(observation.sequence(), observation.requestId(), observation.time(),
                payload.get("remote_addr").asString(), payload.get("status").asInt(),
                nullableText(payload, "req_hash"));
    }

    private static String nullableText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    record LoginAttempt(int sequence, UUID requestId, Instant time, String address, int status, String hash) {
    }
}
