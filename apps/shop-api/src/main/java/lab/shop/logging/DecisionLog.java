package lab.shop.logging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

public final class DecisionLog {
    private static final Logger LOG = LoggerFactory.getLogger("lab.decision");
    private static final JsonMapper JSON = new JsonMapper();
    private static final Pattern POLICY_ID = Pattern.compile("[a-z][a-z0-9._-]{0,95}");
    private static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,95}");

    private DecisionLog() {
    }

    public static void record(String requestId, String policyId, Kind kind, Decision decision, String reason) {
        Objects.requireNonNull(requestId);
        if (!UUID.fromString(requestId).toString().equalsIgnoreCase(requestId)) {
            throw new IllegalArgumentException("requestId must be a full UUID");
        }
        if (policyId == null || !POLICY_ID.matcher(policyId).matches()) {
            throw new IllegalArgumentException("policyId must be a stable policy identifier");
        }
        if (reason == null || !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("reason must be a fixed reason code");
        }
        Objects.requireNonNull(kind);
        Objects.requireNonNull(decision);
        LOG.info(JSON.writeValueAsString(new Entry(1, "policy_decision", Instant.now().toString(),
                "shop-api", requestId, requestId, policyId, kind, decision, reason)));
    }

    public enum Decision { ALLOW, BLOCK }

    public enum Kind { TECHNICAL, SECURITY }

    private record Entry(int schemaVersion, String event, String time, String service,
                         String requestId, String trace_id, String policyId, Kind kind,
                         Decision decision, String reason) {
    }
}
