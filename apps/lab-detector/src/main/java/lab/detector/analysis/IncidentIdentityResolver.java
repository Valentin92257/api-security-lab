package lab.detector.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lab.contracts.detector.model.Incident;

/** Preserves first detection identity when late evidence extends or merges episodes. */
final class IncidentIdentityResolver {
    private static final Comparator<Incident> FIRST_DETECTED = Comparator
            .comparing((Incident incident) -> incident.getDetectedAt().toInstant())
            .thenComparing(Incident::getIncidentId);

    static List<Incident> reconcile(List<Incident> previous, List<Incident> candidates) {
        Map<UUID, Incident> byId = new HashMap<>();
        Map<UUID, List<Incident>> byRequest = new HashMap<>();
        for (Incident incident : previous) {
            byId.put(incident.getIncidentId(), incident);
            for (UUID request : incident.getRequestIds()) {
                byRequest.computeIfAbsent(request, ignored -> new ArrayList<>()).add(incident);
            }
        }
        Set<UUID> candidateIds = new HashSet<>();
        candidates.forEach(incident -> candidateIds.add(incident.getIncidentId()));
        Set<UUID> used = new HashSet<>();
        var result = new ArrayList<Incident>();
        for (Incident candidate : candidates) {
            Map<UUID, Incident> matches = new HashMap<>();
            Incident exact = byId.get(candidate.getIncidentId());
            if (exact != null) {
                matches.put(exact.getIncidentId(), exact);
            }
            for (UUID request : candidate.getRequestIds()) {
                for (Incident old : byRequest.getOrDefault(request, List.of())) {
                    matches.put(old.getIncidentId(), old);
                }
            }
            matches.values().removeIf(old -> used.contains(old.getIncidentId())
                    || !Objects.equals(old.getFamily(), candidate.getFamily())
                    || candidateIds.contains(old.getIncidentId())
                    && !old.getIncidentId().equals(candidate.getIncidentId()));
            Incident identity = matches.values().stream().min(FIRST_DETECTED).orElse(candidate);
            used.addAll(matches.keySet());
            result.add(new Incident(identity.getIncidentId(), identity.getDetectedAt(),
                    new LinkedHashSet<>(candidate.getRequestIds()), candidate.getDescription())
                    .family(candidate.getFamily()));
        }
        return List.copyOf(result);
    }

    private IncidentIdentityResolver() {
    }
}
