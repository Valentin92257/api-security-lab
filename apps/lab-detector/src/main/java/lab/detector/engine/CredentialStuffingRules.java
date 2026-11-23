package lab.detector.engine;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lab.detector.engine.RuleBasedAnalysisEngine.LoginAttempt;

/** Fixed v1 thresholds, evaluated in event-time order with inclusive window boundaries. */
final class CredentialStuffingRules {
    enum Rule {
        CS_01("CS-01"), CS_02("CS-02"), CS_03("CS-03"), CS_04("CS-04"), CS_05("CS-05");

        final String id;

        Rule(String id) {
            this.id = id;
        }
    }

    static void evaluate(List<LoginAttempt> attempts, IncidentEpisodes episodes) {
        var burst = new Window(attempts, Duration.ofSeconds(60), false);
        var diverse = new Window(attempts, Duration.ofMinutes(5), true);
        var sustained = new Window(attempts, Duration.ofMinutes(30), false);
        var denied = new Window(attempts, Duration.ofMinutes(5), false);
        int burstFirst = -1;
        int burstLast = -1;

        for (int index = 0; index < attempts.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Credential stuffing analysis interrupted");
            }
            LoginAttempt attempt = attempts.get(index);
            if (attempt.status() == 401) {
                burst.add(index);
                diverse.add(index);
                sustained.add(index);
                if (burst.size() >= 10) {
                    burstFirst = burst.first();
                    burstLast = index;
                    episodes.failures(Rule.CS_01, burstFirst, index);
                }
                if (diverse.size() >= 20 && diverse.hashes.size() >= 15) {
                    episodes.failures(Rule.CS_02, diverse.first(), index);
                }
                if (sustained.size() >= 100) {
                    episodes.failures(Rule.CS_04, sustained.first(), index);
                }
            }
            if (attempt.status() == 401 || attempt.status() == 429) {
                denied.add(index);
                if (denied.size() >= 30 && denied.failures >= 5) {
                    episodes.denied(denied.first(), index);
                }
            }
            if (attempt.status() == 200 && burstLast >= 0
                    && !attempt.time().isAfter(attempts.get(burstLast).time().plusSeconds(120))) {
                episodes.success(burstFirst, burstLast, index);
            }
        }
    }

    private static final class Window {
        private final List<LoginAttempt> attempts;
        private final Duration duration;
        private final boolean trackHashes;
        private final ArrayDeque<Integer> indices = new ArrayDeque<>();
        private final Map<String, Integer> hashes = new HashMap<>();
        private int failures;

        private Window(List<LoginAttempt> attempts, Duration duration, boolean trackHashes) {
            this.attempts = attempts;
            this.duration = duration;
            this.trackHashes = trackHashes;
        }

        private void add(int index) {
            LoginAttempt attempt = attempts.get(index);
            var cutoff = attempt.time().minus(duration);
            while (!indices.isEmpty() && attempts.get(indices.getFirst()).time().isBefore(cutoff)) {
                LoginAttempt expired = attempts.get(indices.removeFirst());
                if (expired.status() == 401) {
                    failures--;
                }
                if (trackHashes && expired.hash() != null) {
                    hashes.compute(expired.hash(), (hash, count) -> count == 1 ? null : count - 1);
                }
            }
            indices.addLast(index);
            if (attempt.status() == 401) {
                failures++;
            }
            if (trackHashes && attempt.hash() != null) {
                hashes.merge(attempt.hash(), 1, Integer::sum);
            }
        }

        private int first() {
            return indices.getFirst();
        }

        private int size() {
            return indices.size();
        }
    }

    private CredentialStuffingRules() {
    }
}
