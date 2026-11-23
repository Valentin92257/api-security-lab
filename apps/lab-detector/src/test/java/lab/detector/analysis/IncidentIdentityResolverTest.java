package lab.detector.analysis;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lab.contracts.detector.model.Incident;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentIdentityResolverTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    @Test
    void retainsFirstDetectionWhenLateEarlierEvidenceChangesTheCalculatedId() {
        Incident previous = incident(1, 0, A);
        Incident candidate = incident(2, 5, A, B).description("Updated evidence");
        Incident resolved = IncidentIdentityResolver.reconcile(List.of(previous), List.of(candidate)).getFirst();
        assertThat(resolved.getIncidentId()).isEqualTo(previous.getIncidentId());
        assertThat(resolved.getDetectedAt()).isEqualTo(previous.getDetectedAt());
        assertThat(resolved.getRequestIds()).containsExactlyInAnyOrder(A, B);
        assertThat(resolved.getDescription()).isEqualTo("Updated evidence");
    }

    @Test
    void mergedEpisodeRetainsTheOldestIdentityEvenIfItsCalculatedIdMatchesANewerEpisode() {
        Incident oldest = incident(1, 0, A);
        Incident newer = incident(2, 5, B);
        Incident merged = incident(2, 10, A, B, C);
        var result = IncidentIdentityResolver.reconcile(List.of(newer, oldest), List.of(merged));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getIncidentId()).isEqualTo(oldest.getIncidentId());
        assertThat(result.getFirst().getDetectedAt()).isEqualTo(oldest.getDetectedAt());
        assertThat(result.getFirst().getRequestIds()).containsExactlyInAnyOrder(A, B, C);
    }

    @Test
    void exactIdsPreventUnrelatedFindingsWithSharedEvidenceFromTakingEachOthersIdentity() {
        Incident first = incident(1, 0, A);
        Incident second = incident(2, 1, A);
        var result = IncidentIdentityResolver.reconcile(List.of(first, second),
                List.of(incident(2, 10, A), incident(1, 10, A)));
        assertThat(result).extracting(Incident::getIncidentId)
                .containsExactly(second.getIncidentId(), first.getIncidentId());
        assertThat(result).extracting(Incident::getDetectedAt)
                .containsExactly(second.getDetectedAt(), first.getDetectedAt());
    }

    @Test
    void splitCannotAssignOnePreviousIdToTwoCandidates() {
        Incident previous = incident(1, 0, A, B);
        var result = IncidentIdentityResolver.reconcile(List.of(previous),
                List.of(incident(2, 10, A), incident(3, 10, B)));
        assertThat(result).extracting(Incident::getIncidentId)
                .containsExactly(previous.getIncidentId(), new UUID(1, 3));
    }

    @Test
    void newEvidenceAndDifferentFamiliesKeepTheirOwnIdentity() {
        Incident previous = incident(1, 0, A);
        Incident unrelated = incident(2, 10, B);
        Incident otherFamily = incident(3, 10, A).family(Incident.FamilyEnum.REPLAY);
        var result = IncidentIdentityResolver.reconcile(List.of(previous), List.of(unrelated, otherFamily));
        assertThat(result).containsExactly(unrelated, otherFamily);
        assertThat(IncidentIdentityResolver.reconcile(List.of(previous), List.of())).isEmpty();
    }

    private static Incident incident(int id, int seconds, UUID... requests) {
        return new Incident(new UUID(1, id), OffsetDateTime.parse("2026-11-24T10:00:00Z").plusSeconds(seconds),
                Set.of(requests), "Test evidence").family(Incident.FamilyEnum.CREDENTIAL_STUFFING);
    }
}
