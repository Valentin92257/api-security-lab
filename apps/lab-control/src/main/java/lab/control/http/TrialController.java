package lab.control.http;

import java.beans.PropertyEditorSupport;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lab.contracts.control.api.DefaultApi;
import lab.contracts.control.model.CreateTrialRequest;
import lab.contracts.control.model.Trial;
import lab.contracts.control.model.TrialPage;
import lab.contracts.control.model.TrialReport;
import lab.control.trial.TrialService;
import lab.control.trial.TrialSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TrialController implements DefaultApi {
    private final TrialService trials;

    public TrialController(TrialService trials) {
        this.trials = trials;
    }

    @InitBinder
    void strictUuid(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String input) {
                UUID id = UUID.fromString(input);
                if (!id.toString().equalsIgnoreCase(input)) {
                    throw new IllegalArgumentException("Expected a full UUID");
                }
                setValue(id);
            }
        });
    }

    @ModelAttribute
    void requireJsonResponse(NativeWebRequest request) throws HttpMediaTypeNotAcceptableException {
        var accepted = new HeaderContentNegotiationStrategy().resolveMediaTypes(request).stream()
                .filter(type -> type.includes(MediaType.APPLICATION_JSON))
                .reduce((first, next) -> next.removeQualityValue().isMoreSpecific(first.removeQualityValue())
                        ? next : first);
        if (accepted.isEmpty() || accepted.get().getQualityValue() == 0) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }
    }

    @Override
    public ResponseEntity<Trial> startTrial(UUID idempotencyKey, CreateTrialRequest request) {
        var result = trials.create(idempotencyKey,
                request.getFamilies().stream().map(Enum::name).toList(), request.getSeed());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(toResponse(result.trial()));
    }

    @Override
    public ResponseEntity<Trial> getTrial(UUID trialId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(toResponse(trials.get(trialId)));
    }

    @Override
    public ResponseEntity<TrialPage> listTrials(Integer limit, Integer offset) {
        var page = trials.list(limit, offset);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(new TrialPage(page.items().stream().map(TrialController::toResponse).toList(), page.total()));
    }

    @Override
    public ResponseEntity<Set<String>> listScenarios() {
        Set<String> families = Arrays.stream(CreateTrialRequest.FamiliesEnum.values())
                .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(families);
    }

    @Override
    public ResponseEntity<Trial> cancelTrial(UUID trialId) {
        trials.get(trialId);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Отмена эксперимента пока не реализована");
    }

    @Override
    public ResponseEntity<TrialReport> getTrialReport(UUID trialId) {
        trials.get(trialId);
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Отчёт ещё не сформирован");
    }

    private static Trial toResponse(TrialSnapshot trial) {
        Set<Trial.FamiliesEnum> families = trial.families().stream().map(Trial.FamiliesEnum::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new Trial(trial.id(), families, trial.seed(), Trial.StateEnum.valueOf(trial.state()),
                trial.createdAt(), trial.plannedSteps(), trial.finishedSteps(), trial.cleanupSafe()).error(trial.error());
    }
}
