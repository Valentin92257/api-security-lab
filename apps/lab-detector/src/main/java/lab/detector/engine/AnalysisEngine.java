package lab.detector.engine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import tools.jackson.databind.JsonNode;

/** S2-20 supplies rules; the engine only sees complete HTTP observations for one run. */
public interface AnalysisEngine {
    List<Incident> analyze(UUID runId, Stream<Observation> observations);

    record Observation(String producer, int sequence, UUID requestId, Instant time, JsonNode payload) {
    }
}
