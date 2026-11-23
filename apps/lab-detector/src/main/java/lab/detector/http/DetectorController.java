package lab.detector.http;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import lab.contracts.detector.api.DefaultApi;
import lab.contracts.detector.model.AnalysisStatus;
import lab.contracts.detector.model.EventPage;
import lab.contracts.detector.model.FinishAnalysisRequest;
import lab.contracts.detector.model.IncidentPage;
import lab.contracts.detector.model.LogBatch;
import lab.detector.analysis.AnalysisService;
import lab.detector.analysis.EventFilter;
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
    private static final Set<String> EVENT_PARAMETERS = Set.of("runId", "from", "to", "producer", "requestId",
            "method", "status", "statusClass", "route", "remoteAddr", "userId", "limit", "cursor");
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

    @Override
    public ResponseEntity<EventPage> listEvents(UUID runId, OffsetDateTime from, OffsetDateTime to, String producer,
                                               UUID requestId, String method, List<Integer> status, String statusClass,
                                               String route, String remoteAddr, Long userId, Integer limit, String cursor) {
        request.getParameterMap().forEach((name, values) -> {
            if (!EVENT_PARAMETERS.contains(name)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестный параметр просмотра событий");
            }
            if (!name.equals("status") && values.length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Параметр " + name + " нельзя повторять");
            }
            if (Arrays.stream(values).anyMatch(String::isBlank)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Параметр " + name + " не должен быть пустым");
            }
        });
        var filter = new EventFilter(runId, from == null ? null : from.toInstant(), to == null ? null : to.toInstant(),
                producer, requestId, method, status, statusClass, route, remoteAddr, userId);
        // Reading events must never fail an analysis through execute().
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(analyses.events(filter, limit, cursor));
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
