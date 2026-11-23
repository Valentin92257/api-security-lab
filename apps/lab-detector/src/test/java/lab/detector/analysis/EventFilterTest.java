package lab.detector.analysis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class EventFilterTest {
    static Stream<Arguments> statusFilters() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(List.of(), null),
                Arguments.of(null, "4xx"),
                Arguments.of(List.of(), "4xx"),
                Arguments.of(List.of(403, 401, 403), null)
        );
    }

    @ParameterizedTest
    @MethodSource("statusFilters")
    void normalizedFilterCanBeReconstructedFromItsOwnValues(List<Integer> status, String statusClass) {
        var original = new EventFilter(UUID.randomUUID(), Instant.parse("2026-11-16T10:00:00Z"),
                Instant.parse("2026-11-16T11:00:00Z"), "SHOP", UUID.randomUUID(), "POST",
                status, statusClass, "/api/v1/orders", "192.0.2.1", 17L);

        var copy = new EventFilter(original.runId(), original.from(), original.to(), original.producer(),
                original.requestId(), original.method(), original.status(), original.statusClass(),
                original.route(), original.remoteAddr(), original.userId());

        assertThat(copy).isEqualTo(original);
        if (status == null || status.isEmpty()) {
            assertThat(original.status()).isEmpty();
        } else {
            assertThat(original.status()).containsExactly(401, 403);
        }
    }
}
