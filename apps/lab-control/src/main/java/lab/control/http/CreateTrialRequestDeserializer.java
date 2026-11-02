package lab.control.http;

import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
        Input input = context.readValue(parser, Input.class);
        var families = input.families() == null ? null : new LinkedHashSet<>(input.families());
        if (families != null && families.size() != input.families().size()) {
            return context.reportInputMismatch(CreateTrialRequest.class, "Repeated family");
        }
        Integer seed = null;
        if (input.seed() != null) {
            if (!input.seed().isNumber()) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Seed must be an integer in 0..2147483647");
            }
            try {
                seed = input.seed().decimalValue().intValueExact();
            } catch (ArithmeticException error) {
                return context.reportInputMismatch(CreateTrialRequest.class, "Seed must be an integer in 0..2147483647");
            }
        }
        return new CreateTrialRequest(families).seed(seed);
    }

    private record Input(@JsonSetter(contentNulls = Nulls.FAIL) List<CreateTrialRequest.FamiliesEnum> families,
                         JsonNode seed) {
    }
}
