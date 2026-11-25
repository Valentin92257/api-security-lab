package lab.control.fixture;

import lab.control.fixture.model.InvariantViolation;

import java.util.List;

public class FixtureVerificationException extends RuntimeException {
    private final ShopTarget target;
    private final List<InvariantViolation> violations;

    public FixtureVerificationException(ShopTarget target, List<InvariantViolation> violations) {
        super(buildMessage(target, violations));
        this.target = target;
        this.violations = List.copyOf(violations);
    }

    public ShopTarget target() {
        return target;
    }

    public List<InvariantViolation> violations() {
        return violations;
    }

    private static String buildMessage(ShopTarget target, List<InvariantViolation> violations) {
        return "Fixture invariant verification failed for " + target + " with " + violations.size()
                + " violation(s): " + violations;
    }
}
