package lab.control.trial;

public class TrialException extends RuntimeException {
    private final Reason reason;

    public TrialException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CONFLICT, NOT_FOUND
    }
}
