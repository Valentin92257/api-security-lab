package lab.detector;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static lab.detector.DetectorTestData.finishBody;
import static lab.detector.DetectorTestData.incident;
import static lab.detector.DetectorTestData.log;
import static lab.detector.DetectorTestData.path;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorIncidentsIntegrationTest extends AbstractDetectorApiIntegrationTest {
    @Test
    void publishesEngineResultsAtomicallyWithStablePaginationAndPreciseTimeOrdering() throws Exception {
        UUID id = prepare();
        UUID request = UUID.randomUUID();
        assertThat(ingest(id, "GATEWAY", log(1, request)).statusCode()).isEqualTo(204);
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        engine.results = List.of(incident(c, request, "2026-11-16T10:00:00.123456002Z"),
                incident(b, request, "2026-11-16T10:00:00.123456001Z"),
                incident(a, request, "2026-11-16T10:00:00.123456001Z"));
        assertThat(control("PUT", path(id) + "/finish", finishBody(1, 0)).statusCode()).isEqualTo(202);
        completed(id);
        for (int offset = 0; offset < 3; offset++) {
            var page = body(control("GET", path(id) + "/incidents?limit=1&offset=" + offset, null));
            assertThat(page.get("total").asLong()).isEqualTo(3);
            assertThat(page.get("items")).hasSize(1);
            assertThat(page.get("items").get(0).get("incidentId").asString())
                    .isEqualTo(List.of(a, b, c).get(offset).toString());
            assertThat(page.get("items").get(0).get("detectedAt").asString())
                    .contains(offset == 2 ? ".123456002" : ".123456001");
        }
        var finalPage = body(control("GET", path(id) + "/incidents?offset=3", null));
        assertThat(finalPage.get("items")).isEmpty();
        assertThat(finalPage.get("total").asLong()).isEqualTo(3);
    }

    @Test
    void engineFailureAndInvalidEvidenceNeverPublishPartialResults() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id)).statusCode()).isEqualTo(204);
        engine.results = List.of(incident(UUID.randomUUID(), id, "2026-11-16T10:00:00Z"),
                incident(UUID.randomUUID(), UUID.randomUUID(), "2026-11-16T10:00:00Z"));
        control("PUT", path(id) + "/finish", finishBody(1, 0));
        failed(id);
        assertThat(count("incidents", id)).isZero();
        UUID other = prepare();
        engine.throwError = true;
        control("PUT", path(other) + "/finish", finishBody(0, 0));
        failed(other);
        assertThat(count("incidents", other)).isZero();
    }

    @Test
    void resultLimitsFailWithoutTruncatingIncidentsOrObservedIds() throws Exception {
        UUID id = prepare();
        assertThat(ingest(id, "GATEWAY", log(1, id)).statusCode()).isEqualTo(204);
        engine.results = java.util.stream.IntStream.range(0, 6)
                .mapToObj(n -> incident(UUID.randomUUID(), id, "2026-11-16T10:00:00Z")).toList();
        control("PUT", path(id) + "/finish", finishBody(1, 0));
        failed(id);
        assertThat(count("incidents", id)).isZero();

        UUID tooManyIds = prepare();
        // Populate a full, valid 100000-entry producer efficiently; the disjoint Shop ID
        // violates the protocol's maximum union of 100000 unique requestIds.
        admin.sql("""
                INSERT INTO detector.access_logs (run_id, producer, sequence, payload, request_id, event_time, event_nanos)
                SELECT :id, 'GATEWAY', n,
                    CAST(:base AS jsonb) || jsonb_build_object('sequence', n, 'requestId', md5(n::text)::uuid),
                    md5(n::text)::uuid, '2026-11-16T10:00:00Z'::timestamptz, 0 FROM generate_series(1, 100000) n
                """).param("id", tooManyIds).param("base", log(1, tooManyIds).toString()).update();
        admin.sql("""
                UPDATE detector.producer_progress SET received_count = 100000, max_sequence = 100000
                WHERE run_id = :id AND producer = 'GATEWAY'
                """).param("id", tooManyIds).update();
        assertThat(ingest(tooManyIds, "SHOP", log(1, UUID.randomUUID())).statusCode()).isEqualTo(204);
        control("PUT", path(tooManyIds) + "/finish", finishBody(100000, 1));
        failed(tooManyIds);
        assertThat(count("access_logs", tooManyIds)).isEqualTo(100001);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=101", "?offset=-1", "?offset=100001"})
    void validatesPagination(String query) throws Exception {
        UUID id = prepare();
        assertProblem(control("GET", path(id) + "/incidents" + query, null), 400, "INVALID_REQUEST");
    }
}
