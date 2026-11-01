package lab.control.trial;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrialService {
    private final TrialRepository repository;

    public TrialService(TrialRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public StartResult create(UUID key, List<String> families, Integer requestedSeed) {
        List<String> normalizedFamilies = families.stream().sorted().toList();
        var active = repository.lockSlot();
        var previous = repository.findRequest(key);
        if (previous.isPresent()) {
            var request = previous.get();
            if (!request.trial().families().equals(normalizedFamilies)
                    || !Objects.equals(request.requestedSeed(), requestedSeed)) {
                throw new TrialException(TrialException.Reason.CONFLICT, "Idempotency-Key уже использован с другим запросом");
            }
            return new StartResult(request.trial(), true);
        }
        if (active.isPresent()) {
            throw new TrialException(TrialException.Reason.CONFLICT, "Слот предыдущего эксперимента ещё занят");
        }
        int seed = requestedSeed != null ? requestedSeed : ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        var trial = repository.insert(UUID.randomUUID(), normalizedFamilies, seed, key, requestedSeed);
        return new StartResult(trial, false);
    }

    @Transactional(readOnly = true)
    public TrialSnapshot get(UUID id) {
        return repository.find(id)
                .orElseThrow(() -> new TrialException(TrialException.Reason.NOT_FOUND, "Эксперимент не найден"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrialList list(int limit, int offset) {
        return new TrialList(repository.list(limit, offset), repository.count());
    }

    @Transactional
    public void recoverInterrupted() {
        repository.lockSlot().ifPresent(repository::markInterrupted);
    }

    public record StartResult(TrialSnapshot trial, boolean replayed) {
    }

    public record TrialList(List<TrialSnapshot> items, long total) {
    }
}
