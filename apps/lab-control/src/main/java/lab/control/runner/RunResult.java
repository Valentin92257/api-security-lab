package lab.control.runner;

import java.util.List;
import java.util.UUID;

public record RunResult(UUID trialId, UUID runId, RunOutcome outcome, int startedSteps, List<StepResult> steps) {
    public RunResult {
        steps = List.copyOf(steps);
    }
}
