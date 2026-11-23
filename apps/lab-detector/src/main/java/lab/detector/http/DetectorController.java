package lab.detector.http;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import lab.contracts.detector.api.DefaultApi;
import lab.contracts.detector.model.AnalysisStatus;
import lab.contracts.detector.model.FinishAnalysisRequest;
import lab.contracts.detector.model.IncidentPage;
import lab.contracts.detector.model.LogBatch;
import lab.detector.analysis.AnalysisService;
import lab.detector.analysis.FinishSpec;
import lab.detector.security.ServiceAccessFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DetectorController implements DefaultApi {
    private final AnalysisService analyses;
    private final HttpServletRequest request;

    public DetectorController(AnalysisService analyses, HttpServletRequest request) {
        this.analyses = analyses;
        this.request = request;
    }

    @ModelAttribute
    void requireJsonResponse(NativeWebRequest webRequest) throws HttpMediaTypeNotAcceptableException {
        if (request.getMethod().equals("POST") && request.getServletPath().endsWith("/log-batches")) {
            return; // Successful ingest has no response body.
        }
        var accepted = new HeaderContentNegotiationStrategy().resolveMediaTypes(webRequest).stream()
                .filter(type -> type.includes(MediaType.APPLICATION_JSON))
                .reduce((first, next) -> next.removeQualityValue().isMoreSpecific(first.removeQualityValue()) ? next : first);
        if (accepted.isEmpty() || accepted.get().getQualityValue() == 0) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }
    }

    @Override
    public ResponseEntity<AnalysisStatus> prepareAnalysis(UUID runId) {
        return execute(runId, () -> {
            var result = analyses.prepare(runId);
            return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .contentType(MediaType.APPLICATION_JSON).body(result.status());
        });
    }

    @Override
    public ResponseEntity<AnalysisStatus> getAnalysisStatus(UUID runId) {
        return execute(runId, () -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(analyses.get(runId)));
    }

    @Override
    @PostMapping(path = DefaultApi.PATH_INGEST_LOGS, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> ingestLogs(UUID runId, LogBatch batch) {
        if (!batch.getProducer().name().equals(request.getAttribute(ServiceAccessFilter.ROLE))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Токен не разрешает запись этого producer");
        }
        return execute(runId, () -> {
            analyses.ingest(runId, batch);
            return ResponseEntity.noContent().build();
        });
    }

    @Override
    public ResponseEntity<AnalysisStatus> finishAnalysis(UUID runId, FinishAnalysisRequest finish) {
        return execute(runId, () -> ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON)
                .body(analyses.finish(runId, FinishSpec.from(finish))));
    }

    @Override
    public ResponseEntity<IncidentPage> listFinalIncidents(UUID runId, Integer limit, Integer offset) {
        return execute(runId, () -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(analyses.incidents(runId, limit, offset)));
    }

    private <T> T execute(UUID id, Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ResponseStatusException protocolError) {
            throw protocolError;
        } catch (RuntimeException error) {
            // Service transaction has already rolled back; record failure separately if storage is available.
            try {
                analyses.fail(id, "Detector operation failed; the run must be repeated");
            } catch (RuntimeException unavailable) {
                // The original failure is mapped to a safe Problem, never a successful acknowledgement.
            }
            throw error;
        }
    }
}
