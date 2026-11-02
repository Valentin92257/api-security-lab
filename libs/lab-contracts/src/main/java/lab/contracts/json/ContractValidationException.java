package lab.contracts.json;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when a contract payload fails JSON parsing, JSON Schema structural checks,
 * or Jakarta Bean Validation constraints.
 */
public class ContractValidationException extends RuntimeException {

    private final List<String> violations;

    public ContractValidationException(String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.violations = List.of(message);
    }

    public ContractValidationException(String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message must not be null"), cause);
        this.violations = List.of(message);
    }

    public ContractValidationException(List<String> violations) {
        super(formatMessage(violations));
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
    }

    public List<String> violations() {
        return violations;
    }

    private static String formatMessage(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            return "Contract validation failed";
        }
        return "Contract validation failed: " + String.join("; ", violations);
    }
}

