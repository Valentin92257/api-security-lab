package lab.control.trial;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TrialSnapshot(UUID id, List<String> families, int seed, String state,
                            OffsetDateTime createdAt, long plannedSteps, long finishedSteps,
                            boolean cleanupSafe, String error) {
    public TrialSnapshot {
        families = List.copyOf(families);
    }
}
