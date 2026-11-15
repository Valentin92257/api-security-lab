package lab.contracts.json;

import lab.contracts.control.model.CreateTrialRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** JSON serialization for shared contract DTOs, independent of Spring MVC. */
public class ContractJsonCodec {

    private final JsonMapper jsonMapper;
    private final ContractValidator validator;

    public ContractJsonCodec() {
        this(ContractJsonSupport.createJsonMapper(), new ContractValidator());
    }

    public ContractJsonCodec(JsonMapper jsonMapper, ContractValidator validator) {
        Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.jsonMapper = ContractJsonSupport.configure(jsonMapper.rebuild()).build();
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public JsonMapper jsonMapper() {
        return jsonMapper;
    }

    public ContractValidator validator() {
        return validator;
    }

    public <T> T validate(T value) {
        writeAsBytes(value);
        return value;
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new ContractValidationException("JSON input must not be blank");
        }
        return read(json.getBytes(StandardCharsets.UTF_8), type);
    }

    public void validateJson(byte[] jsonBytes, Class<?> type) {
        read(jsonBytes, type);
    }

    /** Reads within the documented byte limit without closing the caller's stream. */
    public <T> T read(InputStream input, Class<T> type) throws IOException {
        Objects.requireNonNull(input, "input must not be null");
        requireContractType(type);
        int limit = ContractValidator.payloadByteLimit(type);
        byte[] bytes = limit == Integer.MAX_VALUE ? input.readAllBytes() : input.readNBytes(limit + 1);
        return read(bytes, type);
    }

    public <T> T read(byte[] jsonBytes, Class<T> type) {
        requireContractType(type);
        if (jsonBytes == null || jsonBytes.length == 0) {
            throw new ContractValidationException("JSON byte input must not be empty");
        }
        ContractValidator.validatePayloadSize(jsonBytes.length, type);
        try {
            JsonNode tree = jsonMapper.readTree(jsonBytes);
            normalizeTrialSeed(tree, type);
            validator.validateJsonTree(tree, type);
            T value = jsonMapper.treeToValue(tree, type);
            validator.validateBean(value);
            return value;
        } catch (JacksonException e) {
            throw new ContractValidationException("Invalid JSON for " + type.getSimpleName() + ": " + e.getMessage(),
                    e);
        }
    }

    public <T> String writeAsString(T value) {
        return new String(writeAsBytes(value), StandardCharsets.UTF_8);
    }

    public <T> byte[] writeAsBytes(T value) {
        if (value == null) {
            throw new ContractValidationException("Contract payload must not be null");
        }
        requireContractType(value.getClass());
        validator.validateBean(value);
        try {
            byte[] bytes = jsonMapper.writeValueAsBytes(value);
            ContractValidator.validatePayloadSize(bytes.length, value.getClass());
            validator.validateJsonTree(jsonMapper.readTree(bytes), value.getClass());
            return bytes;
        } catch (JacksonException e) {
            throw new ContractValidationException(
                    "Failed to serialize " + value.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static void normalizeTrialSeed(JsonNode tree, Class<?> type) {
        if (type != CreateTrialRequest.class || !(tree instanceof ObjectNode object)) {
            return;
        }
        JsonNode seed = object.get("seed");
        if (seed == null || !seed.isNumber() || seed.isIntegralNumber()) {
            return;
        }
        try {
            object.put("seed", seed.decimalValue().intValueExact());
        } catch (ArithmeticException ignored) {
            // Leave fractional and out-of-range values for schema validation.
        }
    }

    private static void requireContractType(Class<?> type) {
        Objects.requireNonNull(type, "type must not be null");
        if (!ContractValidator.supports(type)) {
            throw new IllegalArgumentException("Unsupported contract model: " + type.getName());
        }
    }
}
