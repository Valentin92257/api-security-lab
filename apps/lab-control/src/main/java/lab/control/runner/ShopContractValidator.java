package lab.control.runner;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import lab.contracts.common.model.Problem;
import lab.contracts.json.ContractJsonCodec;
import lab.contracts.json.ContractValidationException;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

@Component
public class ShopContractValidator {
    private final ContractJsonCodec codec;
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .formatAssertionsEnabled(true).locale(Locale.ENGLISH).build()));

    public ShopContractValidator(ContractJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec);
    }

    void validateLoginRequest(String body) {
        Schema schema = registry.getSchema(SchemaLocation.of(
                "classpath:contracts/schemas/shop.yaml#/$defs/LoginRequest"));
        try {
            if (body == null || body.isBlank()) {
                throw new ContractValidationException("Shop request violates contract");
            }
            validate(schema, codec.jsonMapper().readTree(body));
        } catch (JacksonException | ContractValidationException error) {
            throw new ContractValidationException("Shop request violates contract");
        }
    }

    JsonNode validateResponse(ShopRoute route, int status, HttpHeaders headers, byte[] body, UUID requestId) {
        boolean success = status == route.successStatus();
        Schema schema = success ? registry.getSchema(SchemaLocation.of(
                "classpath:contracts/shop-api.openapi.yaml#/components/responses/"
                        + route.responseSchema() + "/content/application~1json/schema")) : null;
        try {
            if ((!success && (status < 400 || status > 599)) || body == null || body.length == 0) {
                throw new ContractValidationException("Shop response violates contract");
            }
            List<String> types = headers.allValues("Content-Type");
            MediaType expectedType = success ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON;
            if (types.size() != 1) {
                throw new ContractValidationException("Shop response violates contract");
            }
            MediaType actualType = MediaType.parseMediaType(types.getFirst());
            if (!actualType.getType().equals(expectedType.getType())
                    || !actualType.getSubtype().equals(expectedType.getSubtype())) {
                throw new ContractValidationException("Shop response violates contract");
            }
            List<String> ids = headers.allValues("X-Request-Id");
            if (ids.size() != 1 || !requestId.toString().equalsIgnoreCase(ids.getFirst())) {
                throw new ContractValidationException("Shop response violates contract");
            }
            JsonNode json = codec.jsonMapper().readTree(body);
            if (success) {
                validate(schema, json);
            } else {
                codec.validator().validateJsonTree(json, Problem.class);
                if (status != json.get("status").intValue()
                        || !requestId.toString().equalsIgnoreCase(json.get("requestId").asString())) {
                    throw new ContractValidationException("Shop response violates contract");
                }
            }
            return json;
        } catch (JacksonException | ContractValidationException | InvalidMediaTypeException error) {
            throw new ContractValidationException("Shop response violates contract");
        }
    }

    private void validate(Schema schema, JsonNode json) {
        if (!schema.validate(json).isEmpty()) {
            throw new ContractValidationException("Shop payload violates contract");
        }
    }
}
