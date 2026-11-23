package lab.detector.analysis;

import java.util.UUID;

public record RunSnapshot(UUID id, String state, String error, FinishSpec finish,
                          long inputRevision, long processedRevision) {
    public boolean active() {
        return state.equals("ACCEPTING") || state.equals("FINISHING");
    }
}
