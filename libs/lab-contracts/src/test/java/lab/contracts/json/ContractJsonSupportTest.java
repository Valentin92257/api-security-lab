package lab.contracts.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractJsonSupportTest {

    private final JsonMapper mapper = ContractJsonSupport.createJsonMapper();

    static Stream<Arguments> invalidJson() {
        return Stream.of(
                Arguments.of(TextValue.class, "{\"value\":123}"),
                Arguments.of(TextValue.class, "{\"value\":true}"),
                Arguments.of(IntegerValue.class, "{\"value\":\"42\"}"),
                Arguments.of(IntegerValue.class, "{\"value\":\"\"}"),
                Arguments.of(IntegerValue.class, "{\"value\":42.0}"),
                Arguments.of(IntegerValue.class, "{\"value\":true}"),
                Arguments.of(DecimalValue.class, "{\"value\":\"1.5\"}"),
                Arguments.of(DecimalValue.class, "{\"value\":false}"),
                Arguments.of(BooleanValue.class, "{\"value\":\"true\"}"),
                Arguments.of(BooleanValue.class, "{\"value\":1}"),
                Arguments.of(EnumValue.class, "{\"value\":0}"),
                Arguments.of(PrimitiveValue.class, "{\"value\":null}"),
                Arguments.of(ArrayValue.class, "{\"value\":1}"),
                Arguments.of(IntegerValue.class, "{\"value\":[1]}"),
                Arguments.of(TextValue.class, "{\"value\":\"first\",\"value\":\"second\"}"),
                Arguments.of(MapValue.class, "{\"value\":{\"key\":\"first\",\"key\":\"second\"}}"),
                Arguments.of(TextValue.class, "{\"value\":\"text\"} {}"),
                Arguments.of(TextValue.class, "{'value':'text'}"),
                Arguments.of(TextValue.class, "{value:\"text\"}"),
                Arguments.of(TextValue.class, "{\"value\":\"text\",}"),
                Arguments.of(TextValue.class, "{/* comment */\"value\":\"text\"}"),
                Arguments.of(DecimalValue.class, "{\"value\":NaN}"),
                Arguments.of(TextValue.class, "{\"value\":\"text\",\"extra\":true}")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidJson")
    void rejectsAmbiguousOrMismatchedJson(Class<?> type, String json) {
        assertThatThrownBy(() -> mapper.readValue(json, type)).isInstanceOf(JacksonException.class);
    }

    @Test
    void preservesValidScalarTypesAndNullableReferences() {
        assertThat(mapper.readValue("{\"value\":42}", IntegerValue.class).value()).isEqualTo(42);
        assertThat(mapper.readValue("{\"value\":1}", DecimalValue.class).value()).isEqualTo(1.0);
        assertThat(mapper.readValue("{\"value\":true}", BooleanValue.class).value()).isTrue();
        assertThat(mapper.readValue("{\"value\":null}", TextValue.class).value()).isNull();
        assertThat(mapper.readValue("{\"value\":\"\"}", TextValue.class).value()).isEmpty();
    }

    @Test
    void appliesRequiredParsingRulesToSuppliedCodecMapper() {
        JsonMapper suppliedMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        var codec = new ContractJsonCodec(suppliedMapper, new ContractValidator());
        assertThatThrownBy(() -> codec.readCreateTrialRequest(
                "{\"families\":[\"BOLA\"],\"families\":[\"REPLAY\"]}"))
                .isInstanceOf(ContractValidationException.class);
        assertThat(codec.jsonMapper().isEnabled(SerializationFeature.INDENT_OUTPUT)).isTrue();
    }

    record TextValue(String value) {}
    record IntegerValue(Integer value) {}
    record DecimalValue(Double value) {}
    record BooleanValue(Boolean value) {}
    record PrimitiveValue(int value) {}
    record ArrayValue(List<Integer> value) {}
    record MapValue(Map<String, String> value) {}
    record EnumValue(Mode value) {}
    enum Mode { FIRST, SECOND }
}
