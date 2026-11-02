package lab.contracts.json;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
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
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Enforces JSON Schema constraints from {@code contracts/schemas/*.yaml} and {@code contracts/semantics.md}
 * in combination with Jakarta Bean Validation.
 */
public class ContractValidator {

    public static final int MAX_ACCESS_LOG_BYTES = 64 * 1024;
    public static final int MAX_LOG_BATCH_BYTES = 8 * 1024 * 1024;
    public static final String PROBLEM_TYPE_URI = "urn:lab:problem:http-error";

    private static final Set<String> VALID_FAMILIES = Set.of(
            "CREDENTIAL_STUFFING",
            "BOLA",
            "REPLAY",
            "INJECTION",
            "BUSINESS_LOGIC"
    );

    private static final List<String> ACCESS_LOG_REQUIRED_FIELDS = List.of(
            "sequence",
            "requestId",
            "time",
            "remote_addr",
            "user",
            "method",
            "path",
            "route",
            "status",
            "bytes",
            "request_time",
            "user_agent",
            "req_hash"
    );

    private final Validator beanValidator;

    public ContractValidator() {
        this(createDefaultBeanValidator());
    }

    public ContractValidator(Validator beanValidator) {
        this.beanValidator = Objects.requireNonNull(beanValidator, "beanValidator must not be null");
    }

    private static Validator createDefaultBeanValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        return factory.getValidator();
    }

    public <T> T validateObject(T value) {
        if (value == null) {
            throw new ContractValidationException("Contract payload must not be null");
        }
        List<String> violations = new ArrayList<>();
        Set<ConstraintViolation<T>> constraintViolations = beanValidator.validate(value);
        for (ConstraintViolation<T> cv : constraintViolations) {
            violations.add(cv.getPropertyPath() + ": " + cv.getMessage());
        }
        validateDomainRules(value, "", violations);
        if (!violations.isEmpty()) {
            violations.sort(String::compareTo);
            throw new ContractValidationException(violations);
        }
        return value;
    }

    public void validateJsonTree(JsonNode root, Class<?> targetType) {
        if (root == null || !root.isObject()) {
            throw new ContractValidationException("Root JSON value for " + targetType.getSimpleName() + " must be a JSON object");
        }
        List<String> violations = new ArrayList<>();
        validateNodeByType(root, targetType, "", violations);
        if (!violations.isEmpty()) {
            throw new ContractValidationException(violations);
        }
    }

    private void validateNodeByType(JsonNode node, Class<?> type, String path, List<String> violations) {
        if (node == null || !node.isObject()) {
            violations.add(prefix(path) + "must be a JSON object");
            return;
        }
        if (type == AccessLog.class) {
            validateAccessLogNode(node, path, violations);
        } else if (type == LogBatch.class) {
            validateLogBatchNode(node, path, violations);
        } else if (type == Incident.class) {
            validateIncidentNode(node, path, violations);
        } else if (type == IncidentPage.class) {
            validateIncidentPageNode(node, path, violations);
        } else if (type == AnalysisStatus.class) {
            validateAnalysisStatusNode(node, path, violations);
        } else if (type == FinishAnalysisRequest.class) {
            validateFinishAnalysisNode(node, path, violations);
        } else if (type == CreateTrialRequest.class) {
            validateCreateTrialRequestNode(node, path, violations);
        } else if (type == Trial.class) {
            validateTrialNode(node, path, violations);
        } else if (type == TrialPage.class) {
            validateTrialPageNode(node, path, violations);
        } else if (type == CaseResult.class) {
            validateCaseResultNode(node, path, violations);
        } else if (type == IncidentAssessment.class) {
            validateIncidentAssessmentNode(node, path, violations);
        } else if (type == RunReport.class) {
            validateRunReportNode(node, path, violations);
        } else if (type == TrialReport.class) {
            validateTrialReportNode(node, path, violations);
        }
    }

    private void validateAccessLogNode(JsonNode node, String path, List<String> violations) {
        for (String field : ACCESS_LOG_REQUIRED_FIELDS) {
            if (!node.has(field)) {
                violations.add(prefix(path) + "missing required property '" + field + "'");
            }
        }
        int entryBytes = node.toString().getBytes(StandardCharsets.UTF_8).length;
        if (entryBytes > MAX_ACCESS_LOG_BYTES) {
            violations.add(prefix(path) + "AccessLog size " + entryBytes + " exceeds limit of " + MAX_ACCESS_LOG_BYTES + " bytes");
        }
        requireIntegralIfPresent(node, "sequence", path, false, violations);
        requireIntegralIfPresent(node, "user", path, true, violations);
        requireIntegralIfPresent(node, "status", path, false, violations);
        requireIntegralIfPresent(node, "bytes", path, false, violations);
        if (node.has("request_time") && !node.get("request_time").isNull() && !node.get("request_time").isNumber()) {
            violations.add(prefix(path) + "request_time must be a number");
        }
    }

    private void validateLogBatchNode(JsonNode node, String path, List<String> violations) {
        JsonNode entries = node.get("entries");
        if (entries != null && entries.isArray()) {
            for (int i = 0; i < entries.size(); i++) {
                validateAccessLogNode(entries.get(i), joinPath(path, "entries[" + i + "]"), violations);
            }
        }
    }

    private void validateIncidentNode(JsonNode node, String path, List<String> violations) {
        if (node.has("family")) {
            JsonNode familyNode = node.get("family");
            if (!familyNode.isNull()) {
                if (!familyNode.isString() || !VALID_FAMILIES.contains(familyNode.stringValue())) {
                    violations.add(prefix(path) + "family must be one of " + VALID_FAMILIES + " or null");
                }
            }
        }
        validateUniqueArrayItems(node.get("requestIds"), joinPath(path, "requestIds"), violations);
    }

    private void validateIncidentPageNode(JsonNode node, String path, List<String> violations) {
        requireIntegralIfPresent(node, "total", path, false, violations);
        JsonNode items = node.get("items");
        if (items != null && items.isArray()) {
            for (int i = 0; i < items.size(); i++) {
                validateIncidentNode(items.get(i), joinPath(path, "items[" + i + "]"), violations);
            }
        }
    }

    private void validateAnalysisStatusNode(JsonNode node, String path, List<String> violations) {
        if (!node.has("observedRequestIds")) {
            violations.add(prefix(path) + "missing required property 'observedRequestIds'");
        } else {
            JsonNode observed = node.get("observedRequestIds");
            if (!observed.isNull()) {
                validateUniqueArrayItems(observed, joinPath(path, "observedRequestIds"), violations);
            }
        }
    }

    private void validateFinishAnalysisNode(JsonNode node, String path, List<String> violations) {
        requireIntegralIfPresent(node, "gatewayLastSequence", path, false, violations);
        requireIntegralIfPresent(node, "shopLastSequence", path, false, violations);
    }

    private void validateCreateTrialRequestNode(JsonNode node, String path, List<String> violations) {
        validateUniqueArrayItems(node.get("families"), joinPath(path, "families"), violations);
        if (node.has("seed")) {
            requireIntegralIfPresent(node, "seed", path, false, violations);
        }
    }

    private void validateTrialNode(JsonNode node, String path, List<String> violations) {
        validateUniqueArrayItems(node.get("families"), joinPath(path, "families"), violations);
        requireIntegralIfPresent(node, "seed", path, false, violations);
        requireIntegralIfPresent(node, "plannedSteps", path, false, violations);
        requireIntegralIfPresent(node, "finishedSteps", path, false, violations);
    }

    private void validateTrialPageNode(JsonNode node, String path, List<String> violations) {
        requireIntegralIfPresent(node, "total", path, false, violations);
        JsonNode items = node.get("items");
        if (items != null && items.isArray()) {
            for (int i = 0; i < items.size(); i++) {
                validateTrialNode(items.get(i), joinPath(path, "items[" + i + "]"), violations);
            }
        }
    }

    private void validateCaseResultNode(JsonNode node, String path, List<String> violations) {
        validateUniqueArrayItems(node.get("requestIds"), joinPath(path, "requestIds"), violations);
    }

    private void validateIncidentAssessmentNode(JsonNode node, String path, List<String> violations) {
        if (node.has("incident") && node.get("incident").isObject()) {
            validateIncidentNode(node.get("incident"), joinPath(path, "incident"), violations);
        }
        validateUniqueArrayItems(node.get("matchedEpisodeIds"), joinPath(path, "matchedEpisodeIds"), violations);
    }

    private void validateRunReportNode(JsonNode node, String path, List<String> violations) {
        if (!node.has("incidents")) {
            violations.add(prefix(path) + "missing required property 'incidents'");
        }
        JsonNode cases = node.get("cases");
        if (cases != null && cases.isArray()) {
            for (int i = 0; i < cases.size(); i++) {
                validateCaseResultNode(cases.get(i), joinPath(path, "cases[" + i + "]"), violations);
            }
        }
        JsonNode incidents = node.get("incidents");
        if (incidents != null && incidents.isArray()) {
            for (int i = 0; i < incidents.size(); i++) {
                validateIncidentAssessmentNode(incidents.get(i), joinPath(path, "incidents[" + i + "]"), violations);
            }
        }
    }

    private void validateTrialReportNode(JsonNode node, String path, List<String> violations) {
        requireIntegralIfPresent(node, "seed", path, false, violations);
        validateUniqueArrayItems(node.get("families"), joinPath(path, "families"), violations);
        JsonNode runs = node.get("runs");
        if (runs != null && runs.isArray()) {
            for (int i = 0; i < runs.size(); i++) {
                validateRunReportNode(runs.get(i), joinPath(path, "runs[" + i + "]"), violations);
            }
        }
    }

    private void validateUniqueArrayItems(JsonNode arrayNode, String path, List<String> violations) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        Set<JsonNode> seen = new HashSet<>();
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode item = arrayNode.get(i);
            if (!seen.add(item)) {
                violations.add(prefix(path) + "must contain unique items (duplicate at index " + i + ")");
                return;
            }
        }
    }

    private void requireIntegralIfPresent(
            JsonNode node, String field, String path, boolean nullable, List<String> violations) {
        if (!node.has(field)) {
            return;
        }
        JsonNode value = node.get(field);
        if (value.isNull()) {
            if (!nullable) {
                violations.add(prefix(joinPath(path, field)) + "must not be null");
            }
            return;
        }
        if (!value.isIntegralNumber()) {
            violations.add(prefix(joinPath(path, field)) + "must be an integer");
        }
    }

    private void validateDomainRules(Object value, String path, List<String> violations) {
        switch (value) {
            case AccessLog log -> validateAccessLogObject(log, path, violations);
            case LogBatch batch -> {
                if (batch.getEntries() != null) {
                    for (int i = 0; i < batch.getEntries().size(); i++) {
                        AccessLog entry = batch.getEntries().get(i);
                        if (entry != null) {
                            validateAccessLogObject(entry, joinPath(path, "entries[" + i + "]"), violations);
                        }
                    }
                }
            }
            case lab.contracts.control.model.Problem p ->
                    validateProblemFields(
                            p.getType() == null ? null : p.getType().getValue(),
                            p.getStatus(),
                            p.getCode() == null ? null : p.getCode().getValue(),
                            path,
                            violations);
            case lab.contracts.detector.model.Problem p ->
                    validateProblemFields(
                            p.getType() == null ? null : p.getType().getValue(),
                            p.getStatus(),
                            p.getCode() == null ? null : p.getCode().getValue(),
                            path,
                            violations);
            case lab.contracts.runtime.model.Problem p ->
                    validateProblemFields(
                            p.getType() == null ? null : p.getType().getValue(),
                            p.getStatus(),
                            p.getCode() == null ? null : p.getCode().getValue(),
                            path,
                            violations);
            default -> {
            }
        }
    }

    private void validateAccessLogObject(AccessLog log, String path, List<String> violations) {
        Double requestTime = log.getRequestTime();
        if (requestTime != null && (!Double.isFinite(requestTime) || requestTime < 0.0)) {
            violations.add(prefix(joinPath(path, "requestTime")) + "must be a finite number >= 0");
        }
    }

    private void validateProblemFields(
            String type, Integer status, String code, String path, List<String> violations) {
        if (type != null && !PROBLEM_TYPE_URI.equals(type)) {
            violations.add(prefix(joinPath(path, "type")) + "must equal '" + PROBLEM_TYPE_URI + "'");
        }
        if (code == null || status == null) {
            return;
        }
        boolean statusMatchesCode = switch (code) {
            case "INVALID_REQUEST" -> status == 400 || status == 405 || status == 413 || status == 415;
            case "UNAUTHORIZED" -> status == 401;
            case "FORBIDDEN" -> status == 403;
            case "NOT_FOUND" -> status == 404;
            case "CONFLICT" -> status == 409;
            case "RATE_LIMITED" -> status == 429;
            case "INTERNAL_ERROR" -> status == 500;
            case "UNAVAILABLE" -> status == 503;
            default -> false;
        };
        if (!statusMatchesCode) {
            violations.add(prefix(path) + "status " + status + " does not match problem code " + code);
        }
    }

    private static String joinPath(String parent, String child) {
        return (parent == null || parent.isEmpty()) ? child : parent + "." + child;
    }

    private static String prefix(String path) {
        return (path == null || path.isEmpty()) ? "" : path + ": ";
    }
}
