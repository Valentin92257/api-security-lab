package lab.control.fixture.model;

public record InvariantViolation(
        String invariantId,
        String entity,
        String entityId,
        String details
) {
    public InvariantViolation {
        details = details == null ? "{}" : details;
    }
}
