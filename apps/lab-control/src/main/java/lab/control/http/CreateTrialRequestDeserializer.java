package lab.control.http;

import java.util.LinkedHashSet;
import java.util.Set;

import lab.contracts.control.model.CreateTrialRequest;
import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

@JacksonComponent
class CreateTrialRequestDeserializer extends ValueDeserializer<CreateTrialRequest> {
    @Override
    public CreateTrialRequest deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode root = context.readTree(parser);
        if (!root.isObject() || !Set.of("families", "seed").containsAll(root.propertyNames())) {
            return context.reportInputMismatch(CreateTrialRequest.class, "Expected families and optional seed only");
        }
        JsonNode families = root.get("families");
        if (families == null || !families.isArray()) {
            return context.reportInputMismatch(CreateTrialRequest.class, "Families must be an array");
        }
        var values = new LinkedHashSet<CreateTrialRequest.FamiliesEnum>();
        for (JsonNode family : families) {
            if (!family.isString()) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Family must be a string");
            }
            CreateTrialRequest.FamiliesEnum value;
            try {
                value = CreateTrialRequest.FamiliesEnum.fromValue(family.asString());
            } catch (IllegalArgumentException error) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Unknown family");
            }
            if (!values.add(value)) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Repeated family");
            }
        }
        Integer seed = null;
        if (root.has("seed")) {
            JsonNode input = root.get("seed");
            if (!input.isNumber()) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Seed must be an integer in 0..2147483647");
            }
            try {
                seed = input.decimalValue().intValueExact();
            } catch (ArithmeticException error) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Seed must be an integer in 0..2147483647");
            }
        }
        return new CreateTrialRequest(values).seed(seed);
    }
}
