package lab.detector.engine;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import lab.contracts.detector.model.Incident;
import lab.detector.engine.CredentialStuffingRules.Rule;
import lab.detector.engine.RuleBasedAnalysisEngine.LoginAttempt;

/** Merges overlapping findings and materializes each evidence request only once. */
final class IncidentEpisodes {
    private static final int MAX_EVIDENCE = 10000;
    private final List<LoginAttempt> attempts;
    private final List<Episode> episodes = new ArrayList<>();
    // Difference arrays avoid copying a growing window for every matching request.
    private final int[] failureCoverage;
    private final int[] limitedCoverage;
    private final boolean[] successEvidence;

    IncidentEpisodes(List<LoginAttempt> attempts) {
        this.attempts = attempts;
        failureCoverage = new int[attempts.size() + 1];
        limitedCoverage = new int[attempts.size() + 1];
        successEvidence = new boolean[attempts.size()];
    }

    void failures(Rule rule, int first, int last) {
        match(rule, first, last);
        cover(failureCoverage, first, last);
    }

    void denied(int first, int last) {
        match(Rule.CS_05, first, last);
        cover(failureCoverage, first, last);
        cover(limitedCoverage, first, last);
    }

    void success(int burstFirst, int burstLast, int success) {
        match(Rule.CS_03, burstFirst, success);
        cover(failureCoverage, burstFirst, burstLast);
        successEvidence[success] = true;
    }

    private void match(Rule rule, int first, int last) {
        var merged = new Episode(first, last, rule);
        // Finding ends are ordered; a longer window can reach back across several
        // earlier episodes, so merge from the end until no overlap remains.
        while (!episodes.isEmpty()) {
            Episode previous = episodes.getLast();
            if (attempts.get(previous.last).time().isBefore(attempts.get(merged.first).time())) {
                break;
            }
            episodes.removeLast();
            merged.first = Math.min(previous.first, merged.first);
            merged.rules.addAll(previous.rules);
        }
        episodes.add(merged);
    }

    List<Incident> incidents(UUID runId, String address, OffsetDateTime detectedAt) {
        if (episodes.isEmpty()) {
            return List.of();
        }
        int failures = 0;
        int limited = 0;
        int episodeIndex = 0;
        for (int index = 0; index < attempts.size(); index++) {
            failures += failureCoverage[index];
            limited += limitedCoverage[index];
            while (episodeIndex < episodes.size() && index > episodes.get(episodeIndex).last) {
                episodeIndex++;
            }
            if (episodeIndex == episodes.size()) {
                break;
            }
            Episode episode = episodes.get(episodeIndex);
            LoginAttempt attempt = attempts.get(index);
            if (index >= episode.first && (attempt.status() == 401 && failures > 0
                    || attempt.status() == 429 && limited > 0 || successEvidence[index])) {
                episode.add(attempt);
            }
        }
        return episodes.stream().map(episode -> episode.incident(runId, address, detectedAt)).toList();
    }

    private static void cover(int[] coverage, int first, int last) {
        coverage[first]++;
        coverage[last + 1]--;
    }

    private final class Episode {
        private int first;
        private final int last;
        private final EnumSet<Rule> rules;
        private final Set<UUID> requestIds = new LinkedHashSet<>();
        private final Set<String> hashes = new HashSet<>();
        private int failures;
        private int limited;
        private int successes;

        private Episode(int first, int last, Rule rule) {
            this.first = first;
            this.last = last;
            this.rules = EnumSet.of(rule);
        }

        private void add(LoginAttempt attempt) {
            requestIds.add(attempt.requestId());
            if (requestIds.size() > MAX_EVIDENCE) {
                throw new IllegalStateException("Credential stuffing incident evidence limit exceeded");
            }
            switch (attempt.status()) {
                case 401 -> failures++;
                case 429 -> limited++;
                case 200 -> successes++;
                default -> throw new IllegalStateException("Unexpected authentication status");
            }
            if (attempt.hash() != null) {
                hashes.add(attempt.hash());
            }
        }

        private Incident incident(UUID runId, String address, OffsetDateTime detectedAt) {
            String identity = RuleBasedAnalysisEngine.RULESET_VERSION + "\n" + runId + "\n" + address
                    + "\n" + requestIds.iterator().next();
            UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
            String description = "Подозрение на автоматизированную проверку credentials. IP %s; интервал %s–%s; "
                    + "правила %s; отказов 401: %d; ограничений 429: %d; успешных входов 200: %d; "
                    + "различных ненулевых req_hash: %d. Компрометация аккаунта не установлена.";
            return new Incident(id, detectedAt, requestIds, description.formatted(address,
                    attempts.get(first).time(), attempts.get(last).time(),
                    rules.stream().map(rule -> rule.id).collect(Collectors.joining(", ")),
                    failures, limited, successes, hashes.size()))
                    .family(Incident.FamilyEnum.CREDENTIAL_STUFFING);
        }
    }
}
