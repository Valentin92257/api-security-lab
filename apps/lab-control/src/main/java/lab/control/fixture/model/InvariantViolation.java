package lab.control.fixture.model;

import java.util.Objects;

public record InvariantViolation(
        String invariantId,
        String entity,
        String entityId,
        String detailsJson
) {
    public InvariantViolation {
        Objects.requireNonNull(invariantId, "invariantId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(entityId, "entityId");
        detailsJson = detailsJson == null ? "{}" : detailsJson;
    }
}
