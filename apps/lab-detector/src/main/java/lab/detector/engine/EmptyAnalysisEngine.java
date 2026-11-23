package lab.detector.engine;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import org.springframework.stereotype.Component;

/** Protocol-only implementation. An empty result does not measure detection quality. */
@Component
public class EmptyAnalysisEngine implements AnalysisEngine {
    @Override
    public List<Incident> analyze(UUID runId, Stream<Observation> observations) {
        observations.forEach(observation -> { });
        return List.of();
    }
}
