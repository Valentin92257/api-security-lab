package lab.detector.engine;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S2-20 supplies rules; the engine only sees complete HTTP observations for one run. */
public interface AnalysisEngine {
    List<Incident> analyze(UUID runId, Stream<Observation> observations);

    record Observation(String producer, int sequence, UUID requestId, Instant time, JsonNode payload) {
        public record Row(String producer, int sequence, UUID requestId, String payload) {
            public Observation toObservation(JsonMapper json) {
                JsonNode parsed = json.readTree(payload);
                return new Observation(producer, sequence, requestId,
                        OffsetDateTime.parse(parsed.get("time").asString()).toInstant(), parsed);
            }
        }
    }
}
