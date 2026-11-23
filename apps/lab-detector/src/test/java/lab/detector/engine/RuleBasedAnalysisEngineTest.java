package lab.detector.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import lab.contracts.detector.model.Incident;
import lab.contracts.json.ContractJsonCodec;
import lab.detector.engine.AnalysisEngine.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBasedAnalysisEngineTest {
    private static final ContractJsonCodec CODEC = new ContractJsonCodec();
    private static final UUID RUN = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Instant START = Instant.parse("2026-11-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
    private static final String ADDRESS = "192.0.2.10";
    private final RuleBasedAnalysisEngine engine = new RuleBasedAnalysisEngine(CLOCK);
    private int sequence;

    static Stream<Arguments> burstBoundaries() {
        return Stream.of(Arguments.of(9, Duration.ZERO, false),
                Arguments.of(10, Duration.ofSeconds(60), true),
                Arguments.of(10, Duration.ofSeconds(60).plusNanos(1), false));
    }

    @ParameterizedTest
    @MethodSource("burstBoundaries")
    void cs01UsesDistinctAttemptsAndAnInclusiveSixtySecondWindow(int count, Duration span, boolean detected) {
        var logs = failures(count, Duration.ZERO, span, 1);
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(detected ? 1 : 0);
        if (detected) {
            assertThat(incidents.getFirst().getDescription()).contains("CS-01").doesNotContain("CS-02", "CS-04", "CS-05");
            assertThat(incidents.getFirst().getRequestIds()).containsExactlyElementsOf(ids(logs));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 14, 15})
    void cs02RequiresTwentyFailuresAndFifteenNonNullHashes(int distinctHashes) {
        var logs = failures(20, Duration.ZERO, Duration.ofMinutes(5), distinctHashes);
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(distinctHashes >= 15 ? 1 : 0);
        if (!incidents.isEmpty()) {
            assertThat(incidents.getFirst().getDescription()).contains("правила CS-02;");
            assertThat(incidents.getFirst().getRequestIds()).containsExactlyElementsOf(ids(logs));
        }
    }

    @Test
    void cs02ExpiresHashesWithoutDroppingOtherOccurrencesOfTheSameHash() {
        var logs = new ArrayList<Observation>();
        logs.add(log(Duration.ZERO, ADDRESS, 401, hash(0)));
        for (int i = 1; i < 20; i++) {
            logs.add(log(Duration.ofSeconds(16L * i), ADDRESS, 401, hash(1 + i % 14)));
        }
        logs.add(log(Duration.ofSeconds(305), ADDRESS, 401, hash(1)));
        assertThat(analyze(logs)).isEmpty();

        logs.add(log(Duration.ofSeconds(306), ADDRESS, 401, hash(15)));
        Incident incident = analyze(logs).getFirst();
        assertThat(incident.getDescription()).contains("правила CS-02;");
        assertThat(incident.getRequestIds()).doesNotContain(logs.getFirst().requestId()).hasSize(21);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cs03IncludesOnlyTheTriggeringFailuresAndSuccessesWithinTwoMinutes(boolean withinBoundary) {
        var failures = failures(10, Duration.ZERO, Duration.ofSeconds(9), 1);
        var logs = new ArrayList<>(failures);
        Observation earlierSuccess = log(Duration.ofSeconds(5), ADDRESS, 200, hash(2));
        Observation limited = log(Duration.ofSeconds(30), ADDRESS, 429, hash(3));
        Observation unrelatedFailure = log(Duration.ofSeconds(70), ADDRESS, 401, hash(4));
        Duration successTime = Duration.ofSeconds(129).plusNanos(withinBoundary ? 0 : 1);
        Observation success = log(successTime, ADDRESS, 200, hash(5));
        logs.addAll(List.of(earlierSuccess, limited, unrelatedFailure, success));

        Incident incident = analyze(logs).getFirst();
        assertThat(incident.getDescription()).contains("CS-01");
        assertThat(incident.getRequestIds()).containsAll(ids(failures))
                .doesNotContain(earlierSuccess.requestId(), limited.requestId(), unrelatedFailure.requestId());
        if (withinBoundary) {
            assertThat(incident.getDescription()).contains("CS-03", "успешных входов 200: 1");
            assertThat(incident.getRequestIds()).contains(success.requestId()).hasSize(11);
        } else {
            assertThat(incident.getDescription()).doesNotContain("CS-03");
            assertThat(incident.getRequestIds()).doesNotContain(success.requestId()).hasSize(10);
        }
    }

    @Test
    void cs03ExtendsTheSequenceForEachSuccessButDoesNotRenewTheTwoMinuteDeadline() {
        var logs = new ArrayList<>(failures(10, Duration.ZERO, Duration.ofSeconds(9), 1));
        var first = log(Duration.ofSeconds(30), ADDRESS, 200, hash(2));
        var second = log(Duration.ofSeconds(129), ADDRESS, 200, hash(3));
        var expired = log(Duration.ofSeconds(130), ADDRESS, 200, hash(4));
        logs.addAll(List.of(first, second, expired));
        Incident incident = analyze(logs).getFirst();
        assertThat(incident.getRequestIds()).hasSize(12).contains(first.requestId(), second.requestId())
                .doesNotContain(expired.requestId());
        assertThat(incident.getDescription()).contains("успешных входов 200: 2");
    }

    static Stream<Arguments> sustainedBoundaries() {
        return Stream.of(Arguments.of(99, Duration.ofMinutes(30), false),
                Arguments.of(100, Duration.ofMinutes(30), true),
                Arguments.of(100, Duration.ofMinutes(30).plusNanos(1), false));
    }

    @ParameterizedTest
    @MethodSource("sustainedBoundaries")
    void cs04DetectsSlowAttemptsAcrossThirtyMinutes(int count, Duration span, boolean detected) {
        var logs = failures(count, Duration.ZERO, span, 1);
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(detected ? 1 : 0);
        if (detected) {
            assertThat(incidents.getFirst().getDescription()).contains("правила CS-04;");
            assertThat(incidents.getFirst().getRequestIds()).containsExactlyElementsOf(ids(logs));
        }
    }

    static Stream<Arguments> deniedBoundaries() {
        return Stream.of(Arguments.of(30, 5, Duration.ofMinutes(5), true),
                Arguments.of(30, 4, Duration.ofMinutes(5), false),
                Arguments.of(29, 5, Duration.ofMinutes(5), false),
                Arguments.of(30, 5, Duration.ofMinutes(5).plusNanos(1), false),
                Arguments.of(100, 0, Duration.ofMinutes(5), false));
    }

    @ParameterizedTest
    @MethodSource("deniedBoundaries")
    void cs05CountsRateLimitsSeparatelyAndRequiresFiveInvalidCredentials(int count, int failures,
                                                                 Duration span, boolean detected) {
        var logs = new ArrayList<Observation>();
        for (int i = 0; i < count; i++) {
            logs.add(log(span.multipliedBy(i).dividedBy(count - 1), ADDRESS, i < failures ? 401 : 429, null));
        }
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(detected ? 1 : 0);
        if (detected) {
            assertThat(incidents.getFirst().getDescription())
                    .contains("правила CS-05;", "отказов 401: 5", "ограничений 429: 25");
            assertThat(incidents.getFirst().getRequestIds()).containsExactlyElementsOf(ids(logs));
        }
    }

    static Stream<Arguments> loginFilters() {
        return Stream.of(
                Arguments.of("SHOP", "POST", null, "/api/v1/auth/login", 401, true),
                Arguments.of("SHOP", "POST", null, "/api/v1/auth/login?a=b", 401, true),
                Arguments.of("SHOP", "POST", "/api/v1/auth/login", "/another-path", 401, true),
                Arguments.of("SHOP", "POST", "/another-route", "/api/v1/auth/login", 401, false),
                Arguments.of("GATEWAY", "POST", null, "/api/v1/auth/login", 401, false),
                Arguments.of("SHOP", "GET", null, "/api/v1/auth/login", 401, false),
                Arguments.of("SHOP", "POST", null, "/api/v1/auth/login-extra", 401, false),
                Arguments.of("SHOP", "POST", null, "/api/v1/auth/login", 500, false),
                Arguments.of("SHOP", "POST", null, "/api/v1/auth/login", 400, false),
                Arguments.of("SHOP", "POST", null, "/api/v1/auth/login", 200, false));
    }

    @ParameterizedTest
    @MethodSource("loginFilters")
    void selectsOnlyShopLoginResults(String producer, String method, String route, String path,
                                    int status, boolean detected) {
        var logs = new ArrayList<Observation>();
        for (int i = 0; i < 10; i++) {
            Observation original = log(Duration.ofSeconds(i), ADDRESS, status, null);
            var payload = (ObjectNode) original.payload();
            payload.put("method", method).put("path", path).put("route", route);
            logs.add(new Observation(producer, original.sequence(), original.requestId(), original.time(), payload));
        }
        assertThat(analyze(logs)).hasSize(detected ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 10})
    void duplicateRequestIdsAndGatewayObservationsCannotInflateCounts(int count) {
        var unique = failures(count, Duration.ZERO, Duration.ofSeconds(9), 1);
        var logs = new ArrayList<>(unique);
        for (Observation original : unique) {
            logs.add(new Observation("GATEWAY", original.sequence(), original.requestId(), original.time(), original.payload()));
            var duplicate = (ObjectNode) original.payload().deepCopy();
            duplicate.put("sequence", original.sequence() + 1000);
            logs.add(new Observation("SHOP", original.sequence() + 1000, original.requestId(), original.time(), duplicate));
        }
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(count >= 10 ? 1 : 0);
        if (!incidents.isEmpty()) {
            assertThat(incidents.getFirst().getRequestIds()).containsExactlyElementsOf(ids(unique));
        }
    }

    @Test
    void longerWindowCanMergeSeveralPreviouslySeparateEpisodes() {
        var logs = new ArrayList<Observation>();
        for (int i = 0; i < 3; i++) {
            logs.addAll(failures(10, Duration.ofSeconds(i * 100L), Duration.ofSeconds(9), 1));
        }
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(1);
        assertThat(incidents.getFirst().getDescription()).contains("правила CS-01, CS-05;");
        assertThat(incidents.getFirst().getRequestIds()).containsExactlyElementsOf(ids(logs));
    }

    @Test
    void separatesAddressesAndNonOverlappingEpisodes() {
        var first = failures(10, Duration.ZERO, Duration.ofSeconds(9), 1);
        var later = failures(10, Duration.ofMinutes(10), Duration.ofSeconds(9), 1);
        var logs = new ArrayList<>(first);
        logs.addAll(later);
        var anotherAddress = new ArrayList<Observation>();
        for (int i = 0; i < 10; i++) {
            anotherAddress.add(log(Duration.ofSeconds(i), "192.0.2.20", 401, null));
        }
        logs.addAll(anotherAddress);
        var incidents = analyze(logs);
        assertThat(incidents).hasSize(3);
        assertThat(incidents.get(0).getRequestIds()).containsExactlyElementsOf(ids(first));
        assertThat(incidents.get(1).getRequestIds()).containsExactlyElementsOf(ids(later));
        assertThat(incidents.get(2).getRequestIds()).containsExactlyElementsOf(ids(anotherAddress));
    }

    @Test
    void stateDoesNotLeakBetweenAddressesOrRuns() {
        var first = failures(9, Duration.ZERO, Duration.ofSeconds(8), 1);
        var logs = new ArrayList<>(first);
        for (int i = 0; i < 9; i++) {
            logs.add(log(Duration.ofSeconds(i), "192.0.2.20", 401, null));
        }
        assertThat(analyze(logs)).isEmpty();
        assertThat(analyze(first)).isEmpty();
        assertThat(analyze(List.of(log(Duration.ofSeconds(9), ADDRESS, 401, null)))).isEmpty();
        assertThat(analyze(List.of())).isEmpty();
    }

    @Test
    void lateDeliveryAndEqualTimesHaveDeterministicEvidenceAndIds() {
        var logs = new ArrayList<>(failures(20, Duration.ZERO, Duration.ZERO, 20));
        Incident original = analyze(logs).getFirst();
        Collections.reverse(logs);
        Incident reordered = analyze(logs).getFirst();
        assertThat(reordered).isEqualTo(original);
        assertThat(original.getDescription()).contains("правила CS-01, CS-02;");
        assertThat(original.getFamily()).isEqualTo(Incident.FamilyEnum.CREDENTIAL_STUFFING);
        assertThat(original.getDetectedAt()).isEqualTo(OffsetDateTime.now(CLOCK));
        CODEC.validate(original);

        var laterEngine = new RuleBasedAnalysisEngine(Clock.offset(CLOCK, Duration.ofHours(1)));
        Incident later = laterEngine.analyze(RUN, logs.stream()).getFirst();
        assertThat(later.getIncidentId()).isEqualTo(original.getIncidentId());
        assertThat(later.getDetectedAt()).isAfter(original.getDetectedAt());
        assertThat(engine.analyze(UUID.randomUUID(), logs.stream()).getFirst().getIncidentId())
                .isNotEqualTo(original.getIncidentId());
    }

    @Test
    void evidenceLimitIsEnforcedWithoutTruncation() {
        var logs = new ArrayList<>(failures(10000, Duration.ZERO, Duration.ofSeconds(59), 1));
        Incident incident = analyze(logs).getFirst();
        assertThat(incident.getRequestIds()).hasSize(10000);
        CODEC.validate(incident);

        logs.add(log(Duration.ofSeconds(60), ADDRESS, 401, hash(1)));
        assertThatThrownBy(() -> analyze(logs)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence limit exceeded");
    }

    private List<Incident> analyze(List<Observation> logs) {
        return engine.analyze(RUN, logs.stream());
    }

    private List<Observation> failures(int count, Duration offset, Duration span, int distinctHashes) {
        return IntStream.range(0, count).mapToObj(index -> log(offset.plus(count == 1 ? Duration.ZERO
                        : span.multipliedBy(index).dividedBy(count - 1)), ADDRESS, 401,
                distinctHashes == 0 ? null : hash(index % distinctHashes))).toList();
    }

    private Observation log(Duration offset, String address, int status, String hash) {
        int seq = ++sequence;
        UUID id = new UUID(0, seq);
        Instant time = START.plus(offset);
        var payload = CODEC.jsonMapper().createObjectNode().put("sequence", seq).put("requestId", id.toString())
                .put("time", time.toString()).put("remote_addr", address).putNull("user")
                .put("method", "POST").put("path", "/api/v1/auth/login").putNull("route")
                .put("status", status).put("bytes", 10).put("request_time", 0.1)
                .putNull("user_agent").put("req_hash", hash);
        return new Observation("SHOP", seq, id, time, payload);
    }

    private static String hash(int value) {
        return "%064x".formatted(value);
    }

    private static List<UUID> ids(List<Observation> logs) {
        return logs.stream().map(Observation::requestId).toList();
    }
}
