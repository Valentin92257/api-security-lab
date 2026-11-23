package lab.detector.analysis;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lab.contracts.detector.model.AccessLog;
import lab.contracts.detector.model.AnalysisStatus;
import lab.contracts.detector.model.EventPage;
import lab.contracts.detector.model.Incident;
import lab.contracts.detector.model.IncidentPage;
import lab.contracts.detector.model.LogBatch;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.storage.DetectorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AnalysisService {
    private final DetectorRepository repository;
    private final ContractJsonCodec codec;
    private final Clock clock;
    private final UUID instanceId = UUID.randomUUID();
    private final int maxIncidents;

    public AnalysisService(DetectorRepository repository, ContractJsonCodec codec, Clock clock,
                           @Value("${lab.detector.max-incidents:10000}") int maxIncidents) {
        this.repository = repository;
        this.codec = codec;
        this.clock = clock;
        if (maxIncidents < 1) {
            throw new IllegalArgumentException("Detector max-incidents must be positive");
        }
        this.maxIncidents = maxIncidents;
    }

    @Transactional
    public Prepared prepare(UUID id) {
        boolean created = repository.insertRun(id, instanceId);
        return new Prepared(status(require(id, false)), created);
    }

    @Transactional(readOnly = true)
    public AnalysisStatus get(UUID id) {
        return status(require(id, false));
    }

    @Transactional
    public void ingest(UUID id, LogBatch batch) {
        String producer = batch.getProducer().name();
        RunSnapshot run = require(id, true);
        var progress = repository.progress(id, producer);
        var unique = new LinkedHashMap<Integer, AccessLog>();
        for (AccessLog entry : batch.getEntries()) {
            AccessLog previous = unique.putIfAbsent(entry.getSequence(), entry);
            if (previous != null && !previous.equals(entry)) {
                throw conflict("Разное содержимое одного sequence внутри пачки");
            }
        }
        var additions = new ArrayList<AccessLog>();
        for (AccessLog entry : unique.values()) {
            var saved = repository.findLog(id, producer, entry.getSequence());
            if (saved.isPresent()) {
                if (!saved.get().equals(entry)) {
                    throw conflict("Содержимое сохранённого sequence отличается");
                }
            } else {
                if (!run.active() || run.state().equals("FINISHING")
                        && entry.getSequence() > progress.last()) {
                    throw conflict("Новая запись недопустима в текущем состоянии или за границей finish");
                }
                additions.add(entry);
            }
        }
        // All keys have been checked before inserting any new entry.
        for (AccessLog entry : additions) {
            repository.insertLog(id, producer, entry);
        }
        if (!additions.isEmpty()) {
            repository.advance(id, producer, additions.size(),
                    additions.stream().mapToInt(AccessLog::getSequence).max().orElseThrow());
        }
    }

    @Transactional
    public AnalysisStatus finish(UUID id, FinishSpec finish) {
        RunSnapshot run = require(id, true);
        if (run.finish() != null) {
            if (!run.finish().equals(finish)) {
                throw conflict("Границы или deadline уже зафиксированы другим finish");
            }
            return status(run);
        }
        if (!run.state().equals("ACCEPTING")) {
            throw conflict("Завершённый анализ не возобновляется");
        }
        if (repository.progress(id, "GATEWAY").max() > finish.gatewayLastSequence()
                || repository.progress(id, "SHOP").max() > finish.shopLastSequence()) {
            throw conflict("Граница finish ниже сохранённого sequence");
        }
        repository.finish(id, finish);
        return status(require(id, false));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IncidentPage incidents(UUID id, int limit, int offset) {
        RunSnapshot run = require(id, false);
        if (run.state().equals("FAILED")) {
            throw conflict("Анализ завершился ошибкой; итоговые инциденты недоступны");
        }
        boolean live = !run.state().equals("COMPLETED");
        return new IncidentPage(repository.incidents(id, limit, offset, live), repository.incidentCount(id, live));
    }

    @Transactional(readOnly = true, timeout = 5)
    public EventPage events(EventFilter filter, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Размер страницы должен быть от 1 до 100");
        }
        var position = EventCursor.decode(cursor, filter, codec.jsonMapper());
        if (filter.runId() != null) {
            require(filter.runId(), false);
        }
        var rows = repository.events(filter, position, limit + 1);
        var items = rows.size() > limit ? rows.subList(0, limit) : rows;
        String next = rows.size() > limit ? EventCursor.encode(items.getLast(), filter, codec.jsonMapper()) : null;
        return new EventPage(items, next);
    }

    /** Called inside the worker's repeatable-read transaction, before opening the log cursor. */
    @Transactional(readOnly = true)
    public Input input(UUID id) {
        RunSnapshot run = require(id, false);
        if (!run.active() || expired(run)) {
            return null;
        }
        boolean finalResult = run.state().equals("FINISHING") && repository.completeRanges(id);
        if (!finalResult && run.inputRevision() == run.processedRevision()) {
            return null;
        }
        return new Input(run.inputRevision(), finalResult, checkedObserved(id));
    }

    @Transactional
    public void publish(UUID id, Input input, List<Incident> incidents) {
        RunSnapshot run = require(id, true);
        if (!run.active()) {
            return;
        }
        if (expired(run)) {
            repository.failed(id, "Analysis deadline exceeded");
            return;
        }
        if (input.revision() < run.processedRevision()
                || input.revision() == run.processedRevision() && !input.finalResult()) {
            return;
        }
        if (input.revision() > run.inputRevision()) {
            throw new IllegalStateException("Analysis revision exceeds saved input");
        }
        validateIncidents(incidents, input.observedRequestIds());
        List<Incident> stable = IncidentIdentityResolver.reconcile(repository.liveIncidents(id), incidents);
        validateIncidents(stable, input.observedRequestIds());
        boolean finalResult = input.finalResult() && run.state().equals("FINISHING")
                && input.revision() == run.inputRevision() && repository.completeRanges(id);
        if (finalResult) {
            for (Incident incident : stable) {
                repository.insertIncident(id, incident);
            }
            repository.replaceLiveIncidents(id, List.of());
        } else {
            repository.replaceLiveIncidents(id, stable);
        }
        repository.processed(id, input.revision());
        if (expired(run)) {
            // Roll back the entire snapshot; the worker records FAILED separately.
            throw new IllegalStateException("Analysis deadline exceeded during publication");
        }
        if (finalResult) {
            repository.completed(id);
        }
    }

    private void validateIncidents(List<Incident> incidents, Set<UUID> observed) {
        if (incidents.size() > maxIncidents) {
            throw new IllegalStateException("Incident limit exceeded");
        }
        var incidentIds = new HashSet<UUID>();
        for (Incident incident : incidents) {
            codec.validate(incident);
            if (!incidentIds.add(incident.getIncidentId()) || !observed.containsAll(incident.getRequestIds())) {
                throw new IllegalStateException("Invalid incident evidence or repeated incidentId");
            }
        }
    }

    @Transactional
    public void expire(UUID id) {
        var run = repository.find(id, true);
        if (run.isPresent() && run.get().state().equals("FINISHING") && expired(run.get())) {
            repository.failed(id, "Analysis deadline exceeded");
        }
    }

    @Transactional
    public void fail(UUID id, String error) {
        repository.find(id, true).filter(RunSnapshot::active).ifPresent(run -> repository.failed(id, error));
    }

    @Transactional
    public void interruptPrevious() {
        repository.interruptPrevious();
    }

    private RunSnapshot require(UUID id, boolean lock) {
        return repository.find(id, lock)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Анализ не найден"));
    }

    private AnalysisStatus status(RunSnapshot run) {
        return new AnalysisStatus(instanceId, AnalysisStatus.StateEnum.valueOf(run.state()),
                run.state().equals("COMPLETED") ? checkedObserved(run.id()) : null).error(run.error());
    }

    private Set<UUID> checkedObserved(UUID id) {
        var observed = repository.observed(id);
        if (observed.size() > 100000) {
            throw new IllegalStateException("Observed request ID limit exceeded");
        }
        return observed;
    }

    private boolean expired(RunSnapshot run) {
        return run.finish() != null && !clock.instant().isBefore(run.finish().deadlineAt());
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record Prepared(AnalysisStatus status, boolean created) {
    }

    public record Input(long revision, boolean finalResult, Set<UUID> observedRequestIds) {
        public Input {
            observedRequestIds = Set.copyOf(observedRequestIds);
        }
    }
}
