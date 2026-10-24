package lab.control;

import lab.contracts.control.model.CaseResult;
import lab.contracts.control.model.Incident;
import lab.contracts.control.model.RunReport;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** In-process contracts. Implementations and persistence belong to Control. */
public final class ControlPorts {
    private ControlPorts() {}

    public interface Fixture {
        FixtureData prepare(RunReport.ModeEnum mode, int seed, Instant businessTimeBase);
    }

    public interface Runner {
        PreparedRun prepare(UUID runId, RunReport.ModeEnum mode,
                            Set<CaseResult.FamilyEnum> families, int seed,
                            FixtureData fixture, Instant deadlineAt);
    }

    public interface PreparedRun {
        List<CasePlan> plan();
        CompletionStage<Void> start(ScenarioHttpClient http, AttemptSink attempts);
        void cancel();
    }

    public interface ScenarioHttpClient {
        CompletionStage<HttpResponse<byte[]>> send(HttpRequest request);
    }

    public interface AttemptSink {
        void append(UUID runId, Attempt attempt);
    }

    public interface Evaluator {
        RunReport evaluate(UUID runId, RunReport.ModeEnum mode, List<CasePlan> plan,
                           FixtureData baseline, List<Attempt> attempts,
                           DataSource readOnlyShop, Set<UUID> observedRequestIds,
                           List<Incident> incidents);
    }

    public record FixtureData(List<Actor> actors, List<Product> products,
                              List<Coupon> coupons, List<Order> orders) {}

    public record Actor(long id, String login, String password, String name,
                        String email, long cartId, long cartRevision) {}

    public record Product(long id, String name, String description, long price, boolean published) {}

    public record Coupon(long id, String code, int discountPercent, Instant expiresAt,
                         long maxUses, long usedCount) {}

    public record Order(long id, long userId, long cartId, long cartRevision, Long couponId) {}

    public record CasePlan(String caseId, String episodeId, CaseResult.FamilyEnum family,
                           CaseResult.LabelEnum label, Map<String, Long> actors,
                           Map<String, Long> resources, Set<String> steps,
                           Set<String> evidenceSteps) {}

    public enum TransportOutcome { RESPONSE, TIMEOUT, TRANSPORT_ERROR }

    public record Attempt(String caseId, String stepId, UUID requestId, Instant businessTime,
                          TransportOutcome transportOutcome, Integer status,
                          JsonNode responseFacts) {}
}
