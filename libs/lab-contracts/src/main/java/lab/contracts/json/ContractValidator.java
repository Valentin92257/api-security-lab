package lab.contracts.json;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import jakarta.validation.Validator;
import lab.contracts.common.model.Problem;
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
import lab.contracts.runtime.model.RunSetup;
import lab.contracts.runtime.model.RuntimeStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates the repository's JSON Schemas. Stateful rules from semantics.md
 * (run lifecycle, evidence and report calculations) belong to the applications.
 */
public class ContractValidator {

    public static final int MAX_ACCESS_LOG_BYTES = 64 * 1024;
    public static final int MAX_LOG_BATCH_BYTES = 8 * 1024 * 1024;
    private static final String SCHEMA_ROOT = "classpath:lab/contracts/schemas/";
    private static final Map<Class<?>, String> SCHEMA_FILES = Map.ofEntries(
            Map.entry(CreateTrialRequest.class, "control.yaml"),
            Map.entry(Trial.class, "control.yaml"),
            Map.entry(TrialPage.class, "control.yaml"),
            Map.entry(CaseResult.class, "control.yaml"),
            Map.entry(IncidentAssessment.class, "control.yaml"),
            Map.entry(RunReport.class, "control.yaml"),
            Map.entry(TrialReport.class, "control.yaml"),
            Map.entry(AccessLog.class, "logs.yaml"),
            Map.entry(LogBatch.class, "logs.yaml"),
            Map.entry(AnalysisStatus.class, "detector.yaml"),
            Map.entry(FinishAnalysisRequest.class, "detector.yaml"),
            Map.entry(Incident.class, "detector.yaml"),
            Map.entry(IncidentPage.class, "detector.yaml"),
            Map.entry(RunSetup.class, "runtime.yaml"),
            Map.entry(RuntimeStatus.class, "runtime.yaml"),
            Map.entry(Problem.class, "common.yaml")
    );

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .formatAssertionsEnabled(true)
                    .locale(Locale.ENGLISH)
                    .build()));
    private final Map<Class<?>, Schema> schemas = new ConcurrentHashMap<>();
    private final JsonMapper objectMapper = ContractJsonSupport.createJsonMapper();
    private final Validator beanValidator;

    /** JSON Schema is sufficient for standalone use; no ValidatorFactory is created. */
    public ContractValidator() {
        this.beanValidator = null;
    }

    /** Also applies the application's Bean Validation configuration. */
    public ContractValidator(Validator beanValidator) {
        this.beanValidator = Objects.requireNonNull(beanValidator, "beanValidator must not be null");
    }

    public static boolean supports(Class<?> type) {
        return SCHEMA_FILES.containsKey(type);
    }

    /** Validates the model's JSON representation, including nested values and byte limits. */
    public <T> T validateObject(T value) {
        if (value == null) {
            throw new ContractValidationException("Contract payload must not be null");
        }
        validateBean(value);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(value);
            validatePayloadSize(bytes.length, value.getClass());
            validateJsonTree(objectMapper.readTree(bytes), value.getClass());
        } catch (JacksonException e) {
            throw new ContractValidationException("Failed to validate " + value.getClass().getSimpleName(), e);
        }
        return value;
    }

    void validateBean(Object value) {
        if (beanValidator == null) {
            return;
        }
        List<String> violations = beanValidator.validate(value).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .toList();
        if (!violations.isEmpty()) {
            throw new ContractValidationException(violations);
        }
    }

    public void validateJsonTree(JsonNode root, Class<?> targetType) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (!supports(targetType)) {
            throw new IllegalArgumentException("Unsupported contract model: " + targetType.getName());
        }
        if (root == null || !root.isObject()) {
            throw new ContractValidationException("Root JSON value for " + targetType.getSimpleName()
                    + " must be a JSON object");
        }
        Schema schema = schemas.computeIfAbsent(targetType, type -> registry.getSchema(
                SchemaLocation.of(SCHEMA_ROOT + SCHEMA_FILES.get(type) + "#/$defs/" + type.getSimpleName())));
        List<String> violations = schema.validate(root).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .sorted()
                .toList();
        if (!violations.isEmpty()) {
            throw new ContractValidationException(violations);
        }
        if (targetType == AccessLog.class) {
            validatePayloadSize(root.toString().getBytes(StandardCharsets.UTF_8).length, AccessLog.class);
        } else if (targetType == LogBatch.class) {
            for (JsonNode entry : root.get("entries")) {
                validatePayloadSize(entry.toString().getBytes(StandardCharsets.UTF_8).length, AccessLog.class);
            }
        }
    }

    static int payloadByteLimit(Class<?> type) {
        if (type == AccessLog.class) {
            return MAX_ACCESS_LOG_BYTES;
        }
        if (type == LogBatch.class) {
            return MAX_LOG_BATCH_BYTES;
        }
        return Integer.MAX_VALUE;
    }

    static void validatePayloadSize(int byteLength, Class<?> type) {
        int limit = payloadByteLimit(type);
        if (byteLength > limit) {
            throw new ContractValidationException(type.getSimpleName() + " size " + byteLength
                    + " exceeds limit of " + limit + " bytes");
        }
    }
}
