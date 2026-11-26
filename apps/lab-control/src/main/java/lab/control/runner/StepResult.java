package lab.control.runner;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StepResult(UUID trialId, UUID runId, UUID requestId, GatewayTarget target,
                         String method, String route, Integer status, StepOutcome outcome,
                         OffsetDateTime startedAt, long durationMillis, OrderEvidence orderEvidence) {
}
