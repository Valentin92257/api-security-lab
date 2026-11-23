package lab.control.runner;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import lab.contracts.common.model.Problem;
import lab.contracts.json.ContractJsonCodec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ShopContractValidator {
    private final ContractJsonCodec codec;
    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                    .formatAssertionsEnabled(true).locale(Locale.ENGLISH).build()));
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

    public ShopContractValidator(ContractJsonCodec codec) {
        this.codec = Objects.requireNonNull(codec);
    }

    public void validateRequest(ShopRoute route, String body) {
        try {
            if (route.requestSchema() == null) {
                if (body != null && !body.isEmpty()) {
                    throw new IllegalArgumentException();
                }
                return;
            }
            JsonNode json = codec.jsonMapper().readTree(body.getBytes(StandardCharsets.UTF_8));
            validate("classpath:contracts/schemas/shop.yaml#/$defs/" + route.requestSchema(), json);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Shop request violates contract");
        }
    }

    public JsonNode validateResponse(ShopRoute route, int status, HttpHeaders headers, byte[] body, UUID requestId) {
        try {
            boolean success = status == route.successStatus();
            if (!success && (status < 400 || status > 599)) {
                throw new IllegalArgumentException();
            }
            List<String> types = headers.allValues("Content-Type");
            MediaType expectedType = success ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON;
            if (types.size() != 1) {
                throw new IllegalArgumentException();
            }
            MediaType actualType = MediaType.parseMediaType(types.getFirst());
            if (!actualType.getType().equals(expectedType.getType())
                    || !actualType.getSubtype().equals(expectedType.getSubtype())) {
                throw new IllegalArgumentException();
            }
            List<String> ids = headers.allValues("X-Request-Id");
            if (ids.size() != 1 || !requestId.toString().equalsIgnoreCase(ids.getFirst())) {
                throw new IllegalArgumentException();
            }
            if (!success) {
                Problem problem = codec.read(body, Problem.class);
                if (!Integer.valueOf(status).equals(problem.getStatus()) || !requestId.equals(problem.getRequestId())) {
                    throw new IllegalArgumentException();
                }
            }
            JsonNode json = codec.jsonMapper().readTree(body);
            if (success) {
                validate("classpath:contracts/shop-api.openapi.yaml#/components/responses/"
                        + route.responseSchema() + "/content/application~1json/schema", json);
            }
            return json;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Shop response violates contract");
        }
    }

    private void validate(String location, JsonNode json) {
        Schema schema = schemas.computeIfAbsent(location, value -> registry.getSchema(SchemaLocation.of(value)));
        if (!schema.validate(json).isEmpty()) {
            throw new IllegalArgumentException();
        }
    }
}
