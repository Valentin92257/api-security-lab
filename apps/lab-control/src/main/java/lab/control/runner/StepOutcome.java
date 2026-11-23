package lab.control.runner;

public enum StepOutcome {
    RESPONSE,
    CONTRACT_VIOLATION,
    REDIRECT_REJECTED,
    TIMEOUT,
    IO_ERROR,
    INTERRUPTED
}
