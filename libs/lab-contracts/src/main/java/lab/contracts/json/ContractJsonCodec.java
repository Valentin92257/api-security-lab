package lab.contracts.json;

import lab.contracts.control.model.CaseResult;
import lab.contracts.control.model.CreateTrialRequest;
import lab.contracts.control.model.IncidentAssessment;
import lab.contracts.control.model.RunReport;
import lab.contracts.control.model.Trial;
import lab.contracts.control.model.TrialPage;
import lab.contracts.control.model.TrialReport;
import lab.contracts.detector.model.AccessLog;
import lab.contracts.detector.model.AnalysisStatus;
import lab.contracts.detector.model.FinishAnalysisRequest;
import lab.contracts.detector.model.Incident;
import lab.contracts.detector.model.IncidentPage;
import lab.contracts.detector.model.LogBatch;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Validating JSON serializer and deserializer for experiment ({@link Trial}, {@link TrialReport},
 * {@link RunReport}, {@link CaseResult}, {@link IncidentAssessment}), incident ({@link Incident},
 * {@link IncidentPage}, {@link AnalysisStatus}, {@link FinishAnalysisRequest}), and access-log
 * ({@link AccessLog}, {@link LogBatch}, JSONL) schemas.
 */
public class ContractJsonCodec {

    private final JsonMapper jsonMapper;
    private final ContractValidator validator;

    public ContractJsonCodec() {
        this(ContractJsonSupport.createJsonMapper(), new ContractValidator());
    }

    public ContractJsonCodec(JsonMapper jsonMapper, ContractValidator validator) {
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public JsonMapper jsonMapper() {
        return jsonMapper;
    }

    public ContractValidator validator() {
        return validator;
    }

    public <T> T validate(T value) {
        return validator.validateObject(value);
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new ContractValidationException("JSON input must not be blank");
        }
        return read(json.getBytes(StandardCharsets.UTF_8), type);
    }

    public <T> T read(byte[] jsonBytes, Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        if (jsonBytes == null || jsonBytes.length == 0) {
            throw new ContractValidationException("JSON byte input must not be empty");
        }
        enforcePayloadByteLimits(jsonBytes.length, type);
        try {
            JsonNode tree = jsonMapper.readTree(jsonBytes);
            validator.validateJsonTree(tree, type);
            T value = jsonMapper.readValue(jsonBytes, type);
            return validator.validateObject(value);
        } catch (ContractValidationException e) {
            throw e;
        } catch (JacksonException e) {
            throw new ContractValidationException("Invalid JSON for " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public <T> String writeAsString(T value) {
        validator.validateObject(value);
        try {
            String json = jsonMapper.writeValueAsString(value);
            byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
            enforcePayloadByteLimits(utf8.length, value.getClass());
            return json;
        } catch (ContractValidationException e) {
            throw e;
        } catch (JacksonException e) {
            throw new ContractValidationException("Failed to serialize " + value.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public <T> byte[] writeAsBytes(T value) {
        return writeAsString(value).getBytes(StandardCharsets.UTF_8);
    }

    // --- Experiment / Trial contracts ---

    public CreateTrialRequest readCreateTrialRequest(String json) {
        return read(json, CreateTrialRequest.class);
    }

    public String writeCreateTrialRequest(CreateTrialRequest request) {
        return writeAsString(request);
    }

    public Trial readTrial(String json) {
        return read(json, Trial.class);
    }

    public String writeTrial(Trial trial) {
        return writeAsString(trial);
    }

    public TrialPage readTrialPage(String json) {
        return read(json, TrialPage.class);
    }

    public String writeTrialPage(TrialPage page) {
        return writeAsString(page);
    }

    public CaseResult readCaseResult(String json) {
        return read(json, CaseResult.class);
    }

    public String writeCaseResult(CaseResult caseResult) {
        return writeAsString(caseResult);
    }

    public IncidentAssessment readIncidentAssessment(String json) {
        return read(json, IncidentAssessment.class);
    }

    public String writeIncidentAssessment(IncidentAssessment assessment) {
        return writeAsString(assessment);
    }

    public RunReport readRunReport(String json) {
        return read(json, RunReport.class);
    }

    public String writeRunReport(RunReport report) {
        return writeAsString(report);
    }

    public TrialReport readTrialReport(String json) {
        return read(json, TrialReport.class);
    }

    public String writeTrialReport(TrialReport report) {
        return writeAsString(report);
    }

    // --- Incident / Detector contracts ---

    public Incident readIncident(String json) {
        return read(json, Incident.class);
    }

    public String writeIncident(Incident incident) {
        return writeAsString(incident);
    }

    public IncidentPage readIncidentPage(String json) {
        return read(json, IncidentPage.class);
    }

    public String writeIncidentPage(IncidentPage page) {
        return writeAsString(page);
    }

    public AnalysisStatus readAnalysisStatus(String json) {
        return read(json, AnalysisStatus.class);
    }

    public String writeAnalysisStatus(AnalysisStatus status) {
        return writeAsString(status);
    }

    public FinishAnalysisRequest readFinishAnalysisRequest(String json) {
        return read(json, FinishAnalysisRequest.class);
    }

    public String writeFinishAnalysisRequest(FinishAnalysisRequest request) {
        return writeAsString(request);
    }

    // --- AccessLog / LogBatch & JSONL contracts ---

    public AccessLog readAccessLog(String json) {
        return read(json, AccessLog.class);
    }

    public String writeAccessLog(AccessLog entry) {
        return writeAsString(entry);
    }

    public LogBatch readLogBatch(String json) {
        return read(json, LogBatch.class);
    }

    public LogBatch readLogBatch(byte[] jsonBytes) {
        return read(jsonBytes, LogBatch.class);
    }

    public String writeLogBatch(LogBatch batch) {
        return writeAsString(batch);
    }

    public byte[] writeLogBatchAsBytes(LogBatch batch) {
        return writeAsBytes(batch);
    }

    /**
     * Serializes a single {@link AccessLog} record as a compact JSONL line (without newline characters)
     * and validates the 64 KiB per-record limit.
     */
    public String writeAccessLogJsonLine(AccessLog entry) {
        String line = writeAccessLog(entry);
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new ContractValidationException("Serialized AccessLog JSONL line must not contain newline characters");
        }
        return line;
    }

    /**
     * Parses and validates a single JSONL line into an {@link AccessLog}.
     */
    public AccessLog readAccessLogJsonLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            throw new ContractValidationException("AccessLog JSONL line must not be blank");
        }
        if (jsonLine.indexOf('\n') >= 0 || jsonLine.indexOf('\r') >= 0) {
            throw new ContractValidationException("Single AccessLog JSONL line must not contain line breaks");
        }
        return readAccessLog(jsonLine);
    }

    /**
     * Formats a collection of {@link AccessLog} records as newline-delimited JSON (JSONL).
     */
    public String writeAccessLogJsonLines(Collection<AccessLog> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        StringBuilder sb = new StringBuilder();
        for (AccessLog entry : entries) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(writeAccessLogJsonLine(entry));
        }
        return sb.toString();
    }

    /**
     * Parses and validates newline-delimited JSON (JSONL) into a list of {@link AccessLog} records.
     */
    public List<AccessLog> readAccessLogJsonLines(String jsonLines) {
        Objects.requireNonNull(jsonLines, "jsonLines must not be null");
        if (jsonLines.isBlank()) {
            return List.of();
        }
        String[] lines = jsonLines.split("\\r?\\n");
        List<AccessLog> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (!line.isBlank()) {
                result.add(readAccessLogJsonLine(line));
            }
        }
        return List.copyOf(result);
    }

    private static void enforcePayloadByteLimits(int byteLength, Class<?> type) {
        if (type == AccessLog.class && byteLength > ContractValidator.MAX_ACCESS_LOG_BYTES) {
            throw new ContractValidationException(
                    "AccessLog size " + byteLength + " exceeds limit of " + ContractValidator.MAX_ACCESS_LOG_BYTES + " bytes");
        }
        if (type == LogBatch.class && byteLength > ContractValidator.MAX_LOG_BATCH_BYTES) {
            throw new ContractValidationException(
                    "LogBatch size " + byteLength + " exceeds limit of " + ContractValidator.MAX_LOG_BATCH_BYTES + " bytes");
        }
    }
}

