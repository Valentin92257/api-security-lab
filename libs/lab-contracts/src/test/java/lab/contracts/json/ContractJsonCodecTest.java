package lab.contracts.json;

import lab.contracts.control.model.CreateTrialRequest;
import lab.contracts.common.model.Problem;
import lab.contracts.control.model.RunReport;
import lab.contracts.control.model.Trial;
import lab.contracts.control.model.TrialReport;
import lab.contracts.detector.model.AccessLog;
import lab.contracts.detector.model.AnalysisStatus;
import lab.contracts.detector.model.FinishAnalysisRequest;
import lab.contracts.detector.model.Incident;
import lab.contracts.detector.model.LogBatch;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractJsonCodecTest {

    private final ContractJsonCodec codec = new ContractJsonCodec();

    private static Path examplesDir() {
        Path local = Path.of("contracts", "examples");
        if (Files.isDirectory(local)) {
            return local;
        }
        return Path.of("..", "..", "contracts", "examples");
    }

    @Test
    void deserializesAndValidatesAllRepositoryContractExamples() throws Exception {
        Path dir = examplesDir();

        String startTrialJson = Files.readString(dir.resolve("start-trial.json"));
        CreateTrialRequest startTrial = codec.readCreateTrialRequest(startTrialJson);
        assertThat(startTrial.getFamilies()).hasSize(2);
        assertThat(startTrial.getSeed()).isEqualTo(42);
        assertThat(codec.readCreateTrialRequest(codec.writeCreateTrialRequest(startTrial))).isEqualTo(startTrial);

        String reportJson = Files.readString(dir.resolve("report.json"));
        TrialReport report = codec.readTrialReport(reportJson);
        assertThat(report.getTrialId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000030"));
        assertThat(report.getRuns()).hasSize(2);
        assertThat(codec.readTrialReport(codec.writeTrialReport(report))).isEqualTo(report);

        String incidentJson = Files.readString(dir.resolve("incident.json"));
        Incident incident = codec.readIncident(incidentJson);
        assertThat(incident.getIncidentId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000200"));
        assertThat(incident.getFamily()).isNull();
        assertThat(incident.getRequestIds()).hasSize(2);
        assertThat(codec.readIncident(codec.writeIncident(incident))).isEqualTo(incident);

        String finishJson = Files.readString(dir.resolve("finish-analysis.json"));
        FinishAnalysisRequest finish = codec.readFinishAnalysisRequest(finishJson);
        assertThat(finish.getGatewayLastSequence()).isEqualTo(2);
        assertThat(finish.getShopLastSequence()).isEqualTo(2);
        assertThat(codec.readFinishAnalysisRequest(codec.writeFinishAnalysisRequest(finish))).isEqualTo(finish);

        String logBatchJson = Files.readString(dir.resolve("log-batch.json"));
        LogBatch logBatch = codec.readLogBatch(logBatchJson);
        assertThat(logBatch.getProducer()).isEqualTo(LogBatch.ProducerEnum.SHOP);
        assertThat(logBatch.getEntries()).hasSize(2);
        assertThat(codec.readLogBatch(codec.writeLogBatch(logBatch))).isEqualTo(logBatch);
    }

    @Test
    void acceptsIntegralSeedInDecimalAndExponentNotationWithoutRounding() {
        for (String notation : List.of("1.0", "1e2", "2147483647.0")) {
            int expected = new java.math.BigDecimal(notation).intValueExact();
            CreateTrialRequest request = codec.readCreateTrialRequest(
                    "{\"families\":[\"BOLA\"],\"seed\":" + notation + "}");
            assertThat(request.getSeed()).isEqualTo(expected);
        }
        assertThatThrownBy(() -> codec.readCreateTrialRequest(
                "{\"families\":[\"BOLA\"],\"seed\":1.000000000000000001}"))
                .isInstanceOf(ContractValidationException.class);
    }

    @Test
    void rejectsUnknownPropertiesAndDuplicateKeysInTrialRequest() {
        String unknownFieldJson = """
                {
                  "families": ["BOLA"],
                  "seed": 42,
                  "extraField": true
                }
                """;
        assertThatThrownBy(() -> codec.readCreateTrialRequest(unknownFieldJson))
                .isInstanceOf(ContractValidationException.class);

        String duplicateKeyJson = """
                {
                  "families": ["BOLA"],
                  "seed": 42,
                  "seed": 43
                }
                """;
        assertThatThrownBy(() -> codec.readCreateTrialRequest(duplicateKeyJson))
                .isInstanceOf(ContractValidationException.class);
    }

    @Test
    void rejectsDuplicateItemsInUniqueArraysAndInvalidScalarTypes() {
        String duplicateFamiliesJson = """
                {
                  "families": ["BOLA", "BOLA"],
                  "seed": 42
                }
                """;
        assertThatThrownBy(() -> codec.readCreateTrialRequest(duplicateFamiliesJson))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("unique items");

        String stringSeedJson = """
                {
                  "families": ["BOLA"],
                  "seed": "42"
                }
                """;
        assertThatThrownBy(() -> codec.readCreateTrialRequest(stringSeedJson))
                .isInstanceOf(ContractValidationException.class);

        String floatSeedJson = """
                {
                  "families": ["BOLA"],
                  "seed": 42.5
                }
                """;
        assertThatThrownBy(() -> codec.readCreateTrialRequest(floatSeedJson))
                .isInstanceOf(ContractValidationException.class);
    }

    @Test
    void rejectsInvalidNullableFamilyEnumAndDuplicateRequestIdsInIncident() {
        String invalidFamilyJson = """
                {
                  "incidentId": "00000000-0000-4000-8000-000000000200",
                  "family": "UNKNOWN_ATTACK",
                  "detectedAt": "2026-10-23T12:00:00Z",
                  "requestIds": ["00000000-0000-4000-8000-000000000010"],
                  "description": "Подозрительный запрос"
                }
                """;
        assertThatThrownBy(() -> codec.readIncident(invalidFamilyJson))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("family");

        String duplicateRequestIdsJson = """
                {
                  "incidentId": "00000000-0000-4000-8000-000000000200",
                  "family": null,
                  "detectedAt": "2026-10-23T12:00:00Z",
                  "requestIds": [
                    "00000000-0000-4000-8000-000000000010",
                    "00000000-0000-4000-8000-000000000010"
                  ],
                  "description": "Подозрительный запрос"
                }
                """;
        assertThatThrownBy(() -> codec.readIncident(duplicateRequestIdsJson))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("unique items");
    }

    @Test
    void usesSingleSharedIncidentModelInIncidentAssessment() {
        var incident = new Incident(
                UUID.fromString("00000000-0000-4000-8000-000000000200"),
                OffsetDateTime.parse("2026-10-23T12:00:00Z"),
                Set.of(UUID.fromString("00000000-0000-4000-8000-000000000010")),
                "BOLA attempt"
        ).family(Incident.FamilyEnum.BOLA);

        var assessment = new lab.contracts.control.model.IncidentAssessment(
                incident,
                lab.contracts.control.model.IncidentAssessment.VerdictEnum.TRUE_POSITIVE,
                Set.of("BO-01-episode"),
                "Confirmed BOLA attempt"
        );
        String json = codec.writeIncidentAssessment(assessment);
        var restored = codec.readIncidentAssessment(json);
        assertThat(restored.getIncident()).isEqualTo(incident);
    }

    @Test
    void enforcesRequiredNullablePropertiesOnAccessLogRunReportAndAnalysisStatus() {
        // Missing "user", "route", "user_agent", "req_hash" keys in AccessLog must fail even though they are nullable
        String accessLogMissingNullableKeys = """
                {
                  "sequence": 1,
                  "requestId": "00000000-0000-4000-8000-000000000010",
                  "time": "2026-10-23T12:00:00Z",
                  "remote_addr": "192.0.2.10",
                  "method": "GET",
                  "path": "/api/v1/orders/123",
                  "status": 404,
                  "bytes": 162,
                  "request_time": 0.012
                }
                """;
        assertThatThrownBy(() -> codec.readAccessLog(accessLogMissingNullableKeys))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("user");

        // Present with null values is valid
        String accessLogWithExplicitNulls = """
                {
                  "sequence": 1,
                  "requestId": "00000000-0000-4000-8000-000000000010",
                  "time": "2026-10-23T12:00:00Z",
                  "remote_addr": "192.0.2.10",
                  "user": null,
                  "method": "GET",
                  "path": "/api/v1/orders/123",
                  "route": null,
                  "status": 401,
                  "bytes": 120,
                  "request_time": 0.005,
                  "user_agent": null,
                  "req_hash": null
                }
                """;
        AccessLog log = codec.readAccessLog(accessLogWithExplicitNulls);
        assertThat(log.getUser()).isNull();
        assertThat(log.getRoute()).isNull();

        // Missing "incidents" in RunReport must fail, whereas "incidents": null is valid
        String runReportMissingIncidents = """
                {
                  "runId": "00000000-0000-4000-8000-000000000020",
                  "mode": "VULNERABLE",
                  "state": "INCONCLUSIVE",
                  "cases": []
                }
                """;
        assertThatThrownBy(() -> codec.readRunReport(runReportMissingIncidents))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("incidents");

        String runReportNullIncidents = """
                {
                  "runId": "00000000-0000-4000-8000-000000000020",
                  "mode": "VULNERABLE",
                  "state": "INCONCLUSIVE",
                  "cases": [],
                  "incidents": null
                }
                """;
        RunReport runReport = codec.readRunReport(runReportNullIncidents);
        assertThat(runReport.getIncidents()).isNull();

        // Missing "observedRequestIds" in AnalysisStatus must fail, whereas "observedRequestIds": null is valid
        String statusMissingObserved = """
                {
                  "instanceId": "00000000-0000-4000-8000-000000000099",
                  "state": "ACCEPTING"
                }
                """;
        assertThatThrownBy(() -> codec.readAnalysisStatus(statusMissingObserved))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("observedRequestIds");

        String statusNullObserved = """
                {
                  "instanceId": "00000000-0000-4000-8000-000000000099",
                  "state": "ACCEPTING",
                  "observedRequestIds": null
                }
                """;
        AnalysisStatus status = codec.readAnalysisStatus(statusNullObserved);
        assertThat(status.getObservedRequestIds()).isNull();
    }

    @Test
    void writesAndReadsAccessLogJsonLines() throws Exception {
        String logBatchJson = Files.readString(examplesDir().resolve("log-batch.json"));
        LogBatch batch = codec.readLogBatch(logBatchJson);

        String jsonl = codec.writeAccessLogJsonLines(batch.getEntries());
        assertThat(jsonl.lines().count()).isEqualTo(2);

        List<AccessLog> parsed = codec.readAccessLogJsonLines(jsonl);
        assertThat(parsed).isEqualTo(batch.getEntries());
    }

    @Test
    void validatesProblemCodeAndStatusConsistency() {
        var validProblem = new Problem(
                Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR,
                "Conflict error",
                409,
                Problem.CodeEnum.CONFLICT,
                UUID.fromString("00000000-0000-4000-8000-000000000010")
        );
        String json = codec.writeAsString(validProblem);
        assertThat(codec.read(json, Problem.class)).isEqualTo(validProblem);

        var mismatchedStatusProblem = new Problem(
                Problem.TypeEnum.URN_LAB_PROBLEM_HTTP_ERROR,
                "Wrong status for conflict",
                400,
                Problem.CodeEnum.CONFLICT,
                UUID.fromString("00000000-0000-4000-8000-000000000010")
        );
        assertThatThrownBy(() -> codec.writeAsString(mismatchedStatusProblem))
                .isInstanceOf(ContractValidationException.class)
                .hasMessageContaining("status");
    }

    @Test
    void serializesAndDeserializesTrialState() {
        var trial = new Trial(
                UUID.fromString("00000000-0000-4000-8000-000000000030"),
                Set.of(Trial.FamiliesEnum.BOLA, Trial.FamiliesEnum.REPLAY),
                42,
                Trial.StateEnum.RUNNING,
                OffsetDateTime.parse("2026-10-23T12:00:00Z"),
                10L,
                3L,
                false
        );
        String json = codec.writeTrial(trial);
        Trial restored = codec.readTrial(json);
        assertThat(restored).isEqualTo(trial);
    }

}
