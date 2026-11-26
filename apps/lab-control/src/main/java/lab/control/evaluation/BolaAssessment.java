package lab.control.evaluation;

import lab.control.runner.OrderEvidence;

import java.util.UUID;

public record BolaAssessment(CaseKind kind, long orderId, Effect effect, UUID requestId,
                             String reason, OrderEvidence expectedEvidence) {
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
